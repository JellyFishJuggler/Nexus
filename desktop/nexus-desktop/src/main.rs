//! Nexus desktop application.
//!
//! A thin Tauri v2 shell over `nexus-core`: the backend owns discovery,
//! pairing/trust, the secure session, and clipboard synchronization; the
//! webview renders a snapshot of that state and posts user actions back.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod clipboard;
mod commands;

use tauri::Manager;
use tracing_subscriber::EnvFilter;

fn main() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    let _ = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .try_init();

    let (shared, cmd_rx) = app::Shared::new();

    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_notification::init())
        .setup({
            let shared = shared.clone();
            move |app| {
                app.manage(shared.clone());
                let handle = app.handle().clone();
                tauri::async_runtime::spawn(app::run(shared, cmd_rx, handle));
                Ok(())
            }
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                if let Some(shared) = window
                    .app_handle()
                    .try_state::<std::sync::Arc<app::Shared>>()
                {
                    shared.send(app::Command::Shutdown);
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_status,
            commands::pair,
            commands::connect,
            commands::disconnect,
            commands::unpair,
            commands::set_clipboard_enabled,
            commands::set_notifications_enabled,
            commands::clear_notifications,
            commands::allow_pairing,
            commands::push_clipboard,
            commands::respond_pairing,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the Nexus desktop application");
}
