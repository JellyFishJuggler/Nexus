//! Persistent device identity: UUID, self-signed certificate, private key.
//!
//! Implements `protocol/pairing.md` § "Device identity".

use std::path::{Path, PathBuf};

use rcgen::{
    CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa, KeyPair, KeyUsagePurpose,
    PKCS_ECDSA_P256_SHA256,
};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::config::ensure_dir;
use crate::error::{Error, Result};

/// ECDSA P-256 signature algorithm used for device certificates.
const KEY_FILE: &str = "identity_key.der";
const CERT_DER_FILE: &str = "identity_cert.der";
const CERT_PEM_FILE: &str = "identity_cert.pem";
const DEVICE_ID_FILE: &str = "device_id";

/// This device's cryptographic identity.
pub struct Identity {
    /// Persistent UUID; survives network and name changes.
    pub device_id: String,
    /// Human-readable name shown to peers.
    pub name: String,
    /// Platform identifier: `windows`, `linux`, `macos`, `android`.
    pub platform: String,
    /// Self-signed end-entity certificate.
    pub cert_der: CertificateDer<'static>,
    /// Private key (PKCS#8) matching `cert_der`.
    pub key_der: PrivateKeyDer<'static>,
    cert_pem: String,
}

impl Clone for Identity {
    fn clone(&self) -> Self {
        Self {
            device_id: self.device_id.clone(),
            name: self.name.clone(),
            platform: self.platform.clone(),
            cert_der: self.cert_der.clone(),
            key_der: self.key_der.clone_key(),
            cert_pem: self.cert_pem.clone(),
        }
    }
}

impl std::fmt::Debug for Identity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never print key material.
        f.debug_struct("Identity")
            .field("device_id", &self.device_id)
            .field("name", &self.name)
            .field("platform", &self.platform)
            .field("cert_sha256", &self.cert_sha256_hex())
            .finish_non_exhaustive()
    }
}

/// Platform identifier for this build.
pub fn platform() -> String {
    if cfg!(target_os = "windows") {
        "windows".to_string()
    } else if cfg!(target_os = "linux") {
        "linux".to_string()
    } else if cfg!(target_os = "macos") {
        "macos".to_string()
    } else if cfg!(target_os = "android") {
        "android".to_string()
    } else {
        "unknown".to_string()
    }
}

impl Identity {
    /// Load the identity from `dir`, generating one on first run.
    pub fn load_or_create(dir: &Path, name: impl Into<String>) -> Result<Self> {
        ensure_dir(dir)?;
        let name = name.into();

        let device_id_path = dir.join(DEVICE_ID_FILE);
        let cert_der_path = dir.join(CERT_DER_FILE);
        let cert_pem_path = dir.join(CERT_PEM_FILE);
        let key_path = dir.join(KEY_FILE);

        let device_id = match std::fs::read_to_string(&device_id_path) {
            Ok(text) if !text.trim().is_empty() => text.trim().to_string(),
            _ => {
                let id = Uuid::new_v4().to_string();
                write_restricted(&device_id_path, id.as_bytes())?;
                id
            }
        };

        if cert_der_path.exists() && key_path.exists() {
            let cert_bytes = std::fs::read(&cert_der_path)?;
            let key_bytes = std::fs::read(&key_path)?;
            let cert_pem = std::fs::read_to_string(&cert_pem_path).unwrap_or_default();
            return Ok(Self {
                device_id,
                name,
                platform: platform(),
                cert_der: CertificateDer::from(cert_bytes),
                key_der: PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_bytes)),
                cert_pem,
            });
        }

        tracing::info!(device_id, "generating new device identity");
        let bundle = generate_certificate(&device_id)?;
        write_restricted(&key_path, bundle.key_der.secret_der())?;
        write_restricted(&cert_der_path, bundle.cert_der.as_ref())?;
        write_restricted(&cert_pem_path, bundle.cert_pem.as_bytes())?;

        Ok(Self {
            device_id,
            name,
            platform: platform(),
            cert_der: bundle.cert_der,
            key_der: bundle.key_der,
            cert_pem: bundle.cert_pem,
        })
    }

    /// SHA-256 fingerprint of the certificate, hex encoded.
    pub fn cert_sha256_hex(&self) -> String {
        hex::encode(self.cert_sha256())
    }

    /// SHA-256 fingerprint of the certificate.
    pub fn cert_sha256(&self) -> [u8; 32] {
        let mut hasher = Sha256::new();
        hasher.update(self.cert_der.as_ref());
        hasher.finalize().into()
    }

    /// Certificate in PEM form.
    pub fn cert_pem(&self) -> &str {
        &self.cert_pem
    }

    /// SHA-256 fingerprint of an arbitrary peer certificate, hex encoded.
    pub fn fingerprint_of(cert_der: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(cert_der);
        hex::encode(hasher.finalize())
    }

    /// Six-digit Short Authentication String for a pairing with `peer_cert_der`.
    ///
    /// Order-independent: both sides sort the two certificates by raw bytes,
    /// so both derive the same code. See `protocol/pairing.md`.
    pub fn sas_code(&self, peer_cert_der: &[u8]) -> String {
        sas_code_of(self.cert_der.as_ref(), peer_cert_der)
    }

    /// Identity as exchanged over the wire.
    pub fn to_device_info(&self) -> crate::protocol::DeviceInfo {
        crate::protocol::DeviceInfo {
            device_id: self.device_id.clone(),
            name: self.name.clone(),
            platform: self.platform.clone(),
            pv: crate::protocol::PROTOCOL_VERSION,
            features: crate::protocol::DeviceInfo::local_features(),
        }
    }

    /// Paths owned by the identity inside `dir`.
    pub fn paths(dir: &Path) -> Vec<PathBuf> {
        vec![
            dir.join(DEVICE_ID_FILE),
            dir.join(CERT_DER_FILE),
            dir.join(CERT_PEM_FILE),
            dir.join(KEY_FILE),
        ]
    }
}

