package dev.nexus.local.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Protocol/framing.md conformance tests for the frame codec.
 */
class FrameCodecTest {

    private fun encode(type: Byte, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Frames.write(out, type, body)
        return out.toByteArray()
    }

    @Test
    fun `control frame round trip`() {
        val json = """{"v":1,"type":"system.hello","id":"abc","ts":100,"payload":{}}"""
        val decoded = Frames.read(ByteArrayInputStream(encode(Frames.CONTROL, json.toByteArray())))
        assertEquals(Frames.CONTROL, decoded!!.type)
        assertEquals(json, String(decoded.body, Charsets.UTF_8))
    }

    @Test
    fun `wire layout is length-prefixed big-endian`() {
        val bytes = encode(Frames.CONTROL, byteArrayOf(0x41, 0x42)) // body "AB"
        // length = 3 (type + 2 body bytes), type = 0x01
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 0x01, 0x41, 0x42), bytes)
    }

    @Test
    fun `data frame has 16-byte transfer id prefix`() {
        val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val out = ByteArrayOutputStream()
        Frames.writeData(out, id, byteArrayOf(0x7a))
        val decoded = Frames.read(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(Frames.DATA, decoded.type)
        assertEquals(17, decoded.body.size)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
                0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()
            ),
            decoded.body.copyOf(16)
        )
        assertEquals(0x7a, decoded.body[16].toInt())
    }

    @Test
    fun `clean eof at frame boundary returns null`() {
        assertNull(Frames.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `oversized frame length is rejected without reading the body`() {
        // length = 2 MiB claim, no body follows: must fail on the claim alone
        val header = byteArrayOf(0, 0x20, 0, 0, Frames.CONTROL)
        assertThrows(FrameViolation::class.java) {
            Frames.read(ByteArrayInputStream(header))
        }
    }

    @Test
    fun `zero frame length is rejected`() {
        assertThrows(FrameViolation::class.java) {
            Frames.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        }
    }

    @Test
    fun `control body over 64 KiB is rejected`() {
        val out = ByteArrayOutputStream()
        assertThrows(FrameViolation::class.java) {
            Frames.writeControl(out, "x".repeat(Frames.MAX_CONTROL_BODY + 1))
        }
    }

    @Test
    fun `unknown frame type is rejected`() {
        val bytes = encode(0x7f, byteArrayOf(1, 2, 3))
        assertThrows(FrameViolation::class.java) {
            Frames.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `truncated frame body is rejected`() {
        val bytes = encode(Frames.CONTROL, "hello".toByteArray()).copyOf(6)
        assertThrows(java.io.EOFException::class.java) {
            Frames.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `chunk over 64 KiB is rejected on write`() {
        assertThrows(FrameViolation::class.java) {
            Frames.writeData(ByteArrayOutputStream(), UUID.randomUUID(), ByteArray(Frames.MAX_CHUNK + 1))
        }
    }

    @Test
    fun `back-to-back frames decode in sequence`() {
        val first = encode(Frames.CONTROL, "one".toByteArray())
        val second = encode(Frames.CONTROL, "two".toByteArray())
        val input = ByteArrayInputStream(first + second)
        assertEquals("one", String(Frames.read(input)!!.body))
        assertEquals("two", String(Frames.read(input)!!.body))
        assertNull(Frames.read(input))
    }
}
