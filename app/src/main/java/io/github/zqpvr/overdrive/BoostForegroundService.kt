package io.github.zqpvr.overdrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * The fallback host, used when the accessibility lifeline has not been granted.
 *
 * Type specialUse is deliberate. It is honest about what this service does, and unlike
 * mediaPlayback it is not on Android 15's list of foreground service types that a BOOT_COMPLETED
 * receiver is forbidden from starting, so the boost can come back by itself after a reboot.
 *
 * The notification is IMPORTANCE_MIN with no sound, no badge and no lockscreen presence, which is
 * the quietest a mandatory foreground notification is allowed to be.
 */
class BoostForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        BoostState.ensureInit(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BoostController.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        running = true
        instance = this
        BoostController.onHostStarted(this)

        // START_STICKY so the platform rebuilds the host if it is ever killed for memory. There
        // is nothing to restore beyond the effect itself, which onHostStarted reattaches.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        BoostController.onHostStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val gain = BoostState.gainDb.value
        val attached = BoostEngine.attached

        val text = when {
            attached -> "Boosting +%.0f dB".format(gain)
            BoostState.onlyWhilePlaying.value -> "Armed at +%.0f dB, waiting for playback".format(gain)
            else -> "Idle"
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BoostForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overdrive)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "BoostFgHost"
        private const val CHANNEL_ID = "boost"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.zqpvr.overdrive.STOP"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var instance: BoostForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, BoostForegroundService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "could not start notification host", it) }
        }

        fun stop(context: Context) {
            if (!running) return
            context.stopService(Intent(context, BoostForegroundService::class.java))
        }

        /**
         * Redraws the notification text in place. No-op when this host is not the one in use.
         *
         * This posts straight to NotificationManager rather than restarting the service, because
         * onStartCommand feeds back into the controller and a restart would loop.
         */
        fun refresh(context: Context) {
            val service = instance ?: return
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, service.buildNotification())
            }
        }
    }
}
