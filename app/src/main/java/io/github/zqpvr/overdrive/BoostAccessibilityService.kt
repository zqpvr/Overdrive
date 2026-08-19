package io.github.zqpvr.overdrive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The silent host. It exists to be a process, and nothing else.
 *
 * The system binds accessibility services persistently, restarts them if they die, and re-binds
 * them at boot before the user unlocks. That is exactly the lifetime the global audio effect
 * needs, and it comes with no notification, no wakelock and no scheduled work.
 *
 * The one thing this service does on connect is throw away its own subscriptions: event types are
 * zeroed, no flags are set, and window content retrieval is off in the manifest config. It cannot
 * read the screen, and the platform will not deliver it anything to read. The XML declares one
 * event type only because a config with none is rejected outright.
 */
class BoostAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = 0
            flags = 0
            notificationTimeout = 0
            packageNames = emptyArray()
        }

        connected = true
        Log.i(TAG, "silent host connected")
        BoostState.ensureInit(this)
        BoostController.onHostStarted(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false

        // The user has just revoked the lifeline. Try to hand the boost over to the notification
        // host rather than letting it die silently. This is best-effort: the process is on its
        // way out and the platform may refuse the start, which is why the UI reads the live host
        // rather than assuming this worked.
        if (BoostState.enabled.value) {
            runCatching { BoostForegroundService.start(this) }
                .onFailure { Log.w(TAG, "could not hand off to notification host", it) }
        } else {
            BoostController.onHostStopped()
        }

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BoostA11yHost"

        /**
         * Ground truth for whether the silent host holds a process. The settings string only says
         * the user permitted it; this says the system actually bound it.
         */
        @Volatile
        var connected: Boolean = false
            private set
    }
}
