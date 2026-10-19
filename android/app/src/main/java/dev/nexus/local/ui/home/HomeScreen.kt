package dev.nexus.local.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.pairing.PairingManager
import dev.nexus.local.ui.NexusActions
import dev.nexus.local.ui.NexusUiState
import dev.nexus.local.ui.components.CardHeading
import dev.nexus.local.ui.components.ConnectionIllustration
import dev.nexus.local.ui.components.DeviceGlyph
import dev.nexus.local.ui.components.EmptyState
import dev.nexus.local.ui.components.KeyValueRow
import dev.nexus.local.ui.components.NexusCard
import dev.nexus.local.ui.components.PillKind
import dev.nexus.local.ui.components.PrimaryButton
import dev.nexus.local.ui.components.ProgressTrack
import dev.nexus.local.ui.components.SecondaryButton
import dev.nexus.local.ui.components.SectionLabel
import dev.nexus.local.ui.components.StatusDot
import dev.nexus.local.ui.components.StatusLabel
import dev.nexus.local.ui.components.StatusPill
import dev.nexus.local.ui.components.platformLabel
import dev.nexus.local.ui.components.rememberCountdownProgress
import dev.nexus.local.ui.components.secondsLeft
import dev.nexus.local.ui.components.shorten
import dev.nexus.local.ui.theme.nexus

/**
 * Home: the product's headline state.
 *
 * When no device is connected it explains what Nexus is and offers the one
 * action that starts pairing. When a device is connected the same screen
 * becomes the live status view — it is transformed, not replaced, so the user
 * never loses their place.
 */
@Composable
fun HomeScreen(
    state: NexusUiState,
    actions: NexusActions,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier
) {
    var detailsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.connectionLost) {
            ConnectionLostPanel(state = state)
        }

        if (state.session.ready) {
            ConnectedHero(state)
        } else {
            DisconnectedHero(state = state, actions = actions)
        }

        ThisDeviceCard(
            state = state,
            onOpenDetails = { detailsOpen = true }
        )

        if (state.session.ready) {
            ClipboardCard(state = state, actions = actions)
        }

        ActivityCard(state = state)

        if (state.trusted.isNotEmpty()) {
            TrustedShortcut(count = state.trusted.size, onClick = onOpenDevices)
        }
    }

    if (detailsOpen) {
        ThisDeviceDialog(state = state, onDismiss = { detailsOpen = false })
    }
}

// ---- disconnected ---------------------------------------------------------

