package dev.nexus.local.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nexus.local.ui.theme.nexus

/**
 * A short, reusable explanation of the real pairing flow — not a first-run
 * wizard and not a second pairing implementation. Every action here is the
 * same responder window the Home screen and Devices screen already offer.
 */
@Composable
fun SetupGuideDialog(
    pairingMode: Boolean,
    onStartPairing: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = MaterialTheme.nexus
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up a device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Nexus connects your phone and computer directly on " +
                        "your local network. You only need to pair them once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted
                )
                SetupStep(
                    number = 1,
                    title = "Same network",
                    body = "Connect both devices to the same Wi-Fi network."
                )
                SetupStep(
                    number = 2,
                    title = "Open the pairing window",
                    body = if (pairingMode) {
                        "The 60-second pairing window is open on this phone."
                    } else {
                        "Tap Start pairing below. Nexus opens a pairing window " +
                            "on this phone for 60 seconds."
                    }
                )
                SetupStep(
                    number = 3,
                    title = "Pair from your computer",
                    body = "On your computer, open Nexus → Devices, choose this " +
                        "phone and tap Pair. Compare the code shown on both " +
                        "screens, then confirm."
                )
                Text(
                    text = "After pairing, Nexus reconnects automatically " +
                        "whenever both devices are online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkFaint
                )
            }
        },
        confirmButton = {
            if (pairingMode) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(
                    onClick = {
                        onStartPairing()
                        onDismiss()
                    }
                ) { Text("Start pairing") }
            }
        },
        dismissButton = {
            if (!pairingMode) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun SetupStep(number: Int, title: String, body: String) {
    val p = MaterialTheme.nexus
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(p.greenContainer, CircleShape)
                .border(1.dp, p.greenBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = p.onGreenContainer
            )
        }
        Column(modifier = Modifier.padding(top = 2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = p.ink
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
