package io.github.zqpvr.overdrive

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted user intent, plus the observable runtime status the UI renders.
 *
 * Every entry point calls [ensureInit] because any of them can be the first thing the system
 * spins up: the launcher activity, the tile, the boot receiver, or either host service.
 */
object BoostState {

    const val MIN_GAIN_DB = 0f
    const val MAX_GAIN_DB = 20f
    const val DEFAULT_GAIN_DB = 6f

    private const val PREFS = "overdrive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GAIN = "gain_db"
    private const val KEY_ONLY_WHILE_PLAYING = "only_while_playing"
    private const val KEY_SAFE_VOLUME = "manage_safe_volume"
    private const val KEY_YIELD_LIMITER = "yield_limiter"

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    private val _gainDb = MutableStateFlow(DEFAULT_GAIN_DB)
    private val _onlyWhilePlaying = MutableStateFlow(true)
    private val _manageSafeVolume = MutableStateFlow(false)
    private val _yieldLimiter = MutableStateFlow(false)
    private val _status = MutableStateFlow(Status())

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val gainDb: StateFlow<Float> = _gainDb.asStateFlow()
    val onlyWhilePlaying: StateFlow<Boolean> = _onlyWhilePlaying.asStateFlow()
    val manageSafeVolume: StateFlow<Boolean> = _manageSafeVolume.asStateFlow()
    val yieldLimiter: StateFlow<Boolean> = _yieldLimiter.asStateFlow()
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * What the engine is actually doing right now, as opposed to what the user asked for.
     * [attached] is false while boost is on but playback is idle in only-while-playing mode.
     */
    data class Status(
        val attached: Boolean = false,
        val host: HostManager.Host = HostManager.Host.NONE,
        val limiter: BoostEngine.LimiterState = BoostEngine.LimiterState.NONE,
        val error: String? = null
    )

    @Synchronized
    fun ensureInit(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
        _gainDb.value = p.getFloat(KEY_GAIN, DEFAULT_GAIN_DB)
        _onlyWhilePlaying.value = p.getBoolean(KEY_ONLY_WHILE_PLAYING, true)
        _manageSafeVolume.value = p.getBoolean(KEY_SAFE_VOLUME, false)

        // Default the limiter to yielded when a system-wide equalizer is already installed, since
        // fighting it for the same effect module breaks its EQ rather than gaining anything here.
        // Detection only picks the initial value; once the user has an opinion it is theirs.
        _yieldLimiter.value = p.getBoolean(
            KEY_YIELD_LIMITER,
            Coexistence.installedEqualizer(context) != null
        )
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    fun setGainDb(value: Float) {
        val clamped = value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        _gainDb.value = clamped
        prefs?.edit()?.putFloat(KEY_GAIN, clamped)?.apply()
    }

    fun setOnlyWhilePlaying(value: Boolean) {
        _onlyWhilePlaying.value = value
        prefs?.edit()?.putBoolean(KEY_ONLY_WHILE_PLAYING, value)?.apply()
    }

    fun setManageSafeVolume(value: Boolean) {
        _manageSafeVolume.value = value
        prefs?.edit()?.putBoolean(KEY_SAFE_VOLUME, value)?.apply()
    }

    fun setYieldLimiter(value: Boolean) {
        _yieldLimiter.value = value
        prefs?.edit()?.putBoolean(KEY_YIELD_LIMITER, value)?.apply()
    }

    internal fun publishStatus(
        attached: Boolean,
        host: HostManager.Host,
        limiter: BoostEngine.LimiterState,
        error: String?
    ) {
        _status.value = Status(attached, host, limiter, error)
    }
}
