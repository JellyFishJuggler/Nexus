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
        NotificationsSection(state = state, actions = actions)
        BackgroundSection(state = state, actions = actions)
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
private fun NotificationsSection(state: NexusUiState, actions: NexusActions) {
    val p = MaterialTheme.nexus
    NexusCard {
        SectionLabel("Notifications")
        Text(
            text = "Forward notifications from this phone to your connected computer.",
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            modifier = Modifier.padding(top = 10.dp)
        )
        Column(
            modifier = Modifier.padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InfoRow(
                title = "Notification access",
                subtitle = if (state.notificationAccess) {
                    "Granted. Nexus can mirror notifications from this phone."
                } else {
                    "Not granted. Open system settings to allow notification access."
                },
                value = if (state.notificationAccess) "Granted" else "Off",
                onClick = actions.onOpenNotificationAccess
            )
            ToggleRow(
                title = "Mirror notifications",
                subtitle = "Show phone notifications on your computer. Off by default.",
                checked = state.mirrorNotifications,
                enabled = state.notificationAccess,
                onCheckedChange = actions.onToggleMirrorNotifications
            )
            ToggleRow(
                title = "Hide notification text",
                subtitle = "Forward only the app name and title, never the message body.",
                checked = state.hideNotificationText,
                enabled = state.mirrorNotifications,
                onCheckedChange = actions.onToggleHideNotificationText
            )
            Text(
                text = "Notification content is sent only over the encrypted local " +
                    "link. It is never stored, uploaded, or written to logs.",
                style = MaterialTheme.typography.bodySmall,
                color = p.inkFaint
            )
        }
    }
}

@Composable
private fun BackgroundSection(state: NexusUiState, actions: NexusActions) {
    val p = MaterialTheme.nexus
    NexusCard {
        SectionLabel("Background")
        Column(
            modifier = Modifier.padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ToggleRow(
                title = "Start on boot",
                subtitle = "Keep the local link ready after this phone restarts.",
                checked = state.startOnBoot,
                onCheckedChange = actions.onToggleStartOnBoot
            )
            Text(
                text = "Nexus shows a low-priority notification while it runs in the " +
                    "background. Clipboard sync still reads the clipboard only while " +
                    "Nexus is open.",
                style = MaterialTheme.typography.bodySmall,
                color = p.inkFaint
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val p = MaterialTheme.nexus
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = p.ink
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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
