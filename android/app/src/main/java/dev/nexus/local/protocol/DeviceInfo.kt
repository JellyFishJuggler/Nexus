package dev.nexus.local.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Peer identity exchanged in `system.hello`, `pairing.request` and
 * `pairing.accept` (protocol/connection.md, protocol/pairing.md).
 *
 * Mirrors the Rust `DeviceInfo` struct:
 * `{device_id, name, platform, pv, features}`.
 */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val pv: Int,
    val features: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("name", name)
        put("platform", platform)
        put("pv", pv)
        put("features", JSONArray(features))
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val PLATFORM = "android"

        /**
         * Features this build actually implements. Kept accurate on purpose:
         * peers use it to decide which UI affordances to offer.
         *
         * `clipboard` is implemented as a foreground-only feature
         * (protocol/clipboard.md § "Android limitations"); file transfer and
         * media control do not exist yet.
         */
        fun localFeatures(): List<String> = listOf("clipboard")

        /**
         * Parse a peer identity, rejecting malformed payloads.
         *
         * @return null when required fields are missing or ill-typed
         * (`pv` must be a positive integer).
         */
        fun parse(json: JSONObject): DeviceInfo? {
            val deviceId = json.optString("device_id", "")
            val name = json.optString("name", "")
            val platform = json.optString("platform", "")
            val pv = json.optInt("pv", -1)
            if (deviceId.isEmpty() || name.isEmpty() || platform.isEmpty() || pv < 1) {
                return null
            }
            val features = mutableListOf<String>()
            val array = json.optJSONArray("features")
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optString(i, "").takeIf { it.isNotEmpty() }?.let { features += it }
                }
            }
            return DeviceInfo(deviceId, name, platform, pv, features)
        }
    }
}
