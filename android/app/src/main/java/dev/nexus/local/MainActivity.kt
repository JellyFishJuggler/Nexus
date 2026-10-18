package dev.nexus.local

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nexus.local.clipboard.ClipboardSync
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.discovery.PeerInfo
import dev.nexus.local.pairing.PairingManager
import dev.nexus.local.trust.TrustedDevice
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
                val pairingUi by controller.pairingUi.collectAsState()
                val trusted by controller.trustedDevices.collectAsState()
                val pairingMode by controller.pairingMode.collectAsState()
                val session by controller.sessionUi.collectAsState()
                val clipboard by controller.clipboardStatus.collectAsState()
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> controller.start()
                            Lifecycle.Event.ON_PAUSE -> controller.stop()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    // Composition runs after the first ON_RESUME, so the
                    // observer would otherwise miss it on cold start.
                    if (lifecycleOwner.lifecycle.currentState
                            .isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        controller.start()
                    }
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NexusScreen(
                        state = state,
                        port = controller.localPort,
                        identity = controller.localIdentity,
                        peers = peers,
                        trusted = trusted,
                        pairingMode = pairingMode,
                        session = session,
                        clipboard = clipboard,
                        onStartPairingMode = { controller.startPairingMode() },
                        onPairPeer = { peer ->
                            if (peer.host.isNotEmpty() && peer.port in 1..65535) {
                                controller.connectTo(peer.host, peer.port)
                            }
                        },
                        onUnpair = { controller.unpair(it) },
                        onPushClipboard = { controller.pushClipboard() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                PairingPrompt(pairingUi, controller)
            }
        }

        handleHarnessIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleHarnessIntent(intent)
    }

    /**
     * Test-harness triggers, mirroring the Rust CLI's flags so scripted
     * runs can drive the same code paths the UI buttons use:
     *
     * - `--ez nexus_pairing true`      open the pairing window (responder)
     * - `--es nexus_autoconfirm CODE`  auto-accept when the code matches
     * - `--es nexus_host H --ei nexus_port P`  connect and pair (initiator)
     * - `--es nexus_set_clipboard S`   simulate a local copy of `S` and send it
     * - `--ez nexus_read_clipboard true`  log the current system clipboard
     */
    private fun handleHarnessIntent(intent: Intent?) {
        if (intent == null) return
        val pairing = intent.getBooleanExtra(EXTRA_PAIRING, false)
        val auto = intent.getStringExtra(EXTRA_AUTOCONFIRM)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val clip = intent.getStringExtra(EXTRA_SET_CLIPBOARD)
        val readClip = intent.getBooleanExtra(EXTRA_READ_CLIPBOARD, false)
        Log.i(
            "NexusHarness",
            "intent extras: pairing=$pairing autoconfirm=$auto host=$host port=$port " +
                "clip=$clip readClip=$readClip"
        )

        // Some devices redeliver the launch intent several times. Ignore exact
        // repeats so the harness (a test-only path) does not open the pairing
        // window, or dial out, more than once per launch.
        val signature = "p=$pairing;a=$auto;h=$host;o=$port;c=$clip;r=$readClip"
        if (signature == lastHarnessSignature) {
            Log.i("NexusHarness", "duplicate harness intent ignored")
            return
        }
        lastHarnessSignature = signature

        if (pairing) controller.startPairingMode()
        auto?.let { controller.setAutoAcceptCode(it) }
        if (host != null && host.isNotEmpty() && port in 1..65535) {
            controller.connectTo(host, port)
        }
        clip?.let { controller.setLocalClipboard(it) }
        if (readClip) controller.logClipboard()
    }

    companion object {
        private const val EXTRA_HOST = "nexus_host"
        private const val EXTRA_PORT = "nexus_port"
        private const val EXTRA_PAIRING = "nexus_pairing"
        private const val EXTRA_AUTOCONFIRM = "nexus_autoconfirm"
        private const val EXTRA_SET_CLIPBOARD = "nexus_set_clipboard"
        private const val EXTRA_READ_CLIPBOARD = "nexus_read_clipboard"

        /** Process-wide so activity re-creation does not re-run the harness. */
        @Volatile
        private var lastHarnessSignature: String? = null
    }
}

