package io.github.chabiroael.twinbook.engine

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.chabiroael.twinbook.engine.bridge.Bridge
import io.github.chabiroael.twinbook.engine.bridge.BridgeState
import io.github.chabiroael.twinbook.engine.bridge.BridgeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.BuildConfig as GeckoBuildConfig
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * The browser engine: one GeckoRuntime per process, the built-in twin-bridge extension and the
 * [Bridge] to it, and optionally a built-in content blocker (uBlock Origin, [EngineConfig.blocker]).
 * Create it with [start] on the main thread; it lives until the process dies.
 *
 * Start-up contract (docs/SHELL.md, docs/ENGINE.md section 9): a headless bootstrap session is
 * opened at once, so Gecko starts the background scripts of already installed extensions. The
 * engine is ready ([awaitReady]) when twin-bridge has said hello over the bridge and the content
 * blocker, if enabled, has cancelled a probe request; site pages must load only after that.
 */
class Engine private constructor(context: Context, val config: EngineConfig) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Main-thread scope for engine work and bridge handlers. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val runtime: GeckoRuntime

    val bridge: Bridge = Bridge(scope) { engineInfo() }

    private val extensionFlow = MutableStateFlow<ExtensionState>(ExtensionState.Installing)

    /** Installation state of twin-bridge. */
    val extension: StateFlow<ExtensionState> = extensionFlow.asStateFlow()

    private val blockerFlow = MutableStateFlow<BlockerState>(if (config.blocker == null) BlockerState.Absent else BlockerState.Installing)

    /** State of the content blocker. */
    val blocker: StateFlow<BlockerState> = blockerFlow.asStateFlow()

    private var blockerExtension: WebExtension? = null
    private var blockerWanted: Boolean = config.blocker?.enabled ?: false
    private var blockerReinstalled = false
    private var probeSerial = 0
    private val probeNonce = SystemClock.elapsedRealtime().toString(36)

    private val timingsFlow = MutableStateFlow(EngineTimings(processStartElapsed = Process.getStartElapsedRealtime(), engineStartElapsed = SystemClock.elapsedRealtime(), startupMode = config.startupMode))

    /** Start-up milestones on the elapsedRealtime clock. */
    val timings: StateFlow<EngineTimings> = timingsFlow.asStateFlow()

    private val defaultContentBlocking: ContentBlockingSnapshot

    private val trackingProtectionFlow = MutableStateFlow(TrackingProtection.DEFAULT)

    /** Tracking protection applied to the runtime and to sessions created from now on. */
    val trackingProtection: StateFlow<TrackingProtection> = trackingProtectionFlow.asStateFlow()

    private val bootstrap: GeckoSession

    init {
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(config.debug)
            .remoteDebuggingEnabled(config.debug)
            // Pages see the system's light or dark setting (prefers-color-scheme).
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .apply { config.configureRuntime(this) }
            .build()
        runtime = GeckoRuntime.create(context.applicationContext, settings)
        defaultContentBlocking = ContentBlockingSnapshot.of(runtime.settings.contentBlocking)
        Log.i(TAG, "GeckoView $GECKOVIEW_VERSION started (${config.startupMode}); content blocking defaults: ${defaultContentBlocking.toJson()}")
        bridge.handle("engine.info") { engineInfo() }
        installExtension()
        config.blocker?.let { installBlocker(it) }
        // GeckoView starts the background scripts of already-installed extensions only on
        // "extensions-late-startup", which fires when the first session window opens
        // (geckoview.js). A fresh install starts them at once, a normal start does not. A
        // bootstrap session makes the engine start them on its own. It also hosts the content
        // blocker's probe page, and is closed once both extensions are ready.
        bootstrap = GeckoSession().apply {
            open(runtime)
            setActive(true)
            loadUri("about:blank")
        }
        Log.i(TAG, "bootstrap session opened")
        scope.launch {
            bridge.state.first { it == BridgeState.Connected }
            timingsFlow.value = timingsFlow.value.copy(bridgeConnectedElapsed = SystemClock.elapsedRealtime())
            if (config.blocker != null) {
                blockerFlow.first { it !is BlockerState.Installing }
                if (blockerFlow.value is BlockerState.Starting) probeBlocker(bootstrap)
            }
            bootstrap.close()
            Log.i(TAG, "bootstrap session closed")
        }
    }

    private fun installExtension() {
        val controller = runtime.webExtensionController
        val install = when (config.startupMode) {
            StartupMode.ENSURE_BUILT_IN -> controller.ensureBuiltIn(config.extensionLocation, config.extensionId)
            StartupMode.INSTALL_EVERY_START -> controller.installBuiltIn(config.extensionLocation)
        }
        install.accept(
            { ext -> ext?.let { onExtensionInstalled(it, reinstalled = false) } },
            { error ->
                Log.e(TAG, "extension install failed", error)
                extensionFlow.value = ExtensionState.Failed(error?.toString() ?: "unknown error")
            },
        )
    }

    private fun onExtensionInstalled(extension: WebExtension, reinstalled: Boolean) {
        Log.i(TAG, "extension ${extension.id} ${extension.metaData.version} installed (built-in=${extension.isBuiltIn}, reinstalled=$reinstalled)")
        extension.setMessageDelegate(messageDelegate, config.nativeApp)
        timingsFlow.value = timingsFlow.value.copy(extensionInstalledElapsed = SystemClock.elapsedRealtime(), extensionReinstalled = reinstalled)
        extensionFlow.value = ExtensionState.Installed(extension.id, extension.metaData.version, extension)
        if (!reinstalled) scope.launch { recoverIfBackgroundNeverStarts() }
    }

    /**
     * Gecko writes its add-on startup state (addonStartup.json.lz4) a moment after an install.
     * If the process dies before that, the next start finds the add-on in its database with
     * the same version, so ensureBuiltIn does nothing, yet never starts its background script.
     * The bridge then never connects. Reinstalling repairs it.
     */
    private suspend fun recoverIfBackgroundNeverStarts() {
        val connected = withTimeoutOrNull(config.backgroundStartTimeoutMs) { bridge.state.first { it == BridgeState.Connected } }
        if (connected != null) return
        Log.w(TAG, "twin-bridge background did not start within ${config.backgroundStartTimeoutMs} ms; reinstalling")
        runtime.webExtensionController.installBuiltIn(config.extensionLocation).accept(
            { ext -> ext?.let { onExtensionInstalled(it, reinstalled = true) } },
            { error ->
                Log.e(TAG, "extension reinstall failed", error)
                extensionFlow.value = ExtensionState.Failed(error?.toString() ?: "unknown error")
            },
        )
    }

    // ---- content blocker ------------------------------------------------------------------

    private fun installBlocker(b: BlockerConfig) {
        val controller = runtime.webExtensionController
        val install = when (config.startupMode) {
            StartupMode.ENSURE_BUILT_IN -> controller.ensureBuiltIn(b.location, b.id)
            StartupMode.INSTALL_EVERY_START -> controller.installBuiltIn(b.location)
        }
        install.accept(
            { ext -> ext?.let { scope.launch { onBlockerInstalled(it) } } },
            { error ->
                Log.e(TAG, "content blocker install failed", error)
                blockerFlow.value = BlockerState.Failed(error?.toString() ?: "unknown error")
            },
        )
    }

    private suspend fun onBlockerInstalled(installed: WebExtension) {
        var ext = installed
        timingsFlow.value = timingsFlow.value.copy(blockerInstalledElapsed = SystemClock.elapsedRealtime())
        Log.i(TAG, "content blocker ${ext.id} ${ext.metaData.version} installed (enabled=${ext.metaData.enabled}, wanted=$blockerWanted)")
        // A disabled add-on stays disabled across restarts; follow the app's setting.
        if (ext.metaData.enabled != blockerWanted) ext = switchBlocker(ext, blockerWanted)
        blockerExtension = ext
        blockerFlow.value = if (blockerWanted) BlockerState.Starting(ext.metaData.version) else BlockerState.Disabled(ext.metaData.version)
    }

    private suspend fun switchBlocker(ext: WebExtension, enabled: Boolean): WebExtension {
        val controller = runtime.webExtensionController
        val result = if (enabled) controller.enable(ext, WebExtensionController.EnableSource.APP) else controller.disable(ext, WebExtensionController.EnableSource.APP)
        return result.await() ?: ext
    }

    /**
     * Loads a probe page in [session] until the content blocker cancels its probe request. Its
     * only subresource is [BlockerConfig.probeUrl], on the loopback interface, which a default
     * list of the blocker blocks; twin-bridge reports how each probe request ended. A request
     * that "passed" means the blocker was not filtering yet. If the blocker is not filtering
     * after [EngineConfig.backgroundStartTimeoutMs], it is reinstalled once (the add-on start-up
     * state quirk applies to it too); after [BlockerConfig.failAfterMs] it is reported failed.
     */
    private suspend fun probeBlocker(session: GeckoSession) {
        val b = config.blocker ?: return
        val started = SystemClock.elapsedRealtime()
        var probes = 0
        var passed = 0
        while (blockerWanted) {
            probes++
            val id = "$probeNonce-${++probeSerial}"
            session.loadUri(probePage(b.probeUrl, id))
            val outcome = runCatching {
                bridge.request("probe.result", JSONObject().put("id", id).put("waitMs", PROBE_WAIT_MS), PROBE_WAIT_MS + 5_000).optString("outcome")
            }.getOrElse { "error: ${it.message}" }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (outcome == "blocked") {
                val now = SystemClock.elapsedRealtime()
                timingsFlow.value = timingsFlow.value.copy(blockerReadyElapsed = now, blockerProbes = probes, blockerProbesPassed = passed)
                blockerFlow.value = BlockerState.Ready(blockerExtension?.metaData?.version.orEmpty(), probes, passed)
                Log.i(TAG, "content blocker ready after $probes probe(s), $passed passed unfiltered, ${elapsed} ms of probing")
                return
            }
            if (outcome == "passed") passed++
            Log.i(TAG, "content blocker probe $id: $outcome")
            if (elapsed > b.failAfterMs) {
                blockerFlow.value = BlockerState.Failed("not filtering after ${elapsed} ms ($probes probes, last: $outcome)")
                return
            }
            if (elapsed > config.backgroundStartTimeoutMs && !blockerReinstalled) {
                blockerReinstalled = true
                Log.w(TAG, "content blocker not filtering after $elapsed ms; reinstalling")
                timingsFlow.value = timingsFlow.value.copy(blockerReinstalled = true)
                runtime.webExtensionController.installBuiltIn(b.location).await()?.let { blockerExtension = it }
            }
            delay(PROBE_RETRY_MS)
        }
    }

    /**
     * Switches the content blocker on or off (GeckoView enable/disable, not a reinstall). On
     * returns once it filters again. Main thread only.
     */
    suspend fun setBlockerEnabled(enabled: Boolean) {
        blockerWanted = enabled
        val ext = blockerExtension ?: return // applied when the install finishes
        if (ext.metaData.enabled == enabled && blockerFlow.value.let { (enabled && it is BlockerState.Ready) || (!enabled && it is BlockerState.Disabled) }) return
        val switched = switchBlocker(ext, enabled)
        blockerExtension = switched
        if (!enabled) {
            blockerFlow.value = BlockerState.Disabled(switched.metaData.version)
            Log.i(TAG, "content blocker disabled")
            return
        }
        blockerFlow.value = BlockerState.Starting(switched.metaData.version)
        bridge.state.first { it == BridgeState.Connected }
        val probe = GeckoSession().apply {
            open(runtime)
            setActive(true)
        }
        try {
            probeBlocker(probe)
        } finally {
            probe.close()
        }
    }

    /** The content blocker's WebExtension, once installed (for its options page). */
    val blockerWebExtension: WebExtension? get() = blockerExtension

    // ---- bridge -----------------------------------------------------------------------------

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val transport = GeckoPortTransport(port, mainHandler)
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    if (message is JSONObject) bridge.onMessage(transport, message) else Log.w(TAG, "non-object bridge message ignored")
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    Log.w(TAG, "bridge port disconnected")
                    transport.disconnected = true
                    bridge.onDisconnected(transport)
                }
            })
            Log.i(TAG, "bridge port connected")
            bridge.onConnected(transport)
        }
    }

    /**
     * Suspends until twin-bridge is installed and its bridge connected, and the content blocker,
     * if configured and enabled, filters. Throws if an extension failed or [timeoutMs] passes.
     */
    suspend fun awaitReady(timeoutMs: Long = 60_000): ReadyInfo = withTimeout(timeoutMs) {
        combine(extension, bridge.state, bridge.extension, blocker) { ext, state, hello, blocker -> Ready(ext, state, hello, blocker) }
            .first { r ->
                if (r.ext is ExtensionState.Failed) throw IllegalStateException("twin-bridge failed to install: ${r.ext.message}")
                if (r.blocker is BlockerState.Failed) throw IllegalStateException("content blocker failed: ${r.blocker.message}")
                r.ext is ExtensionState.Installed && r.state == BridgeState.Connected && r.hello != null && r.blocker.settled
            }
            .let { r ->
                ReadyInfo(
                    extensionId = (r.ext as ExtensionState.Installed).id,
                    installedVersion = r.ext.version,
                    reportedVersion = r.hello!!.optString("version"),
                    marker = r.hello.optString("marker"),
                    blocker = r.blocker,
                )
            }
    }

    private data class Ready(val ext: ExtensionState, val state: BridgeState, val hello: JSONObject?, val blocker: BlockerState)

    /** Creates and opens a session. It is headless until [EngineSession.attach] is called. Main thread only. */
    fun newSession(profile: UserAgentProfile, name: String = profile.name.lowercase(), extraSchemes: Set<String> = emptySet()): EngineSession =
        EngineSession(this, profile, name, trackingProtectionFlow.value, extraSchemes)

    /** Passes a configuration change (dark mode, orientation, locale) on to Gecko. Main thread only. */
    fun configurationChanged(newConfig: Configuration) {
        runtime.configurationChanged(newConfig)
    }

    /** Switches the runtime's tracking protection. Sessions created afterwards follow it too. Main thread only. */
    fun setTrackingProtection(level: TrackingProtection) {
        val cb = runtime.settings.contentBlocking
        when (level) {
            TrackingProtection.DEFAULT -> defaultContentBlocking.applyTo(cb)
            TrackingProtection.STRICT -> cb
                .setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .setAntiTracking(ContentBlocking.AntiTracking.STRICT)
                .setStrictSocialTrackingProtection(true)
                .setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
        }
        trackingProtectionFlow.value = level
        Log.i(TAG, "tracking protection $level: ${describeContentBlocking()}")
    }

    /** The runtime's current content-blocking settings. */
    fun describeContentBlocking(): JSONObject = ContentBlockingSnapshot.of(runtime.settings.contentBlocking).toJson()

    /** The content-blocking settings GeckoView started with (its defaults, as configured here). */
    fun describeDefaultContentBlocking(): JSONObject = defaultContentBlocking.toJson()

    private fun engineInfo(): JSONObject = JSONObject()
        .put("geckoview", GECKOVIEW_VERSION)
        .put("trackingProtection", trackingProtectionFlow.value.name)

    /** Posts to a GeckoView port on the main thread, in call order. */
    private class GeckoPortTransport(private val port: WebExtension.Port, private val handler: Handler) : BridgeTransport {
        @Volatile
        var disconnected = false

        override fun post(message: JSONObject) {
            handler.post { if (!disconnected) port.postMessage(message) }
        }
    }

    companion object {
        internal const val TAG = "twinbook-engine"
        private const val PROBE_WAIT_MS = 10_000L
        private const val PROBE_RETRY_MS = 50L

        /** GeckoView version and build ID, e.g. `157.0 (20260924084938)`. */
        val GECKOVIEW_VERSION: String = "${GeckoBuildConfig.MOZ_APP_VERSION} (${GeckoBuildConfig.MOZ_APP_BUILDID})"

        @Volatile
        private var instance: Engine? = null

        /** Starts the engine, or returns the running one. The config of the first call wins. Main thread only. */
        fun start(context: Context, config: EngineConfig = EngineConfig()): Engine {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Engine.start must run on the main thread" }
            return instance ?: Engine(context, config).also { instance = it }
        }

        /** The running engine, if started. */
        fun getOrNull(): Engine? = instance

        /** The probe page: a document whose only subresource is the probe URL, tagged with [id]. */
        internal fun probePage(probeUrl: String, id: String): String {
            val sep = if ('?' in probeUrl) '&' else '?'
            val html = "<!DOCTYPE html><title>probe</title><img src=\"$probeUrl${sep}twinbook_startup_probe=$id\">"
            return "data:text/html;charset=utf-8," + Uri.encode(html)
        }
    }
}

