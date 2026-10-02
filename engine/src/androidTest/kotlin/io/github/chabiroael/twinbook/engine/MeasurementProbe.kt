package io.github.chabiroael.twinbook.engine

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory measurements for docs (section 5.6 of the M1 prompt): total PSS summed over all of
 * this app's processes at four stages. Skipped unless the instrumentation argument
 * `measure=1` is given, so it runs in a fresh process: tools/measure-memory.sh.
 */
@RunWith(AndroidJUnit4::class)
class MeasurementProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** PSS in KiB per process of this package, from dumpsys meminfo. */
    private fun pssByProcess(): Map<String, Long> {
        val pkg = instrumentation.targetContext.packageName
        val processes = shell("ps -A -o PID,NAME").lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1].startsWith(pkg)) parts[0] to parts[1] else null
        }
        return processes.associate { (pid, name) ->
            val out = shell("dumpsys meminfo $pid")
            val pss = Regex("TOTAL PSS:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLong()
                ?: Regex("(?m)^\\s*TOTAL\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLong()
                ?: -1L
            name.removePrefix(pkg).ifEmpty { "(main)" } to pss
        }
    }

    private suspend fun stage(name: String) {
        delay(5_000)
        System.gc()
        val byProcess = pssByProcess()
        evidence("MEASURE memory stage '$name': total PSS ${byProcess.values.sum() / 1024} MiB over ${byProcess.size} processes: ${byProcess.map { "${it.key}=${it.value / 1024}" }}")
    }

    @Test
    fun memoryByStage() = runBlocking {
        assumeTrue("memory measurement runs only from tools/measure-memory.sh", InstrumentationRegistry.getArguments().getString("measure") == "1")
        val t0 = SystemClock.elapsedRealtime()
        TestEngine.ready()
        evidence("MEASURE engine ready ${SystemClock.elapsedRealtime() - t0} ms after Engine.start in the test process")
        stage("runtime started, no session")

        val scenario = ActivityScenario.launch(TestHostActivity::class.java)
        lateinit var visible: EngineSession
        scenario.onActivity { activity ->
            visible = TestEngine.engine.newSession(UserAgentProfile.MOBILE, "visible")
            visible.attach(activity.geckoView)
        }
        visible.loadAndWait(server.url("/test.html?run=${TestEngine.newRun("mem")}&scenario=no-ads&transports=xhr,fetch"))
        stage("one visible session on the test page")

        val headless = TestEngine.newSession(UserAgentProfile.DESKTOP, "headless")
        headless.loadAndWait(server.url("/test.html?run=${TestEngine.newRun("mem")}&scenario=no-ads&transports=xhr,fetch"))
        stage("plus one headless session on the test page")

        withContext(Dispatchers.Main) { headless.close() }
        stage("after the headless session is closed")

        withContext(Dispatchers.Main) { visible.close() }
        scenario.close()
    }
}