/// Six-digit SAS over two certificates, order-independent.
///
/// `protocol/pairing.md` § "SAS verification code":
/// `SHA-256(min(der) || max(der))`, first 4 bytes big-endian `% 1_000_000`,
/// rendered as `"%03d %03d"`. Split out from [`Identity::sas_code`] so the
/// algorithm can be pinned against a fixed vector that the Android
/// implementation mirrors byte for byte.
pub fn sas_code_of(a: &[u8], b: &[u8]) -> String {
    let (first, second) = if a <= b { (a, b) } else { (b, a) };
    let mut hasher = Sha256::new();
    hasher.update(first);
    hasher.update(second);
    let digest = hasher.finalize();
    let value = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000;
    format!("{:03} {:03}", value / 1000, value % 1000)
}

struct CertBundle {
    cert_der: CertificateDer<'static>,
    key_der: PrivateKeyDer<'static>,
    cert_pem: String,
}

fn generate_certificate(device_id: &str) -> Result<CertBundle> {
    let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
        .map_err(|e| Error::Certificate(e.to_string()))?;

    let mut params = CertificateParams::new(Vec::<String>::new())
        .map_err(|e| Error::Certificate(e.to_string()))?;
    params
        .distinguished_name
        .push(DnType::CommonName, device_id.to_string());
    params.is_ca = IsCa::NoCa;
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    params.extended_key_usages = vec![
        ExtendedKeyUsagePurpose::ServerAuth,
        ExtendedKeyUsagePurpose::ClientAuth,
    ];

    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| Error::Certificate(e.to_string()))?;

    let cert_der = cert.der().clone();
    let cert_pem = cert.pem();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der()));

    Ok(CertBundle {
        cert_der,
        key_der,
        cert_pem,
    })
}

/// Write a file readable only by the current user.
fn write_restricted(path: &Path, bytes: &[u8]) -> Result<()> {
    std::fs::write(path, bytes)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let permissions = std::fs::Permissions::from_mode(0o600);
        std::fs::set_permissions(path, permissions)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_is_stable_across_loads() {
        let dir = tempfile::tempdir().unwrap();
        let first = Identity::load_or_create(dir.path(), "Test").unwrap();
        let second = Identity::load_or_create(dir.path(), "Renamed").unwrap();
        assert_eq!(first.device_id, second.device_id);
        assert_eq!(first.cert_sha256_hex(), second.cert_sha256_hex());
        assert_eq!(second.name, "Renamed");
    }

    #[test]
    fn sas_is_symmetric_and_six_digits() {
        let dir_a = tempfile::tempdir().unwrap();
        let dir_b = tempfile::tempdir().unwrap();
        let a = Identity::load_or_create(dir_a.path(), "A").unwrap();
        let b = Identity::load_or_create(dir_b.path(), "B").unwrap();

        let code_a = a.sas_code(b.cert_der.as_ref());
        let code_b = b.sas_code(a.cert_der.as_ref());
        assert_eq!(code_a, code_b);
        assert_eq!(code_a.len(), 7); // "482 913"
        assert!(code_a.chars().all(|c| c.is_ascii_digit() || c == ' '));
    }

    #[test]
    fn sas_differs_for_different_peers() {
        let dir_a = tempfile::tempdir().unwrap();
        let dir_b = tempfile::tempdir().unwrap();
        let dir_c = tempfile::tempdir().unwrap();
        let a = Identity::load_or_create(dir_a.path(), "A").unwrap();
        let b = Identity::load_or_create(dir_b.path(), "B").unwrap();
        let c = Identity::load_or_create(dir_c.path(), "C").unwrap();
        assert_ne!(
            a.sas_code(b.cert_der.as_ref()),
            a.sas_code(c.cert_der.as_ref())
        );
    }

    #[test]
    fn sas_golden_vector() {
        // Fixed vector mirrored byte-for-byte by the Android `SasTest`.
        // Includes bytes > 0x7f so a signed (rather than unsigned) byte
        // comparison in either implementation would produce a different
        // code; the arrays also differ in length to exercise the tiebreak.
        let a = [0x30u8, 0xff, 0x01, 0x80];
        let b = [0x30u8, 0x7f, 0x10, 0x80, 0x00];
        let code = sas_code_of(&a, &b);
        // Order-independent.
        assert_eq!(code, sas_code_of(&b, &a));
        assert_eq!(code.len(), 7);
        assert_eq!(code, "022 451");
    }
}
