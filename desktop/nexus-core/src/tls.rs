//! TLS 1.3 secure channel with pinned per-device certificates.
//!
//! Implements `protocol/pairing.md` § "TLS profile".
//!
//! Chain validity is deliberately **not** verified: certificates are
//! self-signed by design. Authentication comes from certificate pinning (the
//! trust store) plus the pairing verification code. This module therefore
//! installs permissive verifiers and exposes the peer certificate so the
//! application layer can enforce pinning itself.

use std::sync::{Arc, OnceLock};

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{DigitallySignedStruct, DistinguishedName, SignatureScheme};
use tokio::net::TcpStream;
use tokio_rustls::{client, server, TlsAcceptor, TlsConnector};

use crate::error::{Error, Result};
use crate::identity::Identity;
use crate::protocol::ALPN;

/// Server name sent during the handshake. Nexus devices have no stable DNS
/// names and never verify hostnames, so this is a fixed placeholder.
const PLACEHOLDER_SERVER_NAME: &str = "nexus.device";

/// Install the process-wide rustls crypto provider exactly once.
pub fn install_crypto_provider() {
    static INSTALLED: OnceLock<()> = OnceLock::new();
    INSTALLED.get_or_init(|| {
        // Ignore the error: another component may have installed a provider.
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

fn provider() -> Arc<CryptoProvider> {
    install_crypto_provider();
    Arc::new(rustls::crypto::ring::default_provider())
}

/// Ready-to-use TLS configurations for both connection roles.
#[derive(Clone)]
pub struct TlsSetup {
    /// Server configuration (this device accepts connections).
    pub server: Arc<rustls::ServerConfig>,
    /// Client configuration (this device initiates connections).
    pub client: Arc<rustls::ClientConfig>,
}

impl TlsSetup {
    /// Build TLS configurations around this device's identity.
    pub fn build(identity: &Identity) -> Result<Self> {
        let provider = provider();
        let certs = vec![identity.cert_der.clone()];
        let key = identity.key_der.clone_key();

        let mut server = rustls::ServerConfig::builder_with_provider(provider.clone())
            .with_protocol_versions(&[&rustls::version::TLS13])?
            .with_client_cert_verifier(Arc::new(AcceptAnyClientCert::new(provider.clone())))
            .with_single_cert(certs.clone(), key.clone_key())?;
        server.alpn_protocols = vec![ALPN.to_vec()];

        let mut client = rustls::ClientConfig::builder_with_provider(provider.clone())
            .with_protocol_versions(&[&rustls::version::TLS13])?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(AcceptAnyServerCert::new(provider)))
            .with_client_auth_cert(certs, key)?;
        client.alpn_protocols = vec![ALPN.to_vec()];

        Ok(Self {
            server: Arc::new(server),
            client: Arc::new(client),
        })
    }

    /// Accept a TLS connection from a peer.
    pub async fn accept(&self, stream: TcpStream) -> Result<server::TlsStream<TcpStream>> {
        let stream = TlsAcceptor::from(self.server.clone())
            .accept(stream)
            .await?;
        require_alpn(stream.get_ref().1.alpn_protocol())?;
        Ok(stream)
    }

    /// Establish a TLS connection to a peer.
    pub async fn connect(&self, stream: TcpStream) -> Result<client::TlsStream<TcpStream>> {
        let name = ServerName::try_from(PLACEHOLDER_SERVER_NAME)
            .map_err(|e| Error::Certificate(e.to_string()))?;
        let stream = TlsConnector::from(self.client.clone())
            .connect(name, stream)
            .await?;
        require_alpn(stream.get_ref().1.alpn_protocol())?;
        Ok(stream)
    }
}

fn require_alpn(negotiated: Option<&[u8]>) -> Result<()> {
    match negotiated {
        Some(value) if value == ALPN => Ok(()),
        Some(other) => Err(Error::Protocol(format!(
            "peer negotiated an unsupported protocol ({})",
            String::from_utf8_lossy(other)
        ))),
        None => Err(Error::Protocol(
            "peer did not negotiate the Nexus protocol".into(),
        )),
    }
}

/// Peer certificate presented by a server-side TLS stream.
pub fn server_peer_cert(stream: &server::TlsStream<TcpStream>) -> Option<Vec<u8>> {
    stream
        .get_ref()
        .1
        .peer_certificates()
        .and_then(|certs| certs.first())
        .map(|cert| cert.as_ref().to_vec())
}

/// Peer certificate presented by a client-side TLS stream.
pub fn client_peer_cert(stream: &client::TlsStream<TcpStream>) -> Option<Vec<u8>> {
    stream
        .get_ref()
        .1
        .peer_certificates()
        .and_then(|certs| certs.first())
        .map(|cert| cert.as_ref().to_vec())
}

/// Encode DER bytes as PEM for storage in the trust store.
pub fn der_to_pem(der: &[u8]) -> String {
    use std::fmt::Write;
    let b64 = base64_encode(der);
    let mut out = String::from("-----BEGIN CERTIFICATE-----\n");
    for chunk in b64.as_bytes().chunks(64) {
        let _ = writeln!(out, "{}", std::str::from_utf8(chunk).unwrap_or(""));
    }
    out.push_str("-----END CERTIFICATE-----\n");
    out
}

/// Minimal standard-alphabet base64 encoder (no padding-free variants used).
fn base64_encode(input: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(ALPHABET[((triple >> 18) & 0x3f) as usize] as char);
        out.push(ALPHABET[((triple >> 12) & 0x3f) as usize] as char);
        if chunk.len() > 1 {
            out.push(ALPHABET[((triple >> 6) & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(ALPHABET[(triple & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

/// Accepts any client certificate at the TLS layer.
///
/// Trust is enforced afterwards by comparing the presented certificate with the
/// pinned entry in the trust store (see [`crate::connection`]).
#[derive(Debug)]
struct AcceptAnyClientCert {
    provider: Arc<CryptoProvider>,
}

impl AcceptAnyClientCert {
    fn new(provider: Arc<CryptoProvider>) -> Self {
        Self { provider }
    }
}

impl ClientCertVerifier for AcceptAnyClientCert {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn verify_client_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> std::result::Result<ClientCertVerified, rustls::Error> {
        Ok(ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Accepts any server certificate at the TLS layer; pinning happens after the
/// handshake, in [`crate::connection`].
#[derive(Debug)]
struct AcceptAnyServerCert {
    provider: Arc<CryptoProvider>,
}

impl AcceptAnyServerCert {
    fn new(provider: Arc<CryptoProvider>) -> Self {
        Self { provider }
    }
}

impl ServerCertVerifier for AcceptAnyServerCert {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pem_round_trips_through_rustls_pemfile() {
        let der = vec![0x30, 0x01, 0x02, 0x03, 0xff, 0x00, 0x10];
        let pem = der_to_pem(&der);
        assert!(pem.starts_with("-----BEGIN CERTIFICATE-----"));
        let parsed: Vec<Vec<u8>> = rustls_pemfile::certs(&mut pem.as_bytes())
            .map(|c| c.unwrap().as_ref().to_vec())
            .collect();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0], der);
    }
}
