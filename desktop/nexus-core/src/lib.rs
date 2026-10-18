//! # nexus-core
//!
//! Platform-independent core for Nexus: local-first device discovery, pairing,
//! an authenticated encrypted channel, and the application protocol.
//!
//! The wire protocol lives in `protocol/` at the repository root and is the
//! source of truth for this crate, the Android client, and any future client.
//!
//! ## Layers
//!
//! ```text
//! Node          identity + trust + TLS + listener
//!   ├── Discovery   mDNS advertise/browse          (discovery.md)
//!   ├── Session     TLS 1.3, pinned certificates    (pairing.md)
//!   ├── Pairing     explicit user-confirmed trust   (pairing.md)
//!   └── Connection  hello + heartbeats + liveness   (connection.md)
//!         └── protocol  framing + envelope          (framing.md)
//! ```
//!
//! ## Example
//!
//! ```no_run
//! # async fn run() -> nexus_core::Result<()> {
//! use nexus_core::{Discovery, Node};
//!
//! // Bring up this device (identity, trust store, TLS listener).
//! let node = Node::bind(None, None, None).await?;
//!
//! // Advertise on mDNS and browse for peers.
//! let mut discovery = Discovery::start(node.advertisement())?;
//! while let Some(event) = discovery.next_event().await {
//!     tracing::info!(?event, "discovery event");
//! }
//! # Ok(())
//! # }
//! ```

pub mod config;
pub mod connection;
pub mod discovery;
pub mod error;
pub mod identity;
pub mod node;
pub mod pairing;
pub mod protocol;
pub mod session;
pub mod tls;
pub mod trust;

pub use connection::Connection;
pub use discovery::{Advertisement, Discovery, DiscoveryEvent, PeerInfo};
pub use error::{Error, Result};
pub use identity::Identity;
pub use node::Node;
pub use pairing::{
    auto_accept, pair_as_initiator, pair_as_responder, PairingDecision, PairingPrompt, PromptFn,
};
pub use protocol::{DeviceInfo, Message, PROTOCOL_VERSION};
pub use session::{Role, Session};
pub use trust::{TrustStore, TrustedDevice};

/// Version of the Nexus core, from the crate manifest.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
