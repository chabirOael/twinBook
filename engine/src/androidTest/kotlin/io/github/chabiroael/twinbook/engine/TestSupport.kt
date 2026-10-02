package io.github.chabiroael.twinbook.engine

import android.app.Instrumentation
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.bridge.BridgeEvent
import io.github.chabiroael.twinbook.engine.bridge.BridgeState
import io.github.chabiroael.twinbook.mockserver.MockServer
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

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Writes an evidence line to logcat with tag twinbook-evidence. Scripts in tools/ and the
     * logcat files Gradle keeps per test collect these lines.
     */
    fun evidence(line: String) {
        Log.i("twinbook-evidence", line)
    }
}
