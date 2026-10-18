package dev.nexus.local.discovery

/**
 * A peer discovered via mDNS, parsed per protocol/discovery.md.
 *
 * Required TXT keys: pv (protocol version), id (device id),
 * nm (device name), pf (platform).
 */
data class PeerInfo(
    /** Fully-qualified service name, used for de-duplication and lost events. */
    val serviceName: String,
    val deviceId: String,
    val name: String,
    val platform: String,
    val protocolVersion: Int,
    val host: String,
    val port: Int
) {
    companion object {
        const val KEY_PV = "pv"
        const val KEY_ID = "id"
        const val KEY_NM = "nm"
        const val KEY_PF = "pf"

        /**
         * Build a PeerInfo from a resolved NsdServiceInfo, or null when the
         * record is malformed/incomplete (missing or unparsable required TXT
         * keys, missing host/port). Never fabricates values.
         */
        fun from(
            serviceName: String,
            host: String?,
            port: Int,
            attributes: Map<String, ByteArray>?
        ): PeerInfo? {
            if (host.isNullOrBlank() || port !in 1..65535) return null
            if (attributes == null) return null

            val pv = attributes[KEY_PV]?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: return null
            val id = attributes[KEY_ID]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() } ?: return null
            val name = attributes[KEY_NM]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() } ?: return null
            val platform = attributes[KEY_PF]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() } ?: return null

            return PeerInfo(
                serviceName = serviceName,
                deviceId = id,
                name = name,
                platform = platform,
                protocolVersion = pv,
                host = host,
                port = port
            )
        }
    }
}
