package io.github.chabiroael.twinbook.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chabiroael.twinbook.engine.TestEngine.engine
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.bridge.BridgeException
import io.github.chabiroael.twinbook.engine.bridge.BridgeState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** G3: the bridge between Kotlin and the extension's background script. */
@RunWith(AndroidJUnit4::class)
class BridgeTest {
    private val bridge get() = engine.bridge

    @Before
    fun ready() {
        runBlocking { TestEngine.ready() }
    }

    @Test
    fun requestFromAppToExtension() = runBlocking {
        val params = JSONObject().put("n", 42).put("text", "é 😀 مرحبا").put("nested", JSONObject().put("ok", true))
        val result = bridge.request("diag.echo", params)
        // Values survive; key order does not (GeckoView converts JSON through GeckoBundle).
        assertEquals(42, result.getInt("n"))
        assertEquals("é 😀 مرحبا", result.getString("text"))
        assertEquals(true, result.getJSONObject("nested").getBoolean("ok"))
        evidence("G3 app->ext request diag.echo: $result")
    }

    @Test
    fun requestFromExtensionToApp() = runBlocking {
        bridge.handle("test.square") { p -> JSONObject().put("square", p.getInt("n") * p.getInt("n")) }
        val result = bridge.request("diag.callApp", JSONObject().put("method", "test.square").put("params", JSONObject().put("n", 7)))
        assertEquals(49, result.getJSONObject("result").getInt("square"))
        evidence("G3 ext->app request test.square(7): $result")
    }

    @Test
    fun errorsTravelBothWays() = runBlocking {
        try {
            bridge.request("no.such.method")
            fail("expected an error")
        } catch (e: BridgeException) {
            assertEquals("no_handler", e.code)
        }
        bridge.handle("test.fail") { throw BridgeException("bad_input", "rejected by the app") }
        try {
            bridge.request("diag.callApp", JSONObject().put("method", "test.fail"))
            fail("expected an error")
        } catch (e: BridgeException) {
            assertEquals("bad_input", e.code)
            assertEquals("rejected by the app", e.message)
        }
        evidence("G3 errors: app->ext no_handler, ext->app bad_input both delivered as BridgeException")
    }

    @Test
    fun eventsFromAppToExtension() = runBlocking {
        val run = TestEngine.newRun("notes")
        repeat(3) { bridge.send("diag.note", JSONObject().put("run", run).put("seq", it)) }
        val notes = bridge.request("diag.notes").getJSONArray("notes")
        val mine = (0 until notes.length()).map { notes.getJSONObject(it) }.filter { it.optString("run") == run }
        assertEquals(listOf(0, 1, 2), mine.map { it.getInt("seq") })
        evidence("G3 app->ext events: 3 sent, 3 received in order")
    }

    @Test
    fun eventsFromExtensionToApp() = runBlocking {
        val run = TestEngine.newRun("emit")
        val received = coroutineScope {
            val collector = async {
                withTimeout(15_000) {
                    bridge.events.filter { it.name == "test.ping" && it.data.optString("run") == run }.take(3).toList()
                }
            }
            bridge.request("diag.emit", JSONObject().put("name", "test.ping").put("count", 3).put("data", JSONObject().put("run", run)))
            collector.await()
        }
        assertEquals(listOf(0, 1, 2), received.map { it.data.getInt("seq") })
        evidence("G3 ext->app events: 3 emitted, 3 received in order")
    }

    @Test
    fun oneMegabytePayloadBothWays() = runBlocking {
        val big = buildString { while (length < 1_048_576) append("twinBook é 😀 مرحبا 中文 ") }
        val bytes = big.toByteArray(Charsets.UTF_8).size
        val t0 = System.nanoTime()
        val echoed = bridge.request("diag.echo", JSONObject().put("big", big), timeoutMs = 60_000)
        val appToExtMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(TestEngine.sha256(big), TestEngine.sha256(echoed.getString("big")))

        bridge.handle("test.echo") { it }
        val t1 = System.nanoTime()
        val viaApp = bridge.request(
            "diag.callApp",
            JSONObject().put("method", "test.echo").put("params", JSONObject().put("big", big)),
            timeoutMs = 60_000,
        )
        val extToAppMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(TestEngine.sha256(big), TestEngine.sha256(viaApp.getJSONObject("result").getString("big")))

        val run = TestEngine.newRun("bigevent")
        bridge.request("diag.emit", JSONObject().put("name", "test.big").put("count", 1).put("data", JSONObject().put("run", run).put("big", big)), 60_000)
        val event = TestEngine.awaitEvent("test.big") { it.optString("run") == run }
        assertEquals(TestEngine.sha256(big), TestEngine.sha256(event.data.getString("big")))
        evidence("G3 1 MB payload: ${big.length} chars / $bytes UTF-8 bytes; app->ext->app round trip ${appToExtMs} ms; ext->app->ext round trip ${extToAppMs} ms; ext->app event intact")
    }

    @Test
    fun nothingLostWhenSentBeforeTheOtherSideIsReady() = runBlocking {
        // App side: an event and a request were sent right after Engine.start, before the
        // extension was installed or the port existed.
        assertEquals(BridgeState.Disconnected, TestEngine.stateWhenEarlyMessagesSent)
        assertEquals("request", withTimeout(30_000) { TestEngine.earlyRequest.await() }.getString("early"))
        val notes = bridge.request("diag.notes").getJSONArray("notes")
        val early = (0 until notes.length()).map { notes.getJSONObject(it) }.filter { it.optBoolean("early") }
        assertEquals(1, early.size)

        // Extension side: the background script emitted bridge.startup and sent an
        // engine.info request at startup, before connectNative was even called.
        val startup = TestEngine.eventsNamed("bridge.startup") { true }
        assertEquals(1, startup.size)
        val earlyResult = bridge.request("diag.early")
        assertTrue(earlyResult.toString(), earlyResult.getBoolean("done"))
        assertEquals(Engine.GECKOVIEW_VERSION, earlyResult.getJSONObject("result").getString("geckoview"))
        evidence(
            "G3 early messages: app event+request sent in state ${TestEngine.stateWhenEarlyMessagesSent}, both delivered; " +
                "ext startup event received (${startup.single().data}); ext early request answered: ${earlyResult.getJSONObject("result")}",
        )
    }
}