@Composable
private fun DisconnectedHero(state: NexusUiState, actions: NexusActions) {
    val p = MaterialTheme.nexus
    NexusCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ConnectionIllustration()
            Text(
                text = "Your phone and computer,\nconnected locally.",
                style = MaterialTheme.typography.displaySmall,
                color = p.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp)
            )
            Text(
                text = "Secure. Private. Fast. No cloud.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(20.dp))
            if (state.pairingMode) {
                PairingWindowPanel()
            } else {
                PrimaryButton(
                    text = "Pair a device",
                    onClick = actions.onStartPairing,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Opens a pairing window on this phone for 60 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}

/** Amber "window is open" panel, mirroring the desktop pairing-live block. */
@Composable
private fun PairingWindowPanel() {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(9.dp)
    val remaining = rememberCountdownProgress(PairingManager.PAIRING_WINDOW_MS)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.amberContainer)
            .border(1.dp, p.amberBorder, shape)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(color = p.amber)
            Text(
                text = "Ready to pair",
                style = MaterialTheme.typography.titleSmall,
                color = p.amberHeading
            )
        }
        Text(
            text = "On your computer, choose Nexus, pick this phone, then confirm " +
                "the code shown on both screens.",
            style = MaterialTheme.typography.bodySmall,
            color = p.onAmberContainer,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        ProgressTrack(progress = remaining, color = p.amber)
        Text(
            text = "Closes in ${secondsLeft(remaining, PairingManager.PAIRING_WINDOW_MS)}s",
            style = MaterialTheme.typography.bodySmall,
            color = p.onAmberContainer,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ConnectionLostPanel(state: NexusUiState) {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(9.dp)
    // Say what is actually happening: a dial in flight, a retry loop that has
    // a trusted device to reach, or a link that ended with nothing left to
    // reconnect to.
    val detail = when {
        state.reconnect is DiscoveryController.ReconnectUi.Connecting -> "Reconnecting…"
        state.trusted.isNotEmpty() -> "Trying to reconnect…"
        else -> "No saved device to reconnect to."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.amberContainer)
            .border(1.dp, p.amberBorder, shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = p.amber,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(
                text = "Connection lost",
                style = MaterialTheme.typography.titleSmall,
                color = p.amberHeading
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = p.onAmberContainer
            )
        }
    }
}

// ---- connected ------------------------------------------------------------

@Composable
private fun ConnectedHero(state: NexusUiState) {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(12.dp)
    val platform = connectedPeerPlatform(state)
    val peerName = state.session.peerName ?: "Connected device"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.greenContainer)
            .border(1.dp, p.greenBorder, shape)
            .padding(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DeviceGlyph(platform = platform, size = 52.dp)
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Connected device")
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.titleLarge,
                    color = p.ink,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = platformText(platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.onGreenContainer,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        StatusPill(text = "Connected • Trusted", kind = PillKind.GREEN)
    }
}

@Composable
private fun ClipboardCard(state: NexusUiState, actions: NexusActions) {
    val p = MaterialTheme.nexus
    NexusCard {
        CardHeading("Clipboard Sync")
        StatusLabel(
            text = if (state.clipboard.connected) "Connected" else "Not connected",
            dotColor = if (state.clipboard.connected) p.green else p.inkFaint,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = state.clipboard.note,
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            modifier = Modifier.padding(top = 12.dp)
        )
        SecondaryButton(
            text = "Copy on both",
            onClick = actions.onSendClipboard,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
        )
    }
}

// ---- shared cards ---------------------------------------------------------

@Composable
private fun ThisDeviceCard(state: NexusUiState, onOpenDetails: () -> Unit) {
    val p = MaterialTheme.nexus
    NexusCard(onClick = onOpenDetails) {
        SectionLabel("This device")
        Row(
            modifier = Modifier.padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeviceGlyph(platform = "android", size = 42.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = p.ink
                )
                Text(
                    text = "Android ${state.androidVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = p.inkFaint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ActivityCard(state: NexusUiState) {
    val p = MaterialTheme.nexus
    val direction = state.clipboard.lastDirection
    val preview = state.clipboard.lastPreview

    NexusCard {
        CardHeading("Recent activity")
        if (direction == null) {
            EmptyState(
                title = "No activity yet",
                subtitle = "Clipboard events will appear here once a device is connected."
            )
        } else {
            val received = direction == "received"
            Row(
                modifier = Modifier.padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.greenContainer)
                        .border(1.dp, p.greenBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (received) {
                            Icons.Default.Check
                        } else {
                            Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = null,
                        tint = p.green,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (received) {
                            "Copied on ${platformText(connectedPeerPlatform(state))}"
                        } else {
                            "Copied on this phone"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.ink
                    )
                    if (preview != null) {
                        Text(
                            text = shorten(preview),
                            style = MaterialTheme.typography.bodySmall,
                            color = p.inkMuted,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustedShortcut(count: Int, onClick: () -> Unit) {
    val p = MaterialTheme.nexus
    NexusCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) "1 trusted device" else "$count trusted devices",
                    style = MaterialTheme.typography.titleSmall,
                    color = p.ink
                )
                Text(
                    text = "Manage paired and trusted devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = p.inkFaint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ---- details --------------------------------------------------------------

@Composable
private fun ThisDeviceDialog(state: NexusUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This device") },
        text = {
            Column {
                KeyValueRow(label = "Name", value = state.deviceName)
                KeyValueRow(label = "Platform", value = "Android ${state.androidVersion}")
                KeyValueRow(label = "Network", value = "Local network only")
                Text(
                    text = "Nexus keeps device names and clipboard text on your " +
                        "devices. Nothing is uploaded anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nexus.inkMuted,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

// ---- helpers --------------------------------------------------------------

/** Platform of the device we currently hold a session with, or "". */
internal fun connectedPeerPlatform(state: NexusUiState): String {
    val id = state.session.peerDeviceId ?: return ""
    return state.trusted.firstOrNull { it.deviceId == id }?.platform
        ?: state.peers.firstOrNull { it.deviceId == id }?.platform
        ?: ""
}

internal fun platformText(platform: String): String =
    if (platform.isBlank()) "Paired device" else platformLabel(platform)
