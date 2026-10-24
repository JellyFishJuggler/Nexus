package dev.nexus.local.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /**
     * State of the automatic reconnection to an already-trusted device
     * (pairing.md § Trust states). The UI uses it to say what is actually
     * happening instead of guessing.
     */
    sealed interface ReconnectUi {
        /** Nothing to reconnect: a session is ready, or no device is trusted. */
        data object Idle : ReconnectUi

        /** A trusted device exists and is being waited for / retried. */
        data object Waiting : ReconnectUi

        /** Dialling a trusted device right now. */
        data class Connecting(val peerName: String) : ReconnectUi
    }

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
            mainExecutor.execute { onReconnectSucceeded(peer.deviceId) }
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

    /** Automatic reconnection to an already-trusted device, for the UI. */
    private val _reconnectUi = MutableStateFlow<ReconnectUi>(ReconnectUi.Idle)
    val reconnectUi: StateFlow<ReconnectUi> = _reconnectUi.asStateFlow()

    /** Foreground lifecycle gate: no dials are started while paused. */
    @Volatile
    private var running = false

    /**
     * Reconnect bookkeeping. Mutated only on [mainExecutor] (main thread);
     * [inFlightDeviceId] and [reconnectPeer] are read from connection threads.
     */
    private val lastAttemptAt = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()
    private var reconnectTask: Runnable? = null

    @Volatile
    private var inFlightDeviceId: String? = null

    @Volatile
    private var reconnectPeer: PeerInfo? = null

    private val discovery = DiscoveryManager(
        context = context,
        localDeviceId = identity.deviceId,
        serviceName = identity.name,
        displayName = identity.name,
        executor = mainExecutor,
        listener = this
    )

    /**
     * Bring up discovery, advertising and clipboard sync.
     *
     * The service owns the network half for the life of the process; the
     * activity owns the clipboard half while it is resumed. This combined
     * entry point exists for harness/tests and for callers that own both.
     */
    fun start() {
        startNetwork()
        clipboard.start()
    }

    /**
     * Bring up the TLS server and mDNS advertise/browse. Idempotent; called by
     * [dev.nexus.local.service.NexusService] when the process starts.
     */
    fun startNetwork() {
        if (running) return
        server.start()
        discovery.advertise(localPort)
        discovery.startBrowsing()
        // A trusted peer that is already visible may be dialled immediately;
        // otherwise discovery triggers the attempt as soon as it resolves.
        running = true
        mainExecutor.execute { evaluateReconnect() }
    }

    /**
     * Clipboard sync is foreground-only by design (Android restricts clipboard
     * reads to the focused app), so the activity starts and stops it with its
     * own lifecycle — never the background service.
     */
    fun startClipboard() = clipboard.start()

    fun stopClipboard() = clipboard.stop()

    /**
     * Stop discovery/advertising and the automatic reconnect loop while
     * keeping the TLS server (owned for the whole process lifetime).
     *
     * Automatic reconnection is paused here: no new dial is started, so
     * nothing pretends to be a background connection. A dial already in flight
     * is allowed to finish.
     */
    fun stopNetwork() {
        running = false
        cancelPendingReconnect()
        inFlightDeviceId = null
        reconnectPeer = null
        discovery.shutdown()
        _peers.value = emptyList()
        _state.value = "stopped"
        updateReconnectUi()
    }

    /** Full stop: network + clipboard. */
    fun stop() {
        stopNetwork()
        clipboard.stop()
    }

    /** Full teardown; only for process death paths. */
    fun destroy() {
        running = false
        cancelPendingReconnect()
        discovery.shutdown()
        server.close()
        clipboard.close()
    }

    // -- Clipboard (protocol/clipboard.md) ---------------------------------

    /** Push the current clipboard to the peer now (UI action). */
    fun pushClipboard() = clipboard.pushNow()

    // -- Notification mirroring (protocol/notifications.md) ----------------

    /** Whether a trusted, application-ready session exists right now. */
    fun isSessionReady(): Boolean = session.isReady()

    /** Whether the ready peer advertises notification mirroring. */
    fun peerSupportsNotifications(): Boolean =
        session.currentPeer()?.features?.contains("notifications") == true

    /**
     * Forward one notification to the connected computer.
     *
     * Returns false when nothing was sent (no ready session, or empty
     * payload). Callers must not log the content: this method deliberately
     * only logs a boolean/size at most.
     */
    fun sendNotification(app: String, title: String, text: String): Boolean {
        if (app.isBlank()) return false
        if (title.isBlank() && text.isBlank()) return false
        return session.send(
            Envelope.create("notification.post", JSONObject().apply {
                put("app", app)
                put("title", title)
                put("text", text)
                put("ts", System.currentTimeMillis() / 1000)
            })
        )
    }

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
        // The pin is the only thing that authorises a reconnect, so drop any
        // plan for it and re-evaluate immediately.
        mainExecutor.execute { evaluateReconnect() }
    }

    /**
     * Connect to a discovered peer and run pairing as **initiator**
     * (the UI's "Pair" action and the adb harness).
     *
     * User-initiated: an untrusted peer legitimately starts the pairing
     * handshake. Automatic reconnection uses [dial] instead, which may never
     * pair.
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

    // -- Trusted auto-reconnect (pairing.md § Trust states) ----------------

    /**
     * Decide whether a trusted device should be dialled now, and dial it.
     *
     * Main thread only. A dial happens only when the app is in the
     * foreground, no session is ready, no other attempt is in flight, and a
     * device that is **already pinned in the trust store** is currently
     * visible over discovery. Discovery on its own never authorises a
     * connection: identity is re-verified by certificate pinning during TLS
     * ([PairingManager.onEstablished]), and a peer that no longer matches the
     * pin is dropped without pairing. This method never pairs, never shows a
     * code, and never asks the user for anything.
     */
    private fun evaluateReconnect() {
        if (!running) return
        if (session.isReady()) {
            inFlightDeviceId = null
            reconnectPeer = null
            cancelPendingReconnect()
            updateReconnectUi()
            return
        }
        if (inFlightDeviceId != null) return

        val now = SystemClock.elapsedRealtime()
        val candidate = TrustedReconnect.candidate(
            peers = _peers.value,
            trustedDeviceIds = trustedDevices.value.map { it.deviceId }.toSet(),
            lastAttemptAt = lastAttemptAt,
            failures = failures,
            now = now,
            protocolVersion = DeviceInfo.PROTOCOL_VERSION
        )
        if (candidate == null) {
            // Nothing dialable right now (no trusted peer visible, or still
            // backing off). Re-check locally; discovery events re-trigger us
            // the moment the peer appears.
            scheduleReconnect(TrustedReconnect.RECHECK_MS)
            updateReconnectUi()
            return
        }
        dial(candidate)
    }

    /**
     * Dial a trusted peer as the initiator, in reconnect-only mode so a
     * certificate that no longer matches can never turn into pairing.
     */
    private fun dial(peer: PeerInfo) {
        inFlightDeviceId = peer.deviceId
        reconnectPeer = peer
        lastAttemptAt[peer.deviceId] = SystemClock.elapsedRealtime()
        cancelPendingReconnect()
        updateReconnectUi()
        Log.i(TAG, "auto-reconnecting to trusted device ${peer.name} …")
        Thread({
            try {
                val socket = Tls.connect(Tls.context(tlsIdentity), peer.host, peer.port)
                socket.soTimeout = Connection.READ_TIMEOUT_MS
                val conn = Connection(
                    socket,
                    Connection.Role.CLIENT,
                    this,
                    reconnectOnly = true
                )
                conn.start()
            } catch (e: Exception) {
                Log.i(TAG, "auto-reconnect to ${peer.name} failed: $e")
                mainExecutor.execute { onConnectionEnded(peer.deviceId) }
            }
        }, "nexus-reconnect").apply { isDaemon = true; start() }
    }

    /** The session became ready: stop retrying and forget the backoff. */
    private fun onReconnectSucceeded(deviceId: String) {
        if (inFlightDeviceId == deviceId) inFlightDeviceId = null
        reconnectPeer = null
        failures.remove(deviceId)
        cancelPendingReconnect()
        updateReconnectUi()
    }

    /**
     * A connection ended. Any ended connection is a reason to try again: the
     * dial we made (whatever its outcome), or the session that was live.
     * [deviceId] is null when the connection belonged to no trusted device.
     */
    private fun onConnectionEnded(deviceId: String?) {
        if (deviceId != null) {
            if (inFlightDeviceId == deviceId) inFlightDeviceId = null
            failures[deviceId] = (failures[deviceId] ?: 0) + 1
        }
        reconnectPeer = null
        if (!running) {
            updateReconnectUi()
            return
        }
        scheduleReconnect(
            deviceId?.let { TrustedReconnect.waitMs(failures[it] ?: 0) }
                ?: TrustedReconnect.RECHECK_MS
        )
        updateReconnectUi()
    }

    private fun scheduleReconnect(delayMs: Long) {
        cancelPendingReconnect()
        val task = Runnable {
            reconnectTask = null
            evaluateReconnect()
        }
        reconnectTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelPendingReconnect() {
        reconnectTask?.let { mainHandler.removeCallbacks(it) }
        reconnectTask = null
    }

    private fun updateReconnectUi() {
        val peer = reconnectPeer
        _reconnectUi.value = when {
            session.isReady() -> ReconnectUi.Idle
            inFlightDeviceId != null && peer != null -> ReconnectUi.Connecting(peer.name)
            running && trustedDevices.value.isNotEmpty() -> ReconnectUi.Waiting
            else -> ReconnectUi.Idle
        }
    }

    // DiscoveryManager.Listener (always on mainExecutor) -------------------

    override fun onPeerFound(peer: PeerInfo) {
        _peers.value = discovery.currentPeers()
        // A trusted peer just (re)appeared: reconnect without waiting for the
        // next backoff tick.
        evaluateReconnect()
    }

    override fun onPeerLost(serviceName: String) {
        _peers.value = discovery.currentPeers()
        updateReconnectUi()
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
        // A trusted or reconnect-dialled connection ended: schedule the next
        // automatic attempt (throttled per device). The device id comes from
        // the pin the peer matched, or from the dial that is still in flight.
        val deviceId = conn.trusted?.deviceId ?: inFlightDeviceId
        mainExecutor.execute { onConnectionEnded(deviceId) }
    }

    companion object {
        private const val TAG = "NexusController"
    }
}
