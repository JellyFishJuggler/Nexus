package dev.nexus.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nexus.local.clipboard.ClipboardSync
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.discovery.PeerInfo
import dev.nexus.local.identity.DeviceIdentity
import dev.nexus.local.trust.TrustedDevice
import dev.nexus.local.ui.theme.NexusGreen
import dev.nexus.local.ui.theme.NexusGreenContainer
import dev.nexus.local.ui.theme.NexusInkFaint
import dev.nexus.local.ui.theme.NexusInkMuted
import dev.nexus.local.ui.theme.NexusOnGreenContainer
import dev.nexus.local.ui.theme.NexusRed

/**
 * The Nexus home screen.
 *
 * One scrolling column of white cards on an off-white background. Everything
 * here is runtime state: the connected device, its trust status, clipboard
 * state, pairing controls and the devices currently on this network. Raw
 * protocol values live only in the collapsed technical section at the bottom.
 */
@Composable
fun NexusScreen(
    state: String,
    port: Int,
    identity: DeviceIdentity,
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
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header(session = session, state = state) }

        item {
            ConnectionCard(
                session = session,
                clipboard = clipboard,
                onPushClipboard = onPushClipboard
            )
        }

        item { PairingCard(pairingMode = pairingMode, onStartPairingMode = onStartPairingMode) }

        item {
            SectionTitle(
                text = "Trusted devices",
                trailing = if (trusted.isEmpty()) null else trusted.size.toString()
            )
        }
        if (trusted.isEmpty()) {
            item {
                EmptyState(
                    title = "No devices paired yet",
                    body = "Pair a device once and it will reconnect automatically " +
                        "whenever it is on this network."
                )
            }
        } else {
            items(trusted, key = { it.deviceId }) { device ->
                TrustedDeviceCard(device = device, onUnpair = onUnpair)
            }
        }

        item {
            SectionTitle(
                text = "Nearby devices",
                trailing = if (peers.isEmpty()) null else peers.size.toString()
            )
        }
        if (peers.isEmpty()) {
            item {
                EmptyState(
                    title = "No devices found yet",
                    body = "Open Nexus on your other device and make sure both are on " +
                        "the same Wi-Fi network."
                )
            }
        } else {
            items(peers, key = { it.serviceName }) { peer ->
                PeerCard(peer = peer, onPair = onPairPeer)
            }
        }

        item { ThisDeviceCard(identity = identity, port = port, state = state) }
    }
}

@Composable
private fun Header(session: DiscoveryController.SessionUi, state: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Nexus",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            StatusChip(session = session, state = state)
        }
        Text(
            text = "Your phone and computer, connected locally.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusChip(session: DiscoveryController.SessionUi, state: String) {
    val label: String
    val container: Color
    val content: Color
    when {
        session.ready -> {
            label = "Connected"
            container = NexusGreenContainer
            content = NexusOnGreenContainer
        }

        state.startsWith("browse-failed") || state.startsWith("advertise-failed") -> {
            label = "Discovery issue"
            container = MaterialTheme.colorScheme.errorContainer
            content = NexusRed
        }

        state == "browsing" || state == "advertising" -> {
            label = "Discovering"
            container = MaterialTheme.colorScheme.surfaceVariant
            content = NexusInkMuted
        }

        else -> {
            label = "Idle"
            container = MaterialTheme.colorScheme.surfaceVariant
            content = NexusInkMuted
        }
    }
    NexusChip(text = label, container = container, content = content)
}

@Composable
private fun ConnectionCard(
    session: DiscoveryController.SessionUi,
    clipboard: ClipboardSync.Status,
    onPushClipboard: () -> Unit
) {
    NexusCard {
        if (session.ready) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(NexusGreen)
                Column {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = session.peerName ?: "a trusted device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            KeyValueRow("Security", "TLS 1.3 · certificate pinned")
            KeyValueRow(
                label = "Clipboard",
                value = buildString {
                    append(if (clipboard.active) "Syncing" else "Paused")
                    clipboard.lastDirection?.let { append(" · last $it") }
                }
            )

            clipboard.lastPreview?.let { preview ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onPushClipboard,
                enabled = clipboard.connected && clipboard.active
            ) { Text("Send clipboard now") }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(NexusInkFaint)
                Column {
                    Text(
                        text = "No device connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Trusted devices reconnect automatically when they are " +
                            "on this network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            KeyValueRow("Clipboard", "Waiting for a device")
        }
    }
}

@Composable
private fun PairingCard(pairingMode: Boolean, onStartPairingMode: () -> Unit) {
    NexusCard {
        Text(
            text = "Pair a new device",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Open a 60-second window, then confirm the code shown on both screens.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onStartPairingMode,
            colors = if (pairingMode) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(if (pairingMode) "Pairing window open · 60 s" else "Allow a new pairing")
        }
    }
}

@Composable
private fun TrustedDeviceCard(device: TrustedDevice, onUnpair: (String) -> Unit) {
    NexusCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = platformLabel(device.platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NexusChip(
                text = "Paired",
                container = NexusGreenContainer,
                content = NexusOnGreenContainer
            )
        }
        TextButton(
            onClick = { onUnpair(device.deviceId) },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("Unpair", color = NexusRed)
        }
    }
}

@Composable
private fun PeerCard(peer: PeerInfo, onPair: (PeerInfo) -> Unit) {
    NexusCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = peer.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = platformLabel(peer.platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = { onPair(peer) }) { Text("Pair") }
        }
    }
}

/**
 * Technical details, kept out of the main flow but available for pairing and
 * troubleshooting. This is the only place raw protocol values appear.
 */
@Composable
private fun ThisDeviceCard(identity: DeviceIdentity, port: Int, state: String) {
    NexusCard {
        Text(
            text = "This device",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        KeyValueRow("Name", identity.name)
        KeyValueRow("Platform", platformLabel(identity.platform))
        KeyValueRow("Device ID", identity.deviceId, monospace = true)
        KeyValueRow("Connection port", port.toString())
        KeyValueRow("Discovery", discoveryLabel(state))
        Text(
            text = "Technical details, shown for pairing and troubleshooting.",
            style = MaterialTheme.typography.labelSmall,
            color = NexusInkFaint
        )
    }
}

@Composable
private fun SectionTitle(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NexusCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun NexusChip(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, monospace: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            textAlign = TextAlign.End
        )
    }
}

/** "android" → "Android"; unknown platforms are shown as-is. */
private fun platformLabel(platform: String): String = when (platform.lowercase()) {
    "android" -> "Android"
    "windows" -> "Windows"
    "linux" -> "Linux"
    "macos", "mac" -> "macOS"
    "ios" -> "iOS"
    else -> platform.ifBlank { "Unknown" }
}

/** Human wording for the discovery state string (protocol/discovery.md). */
private fun discoveryLabel(state: String): String = when {
    state.startsWith("browse-failed") || state.startsWith("advertise-failed") ->
        "Error — check Wi-Fi"

    state == "browsing" || state == "advertising" -> "Searching for devices"
    state == "stopped" -> "Paused"
    state == "idle" -> "Starting…"
    else -> "Searching for devices"
}
