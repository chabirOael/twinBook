package io.github.chabiroael.twinbook

import android.content.Context
import android.content.res.AssetManager
import io.github.chabiroael.twinbook.data.DataStatus
import io.github.chabiroael.twinbook.engine.EngineStatus
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** Everything the M0 placeholder screen shows. Each line comes from a different part of the build. */
data class PlaceholderInfo(
    val appName: String,
    val appVersion: String,
    val engineLine: String,
    val dataLine: String,
    val extensionLine: String,
) {
    companion object {
        fun load(context: Context): PlaceholderInfo = PlaceholderInfo(
            appName = context.getString(R.string.app_name),
            appVersion = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            engineLine = EngineStatus().describe(),
            dataLine = DataStatus().describe(),
            extensionLine = extensionLine(TwinBridgeAssets.readVersion(context.assets)),
        )

        fun extensionLine(version: String?): String =
            if (version == null) "Extension: twin-bridge not packaged" else "Extension: twin-bridge $version"
    }
}

/** Reads the twin-bridge extension packaged in the APK assets. */
object TwinBridgeAssets {
    const val MANIFEST_PATH = "extensions/twin-bridge/manifest.json"

    /** The `version` from the packaged manifest, or null if it is missing or unreadable. */
    fun readVersion(assets: AssetManager): String? = try {
        val text = assets.open(MANIFEST_PATH).bufferedReader().use { it.readText() }
        JSONObject(text).optString("version").ifEmpty { null }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }
}
