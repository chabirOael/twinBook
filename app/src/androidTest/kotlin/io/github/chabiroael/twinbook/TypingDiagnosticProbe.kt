package io.github.chabiroael.twinbook

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.mockserver.MockServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView

/**
 * S10 diagnostic, run by tools/typing-diagnostic.sh: where do key events get lost or reordered?
 * On the mock typing page (the login page's two fields) in the capture browser, focus moves to the
 * first field (which empties the password field) and back to the password field (the soft keyboard restarts its input on every focus change), then `Hw-Pass1` is
 * sent as key events, [REPS] times per process, and every repetition is classified from what the
 * page received: ok, lost (keys missing), swapped (all keys, wrong order) or other.
 *
 * Arguments: `-e via input|instrumentation|dispatch` (how the key events are sent: the `input`
 * shell command and Instrumentation.sendKeySync both inject through the window's input pipeline,
 * which hands key events to the input method first; `dispatch` calls GeckoView.dispatchKeyEvent
 * directly, past that pipeline), `-e delayMs <n>` (wait after GeckoView offers the password
 * editor, default 0), `-e reps <n>` (default 10). Never fails on a lost key: it reports.
 */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class TypingDiagnosticProbe {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private lateinit var server: MockServer
    private lateinit var browser: CaptureBrowser
    private val run = "typing-${System.currentTimeMillis()}"
    private val text = "Hw-Pass1"

    @Before
    fun setUp() {
        server = MockServer().start()
        instrumentation.runOnMainSync {
            browser = CaptureBrowser.configureForTest(
                instrumentation.targetContext,
                CaptureBrowser.Config(server.url("/typing.html?run=$run"), server.url("/nav.html?run=$run-desktop"), listOf("mock")),
            )
        }
        compose.onNodeWithTag("open-capture").performClick()
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                Log.w(TAG, "timed out waiting for $what")
                return false
            }
            Thread.sleep(20)
        }
        return true
    }

    private fun geckoView(): GeckoView {
        fun View.find(): GeckoView? = when (this) {
            is GeckoView -> this
            is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).find() }
            else -> null
        }
        return compose.activity.window.decorView.find() ?: throw AssertionError("no GeckoView")
    }

    private fun tap(layout: JSONObject, id: String) {
        val r = layout.getJSONObject("els").getJSONArray(id)
        val view = geckoView()
        val loc = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(loc) }
        val scale = view.width / layout.getDouble("vw")
        shell("input tap ${(loc[0] + (r.getDouble(0) + r.getDouble(2) / 2) * scale).toInt()} ${(loc[1] + (r.getDouble(1) + r.getDouble(3) / 2) * scale).toInt()}")
    }

    private fun editorReady(password: Boolean): Boolean {
        var ready = false
        instrumentation.runOnMainSync {
            val info = EditorInfo()
            val ic = geckoView().onCreateInputConnection(info)
            val isPassword = (info.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD
            ready = ic != null && (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && isPassword == password
        }
        return ready
    }

    private fun events(): List<KeyEvent> = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()).toList()

    private fun send(via: String) {
        when (via) {
            "input" -> shell("input keyboard text $text")
            "instrumentation" -> for (e in events()) instrumentation.sendKeySync(KeyEvent(e))
            "dispatch" -> {
                val view = geckoView()
                for (e in events()) instrumentation.runOnMainSync { view.dispatchKeyEvent(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0)) }
            }
            else -> throw IllegalArgumentException("via $via")
        }
    }

    /** The password field's values in the order the page saw them (the page numbers its logs). */
    private fun passValues(): List<String> = server.logs(run).filter { it.first == "pass" }
        .map { it.second.substringBefore(':').toInt() to it.second.substringAfter(':') }
        .sortedBy { it.first }.map { it.second }

    /** ok, lost, swapped or other, from the last value the page reported. */
    private fun classify(value: String?): String = when {
        value == text -> "ok"
        value == null -> "lost"
        value.length < text.length && text.toList().containsAll(value.toList()) -> "lost"
        value.length == text.length && value.toList().sorted() == text.toList().sorted() -> "swapped"
        else -> "other"
    }

    @Test
    fun burst() {
        val via = args.getString("via") ?: "input"
        val delayMs = args.getString("delayMs")?.toLong() ?: 0L
        val reps = args.getString("reps")?.toInt() ?: REPS
        waitFor("layout") { server.logs(run).any { it.first == "layout" } }
        waitFor("page loaded") { !browser.current.page.value.loading }
        val layout = JSONObject(server.logs(run).last { it.first == "layout" }.second)
        Thread.sleep(1_000)
        val results = mutableListOf<String>()
        repeat(reps) { rep ->
            // Move the focus away and back, so the input method restarts on the password field.
            tap(layout, "email")
            waitFor("email focused") { server.logs(run).count { it == ("focus" to "email") } > rep }
            waitFor("email editor") { editorReady(password = false) }
            val before = passValues().size
            tap(layout, "pass")
            waitFor("password focused") { server.logs(run).count { it == ("focus" to "pass") } > rep }
            val t0 = SystemClock.uptimeMillis()
            waitFor("password editor") { editorReady(password = true) }
            val editorAfter = SystemClock.uptimeMillis() - t0
            if (delayMs > 0) Thread.sleep(delayMs)
            Log.i(TAG, "TYPING sending rep=${rep + 1} via=$via")
            send(via)
            Log.i(TAG, "TYPING sent rep=${rep + 1}")
            waitFor("password value", 3_000) { passValues().drop(before).lastOrNull()?.let { it.length >= text.length } == true }
            Thread.sleep(500)
            val got = passValues().drop(before).lastOrNull()
            val verdict = classify(got)
            results += verdict
            Log.i(TAG, "TYPING via=$via delayMs=$delayMs rep=${rep + 1} editorAfterMs=$editorAfter result=$verdict got=${got?.let { "'$it'" } ?: "nothing"} sequence=${passValues().drop(before)}")
        }
        val summary = results.groupingBy { it }.eachCount()
        Log.i(TAG, "TYPING SUMMARY via=$via delayMs=$delayMs reps=$reps $summary")
        println("TYPING SUMMARY via=$via delayMs=$delayMs reps=$reps $summary")
    }

    companion object {
        private const val TAG = "twinbook-evidence"
        private const val REPS = 10
    }
}
