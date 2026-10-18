package dev.nexus.local.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clipboard loop-prevention rules — `protocol/clipboard.md`.
 *
 * Mirrors the Rust suite in `nexus-core/src/clipboard.rs` one-for-one: the two
 * implementations interoperate, so their decisions must agree. Hashes are
 * byte-identical (SHA-256 of the UTF-8 text, lowercase hex).
 */
class ClipboardProtocolTest {

    @Test
    fun hashMatchesKnownVector() {
        // SHA-256("hello world")
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            ClipboardProtocol.textHash("hello world")
        )
        // SHA-256("")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ClipboardProtocol.textHash("")
        )
    }

    @Test
    fun badHashIsAProtocolError() {
        val state = ClipboardProtocol.SyncState()
        val tampered = "00".repeat(32)
        assertTrue(state.inbound("hello", tampered, null) is ClipboardProtocol.Inbound.InvalidHash)
    }

    @Test
    fun oversizedTextIsRejectedOnBothSides() {
        val big = "a".repeat(ClipboardProtocol.MAX_CLIPBOARD_TEXT + 1)
        val state = ClipboardProtocol.SyncState()
        assertTrue(state.outbound(big) is ClipboardProtocol.Outbound.TooLarge)
        assertTrue(
            state.inbound(big, ClipboardProtocol.textHash(big), null)
                is ClipboardProtocol.Inbound.TooLarge
        )
    }

    @Test
    fun outboundIsDedupedUntilItChanges() {
        val state = ClipboardProtocol.SyncState()
        val first = state.outbound("first")
        assertTrue(first is ClipboardProtocol.Outbound.Send)
        state.noteSent((first as ClipboardProtocol.Outbound.Send).hash)

        // Same value again: not resent.
        assertEquals(ClipboardProtocol.Outbound.Unchanged, state.outbound("first"))
        assertTrue(state.outbound("second") is ClipboardProtocol.Outbound.Send)
    }

    @Test
    fun applyingThenSeeingTheSameTextDoesNotEcho() {
        val state = ClipboardProtocol.SyncState()
        val inbound = "from phone"
        val hash = ClipboardProtocol.textHash(inbound)

        assertTrue(state.inbound(inbound, hash, "something else") is ClipboardProtocol.Inbound.Apply)
        state.noteApplied(hash)

        // The platform write fires a local change event; we must not echo it.
        assertEquals(ClipboardProtocol.Outbound.Unchanged, state.outbound(inbound))
        // A replay of the same inbound update is dropped.
        assertEquals(
            ClipboardProtocol.Inbound.Duplicate,
            state.inbound(inbound, hash, "from phone")
        )
    }

    @Test
    fun ownContentIsNeverReapplied() {
        val state = ClipboardProtocol.SyncState()
        val sent = state.outbound("our own text") as ClipboardProtocol.Outbound.Send
        state.noteSent(sent.hash)

        // The peer (or a misbehaving reflector) sends it back.
        assertEquals(
            ClipboardProtocol.Inbound.Echo,
            state.inbound("our own text", sent.hash, "local")
        )
    }

    @Test
    fun sameContentIsRecognisedWithoutWriting() {
        val state = ClipboardProtocol.SyncState()
        val text = "identical"
        assertEquals(
            ClipboardProtocol.Inbound.SameContent(ClipboardProtocol.textHash(text)),
            state.inbound(text, ClipboardProtocol.textHash(text), text)
        )
    }

    @Test
    fun twoDeviceConversationTerminates() {
        // Simulates A → B and B's local echo, proving no infinite loop.
        val a = ClipboardProtocol.SyncState()
        val b = ClipboardProtocol.SyncState()

        // A copies locally.
        val fromA = a.outbound("from A") as ClipboardProtocol.Outbound.Send
        a.noteSent(fromA.hash)

        // B receives and applies.
        val applied = b.inbound(fromA.text, fromA.hash, "B clipboard")
        assertTrue(applied is ClipboardProtocol.Inbound.Apply)
        b.noteApplied((applied as ClipboardProtocol.Inbound.Apply).hash)

        // B's clipboard changed to "from A": B would try to send it back.
        assertEquals(ClipboardProtocol.Outbound.Unchanged, b.outbound("from A"))
        // A would ignore the echo if it arrived anyway.
        assertEquals(
            ClipboardProtocol.Inbound.Echo,
            a.inbound(fromA.text, fromA.hash, "from A")
        )
    }

    @Test
    fun resetSentOffersTheValueAgainButStillBlocksEchoes() {
        val state = ClipboardProtocol.SyncState()
        val sent = state.outbound("current") as ClipboardProtocol.Outbound.Send
        state.noteSent(sent.hash)
        assertEquals(ClipboardProtocol.Outbound.Unchanged, state.outbound("current"))

        // A new session: the value is offered to the new peer…
        state.resetSent()
        assertTrue(state.outbound("current") is ClipboardProtocol.Outbound.Send)

        // …but content we received from a peer is still never echoed back.
        state.noteApplied(ClipboardProtocol.textHash("received"))
        state.resetSent()
        assertEquals(ClipboardProtocol.Outbound.Unchanged, state.outbound("received"))
    }
}
