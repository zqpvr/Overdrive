package io.github.zqpvr.overdrive

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Tier 0: the part of the boost that needs no running process at all.
 *
 * Android clamps output on wired and Bluetooth headsets to satisfy IEC 62368-1 / EN 50332, and
 * nags before letting you past the limit. The clamp is driven by a single secure setting, so
 * clearing it lifts a real ceiling on headphone output for free, with no effect attached and no
 * process alive.
 *
 * The permission is never granted at install time. The user grants it once over adb with
 * [grantCommand]; after that this works forever, including across reboots.
 *
 * AOSP re-arms the state periodically and at boot, which is why [SafeVolumeScheduler] exists.
 * Android 14 also added a second, separate mechanism on top of this one: computed sound dose,
 * tracked inside audioserver over a rolling seven days. That is not reachable from here, and in
 * some regions it is not disableable at all.
 */
object SafeVolume {

    private const val TAG = "SafeVolume"

    /** Settings.Global.AUDIO_SAFE_VOLUME_STATE, which is hidden from the SDK. */
    private const val KEY = "audio_safe_volume_state"

    /** SAFE_MEDIA_VOLUME_DISABLED in AudioService. */
    private const val STATE_DISABLED = 2

    fun permissionGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun currentState(context: Context): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, KEY) }.getOrNull()

    fun isLifted(context: Context): Boolean = currentState(context) == STATE_DISABLED

    /** Returns false if the permission is missing or the write was rejected. */
    fun lift(context: Context): Boolean {
        if (!permissionGranted(context)) return false
        return runCatching {
            Settings.Global.putInt(context.contentResolver, KEY, STATE_DISABLED)
        }.onFailure { Log.w(TAG, "safe volume write rejected", it) }.getOrDefault(false)
    }

    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
}

/**
 * Re-applies the safe-volume state on a slow, inexact schedule.
 *
 * RTC rather than RTC_WAKEUP, and inexact rather than exact, on purpose: this must never wake the
 * device or hold it awake. The alarm fires whenever the phone next happens to be up, which for a
 * setting measured in hours is entirely good enough and costs nothing.
 */
object SafeVolumeScheduler {

    private const val REQUEST_CODE = 100
    private const val INTERVAL_MS = 6L * 60L * 60L * 1000L

    /**
     * AudioService settles the safe-volume state roughly 30 seconds after boot, so a write issued
     * any earlier is simply overwritten. 90 seconds clears that with room to spare.
     */
    const val BOOT_DELAY_MS = 90_000L

    fun schedule(context: Context, initialDelayMs: Long = INTERVAL_MS) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + initialDelayMs,
            INTERVAL_MS,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SafeVolumeReceiver::class.java).setAction(SafeVolumeReceiver.ACTION_REAPPLY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