/** How built-in extensions are installed at every start (measured in M3a, docs/SHELL.md). */
enum class StartupMode {
    /** `ensureBuiltIn`: install only when the packaged version differs; a bootstrap session starts their backgrounds. */
    ENSURE_BUILT_IN,

    /** `installBuiltIn` at every start, which starts the backgrounds at once. */
    INSTALL_EVERY_START,
}

data class EngineConfig(
    /** Send web console output to logcat and enable remote debugging. Use BuildConfig.DEBUG. */
    val debug: Boolean = false,
    val extensionLocation: String = TWIN_BRIDGE_LOCATION,
    val extensionId: String = TWIN_BRIDGE_ID,
    val nativeApp: String = TWIN_BRIDGE_NATIVE_APP,
    /** If the bridge has not connected this long after install, the extension is reinstalled. */
    val backgroundStartTimeoutMs: Long = 10_000,
    /** The content blocker (uBlock Origin), or null for none. */
    val blocker: BlockerConfig? = null,
    val startupMode: StartupMode = StartupMode.ENSURE_BUILT_IN,
    /** Extra runtime settings, applied after the defaults above. */
    val configureRuntime: (GeckoRuntimeSettings.Builder) -> Unit = {},
) {
    companion object {
        const val TWIN_BRIDGE_ID = "twin-bridge@twinbook"
        const val TWIN_BRIDGE_LOCATION = "resource://android/assets/extensions/twin-bridge/"
        const val TWIN_BRIDGE_NATIVE_APP = "twinbook"
    }
}

