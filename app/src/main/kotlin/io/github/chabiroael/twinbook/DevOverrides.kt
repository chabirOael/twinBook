package io.github.chabiroael.twinbook

import android.content.Intent
import android.util.Log
import io.github.chabiroael.twinbook.engine.StartupMode

/**
 * Launch options for device scripts, read from the launch intent of the debug build only
 * (BuildConfig.DEV_OVERRIDES is false in the daily build, which ignores them):
 *
 * - `twinbook.mockOrigin` (string, `http://127.0.0.1:<port>`): the shell shows the mock at that
 *   origin instead of the real site; with `adb reverse` it reaches a mock running on the host
 *   (tools/mock-host.sh).
 * - `twinbook.openShell` (boolean): open the shell at start instead of the developer start screen.
 * - `twinbook.startupMode` (`ENSURE_BUILT_IN` or `INSTALL_EVERY_START`): how the engine installs
 *   its built-in extensions (start-up measurement).
 *
 * Applied before the engine and the shell are created, so only the first launch of a process counts.
 */
object DevOverrides {
    @Volatile
    var mockOrigin: String? = null
        private set

    @Volatile
    var startupMode: StartupMode? = null

    @Volatile
    var openShell: Boolean = false
        private set

    fun apply(intent: Intent?) {
        if (!BuildConfig.DEV_OVERRIDES || intent == null) return
        intent.getStringExtra("twinbook.mockOrigin")?.let { origin ->
            require(Regex("^http://127\\.0\\.0\\.1:\\d+$").matches(origin)) { "mockOrigin must be http://127.0.0.1:<port>" }
            mockOrigin = origin
        }
        intent.getStringExtra("twinbook.startupMode")?.let { startupMode = StartupMode.valueOf(it) }
        if (intent.getBooleanExtra("twinbook.openShell", false)) openShell = true
        if (mockOrigin != null || startupMode != null || openShell) Log.i(AppEngine.TAG, "dev overrides: mock=$mockOrigin startupMode=$startupMode openShell=$openShell")
    }

    fun shellConfig(): ShellConfig? = mockOrigin?.let { ShellConfig.mock(it) }
}
