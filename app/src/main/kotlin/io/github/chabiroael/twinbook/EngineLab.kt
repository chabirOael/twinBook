package io.github.chabiroael.twinbook

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.chabiroael.twinbook.engine.Engine
import io.github.chabiroael.twinbook.engine.EngineConfig
import io.github.chabiroael.twinbook.engine.EngineSession
import io.github.chabiroael.twinbook.engine.PageState
import io.github.chabiroael.twinbook.engine.UserAgentProfile
import io.github.chabiroael.twinbook.engine.bridge.BridgeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-wide state of the engine lab screen: the engine, one visible session showing a page
 * bundled in the APK, and what the extension reported over the bridge. Survives activity
 * recreation.
 */
class EngineLab private constructor(context: Context) {
    val engine: Engine = Engine.start(context, EngineConfig(debug = BuildConfig.DEBUG))
    val session: EngineSession = engine.newSession(UserAgentProfile.MOBILE, "lab")

    private val extensionVersionFlow = MutableStateFlow<String?>(null)

    /** Version the extension reported in reply to an `extension.info` bridge request. */
    val extensionVersion: StateFlow<String?> = extensionVersionFlow.asStateFlow()

    private val startupFlow = MutableStateFlow(Startup())

    /** Start-up times, measured from process start. */
    val startup: StateFlow<Startup> = startupFlow.asStateFlow()

    val bridgeState: StateFlow<BridgeState> get() = engine.bridge.state
    val page: StateFlow<PageState> get() = session.page

    init {
        session.load(LAB_PAGE)
        val processStart = engine.timings.value.processStartElapsed
        engine.scope.launch {
            engine.awaitReady()
            val info = engine.bridge.request("extension.info")
            extensionVersionFlow.value = info.optString("version")
            val ready = engine.timings.value.bridgeConnectedElapsed - processStart
            startupFlow.value = startupFlow.value.copy(extensionReadyMs = ready)
            Log.i(TIMING_TAG, "extension ready ${ready} ms after process start (version ${info.optString("version")})")
        }
        engine.scope.launch {
            // The session's initial about:blank load also stops; wait for the lab page itself.
            session.page.first { it.url == LAB_PAGE && it.loadCount > 0 && !it.loading }
            val loaded = SystemClock.elapsedRealtime() - processStart
            startupFlow.value = startupFlow.value.copy(firstPageMs = loaded)
            Log.i(TIMING_TAG, "first page loaded ${loaded} ms after process start (${session.page.value.url})")
        }
    }

    data class Startup(val extensionReadyMs: Long? = null, val firstPageMs: Long? = null)

    companion object {
        const val LAB_PAGE = "resource://android/assets/lab/index.html"
        const val TIMING_TAG = "twinbook-timing"

        @Volatile
        private var instance: EngineLab? = null

        /** Main thread only. */
        fun get(context: Context): EngineLab = instance ?: EngineLab(context.applicationContext).also { instance = it }
    }
}
