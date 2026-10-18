//! Pairing: explicit, user-confirmed trust establishment.
//!
//! Implements `protocol/pairing.md` § "Pairing flow".
//!
//! ```text
//! Initiator                          Responder
//!    │──── pairing.request ─────────▶│
//!    │◀─── pairing.accept ───────────│  (after user accepts, code verified)
//!    │──── pairing.confirm ─────────▶│  (after user accepts, code verified)
//!    │◀─── pairing.complete ─────────│
//!    │  both pin the peer certificate│
//! ```
//!
//! Both sides show the same six-digit verification code before accepting, so
//! an active MITM (which would show different codes) is detectable by the user.

use std::time::Duration;

use serde_json::json;

use crate::error::{Error, Result};
use crate::node::Node;
use crate::protocol::DeviceInfo;
use crate::session::Session;
use crate::trust::TrustStore;

/// How long a pairing step may take before it is abandoned.
pub const PAIRING_TIMEOUT: Duration = Duration::from_secs(30);

/// Information shown to the user during pairing.
#[derive(Debug, Clone)]
pub struct PairingPrompt {
    /// The other device's identity.
    pub peer: DeviceInfo,
    /// Six-digit verification code, e.g. `482 913`.
    pub code: String,
    /// True when this device started the pairing.
    pub we_initiated: bool,
}

/// The user's answer to a pairing prompt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingDecision {
    /// Confirm the pairing.
    Accept,
    /// Decline the pairing.
    Reject,
}

/// Callback that presents a [`PairingPrompt`] and returns the user's decision.
///
/// Runs on a blocking thread, so implementations may block on user input.
pub type PromptFn = Box<dyn FnOnce(&PairingPrompt) -> PairingDecision + Send + 'static>;

/// Run a prompt callback off the async runtime.
async fn ask(prompt: PromptFn, prompt_data: PairingPrompt) -> Result<PairingDecision> {
    tokio::task::spawn_blocking(move || prompt(&prompt_data))
        .await
        .map_err(|e| Error::Protocol(format!("pairing prompt failed: {e}")))
}

/// Run the initiator side of the pairing flow.
///
/// Returns the same session, now trusted.
pub async fn pair_as_initiator(
    node: &Node,
    mut session: Session,
    prompt: PromptFn,
) -> Result<Session> {
    let ours = node.identity.to_device_info();
    session
        .send("pairing.request", serde_json::to_value(&ours)?)
        .await?;

    let reply = session.recv_timeout(PAIRING_TIMEOUT).await?;
    let peer: DeviceInfo = match reply.kind.as_str() {
        "pairing.accept" => reply.parse_payload()?,
        "pairing.reject" => return Err(Error::PairingRejected),
        "system.error" => return Err(reply.into()),
        other => return Err(Error::Protocol(format!("unexpected {other} while pairing"))),
    };

    let prompt_data = PairingPrompt {
        peer: peer.clone(),
        code: session.sas_code(&node.identity),
        we_initiated: true,
    };

    if ask(prompt, prompt_data).await? == PairingDecision::Reject {
        let _ = session.send("pairing.reject", json!({})).await;
        return Err(Error::PairingRejected);
    }

    session.send("pairing.confirm", json!({})).await?;
    let done = session.recv_timeout(PAIRING_TIMEOUT).await?;
    match done.kind.as_str() {
        "pairing.complete" => {}
        "pairing.reject" => return Err(Error::PairingRejected),
        "system.error" => return Err(done.into()),
        other => return Err(Error::Protocol(format!("unexpected {other} while pairing"))),
    }

    let record = TrustStore::record_for(&peer, &session.peer_cert_der, &session.peer_cert_pem);
    node.trust_device(record.clone()).await?;
    tracing::info!(
        device_id = %record.device_id,
        name = %record.name,
        "paired successfully (initiator)"
    );
    session.trusted = Some(record);
    Ok(session)
}

/// Run the responder side of the pairing flow.
///
/// Returns the same session, now trusted.
pub async fn pair_as_responder(
    node: &Node,
    mut session: Session,
    prompt: PromptFn,
) -> Result<Session> {
    let request = session.recv_timeout(PAIRING_TIMEOUT).await?;
    let peer: DeviceInfo = match request.kind.as_str() {
        "pairing.request" => request.parse_payload()?,
        "pairing.reject" => return Err(Error::PairingRejected),
        "system.error" => return Err(request.into()),
        other => return Err(Error::Protocol(format!("unexpected {other} while pairing"))),
    };

    let prompt_data = PairingPrompt {
        peer: peer.clone(),
        code: session.sas_code(&node.identity),
        we_initiated: false,
    };

    if ask(prompt, prompt_data).await? == PairingDecision::Reject {
        let _ = session.send("pairing.reject", json!({})).await;
        return Err(Error::PairingRejected);
    }

    let ours = node.identity.to_device_info();
    session
        .send("pairing.accept", serde_json::to_value(&ours)?)
        .await?;

    let reply = session.recv_timeout(PAIRING_TIMEOUT).await?;
    match reply.kind.as_str() {
        "pairing.confirm" => {}
        "pairing.reject" => return Err(Error::PairingRejected),
        "system.error" => return Err(reply.into()),
        other => return Err(Error::Protocol(format!("unexpected {other} while pairing"))),
    }

    let record = TrustStore::record_for(&peer, &session.peer_cert_der, &session.peer_cert_pem);
    node.trust_device(record.clone()).await?;
    session.send("pairing.complete", json!({})).await?;
    tracing::info!(
        device_id = %record.device_id,
        name = %record.name,
        "paired successfully (responder)"
    );
    session.trusted = Some(record);
    Ok(session)
}

/// Auto-accepting prompt, used by tests and non-interactive tooling.
pub fn auto_accept(code: &str) -> PromptFn {
    let code = code.to_string();
    Box::new(move |prompt: &PairingPrompt| {
        if prompt.code != code {
            tracing::warn!(
                expected = %code,
                actual = %prompt.code,
                "verification code mismatch; rejecting"
            );
            return PairingDecision::Reject;
        }
        PairingDecision::Accept
    })
}
