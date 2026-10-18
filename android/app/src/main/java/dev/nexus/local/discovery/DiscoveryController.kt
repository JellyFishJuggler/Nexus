package dev.nexus.local.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.nexus.local.connection.Connection
import dev.nexus.local.connection.NexusServer
import dev.nexus.local.identity.DeviceIdentity
import dev.nexus.local.identity.NexusTlsIdentity
import dev.nexus.local.pairing.PairingManager
import dev.nexus.local.protocol.Envelope
import dev.nexus.local.tls.Tls
import dev.nexus.local.trust.TrustedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * App-level owner of discovery, the TLS server, and pairing
 * (milestones A1–A3).
 *
 * The port mDNS advertises is the TLS server's port, per
 * protocol/connection.md. Outbound connections (initiator role) go through
 * [connectTo].
 */
class DiscoveryController(context: Context) : DiscoveryManager.Listener, Connection.Listener {

    private val identity = DeviceIdentity.loadOrCreate(context)
    private val tlsIdentity = NexusTlsIdentity.loadOrCreate(context, identity.deviceId)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }

    private val pairing = PairingManager(context, identity, tlsIdentity)
    private val server = NexusServer(tlsIdentity, this)

    val localPort: Int get() = server.port
    val localIdentity: DeviceIdentity get() = identity

    /** Pairing prompt / result for the UI. */
    val pairingUi: StateFlow<PairingManager.Ui> get() = pairing.ui

    /** Devices pinned in the trust store. */
    val trustedDevices: StateFlow<List<TrustedDevice>> get() = pairing.devices

    /** True while the explicit pairing window is open. */
    private val _pairingMode = MutableStateFlow(false)
    val pairingMode: StateFlow<Boolean> = _pairingMode.asStateFlow()

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

    // -- Pairing controls --------------------------------------------------

    /**
     * Open the 60-second pairing window. Mirrors the Rust CLI's
     * `nexus-cli pair` running on this side: an untrusted peer may then
     * start the confirmation flow, and we act as responder.
     */
    fun startPairingMode() {
        pairing.enterPairingMode()
        _pairingMode.value = true
        mainHandler.postDelayed(
            { _pairingMode.value = pairing.isPairingModeActive() },
            PairingManager.PAIRING_WINDOW_MS
        )
    }

    /**
     * Non-interactive convenience used by the adb test harness; mirrors the
     * Rust CLI's `--code`. `null` restores the real UI prompt.
     */
    fun setAutoAcceptCode(code: String?) {
        pairing.autoAcceptCode = code
    }

    /** Accept the current pairing prompt (the user confirmed the code). */
    fun acceptPairing() = pairing.accept()

    /** Reject the current pairing prompt (codes differ, or the user declined). */
    fun rejectPairing() = pairing.reject()

    /** Dismiss a pairing result from the UI. */
    fun clearPairingUi() = pairing.clearResult()

    /** Forget a trusted device (pairing.md § Unpairing). */
    fun unpair(deviceId: String) {
        pairing.unpair(deviceId)
    }

    /**
     * Connect to a discovered peer and run pairing as **initiator**
     * (test harness / UI "Pair" action; milestone A4 adds automatic
     * reconnection to already-trusted peers).
     */
    fun connectTo(host: String, port: Int) {
        Thread({
            try {
                Log.i(TAG, "connecting to $host:$port …")
                val socket = Tls.connect(Tls.context(tlsIdentity), host, port)
                socket.soTimeout = Connection.READ_TIMEOUT_MS
                val conn = Connection(socket, Connection.Role.CLIENT, this)
                conn.start()
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

    // Connection.Listener (read threads) -----------------------------------

    /** Trust policy per pairing.md § Trust states. */
    override fun onEstablished(conn: Connection) {
        pairing.onEstablished(conn)
    }

    override fun onMessage(conn: Connection, env: Envelope) {
        pairing.onMessage(conn, env)
    }

    override fun onClosed(conn: Connection, reason: String) {
        Log.i(TAG, "session closed: $reason")
        pairing.onConnectionClosed(conn, reason)
    }

    companion object {
        private const val TAG = "NexusController"
    }
}
