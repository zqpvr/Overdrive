package io.github.zqpvr.overdrive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Decides which process-holder keeps the effect alive, and keeps exactly one of them running.
 *
 * Neither host does any work. The point of both is simply to be a process the system declines to
 * kill, because the effect dies with the process. They differ only in what they cost the user:
 * the accessibility host costs a scary-looking toggle in Settings, the foreground host costs a
 * permanent notification row. Battery cost is the same either way and is indistinguishable from
 * zero, since an idle process holding one binder handle takes no wakelocks and schedules nothing.
 */
object HostManager {

    enum class Host { NONE, ACCESSIBILITY, FOREGROUND }

    /** Whether the user has granted the accessibility lifeline in system settings. */
    fun accessibilityGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val target = ComponentName(context.packageName, BoostAccessibilityService::class.java.name)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    /** Which host is actually holding the process right now, as opposed to merely permitted. */
    fun activeHost(): Host = when {
        BoostAccessibilityService.connected -> Host.ACCESSIBILITY
        BoostForegroundService.running -> Host.FOREGROUND
        else -> Host.NONE
    }

    /**
     * Brings up a host if none is holding the process.
     *
     * When the accessibility service is connected there is nothing to start, since the system
     * already binds it; the foreground service is torn down in that case so the user is not
     * paying a notification for a host that is not being used.
     */
    fun ensureHost(context: Context) {
        if (BoostAccessibilityService.connected) {
            BoostForegroundService.stop(context)
            return
        }
        BoostForegroundService.start(context)
    }

    fun releaseHost(context: Context) {
        BoostForegroundService.stop(context)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
