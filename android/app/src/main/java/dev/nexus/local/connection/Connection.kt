package dev.nexus.local.connection

import android.util.Log
import dev.nexus.local.protocol.Envelope
import dev.nexus.local.protocol.EnvelopeException
import dev.nexus.local.protocol.Frames
import dev.nexus.local.protocol.FrameViolation
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/**
 * One authenticated session over a TLS socket: frame read loop, heartbeat
 * replies, spec-defined error replies (protocol/connection.md).
 *
 * Reads run on a dedicated thread; writes are serialized on the output
 * stream so a slow write never blocks the reader and vice versa.
 */
class Connection(
    private val socket: SSLSocket,
    val peerFingerprint: String?,
    private val listener: Listener
) : Closeable {

    interface Listener {
        /** Every well-formed non-system control message. Called on the read thread. */
        fun onMessage(conn: Connection, env: Envelope)
        /** Session ended. Called once, on the read thread. */
        fun onClosed(conn: Connection, reason: String)
    }

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    @Volatile
    var closed = false
        private set

    val peerDescription: String get() = "sha256=${peerFingerprint ?: "unknown"}"

    /** Start the read loop on a background thread. */
    fun start() {
        Thread({ readLoop() }, "nexus-read").apply {
            isDaemon = true
            start()
        }
    }

    /** Send a control envelope. Throws [IOException] if the session is gone. */
    fun send(env: Envelope) {
        if (closed) throw IOException("connection closed")
        synchronized(output) { Frames.writeControl(output, env.encode()) }
    }

    fun sendSystemError(code: String, message: String) {
        runCatching {
            send(Envelope.create("system.error", org.json.JSONObject().apply {
                put("code", code)
                put("message", message)
            }))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    // ---------------------------------------------------------------------

    private fun readLoop() {
        var reason = "peer closed the connection"
        try {
            while (!closed) {
                val frame = Frames.read(input) ?: break
                when (frame.type) {
                    Frames.CONTROL -> handleControl(frame.body)
                    Frames.DATA -> {
                        // Transfers are not implemented yet, and DATA before
                        // an accepted transfer_id is a violation (framing.md).
                        reason = "unexpected DATA frame (no transfer accepted)"
                        break
                    }
                }
            }
        } catch (e: FrameViolation) {
            reason = "protocol violation: ${e.message}"
            Log.w(TAG, reason)
        } catch (e: SocketTimeoutException) {
            reason = "stale: no frame for ${READ_TIMEOUT_MS / 1000}s"
            Log.w(TAG, reason)
        } catch (e: IOException) {
            reason = "io: ${e.message}"
            Log.d(TAG, reason)
        } finally {
            close()
            listener.onClosed(this, reason)
        }
    }

    private fun handleControl(body: ByteArray) {
        val env = try {
            Envelope.parse(body)
        } catch (e: EnvelopeException) {
            sendSystemError("invalid_payload", e.message ?: "malformed message")
            return
        }
        when {
            env.type == "system.ping" -> {
                runCatching { send(Envelope.create("system.pong")) }
            }
            env.type == "system.pong" -> Unit // liveness only
            env.type == "system.error" -> {
                Log.w(TAG, "peer error: ${env.payload}")
            }
            else -> listener.onMessage(this, env)
        }
    }

    companion object {
        private const val TAG = "NexusConn"

        /** No frame (incl. heartbeat traffic) for this long ⇒ stale (framing.md). */
        const val READ_TIMEOUT_MS = 60_000
    }
}
