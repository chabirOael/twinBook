package io.github.chabiroael.twinbook

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's settings of the web shell, kept in SharedPreferences: ad hiding (uBlock Origin on
 * or off, default on) and strict mode (twin-bridge cancels the site's logging beacons, default
 * off). Process-wide; main thread only.
 */
class ShellSettings private constructor(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("shell-settings", Context.MODE_PRIVATE)

    private val adHidingFlow = MutableStateFlow(prefs.getBoolean(KEY_AD_HIDING, true))
    val adHiding: StateFlow<Boolean> = adHidingFlow.asStateFlow()

    private val strictFlow = MutableStateFlow(prefs.getBoolean(KEY_STRICT, false))
    val strict: StateFlow<Boolean> = strictFlow.asStateFlow()

    fun setAdHiding(on: Boolean) {
        prefs.edit().putBoolean(KEY_AD_HIDING, on).apply()
        adHidingFlow.value = on
    }

    fun setStrict(on: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT, on).apply()
        strictFlow.value = on
    }

    companion object {
        private const val KEY_AD_HIDING = "adHiding"
        private const val KEY_STRICT = "strict"

        @Volatile
        private var instance: ShellSettings? = null

        fun get(context: Context): ShellSettings = instance ?: synchronized(this) { instance ?: ShellSettings(context).also { instance = it } }
    }
}
