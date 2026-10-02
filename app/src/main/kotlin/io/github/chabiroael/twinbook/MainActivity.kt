package io.github.chabiroael.twinbook

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwinBookTheme {
                var screen by rememberSaveable { mutableScreen() }
                when (screen) {
                    Screen.START -> StartScreen { screen = it }
                    Screen.LAB -> {
                        BackHandler { screen = Screen.START }
                        EngineLabScreen(EngineLab.get(this))
                    }
                    Screen.CAPTURE -> CaptureBrowserScreen(CaptureBrowser.get(this)) { screen = Screen.START }
                }
            }
        }
    }
}

enum class Screen { START, LAB, CAPTURE }

private fun mutableScreen() = androidx.compose.runtime.mutableStateOf(Screen.START)

/** Simple start screen: the M1 engine lab and the M2a capture browser. */
@Composable
fun StartScreen(open: (Screen) -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("twinBook", style = MaterialTheme.typography.headlineMedium)
            Text("${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { open(Screen.CAPTURE) }, modifier = Modifier.fillMaxWidth().testTag("open-capture")) { Text("Capture browser") }
            Button(onClick = { open(Screen.LAB) }, modifier = Modifier.fillMaxWidth().testTag("open-lab")) { Text("Engine lab") }
        }
    }
}
