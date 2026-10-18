# Desktop app + clipboard sync (v0.1)

Status: **verified** on a physical Android 14 device (API 34)
against the Tauri desktop app on Windows 11, per `protocol/clipboard.md` and
`protocol/connection.md`.

Scope for this milestone was deliberately narrow: **a desktop UI, and
clipboard synchronization only.** Discovery, pairing, TLS, and trust reuse the
existing Rust core and the Android A1–A3 code; no protocol was redesigned.

## What was built

| Area | File |
| --- | --- |
| Tauri shell, window, command wiring | `desktop/nexus-desktop/src/main.rs` |
| Manager task: discovery, accept loop, active connection, clipboard | `desktop/nexus-desktop/src/app.rs` |
| Clipboard OS access (dedicated thread) | `desktop/nexus-desktop/src/clipboard.rs` |
| Tauri commands (status + actions) | `desktop/nexus-desktop/src/commands.rs` |
| UI (HTML/CSS/JS) | `desktop/nexus-desktop/ui/` |
| Clipboard protocol logic (Rust) | `desktop/nexus-core/src/clipboard.rs` |
| Android clipboard protocol logic | `android/.../clipboard/ClipboardProtocol.kt` |
| Android clipboard OS access | `android/.../clipboard/ClipboardSync.kt` |
| Android connection layer (hello + heartbeat) | `android/.../connection/NexusSession.kt` |
| Android pairing→session handoff | `android/.../connection/TrustedSessionHandler.kt` |
| Android wiring + UI card | `android/.../discovery/DiscoveryController.kt`, `MainActivity.kt` |

### Architecture

The desktop backend is one task that owns the `Node`, discovery, the accept
loop, the active `Connection`, and the clipboard monitor, driven by a single
`Event` enum in a `tokio::select!`. Tauri commands read a `Status` snapshot and
post `Command`s; they never touch the protocol directly. The clipboard monitor
runs on its own OS thread (`arboard` is not `Send`-friendly in an async loop)
and communicates by channel.

Clipboard loop-prevention lives in `nexus-core::clipboard` (Rust) and
`ClipboardProtocol` (Android), byte-for-byte compatible hashes, with the OS
clipboard confined to the platform layer (`arboard` / `ClipboardManager`).

## The desktop UI

A single window with:

- **Header** — "Nexus" wordmark and a connection badge (`online` / `offline`).
- **This device** — name, device ID, platform, listening port, discovery state,
  data directory.
- **Connection** — the active peer and its state, plus **Allow new pairing
  (60 s)** and **Disconnect**.
- **Clipboard sync** — an enable toggle, the sync status, the last value, and
  **Send clipboard now**.
- **Discovered devices** — live peers with **Pair** / **Connect** actions.
- **Trusted devices** — pinned peers with **Unpair**.
- **Activity** — a rolling log (clearable).
- **Pairing dialog** — a modal showing the 6-digit SAS with **Accept** /
  **Reject**.

The webview polls `get_status` roughly twice a second and renders that
snapshot; it does not use the Tauri event API (avoids capability/ACL surface).

## How to run it

```sh
cd desktop
cargo run -p nexus-desktop
```

Optional flags (mirrors `nexus-cli`, useful for isolated runs):

```sh
cargo run -p nexus-desktop -- --data-dir <path> --name <name> --port <port>
```

The default data directory is the platform Nexus config directory
(`%APPDATA%\nexus` on Windows), shared with `nexus-cli`, so an identity and its
trust store are reused across both. Logs go to stderr; `RUST_LOG=info` (the
default) shows discovery, connections, and clipboard decisions.

A release build is `cargo build -p nexus-desktop --release`; the bundled app is
produced with `cargo tauri build` (Tauri CLI) if a native installer is wanted.

## Automated checks

- **Rust** — `cargo fmt --check` clean; `cargo clippy --workspace
  --all-targets -- -D warnings` clean; `cargo test --workspace` passes
  (30 unit tests incl. 10 clipboard tests + 1 doc test).
- **Android** — **55** JVM unit tests pass: `ClipboardProtocolTest` 9,
  `PeerInfoTest` 10, `SasTest` 5, `DeviceInfoTest` 5, `EnvelopeTest` 8,
  `FrameCodecTest` 11, `TrustStoreTest` 7.
- **Android lint** — `:app:lintDebug` has **no errors**. The 104 warnings are
  all pre-existing categories (`LogNotTimber` — the codebase uses
  `android.util.Log` throughout; `GradleDependency` / AGP version;
  `MonochromeLauncherIcon`; `RedundantLabel`; `ObsoleteSdkInt`; and the
  deliberate, documented `TrustAllX509TrustManager`).
- **Builds** — `:app:assembleDebug` produces the installable APK;
  `cargo build -p nexus-desktop` builds the desktop app.
- **CI** — `.github/workflows/ci.yml` installs the Linux WebKitGTK/GTK Tauri
  dependencies before clippy/test so the workspace stays green on Ubuntu.

## Live acceptance test (Windows ↔ Android)

One fresh pairing between the desktop identity and the phone. The 6-digit SAS
was confirmed on both sides.

