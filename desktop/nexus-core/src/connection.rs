//! A live, trusted connection: hello negotiation, heartbeats, liveness.
//!
//! Implements `protocol/connection.md`.

use std::time::{Duration, Instant};

use serde_json::json;
use tokio::sync::mpsc;
use tokio::time::{interval_at, Instant as TokioInstant, Interval, MissedTickBehavior};

use crate::error::{Error, Result};
use crate::identity::Identity;
use crate::protocol::{self, DeviceInfo, Message, PROTOCOL_VERSION};
use crate::session::Session;

/// Heartbeat send interval.
pub const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(15);

/// No inbound frame for this long ⇒ the connection is stale.
pub const STALE_AFTER: Duration = Duration::from_secs(60);

/// How long to wait for the peer's `system.hello`.
pub const HELLO_TIMEOUT: Duration = Duration::from_secs(15);

/// A trusted, application-ready connection to a peer.
///
/// The connection owns the socket. While [`Connection::next`] is running,
/// outgoing messages are handed over through the queue returned by
/// [`Connection::sender`], which keeps reads and writes from blocking each other.
pub struct Connection {
    /// The underlying encrypted session.
    pub session: Session,
    /// Peer identity as reported in `system.hello` and verified against the pin.
    pub peer: DeviceInfo,
    ping_interval: Interval,
    last_rx: Instant,
    outbound_rx: mpsc::UnboundedReceiver<Message>,
    outbound_tx: mpsc::UnboundedSender<Message>,
}

impl Connection {
    /// Exchange `system.hello` and validate the peer.
    ///
    /// Both sides send hello concurrently, so this can be called by the
    /// initiator and the responder alike.
    pub async fn new(mut session: Session, identity: &Identity) -> Result<Self> {
        let ours = identity.to_device_info();
        session
            .send("system.hello", serde_json::to_value(&ours)?)
            .await?;

        let (peer, last_rx) = loop {
            let message = session.recv_timeout(HELLO_TIMEOUT).await?;
            let received_at = Instant::now();
            match message.kind.as_str() {
                "system.hello" => {
                    let info: DeviceInfo = message.parse_payload()?;
                    if info.pv != PROTOCOL_VERSION {
                        let _ = session
                            .send(
                                "system.incompatible",
                                json!({
                                    "ours": PROTOCOL_VERSION,
                                    "theirs": info.pv,
                                }),
                            )
                            .await;
                        return Err(Error::Incompatible);
                    }
                    break (info, received_at);
                }
                "system.incompatible" => return Err(Error::Incompatible),
                "system.error" => return Err(message.into()),
                "system.ping" => {
                    session
                        .send("system.pong", json!({ "ts": message.ts }))
                        .await?;
                }
                "system.pong" => {}
                "pairing.request" | "pairing.accept" | "pairing.confirm" => {
                    let _ = session
                        .send(
                            "system.error",
                            json!({ "code": "busy", "message": "already paired with this device" }),
                        )
                        .await;
                    return Err(Error::Protocol("unexpected pairing message".into()));
                }
                other => {
                    tracing::debug!(kind = other, "unknown message type");
                    session
                        .send(
                            "system.error",
                            json!({ "code": "unknown_type", "message": format!("unsupported message type: {other}") }),
                        )
                        .await?;
                }
            }
        };

        // A pinned device must not present a different identity.
        if let Some(pinned) = &session.trusted {
            if pinned.device_id != peer.device_id {
                tracing::warn!(
                    pinned = %pinned.device_id,
                    claimed = %peer.device_id,
                    "peer identity does not match its pinned certificate"
                );
                return Err(Error::IdentityMismatch);
            }
        }

        tracing::info!(
            device_id = %peer.device_id,
            name = %peer.name,
            platform = %peer.platform,
            "connection established"
        );

        let mut ping_interval =
            interval_at(TokioInstant::now() + HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL);
        ping_interval.set_missed_tick_behavior(MissedTickBehavior::Delay);

        let (outbound_tx, outbound_rx) = mpsc::unbounded_channel();

        Ok(Self {
            session,
            peer,
            ping_interval,
            last_rx,
            outbound_rx,
            outbound_tx,
        })
    }

    /// A handle for queueing outgoing messages while [`Connection::next`] runs.
    pub fn sender(&self) -> mpsc::UnboundedSender<Message> {
        self.outbound_tx.clone()
    }

    /// Whether the peer certificate is pinned.
    pub fn is_trusted(&self) -> bool {
        self.session.is_trusted()
    }

    /// Send an application message immediately.
    ///
    /// Only for use outside the [`Connection::next`] loop; during the loop, use
    /// [`Connection::sender`] so writes are serialized with heartbeats.
    pub async fn send(
        &mut self,
        kind: impl Into<String>,
        payload: serde_json::Value,
    ) -> Result<()> {
        self.session.send(kind, payload).await
    }

    /// Wait for the next application message.
    ///
    /// Heartbeats are handled transparently: pings are answered, pongs are
    /// swallowed, queued outbound messages are written, and the connection is
    /// declared stale when nothing arrives within [`STALE_AFTER`].
    pub async fn next(&mut self) -> Result<Option<Message>> {
        loop {
            // Split borrows so read, write, heartbeat, and the outbound queue
            // can coexist: these are disjoint fields.
            let Session { reader, writer, .. } = &mut self.session;
            tokio::select! {
                biased;

                result = protocol::read_message(reader) => {
                    match result? {
                        None => return Ok(None),
                        Some(message) => {
                            self.last_rx = Instant::now();
                            match message.kind.as_str() {
                                "system.ping" => {
                                    protocol::write_message(
                                        writer,
                                        &Message::new("system.pong", json!({ "ts": message.ts })),
                                    )
                                    .await?;
                                }
                                "system.pong" => {}
                                "system.error" => return Err(message.into()),
                                _ => return Ok(Some(message)),
                            }
                        }
                    }
                }

                queued = self.outbound_rx.recv() => {
                    if let Some(message) = queued {
                        protocol::write_message(writer, &message).await?;
                    }
                }

                _ = self.ping_interval.tick() => {
                    if self.last_rx.elapsed() > STALE_AFTER {
                        tracing::warn!("connection went stale (no traffic from peer)");
                        return Err(Error::Protocol("the connection timed out".into()));
                    }
                    protocol::write_message(
                        writer,
                        &Message::new("system.ping", json!({ "ts": protocol::now_secs() })),
                    )
                    .await?;
                }
            }
        }
    }
}
