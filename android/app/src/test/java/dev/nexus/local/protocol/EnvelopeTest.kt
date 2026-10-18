package dev.nexus.local.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Control envelope tests (protocol/framing.md: `{v,type,id,ts,payload}`).
 */
class EnvelopeTest {

    @Test
    fun `round trip preserves all fields`() {
        val env = Envelope.create("system.ping", JSONObject().put("note", "hi"))
        val parsed = Envelope.parse(env.encode().toByteArray())
        assertEquals(Envelope.PROTOCOL_VERSION, parsed.version)
        assertEquals("system.ping", parsed.type)
        assertEquals(env.id, parsed.id)
        assertEquals(env.ts, parsed.ts)
        assertEquals("hi", parsed.payload.getString("note"))
    }

    @Test
    fun `wrong envelope version is rejected`() {
        val bad = """{"v":2,"type":"system.ping","id":"x","ts":1,"payload":{}}"""
        assertThrows(EnvelopeException::class.java) { Envelope.parse(bad.toByteArray()) }
    }

    @Test
    fun `missing type is rejected`() {
        val bad = """{"v":1,"type":"","id":"x","ts":1,"payload":{}}"""
        assertThrows(EnvelopeException::class.java) { Envelope.parse(bad.toByteArray()) }
    }

    @Test
    fun `type not matching the naming pattern is rejected`() {
        val bad = """{"v":1,"type":"Not-A-Type","id":"x","ts":1,"payload":{}}"""
        assertThrows(EnvelopeException::class.java) { Envelope.parse(bad.toByteArray()) }
    }

    @Test
    fun `missing id or ts is rejected`() {
        val noId = """{"v":1,"type":"system.ping","id":"","ts":1,"payload":{}}"""
        val noTs = """{"v":1,"type":"system.ping","id":"x","ts":-1,"payload":{}}"""
        assertThrows(EnvelopeException::class.java) { Envelope.parse(noId.toByteArray()) }
        assertThrows(EnvelopeException::class.java) { Envelope.parse(noTs.toByteArray()) }
    }

    @Test
    fun `missing payload object is rejected`() {
        val bad = """{"v":1,"type":"system.ping","id":"x","ts":1}"""
        assertThrows(EnvelopeException::class.java) { Envelope.parse(bad.toByteArray()) }
    }

    @Test
    fun `invalid json is rejected`() {
        assertThrows(EnvelopeException::class.java) {
            Envelope.parse("not json at all".toByteArray())
        }
    }

    @Test
    fun `spec-defined message types match the naming pattern`() {
        for (type in listOf(
            "system.hello", "system.ping", "system.pong", "system.error",
            "pairing.request", "pairing.accept", "pairing.confirm",
            "pairing.complete", "pairing.reject",
            "clipboard.offer", "clipboard.data", "clipboard.ack",
            "file.offer", "file.accept", "file.reject", "file.complete",
            "file.cancel", "file.progress",
            "media.pause", "media.play_pause"
        )) {
            val env = Envelope.create(type)
            assertEquals(type, Envelope.parse(env.encode().toByteArray()).type)
        }
    }
}
