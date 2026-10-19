package dev.nexus.local.discovery

/**
 * Pure retry policy for automatic reconnection to an already-trusted device.
 *
 * Kept free of Android and I/O so the two rules that make automatic
 * reconnection safe are provable by unit tests
 * ([dev.nexus.local.discovery.TrustedReconnectTest]) rather than only by
 * reading call sites:
 *
 *  1. **Discovery never authorises a device.** A peer is a reconnect
 *     candidate only when its device id is already pinned in the trust store
 *     (the one the user paired); the certificate is then re-verified by
 *     pinning during TLS (`PairingManager.onEstablished`), and a mismatching
 *     identity is dropped fail-closed without any pairing handshake
 *     (`Connection.reconnectOnly`).
 *  2. **A peer that is down is never hammered.** Repeat attempts back off
 *     (1s, 2s, 5s, 10s, then 30s) per device, and there is never more than
 *     one attempt in flight.
 */
internal object TrustedReconnect {

    /**
     * Delay before the next attempt after 1, 2, 3, 4, … consecutive failures.
     * The last entry is reused once the schedule is exhausted.
     */
    val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

    /** Floor between two dials to the same device, even with no failures. */
    const val MIN_ATTEMPT_GAP_MS = 1_000L

    /**
     * How often to re-check while there is nothing to dial. Local only — no
     * network traffic — so a peer that comes back is noticed quickly.
     */
    const val RECHECK_MS = 2_000L

    /** Backoff for a device that has failed [failures] times in a row. */
    fun backoffMs(failures: Int): Long =
        if (failures <= 0) 0L else BACKOFF_MS[(failures - 1).coerceAtMost(BACKOFF_MS.size - 1)]

    /** Minimum wait between two dials to a device with [failures] failures. */
    fun waitMs(failures: Int): Long = maxOf(MIN_ATTEMPT_GAP_MS, backoffMs(failures))

    /**
     * The trusted peer to dial now, or `null` when there is nothing to dial.
     *
     * A peer is eligible only if it is currently visible over discovery, its
     * device id is pinned in the trust store, it speaks our protocol version,
     * and the per-device backoff has elapsed. Ties are broken by name then
     * device id so the choice is deterministic.
     */
    fun candidate(
        peers: List<PeerInfo>,
        trustedDeviceIds: Set<String>,
        lastAttemptAt: Map<String, Long>,
        failures: Map<String, Int>,
        now: Long,
        protocolVersion: Int
    ): PeerInfo? = peers
        .asSequence()
        .filter { it.deviceId in trustedDeviceIds && it.protocolVersion == protocolVersion }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.deviceId }))
        .firstOrNull { peer ->
            val last = lastAttemptAt[peer.deviceId]
            last == null || now - last >= waitMs(failures[peer.deviceId] ?: 0)
        }
}
