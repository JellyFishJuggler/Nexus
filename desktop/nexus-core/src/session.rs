//! An established, encrypted session with one peer.
//!
//! A [`Session`] owns the TLS stream, split into independent read and write
//! halves so heartbeats and application traffic never block each other.

use tokio::io::{split, AsyncRead, AsyncWrite, ReadHalf, WriteHalf};
use tokio::net::TcpStream;
use tokio_rustls::{client, server};
use uuid::Uuid;

use crate::error::{Error, Result};
use crate::identity::Identity;
use crate::protocol::{self, Frame, Message};
use crate::tls;
use crate::trust::{TrustStore, TrustedDevice};

/// Object-safe combination of the I/O traits used by the TLS stream.
pub trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

/// Which side of the TCP connection this session is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// We accepted the connection (we are the TLS server).
    Acceptor,
    /// We initiated the connection (we are the TLS client).
    Initiator,
}

/// An encrypted session with a peer.
pub struct Session {
    pub(crate) reader: ReadHalf<Box<dyn AsyncStream>>,
    pub(crate) writer: WriteHalf<Box<dyn AsyncStream>>,
    /// Peer certificate (DER) as presented during the handshake.
    pub peer_cert_der: Vec<u8>,
    /// SHA-256 fingerprint of `peer_cert_der`, hex encoded.
    pub peer_cert_sha256: String,
    /// Peer certificate in PEM form (for the trust store).
    pub peer_cert_pem: String,
    /// Pinned trust record when the peer certificate matches the trust store.
    pub trusted: Option<TrustedDevice>,
    /// Connection role.
    pub role: Role,
}

impl Session {
    /// Wrap an accepted (server-side) TLS stream.
    pub fn from_accepted(stream: server::TlsStream<TcpStream>, trust: &TrustStore) -> Result<Self> {
        let cert_der = tls::server_peer_cert(&stream).ok_or_else(|| {
            Error::Protocol("the other device did not present a certificate".into())
        })?;
        Self::finish(Box::new(stream), cert_der, trust, Role::Acceptor)
    }

    /// Wrap an initiated (client-side) TLS stream.
    ///
    /// Unlike the acceptor path, the pinned certificate is verified here: a
    /// trusted reconnection must present exactly the certificate we pinned
    /// during pairing (defends against MITM and rebuilt devices).
    pub fn from_initiated(
        stream: client::TlsStream<TcpStream>,
        trust: &TrustStore,
    ) -> Result<Self> {
        let cert_der = tls::client_peer_cert(&stream).ok_or_else(|| {
            Error::Protocol("the other device did not present a certificate".into())
        })?;
        let fingerprint = Identity::fingerprint_of(&cert_der);
        if let Some(candidate) = trust
            .devices
            .iter()
            .find(|d| d.cert_der().map(|der| der == cert_der).unwrap_or(false))
        {
            tracing::debug!(device_id = %candidate.device_id, "peer certificate matches pin");
        }
        if trust.by_fingerprint(&fingerprint).is_none() && !trust.is_empty() {
            // Not necessarily fatal: the peer may be a new device awaiting
            // pairing. The caller decides. Log only.
            tracing::debug!(fingerprint, "peer certificate is not pinned");
        }
        Self::finish(Box::new(stream), cert_der, trust, Role::Initiator)
    }

    fn finish(
        stream: Box<dyn AsyncStream>,
        cert_der: Vec<u8>,
        trust: &TrustStore,
        role: Role,
    ) -> Result<Self> {
        let fingerprint = Identity::fingerprint_of(&cert_der);
        let trusted = trust.by_fingerprint(&fingerprint).cloned();
        let (reader, writer) = split(stream);
        Ok(Self {
            reader,
            writer,
            peer_cert_pem: tls::der_to_pem(&cert_der),
            peer_cert_sha256: fingerprint,
            peer_cert_der: cert_der,
            trusted,
            role,
        })
    }

    /// Whether the peer's certificate is pinned in the trust store.
    pub fn is_trusted(&self) -> bool {
        self.trusted.is_some()
    }

    /// Pairing verification code for this session (see `protocol/pairing.md`).
    pub fn sas_code(&self, identity: &Identity) -> String {
        identity.sas_code(&self.peer_cert_der)
    }

    /// Send a control message.
    pub async fn send(
        &mut self,
        kind: impl Into<String>,
        payload: serde_json::Value,
    ) -> Result<()> {
        protocol::write_message(&mut self.writer, &Message::new(kind, payload)).await
    }

    /// Send a binary file chunk.
    pub async fn send_chunk(&mut self, transfer_id: Uuid, chunk: &[u8]) -> Result<()> {
        protocol::write_frame(
            &mut self.writer,
            &Frame::Data {
                transfer_id,
                chunk: chunk.to_vec(),
            },
        )
        .await
    }

    /// Read the next frame. `Ok(None)` means the peer closed cleanly.
    pub async fn recv_frame(&mut self) -> Result<Option<Frame>> {
        protocol::read_frame(&mut self.reader).await
    }

    /// Read the next control message. Binary frames are rejected.
    pub async fn recv(&mut self) -> Result<Option<Message>> {
        protocol::read_message(&mut self.reader).await
    }

    /// Read the next control message, failing if nothing arrives in time.
    pub async fn recv_timeout(&mut self, duration: std::time::Duration) -> Result<Message> {
        match tokio::time::timeout(duration, self.recv()).await {
            Ok(Ok(Some(message))) => Ok(message),
            Ok(Ok(None)) => Err(Error::Protocol(
                "the other device closed the connection".into(),
            )),
            Ok(Err(e)) => Err(e),
            Err(_) => Err(Error::PairingTimeout),
        }
    }
}
