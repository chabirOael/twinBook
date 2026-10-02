package io.github.chabiroael.twinbook.engine.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * App side of the twin-bridge protocol: one long-lived native messaging port opened by the
 * extension's background script, carrying JSON messages. The message types are documented in
 * docs/ENGINE.md and in extension/src/lib/bridge.ts.
 *
 * - The extension sends `hello` on every new port; the bridge answers `welcome` and only then
 *   counts as [BridgeState.Connected].
 * - [send] and [request] may be called at any time, from any thread. Messages sent while
 *   disconnected are queued and flushed in order after the next welcome.
 * - Requests from the extension are answered by handlers registered with [handle]. A request
 *   for a method with no handler yet waits until one is registered (the extension side times
 *   out after 30 s).
 * - If the port disconnects, requests already sent on it fail with code `disconnected`.
 *
 * The bridge itself does not touch GeckoView; the engine connects it to a port through
 * [BridgeTransport].
 */
class Bridge internal constructor(
    private val scope: CoroutineScope,
    private val engineInfo: () -> JSONObject,
) {
    private val lock = Any()
    private var transport: BridgeTransport? = null
    private var ready = false
    private val queue = ArrayDeque<Outgoing>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val handlers = ConcurrentHashMap<String, suspend (JSONObject) -> Any?>()
    private val parked = mutableListOf<Pair<BridgeTransport, JSONObject>>()
    private val nextId = AtomicLong(1)

    private val stateFlow = MutableStateFlow(BridgeState.Disconnected)
    private val extensionFlow = MutableStateFlow<JSONObject?>(null)
    private val eventFlow = MutableSharedFlow<BridgeEvent>(
        replay = EVENT_REPLAY,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Connected once the extension's hello has been answered. */
    val state: StateFlow<BridgeState> = stateFlow.asStateFlow()

    /** The `extension` object of the most recent hello (id, version, marker, startedAt). */
    val extension: StateFlow<JSONObject?> = extensionFlow.asStateFlow()

    /**
     * Events from the extension. The last [EVENT_REPLAY] events are replayed to new collectors,
     * so a collector started shortly after an event still sees it. Match on content (for
     * example a request URL with a unique run id), not on arrival alone.
     */
    val events: SharedFlow<BridgeEvent> = eventFlow.asSharedFlow()

    /** Sends an event to the extension. Queued while disconnected. */
    fun send(name: String, data: JSONObject = JSONObject()) {
        enqueue(Outgoing(JSONObject().put("type", "event").put("name", name).put("data", data), null))
    }

    /**
     * Sends a request and suspends until the extension answers. Throws [BridgeException] with
     * code `timeout`, `disconnected`, or the error code the extension returned.
     */
    suspend fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        val id = "k${nextId.getAndIncrement()}"
        val p = Pending(method)
        pending[id] = p
        enqueue(Outgoing(JSONObject().put("type", "request").put("id", id).put("method", method).put("params", params), id))
        try {
            return withTimeout(timeoutMs) { p.result.await() }
        } catch (_: TimeoutCancellationException) {
            throw BridgeException("timeout", "request $method timed out after $timeoutMs ms")
        } finally {
            pending.remove(id)
            synchronized(lock) { queue.removeAll { it.requestId == id } }
        }
    }

    /**
     * Registers the handler for requests from the extension. The handler's return value is the
     * result: a JSONObject, or anything else, which is wrapped as `{"value": ...}`. Throwing
     * [BridgeException] answers with its code; any other exception with `handler_error`.
     */
    fun handle(method: String, handler: suspend (params: JSONObject) -> Any?) {
        handlers[method] = handler
        val ready = synchronized(lock) {
            val matching = parked.filter { it.second.optString("method") == method }
            parked.removeAll(matching)
            matching
        }
        ready.forEach { (t, m) -> answer(t, m) }
    }

    fun removeHandler(method: String) {
        handlers.remove(method)
    }

    // ---- transport side, called by the engine on the main thread ----------------------------

    internal fun onConnected(newTransport: BridgeTransport) {
        synchronized(lock) {
            transport = newTransport
            ready = false
        }
    }

    internal fun onDisconnected(oldTransport: BridgeTransport) {
        val failed = synchronized(lock) {
            if (transport !== oldTransport) return
            transport = null
            ready = false
            pending.values.filter { it.sentOn === oldTransport }
        }
        stateFlow.value = BridgeState.Disconnected
        failed.forEach { it.result.completeExceptionally(BridgeException("disconnected", "bridge disconnected before the response to ${it.method}")) }
    }

    internal fun onMessage(from: BridgeTransport, message: JSONObject) {
        when (message.optString("type")) {
            "hello" -> onHello(from, message)
            "event" -> eventFlow.tryEmit(
                BridgeEvent(message.optString("name"), message.optJSONObject("data") ?: JSONObject(), System.currentTimeMillis()),
            )
            "request" -> answer(from, message)
            "response" -> {
                val p = pending[message.optString("id")] ?: return
                if (message.optBoolean("ok")) {
                    val result = message.opt("result")
                    p.result.complete(result as? JSONObject ?: JSONObject().put("value", result))
                } else {
                    val error = message.optJSONObject("error") ?: JSONObject()
                    p.result.completeExceptionally(
                        BridgeException(error.optString("code", "error"), error.optString("message", "request ${p.method} failed")),
                    )
                }
            }
        }
    }

    private fun onHello(from: BridgeTransport, message: JSONObject) {
        val welcome = JSONObject().put("type", "welcome").put("protocol", PROTOCOL_VERSION).put("engine", engineInfo())
        synchronized(lock) {
            if (transport !== from) return
            // A repeated hello on the same port (the extension retries until welcomed) gets
            // another welcome but no second flush.
            from.post(welcome)
            if (ready) return
            ready = true
            val out = queue.toList()
            queue.clear()
            out.forEach { post(from, it) }
        }
        extensionFlow.value = message.optJSONObject("extension")
        stateFlow.value = BridgeState.Connected
    }

    private fun enqueue(message: Outgoing) {
        synchronized(lock) {
            val t = transport
            if (ready && t != null) post(t, message) else queue.addLast(message)
        }
    }

    /** Called with [lock] held. */
    private fun post(t: BridgeTransport, message: Outgoing) {
        if (message.requestId != null) {
            val p = pending[message.requestId] ?: return // timed out while queued
            p.sentOn = t
        }
        t.post(message.json)
    }

    private fun answer(from: BridgeTransport, message: JSONObject) {
        val id = message.optString("id")
        val method = message.optString("method")
        val handler = handlers[method]
        if (handler == null) {
            synchronized(lock) { parked += from to message }
            return
        }
        scope.launch {
            val response = JSONObject().put("type", "response").put("id", id)
            try {
                val result = handler(message.optJSONObject("params") ?: JSONObject())
                response.put("ok", true).put("result", result as? JSONObject ?: JSONObject().put("value", result ?: JSONObject.NULL))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeException) {
                response.put("ok", false).put("error", JSONObject().put("code", e.code).put("message", e.message))
            } catch (e: Exception) {
                response.put("ok", false).put("error", JSONObject().put("code", "handler_error").put("message", e.toString()))
            }
            synchronized(lock) {
                if (transport === from) from.post(response)
            }
        }
    }

    private class Outgoing(val json: JSONObject, val requestId: String?)

    private class Pending(val method: String) {
        val result = CompletableDeferred<JSONObject>()

        @Volatile
        var sentOn: BridgeTransport? = null
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val EVENT_REPLAY = 64
    }
}

enum class BridgeState { Disconnected, Connected }

/** An event the extension sent. */
data class BridgeEvent(val name: String, val data: JSONObject, val receivedAtMillis: Long)

class BridgeException(val code: String, message: String) : Exception(message)

/** Where the bridge writes messages. Implementations must keep the order of [post] calls. */
fun interface BridgeTransport {
    fun post(message: JSONObject)
}
