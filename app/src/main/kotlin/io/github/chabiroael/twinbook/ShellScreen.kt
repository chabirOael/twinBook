package io.github.chabiroael.twinbook

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.chabiroael.twinbook.engine.BlockerState
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.EngineSession
import io.github.chabiroael.twinbook.engine.ExtensionState
import io.github.chabiroael.twinbook.engine.NavigationDecision
import io.github.chabiroael.twinbook.engine.NavigationPolicy
import io.github.chabiroael.twinbook.engine.UserAgentProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView

/**
 * The web shell: the site in a GeckoView, edge to edge, with no address bar. A plain splash until
 * the engine is ready and the first page is drawn, a thin loading bar that appears only for loads
 * longer than [LOADING_BAR_DELAY_MS], a small overflow button, and a recovery view when the
 * content process is gone. Back goes back in the page's history; with nothing to go back to, the
 * system handles it (predictive back to home), or [onExit] returns to the developer screens.
 */
@Composable
fun ShellScreen(shell: Shell, developerScreens: Boolean, onSettings: () -> Unit, onDeveloper: () -> Unit, onExit: (() -> Unit)?) {
    val page by shell.session.page.collectAsState()
    val phase by shell.phase.collectAsState()
    val shown by shell.shown.collectAsState()
    val prompt by shell.prompts.pending.collectAsState()

    BackHandler(enabled = page.canGoBack && !page.crashed) { shell.back() }
    if (onExit != null) BackHandler(enabled = !page.canGoBack || page.crashed) { onExit() }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("shell")) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            GeckoViewFor(shell.session, Modifier.fillMaxSize().testTag("shell-gecko"), onLaidOut = shell::viewLaidOut)
            LoadingBar(page.loading && shown, page.progress)
            prompt?.let { Box(Modifier.align(Alignment.TopCenter)) { PromptPanel(it) } }
            if (page.crashed) RecoveryView { shell.recover() }
            if (shown || phase is Shell.Phase.Failed) OverflowMenu(shell, developerScreens, onSettings, onDeveloper, Modifier.align(Alignment.BottomEnd))
        }
        if (!shown) Splash(phase, shell)
    }
}

/** Shows [session] in a GeckoView; the session outlives the view (rotation, screen changes). */
@Composable
fun GeckoViewFor(session: EngineSession, modifier: Modifier, onLaidOut: () -> Unit = {}) {
    AndroidView(
        factory = { context ->
            GeckoView(context).also {
                it.setAutofillEnabled(false)
                it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                it.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (v.width > 0 && v.height > 0) onLaidOut() }
            }
        },
        update = { view -> if (view.session !== session.geckoSession) session.attach(view) },
        modifier = modifier,
    )
    DisposableEffect(session) { onDispose { session.detach() } }
}

@Composable
private fun LoadingBar(loading: Boolean, progress: Int) {
    // Shown only for loads that take a while, so quick navigations do not flash a bar.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(LOADING_BAR_DELAY_MS)
            visible = true
        } else {
            visible = false
        }
    }
    if (visible) {
        LinearProgressIndicator(
            progress = { (progress.coerceIn(5, 100)) / 100f },
            modifier = Modifier.fillMaxWidth().height(2.dp).testTag("shell-loading"),
        )
    }
}

