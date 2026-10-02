package io.github.chabiroael.twinbook

import android.content.Context
import android.util.Log
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.EngineSession
import io.github.chabiroael.twinbook.engine.UserAgentProfile
import io.github.chabiroael.twinbook.engine.capture.CaptureRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide state of the capture browser: one session per site position (mobile site with
 * the mobile user agent, desktop site with the desktop user agent), both kept alive, and the
 * capture recorder. Survives activity recreation. Main thread only.
 */
class CaptureBrowser private constructor(context: Context, val config: Config) {
    enum class Site { MOBILE, DESKTOP }

    /** Start pages and the profile that is captured. Tests use the mock server. */
    data class Config(val mobileUrl: String, val desktopUrl: String, val captureProfiles: List<String>) {
        companion object {
            val REAL_SITE = Config("https://m.facebook.com/", "https://www.facebook.com/", listOf("site"))
        }
    }

    val engine: Engine = AppEngine.engine(context)
    val recorder: CaptureRecorder = AppEngine.recorder(context)
    val prompts = Prompts()

    val sessions: Map<Site, EngineSession> = mapOf(
        Site.MOBILE to engine.newSession(UserAgentProfile.MOBILE, "capture-mobile"),
        Site.DESKTOP to engine.newSession(UserAgentProfile.DESKTOP, "capture-desktop"),
    ).onEach { (_, s) -> s.promptDelegate = prompts }

    private val siteFlow = MutableStateFlow(Site.MOBILE)
    val site: StateFlow<Site> = siteFlow.asStateFlow()

    private val readyFlow = MutableStateFlow(false)

    /** True once the engine reported ready; no site page loads before that. */
    val ready: StateFlow<Boolean> = readyFlow.asStateFlow()

    private val started = mutableSetOf<Site>()

    init {
        engine.scope.launch {
            // Pages loaded before the extension runs would not be seen by it.
            engine.awaitReady()
            readyFlow.value = true
            startIfNeeded(siteFlow.value)
        }
    }

    val current: EngineSession get() = sessions.getValue(siteFlow.value)

    fun select(site: Site) {
        siteFlow.value = site
        startIfNeeded(site)
    }

    private fun startIfNeeded(site: Site) {
        if (!readyFlow.value || !started.add(site)) return
        val url = if (site == Site.MOBILE) config.mobileUrl else config.desktopUrl
        Log.i(AppEngine.TAG, "capture browser: first load of the $site site")
        sessions.getValue(site).load(url)
    }

    fun back() {
        current.goBack()
    }

    fun reload() {
        current.reload()
    }

    /**
     * Starts a capture, then reloads the current site's page so that its document is recorded
     * (the owner's first captures had none). Only the visible site is reloaded: reloading the
     * heavy desktop page in the background as well once exhausted the emulator. If the current
     * site has not loaded yet, its first load happens now. Main thread only.
     */
    suspend fun startCapture(): String {
        val id = recorder.start(config.captureProfiles)
        val site = siteFlow.value
        if (site in started) {
            Log.i(AppEngine.TAG, "capture browser: capture $id started, reloading the $site site")
            current.reload()
        } else {
            startIfNeeded(site)
        }
        return id
    }

    companion object {
        @Volatile
        private var instance: CaptureBrowser? = null

        fun get(context: Context): CaptureBrowser = instance ?: CaptureBrowser(context.applicationContext, Config.REAL_SITE).also { instance = it }

        /** Replaces the instance with one using [config] (instrumented tests). Main thread only. */
        fun configureForTest(context: Context, config: Config): CaptureBrowser {
            instance?.sessions?.values?.forEach { it.close() }
            return CaptureBrowser(context.applicationContext, config).also { instance = it }
        }
    }
}
