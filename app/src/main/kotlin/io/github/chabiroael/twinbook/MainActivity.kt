package io.github.chabiroael.twinbook

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.chabiroael.twinbook.engine.Engine

/**
 * The single activity. The `daily` build opens the web shell at once and keeps the developer
 * screens (start screen, capture browser, engine lab) behind the shell's menu. The debug build
 * opens the developer start screen, so launching it loads no site by itself.
 *
 * Configuration changes (rotation, dark mode, keyboard, size) do not recreate the activity
 * (manifest `configChanges`), and every session lives in process-wide objects anyway, so pages
 * are never reloaded by them.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DevOverrides.apply(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = if (BuildConfig.OPEN_SHELL_AT_START || DevOverrides.openShell) Screen.SHELL else Screen.START
        setContent {
            TwinBookTheme {
                var stack by rememberSaveable { mutableStack(root) }
                val push: (Screen) -> Unit = { stack = stack + it }
                val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
                BackHandler(enabled = stack.size > 1) { pop() }
                when (stack.last()) {
                    Screen.START -> StartScreen(shellIsRoot = root == Screen.SHELL, open = push, back = pop)
                    Screen.LAB -> EngineLabScreen(EngineLab.get(this))
                    Screen.CAPTURE -> CaptureBrowserScreen(CaptureBrowser.get(this)) { pop() }
                    Screen.SHELL -> ShellScreen(
                        Shell.get(this),
                        developerScreens = BuildConfig.DEBUG,
                        onSettings = { push(Screen.SETTINGS) },
                        onDeveloper = { if (root == Screen.SHELL) push(Screen.START) else pop() },
                        onExit = if (stack.size > 1) pop else null,
                    )
                    Screen.SETTINGS -> SettingsScreen(Shell.get(this), onDashboard = { push(Screen.DASHBOARD) }, onClose = pop)
                    Screen.DASHBOARD -> DashboardScreen(Shell.get(this), onClose = pop)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark mode and orientation reach Gecko without recreating anything.
        Engine.getOrNull()?.configurationChanged(newConfig)
    }

    override fun onStop() {
        super.onStop()
        Shell.getOrNull()?.saveNow()
    }
}

enum class Screen { START, LAB, CAPTURE, SHELL, SETTINGS, DASHBOARD }

/** The screen stack, saved as a list of names. */
private fun mutableStack(root: Screen) = androidx.compose.runtime.mutableStateOf(listOf(root))

/** Developer start screen: the web shell, the M1 engine lab and the M2a capture browser. */
@Composable
fun StartScreen(shellIsRoot: Boolean, open: (Screen) -> Unit, back: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("twinBook", style = MaterialTheme.typography.headlineMedium)
            Text("${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            if (shellIsRoot) {
                OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().testTag("back-to-shell")) { Text("Back to the web shell") }
            } else {
                Button(onClick = { open(Screen.SHELL) }, modifier = Modifier.fillMaxWidth().testTag("open-shell")) { Text("Web shell (real site)") }
            }
            Button(onClick = { open(Screen.CAPTURE) }, modifier = Modifier.fillMaxWidth().testTag("open-capture")) { Text("Capture browser") }
            Button(onClick = { open(Screen.LAB) }, modifier = Modifier.fillMaxWidth().testTag("open-lab")) { Text("Engine lab") }
        }
    }
}
