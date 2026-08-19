package io.github.zqpvr.overdrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles boot and the periodic safe-volume re-apply.
 *
 * On boot this also brings the notification host back when it is the one in use. Starting a
 * foreground service from BOOT_COMPLETED is an explicit exemption from the background-start
 * rules, and specialUse is not among the types Android 15 blocks from that path. When the
 * accessibility host is the one in use there is nothing to do here, because the system binds
 * accessibility services at boot on its own.
 */
class SafeVolumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        BoostState.ensureInit(context)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED

        if (BoostState.manageSafeVolume.value) {
            if (boot) {
                // Too early to write yet; AudioService has not settled the state. Schedule the
                // first attempt past that window and let the repeating alarm carry it from there.
                SafeVolumeScheduler.schedule(context, SafeVolumeScheduler.BOOT_DELAY_MS)
            } else {
                val ok = SafeVolume.lift(context)
                Log.i(TAG, "safe volume re-applied: $ok")
            }
        }

        if (boot && BoostState.enabled.value && !BoostAccessibilityService.connected) {
            BoostForegroundService.start(context)
        }
    }

    companion object {
        private const val TAG = "SafeVolumeReceiver"
        const val ACTION_REAPPLY = "io.github.zqpvr.overdrive.REAPPLY_SAFE_VOLUME"
    }
}
