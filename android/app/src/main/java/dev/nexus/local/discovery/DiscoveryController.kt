package dev.nexus.local.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.nexus.local.connection.Connection
import dev.nexus.local.connection.NexusServer
import dev.nexus.local.identity.DeviceIdentity
import dev.nexus.local.identity.NexusTlsIdentity
import dev.nexus.local.protocol.Envelope
import dev.nexus.local.tls.Tls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * App-level owner of discovery + the TLS server (milestones A1, A2).
 *
 * The port mDNS advertises is the TLS server's port, per
 * protocol/connection.md. Outbound connections (ad-hoc client role) go
 * through [connectTo].
 */
class DiscoveryController(context: Context) :
    DiscoveryManager.Listener,
    Connection.Listener {

    private val identity = DeviceIdentity.loadOrCreate(context)
    private val tlsIdentity = NexusTlsIdentity.loadOrCreate(context, identity.deviceId)
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val server = NexusServer(tlsIdentity, this)

    val localPort: Int get() = server.port
    val localIdentity: DeviceIdentity get() = identity

    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val discovery = DiscoveryManager(
        context = context,
        localDeviceId = identity.deviceId,
        serviceName = identity.name,
        displayName = identity.name,
        executor = mainExecutor,
        listener = this
    )

    fun start() {
        server.start()
        discovery.advertise(localPort)
        discovery.startBrowsing()
    }

    /**
     * Pause discovery (advertise + browse off) but keep the TLS server —
     * it is owned for the whole process lifetime.
     */
    fun stop() {
        discovery.shutdown()
        _peers.value = emptyList()
        _state.value = "stopped"
    }

    /** Full teardown; only for process death paths. */
    fun destroy() {
        discovery.shutdown()
        server.close()
    }

    /**
     * Ad-hoc outbound TLS connection (test harness trigger; milestone A3
     * replaces this with the proper connect/reconnect flow).
     */
    fun connectTo(host: String, port: Int) {
        Thread({
            try {
                Log.i(TAG, "connecting to $host:$port …")
                val socket = Tls.connect(Tls.context(tlsIdentity), host, port)
                socket.soTimeout = Connection.READ_TIMEOUT_MS
                val conn = Connection(socket, Tls.peerFingerprint(socket), this)
                conn.start()
                Log.i(TAG, "client session established: ${conn.peerDescription}")
            } catch (e: Exception) {
                Log.w(TAG, "connect to $host:$port failed: $e")
            }
        }, "nexus-connect").apply { isDaemon = true; start() }
    }

    // DiscoveryManager.Listener (always on mainExecutor) -------------------

    override fun onPeerFound(peer: PeerInfo) {
        _peers.value = discovery.currentPeers()
    }

    override fun onPeerLost(serviceName: String) {
        _peers.value = discovery.currentPeers()
    }

    override fun onDiscoveryState(state: String) {
        _state.value = state
    }

    // Connection.Listener (read threads) ------------------------------------

    override fun onMessage(conn: Connection, env: Envelope) {
        when {
            // TODO(milestone A3): pairing.request → show SAS prompt, reply
            // accept/reject. Until the pairing UI exists, decline rather
            // than silently ignoring or accepting.
            env.type == "pairing.request" -> {
                Log.i(TAG, "received pairing.request from ${env.payload}; no pairing UI yet — rejecting")
                conn.send(Envelope.create("pairing.reject"))
            }
            else -> Log.i(TAG, "received ${env.type}: ${env.payload}")
        }
    }

    override fun onClosed(conn: Connection, reason: String) {
        Log.i(TAG, "session closed: $reason")
    }

    companion object {
        private const val TAG = "NexusController"
    }
}
