package dev.nexus.local

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.discovery.PeerInfo
import dev.nexus.local.ui.theme.NexusTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: DiscoveryController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = DiscoveryController(applicationContext)

        setContent {
            NexusTheme {
                val peers by controller.peers.collectAsState()
                val state by controller.state.collectAsState()
                val lifecycleOwner = LocalLifecycleOwner.current

                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> controller.start()
                            Lifecycle.Event.ON_PAUSE -> controller.stop()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DiscoveryScreen(
                        state = state,
                        port = controller.localPort,
                        identity = controller.localIdentity,
                        peers = peers,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoveryScreen(
    state: String,
    port: Int,
    identity: dev.nexus.local.identity.DeviceIdentity,
    peers: List<PeerInfo>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Nexus", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "This device: ${identity.name} (${identity.platform})\n" +
                "Device ID: ${identity.deviceId}\n" +
                "Advertising port: $port — state: $state",
            style = MaterialTheme.typography.bodySmall
        )
        Text(text = "Discovered peers (${peers.size})", style = MaterialTheme.typography.titleMedium)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(peers, key = { it.serviceName }) { peer ->
                PeerCard(peer)
            }
        }
    }
}

@Composable
fun PeerCard(peer: PeerInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = peer.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${peer.platform} · pv=${peer.protocolVersion}\n${peer.host}:${peer.port}\n${peer.deviceId}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
