package dev.nexus.local.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.nexus.local.clipboard.ClipboardProtocol
import dev.nexus.local.clipboard.ClipboardSync
import dev.nexus.local.connection.Connection
import dev.nexus.local.connection.NexusServer
import dev.nexus.local.connection.NexusSession
import dev.nexus.local.identity.DeviceIdentity
import dev.nexus.local.identity.NexusTlsIdentity
import dev.nexus.local.pairing.PairingManager
import dev.nexus.local.protocol.DeviceInfo
import dev.nexus.local.protocol.Envelope
import dev.nexus.local.tls.Tls
import dev.nexus.local.trust.TrustedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
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

    /** Connection-layer snapshot for the UI. */
    data class SessionUi(
        val ready: Boolean = false,
        val peerName: String? = null,
        val peerDeviceId: String? = null
    )

    private val identity = DeviceIdentity.loadOrCreate(context)
    private val tlsIdentity = NexusTlsIdentity.loadOrCreate(context, identity.deviceId)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }

    private val _sessionUi = MutableStateFlow(SessionUi())
    val sessionUi: StateFlow<SessionUi> = _sessionUi.asStateFlow()

    /** Foreground-only clipboard synchronization (protocol/clipboard.md). */
    private val clipboard = ClipboardSync(context) { text, hash -> sendClipboard(text, hash) }
    val clipboardStatus: StateFlow<ClipboardSync.Status> get() = clipboard.status

    /** Connection layer: hello + heartbeats + application dispatch. */
    private val session = NexusSession(identity, object : NexusSession.Listener {
        override fun onSessionReady(conn: Connection, peer: DeviceInfo) {
            Log.i(TAG, "session ready with ${peer.name}")
            _sessionUi.value = SessionUi(
                ready = true,
                peerName = peer.name,
                peerDeviceId = peer.deviceId
            )
            clipboard.beginSession()
            flushHarnessClipboard()
        }

        override fun onApplicationMessage(conn: Connection, peer: DeviceInfo, env: Envelope) {
            when (env.type) {
                "clipboard.update" -> handleClipboardUpdate(conn, env)
                else -> {
                    Log.w(TAG, "unsupported message type ${env.type}")
                    conn.sendSystemError("unknown_type", "unsupported message type: ${env.type}")
                }
            }
        }

        override fun onSessionClosed(conn: Connection, reason: String) {
            _sessionUi.value = SessionUi()
            clipboard.endSession()
        }
    })

    private val pairing = PairingManager(context, identity, tlsIdentity, session)
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
        clipboard.start()
    }

    /**
     * Pause discovery (advertise + browse off) but keep the TLS server —
     * it is owned for the whole process lifetime.
     */
    fun stop() {
        discovery.shutdown()
        _peers.value = emptyList()
        _state.value = "stopped"
        clipboard.stop()
    }

    /** Full teardown; only for process death paths. */
    fun destroy() {
        discovery.shutdown()
        server.close()
        clipboard.close()
    }

    // -- Clipboard (protocol/clipboard.md) ---------------------------------

    /** Push the current clipboard to the peer now (UI action). */
    fun pushClipboard() = clipboard.pushNow()

    /** Test harness: simulate a local copy of [text] and send it. */
    fun setLocalClipboard(text: String) = queueHarnessClipboard { clipboard.setLocalAndSend(text) }

    /** Test harness: log what is actually on the system clipboard now. */
    fun logClipboard() = queueHarnessClipboard { clipboard.logCurrent() }

    /**
     * Test harness: assert the system clipboard equals [expected], logging only
     * the boolean result. Lets scripted tests verify exact content without ever
     * writing clipboard contents to the log.
     */
    fun expectClipboard(expected: String) =
        queueHarnessClipboard { clipboard.verifyCurrent(expected) }

    /**
     * Test harness helper. `adb` can only deliver these actions by restarting
     * the activity, which happens before a session exists (and before the
     * window has clipboard-read focus). Hold the action until the session is
     * ready, then run it — deterministic ordering for scripted tests.
     */
    @Volatile
    private var pendingHarnessClipboard: (() -> Unit)? = null

    private fun queueHarnessClipboard(action: () -> Unit) {
        pendingHarnessClipboard = action
        flushHarnessClipboard()
    }

    private fun flushHarnessClipboard() {
        if (!session.isReady()) return
        val action = pendingHarnessClipboard ?: return
        pendingHarnessClipboard = null
        action()
    }

    private fun sendClipboard(text: String, hash: String): Boolean =
        session.send(
            Envelope.create("clipboard.update", JSONObject().apply {
                put("text", text)
                put("h", hash)
            })
        )

    private fun handleClipboardUpdate(conn: Connection, env: Envelope) {
        val payload = env.payload
        if (!payload.has("text") || !payload.has("h")) {
            Log.w(TAG, "clipboard.update without text/h")
            conn.sendSystemError("invalid_payload", "clipboard.update needs text and h")
            return
        }
        val text = payload.optString("text", "")
        val hash = payload.optString("h", "")
        when (val decision = clipboard.handleRemote(text, hash)) {
            is ClipboardProtocol.Inbound.Apply ->
                Log.i(TAG, "clipboard applied (${decision.text.length} chars)")
            is ClipboardProtocol.Inbound.SameContent ->
                Log.i(TAG, "clipboard already up to date")
            ClipboardProtocol.Inbound.Echo ->
                Log.i(TAG, "clipboard echo ignored")
            ClipboardProtocol.Inbound.Duplicate ->
                Log.i(TAG, "clipboard duplicate ignored")
            ClipboardProtocol.Inbound.InvalidHash ->
                conn.sendSystemError("invalid_payload", "clipboard.update hash does not match text")
            is ClipboardProtocol.Inbound.TooLarge ->
                conn.sendSystemError(
                    "payload_too_large",
                    "clipboard text exceeds ${ClipboardProtocol.MAX_CLIPBOARD_TEXT} bytes"
                )
        }
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
