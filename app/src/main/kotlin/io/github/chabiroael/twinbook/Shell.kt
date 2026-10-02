package io.github.chabiroael.twinbook

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.chabiroael.twinbook.data.links.LinkDecision
import io.github.chabiroael.twinbook.data.links.LinkRules
import io.github.chabiroael.twinbook.data.links.OwnHost
import io.github.chabiroael.twinbook.data.links.RedirectRule
import io.github.chabiroael.twinbook.data.links.SiteHosts
import io.github.chabiroael.twinbook.engine.BlockerState
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.EngineSession
import io.github.chabiroael.twinbook.engine.NavigationDecision
import io.github.chabiroael.twinbook.engine.NavigationRequest
import io.github.chabiroael.twinbook.engine.UserAgentProfile
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Which site the shell shows: an id (saved state is kept per site) and its link rules (hosts, start URL). */
data class ShellConfig(val siteId: String, val rules: LinkRules) {
    val startUrl: String get() = rules.startUrl

    companion object {
        fun realSite(): ShellConfig = ShellConfig("site", LinkRules.forSite())

        /**
         * The test mock at [origin] (`http://127.0.0.1:<port>`): its pages under /shell/, its redirect
         * page at /l.php. Its hosts are 127.0.0.1 and, in debug builds, [AppEngine.MOCK_HOST].
         */
        const val MOCK_START = "/shell/home.html"

        fun mock(origin: String, startPath: String = MOCK_START): ShellConfig = ShellConfig(
            "mock:$origin",
            LinkRules.withSite(
                SiteHosts(
                    listOf(OwnHost("127.0.0.1", subdomains = false), OwnHost(AppEngine.MOCK_HOST, subdomains = false)),
                    listOf(RedirectRule(setOf("127.0.0.1", AppEngine.MOCK_HOST), "/l.php", "u")),
                ),
                origin + startPath,
            ),
        )
    }
}

/** Hands links to other apps. Tests replace it with one that only records. */
interface ExternalOpener {
    fun openInBrowser(url: String)

    fun openWithSystem(url: String)
}

/** Opens links with the device's default browser, and `tel:`, `mailto:`, `geo:` with the system. */
class AndroidOpener(private val context: Context) : ExternalOpener {
    override fun openInBrowser(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The default browser is the app that opens a plain https link; sent there directly, an
        // app that claims this particular link (say, a video app) does not take it over. Without a
        // default browser, Android asks.
        val browser = defaultBrowser()
        if (browser == null || !start(Intent(view).setPackage(browser))) start(view)
    }