/** The built-in content blocker. Defaults: uBlock Origin as packaged by the :engine build. */
data class BlockerConfig(
    val id: String = UBLOCK_ID,
    val location: String = UBLOCK_LOCATION,
    /**
     * Probe request that a default list of the blocker blocks, on the loopback interface (twin-bridge
     * watches 127.0.0.1 only) at a port nothing listens on, so an unfiltered probe never leaves
     * the device: `/__utm.gif` is in EasyPrivacy's generic rules.
     */
    val probeUrl: String = "http://127.0.0.1:65535/__utm.gif",
    /** Whether it filters (the app's "ad hiding" setting). */
    val enabled: Boolean = true,
    /** Probing gives up after this long and reports [BlockerState.Failed]. */
    val failAfterMs: Long = 60_000,
) {
    companion object {
        const val UBLOCK_ID = "uBlock0@raymondhill.net"
        const val UBLOCK_LOCATION = "resource://android/assets/extensions/ublock0/"
    }
}

sealed interface ExtensionState {
    data object Installing : ExtensionState
    data class Installed(val id: String, val version: String, val webExtension: WebExtension) : ExtensionState
    data class Failed(val message: String) : ExtensionState
}

/** State of the content blocker. [settled] is true when site pages may load. */
sealed interface BlockerState {
    val settled: Boolean get() = false

