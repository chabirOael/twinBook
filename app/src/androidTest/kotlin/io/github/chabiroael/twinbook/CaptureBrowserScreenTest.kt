package io.github.chabiroael.twinbook

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.capture.CaptureRecorder
import io.github.chabiroael.twinbook.mockserver.MockServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView

/**
 * C10: the capture browser on the mock. Text input through an input method (what the on-screen
 * keyboard does: InputConnection.commitText) and through key events (what the emulator's host
 * keyboard produces), custom schemes and intents ignored, new windows loaded in place,
 * permissions denied, alert/confirm/prompt, back and reload. Taps go through `input tap` at the
 * positions the mock pages report for their elements.
 */
@RunWith(AndroidJUnit4::class)
class CaptureBrowserScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockServer
    private lateinit var browser: CaptureBrowser
    private val run = "c10-${System.currentTimeMillis()}"

    @Before
    fun setUp() {
        server = MockServer().start()
        instrumentation.runOnMainSync {
            browser = CaptureBrowser.configureForTest(
                instrumentation.targetContext,
                CaptureBrowser.Config(server.url("/login.html?run=$run"), server.url("/nav.html?run=$run-desktop"), listOf("mock")),
            )
        }
        compose.onNodeWithTag("open-capture").performClick()
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun evidence(line: String) = Log.i("twinbook-evidence", line)

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; page logs ${server.logs(run)} ${server.logs("$run-nav")}")
            Thread.sleep(100)
        }
    }

    private fun geckoView(): GeckoView {
        fun View.find(): GeckoView? = when (this) {
            is GeckoView -> this
            is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).find() }
            else -> null
        }
        return compose.activity.window.decorView.find() ?: throw AssertionError("no GeckoView")
    }

    private fun layoutCount(r: String) = server.logs(r).count { it.first == "layout" }

    /** Waits for the page of run [r] to report a new layout and returns it. */
    private fun awaitLayout(r: String, after: Int): JSONObject {
        waitFor("layout of $r") { layoutCount(r) > after }
        return JSONObject(server.logs(r).last { it.first == "layout" }.second)
    }

    /** Taps the centre of element [id] using the page-reported layout. */
    private fun tap(layout: JSONObject, id: String) {
        waitFor("no page dialog open") { browser.prompts.pending.value == null }
        compose.waitForIdle()
        val r = layout.getJSONObject("els").getJSONArray(id)
        val view = geckoView()
        val loc = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(loc) }
        val scale = view.width / layout.getDouble("vw")
        val x = loc[0] + (r.getDouble(0) + r.getDouble(2) / 2) * scale
        val y = loc[1] + (r.getDouble(1) + r.getDouble(3) / 2) * scale
        shell("input tap ${x.toInt()} ${y.toInt()}")
    }

    private fun log(r: String, field: String): String? = server.lastLog(r, field)

    @Test
    fun textInputFromInputMethodAndKeyEvents() {
        val layout = awaitLayout(run, 0)
        compose.onNodeWithTag("capture-start").performClick()
        waitFor("recording") { browser.recorder.state.value is CaptureRecorder.State.Recording }

        // On-screen keyboard path: the input method commits text through the InputConnection.
        tap(layout, "email")
        waitFor("email focused") { ("focus" to "email") in server.logs(run) }
        Thread.sleep(500)
        val view = geckoView()
        instrumentation.runOnMainSync {
            val ic = view.onCreateInputConnection(EditorInfo()) ?: throw AssertionError("no input connection")
            ic.commitText("ime-user@example.test", 1)
        }
        // Input events are logged by parallel requests, so they may arrive out of order.
        waitFor("email from the input method") { ("email" to "ime-user@example.test") in server.logs(run) }

        // Host keyboard path: key events from a keyboard input device, as the emulator window
        // delivers them.
        tap(layout, "pass")
        // Key events sent while the focus is still moving can be lost; wait until the page
        // reports the field focused.
        waitFor("password focused") { ("focus" to "pass") in server.logs(run) }
        Thread.sleep(500)
        shell("input keyboard text Hw-Pass1")
        waitFor("password from key events") { ("pass" to "Hw-Pass1") in server.logs(run) }
        evidence("C10 text input: email via InputConnection.commitText reached the page as 'ime-user@example.test'; password via keyboard key events (input keyboard text) reached it as 'Hw-Pass1'; input events logged: ${server.logs(run).count { it.first != "layout" }}")

        waitFor("records") { (browser.recorder.state.value as? CaptureRecorder.State.Recording)?.counters?.records ?: 0 > 0 }
        compose.waitForIdle()
        Thread.sleep(1_000)
        shell("screencap -p /data/local/tmp/twinbook-c10-capture-browser.png")
        evidence("C10 screenshot /data/local/tmp/twinbook-c10-capture-browser.png while ${browser.recorder.state.value}")
        // Stop with finalize from the UI.
        compose.onNodeWithTag("capture-stop").performClick()
        waitFor("finalized", 60_000) { (browser.recorder.state.value as? CaptureRecorder.State.Idle)?.last?.finalized == true }
        evidence("C10 capture stopped from the UI: ${browser.recorder.state.value}")
    }

    @Test
    fun navigationSafetyDialogsBackAndReload() {
        awaitLayout(run, 0)
        val navRun = "$run-nav"
        val session = browser.sessions.getValue(CaptureBrowser.Site.MOBILE)
        instrumentation.runOnMainSync { session.load(server.url("/nav.html?run=$navRun")) }
        var layout = awaitLayout(navRun, 0)
        val navUrl = server.url("/nav.html?run=$navRun")
        val pkg = instrumentation.targetContext.packageName

        for ((id, scheme) in listOf("scheme" to "fb", "intent" to "intent")) {
            val before = session.page.value.blockedLoads
            tap(layout, id)
            waitFor("$id blocked") { session.page.value.blockedLoads > before }
            Thread.sleep(1_000)
            assertEquals(scheme, session.page.value.lastBlockedScheme)
            assertEquals(navUrl, session.page.value.url)
            assertTrue("app left the foreground", shell("dumpsys activity activities").lines().any { "ResumedActivity" in it && pkg in it })
            evidence("C10 $id link: load ignored (scheme '$scheme'), still on ${session.page.value.url}, app in the foreground")
        }

        for ((id, marker) in listOf("newwin" to "newwin=1", "open" to "opened=1")) {
            val before = session.page.value.newWindowsInPlace
            val layouts = layoutCount(navRun)
            tap(layout, id)
            waitFor("$id in place") { session.page.value.url?.contains(marker) == true && !session.page.value.loading }
            assertEquals(before + 1, session.page.value.newWindowsInPlace)
            evidence("C10 $id: new window loaded in the same session: ${session.page.value.url}")
            // Back returns to the navigation page.
            compose.onNodeWithTag("back").performClick()
            layout = awaitLayout(navRun, layouts)
            assertEquals(navUrl, session.page.value.url)
        }
        evidence("C10 back: returned to $navUrl twice")

        val denied = session.page.value.permissionsDenied
        tap(layout, "geo")
        waitFor("geolocation answer") { log(navRun, "geo") != null }
        tap(layout, "notify")
        waitFor("notification answer") { log(navRun, "notify") != null }
        assertEquals("denied:1", log(navRun, "geo"))
        assertEquals("denied", log(navRun, "notify"))
        evidence("C10 permissions: geolocation ${log(navRun, "geo")}, notification ${log(navRun, "notify")}, denials counted ${session.page.value.permissionsDenied - denied}")

        tap(layout, "alert")
        compose.waitUntil(10_000) { browser.prompts.pending.value is PendingPrompt.Alert }
        assertEquals("mock alert", browser.prompts.pending.value!!.message)
        compose.onNodeWithTag("prompt-ok").performClick()
        waitFor("alert closed") { log(navRun, "alert") == "closed" }

        tap(layout, "confirm")
        compose.waitUntil(10_000) { browser.prompts.pending.value is PendingPrompt.Confirm }
        compose.onNodeWithTag("prompt-ok").performClick()
        waitFor("confirm true") { log(navRun, "confirm") == "true" }
        tap(layout, "confirm")
        compose.waitUntil(10_000) { browser.prompts.pending.value is PendingPrompt.Confirm }
        compose.onNodeWithTag("prompt-cancel").performClick()
        waitFor("confirm false") { log(navRun, "confirm") == "false" }

        tap(layout, "prompt")
        compose.waitUntil(10_000) { browser.prompts.pending.value is PendingPrompt.Text }
        compose.onNodeWithTag("prompt-text").performTextReplacement("typed answer")
        compose.onNodeWithTag("prompt-ok").performClick()
        waitFor("prompt answer") { log(navRun, "prompt") == "typed answer" }
        evidence("C10 dialogs: alert ${log(navRun, "alert")}, confirm OK then Cancel -> ${server.logs(navRun).filter { it.first == "confirm" }.map { it.second }}, prompt -> ${log(navRun, "prompt")}")

        val layouts = layoutCount(navRun)
        compose.onNodeWithTag("reload").performClick()
        awaitLayout(navRun, layouts)
        evidence("C10 reload: the page loaded again (layout reports ${layouts} -> ${layoutCount(navRun)})")
    }

    @Test
    fun siteSwitchKeepsBothSessions() {
        awaitLayout(run, 0)
        compose.onNodeWithTag("site-desktop").performClick()
        awaitLayout("$run-desktop", 0)
        val mobile = browser.sessions.getValue(CaptureBrowser.Site.MOBILE)
        val desktop = browser.sessions.getValue(CaptureBrowser.Site.DESKTOP)
        waitFor("desktop location") { desktop.page.value.url?.contains("/nav.html") == true }
        compose.onNodeWithTag("site-mobile").performClick()
        compose.waitForIdle()
        waitFor("mobile location") { mobile.page.value.url?.contains("/login.html") == true }
        assertEquals(1, server.requests.count { it.path == "/login.html" })
        val uaMobile = server.requests.first { it.path == "/login.html" }.header("User-Agent")
        val uaDesktop = server.requests.first { it.path == "/nav.html" }.header("User-Agent")
        assertTrue(uaMobile!!.contains("Mobile"))
        assertTrue(!uaDesktop!!.contains("Mobile"))
        evidence("C10 site switch: mobile session kept its page (no reload); UAs mobile '$uaMobile' desktop '$uaDesktop'")
    }
}
