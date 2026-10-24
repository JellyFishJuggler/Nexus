package dev.nexus.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat

/**
 * Small, local-only preference store for behaviours that are not device
 * identity: background start and phone→computer notification mirroring.
 *
 * Notification mirroring is **opt-in** (default off). Starting on boot is on
 * by default because the user installed a companion that is expected to be
 * reachable; it can be switched off in Settings.
 */
object NexusPrefs {
    private const val FILE = "nexus_prefs"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_MIRROR_NOTIFICATIONS = "mirror_notifications"
    private const val KEY_HIDE_NOTIFICATION_TEXT = "hide_notification_text"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Keep the local link ready after a reboot. Default: on. */
    fun startOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_START_ON_BOOT, true)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    /** Mirror phone notifications to the connected computer. Default: off. */
    fun mirrorNotifications(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MIRROR_NOTIFICATIONS, false)

    fun setMirrorNotifications(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIRROR_NOTIFICATIONS, enabled).apply()
    }

    /** Send only the app name and title, never the notification body. */
    fun hideNotificationText(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_NOTIFICATION_TEXT, false)

    fun setHideNotificationText(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_NOTIFICATION_TEXT, enabled).apply()
    }

    /**
     * Whether the user has granted this app notification-listener access.
     * Checked live (not cached) because the user changes it in system
     * settings, outside the app.
     */
    fun notificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
}
