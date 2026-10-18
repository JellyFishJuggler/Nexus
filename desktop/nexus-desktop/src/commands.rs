//! Tauri commands: the webview's only way to talk to the manager.

use std::sync::Arc;

use tauri::State;

use crate::app::{Command, Shared, Status};

/// The full UI snapshot.
#[tauri::command]
pub fn get_status(shared: State<'_, Arc<Shared>>) -> Status {
    shared.snapshot()
}

/// Connect to a device, pairing first if it is not yet trusted.
#[tauri::command]
pub fn pair(shared: State<'_, Arc<Shared>>, device_id: String) {
    shared.send(Command::Pair(device_id));
}

/// Connect to an already-trusted device.
#[tauri::command]
pub fn connect(shared: State<'_, Arc<Shared>>, device_id: String) {
    shared.send(Command::Connect(device_id));
}

/// Drop the active session.
#[tauri::command]
pub fn disconnect(shared: State<'_, Arc<Shared>>) {
    shared.send(Command::Disconnect);
}

/// Forget a trusted device.
#[tauri::command]
pub fn unpair(shared: State<'_, Arc<Shared>>, device_id: String) {
    shared.send(Command::Unpair(device_id));
}

/// Turn clipboard synchronization on or off.
#[tauri::command]
pub fn set_clipboard_enabled(shared: State<'_, Arc<Shared>>, enabled: bool) {
    shared.send(Command::SetClipboardEnabled(enabled));
}

/// Open the 60-second pairing window (responder role).
#[tauri::command]
pub fn allow_pairing(shared: State<'_, Arc<Shared>>) {
    shared.send(Command::AllowPairing);
}

/// Send the current clipboard to the active peer now.
#[tauri::command]
pub fn push_clipboard(shared: State<'_, Arc<Shared>>) {
    shared.send(Command::PushClipboard);
}

/// Answer a pairing prompt. Returns false if the prompt is no longer pending.
#[tauri::command]
pub fn respond_pairing(shared: State<'_, Arc<Shared>>, token: String, accept: bool) -> bool {
    shared.respond_pairing(&token, accept)
}
