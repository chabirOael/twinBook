package io.github.chabiroael.twinbook.engine

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.BuildConfig as GeckoBuildConfig
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

/**
 * The browser engine: one GeckoRuntime per process, the built-in twin-bridge extension, and
 * the [Bridge] to it. Create it with [start] on the main thread; it lives until the process
 * dies.
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

    private val timingsFlow = MutableStateFlow(EngineTimings(processStartElapsed = Process.getStartElapsedRealtime(), engineStartElapsed = SystemClock.elapsedRealtime()))

    /** Start-up milestones on the elapsedRealtime clock. */
    val timings: StateFlow<EngineTimings> = timingsFlow.asStateFlow()

    private val defaultContentBlocking: ContentBlockingSnapshot

    private val trackingProtectionFlow = MutableStateFlow(TrackingProtection.DEFAULT)

    /** Tracking protection applied to the runtime and to sessions created from now on. */
    val trackingProtection: StateFlow<TrackingProtection> = trackingProtectionFlow.asStateFlow()

    init {
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(config.debug)
            .remoteDebuggingEnabled(config.debug)
            .apply { config.configureRuntime(this) }
            .build()
        runtime = GeckoRuntime.create(context.applicationContext, settings)
        defaultContentBlocking = ContentBlockingSnapshot.of(runtime.settings.contentBlocking)
        Log.i(TAG, "GeckoView $GECKOVIEW_VERSION started; content blocking defaults: ${defaultContentBlocking.toJson()}")
        bridge.handle("engine.info") { engineInfo() }
        installExtension()
        scope.launch {
            bridge.state.first { it == BridgeState.Connected }
            timingsFlow.value = timingsFlow.value.copy(bridgeConnectedElapsed = SystemClock.elapsedRealtime())
        }
    }

    private fun installExtension() {
        runtime.webExtensionController.ensureBuiltIn(config.extensionLocation, config.extensionId).accept(
            { ext ->
                val extension = ext ?: return@accept
                Log.i(TAG, "extension ${extension.id} ${extension.metaData.version} installed (built-in=${extension.isBuiltIn})")
                extension.setMessageDelegate(messageDelegate, config.nativeApp)
                timingsFlow.value = timingsFlow.value.copy(extensionInstalledElapsed = SystemClock.elapsedRealtime())
                extensionFlow.value = ExtensionState.Installed(extension.id, extension.metaData.version, extension)
            },
            { error ->
                Log.e(TAG, "extension install failed", error)
                extensionFlow.value = ExtensionState.Failed(error?.toString() ?: "unknown error")
            },
        )
    }

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
     * Suspends until the extension is installed and the bridge is connected. Throws if the
     * extension failed to install or [timeoutMs] passes.
     */
    suspend fun awaitReady(timeoutMs: Long = 60_000): ReadyInfo = withTimeout(timeoutMs) {
        combine(extension, bridge.state, bridge.extension) { ext, state, hello -> Triple(ext, state, hello) }
            .first { (ext, state, hello) ->
                if (ext is ExtensionState.Failed) throw IllegalStateException("twin-bridge failed to install: ${ext.message}")
                ext is ExtensionState.Installed && state == BridgeState.Connected && hello != null
            }
            .let { (ext, _, hello) ->
                ReadyInfo(
                    extensionId = (ext as ExtensionState.Installed).id,
                    installedVersion = ext.version,
                    reportedVersion = hello!!.optString("version"),
                    marker = hello.optString("marker"),
                )
            }
    }

    /** Creates and opens a session. It is headless until [EngineSession.attach] is called. Main thread only. */
    fun newSession(profile: UserAgentProfile, name: String = profile.name.lowercase()): EngineSession =
        EngineSession(this, profile, name, trackingProtectionFlow.value)

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
    }
}

data class EngineConfig(
    /** Send web console output to logcat and enable remote debugging. Use BuildConfig.DEBUG. */
    val debug: Boolean = false,
    val extensionLocation: String = TWIN_BRIDGE_LOCATION,
    val extensionId: String = TWIN_BRIDGE_ID,
    val nativeApp: String = TWIN_BRIDGE_NATIVE_APP,
    /** Extra runtime settings, applied after the defaults above. */
    val configureRuntime: (GeckoRuntimeSettings.Builder) -> Unit = {},
) {
    companion object {
        const val TWIN_BRIDGE_ID = "twin-bridge@twinbook"
        const val TWIN_BRIDGE_LOCATION = "resource://android/assets/extensions/twin-bridge/"
        const val TWIN_BRIDGE_NATIVE_APP = "twinbook"
    }
}

sealed interface ExtensionState {
    data object Installing : ExtensionState
    data class Installed(val id: String, val version: String, val webExtension: WebExtension) : ExtensionState
    data class Failed(val message: String) : ExtensionState
}

data class ReadyInfo(
    val extensionId: String,
    /** Version GeckoView reports for the installed extension. */
    val installedVersion: String,
    /** Version the extension reported over the bridge (its manifest version). */
    val reportedVersion: String,
    /** Build marker compiled into the extension bundle. */
    val marker: String,
)

/** Times on the elapsedRealtime clock; 0 means not reached yet. */
data class EngineTimings(
    val processStartElapsed: Long,
    val engineStartElapsed: Long,
    val extensionInstalledElapsed: Long = 0,
    val bridgeConnectedElapsed: Long = 0,
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
