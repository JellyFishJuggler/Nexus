# Nexus Framing

Status: Implemented (Rust; Android frame codec + envelope + session loop implemented)  
Protocol version: 1

## Transport

Nexus uses a raw TCP stream. TCP is a byte stream: messages are not
delimited by the transport, so Nexus applies its own framing on top.

## Byte order

All multi-byte integer fields are encoded **big-endian**.

## Frame layout

Every frame on the wire:

```text
┌────────────┬───────────┬──────────────────────┐
│ length     │ frame_type│ body                 │
│ u32 (BE)   │ u8        │ [length - 1] bytes   │
└────────────┴───────────┴──────────────────────┘
```

- `length` — length of `frame_type` + `body`, i.e. the number of bytes
  that follow the length field itself.
- Minimum valid `length`: `1` (a frame with a type and empty body).
- Maximum valid `length`: `MAX_FRAME_LEN` (see Limits).

`length` does **not** include the 4 bytes of the length field.

## Frame types

| Value | Name | Body |
|---|---|---|
| `0x01` | `CONTROL` | UTF-8 JSON message (see Envelope) |
| `0x02` | `DATA` | Binary, see transfer.md |
| other | — | **Rejected.** Close the connection. |

## TLS placement

All frames described here travel **inside** the TLS channel. The TLS
handshake itself is raw TLS records, not Nexus frames. There is no
unencrypted Nexus framing phase.

```text
TCP connect
   │
   ▼
TLS 1.3 handshake  (certificates, pinned — see pairing.md)
   │
   ▼
Nexus frames       (length prefix + type + body)
```

## Limits

| Constant | Value | Rationale |
|---|---|---|
| `MAX_FRAME_LEN` | 1 MiB (`1_048_576`) | Bounds memory per frame; 64 KiB file chunks + JSON overhead fit easily |
| `MAX_CONTROL_BODY` | 64 KiB | Control messages are small; anything larger is malformed |
| `READ_TIMEOUT` | 60 s | No frame (including heartbeat traffic) for 60 s ⇒ stale connection, drop it |

A frame declaring `length > MAX_FRAME_LEN` MUST cause an immediate
connection close without reading the body (the sender is malformed or
hostile; do not allocate based on its claim).

## Envelope

Control frame bodies are JSON objects with this shape:

```json
{
  "v": 1,
  "type": "system.hello",
  "id": "01935c8e-6d1f-7a2b-9c8e-2f6a1b3c4d5e",
  "ts": 1792260000,
  "payload": {}
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `v` | integer | yes | Protocol version of the sender. MUST equal the negotiated version. |
| `type` | string | yes | Dotted message type, `[a-z]+.[a-z_]+` |
| `id` | string (UUID) | yes | Unique per message; used for correlation and deduplication |
| `ts` | integer | yes | Unix time, seconds, sender clock |
| `payload` | object | yes | Type-specific; `{}` when empty |

Unknown `type` values MUST be rejected with a `system.error` reply
(`code: "unknown_type"`) and logged at DEBUG. The connection stays open.

Oversized payloads (> `MAX_CONTROL_BODY`) MUST be rejected by closing the
connection — the peer violates the spec.

## Message type namespaces

```text
system.*      connection lifecycle (hello, ping, pong, error, incompatible)
pairing.*     pairing handshake (see pairing.md)
clipboard.*   clipboard sync (see clipboard.md)
file.*        file transfer control (see transfer.md)
media.*       media control (see media.md)
```

## DATA frames

`DATA` frames carry binary file-chunk payloads. Layout:

```text
┌──────────────┬────────────────────┐
│ transfer_id  │ chunk              │
│ 16 bytes     │ [rest] bytes       │
│ (UUID bytes) │                    │
└──────────────┴────────────────────┘
```

- `transfer_id` is the UUID (RFC 4122 byte order, 16 bytes) of the
  transfer the chunk belongs to, correlating with the preceding
  `file.request` / `file.metadata` control messages.
- The chunk is the raw file bytes — no base64, no JSON wrapping.
- Chunk size MUST NOT exceed 64 KiB (`65_536` bytes).

DATA frames MUST NOT appear before the transfer they reference has been
accepted. An unknown `transfer_id` MUST be rejected (close connection —
a peer sending unrequested binary data is misbehaving).

## Sequence example

```text
A → B   [CONTROL] { "type": "system.hello", ... }
B → A   [CONTROL] { "type": "system.hello", ... }
A → B   [CONTROL] { "type": "system.ping", "payload": { "ts": 1792260000 } }
B → A   [CONTROL] { "type": "system.pong", "payload": { "ts": 1792260000 } }
```
