package dev.nexus.local.ui.devices

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import dev.nexus.local.discovery.PeerInfo
import dev.nexus.local.trust.TrustedDevice
import dev.nexus.local.ui.DevicesSection
import dev.nexus.local.ui.NexusActions
import dev.nexus.local.ui.NexusUiState
import dev.nexus.local.ui.components.CardHeading
import dev.nexus.local.ui.components.CompactButton
import dev.nexus.local.ui.components.DeviceGlyph
import dev.nexus.local.ui.components.EmptyState
import dev.nexus.local.ui.components.GhostButton
import dev.nexus.local.ui.components.NexusCard
import dev.nexus.local.ui.components.SegmentedTabs
import dev.nexus.local.ui.components.StatusLabel
import dev.nexus.local.ui.components.platformLabel
import dev.nexus.local.ui.theme.nexus

/**
 * Devices: everything Nexus can see and everything it already trusts.
 *
 * The Nearby list is live discovery state; Trusted is the pinned trust store.
 * Both are rendered straight from the controller, so a device appears and
 * disappears exactly as the network changes.
 */
@Composable
fun DevicesScreen(
    state: NexusUiState,
    actions: NexusActions,
    section: DevicesSection,
    onSectionChange: (DevicesSection) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingRemoval by remember { mutableStateOf<TrustedDevice?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SegmentedTabs(
            labels = listOf("Nearby", "Trusted"),
            selectedIndex = if (section == DevicesSection.NEARBY) 0 else 1,
            onSelect = { index ->
                onSectionChange(
                    if (index == 0) DevicesSection.NEARBY else DevicesSection.TRUSTED
                )
            }
        )

        when (section) {
            DevicesSection.NEARBY -> NearbySection(state = state, actions = actions)
            DevicesSection.TRUSTED -> TrustedSection(
                state = state,
                onRemove = { pendingRemoval = it }
            )
        }
    }

    val removal = pendingRemoval
    if (removal != null) {
        RemoveDeviceDialog(
            device = removal,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                actions.onUnpair(removal.deviceId)
                pendingRemoval = null
            }
        )
    }
}

// ---- nearby ---------------------------------------------------------------

@Composable
private fun NearbySection(state: NexusUiState, actions: NexusActions) {
    val p = MaterialTheme.nexus
    NexusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                CardHeading("Nearby devices")
                Text(
                    text = if (state.peers.isEmpty()) {
                        "Searching your local network…"
                    } else {
                        "Discovered on your local network."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            GhostButton(text = "Refresh", onClick = actions.onRefresh)
        }

        if (state.peers.isEmpty()) {
            EmptyState(
                title = "No devices found",
                subtitle = "Make sure your computer and phone are on the same " +
                    "local network.",
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = p.inkFaint,
                        modifier = Modifier.size(24.dp)
                    )
                },
                actionLabel = "Refresh",
                onAction = actions.onRefresh
            )
        } else {
            state.peers.forEachIndexed { index, peer ->
                if (index > 0) {
                    HorizontalDivider(color = p.border)
                }
                NearbyRow(state = state, peer = peer, actions = actions)
            }
        }
    }
}

@Composable
private fun NearbyRow(state: NexusUiState, peer: PeerInfo, actions: NexusActions) {
    val p = MaterialTheme.nexus
    val connected = state.session.ready && state.session.peerDeviceId == peer.deviceId
    val trusted = state.trusted.any { it.deviceId == peer.deviceId }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DeviceGlyph(platform = peer.platform)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.name,
                style = MaterialTheme.typography.titleSmall,
                color = p.ink
            )
            Text(
                text = platformLabel(peer.platform),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        when {
            connected -> StatusLabel(
                text = "Connected",
                dotColor = p.green
            )

            trusted -> StatusLabel(
                text = "Trusted",
                dotColor = p.greenSoft
            )

            else -> CompactButton(
                text = "Pair",
                onClick = { actions.onPair(peer) }
            )
        }
    }
}

// ---- trusted --------------------------------------------------------------

@Composable
private fun TrustedSection(state: NexusUiState, onRemove: (TrustedDevice) -> Unit) {
    val p = MaterialTheme.nexus
    NexusCard {
        CardHeading("Trusted devices")
        Text(
            text = "Devices you have paired with. They reconnect automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            modifier = Modifier.padding(top = 2.dp)
        )

        if (state.trusted.isEmpty()) {
            EmptyState(
                title = "No trusted devices",
                subtitle = "Pair a device to get started."
            )
        } else {
            state.trusted.forEachIndexed { index, device ->
                if (index > 0) {
                    HorizontalDivider(color = p.border)
                }
                TrustedRow(state = state, device = device, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun TrustedRow(
    state: NexusUiState,
    device: TrustedDevice,
    onRemove: (TrustedDevice) -> Unit
) {
    val p = MaterialTheme.nexus
    val connected = state.session.ready && state.session.peerDeviceId == device.deviceId
    val nearby = state.peers.any { it.deviceId == device.deviceId }
    val (label, dot) = when {
        connected -> "Connected" to p.green
        nearby -> "Trusted" to p.greenSoft
        else -> "Offline" to p.inkFaint
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DeviceGlyph(platform = device.platform)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name.ifBlank { "Paired device" },
                style = MaterialTheme.typography.titleSmall,
                color = p.ink
            )
            Text(
                text = "${platformLabel(device.platform)} · Paired " +
                    pairedLabel(device.pairedAt),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            StatusLabel(
                text = label,
                dotColor = dot,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        IconButton(onClick = { onRemove(device) }) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove ${device.name}",
                tint = p.inkFaint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RemoveDeviceDialog(
    device: TrustedDevice,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove trusted device?") },
        text = {
            Text(
                "Nexus will forget ${device.name.ifBlank { "this device" }} and forget " +
                    "its certificate. You will need to pair again to reconnect."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.nexus.red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun pairedLabel(pairedAt: Long): String {
    if (pairedAt <= 0L) return "recently"
    return DateUtils.getRelativeTimeSpanString(pairedAt * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
