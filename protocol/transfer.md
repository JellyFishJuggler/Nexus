# Nexus File Transfer

Status: Draft (not yet implemented)  
Protocol version: 1

## Overview

Chunked file transfer between paired devices over an established session
(connection.md). Feature name: `transfer`.

Control messages (`file.*`) negotiate the transfer; raw bytes stream in
`DATA` frames (layout defined in framing.md).

## Constants

| Constant | Value | Rationale |
|---|---|---|
| Chunk size | 64 KiB (`65_536` bytes) max | Per framing.md DATA rule |
| `PROGRESS_INTERVAL` | every 1 MiB **or** 1 s, whichever is later | Bounds control chatter |
| Active transfers | 1 outgoing + 1 incoming per connection (v0.1) | Keeps backpressure and UI simple |

- Files stream to disk; a receiver MUST NOT buffer the whole file in
  memory. The sender MUST await TCP writability per chunk (no unbounded
  send buffering).
- Transfers do **not** resume after a disconnect in v0.1: a lost session
  cancels both directions; the receiver deletes its partial file.
- There is no application-level retransmission — TCP provides it.

## Transfer lifecycle

```text
sender                         receiver
  │  file.offer {id, name, size, sha256}
  ├────────────────────────────────────▶
  │                file.accept {id}  ──▶ receive mode, create .part file
  │  ◀────────────────────────────────┤
  │  DATA {id, chunk} × N             ──▶ stream to disk, running SHA-256
  │  (file.progress {id, received})  ◀── periodic, UI only
  │  DATA last chunk
  │                file.complete {id, sha256} ──▶ verify hash, rename .part
  │  ◀────────────────────────────────┤
```

Rejection path: `file.reject {id, code}` instead of `file.accept`.
Cancellation: `file.cancel {id, code}` from either side at any time after
`file.offer`.

## Messages

### `file.offer`

```json
{
  "type": "file.offer",
  "payload": {
    "transfer_id": "01935c8e-6d1f-7a2b-9c8e-2f6a1b3c4d5e",
    "name": "report.pdf",
    "size": 245760,
    "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}
```

| Field | Type | Notes |
|---|---|---|
| `transfer_id` | string (UUID) | Unique per transfer; the same value prefixes every DATA frame (16 raw bytes) |
| `name` | string | Original file name, 1–255 bytes after sanitization (see Rules) |
| `size` | integer | Exact byte count that will follow; `0 < size ≤ 2^63-1` |
| `sha256` | string (hex, 64 chars) | SHA-256 over the entire file, computed before sending |

### `file.accept`

`payload: { "transfer_id": "…" }` — receiver is ready; DATA frames for this
transfer MAY start immediately after this message is sent.

### `file.reject`

`payload: { "transfer_id": "…", "code": "…" }`

Codes: `busy` (an active transfer occupies the slot), `declined` (user or
policy refusal), `too_large` (exceeds receiver policy), `name_invalid`.

### `file.progress`

`payload: { "transfer_id": "…", "received": 1048576 }` — sender-side UI
only; never used for flow control. MUST NOT be sent more often than
`PROGRESS_INTERVAL`.

### `file.complete`

`payload: { "transfer_id": "…", "sha256": "…" }` — sent by the
**receiver** after the file is fully written, the byte count equals
`size`, and the local SHA-256 matches the offer. The receiver renames
`.part` → final name **before** sending this.

### `file.cancel`

`payload: { "transfer_id": "…", "code": "…", "message": "…" }` — codes:
`cancelled` (user action), `integrity` (hash/size mismatch), `io` (local
disk error), `disconnected` (implied on session loss; no message sent).
The receiver of a cancel deletes its `.part` file and stops sending.

## Rules

1. **DATA before accept is forbidden** (framing.md). A DATA frame with an
   unknown or not-yet-accepted `transfer_id` MUST close the connection.
2. **Byte accounting:** the receiver counts bytes; receiving `size + 1`
   bytes ⇒ cancel with `integrity`. It MUST NOT trust `size` for
   pre-allocation — everything streams.
3. **Integrity:** SHA-256 is computed by the sender before `file.offer`
   and verified by the receiver against the received bytes. A mismatch ⇒
   receiver deletes the `.part` file and sends `file.cancel
   {code: "integrity"}`. Never deliver an unverified file.
4. **Name sanitization (receiver):** take the base name only (strip
   directories, drive letters, `..`, path separators), strip control
   characters and trailing dots/spaces, reject empty/`.`/`..` with
   `file.reject {code: "name_invalid"}`. On name collision in the
   destination directory, append ` (1)`, ` (2)`, … before the extension.
5. **Partial files:** written as `.<name>.part` in the destination
   directory; renamed only after verification (rule 3). Stale `.part`
   files from crashed/cancelled transfers MUST be cleaned up eventually.
6. **Ordering:** DATA frames interleave freely with control frames
   (connection.md); control messages for a transfer remain in order.
7. **Policy:** the receiver MAY reject before accept with `too_large`
   (configurable size policy) or `busy`. Rejecting is not an error.
8. **Trust:** transfers only occur on paired, TLS-authenticated sessions;
   no separate authentication exists for this feature.

## Errors

| Condition | Behavior |
|---|---|
| Unknown `file.*` message type | `system.error {code: "unknown_type"}`, connection stays open |
| Missing/invalid fields in a `file.*` message | `system.error {code: "invalid_payload"}`, message dropped |
| DATA frame for unknown/unaccepted `transfer_id` | Close connection (framing.md) |
| Offer while both slots busy | `file.reject {code: "busy"}` |

## Sequence (happy path)

```text
A → B   file.offer    { transfer_id, name: "photo.jpg", size: 48123, sha256 }
B → A   file.accept   { transfer_id }
A → B   DATA × 1      { transfer_id, 64 KiB }
A → B   DATA × 1      { transfer_id, remaining }
B → A   file.progress { transfer_id, received: 48123 }
B → A   file.complete { transfer_id, sha256 }   (verified + renamed)
```
