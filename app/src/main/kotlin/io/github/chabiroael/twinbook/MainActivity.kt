package io.github.chabiroael.twinbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val lab = EngineLab.get(this)
        setContent {
            TwinBookTheme {
                EngineLabScreen(lab)
            }
        }
    }
}
