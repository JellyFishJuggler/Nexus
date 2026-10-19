package dev.nexus.local.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexus.local.pairing.PairingManager
import dev.nexus.local.ui.components.ConnectionIllustration
import dev.nexus.local.ui.components.DangerButton
import dev.nexus.local.ui.components.PrimaryButton
import dev.nexus.local.ui.components.ProgressTrack
import dev.nexus.local.ui.components.rememberCountdownProgress
import dev.nexus.local.ui.components.secondsLeft
import dev.nexus.local.ui.theme.nexus

/**
 * The pairing request, shown as a blocking full-screen step.
 *
 * Nothing about the security decision is implicit: the short authentication
 * string (SAS) is displayed in full, acceptance is always an explicit tap, and
 * there is no path that skips the comparison. Back is deliberately inert so
 * the prompt cannot be dismissed into an ambiguous half-state.
 */
@Composable
fun PairingRequestScreen(
    prompt: PairingManager.Ui.Prompt,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val p = MaterialTheme.nexus
    val remaining = rememberCountdownProgress(PairingManager.PAIRING_TIMEOUT_MS)
    val seconds = secondsLeft(remaining, PairingManager.PAIRING_TIMEOUT_MS)
    // Local only: the engine keeps the prompt up while it waits for the peer,
    // so this just tells the user that their tap was registered.
    var accepted by remember(prompt.peerDeviceId, prompt.code) { mutableStateOf(false) }

    BackHandler(enabled = true) { /* the decision is the only way out */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "PAIRING REQUEST",
                style = MaterialTheme.typography.labelSmall,
                color = p.amberHeading
            )

            Spacer(Modifier.height(22.dp))
            ConnectionIllustration()
            Spacer(Modifier.height(24.dp))

            Text(
                text = "A device wants to pair",
                style = MaterialTheme.typography.displaySmall,
                color = p.ink,
                textAlign = TextAlign.Center
            )
            Text(
                text = prompt.peerName,
                style = MaterialTheme.typography.titleMedium,
                color = p.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(24.dp))
            VerificationCode(code = prompt.code)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Check that this code matches the one shown on " +
                    "${prompt.peerName}. If it does not, reject the request.",
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
            ProgressTrack(progress = remaining, color = p.amber)
            Spacer(Modifier.height(8.dp))

            if (accepted) {
                WaitingRow(peerName = prompt.peerName)
            } else {
                Text(
                    text = "Expires in ${seconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkFaint
                )
                Spacer(Modifier.height(22.dp))
                PrimaryButton(
                    text = "Accept",
                    onClick = {
                        accepted = true
                        onAccept()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                DangerButton(
                    text = "Reject",
                    onClick = onReject,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "Only accept if you recognise this device. Nexus never pairs " +
                    "automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = p.inkFaint,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun VerificationCode(code: String) {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.amberContainer)
            .border(1.dp, p.amberBorder, shape)
            .padding(vertical = 20.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VERIFICATION CODE",
            style = MaterialTheme.typography.labelSmall,
            color = p.amberHeading
        )
        Text(
            text = code,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 4.sp
            ),
            color = p.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun WaitingRow(peerName: String) {
    val p = MaterialTheme.nexus
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(p.surfaceMuted)
            .border(1.dp, p.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = p.amber,
            strokeWidth = 2.dp,
            trackColor = p.amberContainer
        )
        Text(
            text = "Waiting for $peerName to confirm…",
            style = MaterialTheme.typography.bodyMedium,
            color = p.inkMuted,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/** Success and failure results, phrased for people rather than for logs. */
@Composable
fun PairingResultDialogs(
    ui: PairingManager.Ui,
    onDismiss: () -> Unit
) {
    when (ui) {
        is PairingManager.Ui.Succeeded -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Paired") },
            text = {
                Text(
                    "${ui.peerName} is now trusted. Nexus will reconnect to it " +
                        "automatically whenever you are on the same network."
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        )

        is PairingManager.Ui.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(failureTitle(ui.message)) },
            text = { Text(failureBody(ui.message)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        )

        else -> Unit
    }
}

private fun failureTitle(message: String): String = when {
    message.contains("timed out") -> "Pairing timed out"
    message.contains("declined") -> "Pairing rejected"
    message.contains("was rejected") -> "Pairing cancelled"
    message.contains("ended before pairing") -> "Connection ended"
    message.contains("couldn't send") -> "Couldn't reach the other device"
    message.contains("before the request") -> "Couldn't reach the other device"
    message.contains("invalid identity") -> "Unexpected response"
    message.contains("never identified") -> "Unexpected response"
    else -> "Pairing failed"
}

private fun failureBody(message: String): String = when {
    message.contains("timed out") ->
        "The request took too long and expired. Make sure both devices are on " +
            "the same network, then try again."

    message.contains("declined") ->
        "The other device declined the request. Nothing was paired."

    message.contains("was rejected") ->
        "You rejected the request. Nothing was paired."

    message.contains("ended before pairing") ->
        "The connection closed before pairing finished. Try again."

    message.contains("couldn't send") || message.contains("before the request") ->
        "Nexus could not reach the other device. Check that both devices are on " +
            "the same local network, then try again."

    message.contains("invalid identity") || message.contains("never identified") ->
        "The other device sent a response Nexus could not understand. Nothing " +
            "was paired."

    // Every engine message is already product-level copy with no addresses,
    // identifiers or stack traces, so it is safe to fall back to it.
    else -> message
}
