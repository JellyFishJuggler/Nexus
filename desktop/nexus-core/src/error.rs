//! Error types shared across the Nexus core.
//!
//! User-facing messages follow the SRS §40 style: plain language, no error
//! codes. Technical detail lives in the `Display` of the inner error and in
//! structured logs.

use crate::protocol::Message;

/// Result alias used throughout the core.
pub type Result<T> = std::result::Result<T, Error>;

/// Errors produced by the Nexus core.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("couldn't reach the other device — check that both devices are on the same network")]
    Io(#[from] std::io::Error),

    #[error("the secure handshake failed: {0}")]
    Tls(#[from] rustls::Error),

    #[error("invalid certificate material: {0}")]
    Certificate(String),

    #[error("the other device sent something Nexus couldn't understand ({0})")]
    Protocol(String),

    #[error("{0}")]
    Config(String),

    #[error("device discovery failed: {0}")]
    Discovery(String),

    #[error("the other device isn't paired with this one")]
    NotPaired,

    #[error("pairing was rejected")]
    PairingRejected,

    #[error("pairing timed out — try again")]
    PairingTimeout,

    #[error("device identity mismatch — the peer's identity does not match the paired device")]
    IdentityMismatch,

    #[error("this device and the other device don't speak a compatible protocol version")]
    Incompatible,

    #[error("message encoding failed: {0}")]
    Encoding(#[from] serde_json::Error),
}

impl Error {
    /// True when the error means the peer is not (or no longer) usable and the
    /// connection should be torn down and retried later.
    pub fn is_connection_fatal(&self) -> bool {
        matches!(
            self,
            Error::Io(_)
                | Error::Tls(_)
                | Error::Protocol(_)
                | Error::IdentityMismatch
                | Error::Incompatible
        )
    }
}

impl From<Message> for Error {
    fn from(msg: Message) -> Self {
        let code = msg
            .payload
            .get("code")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown");
        let text = msg
            .payload
            .get("message")
            .and_then(|v| v.as_str())
            .unwrap_or("the other device reported an error");
        match code {
            "not_paired" => Error::NotPaired,
            "incompatible" => Error::Incompatible,
            _ => Error::Protocol(format!("{code}: {text}")),
        }
    }
}
