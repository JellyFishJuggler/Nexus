package dev.nexus.local.connection

import android.os.SystemClock
import android.util.Log
import dev.nexus.local.identity.DeviceIdentity
import dev.nexus.local.protocol.DeviceInfo
import dev.nexus.local.protocol.Envelope
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Connection management — `protocol/connection.md` on Android.
 *
 * Mirrors `nexus-core`'s `connection.rs`: immediately after the TLS handshake
 * (and after pairing, for peers that just became trusted) **both sides** send
 * `system.hello`; the session is application-ready only once the peer's hello
 * has been received and its identity re-checked against the pin.
 *
 * Liveness: we send `system.ping` every 15 s, so the peer always sees traffic
 * from us, and we treat *any* inbound frame as traffic from it — including
 * heartbeats, which are consumed by [Connection] before they reach
 * [onTrustedMessage], so the clock lives on [Connection.lastActivityAt].
 * A hello must arrive within 15 s or the session is dropped.
 */
class NexusSession(
    private val identity: DeviceIdentity,
    private val listener: Listener
) : TrustedSessionHandler {

    /** Application-level events for the owner (discovery controller). */
    interface Listener {
        /** A trusted session finished the hello exchange and is ready. */
        fun onSessionReady(conn: Connection, peer: DeviceInfo)

        /** An application message from a ready session. */
        fun onApplicationMessage(conn: Connection, peer: DeviceInfo, env: Envelope)

        /** The active session ended. */
        fun onSessionClosed(conn: Connection, reason: String)
    }

    private enum class Stage { EXPECTING_HELLO, READY }

    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nexus-session-timer").apply { isDaemon = true }
    }

    /** The single active session (connection.md keeps one per connection). */
    private var conn: Connection? = null
    private var peer: DeviceInfo? = null
    private var stage = Stage.EXPECTING_HELLO
    private var helloTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null

    /** The peer of the ready session, or null. */
    fun currentPeer(): DeviceInfo? = synchronized(lock) { peer }

    /** Whether a session is ready for application traffic. */
    fun isReady(): Boolean = synchronized(lock) { stage == Stage.READY && conn != null }

    /** Send an application envelope on the active session. */
    fun send(env: Envelope): Boolean {
        val active = synchronized(lock) { conn } ?: return false
        return runCatching { active.send(env) }.isSuccess
    }

    // -- TrustedSessionHandler --------------------------------------------

    override fun onTrustedSession(conn: Connection) {
        // A new trusted session supersedes any previous one.
        val previous = synchronized(lock) {
            cancelTimersLocked()
            val old = this.conn
            this.conn = conn
            this.peer = null
            this.stage = Stage.EXPECTING_HELLO
            old
        }
        // Only one session may be live at a time. Both devices reconnect after
        // a drop, so their dials can cross; the losing connection is closed
        // here rather than left open to be silently ignored.
        if (previous != null && previous !== conn) {
            Log.i(TAG, "superseding the previous session")
            previous.close()
        }

        // Both sides send hello concurrently, immediately after TLS + pairing.
        val sent = runCatching { conn.send(hello()) }.isSuccess
        if (!sent) {
            Log.w(TAG, "couldn't send system.hello; dropping the session")
            conn.close()
            return
        }
        armHelloTimeout(conn)
        startHeartbeat(conn)
        Log.i(TAG, "system.hello sent; awaiting peer hello")
    }

    override fun onTrustedMessage(conn: Connection, env: Envelope) {
        synchronized(lock) {
            if (this.conn !== conn) return // stale session
        }
        when (env.type) {
            "system.hello" -> handleHello(conn, env)
            "system.incompatible" -> {
                Log.w(TAG, "peer is incompatible: ${env.payload}")
                drop(conn, "incompatible protocol version")
            }
            else -> {
                val readyPeer = synchronized(lock) {
                    if (stage == Stage.READY) peer else null
                }
                if (readyPeer == null) {
                    Log.w(TAG, "ignoring ${env.type} before system.hello")
                    return
                }
                listener.onApplicationMessage(conn, readyPeer, env)
            }
        }
    }

    override fun onTrustedClosed(conn: Connection, reason: String) {
        val wasActive = synchronized(lock) {
            if (this.conn !== conn) return
            cancelTimersLocked()
            this.conn = null
            this.peer = null
            true
        }
        if (wasActive) {
            Log.i(TAG, "session closed: $reason")
            listener.onSessionClosed(conn, reason)
        }
    }

    // -- Internals --------------------------------------------------------

    private fun handleHello(conn: Connection, env: Envelope) {
        val info = DeviceInfo.parse(env.payload)
        if (info == null) {
            conn.sendSystemError("invalid_payload", "system.hello is missing identity fields")
            drop(conn, "invalid system.hello")
            return
        }
        if (info.pv != DeviceInfo.PROTOCOL_VERSION) {
            runCatching {
                conn.send(
                    Envelope.create("system.incompatible", JSONObject().apply {
                        put("ours", DeviceInfo.PROTOCOL_VERSION)
                        put("theirs", info.pv)
                    })
                )
            }
            drop(conn, "incompatible protocol version")
            return
        }
        // A pinned device must not present a different identity (connection.md).
        val pinned = conn.trusted
        if (pinned != null && pinned.deviceId != info.deviceId) {
            Log.w(TAG, "peer claims ${info.deviceId} but is pinned as ${pinned.deviceId}")
            conn.sendSystemError("not_paired", "peer identity does not match its pinned certificate")
            drop(conn, "identity mismatch")
            return
        }

        val accepted = synchronized(lock) {
            if (this.conn !== conn) return
            peer = info
            stage = Stage.READY
            helloTask?.cancel(false)
            helloTask = null
            true
        }
        if (!accepted) return
        Log.i(TAG, "session ready with ${info.name} (${info.deviceId})")
        listener.onSessionReady(conn, info)
    }

    private fun armHelloTimeout(conn: Connection) {
        val task = scheduler.schedule({
            val stillWaiting = synchronized(lock) { this.conn === conn && stage != Stage.READY }
            if (stillWaiting) {
                Log.w(TAG, "no system.hello within ${HELLO_TIMEOUT_MS / 1000}s; dropping")
                drop(conn, "hello timeout")
            }
        }, HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            helloTask?.cancel(false)
            helloTask = task
        }
    }

    private fun startHeartbeat(conn: Connection) {
        val task = scheduler.scheduleWithFixedDelay({
            val active = synchronized(lock) { this.conn === conn && stage == Stage.READY }
            if (!active) return@scheduleWithFixedDelay
            if (SystemClock.elapsedRealtime() - conn.lastActivityAt > STALE_AFTER_MS) {
                Log.w(TAG, "no traffic from peer for ${STALE_AFTER_MS / 1000}s; dropping")
                drop(conn, "stale connection")
                return@scheduleWithFixedDelay
            }
            runCatching {
                conn.send(
                    Envelope.create("system.ping", JSONObject().put("ts", System.currentTimeMillis() / 1000))
                )
            }.onFailure { drop(conn, "heartbeat failed: ${it.message}") }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            pingTask?.cancel(false)
            pingTask = task
        }
    }

    private fun drop(conn: Connection, reason: String) {
        Log.w(TAG, "dropping session: $reason")
        conn.close()
    }

    private fun cancelTimersLocked() {
        helloTask?.cancel(false)
        helloTask = null
        pingTask?.cancel(false)
        pingTask = null
    }

    private fun hello(): Envelope = Envelope.create(
        "system.hello",
        DeviceInfo(
            deviceId = identity.deviceId,
            name = identity.name,
            platform = DeviceInfo.PLATFORM,
            pv = DeviceInfo.PROTOCOL_VERSION,
            features = DeviceInfo.localFeatures()
        ).toJson()
    )

    companion object {
        private const val TAG = "NexusSession"

        /** How long to wait for the peer's `system.hello` (connection.md). */
        const val HELLO_TIMEOUT_MS = 15_000L

        /** Heartbeat send interval (connection.md). */
        const val HEARTBEAT_INTERVAL_MS = 15_000L

        /** No inbound frame for this long ⇒ stale (connection.md). */
        const val STALE_AFTER_MS = 60_000L
    }
}
