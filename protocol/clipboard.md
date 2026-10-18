# Nexus Clipboard Sync

Status: Implemented (Rust `nexus-core` + Tauri desktop; Android foreground-only)  
Protocol version: 1

## Overview

Two-way plain-text clipboard synchronization between paired devices over an
established session (connection.md). Feature name: `clipboard`.

Text only. Rich formats, images, and files are out of scope for v0.1.

## Constraints

| Constant | Value | Rationale |
|---|---|---|
| `MAX_CLIPBOARD_TEXT` | 16 KiB (16_384 bytes, UTF-8) | Keeps the JSON-escaped control body comfortably inside `MAX_CONTROL_BODY`; clipboard payloads are ephemeral text |
| `MIN_SEND_INTERVAL` | 300 ms | Debounces clipboard-manager event storms; later changes coalesce into one update |

- Encoding is UTF-8 (JSON strings carry it natively).
- Sync is **best-effort and ephemeral**: there is no queueing across
  reconnects. If no session is active when the local clipboard changes,
  the change is dropped (the next change propagates normally).
- If the encoded control body would still exceed `MAX_CONTROL_BODY`
  (pathological escaping), the sender MUST truncate the text to fit.

## Message: `clipboard.update`

Sent whenever the sender's local clipboard changes.

```json
{
  "type": "clipboard.update",
  "payload": {
    "text": "the clipboard content",
    "h": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
  }
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `text` | string | yes | Clipboard content; may be empty (means "clear clipboard") |
| `h` | string (hex) | yes | SHA-256 of the UTF-8 bytes of `text`; used for echo/dedup |

## Loop prevention

Clipboard sync is prone to infinite loops (A sets its clipboard → sends →
B applies → B's clipboard event fires → sends back → …). Implementations
MUST apply all of the following:

1. **Echo suppression (receiver):** drop an incoming update when `h` equals
   the hash of the receiver's *current* local clipboard or of the last
   update the receiver itself sent (`last_sent_hash`). Never re-apply an
   echo of your own content.
2. **Same-content guard (receiver):** if the incoming text is identical to
   the current local clipboard, do not write (writing would fire a local
   change event).
3. **Dedup (receiver):** drop an update whose `h` equals the last applied
   hash (`last_applied_hash`) — protects against replay/duplication.
4. **No relay:** v0.1 is direct device-to-device only. A received
   `clipboard.update` MUST NOT be forwarded to any other peer.
5. **Debounce (sender):** enforce `MIN_SEND_INTERVAL`; coalesce rapid local
   changes into the latest text.

There is no acknowledgment message in v0.1: delivery is in-order per
connection or the change is lost with the connection (acceptable for
ephemeral clipboard state).

## Application order

On applying `clipboard.update`, the receiver sets
`last_applied_hash = h` **after** the platform write reports success. If
the platform write fails (see Platform limits), the message is dropped and
logged at DEBUG; `last_applied_hash` is unchanged.

## Platform limits

Implementations MUST respect operating-system clipboard restrictions and
MUST NOT attempt to bypass them:

- **Android 10+ (API 29+):** reading the clipboard is only allowed while
  the app has input focus (or is the default IME); writes are allowed
  regardless of focus. Nexus therefore treats Android clipboard sync as an
  explicitly **foreground-only** feature: it reads on resume, on the
  framework's clip-changed callback (delivered only to the focused app),
  and on a slow poll, and it applies remote updates (writes) even while
  backgrounded. Consequence: a copy made on the desktop reaches the phone
  immediately, but a copy made *on the phone* is only picked up and sent
  while Nexus is open and focused — background clipboard capture is a known
  prototype limitation, not worked around (no AccessibilityService, root,
  or hidden APIs).
- **Windows:** clipboard access is allowed; use delayed rendering /
  open-clipboard failure handling (retry, never crash).
- **Linux X11:** primary selection and clipboard are separate; Nexus syncs
  the CLIPBOARD selection only (best-effort on Wayland, see SRS §platform).

When a platform cannot read or write the clipboard in the current state,
the direction degrades (that side simply does not propagate) and the
limitation is reported to the user — sync silently stops working, it never
errors the connection.

## Session start (initial offer)

When a session becomes ready (connection.md `system.hello` exchange
complete), both sides offer their current clipboard to the peer once. To
make that first offer deterministic, a sender clears its `last_sent_hash`
at session start (Rust `SyncState::reset_sent`, Android
`SyncState.resetSent`) while keeping `last_applied_hash`. This means:

- the current clipboard is sent to each newly connected peer, rather than
  being suppressed because the same value was already sent to a previous
  session; and
- content that arrived *from* a peer is still never echoed back.

## Errors

| Condition | Behavior |
|---|---|
| `text` longer than `MAX_CLIPBOARD_TEXT` (after receipt) | `system.error {code: "payload_too_large"}`, message dropped, connection stays open |
| Missing/invalid `h` | `system.error {code: "invalid_payload"}`, message dropped |
| Unknown field in payload | Ignore (forward-compatible) |

## Sequence

```text
A (local clipboard changes)
A → B   clipboard.update { text, h }
B       check: h ≠ own clipboard hash, h ≠ last_sent_hash, h ≠ last_applied_hash
B       write clipboard (platform permitting), last_applied_hash = h

B (local clipboard changes)
B → A   clipboard.update { text, h }
A       same checks → apply. No echo back to B.
```
