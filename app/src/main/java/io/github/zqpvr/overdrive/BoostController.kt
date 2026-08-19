package io.github.zqpvr.overdrive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Reconciles what the user asked for with what the engine is doing.
 *
 * All effect work happens on a dedicated thread. Creating an AudioEffect is a blocking binder
 * round-trip into audioserver, and the playback callbacks that trigger it arrive at moments we do
 * not control, so none of it belongs on the main thread.
 */
object BoostController {

    private const val TAG = "BoostController"

    /**
     * How long playback must stay idle before the effect is dropped. A track change or a seek
     * leaves the mix briefly idle, and tearing the effect down for that is both pointless and
     * audible on the first frames of the next track.
     */
    private const val IDLE_GRACE_MS = 12_000L

    private var context: Context? = null
    private var audio: AudioManager? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var callback: AudioManager.AudioPlaybackCallback? = null

    private val detachToken = Any()

    /** Usages that count as media playback. Calls and notifications are deliberately excluded. */
    private val boostedUsages = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN
    )

    /** Called by whichever host has just been given a process. */
    @Synchronized
    fun onHostStarted(context: Context) {
        val app = context.applicationContext
        BoostState.ensureInit(app)
        this.context = app

        if (thread == null) {
            val t = HandlerThread("overdrive-audio")
            t.start()
            thread = t
            handler = Handler(t.looper)
        }

        val manager = app.getSystemService(AudioManager::class.java)
        audio = manager

        if (callback == null) {
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    handler?.post { evaluate(configs) }
                }
            }
            callback = cb
            manager.registerAudioPlaybackCallback(cb, handler)
        }

        sync()
    }

    /** Called when the host is going away. The effect goes with it regardless; this is tidy-up. */
    @Synchronized
    fun onHostStopped() {
        callback?.let { audio?.unregisterAudioPlaybackCallback(it) }
        callback = null
        handler?.removeCallbacksAndMessages(detachToken)
        handler?.post { BoostEngine.detach() }
        publish()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        BoostState.ensureInit(context)
        BoostState.setEnabled(enabled)

        if (enabled) {
            HostManager.ensureHost(context)
            // A host may already be up, in which case nothing will call onHostStarted for us.
            if (HostManager.activeHost() != HostManager.Host.NONE) onHostStarted(context)
        } else {
            handler?.removeCallbacksAndMessages(detachToken)
            val posted = handler?.post { BoostEngine.detach() } ?: false
            if (!posted) BoostEngine.detach()
            HostManager.releaseHost(context)
            publish()
        }
    }

    /**
     * Gain changes are retargeted in place rather than reattached, so dragging the slider does
     * not chop the audio into fragments.
     */
    fun setGain(context: Context, gainDb: Float) {
        BoostState.ensureInit(context)
        BoostState.setGainDb(gainDb)
        handler?.post {
            if (BoostEngine.attached) BoostEngine.setGain(BoostState.gainDb.value)
        }
        BoostForegroundService.refresh(context)
    }

    /** Reconciles engine state against user intent. Safe to call from anywhere, at any time. */
    fun sync() {
        val h = handler
        if (h == null) evaluate(null) else h.post { evaluate(null) }
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>?) {
        val ctx = context ?: return
        val wantBoost = BoostState.enabled.value
        val gated = BoostState.onlyWhilePlaying.value
        val playing = if (gated) isMediaPlaying(configs) else true

        if (wantBoost && playing) {
            handler?.removeCallbacksAndMessages(detachToken)
            // healthy() is the important half. A handle can outlive a playback stop as a Kotlin
            // object while AudioFlinger has already dropped or reassigned it, and control can be
            // taken by a higher-priority client such as an OEM equalizer, after which our writes
            // are accepted and then quietly ignored. Rebuilding is the only reliable repair.
            if (!BoostEngine.attached || !BoostEngine.healthy()) {
                BoostEngine.attach(BoostState.gainDb.value, BoostState.yieldLimiter.value)
            } else {
                BoostEngine.setGain(BoostState.gainDb.value)
            }
        } else if (BoostEngine.attached) {
            if (wantBoost && gated) scheduleIdleDetach() else BoostEngine.detach()
        }

        publish()
        BoostForegroundService.refresh(ctx)
    }

    private fun scheduleIdleDetach() {
        val h = handler ?: return
        h.removeCallbacksAndMessages(detachToken)
        h.postDelayed({
            if (!isMediaPlaying(null)) {
                Log.i(TAG, "playback idle, releasing effect")
                BoostEngine.detach()
                publish()
                context?.let { BoostForegroundService.refresh(it) }
            }
        }, detachToken, IDLE_GRACE_MS)
    }

    /**
     * isMusicActive on its own is unreliable across transitions, so the playback configuration
     * list is preferred whenever the framework handed us one.
     */
    private fun isMediaPlaying(configs: List<AudioPlaybackConfiguration>?): Boolean {
        val manager = audio ?: return false
        val list = configs ?: runCatching { manager.activePlaybackConfigurations }.getOrNull()

        val byConfig = list?.any { it.audioAttributes.usage in boostedUsages } ?: false
        return byConfig || manager.isMusicActive
    }

    /**
     * Whether the limiter is claimed is decided at attach time, so changing it has to rebuild the
     * effect rather than adjust a parameter.
     */
    fun setYieldLimiter(context: Context, shouldYield: Boolean) {
        BoostState.ensureInit(context)
        BoostState.setYieldLimiter(shouldYield)
        handler?.post {
            if (BoostEngine.attached) {
                BoostEngine.attach(BoostState.gainDb.value, shouldYield)
                publish()
            }
        }
    }

    private fun publish() {
        BoostState.publishStatus(
            attached = BoostEngine.attached,
            host = HostManager.activeHost(),
            limiter = BoostEngine.limiterState,
            error = BoostEngine.lastError
        )
    }
}
