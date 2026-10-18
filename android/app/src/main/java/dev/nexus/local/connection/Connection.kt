package dev.nexus.local.connection

import android.os.SystemClock
import android.util.Log
import dev.nexus.local.protocol.Envelope
import dev.nexus.local.protocol.EnvelopeException
import dev.nexus.local.protocol.Frames
import dev.nexus.local.protocol.FrameViolation
import dev.nexus.local.tls.Tls
import dev.nexus.local.trust.TrustedDevice
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/**
 * One TLS session: frame read loop, heartbeat replies, spec-defined error
 * replies (protocol/connection.md), plus the peer certificate needed for
 * pairing/pinning.
 *
 * Reads run on a dedicated thread; writes are serialized on the output
 * stream so a slow write never blocks the reader and vice versa.
 */
class Connection(
    private val socket: SSLSocket,
    /** Which side of the TCP connection this is (pairing.md § Connection roles). */
    val role: Role,
    private val listener: Listener
) : Closeable {

    /** Which side of the TCP connection this session is. */
    enum class Role {
        /** We accepted the connection (we are the TLS server). */
        SERVER,

        /** We initiated the connection (we are the TLS client). */
        CLIENT
    }

    interface Listener {
        /**
         * The session is up. The listener applies trust policy here:
         * pin-verify (client), or drop unpaired peers that arrived outside
         * pairing mode (server). Called on the read thread before any frame.
         */
        fun onEstablished(conn: Connection)

        /** Every well-formed non-system control message. Called on the read thread. */
        fun onMessage(conn: Connection, env: Envelope)

        /** Session ended. Called once, on the read thread. */
        fun onClosed(conn: Connection, reason: String)
    }

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    /** Peer leaf certificate, pinned material for the trust store. */
    val peerCertificate = Tls.peerCertificate(socket)

    /** SHA-256 fingerprint of the peer certificate, lowercase hex. */
    val peerFingerprint: String? = Tls.peerFingerprintOf(peerCertificate)

    /** Peer certificate DER bytes (identity comparisons and SAS). */
    val peerCertDer: ByteArray? = peerCertificate?.encoded

    /** Peer certificate in PEM form (stored in the trust store). */
    val peerCertPem: String? = peerCertificate?.let { Tls.toPem(it) }

    /** Set once the peer certificate is pinned in the trust store. */
    @Volatile
    var trusted: TrustedDevice? = null

    val isTrusted: Boolean get() = trusted != null

    @Volatile
    var closed = false
        private set

    /**
     * Monotonic time (ms) of the last frame read from the peer, of any kind.
     *
     * Heartbeats count: a `system.ping`/`system.pong` is a frame. The session
     * layer uses this for its "any inbound frame within 60 s" staleness rule
     * (connection.md) — the read loop, not the application message callback,
     * is the only place that can see control frames, so it owns the clock.
     */
    @Volatile
    var lastActivityAt: Long = SystemClock.elapsedRealtime()
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
        try {
            listener.onEstablished(this)
        } catch (e: Exception) {
            Log.w(TAG, "establishment handling failed: $e")
            close()
            listener.onClosed(this, "establishment failed")
            return
        }
        if (closed) {
            listener.onClosed(this, "closed during establishment")
            return
        }

        var reason = "peer closed the connection"
        try {
            while (!closed) {
                val frame = Frames.read(input) ?: break
                lastActivityAt = SystemClock.elapsedRealtime()
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
