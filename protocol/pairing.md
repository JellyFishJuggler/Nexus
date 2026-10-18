# Nexus Pairing & Secure Channel

Status: Implemented (Rust; Android pairing flow, trust store, and pinning implemented)  
Protocol version: 1

## Overview

All Nexus application data travels over TLS 1.3 with **pinned
self-signed device certificates**. There is no certificate authority, no
account system, no server. Trust is established once, by explicit user
action, and remembered.

- Rust: `rustls` + `rcgen`
- Android: `SSLSocket` + custom `X509TrustManager`

## Device identity

Each installation generates, on first run:

- An **ECDSA P-256** key pair (PKCS#8)
- A self-signed X.509 certificate for that key (CN = device_id, 10-year
  validity, EKU serverAuth + clientAuth). SKI/AKI are optional and are
  not currently emitted by either implementation; no extension is
  validated, since chain validity is deliberately never checked.
- A random device UUID (`device_id`)

Stored with user-only read permission. The private key never leaves the
device and is never transmitted.

## Connection roles

Both devices run a TLS **server** on their advertised port and may act as
TLS **client**. Either side may initiate a connection; pairing role does
not matter.

## TLS profile

| Setting | Value |
|---|---|
| Version | TLS 1.3 only (min = max) |
| Cipher | TLS 1.3 defaults (AES-128-GCM, ChaCha20-Poly1305, AES-256-GCM) |
| ALPN | `nexus/1` — MUST match, else reject |
| Server cert | Self-signed ECDSA P-256, presented always |
| Client cert | Presented always (mutual TLS) |
| SNI/hostname | Not used for verification (devices have no stable DNS names) |

Certificate **chain validity is not checked** (self-signed by design).
Authentication comes entirely from **pinning** (below) plus the **SAS
code** during pairing.

## Trust states

```text
UNTRUSTED  — peer certificate not in trust store
PAIRING    — user explicitly started pairing; TOFU pin in progress
TRUSTED    — certificate pinned after successful pairing
REJECTED   — user declined; ignore until pairing is restarted
```

### Untrusted connection (server side)

During the TLS handshake the server requests a client certificate. Any
certificate is accepted **at the TLS layer** so the application layer can
identify the peer. After the handshake:

- If the client certificate SHA-256 fingerprint is pinned in the trust
  store ⇒ `TRUSTED`, proceed to normal messaging.
- If not ⇒ the connection may only carry `pairing.*` messages. Any other
  message type MUST be rejected with `system.error` and the connection
  dropped. Also: if the server is NOT in explicit pairing mode (user
  initiated within the last 60 s), untrusted connections are dropped
  immediately after the handshake with `system.error {code:"not_paired"}`.

### Client side

The client pins the **server certificate seen during the handshake**. On
a trusted reconnection the client MUST verify the server certificate
matches the stored pin before sending anything; mismatch = abort with a
user-visible warning (possible MITM or rebuilt device — must re-pair).

## Pairing flow

Initiator = the side the user clicked "Pair" on. Responder = the other.

```text
Initiator                                    Responder
    │                                            │
    │──── TCP + TLS handshake ──────────────────▶│
    │◀─── TLS handshake (server cert) ───────────│
    │                                            │
    │     [both compute SAS from the two certs]  │
    │                                            │
    │──── pairing.request ──────────────────────▶│
    │     {device_id, name, platform, pv}        │  shows prompt:
    │                                            │  "Pair with X? Code 482 913"
    │                                            │  [Accept] [Reject]
    │◀─── pairing.accept ────────────────────────│
    │     {device_id, name, platform, pv}        │
    │  shows prompt (same SAS)                   │
    │  [Accept] [Reject]                         │
    │──── pairing.confirm ──────────────────────▶│
    │     both sides: pin peer cert, TRUSTED     │
    │◀─── pairing.complete ──────────────────────│
```

The `pairing.confirm` step is what makes the flow symmetric: each
side pins only after the *other* side's user has also accepted, so no
device trusts a peer that has not yet confirmed. Concretely:

- Responder: on `pairing.request`, ask the user. On acceptance send
  `pairing.accept`, then wait for `pairing.confirm`; on receipt, pin the
  peer certificate and send `pairing.complete`.
- Initiator: on `pairing.accept`, ask the user. On acceptance send
  `pairing.confirm`, then wait for `pairing.complete`; on receipt, pin
  the peer certificate.

- `pairing.reject` from either side ends the connection; the rejecting
  side marks the peer `REJECTED` (no re-nag until pairing is explicitly
  restarted).
- Both sides display the SAS code **before** either accepts. The user
  compares the two codes visually. Mismatch ⇒ reject.
- Timeout: if `pairing.accept`/`pairing.reject` does not arrive within
  30 s, abort and clean up.

## SAS verification code

The Short Authentication String binds the pairing to the exact two
certificates on the wire (defeats an active MITM that would otherwise
present its own certs to each side).

```text
certs = [cert_initiator_der, cert_responder_der]   # sorted by raw bytes
h = SHA-256( certs[0] || certs[1] )
code = ( first 4 bytes of h as BE u32 ) % 1_000_000
display as 6 digits, zero-padded: "0482913" → shown as "048 291"
```

Both devices derive the same code from the same two certificates. An
attacker terminating TLS on both sides presents two different cert pairs
⇒ different codes on the two screens ⇒ user rejects.

Display format (both platforms):

```text
Verification code

   048 291

Confirm this matches the other device.
```

## Message types

### `pairing.request` (initiator → responder)

```json
{
  "type": "pairing.request",
  "payload": {
    "device_id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Srijan's Laptop",
    "platform": "windows",
    "pv": 1
  }
}
```

### `pairing.accept` (responder → initiator)

Same payload shape as `pairing.request`. Carries the responder's identity
so the initiator can display it.

### `pairing.confirm` (initiator → responder)

```json
{ "type": "pairing.confirm", "payload": {} }
```

Sent by the initiator once its user has accepted. The responder then pins
the peer certificate and replies with `pairing.complete`.

### `pairing.reject` (either direction)

```json
{ "type": "pairing.reject", "payload": {} }
```

### `pairing.complete` (both directions)

```json
{ "type": "pairing.complete", "payload": {} }
```

Sent after both sides have persisted the pin. The connection may then
continue as a normal trusted connection (no reconnect needed).

## Trust store

Persisted per installation, user-only permission:

```json
{
  "version": 1,
  "devices": [
    {
      "device_id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Srijan's Phone",
      "platform": "android",
      "cert_sha256": "hex…",
      "cert_pem": "-----BEGIN CERTIFICATE-----…",
      "paired_at": 1792260000
    }
  ]
}
```

The full certificate is stored (not just the hash) so reconnections can
present a complete pin match and future protocol versions can re-validate
extensions.

## Unpairing

Removing a trusted device deletes its entry. The next connection from
that device hits the `UNTRUSTED` path above: dropped unless the user
explicitly starts pairing again. Both sides SHOULD be unpaired by the
user for a clean break (the other device still holds our pin until it
also removes us; the connection will fail on our side regardless).

## Security requirements checklist

- [x] Private keys never transmitted, stored user-only
- [x] No trust without explicit user confirmation on BOTH sides
- [x] SAS binds certificates against active MITM
- [x] Reconnection requires exact pinned certificate (no TOFU re-pinning)
- [x] Untrusted peers cannot send application messages
- [x] Pairing mode expires (60 s) — a forgotten open prompt is not a hole
- [x] All application bytes inside TLS 1.3
