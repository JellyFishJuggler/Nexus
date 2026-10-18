package dev.nexus.local.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.nexus.local.identity.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket
import java.util.concurrent.Executor

/**
 * App-level owner of discovery (milestone A1).
 *
 * Owns the listening port that mDNS advertises. For A1 this is a plain
 * placeholder socket whose only job is to occupy a real TCP port; milestone
 * A2 replaces it with the pinned TLS 1.3 listener per protocol/connection.md.
 */
class DiscoveryController(context: Context) : DiscoveryManager.Listener {

    private val identity = DeviceIdentity.loadOrCreate(context)
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Placeholder listener; replaced by TLS listener in milestone A2. */
    private val placeholderServer: ServerSocket = ServerSocket(0)

    val localPort: Int get() = placeholderServer.localPort
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
        if (placeholderServer.isClosed) {
            // Should not happen: the socket lives for the process lifetime.
            _state.value = "error: listener closed"
            return
        }
        discovery.advertise(localPort)
        discovery.startBrowsing()
    }

    /**
     * Pause discovery (advertise + browse off) but keep the listening
     * socket — it is owned for the whole process lifetime.
     */
    fun stop() {
        discovery.shutdown()
        _peers.value = emptyList()
        _state.value = "stopped"
    }

    /** Full teardown; only for process death paths. */
    fun destroy() {
        discovery.shutdown()
        runCatching { placeholderServer.close() }
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
}
