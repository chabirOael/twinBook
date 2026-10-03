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

/** A top-level navigation, as a [NavigationPolicy] sees it. */
data class NavigationRequest(
    val uri: String,
    /** URL of the page that started it, if known. */
    val triggerUri: String?,
    /** A request for a new window (`target=_blank`, `window.open`). */
    val newWindow: Boolean,
    /** Gecko saw a user gesture (or the transient activation that follows one). */
    val userGesture: Boolean,
    /** A server redirect of an earlier navigation. */
    val isRedirect: Boolean,
)

/** What a session does with a navigation its [NavigationPolicy] was asked about. */
sealed interface NavigationDecision {
    /** Load it here (scheme safety still applies; a new window loads in place). */
    data object Allow : NavigationDecision

    /** Do not load it (the policy may have handed it to another app). */
    data object Deny : NavigationDecision

    /** Do not load it; load [uri] in this session instead. */
    data class LoadInstead(val uri: String) : NavigationDecision
}

/** Decides top-level navigations of a session. Called on the main thread; must not block. */
fun interface NavigationPolicy {
    fun decide(request: NavigationRequest): NavigationDecision
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
    /** Top-level navigations the [EngineSession.navigationPolicy] denied or replaced. */
    val policyDenied: Int = 0,
    /** Times the session was reopened after a crash or kill ([EngineSession.recover]). */
    val recoveries: Int = 0,
)

/**
 * One browsing session (a tab) in the engine's runtime. All sessions share the runtime's cookie
 * jar and the twin-bridge extension. A session is headless until it is attached to a
 * GeckoView; headless sessions load pages and run scripts normally.
 *
 * Navigation safety, for every session: only the schemes in [ALLOWED_SCHEMES] (plus the
 * session's extra schemes) load; anything else (custom schemes, `intent:`) is ignored and never
 * leaves the app unless a [navigationPolicy] hands it on. A request to open a new window loads
 * in this session. Every permission request is denied.
 */
