package dev.nexus.local.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the security invariant of `protocol/pairing.md`:
 *
 * ```text
 * incoming pairing request → Pairing Request UI → wait →
 * user taps Accept → pairing.accept() → pairing.confirm →
 * trust persisted → connected
 * ```
 *
 * An incoming `pairing.request` must **never** reach `pairing.accept` on its
 * own. The only automatic decision is the DEBUG-gated adb harness, and it may
 * engage only once a code has been configured (`PairingManager.autoAcceptCode`,
 * wired from `MainActivity`'s harness handler). Nothing can configure that code
 * in a release build, so with no code the engine must ask the user.
 *
 * [autoPairingDecision] is the single gate between an incoming request and
 * `pairing.accept`, so pinning its truth table here pins the invariant.
 */
class PairingAutoDecisionTest {

    private val displayed = "700 398"

    @Test
    fun incomingRequestWithNoConfiguredCodeAsksTheUser() {
        // No configured code is the release build: the prompt must be shown and
        // the engine must wait.
        assertEquals(AutoPairingDecision.AskUser, autoPairingDecision(displayed, null))
    }

    @Test
    fun incomingRequestWithNoConfiguredCodeNeverAcceptsOrRejects() {
        for (code in listOf("", "000 000", displayed, "999 999")) {
            assertEquals(
                "no configured code must always fall through to the user (code=$code)",
                AutoPairingDecision.AskUser,
                autoPairingDecision(code, null)
            )
        }
    }

    @Test
    fun aMatchingConfiguredCodeIsTheOnlyWayToAccept() {
        assertEquals(AutoPairingDecision.Accept, autoPairingDecision(displayed, displayed))
    }

    @Test
    fun aMismatchedConfiguredCodeRejectsRatherThanAccepts() {
        assertEquals(AutoPairingDecision.Reject, autoPairingDecision(displayed, "000 000"))
    }
}
