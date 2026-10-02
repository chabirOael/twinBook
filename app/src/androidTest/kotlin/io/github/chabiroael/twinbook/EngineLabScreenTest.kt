package io.github.chabiroael.twinbook

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.await
import io.github.chabiroael.twinbook.engine.bridge.BridgeState
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView

@RunWith(AndroidJUnit4::class)
class EngineLabScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val timeoutMs = 60_000L

    /** The lab is one tap away from the start screen. */
    @Before
    fun openLab() {
        compose.onNodeWithTag("open-lab").performClick()
        compose.waitForIdle()
    }

    private fun packagedVersion(): String {
        // Read the manifest straight from the installed APK, independently of the app code.
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val manifest = assets.open("extensions/twin-bridge/manifest.json").bufferedReader().use { it.readText() }
        return JSONObject(manifest).getString("version")
    }

    @Test
    fun showsGeckoViewVersionAndBridgeState() {
        compose.onNodeWithText(LabText.gecko(Engine.GECKOVIEW_VERSION)).assertIsDisplayed()
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTextCount(LabText.bridge(BridgeState.Connected)) == 1 }
    }

    @Test
    fun showsExtensionVersionReportedOverTheBridge() {
        val version = packagedVersion()
        assertTrue("unexpected version '$version'", Regex("""\d+\.\d+\.\d+\.\d+""").matches(version))
        val expected = LabText.extension(version)
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTextCount(expected) == 1 }
        compose.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun rendersTheLabPageInGeckoView() {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTextCount("Page: loaded \"twinBook engine lab\"") == 1 }
        val view = compose.activity.window.decorView.findGeckoView()
        // The lab page's background is #1565c0. Sample the composited pixels (first frames on
        // the emulator's software GPU can lag, so retry for a while).
        val deadline = System.currentTimeMillis() + timeoutMs
        var pixel = 0
        while (System.currentTimeMillis() < deadline) {
            // capturePixels must be called on the main thread.
            var capture: org.mozilla.geckoview.GeckoResult<android.graphics.Bitmap>? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync { capture = view.capturePixels() }
            val bitmap = runBlocking { capture!!.await() }!!
            pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height - 10)
            if (close(pixel, Color.rgb(0x15, 0x65, 0xc0))) break
            Thread.sleep(500)
        }
        assertTrue("GeckoView pixel was #%06x".format(pixel and 0xffffff), close(pixel, Color.rgb(0x15, 0x65, 0xc0)))
        assertEquals(1, compose.onAllNodesWithTextCount("Page: loaded \"twinBook engine lab\""))
    }

    private fun close(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) < 8 && abs(Color.green(a) - Color.green(b)) < 8 && abs(Color.blue(a) - Color.blue(b)) < 8

    private fun View.findGeckoView(): GeckoView {
        if (this is GeckoView) return this
        if (this is ViewGroup) for (i in 0 until childCount) runCatching { return getChildAt(i).findGeckoView() }
        throw NoSuchElementException("no GeckoView")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
