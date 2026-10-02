package io.github.chabiroael.twinbook

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.EngineSession
import io.github.chabiroael.twinbook.engine.UserAgentProfile
import io.github.chabiroael.twinbook.mockserver.MockServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaves a capture session in the debug app's own store, for tools/capture-kill-test.sh (C7)
 * and tools/capture-pull.sh (C9). Instrumentation argument `capturePhase`:
 * - `finalized`: capture the mock secrets page, stop and finalize.
 * - `open`: capture the same page and return without stopping; the script then kills the
 *   process, so the session stays unfinalized.
 * Prints `CAPTURE_ID <id>` with tag twinbook-evidence.
 */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class CaptureProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var r: Result<T>? = null
        instrumentation.runOnMainSync { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    @Test
    fun leaveSession() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("capturePhase")
        assumeTrue("run by tools/capture-kill-test.sh and tools/capture-pull.sh", phase == "finalized" || phase == "open")
        val server = MockServer().start()
        val context = instrumentation.targetContext
        val engine = onMain { AppEngine.engine(context) }
        engine.awaitReady(90_000)
        val recorder = onMain { AppEngine.recorder(context) }
        val session: EngineSession = onMain { engine.newSession(UserAgentProfile.MOBILE, "probe") }
        val id = recorder.start(listOf("mock"), "probe-$phase-${System.currentTimeMillis()}")
        val run = "probe-${System.currentTimeMillis()}"
        withContext(Dispatchers.Main) { session.load(server.url("/secrets/page?run=$run")) }
        withContext(Dispatchers.IO) { server.awaitReport(run, 90_000) }
        delay(1_500)
        if (phase == "finalized") {
            val last = recorder.stop()
            assertTrue(last.message, last.finalized)
            Log.i("twinbook-evidence", "CAPTURE_ID $id finalized: $last")
        } else {
            Log.i("twinbook-evidence", "CAPTURE_ID $id left open with ${recorder.state.value}")
        }
        server.close()
    }
}
