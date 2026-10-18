# Nexus Media Control

Status: Draft (not yet implemented)  
Protocol version: 1

## Overview

Remote control of media playback on a paired device over an established
session (connection.md). Feature name: `media`.

Commands are one-way: the sender issues `media.command`, the receiver
applies it to the platform's active media session. Transport is already
authenticated and encrypted (pinned TLS); there is no additional
authentication for this feature.

## Messages

### `media.command`

```json
{
  "type": "media.command",
  "payload": { "action": "toggle" }
}
```

| Action | Extra payload | Effect |
|---|---|---|
| `play` | — | Resume playback |
| `pause` | — | Pause playback |
| `toggle` | — | Play/pause toggle |
| `next` | — | Skip to next track |
| `previous` | — | Skip to previous track |
| `volume_up` | `step?` (int 1–10) | Raise volume; default step 5 |
| `volume_down` | `step?` (int 1–10) | Lower volume; default step 5 |
| `volume_set` | `volume` (int 0–100) | Set absolute volume |
| `mute` | — | Mute |
| `unmute` | — | Unmute |

Unknown `action` values MUST be answered with
`system.error {code: "invalid_payload"}` and logged at DEBUG; the
connection stays open.

### `media.status`

Optional best-effort push from the receiver (e.g., after applying a
command, or when the OS reports a change):

```json
{
  "type": "media.status",
  "payload": { "playing": true, "volume": 40, "muted": false }
}
```

All three fields are optional; a receiver that cannot report a value omits
it (empty payload `{}` is legal). Receivers MUST NOT be required to send
this message — senders that display status MUST tolerate its absence.

## Rules

1. Applying a command MUST be non-blocking for the connection and MUST
   never crash the session: if no media session is active or the platform
   cannot perform the action, drop the command and log at DEBUG.
2. Receivers MAY drop commands when the sender exceeds 20 commands/second
   (flood protection), logging at DEBUG. Silently dropped — not an error.
3. `volume_set.volume` outside 0–100, `step` outside 1–10, or a non-integer
   ⇒ `system.error {code: "invalid_payload"}`.
4. Sender side SHOULD limit how fast it forwards repeated local actions
   (e.g., holding a volume key generates a stream); debouncing is a
   sender-side concern.
5. Unknown `media.*` message types ⇒ `system.error {code:"unknown_type"}`
   (connection.md), connection stays open.

## Platform mapping (v0.1)

| Platform | Mechanism | Notes |
|---|---|---|
| Windows | `SendInput` media keys (`VK_MEDIA_PLAY_PAUSE`, etc.) | Volume keys adjust system volume; works with any focused player |
| Linux X11 | MPRIS D-Bus (`org.mpris.MediaPlayer2.Player`) | Targets the most recently active player; falls back to media keys |
| Linux Wayland | Best-effort (media keys via `wl-notify`-independent paths) | May be unavailable; documented limitation per SRS |
| Android | Dispatch to the active `MediaSession` (via `MediaController`) | Android 10+ background control limited to sessions that allow it; no bypassing OS restrictions |

Implementations MUST use these platform mechanisms — never custom input
injection that circumvents OS policy.

## Errors

| Condition | Behavior |
|---|---|
| Unknown `action` or invalid payload values | `system.error {code: "invalid_payload"}`, message dropped |
| Unknown `media.*` type | `system.error {code: "unknown_type"}`, message dropped |
| Platform cannot apply the action | Drop silently, log at DEBUG |
