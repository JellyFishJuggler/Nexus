//! The local node: identity, trust store, TLS configuration, and listener.
//!
//! A [`Node`] is everything a process needs to participate in Nexus: it owns
//! the device identity, the pinned trust store, and the TCP/TLS listener that
//! peers connect to.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use tokio::net::{TcpListener, TcpStream};
use tokio::sync::RwLock;

use crate::config::{self, Settings};
use crate::discovery::Advertisement;
use crate::error::{Error, Result};
use crate::identity::Identity;
use crate::session::Session;
use crate::tls::TlsSetup;
use crate::trust::TrustStore;

/// A running Nexus node.
pub struct Node {
    /// This device's cryptographic identity.
    pub identity: Identity,
    /// Directory holding identity, settings, and trust store.
    pub data_dir: PathBuf,
    /// Pinned peer certificates.
    pub trust: Arc<RwLock<TrustStore>>,
    /// TLS configurations for both roles.
    pub tls: TlsSetup,
    listener: TcpListener,
    port: u16,
    settings: Settings,
}

impl Node {
    /// Bind a node, loading (or creating) identity and trust from disk.
    ///
    /// `data_dir` defaults to the platform config directory, `name` to the
    /// stored settings, and `port` to the stored preferred port (`0` =
    /// ephemeral). An ephemeral port is the norm: the real port is always
    /// advertised over mDNS.
    pub async fn bind(
        data_dir: Option<PathBuf>,
        name: Option<String>,
        port: Option<u16>,
    ) -> Result<Self> {
        let dir = match data_dir {
            Some(dir) => dir,
            None => config::default_data_dir()?,
        };
        config::ensure_dir(&dir)?;

        let mut settings = Settings::load(&dir);
        if let Some(name) = name {
            settings.device_name = name;
            if let Err(e) = settings.save(&dir) {
                tracing::warn!("couldn't persist device name: {e}");
            }
        }

        let identity = Identity::load_or_create(&dir, settings.device_name.clone())?;
        let trust = TrustStore::load(&dir);
        let tls = TlsSetup::build(&identity)?;

        let hint = port.unwrap_or(settings.preferred_port);
        let listener = bind_listener(hint, port.is_some()).await?;
        let port = listener.local_addr()?.port();

        tracing::info!(
            device_id = %identity.device_id,
            name = %identity.name,
            port,
            trusted_devices = trust.devices.len(),
            "node ready"
        );

        Ok(Self {
            identity,
            data_dir: dir,
            trust: Arc::new(RwLock::new(trust)),
            tls,
            listener,
            port,
            settings,
        })
    }

    /// TCP port this node listens on.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Local address of the listener.
    pub fn local_addr(&self) -> Result<SocketAddr> {
        Ok(self.listener.local_addr()?)
    }

    /// Effective settings.
    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    /// mDNS advertisement describing this node.
    pub fn advertisement(&self) -> Advertisement {
        Advertisement {
            device_id: self.identity.device_id.clone(),
            name: self.identity.name.clone(),
            platform: self.identity.platform.clone(),
            port: self.port,
            protocol_version: crate::protocol::PROTOCOL_VERSION,
        }
    }

    /// Accept the next inbound TCP connection.
    pub async fn accept_tcp(&self) -> Result<(TcpStream, SocketAddr)> {
        let (stream, addr) = self.listener.accept().await?;
        // Latency matters more than throughput for control traffic.
        let _ = stream.set_nodelay(true);
        Ok((stream, addr))
    }

    /// Accept and complete a TLS handshake with the next inbound peer.
    pub async fn accept_tls(&self) -> Result<(Session, SocketAddr)> {
        let (stream, addr) = self.accept_tcp().await?;
        let tls_stream = self.tls.accept(stream).await?;
        let trust = self.trust.read().await;
        let session = Session::from_accepted(tls_stream, &trust)?;
        tracing::debug!(
            peer = %addr,
            fingerprint = %session.peer_cert_sha256,
            trusted = session.is_trusted(),
            "accepted encrypted connection"
        );
        Ok((session, addr))
    }

    /// Connect to a peer address and complete a TLS handshake.
    pub async fn connect(&self, addr: SocketAddr) -> Result<Session> {
        let stream = TcpStream::connect(addr).await?;
        let _ = stream.set_nodelay(true);
        let tls_stream = self.tls.connect(stream).await?;
        let trust = self.trust.read().await;
        let session = Session::from_initiated(tls_stream, &trust)?;
        tracing::debug!(
            peer = %addr,
            fingerprint = %session.peer_cert_sha256,
            trusted = session.is_trusted(),
            "established encrypted connection"
        );
        Ok(session)
    }

    /// Persist the current trust store.
    pub async fn save_trust(&self) -> Result<()> {
        let store = self.trust.read().await;
        store.save(&self.data_dir)
    }

    /// Add a device to the trust store and persist it.
    pub async fn trust_device(&self, device: crate::trust::TrustedDevice) -> Result<()> {
        {
            let mut store = self.trust.write().await;
            store.insert(device);
        }
        self.save_trust().await
    }

    /// Remove a device from the trust store and persist the change.
    pub async fn untrust_device(&self, device_id: &str) -> Result<bool> {
        let removed = {
            let mut store = self.trust.write().await;
            store.remove(device_id)
        };
        if removed {
            self.save_trust().await?;
        }
        Ok(removed)
    }
}

/// Bind the listener, optionally falling back to an ephemeral port.
///
/// When the caller explicitly asked for a port, a bind failure is an error;
/// otherwise a busy preferred port falls back to ephemeral.
async fn bind_listener(hint: u16, explicit: bool) -> Result<TcpListener> {
    match bind_any(hint) {
        Ok(listener) => Ok(TcpListener::from_std(listener)?),
        Err(e) => {
            if explicit || hint == 0 {
                return Err(Error::Config(format!(
                    "couldn't listen on port {hint}: {e}"
                )));
            }
            tracing::warn!("preferred port {hint} unavailable ({e}); using an ephemeral port");
            Ok(TcpListener::from_std(bind_any(0)?)?)
        }
    }
}

/// Bind a dual-stack listener on `[::]:port` so both IPv4 and IPv6 peers can
/// connect (mDNS commonly resolves peers to IPv6 addresses), falling back to
/// IPv4-only `0.0.0.0` when the platform has no usable IPv6 stack.
fn bind_any(port: u16) -> std::io::Result<std::net::TcpListener> {
    use socket2::{Domain, Protocol, Socket, Type};

    let attempt = || -> std::io::Result<std::net::TcpListener> {
        let socket = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP))?;
        socket.set_only_v6(false)?;
        socket.bind(&std::net::SocketAddr::from((std::net::Ipv6Addr::UNSPECIFIED, port)).into())?;
        socket.listen(128)?;
        socket.set_nonblocking(true)?;
        Ok(socket.into())
    };

    match attempt() {
        Ok(listener) => Ok(listener),
        Err(e) => {
            tracing::debug!("dual-stack bind failed ({e}); falling back to IPv4-only");
            let listener = std::net::TcpListener::bind(("0.0.0.0", port))?;
            listener.set_nonblocking(true)?;
            Ok(listener)
        }
    }
}