class EngineSession internal constructor(
    private val engine: Engine,
    val profile: UserAgentProfile,
    val name: String,
    trackingProtection: TrackingProtection,
    /** Schemes this session loads besides [ALLOWED_SCHEMES], e.g. `moz-extension` for an extension's own pages. */
    private val extraSchemes: Set<String> = emptySet(),
) {
    val geckoSession: GeckoSession

    private val pageFlow = MutableStateFlow(PageState())
    val page: StateFlow<PageState> = pageFlow.asStateFlow()

    var view: GeckoView? = null
        private set

    val isHeadless: Boolean get() = view == null

    val isOpen: Boolean get() = geckoSession.isOpen

    /**
     * Decides every top-level navigation (and new-window request) before scheme safety applies.
     * Null keeps the default: allowed schemes load, everything else is ignored. Main thread only.
     */
    var navigationPolicy: NavigationPolicy? = null

    private val stateFlow = MutableStateFlow<String?>(null)
    private var lastGoodState: String? = null
    private var lastGoodUrl: String? = null

    /**
     * Gecko's session state (history and the current page) as JSON; see [restoreState]. Updated
     * after every finished load (the session asks Gecko to flush it then), after [flushState], and
     * otherwise on Gecko's 10 s session store timer (scrolling, form data).
     */
    val sessionState: StateFlow<String?> = stateFlow.asStateFlow()

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
                // Gecko otherwise reports the session state on a 10 s timer (browser.sessionstore.interval).
                if (session.isOpen) session.flushSessionState()
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                val json = sessionState.toString()
                stateFlow.value = json
                // A state taken on a web page; the one taken while loading a crash or error page is not.
                val url = pageFlow.value.url
                if (url != null && (url.startsWith("https:") || url.startsWith("http:"))) {
                    lastGoodState = json
                    lastGoodUrl = url
                }
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
                val newWindow = request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                val decision = navigationPolicy?.decide(NavigationRequest(request.uri, request.triggerUri, newWindow, request.hasUserGesture, request.isRedirect))
                when (decision) {
                    null, NavigationDecision.Allow -> Unit
                    NavigationDecision.Deny -> return policyDenied()
                    is NavigationDecision.LoadInstead -> {
                        later { if (geckoSession.isOpen) geckoSession.loadUri(decision.uri) }
                        return policyDenied()
                    }
                }
                if (!isAllowed(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY)
                if (newWindow) {
                    openInPlace(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onSubframeLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny> =
                GeckoResult.fromValue(if (isAllowed(request.uri)) AllowOrDeny.ALLOW else AllowOrDeny.DENY)

            // window.open. Popups without a user gesture reach the prompt delegate's onPopupPrompt
            // first; a session whose delegate refuses them gets here only after a gesture.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                when (val decision = navigationPolicy?.decide(NavigationRequest(uri, pageFlow.value.url, newWindow = true, userGesture = true, isRedirect = false))) {
                    null, NavigationDecision.Allow -> if (isAllowed(uri)) openInPlace(uri)
                    NavigationDecision.Deny -> policyDenied()
                    is NavigationDecision.LoadInstead -> {
                        policyDenied()
                        openInPlace(decision.uri)
                    }
                }
                return null
            }
        }
        // The history list is the reliable source for back and forward: after restoreState Gecko
        // reports the restored history here, while onCanGoBack may not fire again.
        geckoSession.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onHistoryStateChange(session: GeckoSession, historyList: GeckoSession.HistoryDelegate.HistoryList) {
                val i = historyList.currentIndex
                pageFlow.value = pageFlow.value.copy(canGoBack = i > 0, canGoForward = i in 0 until historyList.size - 1)
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
        if (scheme in ALLOWED_SCHEMES || scheme in extraSchemes) return true
        Log.i(Engine.TAG, "session $name: ignored a load with scheme '$scheme'")
        pageFlow.value = pageFlow.value.copy(blockedLoads = pageFlow.value.blockedLoads + 1, lastBlockedScheme = scheme)
        return false
    }

    private fun openInPlace(uri: String) {
        pageFlow.value = pageFlow.value.copy(newWindowsInPlace = pageFlow.value.newWindowsInPlace + 1)
        // Not from inside the delegate call that asked for the new window.
        later { if (geckoSession.isOpen) geckoSession.loadUri(uri) }
    }

    private fun policyDenied(): GeckoResult<AllowOrDeny> {
        pageFlow.value = pageFlow.value.copy(policyDenied = pageFlow.value.policyDenied + 1)
        return GeckoResult.fromValue(AllowOrDeny.DENY)
    }

    private fun later(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun denied(what: String) {
        Log.i(Engine.TAG, "session $name: denied $what")
        pageFlow.value = pageFlow.value.copy(permissionsDenied = pageFlow.value.permissionsDenied + 1)
    }

    /** Main thread only. */
    fun goBack() {
        geckoSession.goBack()
    }

    /** Asks Gecko to report the session state now ([sessionState] updates shortly after). Main thread only. */
    fun flushState() {
        if (geckoSession.isOpen) geckoSession.flushSessionState()
    }

    /**
     * Restores a state saved from [sessionState]: Gecko rebuilds the history and loads its current
     * entry. Returns false if [json] is not a session state. Main thread only.
     */
    fun restoreState(json: String): Boolean {
        val state = runCatching { GeckoSession.SessionState.fromString(json) }.getOrNull() ?: return false
        return runCatching { geckoSession.restoreState(state) }.isSuccess
    }

    /**
     * After the content process crashed or was killed ([PageState.crashed]) the GeckoSession is
     * closed. Reopens it and restores the last state taken on a web page (history and page; not
     * the state of the page that crashed it, which Gecko may have saved last), else loads the last
     * web page, else [fallbackUrl]. Main thread only.
     */
    fun recover(fallbackUrl: String?) {
        if (!geckoSession.isOpen) {
            geckoSession.open(engine.runtime)
            geckoSession.setActive(true)
        }
        val p = pageFlow.value
        pageFlow.value = p.copy(crashed = false, recoveries = p.recoveries + 1)
        val state = lastGoodState
        Log.i(Engine.TAG, "session $name: recovering (${if (state != null) "restoring the last web page's state" else "loading a URL"})")
        if (state == null || !restoreState(state)) (lastGoodUrl ?: fallbackUrl)?.let { geckoSession.loadUri(it) }
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
