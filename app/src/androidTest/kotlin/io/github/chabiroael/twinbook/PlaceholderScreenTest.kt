package io.github.chabiroael.twinbook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.data.DataStatus
import io.github.chabiroael.twinbook.engine.EngineStatus
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceholderScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsEngineLine() {
        compose.onNodeWithText(EngineStatus().describe()).assertIsDisplayed()
    }

    @Test
    fun showsDataLine() {
        compose.onNodeWithText(DataStatus().describe()).assertIsDisplayed()
    }

    @Test
    fun showsVersionOfPackagedExtension() {
        // Read the manifest straight from the installed APK, independently of the app code.
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val manifest = assets.open("extensions/twin-bridge/manifest.json").bufferedReader().use { it.readText() }
        val version = JSONObject(manifest).getString("version")
        assertTrue("unexpected version '$version'", Regex("""\d+\.\d+\.\d+""").matches(version))

        compose.onNodeWithText("Extension: twin-bridge $version").assertIsDisplayed()
    }
}
