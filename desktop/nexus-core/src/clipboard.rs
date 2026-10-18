//! Clipboard synchronization: message shape and loop prevention.
//!
//! Implements `protocol/clipboard.md`. This module is deliberately
//! platform-free: it decides *what* to send and *whether* to apply an inbound
//! update. Reading and writing the operating-system clipboard lives in the
//! platform layer (the desktop app uses `arboard`; Android uses
//! `ClipboardManager`).
//!
//! Loop prevention is the whole game here. The rules (clipboard.md
//! § "Loop prevention") are:
//!
//! 1. echo suppression — never apply an update whose hash is one we sent
//! 2. same-content guard — never re-write text already on the clipboard
//! 3. dedup — never apply the same inbound hash twice
//! 4. no relay — a received update is never forwarded to another peer
//! 5. debounce — `MIN_SEND_INTERVAL` (enforced by the caller's poll loop)

use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::protocol::Message;

/// Maximum clipboard text size in UTF-8 bytes (`protocol/clipboard.md`).
pub const MAX_CLIPBOARD_TEXT: usize = 16_384;

/// Minimum interval between outbound clipboard updates (debounce).
pub const MIN_SEND_INTERVAL: Duration = Duration::from_millis(300);

/// SHA-256 of the UTF-8 bytes of `text`, lowercase hex.
///
/// This is the `h` field of `clipboard.update` and the basis for echo/dedup.
pub fn text_hash(text: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(text.as_bytes());
    hex::encode(hasher.finalize())
}

/// The payload of a `clipboard.update` message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClipboardUpdate {
    /// Clipboard content; may be empty (means "clear clipboard").
    pub text: String,
    /// SHA-256 of the UTF-8 bytes of `text`, lowercase hex.
    pub h: String,
}

impl ClipboardUpdate {
    /// Build an update for `text`, computing its hash.
    pub fn new(text: impl Into<String>) -> Self {
        let text = text.into();
        let h = text_hash(&text);
        Self { text, h }
    }

    /// Parse a `clipboard.update` payload.
    ///
    /// Returns `None` when `text` or `h` is missing or not a string; the
    /// caller answers `system.error {code:"invalid_payload"}`.
    pub fn from_payload(payload: &Value) -> Option<Self> {
        let text = payload.get("text")?.as_str()?.to_string();
        let h = payload.get("h")?.as_str()?.to_string();
        Some(Self { text, h })
    }

    /// Whether `h` is the correct hash of `text`.
    pub fn hash_is_valid(&self) -> bool {
        self.h == text_hash(&self.text)
    }

    /// The `clipboard.update` control message carrying this payload.
    pub fn to_message(&self) -> Message {
        Message::new(
            "clipboard.update",
            serde_json::json!({ "text": self.text, "h": self.h }),
        )
    }
}

/// What the receiver should do with an inbound update.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Inbound {
    /// Write `text` to the local clipboard. After the platform write
    /// succeeds, call [`SyncState::note_applied`] with `hash`.
    Apply { text: String, hash: String },
    /// The clipboard already holds this exact text: nothing to write, but
    /// record it as applied so a replay is dropped.
    SameContent { hash: String },
    /// Drop: an echo of an update this device sent.
    Echo,
    /// Drop: already applied this hash.
    Duplicate,
    /// Protocol error: `h` is not the hash of `text`.
    InvalidHash,
    /// Protocol error: the text exceeds [`MAX_CLIPBOARD_TEXT`].
    TooLarge { bytes: usize },
}

/// What to do with a local clipboard change.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outbound {
    /// Send this update to the active peer.
    Send(ClipboardUpdate),
    /// The value was already sent or was just applied from the peer.
    Unchanged,
    /// Too large to sync ([`MAX_CLIPBOARD_TEXT`]).
    TooLarge { bytes: usize },
}

/// Loop-prevention bookkeeping for one clipboard direction.
///
/// A single instance tracks a device's whole clipboard session (all its
/// peers are direct, there is no relaying, so one pair of hashes suffices).
#[derive(Debug, Default, Clone)]
pub struct SyncState {
    last_sent_hash: Option<String>,
    last_applied_hash: Option<String>,
}

impl SyncState {
    /// A fresh state with no history.
    pub fn new() -> Self {
        Self::default()
    }

    /// The hash of the last update this device sent, if any.
    pub fn last_sent_hash(&self) -> Option<&str> {
        self.last_sent_hash.as_deref()
    }

    /// The hash of the last update this device applied, if any.
    pub fn last_applied_hash(&self) -> Option<&str> {
        self.last_applied_hash.as_deref()
    }

    /// Forget what we sent, but keep what we applied.
    ///
    /// Used when a new session becomes ready so the current clipboard is
    /// offered to the new peer, while still never echoing content that came
    /// *from* a peer back to it.
    pub fn reset_sent(&mut self) {
        self.last_sent_hash = None;
    }

    /// Decide whether a local clipboard value should be sent.
    pub fn outbound(&self, text: &str) -> Outbound {
        if text.len() > MAX_CLIPBOARD_TEXT {
            return Outbound::TooLarge { bytes: text.len() };
        }
        let update = ClipboardUpdate::new(text);
        if self.last_sent_hash.as_deref() == Some(update.h.as_str()) {
            return Outbound::Unchanged;
        }
        // We just applied this from the peer: sending it back is an echo.
        if self.last_applied_hash.as_deref() == Some(update.h.as_str()) {
            return Outbound::Unchanged;
        }
        Outbound::Send(update)
    }