/** Dialog shown while a pairing needs the user's decision (pairing.md). */
@Composable
fun PairingPrompt(ui: PairingManager.Ui, controller: DiscoveryController) {
    when (ui) {
        is PairingManager.Ui.Prompt -> AlertDialog(
            onDismissRequest = { /* must be decided explicitly */ },
            title = { Text("Pair with ${ui.peerName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verification code", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = ui.code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("Confirm this matches the other device.")
                }
            },
            confirmButton = {
                Button(onClick = { controller.acceptPairing() }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { controller.rejectPairing() }) { Text("Reject") }
            }
        )

        is PairingManager.Ui.Succeeded -> AlertDialog(
            onDismissRequest = { controller.clearPairingUi() },
            title = { Text("Paired") },
            text = { Text("${ui.peerName} is now trusted.") },
            confirmButton = {
                Button(onClick = { controller.clearPairingUi() }) { Text("OK") }
            }
        )

        is PairingManager.Ui.Failed -> AlertDialog(
            onDismissRequest = { controller.clearPairingUi() },
            title = { Text("Pairing failed") },
            text = { Text(ui.message) },
            confirmButton = {
                Button(onClick = { controller.clearPairingUi() }) { Text("OK") }
            }
        )

        PairingManager.Ui.Idle -> Unit
    }
}

@Composable
fun NexusScreen(
    state: String,
    port: Int,
    identity: dev.nexus.local.identity.DeviceIdentity,
    peers: List<PeerInfo>,
    trusted: List<TrustedDevice>,
    pairingMode: Boolean,
    session: DiscoveryController.SessionUi,
    clipboard: ClipboardSync.Status,
    onStartPairingMode: () -> Unit,
    onPairPeer: (PeerInfo) -> Unit,
    onUnpair: (String) -> Unit,
    onPushClipboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Nexus", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "This device: ${identity.name} (${identity.platform})\n" +
                        "Device ID: ${identity.deviceId}\n" +
                        "Advertising port: $port — state: $state",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            ConnectionCard(session = session, clipboard = clipboard, onPushClipboard = onPushClipboard)
        }

        item {
            Button(onClick = onStartPairingMode) {
                Text(if (pairingMode) "Pairing mode: on (60 s)" else "Allow a new pairing")
            }
        }

        item {
            Text(
                text = "Trusted devices (${trusted.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (trusted.isEmpty()) {
            item {
                Text(
                    text = "No devices paired yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            items(trusted, key = { it.deviceId }) { device ->
                TrustedCard(device, onUnpair)
            }
        }

        item {
            Text(
                text = "Discovered peers (${peers.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }
        items(peers, key = { it.serviceName }) { peer ->
            PeerCard(peer, onPairPeer)
        }
    }
}

@Composable
fun ConnectionCard(
    session: DiscoveryController.SessionUi,
    clipboard: ClipboardSync.Status,
    onPushClipboard: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "Connection", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (session.ready) {
                    "Connected to ${session.peerName ?: "a trusted device"}"
                } else {
                    "Not connected — a paired device will reconnect automatically."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = buildString {
                    append("Clipboard: ")
                    append(if (clipboard.connected) "linked" else "no session")
                    append(if (clipboard.active) " · watching" else " · paused")
                    clipboard.lastDirection?.let { append(" · last $it") }
                },
                style = MaterialTheme.typography.bodySmall
            )
            clipboard.lastPreview?.let {
                Text(text = "“$it”", style = MaterialTheme.typography.bodySmall)
            }
            Text(text = clipboard.note, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPushClipboard,
                    enabled = clipboard.connected && clipboard.active
                ) { Text("Send clipboard now") }
            }
        }
    }
}

@Composable
fun PeerCard(peer: PeerInfo, onPair: (PeerInfo) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = peer.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${peer.platform} · pv=${peer.protocolVersion}\n" +
                    "${peer.host}:${peer.port}\n${peer.deviceId}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPair(peer) }) { Text("Pair") }
            }
        }
    }
}

@Composable
fun TrustedCard(device: TrustedDevice, onUnpair: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = device.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${device.platform} · paired\n${device.deviceId}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onUnpair(device.deviceId) }) { Text("Unpair") }
            }
        }
    }
}
