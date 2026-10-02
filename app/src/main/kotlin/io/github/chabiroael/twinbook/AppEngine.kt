package io.github.chabiroael.twinbook

import android.content.Context
import io.github.chabiroael.twinbook.capture.CaptureStore
import io.github.chabiroael.twinbook.engine.BlockerConfig
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.EngineConfig
import io.github.chabiroael.twinbook.engine.StartupMode
import io.github.chabiroael.twinbook.engine.capture.CaptureRecorder

/** The app's one engine and capture recorder. Main thread only. */
object AppEngine {
    const val TAG = "twinbook-app"

    /**
     * How the built-in extensions are installed at start: chosen in M3a from 20 cold starts of
     * each mode on the emulator (docs/SHELL.md section 5, docs/reports/M3a.md section 3).
     */
    val DEFAULT_STARTUP_MODE = StartupMode.ENSURE_BUILT_IN

    fun engine(context: Context): Engine = Engine.start(
        context.applicationContext,
        EngineConfig(
            debug = BuildConfig.DEBUG,
            // uBlock Origin; "ad hiding" in the shell's settings switches it on and off.
            blocker = BlockerConfig(enabled = ShellSettings.get(context).adHiding.value),
            startupMode = DevOverrides.startupMode ?: DEFAULT_STARTUP_MODE,
            // No password saving and no login autofill (there is no login storage either).
            configureRuntime = { it.loginAutofillEnabled(false) },
        ),
    )

    @Volatile
    private var recorder: CaptureRecorder? = null

    fun recorder(context: Context): CaptureRecorder = recorder ?: CaptureRecorder(
        engine(context),
        CaptureStore(TwinBookApp.capturesDir(context.applicationContext)),
        mapOf("applicationId" to BuildConfig.APPLICATION_ID, "version" to BuildConfig.VERSION_NAME, "buildType" to BuildConfig.BUILD_TYPE),
    ).also { recorder = it }
}