    private fun defaultBrowser(): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
        val pkg = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android" is the chooser: no default is set.
        return pkg?.takeIf { it != "android" && it != context.packageName }
    }

    override fun openWithSystem(url: String) {
        val action = if (url.startsWith("mailto:", ignoreCase = true)) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
        start(Intent(action, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(AppEngine.TAG, "shell: no app for ${intent.action} ${intent.data?.scheme}: ${e.message}")
        false
    }
}

/**
 * Process-wide state of the web shell: one visible session on the site with the mobile user
 * agent, the link rules, saved state, settings and crash recovery. Survives activity recreation
 * and configuration changes. Main thread only. See docs/SHELL.md.
 */
@OptIn(FlowPreview::class)
class Shell private constructor(context: Context, val config: ShellConfig, val opener: ExternalOpener) {
    sealed interface Phase {
        data object Starting : Phase

        data object Ready : Phase

        data class Failed(val message: String) : Phase
    }

    private val appContext = context.applicationContext
    val engine: Engine = AppEngine.engine(appContext)
    val settings: ShellSettings = ShellSettings.get(appContext)
    val prompts = Prompts(allowPopupsWithoutGesture = false)
    val session: EngineSession = engine.newSession(UserAgentProfile.MOBILE, "shell")
    private val store = SessionStateStore(File(appContext.filesDir, "shell/state.json"))

    private val phaseFlow = MutableStateFlow<Phase>(Phase.Starting)
    val phase: StateFlow<Phase> = phaseFlow.asStateFlow()

    private val outboundFlow = MutableStateFlow(0)

    /** Links handed to other apps so far. */
    val outbound: StateFlow<Int> = outboundFlow.asStateFlow()

    /** True once the first page of this process has been drawn (the splash ends then). */
    private val shownFlow = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = shownFlow.asStateFlow()

    private val viewReadyFlow = MutableStateFlow(false)

    /** The screen calls this once its GeckoView has a size: a page loaded before that lays out at width 0. */
    fun viewLaidOut() {
        if (!viewReadyFlow.value) Log.i(AppEngine.TAG, "shell: view laid out")
        viewReadyFlow.value = true
    }

    init {
        session.promptDelegate = prompts
        session.navigationPolicy = io.github.chabiroael.twinbook.engine.NavigationPolicy { decide(it) }
        engine.scope.launch { start() }
        engine.scope.launch {
            // Gecko reports the session state after every navigation and scroll; keep the latest on disk.
            session.sessionState.filterNotNull().debounce(SAVE_DEBOUNCE_MS).collect { if (savable(it)) store.save(config.siteId, it) }
        }
        engine.scope.launch {
            session.page.collect { p ->
                if (!shownFlow.value && phaseFlow.value == Phase.Ready && (p.firstContentfulPaint || (p.loadCount > 0 && !p.loading && p.url != null && p.url != "about:blank"))) {
                    shownFlow.value = true
                    logTiming("shell first page shown", p.url)
                }
            }
        }
    }

    /** True once the saved state was restored or the start page requested; states before that are the new session's about:blank. */
    @Volatile
    private var started = false

    /** Only states taken after start that hold at least one web page are saved. */
    private fun savable(state: String): Boolean = started && SessionStateStore.hasWebPage(state)

    private suspend fun start() {
        val ready = try {
            engine.awaitReady(STARTUP_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(AppEngine.TAG, "shell: engine not ready", e)
            phaseFlow.value = Phase.Failed(e.message ?: e.toString())
            return
        }
        // Strict mode is applied before the first site request.
        runCatching { applyStrict(settings.strict.value) }.onFailure { Log.w(AppEngine.TAG, "shell: strict mode not applied: ${it.message}") }
        val t = engine.timings.value
        logTiming("shell engine ready (twin-bridge ${t.bridgeConnectedElapsed - t.processStartElapsed} ms, blocker ${if (t.blockerReadyElapsed > 0) "${t.blockerReadyElapsed - t.processStartElapsed} ms, ${t.blockerProbes} probes, ${t.blockerProbesPassed} passed" else ready.blocker}, mode ${t.startupMode})", null)
        phaseFlow.value = Phase.Ready
        viewReadyFlow.first { it }
        val saved = store.load(config.siteId)
        if (saved != null && session.restoreState(saved)) {
            Log.i(AppEngine.TAG, "shell: restored the saved session state (${SessionStateStore.entryCount(saved)} history entries)")
        } else {
            session.load(config.startUrl)
        }
        started = true
    }

    private fun logTiming(what: String, url: String?) {
        val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        Log.i(EngineLab.TIMING_TAG, "$what $ms ms after process start${if (url != null && !config.siteId.startsWith("site")) " ($url)" else ""}")
    }

    /** The link rules applied to a top-level navigation (docs/SHELL.md section 3). */
    private fun decide(request: NavigationRequest): NavigationDecision = when (val d = config.rules.decide(request.uri)) {
        is LinkDecision.Stay -> if (d.rewritten) NavigationDecision.LoadInstead(d.url) else NavigationDecision.Allow
        is LinkDecision.Browser -> {
            if (request.userGesture || request.isRedirect) {
                outboundFlow.value++
                opener.openInBrowser(d.url)
            } else {
                Log.i(AppEngine.TAG, "shell: an outbound navigation without a user gesture was ignored")
            }
            NavigationDecision.Deny
        }
        is LinkDecision.System -> {
            if (request.userGesture) {
                outboundFlow.value++
                opener.openWithSystem(d.url)
            }
            NavigationDecision.Deny
        }
        LinkDecision.EngineDefault -> NavigationDecision.Allow
        is LinkDecision.Ignore -> {
            Log.i(AppEngine.TAG, "shell: link ignored (${d.reason})")
            NavigationDecision.Deny
        }
    }

    /** The uBlock Origin dashboard's session while that screen is open (tests read it). */
    @Volatile
    var dashboardSession: EngineSession? = null

    fun back() = session.goBack()

    fun reload() = session.reload()

    fun home() = session.load(config.startUrl)

    /** After a content process crash or kill: reopen the session and restore its page. */
    fun recover() = session.recover(config.startUrl)

    /** Writes the current session state now (the activity calls this when it stops). */
    fun saveNow() {
        session.sessionState.value?.let { if (savable(it)) store.save(config.siteId, it) }
        // And ask Gecko for the latest state (scroll position, form data); it is saved when it arrives.
        session.flushState()
    }

    /** Ad hiding on or off: uBlock Origin is enabled or disabled (not reinstalled), then the page reloads. */
    suspend fun setAdHiding(on: Boolean) {
        settings.setAdHiding(on)
        engine.setBlockerEnabled(on)
        session.reload()
    }

    suspend fun setStrict(on: Boolean) {
        settings.setStrict(on)
        applyStrict(on)
    }

    private suspend fun applyStrict(on: Boolean) {
        engine.bridge.request("strict.set", JSONObject().put("enabled", on))
    }

    val blocker: StateFlow<BlockerState> get() = engine.blocker

    companion object {
        private const val SAVE_DEBOUNCE_MS = 300L
        private const val STARTUP_TIMEOUT_MS = 90_000L

        @Volatile
        private var instance: Shell? = null

        fun get(context: Context): Shell = instance ?: Shell(context.applicationContext, DevOverrides.shellConfig() ?: ShellConfig.realSite(), AndroidOpener(context.applicationContext)).also { instance = it }

        fun getOrNull(): Shell? = instance

        /** Replaces the instance (instrumented tests). Main thread only. */
        fun configureForTest(context: Context, config: ShellConfig, opener: ExternalOpener): Shell {
            instance?.session?.close()
            return Shell(context.applicationContext, config, opener).also { instance = it }
        }
    }
}

/**
 * The shell's last session state (history and current page, as Gecko serializes it), one file,
 * tagged with the site id so the state of one site is never restored into another. Written
 * through a temporary file, so a process death mid-write leaves the previous state.
 */
class SessionStateStore(private val file: File) {
    fun save(siteId: String, state: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("site", siteId).put("savedAt", System.currentTimeMillis()).put("state", state).toString())
            if (!tmp.renameTo(file)) Log.w(AppEngine.TAG, "shell: could not replace ${file.name}")
        } catch (e: Exception) {
            Log.w(AppEngine.TAG, "shell: state not saved: ${e.message}")
        }
    }

    companion object {
        private fun entries(state: String) = runCatching { JSONObject(state).optJSONObject("history")?.optJSONArray("entries") }.getOrNull()

        fun entryCount(state: String): Int = entries(state)?.length() ?: 0

        /** True if the state's history holds an http(s) page (not only about:blank). */
        fun hasWebPage(state: String): Boolean {
            val list = entries(state) ?: return false
            return (0 until list.length()).any { list.optJSONObject(it)?.optString("url").orEmpty().let { u -> u.startsWith("http:") || u.startsWith("https:") } }
        }
    }

    fun load(siteId: String): String? = try {
        if (!file.exists()) {
            null
        } else {
            val json = JSONObject(file.readText())
            if (json.optString("site") == siteId) json.optString("state").ifEmpty { null } else null
        }
    } catch (e: Exception) {
        Log.w(AppEngine.TAG, "shell: saved state unreadable: ${e.message}")
        null
    }
}
