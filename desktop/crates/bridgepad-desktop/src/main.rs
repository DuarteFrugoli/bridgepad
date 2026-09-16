#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use bridgepad_daemon::{DaemonOptions, DesktopServer};
use serde::Serialize;
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Manager, State};

const APP_ICON: tauri::image::Image<'_> = tauri::include_image!("icons/32x32.png");

struct DesktopState {
    server: Mutex<Option<DesktopServer>>,
    startup_error: Mutex<Option<String>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TrustedDeviceDto {
    id: String,
    name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopStatusDto {
    phase: &'static str,
    desktop_name: String,
    pairing_code: String,
    pairing_expires_in_seconds: u16,
    trusted_devices: Vec<TrustedDeviceDto>,
    connected_clients: usize,
    active_sessions: usize,
    error: Option<String>,
}

#[tauri::command]
fn desktop_status(state: State<'_, DesktopState>) -> Result<DesktopStatusDto, String> {
    let server = state
        .server
        .lock()
        .map_err(|_| "desktop state lock failed")?;
    let Some(server) = server.as_ref() else {
        let error = state
            .startup_error
            .lock()
            .map_err(|_| "desktop error lock failed")?
            .clone()
            .unwrap_or_else(|| "BridgePad Desktop could not start".to_owned());
        return Ok(DesktopStatusDto {
            phase: "error",
            desktop_name: bridgepad_daemon::default_desktop_name(),
            pairing_code: String::new(),
            pairing_expires_in_seconds: 0,
            trusted_devices: Vec::new(),
            connected_clients: 0,
            active_sessions: 0,
            error: Some(error),
        });
    };
    let snapshot = server.snapshot().map_err(|error| error.to_string())?;
    let phase = if snapshot.active_sessions > 0 {
        "playing"
    } else if snapshot.connected_clients > 0 {
        "connected"
    } else {
        "ready"
    };
    Ok(DesktopStatusDto {
        phase,
        desktop_name: snapshot.desktop_name,
        pairing_code: snapshot.pairing_code,
        pairing_expires_in_seconds: snapshot.pairing_expires_in_seconds,
        trusted_devices: snapshot
            .trusted_devices
            .into_iter()
            .map(|device| TrustedDeviceDto {
                id: device.id,
                name: device.name,
            })
            .collect(),
        connected_clients: snapshot.connected_clients,
        active_sessions: snapshot.active_sessions,
        error: snapshot.last_error,
    })
}

#[tauri::command]
fn new_pairing_code(state: State<'_, DesktopState>) -> Result<(), String> {
    let server = state
        .server
        .lock()
        .map_err(|_| "desktop state lock failed")?;
    server
        .as_ref()
        .ok_or_else(|| "BridgePad Desktop is not running".to_owned())?
        .rotate_pairing_code()
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn forget_device(id: String, state: State<'_, DesktopState>) -> Result<bool, String> {
    let server = state
        .server
        .lock()
        .map_err(|_| "desktop state lock failed")?;
    server
        .as_ref()
        .ok_or_else(|| "BridgePad Desktop is not running".to_owned())?
        .forget_peer(&id)
        .map_err(|error| error.to_string())
}

fn show_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let identity_directory = app.path().app_local_data_dir()?.join("identity");
            let (server, startup_error) = match DesktopServer::start(DaemonOptions {
                identity_directory,
                ..DaemonOptions::default()
            }) {
                Ok(server) => (Some(server), None),
                Err(error) => (None, Some(error.to_string())),
            };
            app.manage(DesktopState {
                server: Mutex::new(server),
                startup_error: Mutex::new(startup_error),
            });

            let open = MenuItem::with_id(app, "open", "Open BridgePad", true, None::<&str>)?;
            let pair = MenuItem::with_id(app, "pair", "Pair another phone", true, None::<&str>)?;
            let separator = PredefinedMenuItem::separator(app)?;
            let quit = MenuItem::with_id(app, "quit", "Exit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &pair, &separator, &quit])?;
            TrayIconBuilder::new()
                .icon(APP_ICON.clone())
                .tooltip("BridgePad Desktop")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "open" => show_main_window(app),
                    "pair" => {
                        if let Ok(server) = app.state::<DesktopState>().server.lock()
                            && let Some(server) = server.as_ref()
                        {
                            let _ = server.rotate_pairing_code();
                        }
                        show_main_window(app);
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main_window(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            desktop_status,
            new_pairing_code,
            forget_device
        ])
        .run(tauri::generate_context!())
        .expect("failed to run BridgePad Desktop");
}
