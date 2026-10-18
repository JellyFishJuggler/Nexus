package dev.nexus.local.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for protocol/discovery.md record validation.
 * Malformed or incomplete mDNS records must be discarded, never guessed at.
 */
class PeerInfoTest {

    private val attrs = mapOf(
        PeerInfo.KEY_PV to "1".toByteArray(),
        PeerInfo.KEY_ID to "3188b946-0998-44ac-85f0-ade23fb29b48".toByteArray(),
        PeerInfo.KEY_NM to "Bob".toByteArray(),
        PeerInfo.KEY_PF to "windows".toByteArray()
    )

    @Test
    fun `valid record is parsed`() {
        val peer = PeerInfo.from("Bob._nexus._tcp.local.", "192.168.1.14", 51855, attrs)
        requireNotNull(peer)
        assertEquals("3188b946-0998-44ac-85f0-ade23fb29b48", peer.deviceId)
        assertEquals("Bob", peer.name)
        assertEquals("windows", peer.platform)
        assertEquals(1, peer.protocolVersion)
        assertEquals("192.168.1.14", peer.host)
        assertEquals(51855, peer.port)
    }

    @Test
    fun `missing pv is rejected`() {
        assertNull(PeerInfo.from("svc", "192.168.1.14", 1, attrs - PeerInfo.KEY_PV))
    }

    @Test
    fun `missing id is rejected`() {
        assertNull(PeerInfo.from("svc", "192.168.1.14", 1, attrs - PeerInfo.KEY_ID))
    }

    @Test
    fun `missing nm is rejected`() {
        assertNull(PeerInfo.from("svc", "192.168.1.14", 1, attrs - PeerInfo.KEY_NM))
    }

    @Test
    fun `missing pf is rejected`() {
        assertNull(PeerInfo.from("svc", "192.168.1.14", 1, attrs - PeerInfo.KEY_PF))
    }

    @Test
    fun `null attributes are rejected`() {
        assertNull(PeerInfo.from("svc", "192.168.1.14", 1, null))
    }

    @Test
    fun `non numeric pv is rejected`() {
        val bad = attrs + (PeerInfo.KEY_PV to "one".toByteArray())
        assertNull(PeerInfo.from("svc", "192.168.1.14", 1, bad))
    }

    @Test
    fun `blank name is rejected`() {
        val bad = attrs + (PeerInfo.KEY_NM to "   ".toByteArray())
        assertNull(PeerInfo.from("svc", "192.168.1.14", 1, bad))
    }

    @Test
    fun `missing host is rejected`() {
        assertNull(PeerInfo.from("svc", null, 1, attrs))
    }

    @Test
    fun `out of range port is rejected`() {
        assertNull(PeerInfo.from("svc", "192.168.1.14", 0, attrs))
        assertNull(PeerInfo.from("svc", "192.168.1.14", 65536, attrs))
    }
}
