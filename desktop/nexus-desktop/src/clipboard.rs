//! Operating-system clipboard access for the desktop app.
//!
//! The platform clipboard handle (`arboard::Clipboard`) is kept on a
//! dedicated OS thread: it is not reliably `Send`, and the manager runs on the
//! async runtime. The two talk over channels. The thread polls at
//! [`MIN_SEND_INTERVAL`], which doubles as the protocol's send debounce.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::Arc;

use nexus_core::clipboard::MIN_SEND_INTERVAL;

/// Commands into the clipboard thread.
#[derive(Debug)]
pub enum ClipboardCommand {
    /// Write `text` to the clipboard; report `hash` back when it succeeds.
    Apply { text: String, hash: String },
    /// Re-read the clipboard and report it (used right after connecting).
    Push,
}

/// Events out of the clipboard thread.
#[derive(Debug, Clone)]
pub enum ClipboardEvent {
    /// The local clipboard now holds `text` (a change this app did not cause).
    Local(String),
    /// The clipboard was written on behalf of a peer; record this hash.
    Applied { hash: String },
    /// A platform clipboard operation failed (never fatal to the session).
    Failed(String),
}

/// A running clipboard monitor.
pub struct ClipboardMonitor {
    tx: Sender<ClipboardCommand>,
}

impl ClipboardMonitor {
    /// Spawn the monitor thread. `on_event` is called for every event.
    pub fn start(
        enabled: Arc<AtomicBool>,
        on_event: impl Fn(ClipboardEvent) + Send + 'static,
    ) -> Self {
        let (tx, rx) = mpsc::channel();
        let _ = std::thread::Builder::new()
            .name("nexus-clipboard".into())
            .spawn(move || run(enabled, rx, on_event));
        Self { tx }
    }

    /// Queue a command for the clipboard thread.
    pub fn send(&self, command: ClipboardCommand) {
        let _ = self.tx.send(command);
    }
}

fn run(
    enabled: Arc<AtomicBool>,
    rx: Receiver<ClipboardCommand>,
    on_event: impl Fn(ClipboardEvent),
) {
    let mut clipboard = match arboard::Clipboard::new() {
        Ok(clipboard) => clipboard,
        Err(e) => {
            on_event(ClipboardEvent::Failed(format!(
                "the system clipboard is unavailable: {e}"
            )));
            return;
        }
    };

    // Last value we know about (either observed or written by us). Used so a
    // local read never reports our own write back as a change.
    let mut last_seen: Option<String> = None;
    // Whether we have already reported the current value to the caller.
    let mut reported: Option<String> = None;

    loop {
        match rx.recv_timeout(MIN_SEND_INTERVAL) {
            Ok(ClipboardCommand::Apply { text, hash }) => match clipboard.set_text(text.clone()) {
                Ok(()) => {
                    last_seen = Some(text);
                    reported = last_seen.clone();
                    on_event(ClipboardEvent::Applied { hash });
                }
                Err(e) => {
                    // Delayed rendering / another owner: drop and report.
                    on_event(ClipboardEvent::Failed(format!(
                        "couldn't write to the clipboard: {e}"
                    )));
                }
            },
            Ok(ClipboardCommand::Push) => {
                if let Ok(text) = clipboard.get_text() {
                    if last_seen.as_deref() != Some(text.as_str()) {
                        last_seen = Some(text.clone());
                    }
                    if reported.as_deref() != Some(text.as_str()) {
                        reported = Some(text.clone());
                        on_event(ClipboardEvent::Local(text));
                    }
                }
            }
            Err(RecvTimeoutError::Timeout) => {
                if !enabled.load(Ordering::Relaxed) {
                    continue;
                }
                // A busy or locked clipboard is routine; skip this tick.
                let Ok(text) = clipboard.get_text() else {
                    continue;
                };
                if last_seen.as_deref() == Some(text.as_str()) {
                    continue;
                }
                last_seen = Some(text.clone());
                reported = Some(text.clone());
                on_event(ClipboardEvent::Local(text));
            }
            Err(RecvTimeoutError::Disconnected) => break,
        }
    }
}
