package dev.nexus.local.trust

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A device this installation has explicitly paired with.
 *
 * Mirrors the Rust `TrustedDevice` (protocol/pairing.md § "Trust store"):
 * `{device_id, name, platform, cert_sha256, cert_pem, paired_at}`.
 */
data class TrustedDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    /** SHA-256 fingerprint of the pinned certificate, lowercase hex. */
    val certSha256: String,
    /** The pinned certificate itself, PEM encoded. */
    val certPem: String,
    /** Unix time (seconds) the pairing completed. */
    val pairedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("name", name)
        put("platform", platform)
        put("cert_sha256", certSha256)
        put("cert_pem", certPem)
        put("paired_at", pairedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): TrustedDevice? {
            val deviceId = json.optString("device_id", "")
            val certSha256 = json.optString("cert_sha256", "")
            if (deviceId.isEmpty() || certSha256.isEmpty()) return null
            return TrustedDevice(
                deviceId = deviceId,
                name = json.optString("name", ""),
                platform = json.optString("platform", ""),
                certSha256 = certSha256,
                certPem = json.optString("cert_pem", ""),
                pairedAt = json.optLong("paired_at", 0L)
            )
        }
    }
}

/**
 * Persisted set of trusted devices (`trusted_devices.json`, same shape as
 * the Rust implementation).
 *
 * A missing or corrupt store is treated as **empty**: trust is never gained
 * by a parse failure, so this fails safe. Access is synchronized; the store
 * is shared between the UI thread, connection read threads, and timeout
 * threads.
 */
class TrustStore private constructor(private val file: File) {

    private val lock = Any()
    private var devices: MutableList<TrustedDevice> = mutableListOf()

    /** Snapshot of the trusted devices. */
    fun devices(): List<TrustedDevice> = synchronized(lock) { devices.toList() }

    fun isEmpty(): Boolean = synchronized(lock) { devices.isEmpty() }

    fun byFingerprint(fingerprint: String): TrustedDevice? =
        synchronized(lock) { devices.firstOrNull { it.certSha256 == fingerprint } }

    fun byDeviceId(deviceId: String): TrustedDevice? =
        synchronized(lock) { devices.firstOrNull { it.deviceId == deviceId } }

    /** Add or replace a device, keyed by fingerprint and device id. */
    fun insert(device: TrustedDevice) {
        synchronized(lock) {
            devices.removeAll {
                it.certSha256 == device.certSha256 || it.deviceId == device.deviceId
            }
            devices.add(device)
            persistLocked()
        }
    }

    /** Remove a device by device id. Returns whether anything was removed. */
    fun remove(deviceId: String): Boolean = synchronized(lock) {
        val removed = devices.removeAll { it.deviceId == deviceId }
        if (removed) persistLocked()
        removed
    }

    private fun persistLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(encode(devices))
        }.onFailure { Log.e(TAG, "couldn't save trust store: $it") }
    }

    private fun loadLocked() {
        if (!file.isFile) return
        devices = runCatching {
            decode(file.readText())
        }.getOrElse {
            // Fail safe: a store we cannot read grants no trust.
            Log.e(TAG, "trust store is unreadable ($it); treating as empty")
            mutableListOf()
        }
    }

    companion object {
        private const val TAG = "NexusTrust"
        private const val DIR = "nexus"
        private const val FILE = "trusted_devices.json"

        fun load(context: Context): TrustStore {
            val store = TrustStore(File(File(context.filesDir, DIR).apply { mkdirs() }, FILE))
            store.loadLocked()
            return store
        }

        /** Serialize the store exactly like the Rust `trusted_devices.json`. */
        fun encode(devices: List<TrustedDevice>): String {
            val root = JSONObject().apply {
                put("version", 1)
                put("devices", JSONArray().apply { devices.forEach { put(it.toJson()) } })
            }
            return root.toString(2)
        }

        /**
         * Parse a trust store document. Throws on malformed JSON or a
         * missing/!array `devices` field, so callers fail safe rather than
         * silently trusting a subtree.
         */
        fun decode(text: String): MutableList<TrustedDevice> {
            val root = JSONObject(text)
            val array = root.getJSONArray("devices")
            val list = mutableListOf<TrustedDevice>()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { TrustedDevice.fromJson(it) }?.let { list += it }
            }
            return list
        }

        /** SHA-256 fingerprint (lowercase hex) of certificate DER bytes. */
        fun fingerprintOf(certDer: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(certDer)
                .joinToString("") { "%02x".format(it) }
    }
}
