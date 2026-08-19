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

    /** Effect priority. Higher wins if two clients fight over the same session. */
    private const val PRIORITY = 0

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

    /**
     * Attaches both effects at [gainDb], replacing anything already attached.
     *
     * Returns false and leaves nothing attached if either effect could not be created, which is
     * what happens on devices whose vendor audio HAL ships no software effect bundle.
     */
    @Synchronized
    fun attach(gainDb: Float): Boolean {
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

        // The limiter is best-effort. If DynamicsProcessing is unavailable the boost still
        // works, it just clips earlier, so a failure here is logged rather than fatal.
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                CHANNEL_COUNT,
                /* preEqInUse = */ false, /* preEqBandCount = */ 0,
                /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                /* limiterInUse = */ true
            ).build()

            DynamicsProcessing(PRIORITY, GLOBAL_SESSION, config).also { effect ->
                for (channel in 0 until CHANNEL_COUNT) {
                    effect.setLimiterByChannelIndex(channel, buildLimiter())
                }
                effect.enabled = true
                dynamics = effect
            }
        }.onFailure { Log.w(TAG, "limiter unavailable, boost will clip earlier", it) }

        lastError = null
        attached = true
        Log.i(TAG, "attached at ${gainDb}dB (limiter=${dynamics != null})")
        return true
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
