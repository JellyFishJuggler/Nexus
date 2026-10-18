# A3 — Bluetooth-free pairing & trust (Android)

Status: **verified** on the physical device (LAVA LZX415, Android 14 / API 34)
against `nexus-cli` on Windows, per `protocol/pairing.md`.

## Scope

Implement the documented pairing flow and certificate pinning on Android,
with no Android-specific protocol variant:

- SAS computation identical to the Rust implementation
- `pairing.request → accept → confirm → complete` in both roles
- accept / reject UI
- trust store persistence (`trusted_devices.json`, Rust-compatible shape)
- pinned-certificate enforcement on reconnect
- correct rejection and error handling

## What was built

| Area | File |
| --- | --- |
| SAS (`SHA-256(min‖max)` → 6 digits) | `android/.../pairing/Sas.kt` |
| Pairing state machine (both roles) | `android/.../pairing/PairingManager.kt` |
| Trust store + `TrustedDevice` | `android/.../trust/TrustStore.kt` |
| Peer identity payload | `android/.../protocol/DeviceInfo.kt` |
| Peer cert / fingerprint / PEM | `android/.../tls/Tls.kt` |
| Connection (role, trust, events) | `android/.../connection/Connection.kt` |
| Accept loop / untrusted policy | `android/.../connection/NexusServer.kt` |
| Controller + UI flows | `android/.../discovery/DiscoveryController.kt` |
| Pairing dialog, trusted list, harness | `android/.../MainActivity.kt` |

### Protocol doc reconciliation

Rust implements an explicit `pairing.confirm` step that `protocol/pairing.md`
did not document. The document was corrected (flow diagram + a new
`### pairing.confirm` message-type section) rather than introducing an
Android-only variant; Android implements the documented flow.

## Automated checks

- Rust: `cargo fmt --check` clean; `cargo clippy --all-targets -D warnings`
  clean; 20 unit tests + 1 doc test pass.
- Android: **46** JVM unit tests pass
  (`PeerInfoTest` 10, `SasTest` 5, `DeviceInfoTest` 5, `EnvelopeTest` 8,
  `FrameCodecTest` 11, `TrustStoreTest` 7).
- Android `lintDebug`: no errors. The deliberate permissive
  `X509TrustManager` is annotated with the rationale
  (`protocol/pairing.md` § "TLS profile").
- `:app:assembleDebug` produces the installable APK.

### Cross-platform SAS equality

Verified two independent ways:

1. **Shared golden vector** `022 451` — the same fixed byte arrays are
   asserted in `nexus-core` `identity::tests::sas_golden_vector` and Android
   `SasTest.matchesTheRustGoldenVector`. The vector includes bytes `> 0x7f`
   (negative as Kotlin `Byte`s) and different lengths, so a signed comparison
   or a missing tiebreak would fail.
2. **Live pairing** — each platform computes its own code from its own
   certificate and only proceeds when the operator supplies that exact code
   on both sides (see A and B below).

## Live tests (Android 14 device ↔ `nexus-cli`)

Each run used a fresh Rust data directory, so each pair had distinct
certificates and therefore a distinct SAS.

| # | Initiator | Responder | SAS | Result |
| --- | --- | --- | --- | --- |
| A | Rust `rust-a` (`3edc169d`) | Android | `461 222` | Rust: `paired successfully (initiator)`; Android pinned `037189…`; both trust stores updated |
| B | Android | Rust `rust-b` (`3255db93`) | `418 407` | Rust: `paired successfully (responder)`; Android pinned `39de4bd2…`; both trust stores updated |
| C | Rust `rust-c` (`d2045f30`) | Android | `802 267` vs confirmed `000 000` | Android rejected (mismatch); Rust: `pairing was rejected`; no trust persisted |
| D | Rust `rust-d` (`85ffe66a`) | Android (no pairing window) | — | Android dropped the peer (`not_paired`); Rust: `the other device isn't paired with this one` |
| E | Android | Rust `rust-b` (already pinned) | — | Android: `peer is trusted`; normal session, no re-pair |

Additional observed behaviours:

- An untrusted peer that sends `system.hello` (instead of `pairing.*`) is
  dropped with `not_paired` on Android.
- Android's persisted `cert_pem` matches Rust's encoding exactly
  (Base-64, 64 columns, trailing newline) and the trust-store JSON matches
  Rust's `{version, devices:[{device_id, name, platform, cert_sha256,
  cert_pem, paired_at}]}` shape.

### Harness

Android accepts intent extras for scripted runs (test-only path):

```
adb shell am start -n dev.nexus.local/.MainActivity \
  --ez nexus_pairing true \
  --es nexus_autoconfirm '461 222' \
  --es nexus_host 192.168.1.14 --ei nexus_port 54321
```

`nexus_autoconfirm` mirrors the Rust harness' `--code`: it accepts only when
the operator-supplied code equals the code this device computed, otherwise it
rejects.

## Known limitations / notes

- Discovery and the listener stop while the app is not in the foreground
  (no foreground service in v0.1). A real test must keep the app on screen.
- When the physical device is in use by another app, stray touches can hit
  the on-screen Pair/Unpair buttons and corrupt a scripted run; the harness
  now ignores duplicated launch intents, and the verification runs above were
  performed with the device free.

## Not in scope here

- Android 10+ background clipboard feasibility (A4) — next.
- Clipboard / transfer / media features (Android advertises an empty feature
  list until they exist).