    /// Record that `hash` was sent to the peer.
    pub fn note_sent(&mut self, hash: &str) {
        self.last_sent_hash = Some(hash.to_string());
    }

    /// Decide what to do with an inbound update given the current clipboard.
    pub fn inbound(&self, update: &ClipboardUpdate, local: Option<&str>) -> Inbound {
        if !update.hash_is_valid() {
            return Inbound::InvalidHash;
        }
        if update.text.len() > MAX_CLIPBOARD_TEXT {
            return Inbound::TooLarge {
                bytes: update.text.len(),
            };
        }
        if self.last_sent_hash.as_deref() == Some(update.h.as_str()) {
            return Inbound::Echo;
        }
        if self.last_applied_hash.as_deref() == Some(update.h.as_str()) {
            return Inbound::Duplicate;
        }
        if local == Some(update.text.as_str()) {
            return Inbound::SameContent {
                hash: update.h.clone(),
            };
        }
        Inbound::Apply {
            text: update.text.clone(),
            hash: update.h.clone(),
        }
    }

    /// Record that `hash` was successfully applied locally.
    pub fn note_applied(&mut self, hash: &str) {
        self.last_applied_hash = Some(hash.to_string());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_matches_known_vector() {
        // SHA-256("hello world")
        assert_eq!(
            text_hash("hello world"),
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        );
        assert_eq!(
            text_hash(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn update_round_trips_through_payload() {
        let update = ClipboardUpdate::new("hi there");
        let value = serde_json::to_value(&update).unwrap();
        let parsed = ClipboardUpdate::from_payload(&value).unwrap();
        assert_eq!(parsed, update);
        assert!(parsed.hash_is_valid());
        assert_eq!(parsed.to_message().kind, "clipboard.update");
    }

    #[test]
    fn missing_fields_are_rejected() {
        assert!(ClipboardUpdate::from_payload(&serde_json::json!({ "text": "x" })).is_none());
        assert!(ClipboardUpdate::from_payload(&serde_json::json!({ "h": "x" })).is_none());
        assert!(ClipboardUpdate::from_payload(&serde_json::json!({ "text": 1, "h": 2 })).is_none());
    }

    #[test]
    fn bad_hash_is_a_protocol_error() {
        let state = SyncState::new();
        let tampered = ClipboardUpdate {
            text: "hello".into(),
            h: "00".repeat(32),
        };
        assert!(matches!(
            state.inbound(&tampered, None),
            Inbound::InvalidHash
        ));
    }

    #[test]
    fn oversized_text_is_rejected_on_both_sides() {
        let big = "a".repeat(MAX_CLIPBOARD_TEXT + 1);
        let state = SyncState::new();
        assert!(matches!(state.outbound(&big), Outbound::TooLarge { .. }));
        let update = ClipboardUpdate::new(big);
        assert!(matches!(
            state.inbound(&update, None),
            Inbound::TooLarge { .. }
        ));
    }

    #[test]
    fn outbound_is_deduped_until_it_changes() {
        let mut state = SyncState::new();
        match state.outbound("first") {
            Outbound::Send(update) => state.note_sent(&update.h),
            other => panic!("expected send, got {other:?}"),
        }
        // Same value again: not resent.
        assert_eq!(state.outbound("first"), Outbound::Unchanged);
        assert!(matches!(state.outbound("second"), Outbound::Send(_)));
    }

    #[test]
    fn applying_then_seeing_the_same_text_does_not_echo() {
        let mut state = SyncState::new();
        let inbound = ClipboardUpdate::new("from phone");

        assert!(matches!(
            state.inbound(&inbound, Some("something else")),
            Inbound::Apply { .. }
        ));
        state.note_applied(&inbound.h);

        // The platform write fires a local change event; we must not echo it.
        assert_eq!(state.outbound("from phone"), Outbound::Unchanged);
        // And a replay of the same inbound update is dropped.
        assert_eq!(
            state.inbound(&inbound, Some("from phone")),
            Inbound::Duplicate
        );
    }

    #[test]
    fn own_content_is_never_reapplied() {
        let mut state = SyncState::new();
        let update = ClipboardUpdate::new("our own text");
        match state.outbound("our own text") {
            Outbound::Send(sent) => {
                assert_eq!(sent, update);
                state.note_sent(&sent.h);
            }
            other => panic!("expected send, got {other:?}"),
        }
        // The peer (or a misbehaving reflector) sends it back.
        assert_eq!(state.inbound(&update, Some("local")), Inbound::Echo);
    }

    #[test]
    fn same_content_is_recognised_without_writing() {
        let state = SyncState::new();
        let update = ClipboardUpdate::new("identical");
        assert_eq!(
            state.inbound(&update, Some("identical")),
            Inbound::SameContent { hash: update.h }
        );
    }

    #[test]
    fn two_device_conversation_terminates() {
        // Simulates A → B and B's local echo, proving no infinite loop.
        let mut a = SyncState::new();
        let mut b = SyncState::new();

        // A copies locally.
        let from_a = match a.outbound("from A") {
            Outbound::Send(u) => u,
            other => panic!("{other:?}"),
        };
        a.note_sent(&from_a.h);

        // B receives and applies.
        match b.inbound(&from_a, Some("B clipboard")) {
            Inbound::Apply { hash, .. } => b.note_applied(&hash),
            other => panic!("expected apply, got {other:?}"),
        }
        // B's clipboard changed to "from A": B would try to send it back.
        assert_eq!(b.outbound("from A"), Outbound::Unchanged);
        // A would ignore the echo if it arrived anyway.
        assert_eq!(a.inbound(&from_a, Some("from A")), Inbound::Echo);
    }
}
