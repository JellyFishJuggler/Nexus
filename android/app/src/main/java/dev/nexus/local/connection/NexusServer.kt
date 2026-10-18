package dev.nexus.local.connection

import android.util.Log
import dev.nexus.local.identity.NexusTlsIdentity
import dev.nexus.local.tls.Tls
import java.io.Closeable
import javax.net.ssl.SSLSocket

/**
 * This device's TLS server (protocol/pairing.md § Connection roles):
 * mutual TLS on the advertised port, one active session per connection.md.
 */
class NexusServer(
    identity: NexusTlsIdentity,
    private val listener: Connection.Listener
) : Closeable {

    private val context = Tls.context(identity)
    private val serverSocket = Tls.serverSocket(context, 0)

    @Volatile
    private var running = false

    @Volatile
    private var active: Connection? = null

    /** The port mDNS advertises. */
    val port: Int get() = serverSocket.localPort

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "nexus-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "TLS server listening on port $port")
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        active?.close()
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept() as SSLSocket
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: $e")
                break
            }
            try {
                socket.soTimeout = Connection.READ_TIMEOUT_MS
                Tls.applyAcceptedParameters(socket)
                socket.startHandshake() // mutual TLS: client cert required
                val fingerprint = Tls.peerFingerprint(socket)
                Log.i(TAG, "peer connected from ${socket.inetAddress?.hostAddress} $fingerprint")

                val conn = Connection(socket, fingerprint, listener)
                val current = active
                if (current != null && !current.closed) {
                    // connection.md: older connection wins, the newer one is
                    // closed with system.error {code:"busy"} (reconnect races).
                    conn.sendSystemError("busy", "another connection from this device is active")
                    conn.close()
                    Log.i(TAG, "rejected newer connection (busy)")
                    continue
                }
                active = conn
                conn.start()
            } catch (e: Exception) {
                Log.w(TAG, "handshake failed: $e")
                runCatching { socket.close() }
            }
        }
    }

    companion object {
        private const val TAG = "NexusServer"
    }
}
