package dev.nexus.local.pairing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.nexus.local.connection.Connection
import dev.nexus.local.connection.TrustedSessionHandler
import dev.nexus.local.identity.DeviceIdentity
import dev.nexus.local.identity.NexusTlsIdentity
import dev.nexus.local.protocol.DeviceInfo
import dev.nexus.local.protocol.Envelope
import dev.nexus.local.trust.TrustStore
import dev.nexus.local.trust.TrustedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Pairing state machine — `protocol/pairing.md` § "Pairing flow".
 *
 * Implements both roles against the same wire sequence the Rust core uses
 * (`pairing.rs`):
 *
 * ```text
 * Initiator                                Responder
 *   ── pairing.request ───────────────────▶
 *                                          [prompt: accept/reject]
 *   ◀─ pairing.accept ────────────────────
 *   [prompt: accept/reject]
 *   ── pairing.confirm ───────────────────▶
 *                                          pin peer cert + persist
 *   ◀─ pairing.complete ──────────────────
 *   pin peer cert + persist
 * ```
 *
 * Both sides display the same six-digit SAS before accepting; the code binds
 * the exchange to the exact two certificates on the wire.
 */
class PairingManager(
    context: Context,
    private val identity: DeviceIdentity,
    private val tlsIdentity: NexusTlsIdentity,
    /**
     * Where trusted sessions go for the connection-level handshake and
     * application traffic (protocol/connection.md). Null in unit tests that
     * only exercise the pairing state machine.
     */
    private val sessions: TrustedSessionHandler? = null
) {

    /** What the UI should show. */
    sealed interface Ui {
        /** Nothing to show. */
        data object Idle : Ui

        /** Waiting for the user to compare codes and decide. */
        data class Prompt(
            val peerName: String,
            val peerDeviceId: String,
            val code: String,
            val weInitiated: Boolean
        ) : Ui

        /** Pairing finished; the peer is now trusted. */
        data class Succeeded(val peerName: String, val peerDeviceId: String) : Ui

        /** Pairing failed (rejected, timed out, or protocol error). */
        data class Failed(val message: String) : Ui
    }

    private enum class Stage {
        /** Waiting for the user to accept/reject this side's prompt. */
        AWAITING_DECISION,

        /** Responder: accept sent, waiting for pairing.confirm. */
        AWAITING_CONFIRM,

        /** Initiator: confirm sent, waiting for pairing.complete. */
        AWAITING_COMPLETE,

        /** Finished; both sides pinned. */
        COMPLETE
    }

    private class Pending(
        val conn: Connection,
        val peerCertDer: ByteArray,
        val peerCertPem: String,
        val weInitiated: Boolean,
        var peer: DeviceInfo?,
        var stage: Stage
    ) {
        /** Guards against stale timeout tasks firing for a newer attempt. */
        var token: Long = 0
    }

    private val store = TrustStore.load(context)
    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nexus-pairing-timer").apply { isDaemon = true }
    }

    /**
     * User decisions ([accept]/[reject]) write to the TLS socket, and they are
     * invoked from the Compose main thread — where Android forbids network I/O
     * (non-debuggable builds throw `NetworkOnMainThreadException`). The writes
     * therefore run here, mirroring `ClipboardSync.pushNow`.
     */
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-pairing-io").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0)

    private var pending: Pending? = null
    private var timeoutTask: ScheduledFuture<*>? = null

    /** Device ids the user declined; ignored until pairing is restarted. */
    private val rejectedDeviceIds = mutableSetOf<String>()

    /** Monotonic deadline of the explicit pairing window, if any. */
    private var pairingModeUntil = 0L

    /**
     * Test/non-interactive convenience mirroring the Rust `auto_accept`:
     * when set, a prompt whose SAS equals this value is accepted
     * automatically, anything else is rejected. `null` uses the real UI.
     */
    @Volatile
    var autoAcceptCode: String? = null

    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _devices = MutableStateFlow(store.devices())
    val devices: StateFlow<List<TrustedDevice>> = _devices.asStateFlow()

    // -- Trust store ------------------------------------------------------

    fun trustedByFingerprint(fingerprint: String?): TrustedDevice? =
        fingerprint?.let { store.byFingerprint(it) }

    fun unpair(deviceId: String): Boolean {
        val removed = store.remove(deviceId)
        if (removed) {
            _devices.value = store.devices()
            Log.i(TAG, "unpaired $deviceId")
        }
        return removed
    }

    // -- Explicit pairing mode (pairing.md § Untrusted connection) ---------

    /**
     * Open the 60-second pairing window. Untrusted peers that connect while
     * it is open may start the pairing handshake; otherwise they are dropped
     * with `system.error {code:"not_paired"}`.
     */
    fun enterPairingMode() {
        synchronized(lock) {
            rejectedDeviceIds.clear()
            pairingModeUntil = SystemClock.elapsedRealtime() + PAIRING_WINDOW_MS
        }
        Log.i(TAG, "pairing mode open for ${PAIRING_WINDOW_MS / 1000}s")
    }

    fun isPairingModeActive(): Boolean =
        synchronized(lock) { SystemClock.elapsedRealtime() < pairingModeUntil }

    // -- Session entry points ---------------------------------------------

    /**
     * Apply trust policy to a just-established session.
     *
     * Server: trusted ⇒ normal session; untrusted ⇒ keep only if the pairing
     * window is open, otherwise drop with `not_paired`.
     * Client: trusted ⇒ normal session (pin already matched); untrusted ⇒ we
     * are the initiator and start the pairing handshake.
     */
    fun onEstablished(conn: Connection) {
        val record = trustedByFingerprint(conn.peerFingerprint)
        if (record != null) {
            conn.trusted = record
            Log.i(TAG, "peer is trusted: ${record.name} (${record.deviceId})")
            // Hand off to the connection layer (system.hello + heartbeats).
            sessions?.onTrustedSession(conn)
            return
        }
        when (conn.role) {
            Connection.Role.SERVER -> {
                if (isPairingModeActive()) {
                    Log.i(TAG, "untrusted peer connected; awaiting pairing.request (pairing mode)")
                } else {
                    Log.i(TAG, "dropping untrusted peer (${conn.peerFingerprint}); no pairing mode")
                    conn.sendSystemError(
                        "not_paired",
                        "this device isn't paired with Nexus on the other side yet"
                    )
                    conn.close()
                }
            }
            Connection.Role.CLIENT -> beginInitiator(conn)
        }
    }

    /** Start the initiator half of the flow on a fresh untrusted session. */
    private fun beginInitiator(conn: Connection) {
        val der = conn.peerCertDer
        val pem = conn.peerCertPem
        if (der == null || pem == null) {
            conn.sendSystemError("not_paired", "the other device did not present a certificate")
            conn.close()
            return
        }
        val session = Pending(
            conn = conn,
            peerCertDer = der,
            peerCertPem = pem,
            weInitiated = true,
            peer = null,
            stage = Stage.AWAITING_DECISION
        )
        synchronized(lock) {
            if (pending != null) {
                conn.sendSystemError("busy", "pairing already in progress")
                conn.close()
                return
            }
            pending = session
        }
        runCatching { conn.send(Envelope.create("pairing.request", ourDeviceInfo().toJson())) }
            .onFailure { fail(session, "couldn't send the pairing request") }
        armTimeout(session)
    }

    // -- Inbound messages (called on the connection read thread) -----------

    fun onMessage(conn: Connection, env: Envelope) {
        if (env.type.startsWith("pairing.")) {
            handlePairing(conn, env)
            return
        }
        // Untrusted peers may only carry pairing.* messages (pairing.md).
        if (!conn.isTrusted) {
            Log.w(TAG, "untrusted peer sent ${env.type}; dropping with not_paired")
            conn.sendSystemError(
                "not_paired",
                "this device isn't paired with Nexus on the other side yet"
            )
            conn.close()
            return
        }
        // Trusted: the connection layer interprets system.hello/incompatible
        // and dispatches application messages (protocol/connection.md).
        sessions?.onTrustedMessage(conn, env)
    }

    private fun handlePairing(conn: Connection, env: Envelope) {
        when (env.type) {
            "pairing.request" -> handleRequest(conn, env)
            "pairing.accept" -> handleAccept(conn, env)
            "pairing.confirm" -> handleConfirm(conn)
            "pairing.complete" -> handleComplete(conn)
            "pairing.reject" -> handleReject(conn)
            else -> {
                Log.w(TAG, "unknown pairing message ${env.type}")
                conn.sendSystemError("unknown_type", "unsupported message type: ${env.type}")
            }
        }
    }

    private fun handleRequest(conn: Connection, env: Envelope) {
        if (conn.isTrusted) {
            // Already paired with this device (Rust: pairing.request is
            // answered with `busy` on a trusted connection).
            conn.sendSystemError("busy", "already paired with this device")
            return
        }
        if (!isPairingModeActive()) {
            // No explicit pairing in progress: drop (pairing.md § Untrusted connection).
            Log.i(TAG, "pairing.request outside pairing mode; dropping")
            conn.sendSystemError(
                "not_paired",
                "this device isn't paired with Nexus on the other side yet"
            )
            conn.close()
            return
        }
        val peer = DeviceInfo.parse(env.payload) ?: run {
            Log.w(TAG, "pairing.request with an invalid payload")
            conn.sendSystemError("invalid_payload", "pairing request is missing identity fields")
            conn.close()
            return
        }
        if (synchronized(lock) { peer.deviceId in rejectedDeviceIds }) {
            Log.i(TAG, "auto-rejecting previously rejected device ${peer.deviceId}")
            runCatching { conn.send(Envelope.create("pairing.reject")) }
            return
        }
        val der = conn.peerCertDer
        val pem = conn.peerCertPem
        if (der == null || pem == null) {
            conn.sendSystemError("not_paired", "the other device did not present a certificate")
            conn.close()
            return
        }
        val session = Pending(
            conn = conn,
            peerCertDer = der,
            peerCertPem = pem,
            weInitiated = false,
            peer = peer,
            stage = Stage.AWAITING_DECISION
        )
        synchronized(lock) {
            if (pending != null) {
                conn.sendSystemError("busy", "pairing already in progress")
                conn.close()
                return
            }
            pending = session
        }
        showPrompt(session)
        armTimeout(session)
    }

    private fun handleAccept(conn: Connection, env: Envelope) {
        val session = synchronized(lock) { pending } ?: return stale(conn, "pairing.accept")
        if (session.conn !== conn || !session.weInitiated || session.stage != Stage.AWAITING_DECISION) {
            return stale(conn, "pairing.accept")
        }
        val peer = DeviceInfo.parse(env.payload) ?: run {
            fail(session, "the other device sent an invalid identity")
            return
        }
        synchronized(lock) { session.peer = peer }
        showPrompt(session)
    }

    private fun handleConfirm(conn: Connection) {
        val session = synchronized(lock) { pending } ?: return stale(conn, "pairing.confirm")
        if (session.conn !== conn || session.weInitiated || session.stage != Stage.AWAITING_CONFIRM) {
            return stale(conn, "pairing.confirm")
        }
        val peer = session.peer ?: run {
            fail(session, "the other device never identified itself")
            return
        }
        // Both users accepted: pin now, then tell the initiator.
        pin(session, peer)
        synchronized(lock) { session.stage = Stage.COMPLETE }
        runCatching { conn.send(Envelope.create("pairing.complete")) }
        succeed(session, peer)
    }

    private fun handleComplete(conn: Connection) {
        val session = synchronized(lock) { pending } ?: return stale(conn, "pairing.complete")
        if (session.conn !== conn || !session.weInitiated || session.stage != Stage.AWAITING_COMPLETE) {
            return stale(conn, "pairing.complete")
        }
        val peer = session.peer ?: run {
            fail(session, "the other device never identified itself")
            return
        }
        pin(session, peer)
        succeed(session, peer)
    }

    private fun handleReject(conn: Connection) {
        val session = synchronized(lock) { pending }
        if (session == null || session.conn !== conn) {
            Log.i(TAG, "peer rejected pairing (no local pairing in progress)")
            return
        }
        val name = session.peer?.name ?: "the other device"
        clearPending(session, close = true)
        _ui.value = Ui.Failed("$name declined the pairing")
    }

    /** Unexpected pairing message for the current state: drop the connection. */
    private fun stale(conn: Connection, kind: String) {
        Log.w(TAG, "unexpected $kind; dropping the connection")
        conn.sendSystemError("invalid_payload", "unexpected $kind")
        conn.close()
    }

    // -- User decisions ---------------------------------------------------

    /**
     * The user confirmed the displayed code matches.
     *
     * The socket write runs on [io], never on the caller's thread: this is
     * reached from the Compose main thread, where network I/O would throw.
     */
    fun accept() = io.execute { acceptNow() }

    private fun acceptNow() {
        val session = synchronized(lock) { pending } ?: return
        when (session.stage) {
            Stage.AWAITING_DECISION -> {
                if (!session.weInitiated) {
                    // Responder: send accept, then wait for confirm.
                    synchronized(lock) { session.stage = Stage.AWAITING_CONFIRM }
                    val sent = runCatching {
                        session.conn.send(Envelope.create("pairing.accept", ourDeviceInfo().toJson()))
                    }.onFailure { Log.w(TAG, "couldn't send pairing.accept: $it") }.isSuccess
                    if (!sent) fail(session, "couldn't send the pairing acceptance")
                    else armTimeout(session)
                } else {
                    // Initiator: accept received; send confirm, wait for complete.
                    synchronized(lock) { session.stage = Stage.AWAITING_COMPLETE }
                    val sent = runCatching {
                        session.conn.send(Envelope.create("pairing.confirm"))
                    }.onFailure { Log.w(TAG, "couldn't send pairing.confirm: $it") }.isSuccess
                    if (!sent) fail(session, "couldn't send the pairing confirmation")
                    else armTimeout(session)
                }
            }
            else -> Log.w(TAG, "accept() in unexpected stage ${session.stage}")
        }
    }

    /** The user declined, or the codes did not match. */
    fun reject() = io.execute { rejectNow() }

    private fun rejectNow() {
        val session = synchronized(lock) { pending } ?: return
        val peerId = session.peer?.deviceId
        val name = session.peer?.name ?: "the other device"
        if (peerId != null) {
            // The rejecting side remembers, so the peer is not re-nagged
            // until pairing is explicitly restarted (pairing.md).
            synchronized(lock) { rejectedDeviceIds += peerId }
        }
        runCatching { session.conn.send(Envelope.create("pairing.reject")) }
            .onFailure { Log.w(TAG, "couldn't send pairing.reject: $it") }
        clearPending(session, close = true)
        _ui.value = Ui.Failed("pairing with $name was rejected")
    }

    // -- Internals --------------------------------------------------------

    private fun showPrompt(session: Pending) {
        val peer = session.peer ?: return
        val code = Sas.code(tlsIdentity.certificate.encoded, session.peerCertDer)
        Log.i(
            TAG,
            "verification code ${code} for ${peer.name} (${peer.deviceId}); " +
                "we_initiated=${session.weInitiated}"
        )
        val auto = autoAcceptCode
        if (auto != null) {
            // Non-interactive harness, mirrors the Rust `auto_accept` prompt.
            if (code == auto) accept() else reject()
            return
        }
        _ui.value = Ui.Prompt(
            peerName = peer.name,
            peerDeviceId = peer.deviceId,
            code = code,
            weInitiated = session.weInitiated
        )
    }

    private fun pin(session: Pending, peer: DeviceInfo) {
        val record = TrustedDevice(
            deviceId = peer.deviceId,
            name = peer.name,
            platform = peer.platform,
            certSha256 = TrustStore.fingerprintOf(session.peerCertDer),
            certPem = session.peerCertPem,
            pairedAt = System.currentTimeMillis() / 1000
        )
        store.insert(record)
        _devices.value = store.devices()
        session.conn.trusted = record
        Log.i(TAG, "pinned ${peer.name} (${peer.deviceId}) ${record.certSha256}")
    }

    private fun succeed(session: Pending, peer: DeviceInfo) {
        clearPending(session, close = false)
        _ui.value = Ui.Succeeded(peer.name, peer.deviceId)
        // Freshly paired: hand the (now trusted) connection to the connection
        // layer so the hello exchange runs without a reconnect.
        sessions?.onTrustedSession(session.conn)
    }

    private fun fail(session: Pending, message: String) {
        Log.w(TAG, "pairing failed: $message")
        runCatching { session.conn.sendSystemError("invalid_payload", message) }
        clearPending(session, close = true)
        _ui.value = Ui.Failed(message)
    }

    private fun armTimeout(session: Pending) {
        val token = generation.incrementAndGet()
        session.token = token
        val task = scheduler.schedule({
            val current = synchronized(lock) { pending }
            if (current === session && session.token == token) {
                runCatching { session.conn.send(Envelope.create("pairing.reject")) }
                clearPending(session, close = true)
                _ui.value = Ui.Failed("pairing timed out — try again")
            }
        }, PAIRING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            timeoutTask?.cancel(false)
            timeoutTask = task
        }
    }

    private fun clearPending(session: Pending, close: Boolean) {
        synchronized(lock) {
            if (pending === session) {
                pending = null
                timeoutTask?.cancel(false)
                timeoutTask = null
            }
        }
        if (close) session.conn.close()
    }

    /** The session ended; surface it if a pairing was in flight. */
    fun onConnectionClosed(conn: Connection, reason: String) {
        // Tell the connection layer first: a trusted session simply went away.
        sessions?.onTrustedClosed(conn, reason)
        val session = synchronized(lock) { pending } ?: return
        if (session.conn !== conn) return
        val name = session.peer?.name ?: "the other device"
        clearPending(session, close = false)
        _ui.value = Ui.Failed("the connection to $name ended before pairing finished")
        Log.i(TAG, "pairing connection closed: $reason")
    }

    /** Dismiss a Succeeded/Failed result; the UI returns to idle. */
    fun clearResult() {
        if (_ui.value is Ui.Succeeded || _ui.value is Ui.Failed) {
            _ui.value = Ui.Idle
        }
    }

    private fun ourDeviceInfo(): DeviceInfo = DeviceInfo(
        deviceId = identity.deviceId,
        name = identity.name,
        platform = DeviceInfo.PLATFORM,
        pv = DeviceInfo.PROTOCOL_VERSION,
        features = DeviceInfo.localFeatures()
    )

    companion object {
        private const val TAG = "NexusPairing"

        /** Pairing step timeout (pairing.md § Pairing flow). */
        const val PAIRING_TIMEOUT_MS = 30_000L

        /** How long an explicitly opened pairing window lasts. */
        const val PAIRING_WINDOW_MS = 60_000L
    }
}
