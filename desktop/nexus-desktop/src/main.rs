//! Nexus desktop application.
//!
//! A thin Tauri v2 shell over `nexus-core`: the backend owns discovery,
//! pairing/trust, the secure session, and clipboard synchronization; the
//! webview renders a snapshot of that state and posts user actions back.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod clipboard;
mod commands;

use tauri::{menu::{Menu, MenuItem}, tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent}, Manager};
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
                let open = MenuItem::with_id(app, "open", "Open Nexus", true, None::<&str>)?;
                let quit = MenuItem::with_id(app, "quit", "Exit Nexus", true, None::<&str>)?;
                let menu = Menu::with_items(app, &[&open, &quit])?;
                TrayIconBuilder::new()
                    .icon(app.default_window_icon().expect("default app icon is configured").clone())
                    .tooltip("Nexus")
                    .menu(&menu)
                    .on_menu_event(|app, event| match event.id().as_ref() {
                        "open" => {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                        "quit" => {
                            if let Some(shared) = app.try_state::<std::sync::Arc<app::Shared>>() {
                                shared.send(app::Command::Shutdown);
                            }
                            app.exit(0);
                        }
                        _ => {}
                    })
                    .on_tray_icon_event(|tray, event| {
                        if let TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        } = event {
                            if let Some(window) = tray.app_handle().get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    })
                    .build(app)?;
                let handle = app.handle().clone();
                tauri::async_runtime::spawn(app::run(shared, cmd_rx, handle));
                Ok(())
            }
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
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
