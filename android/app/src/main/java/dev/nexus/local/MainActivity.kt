package dev.nexus.local

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.pairing.PairingManager
import dev.nexus.local.ui.NexusScreen
import dev.nexus.local.ui.theme.NexusRed
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

                Scaffold(modifier = Modifier.fillMaxWidth()) { innerPadding ->
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
     * Test-harness triggers, mirroring the Rust CLI's flags so scripted runs
     * can drive the same code paths the UI buttons use:
     *
     * - `--ez nexus_pairing true`      open the pairing window (responder)
     * - `--es nexus_autoconfirm CODE`  auto-accept when the code matches
     * - `--es nexus_host H --ei nexus_port P`  connect and pair (initiator)
     * - `--es nexus_set_clipboard S`   simulate a local copy of `S` and send it
     * - `--ez nexus_read_clipboard true`  report the current system clipboard
     * - `--es nexus_expect_clipboard S`   assert the clipboard equals `S`
     *
     * These hooks exist **only in debug builds** (`BuildConfig.DEBUG`). A
     * release build ignores the extras entirely, so another app cannot use
     * them to auto-accept a pairing or dial out on the user's behalf.
     */
    private fun handleHarnessIntent(intent: Intent?) {
        if (intent == null) return
        if (!BuildConfig.DEBUG) return

        val pairing = intent.getBooleanExtra(EXTRA_PAIRING, false)
        val auto = intent.getStringExtra(EXTRA_AUTOCONFIRM)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val clip = intent.getStringExtra(EXTRA_SET_CLIPBOARD)
        val readClip = intent.getBooleanExtra(EXTRA_READ_CLIPBOARD, false)
        val expectClip = intent.getStringExtra(EXTRA_EXPECT_CLIPBOARD)
        // Never log clipboard contents — only the size of the harness payload.
        Log.i(
            "NexusHarness",
            "intent extras: pairing=$pairing autoconfirm=$auto host=$host port=$port " +
                "clipLen=${clip?.length ?: 0} readClip=$readClip expectClip=${expectClip != null}"
        )

        // Some devices redeliver the launch intent several times. Ignore exact
        // repeats so the harness (a test-only path) does not open the pairing
        // window, or dial out, more than once per launch.
        val signature = "p=$pairing;a=$auto;h=$host;o=$port;c=$clip;r=$readClip;e=$expectClip"
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
        expectClip?.let { controller.expectClipboard(it) }
    }

    companion object {
        private const val EXTRA_HOST = "nexus_host"
        private const val EXTRA_PORT = "nexus_port"
        private const val EXTRA_PAIRING = "nexus_pairing"
        private const val EXTRA_AUTOCONFIRM = "nexus_autoconfirm"
        private const val EXTRA_SET_CLIPBOARD = "nexus_set_clipboard"
        private const val EXTRA_READ_CLIPBOARD = "nexus_read_clipboard"
        private const val EXTRA_EXPECT_CLIPBOARD = "nexus_expect_clipboard"

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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Confirm this code matches the one shown on " +
                            "${ui.peerName}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = ui.code,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = "If the codes differ, reject — someone may be trying to " +
                            "intercept the connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { controller.acceptPairing() }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { controller.rejectPairing() }) {
                    Text("Reject", color = NexusRed)
                }
            }
        )

        is PairingManager.Ui.Succeeded -> AlertDialog(
            onDismissRequest = { controller.clearPairingUi() },
            title = { Text("Paired") },
            text = { Text("${ui.peerName} is now trusted and will reconnect automatically.") },
            confirmButton = {
                Button(onClick = { controller.clearPairingUi() }) { Text("Done") }
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