    /** None configured. */
    data object Absent : BlockerState {
        override val settled get() = true
    }

    data object Installing : BlockerState

    /** Installed and switched off by the app. */
    data class Disabled(val version: String) : BlockerState {
        override val settled get() = true
    }

    /** Enabled, not yet seen filtering. */
    data class Starting(val version: String) : BlockerState

    /** Seen filtering: it cancelled a probe request after [probes] attempts, [passed] of which went out unfiltered. */
    data class Ready(val version: String, val probes: Int, val passed: Int) : BlockerState {
        override val settled get() = true
    }

    data class Failed(val message: String) : BlockerState
}

data class ReadyInfo(
    val extensionId: String,
    /** Version GeckoView reports for the installed extension. */
    val installedVersion: String,
    /** Version the extension reported over the bridge (its manifest version). */
    val reportedVersion: String,
    /** Build marker compiled into the extension bundle. */
    val marker: String,
    val blocker: BlockerState = BlockerState.Absent,
)

/** Times on the elapsedRealtime clock; 0 means not reached yet. */
data class EngineTimings(
    val processStartElapsed: Long,
    val engineStartElapsed: Long,
    val extensionInstalledElapsed: Long = 0,
    val bridgeConnectedElapsed: Long = 0,
    /** True if the extension had to be reinstalled because its background never started. */
    val extensionReinstalled: Boolean = false,
    val startupMode: StartupMode = StartupMode.ENSURE_BUILT_IN,
    val blockerInstalledElapsed: Long = 0,
    val blockerReadyElapsed: Long = 0,
    val blockerProbes: Int = 0,
    val blockerProbesPassed: Int = 0,
    val blockerReinstalled: Boolean = false,
)

