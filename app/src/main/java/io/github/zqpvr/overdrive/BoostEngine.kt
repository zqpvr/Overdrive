package io.github.zqpvr.overdrive

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Owns the pair of effects hanging off the global output mix.
 *
 * [LoudnessEnhancer] supplies the gain and [DynamicsProcessing] supplies a brickwall limiter, so
 * the extra gain runs into a ceiling rather than into the clipper. Both attach to audio session
 * 0, which AudioFlinger treats as the global output mix: every app's media audio passes through
 * it. AOSP marks session 0 deprecated and logs a warning when you attach to it, but it has never
 * been removed, and it is the only route to another app's audio that does not involve
 * MediaProjection.
 *
 * The lifetime rule is the constraint the rest of this app is built around. AudioFlinger holds
 * each effect through an EffectHandle binder owned by this process. When the process dies the
 * handle's destructor runs and the effect is torn down with it, so the boost cannot outlive us.
 * That is why something has to keep the process bound; see [HostManager].
 */
object BoostEngine {

    private const val TAG = "BoostEngine"

    /** AudioFlinger's global output mix. */
    private const val GLOBAL_SESSION = 0

    /**
     * Priority requested for the limiter. Higher wins when two clients want the same effect type
     * in the same chain, and this is deliberately negative so Overdrive always loses that contest.
     *
     * The contest is real. AudioFlinger reuses one effect module per UUID within a chain rather
     * than instantiating a second one, so a system-wide equalizer running on session 0 is asking
     * for the same DynamicsProcessing instance Overdrive wants. Wavelet in legacy mode is the
     * common case. Yielding costs a limiter that LoudnessEnhancer partly duplicates anyway;
     * winning would silently break the other application's EQ, which is worse.
     */
    private const val LIMITER_PRIORITY = -1

    /**
     * Ceiling the limiter holds the mix under, in dBFS. Slightly below full scale, because the
     * limiter is a feedback design and a hair of headroom keeps transients off the rail.
     */
    private const val LIMITER_THRESHOLD_DB = -0.5f

    /** The output mix is stereo on every device this runs on. */
    private const val CHANNEL_COUNT = 2

    private var enhancer: LoudnessEnhancer? = null
    private var dynamics: DynamicsProcessing? = null

    @Volatile
    var attached: Boolean = false
        private set

    /** Last failure reason, surfaced in the UI so a silent no-op is never mistaken for success. */
    @Volatile
    var lastError: String? = null
        private set

    /** What became of the limiter on the last attach, reported in the UI. */
    @Volatile
    var limiterState: LimiterState = LimiterState.NONE
        private set

    enum class LimiterState {
        /** Nothing is attached. */
        NONE,

        /** Overdrive owns the DynamicsProcessing instance and is limiting. */
        ACTIVE,

        /** Skipped on purpose so another application can own it. */
        YIELDED_BY_CHOICE,

        /** Another client already held control, so the handle was released again. */
        YIELDED_TO_OTHER,

        /** Creation failed outright, which happens where the HAL ships no effect bundle. */
        UNAVAILABLE
    }

    /**
     * Attaches the gain stage at [gainDb], replacing anything already attached, and adds the
     * limiter unless [yieldLimiter] is set or another client already owns it.
     *
     * Returns false and leaves nothing attached only if the gain stage itself could not be
     * created. A missing limiter is not fatal, because LoudnessEnhancer carries an adaptive
     * dynamic range compressor of its own that compresses anything amplified past full scale.
     * That is weaker than a real brickwall limiter but it is not nothing.
     */
    @Synchronized
    fun attach(gainDb: Float, yieldLimiter: Boolean): Boolean {
        detach()

        val enhancerOk = runCatching {
            LoudnessEnhancer(GLOBAL_SESSION).also { effect ->
                effect.setTargetGain(millibels(gainDb))
                effect.enabled = true
                enhancer = effect
            }
        }.onFailure { failed("LoudnessEnhancer", it) }.isSuccess

        if (!enhancerOk) {
            detach()
            return false
        }

        limiterState = if (yieldLimiter) LimiterState.YIELDED_BY_CHOICE else attachLimiter()

        lastError = null
        attached = true
        Log.i(TAG, "attached at ${gainDb}dB, limiter $limiterState")
        return true
    }

