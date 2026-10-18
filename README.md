# Nexus

Local-first device companion for your phone and computer: sync the clipboard,
send files, and control media — directly between your devices over the local
network. No accounts, no servers, no cloud.

**Status: v0.1 (in development).** Scope for v0.1:

- Clipboard sync (plain text, two-way)
- File transfer
- Media control
- mDNS discovery (`_nexus._tcp`)
- Pairing with pinned per-device certificates (TLS 1.3)
- Automatic reconnection

## How it works

1. Devices announce themselves on the LAN via mDNS and find each other
   automatically — no IP addresses, no configuration.
2. The first connection is verified by comparing a short code shown on both
   screens (short authentication string). After that, each device's
   certificate is pinned; future connections only work between trusted
   devices.
3. All traffic runs over a direct TCP connection encrypted with TLS 1.3.

The protocol is documented in [`protocol/`](protocol/) and is the source of
truth for both implementations.

## Repository layout

| Path        | Contents                                                        |
| ----------- | --------------------------------------------------------------- |
| `desktop/`  | Rust workspace: `nexus-core` (library) and `nexus-cli` (harness) |
| `android/`  | Kotlin Android app (min SDK 29 / Android 10)                     |
| `protocol/` | Protocol specifications (framing, discovery, pairing, ...)       |

## Building

### Desktop (Rust)

```sh
cd desktop
cargo build --workspace
cargo test --workspace
```

`nexus-cli` is the development harness used to exercise the protocol:

```sh
# Terminal A
nexus-cli browse
# Terminal B
nexus-cli pair --to <device-name>
```

### Android

Requires JDK 17 and the Android SDK (set `sdk.dir` in `android/local.properties`
or the `ANDROID_HOME` environment variable):

```sh
cd android
gradle :app:assembleDebug
```

## Contributing

Protocol changes start in `protocol/`; code follows. Both the Rust and
Kotlin implementations must implement the documented behavior. See
`.github/workflows/ci.yml` for the checks that run on every change.

## License

[MIT](LICENSE)
