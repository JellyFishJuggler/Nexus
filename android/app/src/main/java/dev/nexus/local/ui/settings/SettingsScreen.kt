package dev.nexus.local.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nexus.local.BuildConfig
import dev.nexus.local.ui.NexusActions
import dev.nexus.local.ui.NexusUiState
import dev.nexus.local.ui.components.InfoRow
import dev.nexus.local.ui.components.NexusCard
import dev.nexus.local.ui.components.SectionLabel
import dev.nexus.local.ui.theme.nexus

/**
 * Settings.
 *
 * Only real controls appear here. The privacy rows are statements of fact
 * about how Nexus works, not toggles, so there is nothing to fake: everything
 * either switches a real behaviour or opens the real repository.
 */
@Composable
fun SettingsScreen(
    state: NexusUiState,
    actions: NexusActions,
    onOpenTrustedDevices: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GeneralSection(state = state, actions = actions)
        PrivacySection(
            trustedCount = state.trusted.size,
            onOpenTrustedDevices = onOpenTrustedDevices
        )
        AboutSection(actions = actions)
    }
}

@Composable
private fun GeneralSection(state: NexusUiState, actions: NexusActions) {
    val p = MaterialTheme.nexus
    NexusCard {
        SectionLabel("General")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dark mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = p.ink
                )
                Text(
                    text = "Use the dark Nexus palette.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(
                checked = state.darkTheme,
                onCheckedChange = actions.onToggleDarkTheme,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = p.green,
                    checkedBorderColor = p.green,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = p.borderStrong,
                    uncheckedBorderColor = p.borderStrong
                )
            )
        }
    }
}

@Composable
private fun PrivacySection(trustedCount: Int, onOpenTrustedDevices: () -> Unit) {
    NexusCard {
        SectionLabel("Privacy & security")
        Column(
            modifier = Modifier.padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoRow(
                title = "Local network only",
                subtitle = "Your data never leaves your devices."
            )
            InfoRow(
                title = "End-to-end encrypted",
                subtitle = "Clipboard data is encrypted in transit."
            )
            InfoRow(
                title = "Trusted devices",
                subtitle = "Manage paired and trusted devices.",
                value = trustedCount.toString(),
                onClick = onOpenTrustedDevices
            )
        }
    }
}

@Composable
private fun AboutSection(actions: NexusActions) {
    val p = MaterialTheme.nexus
    NexusCard {
        SectionLabel("About")
        Text(
            text = "Nexus v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
            color = p.ink,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            text = "A local-first clipboard companion.",
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(modifier = Modifier.padding(top = 14.dp)) {
            InfoRow(
                title = "GitHub Repository",
                subtitle = "Source code and issue tracker.",
                onClick = actions.onOpenRepository
            )
        }
    }
}