| # | Step | Result |
| --- | --- | --- |
| 1 | Discover | Desktop discovered the phone (android) via mDNS; phone advertised its TLS port. |
| 2 | Pair / trust | `paired successfully`; both trust stores updated and the phone persisted the pin. |
| 3 | Secure connection | `connected — encrypted + authenticated`, `features: clipboard`; both sides exchanged `system.hello`. |
| 4 | Windows → Android | `Set-Clipboard "windows-to-android-42"` → desktop `clipboard sent (21 bytes)` → phone `clipboard applied (21 chars)`. **Phone's actual clipboard read back: `"windows-to-android-42"`.** |
| 5 | Android → Windows | Phone sent `"android-to-windows-777"` → desktop `clipboard updated from peer`. **Windows clipboard: `"android-to-windows-777"`.** |
| 6 | No loops | Desktop clipboard-log line count unchanged across 15 s idle (10 → 10); no phone activity. |
| 7 | Repeated modifications | `repeat-1`, `repeat-2`, `repeat-3` each sent and applied in order; final phone clipboard `"repeat-3"`. |
| 8 | Disconnect / reconnect | Desktop killed → phone `session closed: io: Connection reset`; desktop restarted → reconnected; `"after-reconnect-99"` delivered. |
| 9 | Heartbeat stability | 75 s idle with **no** stale drop and no reconnect. |

The Windows clipboard was read back with `Get-Clipboard`, and the phone's
clipboard with a test-only hook (see below), so both directions were verified
by **content**, not merely by absence of errors.

## Bug found and fixed during the live run

The first long-idle run dropped a healthy session after 60 s with
`NexusSession: no traffic from peer for 60s; dropping`, even though the desktop
was sending `system.ping` every 15 s.

Root cause: Android's `Connection.handleControl` answers `system.ping` and
swallows `system.pong`/`system.error` internally, so `NexusSession`'s
`onTrustedMessage` (the only place it refreshed liveness) never saw heartbeats.
Its 60 s timer therefore declared a live peer dead.

Fix: the read loop — the only code that sees every frame, including control
frames — now owns the clock (`Connection.lastActivityAt`, updated for every
frame read), and `NexusSession` reads it. This makes the documented rule
("any inbound frame within 60 s ⇒ alive", `protocol/connection.md`) literally
true on Android. Re-tested: the session survives idle, no drops.

## Android clipboard limitations (explicit)

Android 10+ only lets an app **read** the clipboard while it has input focus
(writes are unrestricted). Nexus does not work around this:

- Clipboard sync on Android is **foreground-only**.
- It reads on resume, on the framework's clip-changed callback (delivered only
  to the focused app), and on a slow poll.
- Consequence: a copy made on the desktop reaches the phone immediately, but a
  copy made *on the phone* is only picked up and sent while Nexus is open and
  focused. Background clipboard capture is a known prototype limitation.
- No AccessibilityService, root, hidden APIs, or privacy bypasses are used.

Because `adb` cannot read or write the clipboard on Android 10+, the scripted
test used two **test-only** harness hooks (see below) to simulate a local copy
and to read the clipboard back. Each hook goes through the normal
`ClipboardManager` path — the app writes the clipboard and the ordinary
capture/send path runs, exactly as it would after a user copies in another app.

## Test harness (test-only)

Android activity extras, mirroring the A3 harness:

```
adb shell am start -n dev.nexus.local/.MainActivity \
  --ez nexus_pairing true \
  --es nexus_autoconfirm '123 456' \
  --es nexus_host 192.0.2.14 --ei nexus_port 47821 \
  --es nexus_set_clipboard 'android-to-windows-777' \
  --ez nexus_read_clipboard true
```

- `nexus_set_clipboard <s>` — write `s` to the clipboard and send it (simulates
  a local copy).
- `nexus_read_clipboard true` — log the current system clipboard.
- Both actions wait until the session is ready before touching the clipboard,
  because `am start` restarts the activity before a session exists.

For the automated run, trust was established by driving `nexus-cli` as the
pairing **responder** with a deliberately wrong code, reading the real SAS from
its log, then re-running with the correct code. The desktop app then reused the
same identity and trust store.

## Known limitations / notes

- **Heartbeat sensitivity (fixed)** — see above; both sides now agree on
  "any frame within 60 s".
- **No cross-reconnect queueing** — as specified in `protocol/clipboard.md`, a
  clipboard change with no active session is dropped; the next change
  propagates normally.
- **16 KiB text cap** — larger clipboard values are ignored with a log line
  (`clipboard not synced: … exceeds the limit`) rather than truncated.
- **Text only** — no images, rich formats, or files.
- **New session offers the current clipboard once** — the sender forgets its
  last-sent hash when a session becomes ready (`SyncState::reset_sent` /
  `resetSent`), so the latest value is shared with a newly connected peer,
  while content received *from* a peer is still never echoed back.
- **Desktop pairing prompt** has no auto-accept flag; scripted runs establish
  trust with `nexus-cli` first (same data directory).
- **OEM quirk** — on this Transsion OEM build, `adb shell am start` targeted at
  an already-running instance did not deliver `onNewIntent`, so harness actions
  are applied on a fresh launch (`force-stop` then `start`).
- **Single active session** per device, consistent with `connection.md`.

## Protocol docs updated

- `protocol/clipboard.md` — marked **Implemented**; replaced the "feasibility
  spike" text with the real Android behaviour; added the **Session start
  (initial offer)** section.
- `protocol/connection.md` — marked the Android session loop implemented; added
  a note that Android consumes pongs below the frame loop and therefore relies
  on the 60 s read timeout plus its own 15 s pings.

## Not in scope here

- File transfer, media control, notifications, SMS, calls, screen mirroring,
  remote input, camera, and any cloud/backend — all explicitly out of scope for
  v0.1.
- Android background clipboard capture (would require capabilities the project
  deliberately avoids).
