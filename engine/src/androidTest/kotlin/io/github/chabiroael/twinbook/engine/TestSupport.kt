package io.github.chabiroael.twinbook.engine

import android.app.Instrumentation
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.capture.CaptureStore
import io.github.chabiroael.twinbook.engine.bridge.BridgeEvent
import io.github.chabiroael.twinbook.engine.capture.CaptureRecorder
import io.github.chabiroael.twinbook.engine.bridge.BridgeState
import io.github.chabiroael.twinbook.mockserver.MockServer
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * The engine and mock server shared by every instrumented test in this process. The engine is
 * started on first use; right after starting it, before the extension is even installed, it
 * sends an event and a request over the bridge (see BridgeTest), and starts recording every
 * bridge event from the very first one.
 */
object TestEngine {
    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

    val server: MockServer by lazy { MockServer().start() }

    /** Bridge state at the moment the early messages were sent. */
    lateinit var stateWhenEarlyMessagesSent: BridgeState
        private set

    lateinit var earlyRequest: Deferred<JSONObject>
        private set

    /** Every bridge event since engine start, in arrival order. */
    val allEvents: MutableList<BridgeEvent> = Collections.synchronizedList(mutableListOf())

    val engine: Engine by lazy {
        onMain {
            Engine.start(instrumentation.targetContext, EngineConfig(debug = true)).also { e ->
                stateWhenEarlyMessagesSent = e.bridge.state.value
                e.bridge.send("diag.note", JSONObject().put("early", true))
                earlyRequest = e.scope.async { e.bridge.request("diag.echo", JSONObject().put("early", "request")) }
                e.scope.launch { e.bridge.events.collect { allEvents += it } }
            }
        }
    }

    suspend fun ready(): ReadyInfo = engine.awaitReady(90_000)

    /** Capture recorder writing to this test app's private storage (captures-test/). */
    val recorder: CaptureRecorder by lazy {
        val store = CaptureStore(File(instrumentation.targetContext.filesDir, "captures-test"))
        onMain { CaptureRecorder(engine, store, mapOf("app" to "engine-test")) }
    }

    fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Total PSS in KiB over every process of this package (dumpsys meminfo per process). */
    fun totalPssKiB(): Long {
        val pkg = instrumentation.targetContext.packageName
        val pids = shell("ps -A -o PID,NAME").lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1].startsWith(pkg)) parts[0] else null
        }
        return pids.sumOf { pid ->
            val out = shell("dumpsys meminfo $pid")
            Regex("TOTAL PSS:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLong()
                ?: Regex("(?m)^\\s*TOTAL\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLong()
                ?: 0L
        }
    }

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private val runCounter = AtomicInteger()

    /** A run id unique in this process, used as the page's report key and in request URLs. */
    fun newRun(tag: String): String = "$tag-${runCounter.incrementAndGet()}-${System.currentTimeMillis()}"

    fun newSession(profile: UserAgentProfile = UserAgentProfile.MOBILE, name: String = "test"): EngineSession =
        onMain { engine.newSession(profile, name) }

    /** Loads the mock test page in [session] and returns the report it posts. */
    suspend fun runTestPage(session: EngineSession, run: String, query: String, timeoutMs: Long = 90_000): JSONObject {
        val url = server.url("/test.html?run=$run&$query")
        withContext(Dispatchers.Main) { session.load(url) }
        val raw = withContext(Dispatchers.IO) { server.awaitReport(run, timeoutMs) }
        return JSONObject(raw)
    }

    /** First bridge event (since engine start) with [name] matching [predicate]. */
    suspend fun awaitEvent(name: String, timeoutMs: Long = 30_000, predicate: (JSONObject) -> Boolean): BridgeEvent =
        withTimeout(timeoutMs) {
            synchronized(allEvents) { allEvents.firstOrNull { it.name == name && predicate(it.data) } }
                ?: engine.bridge.events.first { it.name == name && predicate(it.data) }
        }

    fun eventsNamed(name: String, predicate: (JSONObject) -> Boolean): List<BridgeEvent> =
        synchronized(allEvents) { allEvents.filter { it.name == name && predicate(it.data) } }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Writes an evidence line to logcat with tag twinbook-evidence. Scripts in tools/ and the
     * logcat files Gradle keeps per test collect these lines.
     */
    fun evidence(line: String) {
        Log.i("twinbook-evidence", line)
    }
}