    /**
     * Creating the effect succeeds even when another client already controls the underlying
     * module, so control has to be checked rather than assumed. Without it every parameter write
     * is accepted and then discarded, which looks identical to working from the outside. The
     * handle is dropped in that case instead of being kept as a decoration.
     */
    private fun attachLimiter(): LimiterState {
        val config = runCatching {
            DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                CHANNEL_COUNT,
                /* preEqInUse = */ false, /* preEqBandCount = */ 0,
                /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                /* limiterInUse = */ true
            ).build()
        }.getOrNull() ?: return LimiterState.UNAVAILABLE

        val effect = runCatching { DynamicsProcessing(LIMITER_PRIORITY, GLOBAL_SESSION, config) }
            .onFailure { Log.w(TAG, "limiter unavailable", it) }
            .getOrNull() ?: return LimiterState.UNAVAILABLE

        if (!runCatching { effect.hasControl() }.getOrDefault(false)) {
            Log.i(TAG, "limiter already owned by another client, releasing")
            runCatching { effect.release() }
            return LimiterState.YIELDED_TO_OTHER
        }

        return runCatching {
            for (channel in 0 until CHANNEL_COUNT) {
                effect.setLimiterByChannelIndex(channel, buildLimiter())
            }
            effect.enabled = true
            dynamics = effect
            LimiterState.ACTIVE
        }.onFailure {
            Log.w(TAG, "limiter configuration rejected", it)
            runCatching { effect.release() }
        }.getOrDefault(LimiterState.YIELDED_TO_OTHER)
    }

    /**
     * Retargets the gain in place. Cheaper than a reattach and, more importantly, it does not
     * drop the effect for the instant it would take to rebuild, which is audible.
     */
    @Synchronized
    fun setGain(gainDb: Float) {
        val effect = enhancer ?: return
        runCatching { effect.setTargetGain(millibels(gainDb)) }
            .onFailure { failed("setTargetGain", it) }
    }

    @Synchronized
    fun detach() {
        runCatching { enhancer?.release() }
        runCatching { dynamics?.release() }
        enhancer = null
        dynamics = null
        attached = false
        limiterState = LimiterState.NONE
    }

    /**
     * True when the handles still exist and we still hold control of them.
     *
     * Both halves matter. A handle can go stale after a playback stop/start cycle on some
     * devices, and control can be taken away entirely by a higher-priority client such as an
     * OEM equalizer, in which case our writes are accepted and then ignored.
     */
    @Synchronized
    fun healthy(): Boolean {
        val effect = enhancer ?: return false
        return runCatching { effect.hasControl() && effect.enabled }.getOrDefault(false)
    }

    /**
     * A limiter, not a compressor: 20:1 above the threshold with a 1 ms attack catches peaks
     * without audibly pumping. Both channels share link group 0 so they duck together and the
     * stereo image does not wander when one side clips.
     */
    private fun buildLimiter() = DynamicsProcessing.Limiter(
        /* inUse = */ true,
        /* enabled = */ true,
        /* linkGroup = */ 0,
        /* attackTime = */ 1f,
        /* releaseTime = */ 60f,
        /* ratio = */ 20f,
        /* threshold = */ LIMITER_THRESHOLD_DB,
        /* postGain = */ 0f
    )

    /** LoudnessEnhancer takes millibels; the UI works in dB. */
    private fun millibels(gainDb: Float): Int = (gainDb * 100f).toInt()

    private fun failed(what: String, cause: Throwable) {
        lastError = "$what: ${cause.javaClass.simpleName}"
        Log.e(TAG, "$what failed", cause)
    }
}