enum class TrackingProtection { DEFAULT, STRICT }

private data class ContentBlockingSnapshot(
    val etpLevel: Int,
    val antiTracking: Int,
    val cookieBehavior: Int,
    val strictSocial: Boolean,
    val cookiePurging: Boolean,
) {
    fun applyTo(cb: ContentBlocking.Settings) {
        cb.setEnhancedTrackingProtectionLevel(etpLevel)
            .setAntiTracking(antiTracking)
            .setCookieBehavior(cookieBehavior)
            .setStrictSocialTrackingProtection(strictSocial)
            .setCookiePurging(cookiePurging)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enhancedTrackingProtectionLevel", etpLevel)
        .put("antiTrackingCategories", antiTracking)
        .put("cookieBehavior", cookieBehavior)
        .put("strictSocialTrackingProtection", strictSocial)
        .put("cookiePurging", cookiePurging)

    companion object {
        fun of(cb: ContentBlocking.Settings) = ContentBlockingSnapshot(
            etpLevel = cb.enhancedTrackingProtectionLevel,
            antiTracking = cb.antiTrackingCategories,
            cookieBehavior = cb.cookieBehavior,
            strictSocial = cb.strictSocialTrackingProtection,
            cookiePurging = cb.cookiePurging,
        )
    }
}
