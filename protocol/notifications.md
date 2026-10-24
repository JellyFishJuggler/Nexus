# Nexus Notification Mirroring

Status: Implemented (Android sender, Rust/Tauri desktop receiver) in v0.2.0  
Protocol version: 1 — additive message, no version bump  
Feature name: `notifications`  
Direction: phone → computer (one-way in v0.2.0)

## Overview

A paired Android phone can forward a notification it just posted to the
connected computer over the existing TLS 1.3 session (connection.md). The
computer shows a native toast and keeps a short, in-memory list in the app.
Nothing is persisted on either side.

This is deliberately a notification **mirror**, not clipboard sync and not a
remote notification API: the phone never sends actions, replies or images, and
the computer cannot act on a notification.

## Privacy rules

Mirroring is **opt-in** on the phone. The user must both enable
"Mirror notifications" and grant Android notification-listener access. When
either is off, the listener reads and sends nothing.

- Only the **app label**, **title** and **body** are extracted. Keys, extras,
  icons, actions, progress, people, and the untruncated original are never
  read or sent.
- "Hide notification text" removes the body before it leaves the phone; the
  title and app label are still forwarded.
- Notification content is never written to logs, disk, or telemetry by either
  implementation. Activity logs only name the app.
- Content is only sent inside the existing encrypted, authenticated session.
  No new connection is opened to mirror a notification.
- Best effort: if no trusted session is ready, the notification is dropped. No
  queue exists across reconnects, and receivers do not request a resend.

## Capability advertisement

Both v0.2.0 implementations add `notifications` to the `features` array in
`system.hello` / discovery `DeviceInfo`:

```json
"features": ["clipboard", "transfer", "media", "notifications"]
```

A sender SHOULD only send `notification.post` when the ready peer's hello
advertised `notifications`. Receivers that do not implement this message reject
it as an unknown control type (connection.md) without closing the session.

## Message: `notification.post`

Sent after the phone posts a notification and the user's filters allow it.

```json
{
  "type": "notification.post",
  "payload": {
    "app": "Messages",
    "title": "Alex",
    "text": "Are we still on for lunch?",
    "ts": 1792756981
  }
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `app` | string | yes | User-visible app label, trimmed, max 64 Unicode code points |
| `title` | string | no | Trimmed, max 200 code points; may be empty when `text` is not |
| `text` | string | no | Trimmed, max 500 code points; empty when the user hid the text |
| `ts` | integer | no | Sender's Unix time in **seconds**; receivers fall back to local time when absent or non-positive |

At least one of `title` and `text` MUST be non-empty. Newlines and carriage
returns are collapsed to single spaces; NUL is replaced with a space. The
sender uses `EXTRA_BIG_TEXT` in preference to `EXTRA_TEXT` when both exist.

## Sender filtering

The phone MUST NOT mirror:

| Case | Reason |
|---|---|
| Its own package (`dev.nexus.local`) | The foreground-service status notification is not user content |
| Ongoing notifications (`isOngoing`, group summaries) | Music, charging, navigation and group containers are noise; individual messages are mirrored instead |
| Content with an empty app label, or an empty title **and** body | Nothing useful to show |
| The same app + title + body within 4 seconds | Android can call `onNotificationPosted` more than once for one update |

The sender performs the network write on a background thread: listener
callbacks run on the main thread, where blocking socket I/O is forbidden.

## Receiver behavior

- Reject `notification.post` when `app` is missing/empty or both title and body
  are empty. Respond with `system.error {code:"invalid_payload"}`; keep the
  session open.
- Cap `app`/`title`/`text` at 64/200/500 code points and collapse newlines.
- If the user disabled mirroring on the computer, ignore the message and do not
  show a toast. Log only that mirroring is disabled.
- Otherwise show a native toast and add the item to an in-memory list capped at
  50 newest-first entries. Reaching the cap drops the oldest entry.
- Never write the item to disk or a log, and never include the title or body in
  an error message.

## Errors

| Condition | Behavior |
|---|---|
| Missing/empty `app` | `system.error {code:"invalid_payload"}`, message dropped |
| Empty `title` and `text` | `system.error {code:"invalid_payload"}`, message dropped |
| Oversized fields | Receiver truncates to the caps above; no error |
| No ready trusted session | Sender drops the notification silently |
| Mirroring disabled on the receiver | Ignored and logged (app name only) |
| Unknown extra fields | Ignored (forward-compatible) |

## Sequence

```text
Phone app posts a notification
Android listener filters (own package? ongoing? summary? duplicate?)
Phone → Computer   notification.post { app, title, text, ts }
Computer           validate, cap, show toast, add to in-memory list
                   (only the app name goes into the activity log)
```
