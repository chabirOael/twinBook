package io.github.chabiroael.twinbook

import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.capture.CaptureRecorder
import io.github.chabiroael.twinbook.mockserver.MockServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 *
 * Every test starts from the same state, whatever the previous test left behind: no capture
 * running or finalizing, no soft keyboard, the app's window focused (see [resetSharedState]).
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
        resetSharedState("before")
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
        try {
            resetSharedState("after")
        } finally {
            if (::server.isInitialized) server.close()
        }
    }

    /**
     * The recorder is process-wide and the soft keyboard outlives the activity, so a test that
     * fails half-way (while recording, or with a field focused) would otherwise break the next
     * test. Waits for a finalize to end, discards a running capture, hides the keyboard and
     * waits until the app's window has the focus, so taps are not injected into another window.
     */
    private fun resetSharedState(phase: String) {
        lateinit var recorder: CaptureRecorder
        instrumentation.runOnMainSync { recorder = AppEngine.recorder(instrumentation.targetContext) }
        settle("$phase: no capture finalizing", 60_000) { recorder.state.value !is CaptureRecorder.State.Finalizing }
        if (recorder.isRecording) {
            runBlocking(Dispatchers.Main) { recorder.discard() }
            Log.w("twinbook-evidence", "C10 $phase: a capture was still running and was discarded")
        }
        val activity = compose.activity
        val decor = activity.window.decorView
        instrumentation.runOnMainSync {
            activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(activity.window, decor).hide(WindowInsetsCompat.Type.ime())
        }
        settle("$phase: soft keyboard hidden", 10_000) {
            var shown = true
            instrumentation.runOnMainSync { shown = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            !shown
        }
        settle("$phase: app window focused", 10_000) { activity.hasWindowFocus() }
        assertTrue("$phase: recorder idle", recorder.state.value is CaptureRecorder.State.Idle)
    }

    private fun settle(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
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

    private fun documentLoads() = server.requests.count { it.path == "/login.html" }

    /**
     * Waits until GeckoView's input side has caught up with the page's focus: it offers an
     * input connection for a text field, a password field when [password]. The page reports
     * the focus before Gecko has told the Java side, and key events sent in between are lost.
     */
    private fun awaitEditor(what: String, password: Boolean) {
        val view = geckoView()
        waitFor("input connection for $what") {
            var ready = false
            instrumentation.runOnMainSync {
                val info = EditorInfo()
                val ic = view.onCreateInputConnection(info)
                val isPassword = (info.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD
                ready = ic != null && (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && isPassword == password
            }
            ready
        }
    }

    @Test
    fun textInputFromInputMethodAndKeyEvents() {
        awaitLayout(run, 0)
        val loads = documentLoads()
        compose.onNodeWithTag("capture-start").performClick()
        waitFor("recording") { browser.recorder.state.value is CaptureRecorder.State.Recording }
        // Capture start reloads the page (M2b). A tap before the reloaded page has finished
        // loading lands on the old page and its focus is lost, so wait for the reloaded
        // document's own layout report and tap at its positions.
        waitFor("reload after capture start") { documentLoads() > loads }
        val layout = awaitLayout(run, 1)
        waitFor("reloaded page loaded") { !browser.current.page.value.loading }
        evidence("C10 capture start reloaded the page (login.html loads $loads -> ${documentLoads()}); typing starts on the reloaded page")

        // On-screen keyboard path: the input method commits text through the InputConnection.
        tap(layout, "email")
        waitFor("email focused") { ("focus" to "email") in server.logs(run) }
        awaitEditor("email", password = false)
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
        awaitEditor("password", password = true)
        // Key events go through the input method before they reach the app. Until it has restarted
        // on the newly focused field, keys sent to it are lost or swapped (docs/reports/M3a.md
        // section 4), so wait for that, then type one key at a time, each waited for on the page,
        // as a person types. keyEventBurstDiagnostic reports the burst case.
        awaitInputMethodOn(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        typeKeyByKey("Hw-Pass1")
        evidence("C10 text input: email via InputConnection.commitText reached the page as 'ime-user@example.test'; password via keyboard key events (input keyboard text, one key at a time) reached it as 'Hw-Pass1'; input events logged: ${server.logs(run).count { it.first != "layout" }}")

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

    /**
     * Waits until the system's input method is bound to this app's field of [inputType] (its
     * `curEditorInfo` in `dumpsys input_method`), then for the restart that follows within about
     * 50 ms on a focus change.
     */
    private fun awaitInputMethodOn(inputType: Int) {
        val pkg = instrumentation.targetContext.packageName
        val type = "inputType=0x" + Integer.toHexString(inputType)
        waitFor("input method on the $type field", 15_000) {
            val dump = shell("dumpsys input_method")
            val editor = dump.substringAfter("curEditorInfo:", "").lines().take(8).joinToString(" ")
            type in editor && "packageName=$pkg " in "$editor "
        }
        Thread.sleep(400)
    }

    /** Sends [text] as key events, one character at a time, waiting until the page has each. */
    private fun typeKeyByKey(text: String) {
        for (i in 1..text.length) {
            val c = text[i - 1]
            shell(if (c == '-') "input keyboard keyevent KEYCODE_MINUS" else "input keyboard text $c")
            waitFor("'${text.substring(0, i)}' on the page", 10_000) { ("pass" to text.substring(0, i)) in server.logs(run) }
        }
    }

    /**
     * The burst variant of the key-event test, kept as a diagnostic: it sends `Hw-Pass1` in one
     * `input keyboard text` right after the password field has an input connection, and reports
     * what reached the page. It never fails on a lost or swapped key (S10, tools/typing-diagnostic.sh).
     */
    @Test
    fun keyEventBurstDiagnostic() {
        val layout = awaitLayout(run, 0)
        waitFor("page loaded") { !browser.current.page.value.loading }
        tap(layout, "pass")
        waitFor("password focused") { ("focus" to "pass") in server.logs(run) }
        awaitEditor("password", password = true)
        Thread.sleep(500)
        shell("input keyboard text Hw-Pass1")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && ("pass" to "Hw-Pass1") !in server.logs(run)) Thread.sleep(100)
        // The page logs every value in a request of its own, so they can arrive out of order:
        // judge from the set of values the field held, not from the last one to arrive.
        val values = server.logs(run).filter { it.first == "pass" }.map { it.second }
        val swapped = values.firstOrNull { it.length == 8 && it != "Hw-Pass1" && it.toList().sorted() == "Hw-Pass1".toList().sorted() }
        val result = when {
            "Hw-Pass1" in values -> "ok"
            swapped != null -> "swapped"
            else -> "lost"
        }
        val got = if (result == "ok") "Hw-Pass1" else swapped ?: values.maxByOrNull { it.length }
        evidence("C10 BURST DIAGNOSTIC result=$result got=${got?.let { "'$it'" } ?: "nothing"} sequence=$values")
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
    fun captureStartReloadsThePageSoItsDocumentIsRecorded() {
        awaitLayout(run, 0)
        assertEquals(1, server.requests.count { it.path == "/login.html" })
        compose.onNodeWithTag("capture-start").performClick()
        waitFor("reload after capture start") { server.requests.count { it.path == "/login.html" } == 2 }
        waitFor("page loaded again") { !browser.current.page.value.loading }
        compose.onNodeWithTag("capture-stop").performClick()
        waitFor("capture finalized", 60_000) { (browser.recorder.state.value as? CaptureRecorder.State.Idle)?.last != null }
        val last = (browser.recorder.state.value as CaptureRecorder.State.Idle).last!!
        assertTrue(last.message, last.finalized)
        val events = java.io.File(browser.recorder.store.dir(last.id), "events.ndjson").readLines().map { JSONObject(it) }
        val documents = events.filter { it.optString("ev") == "request" && it.optJSONObject("d")?.optString("type") == "main_frame" }
        assertTrue("main document request recorded", documents.any { it.getJSONObject("d").getString("url").contains("/login.html") })
        val bodies = events.filter { it.optString("ev") == "body" && it.optString("type") == "main_frame" }
        assertEquals("main document body recorded", 1, bodies.size)
        // The other site's session was not loaded or reloaded by the capture start.
        assertEquals(0, server.requests.count { it.path == "/nav.html" })
        evidence("F4 capture start: page reloaded (login.html requests 1 -> 2), session ${last.id} has ${documents.size} main_frame request(s) and ${bodies.size} document body")
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
