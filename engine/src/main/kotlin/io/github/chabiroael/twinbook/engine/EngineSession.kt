package io.github.chabiroael.twinbook.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/** User-agent profile of a session. */
enum class UserAgentProfile(internal val userAgentMode: Int, internal val viewportMode: Int) {
    /** GeckoView's mobile user agent and viewport. */
    MOBILE(GeckoSessionSettings.USER_AGENT_MODE_MOBILE, GeckoSessionSettings.VIEWPORT_MODE_MOBILE),

    /** GeckoView's desktop user agent and viewport. */
    DESKTOP(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP, GeckoSessionSettings.VIEWPORT_MODE_DESKTOP),
}

/** Navigation state of a session. [loadCount] increases on every finished page load. */
data class PageState(
    val url: String? = null,
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val loadCount: Int = 0,
    val lastLoadSucceeded: Boolean? = null,
    val firstContentfulPaint: Boolean = false,
    val crashed: Boolean = false,
)

/**
 * One browsing session (a tab) in the engine's runtime. All sessions share the runtime's cookie
 * jar and the twin-bridge extension. A session is headless until it is attached to a
 * GeckoView; headless sessions load pages and run scripts normally.
 */
class EngineSession internal constructor(
    private val engine: Engine,
    val profile: UserAgentProfile,
    val name: String,
    trackingProtection: TrackingProtection,
) {
    val geckoSession: GeckoSession

    private val pageFlow = MutableStateFlow(PageState())
    val page: StateFlow<PageState> = pageFlow.asStateFlow()

    var view: GeckoView? = null
        private set

    val isHeadless: Boolean get() = view == null

    val isOpen: Boolean get() = geckoSession.isOpen

    init {
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(profile.userAgentMode)
            .viewportMode(profile.viewportMode)
            .useTrackingProtection(trackingProtection == TrackingProtection.STRICT)
            .build()
        geckoSession = GeckoSession(settings)
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                pageFlow.value = pageFlow.value.copy(url = url, loading = true, progress = 0, firstContentfulPaint = false)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                pageFlow.value = pageFlow.value.copy(progress = progress)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                val p = pageFlow.value
                pageFlow.value = p.copy(loading = false, loadCount = p.loadCount + 1, lastLoadSucceeded = success)
            }
        }
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageFlow.value = pageFlow.value.copy(title = title.orEmpty())
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                pageFlow.value = pageFlow.value.copy(firstContentfulPaint = true)
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(Engine.TAG, "session $name: content process crashed")
                pageFlow.value = pageFlow.value.copy(crashed = true, loading = false)
            }

            override fun onKill(session: GeckoSession) {
                Log.e(Engine.TAG, "session $name: content process killed")
                pageFlow.value = pageFlow.value.copy(crashed = true, loading = false)
            }
        }
        geckoSession.open(engine.runtime)
        // A headless session has no view to mark it visible; keep it active so its page runs
        // at full speed (timers, network) instead of being throttled as a background tab.
        geckoSession.setActive(true)
    }

    /** Shows this session in [view]. Main thread only. */
    fun attach(view: GeckoView) {
        view.setSession(geckoSession)
        this.view = view
    }

    /** Detaches the session from its view; it keeps running headless. Main thread only. */
    fun detach() {
        val v = view ?: return
        if (v.session === geckoSession) v.releaseSession()
        view = null
        geckoSession.setActive(true)
    }

    /** Main thread only. */
    fun load(url: String) {
        geckoSession.loadUri(url)
    }

    /** Loads [url] and suspends until that load stops. Returns the page state at that moment. */
    suspend fun loadAndWait(url: String, timeoutMs: Long = 30_000): PageState = withContext(Dispatchers.Main.immediate) {
        val before = pageFlow.value.loadCount
        load(url)
        withTimeout(timeoutMs) { pageFlow.first { it.loadCount > before || it.crashed } }
    }

    /** The user agent this session sends. */
    suspend fun userAgent(): String = geckoSession.userAgent.await().orEmpty()

    /** Main thread only. */
    fun close() {
        detach()
        geckoSession.close()
    }
}
