package dev.nexus.local.discovery

import dev.nexus.local.protocol.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Guards the two security/robustness rules of Android trusted-device
 * automatic reconnection:
 *
 * ```text
 * trusted device id + certificate pin match + successful TLS
 *   → normal trusted session   (no prompt, no code, no user action)
 * anything else
 *   → no connection            (discovery alone never authorises a device)
 * ```
 *
 * [TrustedReconnect.candidate] is the single gate that decides *whether* a
 * peer may be dialled at all, so pinning its truth table here pins the
 * invariant. Whether the dial is then authorised is decided by certificate
 * pinning during TLS (`PairingManager.onEstablished`), which drops a
 * mismatching identity fail-closed and never pairs on a reconnect
 * (`Connection.reconnectOnly`).
 */
class TrustedReconnectTest {

    private val peer = PeerInfo(
        serviceName = "Nexus._nexus._tcp.local.",
        deviceId = "peer-device-id",
        name = "Nexus peer",
        platform = "windows",
        protocolVersion = DeviceInfo.PROTOCOL_VERSION,
        host = "192.0.2.10",
        port = 51_000
    )

    private fun candidate(
        peers: List<PeerInfo> = listOf(peer),
        trusted: Set<String> = setOf(peer.deviceId),
        lastAttemptAt: Map<String, Long> = emptyMap(),
        failures: Map<String, Int> = emptyMap(),
        now: Long = 1_000_000L
    ): PeerInfo? = TrustedReconnect.candidate(
        peers = peers,
        trustedDeviceIds = trusted,
        lastAttemptAt = lastAttemptAt,
        failures = failures,
        now = now,
        protocolVersion = DeviceInfo.PROTOCOL_VERSION
    )

    // -- backoff -----------------------------------------------------------

    @Test
    fun retriesBackOffAndThenStaySlow() {
        assertEquals(0L, TrustedReconnect.backoffMs(0))
        assertEquals(1_000L, TrustedReconnect.backoffMs(1))
        assertEquals(2_000L, TrustedReconnect.backoffMs(2))
        assertEquals(5_000L, TrustedReconnect.backoffMs(3))
        assertEquals(10_000L, TrustedReconnect.backoffMs(4))
        assertEquals(30_000L, TrustedReconnect.backoffMs(5))
        // Never grows past the slowest step: a down peer is polled, not hammered.
        assertEquals(30_000L, TrustedReconnect.backoffMs(50))
    }

    @Test
    fun consecutiveAttemptsAreNeverCloserThanTheBackoff() {
        val first = 1_000_000L
        // 1 attempt in flight already happened at `first`; the next dial to the
        // same device must wait for the backoff for that failure count.
        assertNull(candidate(lastAttemptAt = mapOf(peer.deviceId to first), failures = mapOf(peer.deviceId to 1), now = first + 999))
        assertSame(peer, candidate(lastAttemptAt = mapOf(peer.deviceId to first), failures = mapOf(peer.deviceId to 1), now = first + 1_000))
        // Third failure ⇒ 2s, not 1s.
        assertNull(candidate(lastAttemptAt = mapOf(peer.deviceId to first), failures = mapOf(peer.deviceId to 2), now = first + 1_999))
        assertSame(peer, candidate(lastAttemptAt = mapOf(peer.deviceId to first), failures = mapOf(peer.deviceId to 2), now = first + 2_000))
    }

    // -- discovery never authorises a dial ---------------------------------

    @Test
    fun aPeerThatIsNotTrustedIsNeverDialled() {
        // Visible over discovery, but nothing pinned: no automatic dial. The
        // user must pair it through the normal flow.
        assertNull(candidate(trusted = emptySet()))
        assertNull(candidate(trusted = setOf("some-other-device")))
    }

    @Test
    fun aTrustedDeviceThatIsNotVisibleIsNeverDialled() {
        assertNull(candidate(peers = emptyList()))
    }

    @Test
    fun anIncompatibleProtocolVersionIsNeverDialled() {
        assertNull(candidate(peers = listOf(peer.copy(protocolVersion = DeviceInfo.PROTOCOL_VERSION + 1))))
    }

    @Test
    fun aVisibleTrustedPeerIsDialled() {
        assertSame(peer, candidate())
    }

    @Test
    fun theFirstAttemptIsNotDelayed() {
        // Fresh session loss: the peer is dialled on the first evaluation.
        assertSame(peer, candidate(now = 1_000_000L))
    }

    // -- one device at a time ----------------------------------------------

    @Test
    fun aBackingOffDeviceDoesNotBlockAnotherTrustedDevice() {
        val other = peer.copy(
            serviceName = "Other._nexus._tcp.local.",
            deviceId = "other-device-id",
            name = "Other peer"
        )
        val now = 1_000_000L
        assertSame(
            other,
            candidate(
                peers = listOf(peer, other),
                trusted = setOf(peer.deviceId, other.deviceId),
                lastAttemptAt = mapOf(peer.deviceId to now),
                failures = mapOf(peer.deviceId to 4),
                now = now
            )
        )
    }

    @Test
    fun theChoiceIsDeterministic() {
        val beta = peer.copy(serviceName = "b._nexus._tcp.local.", deviceId = "b", name = "Beta")
        val alpha = peer.copy(serviceName = "a._nexus._tcp.local.", deviceId = "a", name = "alpha")
        assertSame(alpha, candidate(peers = listOf(beta, alpha), trusted = setOf("a", "b")))
    }

    @Test
    fun unrelatedPeersAreIgnored() {
        val rogue = peer.copy(
            serviceName = "Rogue._nexus._tcp.local.",
            deviceId = "rogue",
            name = "Aardvark" // sorts first, but is not trusted
        )
        assertSame(peer, candidate(peers = listOf(rogue, peer)))
    }
}
