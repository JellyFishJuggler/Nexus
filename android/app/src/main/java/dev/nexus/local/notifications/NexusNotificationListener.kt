package dev.nexus.local.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.nexus.local.NexusPrefs
import dev.nexus.local.NexusRuntime
import java.util.concurrent.Executors

/**
 * Mirrors posted notifications to the connected computer
 * (`protocol/notifications.md`).
 *
 * Privacy rules, enforced here rather than in the UI:
 *  - mirroring is opt-in; nothing is read or sent unless the user enabled it
 *    **and** granted notification-listener access;
 *  - only the app label, title and body are extracted — never keys, extras,
 *    icons, actions or the untruncated original;
 *  - "Hide notification text" removes the body before anything is sent;
 *  - notification content is never written to logs or to disk.
 *
 * The listener does not create a connection: if the runtime/service is not
 * running there is no trusted session to mirror to, so the notification is
 * dropped.
 */
class NexusNotificationListener : NotificationListenerService() {

    /** Fingerprint → last forwarded time (elapsed ms), for duplicate posts. */
    private val seen = HashMap<String, Long>()

    /**
     * Sends happen on a background thread: callbacks arrive on the main
     * thread and the socket write must never run there.
     */
    private val forwarder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nexus-notify").apply { isDaemon = true }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (!NexusPrefs.mirrorNotifications(applicationContext)) return
        forwarder.execute { forward(posted) }
    }

    private fun forward(posted: StatusBarNotification) {
        val context = applicationContext
        // Re-check: the setting may have changed while this task was queued.
        if (!NexusPrefs.mirrorNotifications(context)) return

        val notification = posted.notification ?: return
        val summary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (!NotificationMapper.shouldMirror(
                packageName = posted.packageName,
                localPackage = packageName,
                ongoing = posted.isOngoing,
                groupSummary = summary
            )
        ) {
            return
        }

        val controller = NexusRuntime.existing() ?: return
        if (!controller.isSessionReady()) return
        if (!controller.peerSupportsNotifications()) return

        val extras = notification.extras ?: return
        val mapped = NotificationMapper.map(
            appLabel = appLabel(posted.packageName),
            title = extras.getCharSequence(Notification.EXTRA_TITLE),
            text = extras.getCharSequence(Notification.EXTRA_TEXT),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            hideText = NexusPrefs.hideNotificationText(context)
        ) ?: return

        if (isDuplicate(mapped.fingerprint)) return

        if (controller.sendNotification(mapped.app, mapped.title, mapped.text)) {
            // Never log the notification content.
            Log.i(TAG, "mirrored a notification from ${mapped.app}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onDestroy() {
        forwarder.shutdownNow()
        super.onDestroy()
    }

    private fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }

    /** True when the same notification content was forwarded very recently. */
    private fun isDuplicate(fingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            if (seen.size > 100) {
                seen.entries.removeAll { now - it.value > DUPLICATE_WINDOW_MS }
            }
            val last = seen[fingerprint]
            if (last != null && now - last < DUPLICATE_WINDOW_MS) return true
            seen[fingerprint] = now
            return false
        }
    }

    private companion object {
        const val TAG = "NexusNotifications"
        const val DUPLICATE_WINDOW_MS = 4_000L
    }
}
