package io.github.chabiroael.twinbook

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.PageState
import io.github.chabiroael.twinbook.engine.bridge.BridgeState
import org.mozilla.geckoview.GeckoView

@Composable
fun TwinBookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** Plain development screen: engine status lines above a GeckoView showing the lab page. */
@Composable
fun EngineLabScreen(lab: EngineLab) {
    val bridge by lab.bridgeState.collectAsState()
    val version by lab.extensionVersion.collectAsState()
    val page by lab.page.collectAsState()
    val startup by lab.startup.collectAsState()
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("twinBook engine lab", style = MaterialTheme.typography.titleLarge)
                Text("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
                Text(LabText.gecko(Engine.GECKOVIEW_VERSION), style = MaterialTheme.typography.bodyMedium)
                Text(LabText.bridge(bridge), style = MaterialTheme.typography.bodyMedium)
                Text(LabText.extension(version), style = MaterialTheme.typography.bodyMedium)
                Text(LabText.page(page), style = MaterialTheme.typography.bodyMedium)
                Text(LabText.startup(startup), style = MaterialTheme.typography.bodyMedium)
            }
            AndroidView(
                factory = { context -> GeckoView(context).also { lab.session.attach(it) } },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            DisposableEffect(lab.session) {
                onDispose { lab.session.detach() }
            }
        }
    }
}

/** The status lines, kept apart so tests can build the expected strings. */
object LabText {
    fun gecko(version: String) = "GeckoView: $version"

    fun bridge(state: BridgeState) = when (state) {
        BridgeState.Connected -> "Bridge: connected"
        BridgeState.Disconnected -> "Bridge: disconnected"
    }

    fun extension(version: String?) =
        if (version == null) "Extension: waiting for the bridge" else "Extension: twin-bridge $version (reported over the bridge)"

    fun page(page: PageState) = when {
        page.crashed -> "Page: content process crashed"
        page.loading -> "Page: loading ${page.progress}%"
        page.lastLoadSucceeded == true -> "Page: loaded \"${page.title}\""
        page.lastLoadSucceeded == false -> "Page: failed to load"
        else -> "Page: not loaded"
    }

    fun startup(s: EngineLab.Startup) =
        "Start-up: extension ready ${s.extensionReadyMs ?: "…"} ms, first page ${s.firstPageMs ?: "…"} ms"
}
