# Nexus Discovery (mDNS)

Status: Implemented (Rust)  
Protocol version: 1

## Overview

Nexus devices find each other on the local network using mDNS/Bonjour
(RFC 6762). No subnet scanning, no servers, no internet.

- Rust: `mdns-sd` crate
- Android: `NsdManager`

## Service type

```text
_nexus._tcp.local.
```

## Advertisement

A device announces itself with one SRV record (host + TCP port) and the
following TXT records:

| Key | Example | Required | Notes |
|---|---|---|---|
| `pv` | `1` | yes | Protocol version. Integer, no prefix. |
| `id` | `550e8400-e29b-41d4-a716-446655440000` | yes | Persistent device_id (UUID, lowercase) |
| `nm` | `Srijan's Laptop` | yes | Human-readable device name. UTF-8, ≤ 64 bytes, no control characters. |
| `pf` | `windows` | yes | One of: `windows`, `linux`, `android`, `macos` |

The TCP `port` is the Nexus TLS listener port (see pairing.md). It is
carried in the SRV record, not duplicated in TXT.

### Example announcement

```text
Service:  Srijan's Laptop._nexus._tcp.local.
SRV:      myhost.local.:48231
TXT:      pv=1, id=550e8400-..., nm=Srijan's Laptop, pf=windows
```

## What MUST NOT be announced

- IP addresses in TXT (SRV covers it)
- Usernames, account names, emails
- File names, clipboard content
- Certificate fingerprints or any credential material
- Pairing state, trusted-device lists

Discovery packets are unauthenticated and visible to anyone on the LAN.
Treat them as public.

## Browsing

A browsing device:

1. Browses for `_nexus._tcp.local.`
2. On `ServiceFound`: resolves SRV/A/AAAA/TXT, emits `DeviceFound`
3. On record change: re-resolves, emits `DeviceUpdated`
4. On `ServiceLost` or TTL expiry: emits `DeviceLost`

Implementations MUST rate-limit re-resolution bursts (mDNS answers often
arrive as several packet callbacks for one device).

## Duplicate device_id

If two advertisements carry the same `id`, keep the most recent and log a
WARNING. This indicates a cloned config directory; the user must resolve
it manually. Do not crash.

## TTL / expiry

Default mDNS TTLs apply. A device that disappears without saying goodbye
should vanish from peer lists within ~75 seconds of its last announcement.
Implementations SHOULD re-announce periodically (at least every 60 s, plus
on network-interface change).

## Version gating

Devices with `pv` greater than the local supported version are still
listed, but marked **incompatible** in UI and MUST NOT be offered pairing.
Pairing with an older `pv` is allowed only if the connection-level
negotiation (connection.md) succeeds.

## Security note

mDNS provides zero authentication. Anyone can advertise `_nexus._tcp`.
Being discovered grants nothing — see pairing.md. A discovered device
cannot exchange application data until it completes pairing and holds a
trusted certificate.
