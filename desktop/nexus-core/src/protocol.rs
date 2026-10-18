//! Wire protocol: framing, envelope, and frame I/O.
//!
//! Implements `protocol/framing.md`.
//!
//! ```text
//! ┌────────────┬───────────┬──────────────────────┐
//! │ length u32 │ frame u8  │ body                 │
//! └────────────┴───────────┴──────────────────────┘
//! ```

use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use uuid::Uuid;

use crate::error::{Error, Result};

/// Protocol version implemented by this build.
pub const PROTOCOL_VERSION: u32 = 1;

/// ALPN protocol identifier for the Nexus secure channel.
pub const ALPN: &[u8] = b"nexus/1";

/// Maximum total frame length (type byte + body).
pub const MAX_FRAME_LEN: u32 = 1_048_576;

/// Maximum JSON control message body.
pub const MAX_CONTROL_BODY: usize = 65_536;

/// Maximum size of a single binary file chunk.
pub const MAX_CHUNK: usize = 65_536;

/// Frame type: JSON control message.
pub const FRAME_CONTROL: u8 = 0x01;

/// Frame type: binary file chunk.
pub const FRAME_DATA: u8 = 0x02;

/// Current unix time in whole seconds.
pub fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Identity information exchanged by peers (hello and pairing messages).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeviceInfo {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    pub pv: u32,
    #[serde(default)]
    pub features: Vec<String>,
}

impl DeviceInfo {
    /// Feature identifiers supported by this build.
    pub fn local_features() -> Vec<String> {
        // Grows as milestones land. Kept accurate on purpose: peers use this
        // to decide which UI affordances to offer.
        vec![
            "clipboard".to_string(),
            "transfer".to_string(),
            "media".to_string(),
        ]
    }

    /// Whether the peer advertises a feature.
    pub fn supports(&self, feature: &str) -> bool {
        self.features.iter().any(|f| f == feature)
    }
}

/// A decoded control message.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    /// Protocol version of the sender.
    pub v: u32,
    /// Dotted message type, e.g. `system.hello`.
    #[serde(rename = "type")]
    pub kind: String,
    /// Unique message id (UUID) for correlation and dedup.
    pub id: String,
    /// Sender clock, unix seconds.
    pub ts: i64,
    /// Type-specific payload; `{}` when empty.
    pub payload: Value,
}

impl Message {
    /// Build a new message with a fresh id and current timestamp.
    pub fn new(kind: impl Into<String>, payload: Value) -> Self {
        Self {
            v: PROTOCOL_VERSION,
            kind: kind.into(),
            id: Uuid::new_v4().to_string(),
            ts: now_secs(),
            payload,
        }
    }

    /// Build a message with no payload.
    pub fn unit(kind: impl Into<String>) -> Self {
        Self::new(kind, Value::Object(serde_json::Map::new()))
    }

    /// Build a `system.error` reply.
    pub fn error(code: &str, message: impl Into<String>) -> Self {
        Self::new(
            "system.error",
            serde_json::json!({ "code": code, "message": message.into() }),
        )
    }

    /// Look up a string field in the payload.
    pub fn str_field(&self, key: &str) -> Option<&str> {
        self.payload.get(key).and_then(|v| v.as_str())
    }

    /// Look up an integer field in the payload.
    pub fn i64_field(&self, key: &str) -> Option<i64> {
        self.payload.get(key).and_then(|v| v.as_i64())
    }

    /// Deserialize the payload into a typed value.
    pub fn parse_payload<T: for<'de> Deserialize<'de>>(&self) -> Result<T> {
        Ok(serde_json::from_value(self.payload.clone())?)
    }
}

/// A frame read from or destined for the wire.
#[derive(Debug, Clone)]
pub enum Frame {
    /// JSON control message.
    Control(Box<Message>),
    /// Binary file chunk for an in-flight transfer.
    Data { transfer_id: Uuid, chunk: Vec<u8> },
}

impl Frame {
    /// The numeric frame type tag.
    pub fn type_byte(&self) -> u8 {
        match self {
            Frame::Control(_) => FRAME_CONTROL,
            Frame::Data { .. } => FRAME_DATA,
        }
    }
}

/// Encode and write one frame, flushing it.
pub async fn write_frame<W>(writer: &mut W, frame: &Frame) -> Result<()>
where
    W: AsyncWrite + Unpin,
{
    let body: Vec<u8> = match frame {
        Frame::Control(message) => {
            let json = serde_json::to_vec(message)?;
            if json.len() > MAX_CONTROL_BODY {
                return Err(Error::Protocol("control message too large".into()));
            }
            json
        }
        Frame::Data { transfer_id, chunk } => {
            if chunk.len() > MAX_CHUNK {
                return Err(Error::Protocol("file chunk too large".into()));
            }
            let mut body = Vec::with_capacity(16 + chunk.len());
            body.extend_from_slice(transfer_id.as_bytes());
            body.extend_from_slice(chunk);
            body
        }
    };

    let len = (body.len() + 1) as u32; // + frame type byte
    if len > MAX_FRAME_LEN {
        return Err(Error::Protocol("frame too large".into()));
    }

    writer.write_all(&len.to_be_bytes()).await?;
    writer.write_all(&[frame.type_byte()]).await?;
    writer.write_all(&body).await?;
    writer.flush().await?;
    Ok(())
}

