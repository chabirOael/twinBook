package io.github.chabiroael.twinbook

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun TwinBookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** M0 placeholder: proves that `:app`, `:engine`, `:data` and the packaged extension are wired. */
@Composable
fun PlaceholderScreen(info: PlaceholderInfo) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(info.appName, style = MaterialTheme.typography.headlineMedium)
            Text(info.appVersion, style = MaterialTheme.typography.bodyLarge)
            Text(info.engineLine, style = MaterialTheme.typography.bodyLarge)
            Text(info.dataLine, style = MaterialTheme.typography.bodyLarge)
            Text(info.extensionLine, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    TwinBookTheme {
        PlaceholderScreen(
            PlaceholderInfo(
                appName = "twinBook",
                appVersion = "Version 0.1.0-debug (1)",
                engineLine = "Engine: placeholder, no browser engine yet",
                dataLine = "Data: placeholder, no models yet",
                extensionLine = PlaceholderInfo.extensionLine("0.1.0"),
            ),
        )
    }
}
