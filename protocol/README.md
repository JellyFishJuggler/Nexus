# Nexus Protocol

This directory is the source of truth for the Nexus wire protocol. Every
feature is documented here before it is implemented. Implementations in
`desktop/nexus-core` (Rust) and `android/` (Kotlin) MUST conform to these
documents.

## Documents

| Document | Status | Covers |
|---|---|---|
| [framing.md](framing.md) | Implemented (Rust) | Byte-level framing, frame types, limits |
| [discovery.md](discovery.md) | Implemented (Rust) | mDNS service advertisement and browsing |
| [pairing.md](pairing.md) | Implemented (Rust) | TLS channel, trust establishment, verification code |
| [connection.md](connection.md) | Implemented (Rust) | Hello/version negotiation, heartbeat, reconnection |
| [clipboard.md](clipboard.md) | Draft | Two-way clipboard sync, loop prevention |
| [notifications.md](notifications.md) | Implemented (Android sender, desktop receiver) | Phone → computer notification mirroring, privacy filters |
| [transfer.md](transfer.md) | Draft | Chunked file transfer, integrity |
| [media.md](media.md) | Draft | Remote media control |

## Protocol version

`protocol_version: 1`

Every discovery announcement and every new connection carries this value.
A device that receives an unsupported version MUST fail gracefully with a
human-readable incompatibility message (never crash, never silently drop).

## Layering

```text
Application messages (JSON, per feature docs)
        │
Envelope (message type, id, timestamp)
        │
Frame codec (4-byte length prefix + 1-byte frame type)
        │
TLS 1.3 (pinned self-signed device certificates)
        │
TCP (local network)
```

Discovery (mDNS) is out-of-band: it uses no TLS and carries no sensitive
information.

## Design rules

1. No custom cryptography. TLS 1.3 via vetted libraries only.
2. Discovery does not imply trust. Only explicitly paired devices may
   exchange application data.
3. Every control message MUST have a known `type`. Unknown types are
   rejected (and logged at DEBUG), never crash the connection.
4. Every message MUST be size-bounded before allocation. See framing.md
   for limits.
5. Plaintext on the wire is limited to the mDNS announcement and the TLS
   handshake. All application bytes are inside TLS.
