package dev.nexus.local.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * mDNS advertise + browse via Android's NsdManager (SRS §10, milestone A1).
 *
 * Conforms to protocol/discovery.md:
 *  - service type: `_nexus._tcp` (NsdManager appends the `.local.` domain)
 *  - TXT keys: pv, id, nm, pf
 *  - resolved peers that are malformed (missing required TXT keys) are
 *    discarded rather than shown as usable devices.
 */
class DiscoveryManager(
    context: Context,
    private val localDeviceId: String,
    private val serviceName: String,
    private val displayName: String,
    private val executor: Executor,
    private val listener: Listener
) {
    interface Listener {
        /** Upsert a validated peer (resolved or updated). Called on [executor]. */
        fun onPeerFound(peer: PeerInfo)
        /** Remove a peer by its fully-qualified service name. Called on [executor]. */
        fun onPeerLost(serviceName: String)
        /** Discovery state changes (registration started/failed, etc.). */
        fun onDiscoveryState(state: String)
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var registeredPort: Int? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Resolved peers keyed by fully-qualified service name. */
    private val peers = ConcurrentHashMap<String, PeerInfo>()

    // ---------------------------------------------------------------------
    // Advertising
    // ---------------------------------------------------------------------

    /** Register `_nexus._tcp` on [port]. Safe to call once during startup. */
    fun advertise(port: Int) {
        if (registrationListener != null) return
        registeredPort = port

        val info = NsdServiceInfo().apply {
            serviceName = this@DiscoveryManager.serviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(PeerInfo.KEY_PV, PROTOCOL_VERSION.toString())
            setAttribute(PeerInfo.KEY_ID, localDeviceId)
            setAttribute(PeerInfo.KEY_NM, displayName)
            setAttribute(PeerInfo.KEY_PF, PLATFORM)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "registered ${serviceInfo.serviceName} type=${serviceInfo.serviceType} port=$port")
                executor.execute { listener.onDiscoveryState("advertising") }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "registration failed: $errorCode")
                executor.execute { listener.onDiscoveryState("advertise-failed:$errorCode") }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "unregistered ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registrationListener?.let { nsdManager.unregisterService(it) }
        registrationListener = null
    }

    // ---------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------

    /** Start browsing for `_nexus._tcp`. Safe to call once during startup. */
    fun startBrowsing() {
        if (discoveryListener != null) return

        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "browsing started for $serviceType")
                executor.execute { listener.onDiscoveryState("browsing") }
            }

            override fun onServiceFound(found: NsdServiceInfo) {
                if (found.serviceType != SERVICE_TYPE && !found.serviceType.startsWith(SERVICE_TYPE)) {
                    return
                }
                Log.d(TAG, "found ${found.serviceName} (${found.serviceType})")
                resolve(found)
            }

            override fun onServiceLost(lost: NsdServiceInfo) {
                Log.d(TAG, "lost ${lost.serviceName}")
                peers.remove(lost.serviceName)
                executor.execute { listener.onPeerLost(lost.serviceName) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "browsing stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "start discovery failed: $errorCode")
                executor.execute { listener.onDiscoveryState("browse-failed:$errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "stop discovery failed: $errorCode")
            }
        }
        discoveryListener = dl
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
    }

    fun stopBrowsing() {
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        discoveryListener = null
    }

    fun shutdown() {
        stopBrowsing()
        stopAdvertising()
        peers.clear()
    }

    fun currentPeers(): List<PeerInfo> = peers.values.sortedBy { it.name.lowercase() }

    // ---------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------

    private fun resolve(found: NsdServiceInfo) {
        nsdManager.resolveService(found, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Transient failures (e.g. SERVICE_RUNNING) are retried by the
                // framework for PROTOCOL_DNS_SD; log and drop otherwise.
                Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val attrs = try {
                    resolved.attributes
                } catch (t: Throwable) {
                    // Some platform builds throw on attribute access.
                    Log.w(TAG, "attributes unavailable for ${resolved.serviceName}: $t")
                    null
                }

                val host = resolved.host?.hostAddress
                val peer = PeerInfo.from(
                    serviceName = resolved.serviceName,
                    host = host,
                    port = resolved.port,
                    attributes = attrs
                )

                if (peer == null) {
                    Log.w(TAG, "discarding malformed/incomplete record: ${resolved.serviceName} attrs=$attrs")
                    return
                }
                if (peer.deviceId == localDeviceId) {
                    // Never report ourselves as a peer.
                    return
                }

                val isNew = peers.put(peer.serviceName, peer) == null
                if (isNew || peers[peer.serviceName] != peer) {
                    Log.i(TAG, "peer: ${peer.name} [${peer.platform}] ${peer.host}:${peer.port} id=${peer.deviceId}")
                    executor.execute { listener.onPeerFound(peer) }
                }
            }
        })
    }

    companion object {
        private const val TAG = "NexusDiscovery"

        /** Service type per protocol/discovery.md. */
        const val SERVICE_TYPE = "_nexus._tcp"

        /** TXT protocol version value per protocol/discovery.md. */
        const val PROTOCOL_VERSION = 1

        /** Platform identifier announced in mDNS. */
        const val PLATFORM = "android"
    }
}
