# Nexus

**Nexus is a local-first, open-source companion for connecting your Android phone and desktop.**

It finds your devices on the local network, pairs them once with a short code you
compare on both screens, and then keeps a direct, encrypted connection between
them — so the clipboard stays in sync. Everything happens on your own network.
There is no account, no server and no cloud.

**Status: v0.2.0.** This release adds a guided device-setup flow, start on
boot for both apps, and opt-in notification mirroring from your phone to your
computer. The core remains **clipboard sync over a paired local connection**,
plus the discovery, pairing and security that make it trustworthy. Features such
as file transfer and media control are on the [roadmap](#roadmap), not in this
release.

## Features

- **Automatic discovery** — devices announce themselves on your LAN with mDNS
  (`_nexus._tcp`); no IP addresses or configuration.
- **Secure pairing** — compare a 6-digit code shown on both screens. Each
  device's certificate is then pinned, so the connection is only ever between
  the two devices you paired.
- **Encrypted and authenticated** — all traffic runs over a direct TLS 1.3
  connection between the devices.
- **Automatic reconnection** — trusted devices reconnect on their own when
  they are on the same network.
- **Two-way clipboard sync** — plain text, in both directions, with loop
  prevention so a synced value is never sent straight back.
- **Guided setup** — a short, repeatable walkthrough on both apps for pairing a
  new phone; it reuses the normal pairing flow rather than hiding it.
- **Start on boot** — both apps can restore the local link after a restart
  (enabled by default; toggle in Settings).
- **Notification mirroring** — opt in on the phone to see its notifications on
  your computer as native toasts and a short in-memory list. The phone's app
  controls whether the message body is included.
- **Local-first and private** — no account, no server, no cloud, no telemetry.
  Clipboard history and mirrored notifications are never stored.

## Installation

### Windows

Download the latest `Nexus_0.2.0_x64-setup.exe` (or `Nexus_0.2.0_x64_en-US.msi`)
from the [Releases page](https://github.com/JellyFishJuggler/Nexus/releases) and
run it. No Rust, Node or terminal is required.

### Android

Download `app-release.apk` from the
[Releases page](https://github.com/JellyFishJuggler/Nexus/releases) and open it.
Android will ask you to allow installing apps from this source. Nexus requires
**Android 10 (API 29) or newer**.

## Getting started

1. Install Nexus on both your desktop and your phone.
2. Put both on the same Wi-Fi network.
3. Open Nexus on both devices.
4. Open **Devices → Set up** on the phone (or **Set up your phone** on the
   desktop) and follow the three steps. It opens a 60-second pairing window.
5. Choose the other device under **Nearby devices** and tap **Pair**.
6. Confirm that the **same 6-digit code** appears on both screens.

That's it — the devices connect automatically from now on. The home screen shows
which device is connected, that the connection is secure, whether clipboard sync
is active, and the devices currently on your network.

### Optional: notification mirroring

On the phone, open **Settings → Mirror notifications** and grant notification
access when Android asks. After that, notifications from the phone appear as
toasts on the computer and in the desktop app's **Notifications** page. Turn on
**Hide notification text** to forward only the app name and title. This is
opt-in, phone → computer only, and can be turned off at any time; the desktop
also has its own toggle under **Settings**.

## How it works

1. Devices announce themselves on the LAN with mDNS and find each other
   automatically.
2. The first connection is verified by comparing a short code shown on both
   screens (a short authentication string). Afterwards each device's certificate
   is pinned, and future connections only work between trusted devices.
3. All traffic runs over a direct TCP connection encrypted with TLS 1.3.

The protocol is documented in [`protocol/`](protocol/) and is the source of truth
for both implementations.

## Security

- Nexus only talks to devices on your local network; there is no internet
  backend and nothing is uploaded anywhere.
- Every device has its own self-signed certificate and private key, generated
  on the device. Private keys never leave the device.
- A connection is only accepted between devices that have completed pairing and
  whose certificates are pinned. An unpaired device is rejected.
- Pairing requires you to confirm the same short code on both devices, which
  prevents a silent man-in-the-middle during the initial pairing.
- Nexus collects no analytics. Clipboard contents and mirrored notification
  content are never written to logs or stored on disk.

If you find a security issue, please open an issue on the
[issue tracker](https://github.com/JellyFishJuggler/Nexus/issues).

## Limitations

- **Android clipboard is foreground-only.** Since Android 10, only the app with
  input focus may read the clipboard, so Nexus does not try to work around it.
  In practice: a copy on the desktop reaches the phone immediately, but a copy
  on the phone is only picked up and sent while Nexus is open and focused. This
  also applies after starting on boot: the link is ready, but clipboard capture
  waits until you open the app.
- **Notification mirroring is opt-in and one-way.** It is phone → computer only;
  you cannot reply or dismiss from the desktop. Ongoing notifications and group
  summaries are skipped, and the computer keeps at most 50 items in memory.
- **Start on boot is best-effort.** Some Android vendors delay or block boot
  receivers (Nexus queues behind the system's other boot work), and battery
  optimisation can stop the background link. Disable "Start on boot" in
  Settings if you prefer to open the apps yourself.
- **Plain text only.** Images, files and rich text are not synced.
- **One active connection at a time** per device.
- **Platform support:** Windows and Linux (X11) are the primary targets.
  Wayland and macOS are best-effort and untested. v0.2 installers are published
  for Windows; other platforms can be built from source.

## Roadmap

These are **not** in v0.2:

- **File transfer** between paired devices.
- **Media control**.
- **Notification actions** on the computer.

## Repository layout

| Path        | Contents                                                            |
| ----------- | ------------------------------------------------------------------- |
| `desktop/`  | Rust workspace: `nexus-core` (library), `nexus-desktop` (Tauri app), `nexus-cli` (test harness) |
| `android/`  | Kotlin/Compose Android app (min SDK 29)                             |
| `protocol/` | Protocol specifications — the source of truth for both sides        |
| `docs/`     | Milestone and release notes                                         |

## Building from source

### Desktop

Requires a recent stable Rust toolchain.

```sh
cd desktop
cargo build --workspace
cargo test --workspace
```

To build the Tauri desktop app and installers:

```sh
cd desktop/nexus-desktop
cargo install tauri-cli --version '^2'   # once
cargo tauri build
```

`nexus-cli` is the development harness used to exercise the protocol without a
GUI:

```sh
# Terminal A — advertise and browse
nexus-cli browse
# Terminal B — pair with the other device
nexus-cli pair --to <device-name>
```

### Android

Requires JDK 17 and the Android SDK (set `sdk.dir` in
`android/local.properties`, or the `ANDROID_HOME` environment variable).

```sh
cd android
./gradlew :app:assembleDebug
```

Release builds are signed from `android/keystore.properties` (not committed).
Without that file the release build is left unsigned.

## Contributing

Protocol changes start in `protocol/`; code follows. Both the Rust and Kotlin
implementations must implement the documented behavior. See
`.github/workflows/ci.yml` for the checks that run on every change.

## License

[MIT](LICENSE)
