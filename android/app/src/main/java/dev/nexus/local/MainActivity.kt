package dev.nexus.local

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.ui.NexusActions
import dev.nexus.local.ui.NexusApp
import dev.nexus.local.ui.NexusUiState
import dev.nexus.local.ui.theme.NexusTheme
import dev.nexus.local.ui.theme.ThemePreference

class MainActivity : ComponentActivity() {

    private lateinit var controller: DiscoveryController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = DiscoveryController(applicationContext)

        val activity = this
        setContent {
            var darkTheme by remember { mutableStateOf(ThemePreference.isDark(activity)) }

            NexusTheme(darkTheme = darkTheme) {
                val peers by controller.peers.collectAsState()
                val trusted by controller.trustedDevices.collectAsState()
                val session by controller.sessionUi.collectAsState()
                val clipboard by controller.clipboardStatus.collectAsState()
                val pairingUi by controller.pairingUi.collectAsState()
                val pairingMode by controller.pairingMode.collectAsState()
                val reconnect by controller.reconnectUi.collectAsState()
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

                // A link that was live at some point in this run and is not now
                // is a dropped connection. Derived from the real session state,
                // never from a timer or a guess; the reconnect snapshot below
                // says what the app is doing about it.
                var everReady by remember { mutableStateOf(false) }
                LaunchedEffect(session.ready) {
                    if (session.ready) everReady = true
                }

                val state = NexusUiState(
                    deviceName = controller.localIdentity.name,
                    androidVersion = Build.VERSION.RELEASE ?: "",
                    peers = peers,
                    trusted = trusted,
                    session = session,
                    clipboard = clipboard,
                    pairing = pairingUi,
                    pairingMode = pairingMode,
                    connectionLost = everReady && !session.ready,
                    reconnect = reconnect,
                    darkTheme = darkTheme
                )

                NexusApp(
                    state = state,
                    actions = NexusActions(
                        onStartPairing = { controller.startPairingMode() },
                        // Refresh re-runs the existing discovery lifecycle.
                        onRefresh = {
                            controller.stop()
                            controller.start()
                        },
                        onPair = { peer ->
                            if (peer.host.isNotEmpty() && peer.port in 1..65535) {
                                controller.connectTo(peer.host, peer.port)
                            }
                        },
                        onAcceptPairing = { controller.acceptPairing() },
                        onRejectPairing = { controller.rejectPairing() },
                        onDismissPairing = { controller.clearPairingUi() },
                        onUnpair = { controller.unpair(it) },
                        onSendClipboard = { controller.pushClipboard() },
                        onOpenRepository = ::openRepository,
                        onToggleDarkTheme = { enabled ->
                            darkTheme = enabled
                            ThemePreference.setDark(activity, enabled)
                        }
                    )
                )
            }
        }

        handleHarnessIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleHarnessIntent(intent)
    }

    /** Opens the project's own repository page in whatever app can handle it. */
    private fun openRepository() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "no app available to open the repository link") }
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
        private const val TAG = "NexusMain"

        /**
         * The project's own repository, as published by the desktop build
         * (`tauri.conf.json` homepage / `Cargo.toml` repository).
         */
        private const val REPOSITORY_URL = "https://github.com/JellyFishJuggler/Nexus"

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
