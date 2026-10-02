package io.github.chabiroael.twinbook.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
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
    /** URL of the document shown (from location changes, so a denied load never shows here). */
    val url: String? = null,
    /** URL of the most recent load start, which may have been denied. */
    val loadingUrl: String? = null,
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val loadCount: Int = 0,
    val lastLoadSucceeded: Boolean? = null,
    val firstContentfulPaint: Boolean = false,
    val crashed: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Loads ignored because their scheme is not allowed (custom schemes, intents). */
    val blockedLoads: Int = 0,
    /** Scheme of the last ignored load (never the URL). */
    val lastBlockedScheme: String? = null,
    /** Requests for a new window that were loaded in this session instead. */
    val newWindowsInPlace: Int = 0,
    /** Permission requests that were denied. */
    val permissionsDenied: Int = 0,
)

/**
 * One browsing session (a tab) in the engine's runtime. All sessions share the runtime's cookie
 * jar and the twin-bridge extension. A session is headless until it is attached to a
 * GeckoView; headless sessions load pages and run scripts normally.
 *
 * Navigation safety, for every session: only the schemes in [ALLOWED_SCHEMES] load; anything
 * else (custom schemes, `intent:`) is ignored and never leaves the app. A request to open a new
 * window loads in this session. Every permission request is denied.
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
                pageFlow.value = pageFlow.value.copy(loadingUrl = url, loading = true, progress = 0, firstContentfulPaint = false)
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
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<ContentPermission>, hasUserGesture: Boolean) {
                pageFlow.value = pageFlow.value.copy(url = url)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                pageFlow.value = pageFlow.value.copy(canGoBack = canGoBack)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                pageFlow.value = pageFlow.value.copy(canGoForward = canGoForward)
            }

            override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny> {
                if (!isAllowed(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY)
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    openInPlace(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onSubframeLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny> =
                GeckoResult.fromValue(if (isAllowed(request.uri)) AllowOrDeny.ALLOW else AllowOrDeny.DENY)

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (isAllowed(uri)) openInPlace(uri)
                return null
            }
        }
        geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
                denied("content permission ${perm.permission}")
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            }

            override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback) {
                denied("android permissions ${permissions?.joinToString()}")
                callback.reject()
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                denied("media")
                callback.reject()
            }
        }
        geckoSession.open(engine.runtime)
        // A headless session has no view to mark it visible; keep it active so its page runs
        // at full speed (timers, network) instead of being throttled as a background tab.
        geckoSession.setActive(true)
    }

    /** Handles JavaScript dialogs and other prompts; null dismisses them. Main thread only. */
    var promptDelegate: GeckoSession.PromptDelegate?
        get() = geckoSession.promptDelegate
        set(value) {
            geckoSession.promptDelegate = value
        }

    private fun isAllowed(uri: String): Boolean {
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme in ALLOWED_SCHEMES) return true
        Log.i(Engine.TAG, "session $name: ignored a load with scheme '$scheme'")
        pageFlow.value = pageFlow.value.copy(blockedLoads = pageFlow.value.blockedLoads + 1, lastBlockedScheme = scheme)
        return false
    }

    private fun openInPlace(uri: String) {
        pageFlow.value = pageFlow.value.copy(newWindowsInPlace = pageFlow.value.newWindowsInPlace + 1)
        // Not from inside the delegate call that asked for the new window.
        android.os.Handler(android.os.Looper.getMainLooper()).post { if (geckoSession.isOpen) geckoSession.loadUri(uri) }
    }

    private fun denied(what: String) {
        Log.i(Engine.TAG, "session $name: denied $what")
        pageFlow.value = pageFlow.value.copy(permissionsDenied = pageFlow.value.permissionsDenied + 1)
    }

    /** Main thread only. */
    fun goBack() {
        geckoSession.goBack()
    }

    /** Main thread only. */
    fun reload() {
        geckoSession.reload()
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

    /**
     * Loads [url] and suspends until that load stops. Returns the page state at that moment.
     *
     * A new session always reports a stop for its initial about:blank, and that stop can
     * arrive after a load started right away. So the first call waits (up to 5 s) for the
     * initial stop before loading.
     */
    suspend fun loadAndWait(url: String, timeoutMs: Long = 30_000): PageState = withContext(Dispatchers.Main.immediate) {
        if (pageFlow.value.loadCount == 0) withTimeoutOrNull(5_000) { pageFlow.first { it.loadCount > 0 } }
        val before = pageFlow.value.loadCount
        load(url)
        withTimeout(timeoutMs) { pageFlow.first { it.loadCount > before || it.crashed } }
    }

    /** The user agent this session sends. */
    suspend fun userAgent(): String = withContext(Dispatchers.Main.immediate) { geckoSession.userAgent }.await().orEmpty()

    companion object {
        /** Schemes a session loads. Everything else is ignored. */
        val ALLOWED_SCHEMES = setOf("http", "https", "about", "resource", "data", "blob")
    }

    /** Main thread only. */
    fun close() {
        detach()
        geckoSession.close()
    }
}