@Composable
private fun Splash(phase: Shell.Phase, shell: Shell) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("shell-splash"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Text("twinBook", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            when (phase) {
                is Shell.Phase.Failed -> {
                    Text("The browser engine did not start: ${phase.message}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("shell-failed"))
                    if (shell.blocker.collectAsState().value is BlockerState.Failed) {
                        OutlinedButton(onClick = { shell.engine.scope.launch { shell.settings.setAdHiding(false) } }) { Text("Turn ad hiding off and restart the app") }
                    }
                }
                else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun RecoveryView(onReload: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("shell-crashed"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
            Text("The page stopped working.", style = MaterialTheme.typography.titleMedium)
            Text("Its browser process ended. Reloading brings back the page you were on.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onReload, modifier = Modifier.testTag("shell-recover")) { Text("Reload") }
        }
    }
}

@Composable
private fun OverflowMenu(shell: Shell, developerScreens: Boolean, onSettings: () -> Unit, onDeveloper: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier.padding(10.dp)) {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            shadowElevation = 2.dp,
            modifier = Modifier.size(36.dp).testTag("shell-menu"),
        ) {
            Box(contentAlignment = Alignment.Center) { Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Reload") }, onClick = { open = false; shell.reload() }, modifier = Modifier.testTag("menu-reload"))
            DropdownMenuItem(text = { Text("Home") }, onClick = { open = false; shell.home() }, modifier = Modifier.testTag("menu-home"))
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() }, modifier = Modifier.testTag("menu-settings"))
            if (developerScreens) DropdownMenuItem(text = { Text("Developer screens") }, onClick = { open = false; onDeveloper() }, modifier = Modifier.testTag("menu-developer"))
        }
    }
}

/** Minimal settings: ad hiding, strict mode, uBlock Origin's own dashboard, version information. */
@Composable
fun SettingsScreen(shell: Shell, onDashboard: () -> Unit, onClose: () -> Unit) {
    val adHiding by shell.settings.adHiding.collectAsState()
    val strict by shell.settings.strict.collectAsState()
    val blocker by shell.blocker.collectAsState()
    val extension by shell.engine.extension.collectAsState()
    val scope = shell.engine.scope
    BackHandler { onClose() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.testTag("settings-close")) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        SettingSwitch(
            title = "Ad hiding",
            detail = "uBlock Origin hides ads and blocks trackers on the site. " + when (blocker) {
                is BlockerState.Ready -> "Active."
                is BlockerState.Starting, BlockerState.Installing -> "Starting…"
                is BlockerState.Disabled -> "Off."
                is BlockerState.Failed -> "Failed to start."
                BlockerState.Absent -> ""
            },
            checked = adHiding,
            tag = "setting-ad-hiding",
        ) { on -> scope.launch { shell.setAdHiding(on) } }
        SettingSwitch(
            title = "Strict mode",
            detail = "Also stop the site's own logging beacons (twin-bridge). Never active while a capture runs.",
            checked = strict,
            tag = "setting-strict",
        ) { on -> scope.launch { runCatching { shell.setStrict(on) } } }
        OutlinedButton(onClick = onDashboard, enabled = shell.engine.blockerWebExtension != null && adHiding, modifier = Modifier.fillMaxWidth().testTag("setting-dashboard")) {
            Text("uBlock Origin dashboard")
        }
        HorizontalDivider()
        Text("Versions", style = MaterialTheme.typography.titleMedium)
        Text(
            versionText(shell.engine, extension, blocker),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("setting-versions"),
        )
    }
}

private fun versionText(engine: Engine, extension: ExtensionState, blocker: BlockerState): String = buildString {
    appendLine("twinBook ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}), license GPL-3.0-or-later")
    appendLine("GeckoView ${Engine.GECKOVIEW_VERSION}, Mozilla Public License 2.0")
    appendLine("twin-bridge ${(extension as? ExtensionState.Installed)?.version ?: "not installed"}")
    val installed = engine.blockerWebExtension?.metaData?.version
    appendLine("uBlock Origin ${installed ?: BuildConfig.UBLOCK_ORIGIN_VERSION} (packaged ${BuildConfig.UBLOCK_ORIGIN_VERSION}), ${blocker::class.simpleName}")
    appendLine("  license GPL-3.0, source https://github.com/gorhill/uBlock (release ${BuildConfig.UBLOCK_ORIGIN_VERSION})")
    append("  release asset SHA-256 ${BuildConfig.UBLOCK_ORIGIN_SHA256}")
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * uBlock Origin's own dashboard (its options page) in a session of its own. Its pages load; any
 * http(s) link from them opens in the browser.
 */
@Composable
fun DashboardScreen(shell: Shell, onClose: () -> Unit) {
    val session = remember {
        shell.engine.newSession(UserAgentProfile.MOBILE, "ublock-dashboard", extraSchemes = setOf("moz-extension")).also { s ->
            s.navigationPolicy = NavigationPolicy { req ->
                if (req.uri.startsWith("http:") || req.uri.startsWith("https:")) {
                    shell.opener.openInBrowser(req.uri)
                    NavigationDecision.Deny
                } else {
                    NavigationDecision.Allow
                }
            }
            val meta = shell.engine.blockerWebExtension?.metaData
            val url = DevOverrides.dashboardPage?.let { page -> meta?.baseUrl?.let { it + page } } ?: meta?.optionsPageUrl
            url?.let { s.load(it) }
        }
    }
    val page by session.page.collectAsState()
    BackHandler { if (page.canGoBack) session.goBack() else onClose() }
    DisposableEffect(session) {
        shell.dashboardSession = session
        onDispose {
            if (shell.dashboardSession === session) shell.dashboardSession = null
            session.close()
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing).testTag("dashboard")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.testTag("dashboard-close")) { Text("← Back") }
            Text("uBlock Origin", style = MaterialTheme.typography.titleMedium)
        }
        GeckoViewFor(session, Modifier.fillMaxSize().testTag("dashboard-gecko"))
    }
}

private const val LOADING_BAR_DELAY_MS = 400L
