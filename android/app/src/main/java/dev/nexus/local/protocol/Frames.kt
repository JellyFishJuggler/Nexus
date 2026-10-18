package dev.nexus.local.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Frame codec per protocol/framing.md.
 *
 * ```text
 * ┌────────────┬───────────┬──────────────────────┐
 * │ length     │ frame_type│ body                 │
 * │ u32 (BE)   │ u8        │ [length - 1] bytes   │
 * └────────────┴───────────┴──────────────────────┘
 * ```
 */
object Frames {
    const val CONTROL: Byte = 0x01
    const val DATA: Byte = 0x02

    /** Max `length` field value (frame_type + body), per framing.md. */
    const val MAX_FRAME_LEN = 1_048_576 // 1 MiB

    /** Control bodies larger than this are a protocol violation. */
    const val MAX_CONTROL_BODY = 65_536 // 64 KiB

    /** DATA chunk cap (also excludes the 16-byte transfer_id prefix). */
    const val MAX_CHUNK = 65_536

    /** Raw bytes of a UUID (RFC 4122 order), the DATA frame prefix. */
    fun uuidBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val out = ByteArray(16)
        for (i in 0 until 8) out[i] = (msb shr (56 - 8 * i)).toByte()
        for (i in 0 until 8) out[8 + i] = (lsb shr (56 - 8 * i)).toByte()
        return out
    }

    /** One decoded frame. [body] is the raw body after the type byte. */
    data class Frame(val type: Byte, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && type == other.type && body.contentEquals(other.body)
        override fun hashCode(): Int = 31 * type.toInt() + body.contentHashCode()
    }

    /**
     * Read one frame. Returns `null` on clean EOF *at a frame boundary*.
     * Throws [FrameViolation] on any spec violation (the caller MUST then
     * close the connection) and [IOException] on transport errors.
     */
    fun read(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null // clean EOF at frame boundary
        val lenBytes = ByteArray(4)
        lenBytes[0] = first.toByte()
        readFully(input, lenBytes, 1, 3)

        val length = ((lenBytes[0].toInt() and 0xff) shl 24) or
            ((lenBytes[1].toInt() and 0xff) shl 16) or
            ((lenBytes[2].toInt() and 0xff) shl 8) or
            (lenBytes[3].toInt() and 0xff)

        if (length < 1) throw FrameViolation("frame length $length < 1")
        if (length > MAX_FRAME_LEN) {
            // Spec: close without reading the body — never allocate on the
            // sender's claim.
            throw FrameViolation("frame length $length > MAX_FRAME_LEN")
        }

        val type = input.read()
        if (type < 0) throw EOFException("EOF inside frame header")
        val bodyLen = length - 1
        if (type.toInt() == CONTROL.toInt() && bodyLen > MAX_CONTROL_BODY) {
            throw FrameViolation("control body $bodyLen > MAX_CONTROL_BODY")
        }
        val body = ByteArray(bodyLen)
        readFully(input, body, 0, bodyLen)
        val byteType = type.toByte()
        if (byteType != CONTROL && byteType != DATA) {
            throw FrameViolation("unknown frame type 0x%02x".format(type))
        }
        return Frame(byteType, body)
    }

    /** Write one frame (flushes the stream). */
    fun write(out: OutputStream, type: Byte, body: ByteArray) {
        if (type == CONTROL && body.size > MAX_CONTROL_BODY) {
            throw FrameViolation("control body ${body.size} > MAX_CONTROL_BODY")
        }
        val length = body.size + 1
        if (length > MAX_FRAME_LEN) {
            throw FrameViolation("frame length $length > MAX_FRAME_LEN")
        }
        out.write(byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
            type
        ))
        out.write(body)
        out.flush()
    }

    /** Encode + write a CONTROL frame from a JSON string. */
    fun writeControl(out: OutputStream, json: String) = write(out, CONTROL, json.toByteArray(Charsets.UTF_8))

    /**
     * Write a DATA frame: 16-byte transfer_id prefix + chunk.
     * [chunk] must not exceed [MAX_CHUNK].
     */
    fun writeData(out: OutputStream, transferId: UUID, chunk: ByteArray) {
        if (chunk.size > MAX_CHUNK) {
            throw FrameViolation("chunk ${chunk.size} > MAX_CHUNK")
        }
        val body = ByteArrayOutputStream(16 + chunk.size)
        body.write(uuidBytes(transferId))
        body.write(chunk)
        write(out, DATA, body.toByteArray())
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, count: Int) {
        var read = 0
        while (read < count) {
            val n = input.read(buffer, offset + read, count - read)
            if (n < 0) throw EOFException("EOF inside frame")
            read += n
        }
    }
}

/** Spec violation: the connection MUST be closed. */
class FrameViolation(message: String) : IOException(message)
