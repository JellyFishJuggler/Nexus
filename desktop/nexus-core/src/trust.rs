//! Trust store: pinned peer certificates.
//!
//! Implements `protocol/pairing.md` § "Trust store".

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::config::ensure_dir;
use crate::error::{Error, Result};
use crate::protocol::DeviceInfo;

const TRUST_FILE: &str = "trusted_devices.json";

/// A device this installation has explicitly paired with.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TrustedDevice {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    /// SHA-256 fingerprint of the pinned certificate (hex).
    pub cert_sha256: String,
    /// The pinned certificate itself, PEM encoded.
    pub cert_pem: String,
    /// Unix time the pairing completed.
    pub paired_at: i64,
}

impl TrustedDevice {
    /// Certificate bytes, decoded from PEM.
    pub fn cert_der(&self) -> Result<Vec<u8>> {
        let der = rustls_pemfile::certs(&mut self.cert_pem.as_bytes())
            .next()
            .transpose()
            .map_err(|e| Error::Certificate(e.to_string()))?
            .ok_or_else(|| Error::Certificate("stored certificate is unreadable".into()))?;
        Ok(der.as_ref().to_vec())
    }
}

/// Persisted set of trusted devices.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrustStore {
    pub version: u32,
    pub devices: Vec<TrustedDevice>,
}

impl Default for TrustStore {
    fn default() -> Self {
        Self {
            version: 1,
            devices: Vec::new(),
        }
    }
}

impl TrustStore {
    /// Path of the trust store inside `dir`.
    pub fn path(dir: &Path) -> PathBuf {
        dir.join(TRUST_FILE)
    }

    /// Load the trust store, tolerating a missing or corrupt file.
    ///
    /// A corrupt store is treated as empty: trust is never *gained* by a
    /// parse failure, so this fails safe.
    pub fn load(dir: &Path) -> Self {
        let path = Self::path(dir);
        match std::fs::read_to_string(&path) {
            Ok(text) => match serde_json::from_str::<TrustStore>(&text) {
                Ok(store) => store,
                Err(e) => {
                    tracing::error!(
                        "trust store {} is corrupt ({e}); treating as empty",
                        path.display()
                    );
                    TrustStore::default()
                }
            },
            Err(_) => TrustStore::default(),
        }
    }

    /// Persist the trust store.
    pub fn save(&self, dir: &Path) -> Result<()> {
        ensure_dir(dir)?;
        let text = serde_json::to_string_pretty(self)?;
        let path = Self::path(dir);
        std::fs::write(&path, text)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
        }
        Ok(())
    }

    /// Find a trusted device by certificate fingerprint.
    pub fn by_fingerprint(&self, fingerprint: &str) -> Option<&TrustedDevice> {
        self.devices.iter().find(|d| d.cert_sha256 == fingerprint)
    }

    /// Find a trusted device by device id.
    pub fn by_device_id(&self, device_id: &str) -> Option<&TrustedDevice> {
        self.devices.iter().find(|d| d.device_id == device_id)
    }

    /// Whether any device is trusted.
    pub fn is_empty(&self) -> bool {
        self.devices.is_empty()
    }

    /// Add or replace a trusted device, keyed by certificate fingerprint.
    pub fn insert(&mut self, device: TrustedDevice) {
        self.devices
            .retain(|d| d.cert_sha256 != device.cert_sha256 && d.device_id != device.device_id);
        self.devices.push(device);
    }

    /// Remove a device by device id. Returns whether anything was removed.
    pub fn remove(&mut self, device_id: &str) -> bool {
        let before = self.devices.len();
        self.devices.retain(|d| d.device_id != device_id);
        self.devices.len() != before
    }

    /// Build a trusted-device record from peer identity plus pinned cert.
    pub fn record_for(info: &DeviceInfo, cert_der: &[u8], cert_pem: &str) -> TrustedDevice {
        TrustedDevice {
            device_id: info.device_id.clone(),
            name: info.name.clone(),
            platform: info.platform.clone(),
            cert_sha256: crate::identity::Identity::fingerprint_of(cert_der),
            cert_pem: cert_pem.to_string(),
            paired_at: crate::protocol::now_secs(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(id: &str, fp: &str) -> TrustedDevice {
        TrustedDevice {
            device_id: id.into(),
            name: "Phone".into(),
            platform: "android".into(),
            cert_sha256: fp.into(),
            cert_pem: "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n".into(),
            paired_at: 0,
        }
    }

    #[test]
    fn round_trip_and_lookup() {
        let dir = tempfile::tempdir().unwrap();
        let mut store = TrustStore::default();
        store.insert(device("a", "fp-a"));
        store.insert(device("b", "fp-b"));
        store.save(dir.path()).unwrap();

        let loaded = TrustStore::load(dir.path());
        assert_eq!(loaded.devices.len(), 2);
        assert!(loaded.by_fingerprint("fp-a").is_some());
        assert!(loaded.by_device_id("b").is_some());
        assert!(loaded.by_device_id("missing").is_none());
    }

    #[test]
    fn insert_replaces_same_device() {
        let mut store = TrustStore::default();
        store.insert(device("a", "fp-1"));
        store.insert(device("a", "fp-2"));
        assert_eq!(store.devices.len(), 1);
        assert_eq!(store.devices[0].cert_sha256, "fp-2");
    }

    #[test]
    fn corrupt_store_is_empty_not_panic() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(TrustStore::path(dir.path()), b"{ not json").unwrap();
        assert!(TrustStore::load(dir.path()).is_empty());
    }

    #[test]
    fn remove_reports_change() {
        let mut store = TrustStore::default();
        store.insert(device("a", "fp-a"));
        assert!(store.remove("a"));
        assert!(!store.remove("a"));
        assert!(store.is_empty());
    }
}
