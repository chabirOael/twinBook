package io.github.chabiroael.twinbook

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.BlockerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S11, run only by hand (tools/app-instrument.sh RealSiteProbe), debug build, logged out: the web
 * shell loads the real mobile site's start page with uBlock Origin active, then reloads it with ad
 * hiding off, and reports for each load which hosts were contacted and how each request ended
 * (diag.netlog: host names, request types and Gecko error codes only; no URL, no body). Nothing on
 * the page is tapped or typed into; links are never followed (the opener only records). Every
 * page load is logged with its time for the report's traffic log. Two loads: ad hiding on, then
 * off. Ad hiding is switched on again at the end.
 */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class RealSiteProbe {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var shell: Shell

    private fun evidence(line: String) = Log.i("twinbook-evidence", line)

    private fun shellCmd(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(200)
        }
    }

    private fun request(method: String): JSONObject = runBlocking(Dispatchers.Main) { shell.engine.bridge.request(method) }

    /** One page load of the site, counted from just before [start] (test thread) to 20 s after it stopped loading. */
    private fun measuredLoad(label: String, start: () -> Unit): JSONObject {
        request("diag.netlog.start")
        val before = shell.session.page.value.loadCount
        evidence("S11 TRAFFIC LOG ${java.time.LocalTime.now()} page load of https://m.facebook.com/ ($label)")
        start()
        waitFor("load $label", 60_000) { shell.session.page.value.loadCount > before && !shell.session.page.value.loading }
        Thread.sleep(20_000)
        val hosts = request("diag.netlog.stop").getJSONObject("hosts")
        var seen = 0
        var aborted = 0
        val summary = StringBuilder()
        for (host in hosts.keys().asSequence().sorted()) {
            val byType = hosts.getJSONObject(host)
            for (type in byType.keys().asSequence().sorted()) {
                val c = byType.getJSONObject(type)
                val errors = c.getJSONObject("errors")
                seen += c.getInt("seen")
                aborted += errors.optInt("NS_ERROR_ABORT")
                summary.append("$host $type seen ${c.getInt("seen")} completed ${c.getInt("completed")}${if (errors.length() > 0) " errors $errors" else ""}; ")
            }
        }
        evidence("S11 $label: page ${shell.session.page.value.url?.substringBefore('?')} title '${shell.session.page.value.title}'; requests $seen, cancelled (NS_ERROR_ABORT) $aborted; by host: $summary")
        return hosts
    }

    @Test
    fun loggedOutStartPage() {
        val opener = ShellScreenTest.RecordingOpener()
        instrumentation.runOnMainSync {
            ShellSettings.get(instrumentation.targetContext).setStrict(false)
            shell = Shell.configureForTest(instrumentation.targetContext, ShellConfig.realSite(), opener)
        }
        waitFor("engine", 90_000) { shell.phase.value == Shell.Phase.Ready }
        check(shell.blocker.value is BlockerState.Ready) { "blocker ${shell.blocker.value}" }
        // The shell loads the site as soon as its screen is shown: the counter starts before that.
        measuredLoad("load 1, ad hiding on (uBlock Origin ${shell.blocker.value})") {
            compose.onNodeWithTag("open-shell").performClick()
            // Compose test frames advance only through the test API: let the shell screen appear.
            compose.onNodeWithTag("shell").assertIsDisplayed()
        }
        compose.waitForIdle()
        Thread.sleep(1_000)
        shellCmd("screencap -p /data/local/tmp/twinbook-s11-real-site.png")
        evidence("S11 screenshot /data/local/tmp/twinbook-s11-real-site.png")

        runBlocking(Dispatchers.Main) { shell.engine.setBlockerEnabled(false) }
        measuredLoad("load 2, ad hiding off (uBlock Origin ${shell.blocker.value})") { instrumentation.runOnMainSync { shell.session.reload() } }

        runBlocking(Dispatchers.Main) { shell.engine.setBlockerEnabled(true) }
        evidence("S11 ad hiding on again: ${shell.blocker.value}; outbound opens recorded (never followed): ${opener.browser.size + opener.system.size}")
    }
}
