package dev.nexus.local.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.nexus.local.clipboard.ClipboardSync
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.discovery.PeerInfo
import dev.nexus.local.pairing.PairingManager
import dev.nexus.local.trust.TrustedDevice
import dev.nexus.local.ui.components.NexusTopBar
import dev.nexus.local.ui.devices.DevicesScreen
import dev.nexus.local.ui.home.HomeScreen
import dev.nexus.local.ui.pairing.PairingRequestScreen
import dev.nexus.local.ui.pairing.PairingResultDialogs
import dev.nexus.local.ui.settings.SettingsScreen
import dev.nexus.local.ui.theme.nexus

/** Primary navigation destinations. */
enum class NexusTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    DEVICES("Devices", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Default.Settings)
}

/** Selected segmented section on the Devices screen. */
enum class DevicesSection { NEARBY, TRUSTED }

/**
 * Everything the UI renders, assembled from the controller's flows.
 *
 * Values are resolved at runtime only — no device names, addresses, codes or
 * versions are hardcoded anywhere in the UI layer.
 */
data class NexusUiState(
    val deviceName: String,
    val androidVersion: String,
    val peers: List<PeerInfo>,
    val trusted: List<TrustedDevice>,
    val session: DiscoveryController.SessionUi,
    val clipboard: ClipboardSync.Status,
    val pairing: PairingManager.Ui,
    val pairingMode: Boolean,
    /** A session was established this run and is no longer ready. */
    val connectionLost: Boolean,
    /** What the automatic reconnection to a trusted device is doing. */
    val reconnect: DiscoveryController.ReconnectUi,
    val darkTheme: Boolean,
    /** Keep the local link available after a reboot. */
    val startOnBoot: Boolean,
    /** Opt-in phone→computer notification mirroring. */
    val mirrorNotifications: Boolean,
    /** Strip notification bodies before forwarding. */
    val hideNotificationText: Boolean,
    /** Whether the system notification-listener grant is in place. */
    val notificationAccess: Boolean
)

/**
 * The real actions the UI can perform. Each one maps to an existing
 * controller call; nothing here is a placeholder.
 */
data class NexusActions(
    /** Open the 60-second pairing window (responder side). */
    val onStartPairing: () -> Unit,
    /** Re-run discovery using the existing stop/start lifecycle. */
    val onRefresh: () -> Unit,
    /** Connect to a discovered peer and pair as initiator. */
    val onPair: (PeerInfo) -> Unit,
    val onAcceptPairing: () -> Unit,
    val onRejectPairing: () -> Unit,
    val onDismissPairing: () -> Unit,
    /** Forget a trusted device. */
    val onUnpair: (String) -> Unit,
    /** Push this device's clipboard to the connected peer now. */
    val onSendClipboard: () -> Unit,
    val onOpenRepository: () -> Unit,
    val onToggleDarkTheme: (Boolean) -> Unit,
    /** Start Nexus in the background after a reboot. */
    val onToggleStartOnBoot: (Boolean) -> Unit,
    /** Enable/disable phone→computer notification mirroring. */
    val onToggleMirrorNotifications: (Boolean) -> Unit,
    /** Forward only app + title, never the notification body. */
    val onToggleHideNotificationText: (Boolean) -> Unit,
    /** Open the system notification-listener access screen. */
    val onOpenNotificationAccess: () -> Unit
)

/**
 * Application shell: header, bottom navigation and the pairing flow.
 *
 * Screens are stateless; all of their data comes from [state] and all of
 * their interactions go through [actions].
 */
@Composable
fun NexusApp(
    state: NexusUiState,
    actions: NexusActions,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(NexusTab.HOME) }
    var devicesSection by remember { mutableStateOf(DevicesSection.NEARBY) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nexus.background)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.nexus.background,
            topBar = {
                NexusTopBar(
                    title = when (tab) {
                        NexusTab.HOME -> "Nexus"
                        NexusTab.DEVICES -> "Devices"
                        NexusTab.SETTINGS -> "Settings"
                    },
                    subtitle = when (tab) {
                        NexusTab.HOME -> "Your devices, connected locally."
                        NexusTab.DEVICES -> "Manage devices connected to Nexus."
                        NexusTab.SETTINGS -> "Preferences for this Nexus device."
                    },
                    onOpenSettings = if (tab == NexusTab.SETTINGS) {
                        null
                    } else {
                        { tab = NexusTab.SETTINGS }
                    }
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.nexus.surface,
                    tonalElevation = 0.dp
                ) {
                    NexusTab.values().forEach { destination ->
                        val selected = destination == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = destination },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(destination.label) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.nexus.onGreenContainer,
                                selectedTextColor = MaterialTheme.nexus.onGreenContainer,
                                indicatorColor = MaterialTheme.nexus.greenContainer,
                                unselectedIconColor = MaterialTheme.nexus.inkFaint,
                                unselectedTextColor = MaterialTheme.nexus.inkMuted
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            NexusContent(
                state = state,
                actions = actions,
                tab = tab,
                devicesSection = devicesSection,
                onOpenTab = { tab = it },
                onSectionChange = { devicesSection = it },
                innerPadding = innerPadding
            )
        }

        // Pairing is a blocking, full-screen flow: it is never auto-accepted
        // and it always requires an explicit decision (pairing.md).
        val pairing = state.pairing
        if (pairing is PairingManager.Ui.Prompt) {
            PairingRequestScreen(
                prompt = pairing,
                onAccept = actions.onAcceptPairing,
                onReject = actions.onRejectPairing
            )
        }
    }

    PairingResultDialogs(
        ui = state.pairing,
        onDismiss = actions.onDismissPairing
    )
}

@Composable
private fun NexusContent(
    state: NexusUiState,
    actions: NexusActions,
    tab: NexusTab,
    devicesSection: DevicesSection,
    onOpenTab: (NexusTab) -> Unit,
    onSectionChange: (DevicesSection) -> Unit,
    innerPadding: PaddingValues
) {
    Crossfade(targetState = tab, label = "tab-content") { current ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (current) {
            NexusTab.HOME -> HomeScreen(
                state = state,
                actions = actions,
                onOpenDevices = {
                    onSectionChange(DevicesSection.NEARBY)
                    onOpenTab(NexusTab.DEVICES)
                },
                modifier = contentModifier
            )

            NexusTab.DEVICES -> DevicesScreen(
                state = state,
                actions = actions,
                section = devicesSection,
                onSectionChange = onSectionChange,
                modifier = contentModifier
            )

            NexusTab.SETTINGS -> SettingsScreen(
                state = state,
                actions = actions,
                onOpenTrustedDevices = {
                    onSectionChange(DevicesSection.TRUSTED)
                    onOpenTab(NexusTab.DEVICES)
                },
                modifier = contentModifier
            )
        }
    }
}
