package io.github.chabiroael.twinbook.engine.capture

import android.util.Log
import io.github.chabiroael.twinbook.capture.CaptureItem
import io.github.chabiroael.twinbook.capture.CaptureStore
import io.github.chabiroael.twinbook.capture.FinalizeResult
import io.github.chabiroael.twinbook.capture.Finalizer
import io.github.chabiroael.twinbook.capture.Secret
import io.github.chabiroael.twinbook.capture.SessionWriter
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.bridge.BridgeException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs capture sessions: tells the extension to record (`capture.start`), writes the batches it
 * sends (`capture.write`) to the [store] in order, and on stop runs the finalize pass with the
 * secrets the extension remembered (layer 2). See docs/CAPTURE.md.
 *
 * - Batches are written one at a time under a lock; the extension waits for each answer, so
 *   nothing is lost or reordered.
 * - If the extension restarts during a capture, its remembered secrets are gone, so the session
 *   can never be scrubbed: it is deleted at once.
 * - If the process dies, the session stays unfinalized on disk and is deleted at the next app
 *   start ([CaptureStore.deleteUnfinalized], called by the app).
 *
 * One recorder per engine. Construct it on the main thread.
 */
class CaptureRecorder(
    private val engine: Engine,
    val store: CaptureStore,
    /** Facts about the app written into session.json (for example its version). */
    private val appInfo: Map<String, Any?> = emptyMap(),
) {
    sealed interface State {
        /** No capture running. [last] describes the most recent session of this process, if any. */
        data class Idle(val last: LastSession? = null) : State

        data class Recording(val id: String, val profiles: List<String>, val startedAt: Long, val counters: Counters) : State

        data class Finalizing(val id: String, val done: Int, val total: Int) : State
    }

    data class Counters(
        val records: Long = 0,
        val bytes: Long = 0,
        val bodies: Long = 0,
        val redactions: Long = 0,
        val errors: Long = 0,
    )

    data class LastSession(val id: String, val finalized: Boolean, val message: String, val files: Int = 0, val bytes: Long = 0, val replacements: Int = 0)

    private val stateFlow = MutableStateFlow<State>(State.Idle())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private val lock = Mutex()
    private var writer: SessionWriter? = null
    private var current: State.Recording? = null
    private var extensionStartedAt: Long = 0
    private var extensionCounters = JSONObject()

    init {
        engine.bridge.handle("capture.write") { params -> write(params) }
        engine.scope.launch {
            engine.bridge.extension.collect { hello ->
                val startedAt = hello?.optLong("startedAt") ?: return@collect
                val rec = current ?: return@collect
                if (extensionStartedAt != 0L && startedAt != extensionStartedAt) abort(rec.id, "the extension restarted during the capture; its secrets are gone, so the session was deleted")
            }
        }
    }

    val isRecording: Boolean get() = current != null

    /** Starts a capture of [profiles] ("site" or "mock"). Returns the session id. */
    suspend fun start(profiles: List<String>, id: String = newId(profiles)): String {
        check(current == null) { "a capture is already running" }
        val w = withContext(Dispatchers.IO) { store.create(id) }
        writer = w
        val result = try {
            engine.bridge.request("capture.start", JSONObject().put("sessionId", id).put("profiles", JSONArray(profiles)))
        } catch (e: Exception) {
            writer = null
            withContext(Dispatchers.IO) {
                w.close()
                store.delete(id)
            }
            throw e
        }
        extensionStartedAt = result.optLong("extensionStartedAt")
        extensionCounters = JSONObject()
        val rec = State.Recording(id, profiles, result.optLong("startedAt"), Counters())
        current = rec
        stateFlow.value = rec
        Log.i(TAG, "capture $id started (${profiles.joinToString()})")
        return id
    }

    /** Stops the running capture and finalizes it. The session is either finalized or deleted. */
    suspend fun stop(): LastSession {
        val rec = current ?: throw IllegalStateException("no capture is running")
        stateFlow.value = State.Finalizing(rec.id, 0, 1)
        val stop = try {
            engine.bridge.request("capture.stop", JSONObject().put("sessionId", rec.id), STOP_TIMEOUT_MS)
        } catch (e: BridgeException) {
            return abort(rec.id, "the extension did not answer capture.stop (${e.code}); the session cannot be scrubbed and was deleted")
        }
        val secrets = stop.optJSONArray("secrets").toSecrets()
        stop.remove("secrets")
        val w = lock.withLock { writer.also { writer = null } } ?: return abort(rec.id, "session writer missing")
        current = null
        val meta = LinkedHashMap<String, Any?>()
        meta["profiles"] = rec.profiles
        meta["startedAt"] = rec.startedAt
        meta["stoppedAt"] = System.currentTimeMillis()
        meta["complete"] = stop.optBoolean("ok")
        meta["app"] = appInfo
        meta["geckoview"] = Engine.GECKOVIEW_VERSION
        meta["extension"] = engine.bridge.extension.value?.let { mapOf("version" to it.optString("version"), "marker" to it.optString("marker")) }
        meta["extensionReport"] = stop.toMap()
        val result: FinalizeResult = withContext(Dispatchers.IO) {
            Finalizer.finalize(store, w, secrets, meta) { done, total -> stateFlow.value = State.Finalizing(rec.id, done, total) }
        }
        val last = LastSession(rec.id, result.ok, result.message, result.files, result.bytes, result.replacements.values.sum())
        stateFlow.value = State.Idle(last)
        Log.i(TAG, "capture ${rec.id}: ${result.message}, ${result.files} files, ${result.bytes} bytes, ${last.replacements} taint replacements")
        return last
    }

    /** Stops the running capture without keeping anything. */
    suspend fun discard(): LastSession {
        val rec = current ?: throw IllegalStateException("no capture is running")
        try {
            engine.bridge.request("capture.discard", JSONObject().put("sessionId", rec.id), 10_000)
        } catch (_: BridgeException) {
            // The session is deleted either way.
        }
        return abort(rec.id, "discarded")
    }

    private suspend fun abort(id: String, message: String): LastSession {
        current = null
        val w = lock.withLock { writer.also { writer = null } }
        withContext(Dispatchers.IO) {
            w?.close()
            store.delete(id)
        }
        Log.w(TAG, "capture $id: $message")
        return LastSession(id, false, message).also { stateFlow.value = State.Idle(it) }
    }

    private suspend fun write(params: JSONObject): JSONObject {
        val id = params.optString("sessionId")
        val seq = params.optLong("seq")
        val items = params.optJSONArray("items") ?: JSONArray()
        val parsed = ArrayList<CaptureItem>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            parsed += if (o.has("l")) CaptureItem.Line(o.getString("l")) else CaptureItem.Body(o.getString("f"), Base64.getDecoder().decode(o.getString("b")), o.optBoolean("last"))
        }
        val written = lock.withLock {
            val w = writer
            if (w == null || w.id != id) return JSONObject().put("dropped", true)
            withContext(Dispatchers.IO) { w.append(seq, parsed) }
            w.counters
        }
        params.optJSONObject("counters")?.let { extensionCounters = it }
        val rec = current
        if (rec != null && rec.id == id) {
            val updated = rec.copy(
                counters = Counters(
                    records = written.lines,
                    bytes = written.bytes,
                    bodies = written.bodies,
                    redactions = extensionCounters.optLong("redactions"),
                    errors = extensionCounters.optLong("errors") + written.errors,
                ),
            )
            current = updated
            if (stateFlow.value is State.Recording) stateFlow.value = updated
        }
        return JSONObject().put("written", true).put("seq", seq)
    }

    private fun JSONArray?.toSecrets(): List<Secret> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { Secret(it.optString("value"), it.optString("label")) } }
    }

    companion object {
        const val TAG = "twinbook-capture"
        const val STOP_TIMEOUT_MS = 180_000L

        fun newId(profiles: List<String>): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + "-" + profiles.joinToString("-")
    }
}

/** JSONObject to plain Kotlin maps and lists, for session.json. */
fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k -> unwrap(opt(k)) }

private fun unwrap(v: Any?): Any? = when (v) {
    is JSONObject -> v.toMap()
    is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
    JSONObject.NULL -> null
    else -> v
}
