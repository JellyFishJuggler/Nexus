package dev.nexus.local.identity

import android.content.Context
import java.util.UUID

/**
 * Persistent device identity (SRS §12).
 *
 * Only the UUID is needed for discovery (Milestone A1); the certificate
 * material is added in Milestone A2 alongside the TLS listener.
 *
 * The identifier must survive network changes and must not be derived from
 * IP/hostname/MAC, so a random UUID persisted in app-private storage is used.
 */
data class DeviceIdentity(
    val deviceId: String,
    val name: String,
    val platform: String = "android"
) {
    companion object {
        private const val PREFS = "nexus_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"

        /** Platform identifier announced in mDNS (protocol/discovery.md). */
        const val PLATFORM = "android"

        /** Load (or create on first run) this installation's identity. */
        fun loadOrCreate(context: Context): DeviceIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
                val fresh = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
                fresh
            }
            val name = prefs.getString(KEY_DEVICE_NAME, null) ?: run {
                val fresh = defaultDeviceName(context)
                prefs.edit().putString(KEY_DEVICE_NAME, fresh).apply()
                fresh
            }
            return DeviceIdentity(deviceId, name, PLATFORM)
        }

        private fun defaultDeviceName(context: Context): String {
            return android.provider.Settings.Global.getString(
                context.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME
            ) ?: "Android Phone"
        }
    }
}
