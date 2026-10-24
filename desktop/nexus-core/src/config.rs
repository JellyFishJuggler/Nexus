//! Configuration and on-disk layout.
//!
//! ```text
//! <data_dir>/
//! ├── settings.toml        user-editable settings
//! ├── device_id            persistent UUID identity
//! ├── identity_cert.der    self-signed certificate (DER)
//! ├── identity_cert.pem    same, PEM (for humans / Android interop)
//! ├── identity_key.der     private key, PKCS#8 (user-only permissions)
//! └── trusted_devices.json pinned peer certificates
//! ```

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};

/// Directory name under the platform config location.
pub const APP_DIR_NAME: &str = "nexus";

/// Default TCP port hint. `0` means "pick an ephemeral port".
///
/// Ephemeral is the default because the advertised mDNS record always carries
/// the real port, and it lets several instances share a machine (dev/testing).
pub const DEFAULT_PORT_HINT: u16 = 0;

/// Nexus data directory for this user.
pub fn default_data_dir() -> Result<PathBuf> {
    let base = dirs::config_dir()
        .ok_or_else(|| Error::Config("couldn't determine the system config directory".into()))?;
    Ok(base.join(APP_DIR_NAME))
}

/// Create a directory (and parents) if it does not exist.
pub fn ensure_dir(path: &Path) -> Result<()> {
    std::fs::create_dir_all(path).map_err(|e| {
        Error::Config(format!(
            "couldn't create data directory {}: {e}",
            path.display()
        ))
    })
}

/// Best-effort host name used as the default device name.
pub fn default_device_name() -> String {
    if let Ok(name) = std::env::var("COMPUTERNAME") {
        if !name.trim().is_empty() {
            return name;
        }
    }
    if let Ok(name) = std::env::var("HOSTNAME") {
        if !name.trim().is_empty() {
            return name;
        }
    }
    "Nexus Device".to_string()
}

/// User-editable settings (SRS §48).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// Name shown to other devices.
    pub device_name: String,
    /// Preferred TCP port; `0` lets the OS choose.
    pub preferred_port: u16,
    /// Whether clipboard synchronization is enabled.
    pub clipboard_enabled: bool,
    /// Whether media controls are enabled.
    pub media_enabled: bool,
    /// Whether incoming file transfers are accepted.
    pub accept_transfers: bool,
    /// Whether phone→computer notification mirroring is shown. Default: on,
    /// because the phone only sends when the user opted in there.
    pub notifications_enabled: bool,
    /// Directory for received files; `None` = platform default.
    pub download_dir: Option<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            device_name: default_device_name(),
            preferred_port: DEFAULT_PORT_HINT,
            clipboard_enabled: true,
            media_enabled: true,
            accept_transfers: true,
            notifications_enabled: true,
            download_dir: None,
        }
    }
}

impl Settings {
    /// Path of the settings file inside `dir`.
    pub fn path(dir: &Path) -> PathBuf {
        dir.join("settings.toml")
    }

    /// Load settings, falling back to defaults when absent or unreadable.
    pub fn load(dir: &Path) -> Settings {
        let path = Self::path(dir);
        match std::fs::read_to_string(&path) {
            Ok(text) => match toml::from_str::<Settings>(&text) {
                Ok(settings) => settings,
                Err(e) => {
                    tracing::warn!(
                        "settings file {} is invalid ({e}); using defaults",
                        path.display()
                    );
                    Settings::default()
                }
            },
            Err(_) => Settings::default(),
        }
    }

    /// Persist settings to disk.
    pub fn save(&self, dir: &Path) -> Result<()> {
        ensure_dir(dir)?;
        let text = toml::to_string_pretty(self)
            .map_err(|e| Error::Config(format!("couldn't encode settings: {e}")))?;
        std::fs::write(Self::path(dir), text)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let settings = Settings {
            device_name: "Test Device".into(),
            preferred_port: 1234,
            ..Default::default()
        };
        settings.save(dir.path()).unwrap();

        let loaded = Settings::load(dir.path());
        assert_eq!(loaded.device_name, "Test Device");
        assert_eq!(loaded.preferred_port, 1234);
    }

    #[test]
    fn missing_settings_yield_defaults() {
        let dir = tempfile::tempdir().unwrap();
        let loaded = Settings::load(dir.path());
        assert!(loaded.clipboard_enabled);
        assert!(loaded.notifications_enabled);
        assert!(!loaded.device_name.is_empty());
    }
}