/// Convenience wrapper: write a control message.
pub async fn write_message<W>(writer: &mut W, message: &Message) -> Result<()>
where
    W: AsyncWrite + Unpin,
{
    write_frame(writer, &Frame::Control(Box::new(message.clone()))).await
}

/// Read one frame.
///
/// Returns `Ok(None)` on a clean end-of-stream at a frame boundary.
/// Returns `Err(Error::Protocol(..))` for malformed or oversized frames —
/// callers MUST treat that as fatal and close the connection.
pub async fn read_frame<R>(reader: &mut R) -> Result<Option<Frame>>
where
    R: AsyncRead + Unpin,
{
    // Peek the first length byte; a clean EOF here means the peer closed.
    let mut first = [0u8; 1];
    if reader.read(&mut first).await? == 0 {
        return Ok(None);
    }

    let mut len_bytes = [0u8; 4];
    len_bytes[0] = first[0];
    reader.read_exact(&mut len_bytes[1..]).await?;
    let len = u32::from_be_bytes(len_bytes);

    if len == 0 || len > MAX_FRAME_LEN {
        return Err(Error::Protocol(format!("invalid frame length {len}")));
    }

    let mut type_byte = [0u8; 1];
    reader.read_exact(&mut type_byte).await?;

    let body_len = (len - 1) as usize;
    let mut body = vec![0u8; body_len];
    if body_len > 0 {
        reader.read_exact(&mut body).await?;
    }

    match type_byte[0] {
        FRAME_CONTROL => {
            if body.len() > MAX_CONTROL_BODY {
                return Err(Error::Protocol("control message too large".into()));
            }
            let message: Message = serde_json::from_slice(&body)?;
            Ok(Some(Frame::Control(Box::new(message))))
        }
        FRAME_DATA => {
            if body.len() < 16 {
                return Err(Error::Protocol("data frame missing transfer id".into()));
            }
            let transfer_id = Uuid::from_slice(&body[..16])
                .map_err(|e| Error::Protocol(format!("bad transfer id: {e}")))?;
            let chunk = body[16..].to_vec();
            if chunk.len() > MAX_CHUNK {
                return Err(Error::Protocol("file chunk too large".into()));
            }
            Ok(Some(Frame::Data { transfer_id, chunk }))
        }
        other => Err(Error::Protocol(format!("unknown frame type 0x{other:02x}"))),
    }
}

/// Read one frame and require it to be a control message.
///
/// Used by the pairing and session layers, where binary frames are not legal
/// before a transfer has been accepted.
pub async fn read_message<R>(reader: &mut R) -> Result<Option<Message>>
where
    R: AsyncRead + Unpin,
{
    match read_frame(reader).await? {
        None => Ok(None),
        Some(Frame::Control(message)) => Ok(Some(*message)),
        Some(Frame::Data { .. }) => Err(Error::Protocol(
            "unexpected binary frame outside a transfer".into(),
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    #[tokio::test]
    async fn control_round_trip() {
        let (mut a, mut b) = duplex(4096);
        let message = Message::new("system.hello", serde_json::json!({ "name": "test" }));
        write_message(&mut a, &message).await.unwrap();
        let read = read_message(&mut b).await.unwrap().unwrap();
        assert_eq!(read.kind, "system.hello");
        assert_eq!(read.str_field("name"), Some("test"));
        assert_eq!(read.v, PROTOCOL_VERSION);
    }

    #[tokio::test]
    async fn data_round_trip() {
        let (mut a, mut b) = duplex(4096);
        let id = Uuid::new_v4();
        let chunk = vec![7u8; 1024];
        write_frame(
            &mut a,
            &Frame::Data {
                transfer_id: id,
                chunk: chunk.clone(),
            },
        )
        .await
        .unwrap();
        match read_frame(&mut b).await.unwrap().unwrap() {
            Frame::Data {
                transfer_id,
                chunk: read,
            } => {
                assert_eq!(transfer_id, id);
                assert_eq!(read, chunk);
            }
            other => panic!("expected data frame, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn clean_eof_is_none() {
        let (a, mut b) = duplex(64);
        drop(a);
        assert!(read_message(&mut b).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn oversized_length_rejected() {
        let (mut a, mut b) = duplex(64);
        a.write_all(&(MAX_FRAME_LEN + 1).to_be_bytes())
            .await
            .unwrap();
        a.flush().await.unwrap();
        let err = read_message(&mut b).await.unwrap_err();
        assert!(matches!(err, Error::Protocol(_)), "got {err:?}");
    }

    #[tokio::test]
    async fn unknown_frame_type_rejected() {
        let (mut a, mut b) = duplex(64);
        a.write_all(&2u32.to_be_bytes()).await.unwrap();
        a.write_all(&[0x7f]).await.unwrap();
        a.write_all(&[0x00]).await.unwrap();
        a.flush().await.unwrap();
        let err = read_message(&mut b).await.unwrap_err();
        assert!(matches!(err, Error::Protocol(_)), "got {err:?}");
    }

    #[tokio::test]
    async fn zero_length_rejected() {
        let (mut a, mut b) = duplex(64);
        a.write_all(&0u32.to_be_bytes()).await.unwrap();
        a.flush().await.unwrap();
        let err = read_message(&mut b).await.unwrap_err();
        assert!(matches!(err, Error::Protocol(_)), "got {err:?}");
    }
}
