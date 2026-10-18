package dev.nexus.local.clipboard

import java.security.MessageDigest

/**
 * Clipboard synchronization rules — `protocol/clipboard.md`, Android side.
 *
 * Deliberately platform-free (no Android imports) so the loop-prevention
 * rules can be unit-tested on the JVM and so they stay byte-identical to
 * `nexus-core`'s `clipboard.rs`. Reading/writing the system clipboard lives in
 * [ClipboardSync].
 *
 * Loop prevention (clipboard.md § "Loop prevention"):
 *  1. echo suppression — never apply an update whose hash is one we sent
 *  2. same-content guard — never re-write text already on the clipboard
 *  3. dedup — never apply the same inbound hash twice
 *  4. no relay — a received update is never forwarded to another peer
 *  5. debounce — [MIN_SEND_INTERVAL_MS] (enforced by the caller's poll loop)
 */
object ClipboardProtocol {

    /** Maximum clipboard text size in UTF-8 bytes (clipboard.md). */
    const val MAX_CLIPBOARD_TEXT = 16_384

    /** Minimum interval between outbound clipboard updates (debounce). */
    const val MIN_SEND_INTERVAL_MS = 300L

    /** SHA-256 of the UTF-8 bytes of [text], lowercase hex (`h` field). */
    fun textHash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** UTF-8 byte length, which is what the protocol limit applies to. */
    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** What the receiver should do with an inbound update. */
    sealed interface Inbound {
        /** Write [text] locally, then record [hash] via `noteApplied`. */
        data class Apply(val text: String, val hash: String) : Inbound

        /** The clipboard already holds this exact text; record [hash]. */
        data class SameContent(val hash: String) : Inbound

        /** Drop: an echo of an update this device sent. */
        data object Echo : Inbound

        /** Drop: already applied this hash. */
        data object Duplicate : Inbound

        /** Protocol error: `h` is not the hash of `text`. */
        data object InvalidHash : Inbound

        /** Protocol error: the text exceeds [MAX_CLIPBOARD_TEXT]. */
        data class TooLarge(val bytes: Int) : Inbound
    }

    /** What to do with a local clipboard change. */
    sealed interface Outbound {
        data class Send(val text: String, val hash: String) : Outbound
        data object Unchanged : Outbound
        data class TooLarge(val bytes: Int) : Outbound
    }

    /** Loop-prevention bookkeeping for one clipboard session. */
    class SyncState {
        private var lastSentHash: String? = null
        private var lastAppliedHash: String? = null

        val lastSent: String? get() = lastSentHash
        val lastApplied: String? get() = lastAppliedHash

        /**
         * Forget what we sent, but keep what we applied.
         *
         * Used when a new session becomes ready so the current clipboard is
         * offered to the new peer, while still never echoing content that
         * came *from* a peer back to it.
         */
        fun resetSent() {
            lastSentHash = null
        }

        /** Decide whether a local clipboard value should be sent. */
        fun outbound(text: String): Outbound {
            val bytes = utf8Length(text)
            if (bytes > MAX_CLIPBOARD_TEXT) return Outbound.TooLarge(bytes)
            val hash = textHash(text)
            if (hash == lastSentHash) return Outbound.Unchanged
            // We just applied this from a peer: sending it back is an echo.
            if (hash == lastAppliedHash) return Outbound.Unchanged
            return Outbound.Send(text, hash)
        }

        fun noteSent(hash: String) {
            lastSentHash = hash
        }

        /** Decide what to do with an inbound update given the local clipboard. */
        fun inbound(text: String, hash: String, local: String?): Inbound {
            if (textHash(text) != hash) return Inbound.InvalidHash
            val bytes = utf8Length(text)
            if (bytes > MAX_CLIPBOARD_TEXT) return Inbound.TooLarge(bytes)
            if (hash == lastSentHash) return Inbound.Echo
            if (hash == lastAppliedHash) return Inbound.Duplicate
            if (local != null && local == text) return Inbound.SameContent(hash)
            return Inbound.Apply(text, hash)
        }

        fun noteApplied(hash: String) {
            lastAppliedHash = hash
        }
    }
}
