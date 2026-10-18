package dev.nexus.local.tls

import android.util.Log
import dev.nexus.local.identity.NexusTlsIdentity
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.3 channel with pinned self-signed certificates (protocol/pairing.md).
 *
 * - TLS 1.3 only (min = max), ALPN `nexus/1` (MUST match, else the Rust
 *   side rejects — so we always offer it).
 * - Chain validity is deliberately NOT verified: certificates are
 *   self-signed by design. Authentication happens at the application layer
 *   by pinning the peer certificate's SHA-256 against the trust store.
 * - No hostname/endpoint identification (devices have no stable DNS names).
 */
object Tls {
    private const val TAG = "NexusTls"
    private const val ALPN = "nexus/1"
    private const val TLS13 = "TLSv1.3"

    /** Build the SSLContext around this device's identity (in-memory keystore). */
    fun context(identity: NexusTlsIdentity): SSLContext {
        // Random in-memory password: the keystore exists only for the
        // lifetime of this call; the key is never persisted with it.
        val password = CharArray(16).also { chars ->
            val random = SecureRandom()
            for (i in chars.indices) chars[i] = (33 + random.nextInt(94)).toChar()
        }
        val keyStore = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("nexus", identity.privateKey, password, arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)

        val ctx = SSLContext.getInstance(TLS13)
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(PermissiveTrustManager()), SecureRandom())
        return ctx
    }

    /** Connect to a peer's TLS server. Caller owns the returned socket. */
    fun connect(
        ctx: SSLContext,
        host: String,
        port: Int,
        timeoutMs: Int = 10_000
    ): SSLSocket {
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.soTimeout = timeoutMs
        applyClientParameters(socket)
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.startHandshake()
        // Some platforms (Android) don't propagate ALPN to the accepted/created
        // client socket via sslParameters. Explicitly re-apply it so the Rust
        // peer's "require_alpn" check succeeds.
        try { applyClientParameters(socket) } catch (_: Exception) {}
        Log.i(TAG, "client handshake done: ${socket.session.protocol} peer=${describePeer(socket)}")
        return socket
    }

    /** Create the server socket for the advertised port (mutual TLS). */
    fun serverSocket(ctx: SSLContext, port: Int): SSLServerSocket {
        val socket = ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        socket.enabledProtocols = arrayOf(TLS13) // TLS 1.3 only (min = max)
        socket.needClientAuth = true // mutual TLS: client cert always required
        val params = socket.sslParameters
        params.applicationProtocols = arrayOf(ALPN)
        socket.sslParameters = params
        return socket
    }

    /**
     * Accepted sockets do not reliably inherit the server socket's
     * SSLParameters on Android, so the ALPN list (which the Rust peer
     * requires) must be applied to each accepted socket explicitly.
     */
    fun applyAcceptedParameters(socket: SSLSocket) {
        val params = socket.sslParameters
        params.applicationProtocols = arrayOf(ALPN)
        socket.sslParameters = params
    }

    /** SHA-256 fingerprint (lowercase hex) of the peer's leaf certificate. */
    fun peerFingerprint(socket: SSLSocket): String? = try {
        val peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate
        peer?.let { sha256Hex(it.encoded) }
    } catch (e: SSLPeerUnverifiedException) {
        null
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------------

    private fun applyClientParameters(socket: SSLSocket) {
        socket.enabledProtocols = arrayOf(TLS13) // TLS 1.3 only (min = max)
        val params: SSLParameters = socket.sslParameters
        params.applicationProtocols = arrayOf(ALPN)
        // No endpoint identification: verification is pin-based (see class doc).
        params.endpointIdentificationAlgorithm = null
        socket.sslParameters = params
    }

    private fun describePeer(socket: SSLSocket): String =
        peerFingerprint(socket)?.let { "sha256=$it" } ?: "no-peer-cert"

    /**
     * Accepts any certificate at the TLS layer; pinning happens afterwards
     * at the application layer (trust store comparison), exactly like the
     * Rust implementation.
     */
    class PermissiveTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
