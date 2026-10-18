# Nexus Connection Management

Status: Implemented (Rust)  
Protocol version: 1

## Overview

After pairing.md's TLS channel is established, peers exchange
`system.*` control messages to negotiate the session and keep it alive.

## Connection states

```text
DISCOVERED    mDNS saw the device
    │  user action / autoconnect
    ▼
CONNECTING    TCP + TLS in progress
    │
    ├─▶ PAIRING          untrusted peer, user pairing mode (pairing.md)
    │
    ▼
CONNECTED     hello exchanged, trusted, application-ready
    │  heartbeat timeout / TCP error / user disconnect
    ▼
DISCONNECTED  waiting; reconnection backoff running
    │
    └─▶ CONNECTING   (auto-reconnect, see below)
```

## Session start: `system.hello`

Immediately after the TLS handshake (and after pairing, for trusted
peers), **both sides** send `system.hello` concurrently:

```json
{
  "type": "system.hello",
  "payload": {
    "device_id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Srijan's Phone",
    "platform": "android",
    "pv": 1,
    "features": ["clipboard", "transfer", "media"]
  }
}
```

Rules:

- `pv` mismatch where no common version exists ⇒ reply
  `system.incompatible`, then close gracefully (no panic, no retry loop
  against an incompatible peer).
- `device_id` MUST match the certificate identity used at pairing time.
  If a trusted connection's hello carries a different `device_id` than
  the pinned entry ⇒ close and WARN (identity tampering).
- The connection is not "application-ready" until the local side has both
  sent and received hello.

## Heartbeat

| Parameter | Value |
|---|---|
| Interval | 15 s |
| Timeout | 60 s without any inbound frame ⇒ stale |
| Miss limit | 3 consecutive unanswered pings ⇒ stale |

```json
{ "type": "system.ping", "payload": { "ts": 1792260000 } }
{ "type": "system.pong", "payload": { "ts": 1792260000 } }
```

Any inbound frame counts as liveness; pongs are only sent for pings
(current clock in `ts`).

## Reconnection

Automatic, no user action:

| Attempt | Delay |
|---|---|
| 1 | 1 s |
| 2 | 2 s |
| 3 | 4 s |
| … doubling … |
| cap | 60 s |

- The peer address is **re-resolved via mDNS** on every attempt. Never
  cache IP addresses across disconnects.
- Reconnect only applies to TRUSTED devices. Untrusted devices are never
  auto-connected.
- Network interface changes trigger immediate re-resolve + re-announce.

## `system.error`

```json
{
  "type": "system.error",
  "payload": { "code": "unknown_type", "message": "…" }
}
```

Codes: `unknown_type`, `not_paired`, `incompatible`, `busy`,
`payload_too_large`, `invalid_payload`. Messages shown to users MUST be
human-readable (error-handling style per SRS §40).

## Ordering & concurrency

- Control messages on one connection are delivered in send order (single
  TCP stream; no out-of-order possibility).
- File DATA frames interleave freely with control messages (transfer.md).
- One active connection per peer. A second simultaneous TCP connection
  from the same trusted device: the older connection wins, the newer is
  closed with `system.error {code:"busy"}` (breaks reconnect races).
