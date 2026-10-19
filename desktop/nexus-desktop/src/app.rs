//! Application state and the background manager task.
//!
//! One task owns every long-lived resource (the [`Node`], mDNS discovery, the
//! accept loop, the active [`Connection`], and the clipboard monitor) and is
//! driven by a single event loop. Tauri commands never touch the core
//! directly; they mutate a [`Status`] snapshot and post [`Command`]s.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use nexus_core::clipboard::{ClipboardUpdate, Inbound, Outbound, SyncState};
use nexus_core::{
    pair_as_initiator, pair_as_responder, Connection, DeviceInfo, Discovery, DiscoveryEvent,
    Message, Node, PairingDecision, PairingPrompt, PeerInfo, PromptFn, Role, Session,
    TrustedDevice,
};
use serde::Serialize;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::clipboard::{ClipboardCommand, ClipboardEvent, ClipboardMonitor};

/// How long the UI may take to answer a pairing prompt.
const PROMPT_TIMEOUT: Duration = Duration::from_secs(120);

/// How long an explicitly opened pairing window stays open.
const PAIRING_WINDOW: Duration = Duration::from_secs(60);

/// Minimum time between automatic reconnect attempts to the same device.
const RECONNECT_THROTTLE: Duration = Duration::from_secs(5);

/// Periodic housekeeping interval (pairing-window expiry, auto-reconnect).
const TICK: Duration = Duration::from_secs(2);

/// How many log lines the snapshot keeps.
const LOG_LIMIT: usize = 200;

/// How many human-readable activity entries the snapshot keeps.
const ACTIVITY_LIMIT: usize = 50;

/// Two identical activity entries closer together than this are collapsed, so
/// auto-reconnect chatter cannot flood the UI.
const ACTIVITY_DEDUPE: Duration = Duration::from_secs(5);

// ---------------------------------------------------------------------------
// Snapshot types (serialized to the webview)
// ---------------------------------------------------------------------------

/// Everything the UI renders.
#[derive(Debug, Clone, Default, Serialize)]
pub struct Status {
    pub ready: bool,
    pub error: Option<String>,
    pub device_name: String,
    pub device_id: String,
    pub device_short_id: String,
    pub platform: String,
    pub port: u16,
    pub data_dir: String,
    pub discovery_state: String,
    pub peers: Vec<PeerView>,
    pub trusted: Vec<TrustedView>,
    pub connection: Option<ConnectionView>,
    pub pairing_mode: bool,
    pub pairing_prompt: Option<PromptView>,
    pub clipboard_enabled: bool,
    pub clipboard_state: String,
    pub clipboard_preview: Option<String>,
    pub clipboard_last_at: Option<i64>,
    pub activities: Vec<ActivityView>,
    pub log: Vec<String>,
}

/// A discovered device.
#[derive(Debug, Clone, Serialize)]
pub struct PeerView {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    pub address: String,
    pub trusted: bool,
    pub compatible: bool,
    pub connected: bool,
}

/// A device pinned in the trust store.
#[derive(Debug, Clone, Serialize)]
pub struct TrustedView {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    pub cert_sha256: String,
    pub paired_at: i64,
}

/// The live session, when there is one.
#[derive(Debug, Clone, Serialize)]
pub struct ConnectionView {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    pub role: String,
}

/// A pairing prompt awaiting the user's decision.
#[derive(Debug, Clone, Serialize)]
pub struct PromptView {
    pub token: String,
    pub peer_name: String,
    pub peer_device_id: String,
    pub code: String,
    pub we_initiated: bool,
}

/// One user-facing activity entry (a curated view of the event log).
#[derive(Debug, Clone, Serialize)]
pub struct ActivityView {
    /// Unix milliseconds, for relative timestamps in the UI.
    pub at: i64,
    /// "info" | "success" | "warn" | "error".
    pub level: String,
    pub text: String,
}

// ---------------------------------------------------------------------------
// Pairing bridge: core prompt callback ⇄ UI dialog
// ---------------------------------------------------------------------------

/// Bridges `nexus-core`'s blocking prompt callback to the webview.
///
/// The core runs the prompt on a blocking thread; we publish a prompt into the
/// status snapshot and block until a Tauri command answers it.
#[derive(Clone)]
pub struct PairingBridge {
    status: Arc<Mutex<Status>>,
    waiting: Arc<Mutex<HashMap<String, std::sync::mpsc::Sender<bool>>>>,
    counter: Arc<AtomicU64>,
}

impl PairingBridge {
    fn new(status: Arc<Mutex<Status>>) -> Self {
        Self {
            status,
            waiting: Arc::new(Mutex::new(HashMap::new())),
            counter: Arc::new(AtomicU64::new(0)),
        }
    }

    /// Build a fresh prompt callback (one per pairing attempt).
    pub fn prompt(&self) -> PromptFn {
        let status = self.status.clone();
        let waiting = self.waiting.clone();
        let counter = self.counter.clone();
        Box::new(move |prompt: &PairingPrompt| {
            let token = format!("p{}", counter.fetch_add(1, Ordering::SeqCst));
            let (tx, rx) = std::sync::mpsc::channel();
            waiting.lock().unwrap().insert(token.clone(), tx);

            let role = if prompt.we_initiated {
                "confirm the code with"
            } else {
                "check the code with"
            };
            {
                let mut s = status.lock().unwrap();
                s.pairing_prompt = Some(PromptView {
                    token: token.clone(),
                    peer_name: prompt.peer.name.clone(),
                    peer_device_id: prompt.peer.device_id.clone(),
                    code: prompt.code.clone(),
                    we_initiated: prompt.we_initiated,
                });
                push_log(
                    &mut s,
                    format!(
                        "pairing: {} {} — verification code {}",
                        role, prompt.peer.name, prompt.code
                    ),
                );
            }

            let decision = rx.recv_timeout(PROMPT_TIMEOUT).unwrap_or(false);

            waiting.lock().unwrap().remove(&token);
            let mut s = status.lock().unwrap();
            let still_pending = s
                .pairing_prompt
                .as_ref()
                .map(|p| p.token == token)
                .unwrap_or(false);
            if still_pending {
                s.pairing_prompt = None;
            }
            drop(s);

            if decision {
                PairingDecision::Accept
            } else {
                PairingDecision::Reject
            }
        })
    }

    /// Answer a pending prompt. Returns false if the token is unknown/expired.
    pub fn respond(&self, token: &str, accept: bool) -> bool {
        match self.waiting.lock().unwrap().remove(token) {
            Some(tx) => tx.send(accept).is_ok(),
            None => false,
        }
    }
}

// ---------------------------------------------------------------------------
// Commands from the UI
// ---------------------------------------------------------------------------

/// A UI-initiated action.
#[derive(Debug)]
pub enum Command {
    /// Connect to a device, pairing first if it is not yet trusted.
    Pair(String),
    /// Connect to an already-trusted device.
    Connect(String),
    /// Drop the active session.
    Disconnect,
    /// Forget a trusted device.
    Unpair(String),
    /// Turn clipboard sync on or off.
    SetClipboardEnabled(bool),
    /// Open the 60-second pairing window (responder role).
    AllowPairing,
    /// Send the current clipboard to the active peer now.
    PushClipboard,
    /// Stop the manager.
    Shutdown,
}

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

/// State shared with Tauri commands.
pub struct Shared {
    status: Arc<Mutex<Status>>,
    cmd_tx: mpsc::UnboundedSender<Command>,
    pairing: PairingBridge,
    clipboard_enabled: Arc<AtomicBool>,
}

impl Shared {
    /// Build the shared state and the command receiver the manager consumes.
    pub fn new() -> (Arc<Self>, mpsc::UnboundedReceiver<Command>) {
        let status = Arc::new(Mutex::new(Status::default()));
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let clipboard_enabled = Arc::new(AtomicBool::new(true));
        {
            let mut s = status.lock().unwrap();
            s.clipboard_enabled = true;
            s.clipboard_state = "idle".to_string();
            s.discovery_state = "starting".to_string();
        }
        let pairing = PairingBridge::new(status.clone());
        let shared = Arc::new(Self {
            status,
            cmd_tx,
            pairing,
            clipboard_enabled,
        });
        (shared, cmd_rx)
    }

    /// A copy of the current snapshot.
    pub fn snapshot(&self) -> Status {
        self.status.lock().unwrap().clone()
    }

    /// Answer a pairing prompt.
    pub fn respond_pairing(&self, token: &str, accept: bool) -> bool {
        self.pairing.respond(token, accept)
    }

    /// Post a command to the manager.
    pub fn send(&self, command: Command) {
        let _ = self.cmd_tx.send(command);
    }
}

// ---------------------------------------------------------------------------
// Manager
// ---------------------------------------------------------------------------

/// Events the manager reacts to.
enum Event {
    Discovery(DiscoveryEvent),
    Inbound { session: Session, addr: SocketAddr },
    Connected(Box<Connection>),
    ConnectFailed(String),
    Message(u64, Message),
    Closed(u64, String),
    Clipboard(ClipboardEvent),
    Tick,
    Command(Command),
}

/// A live session plus its reader task.
struct ActiveSession {
    id: u64,
    peer: DeviceInfo,
    sender: mpsc::UnboundedSender<Message>,
    task: JoinHandle<()>,
}

impl ActiveSession {
    fn stop(self) {
        self.task.abort();
    }
}

/// The manager's mutable state.
struct Manager {
    node: Arc<Node>,
    shared: Arc<Shared>,
    tx: mpsc::UnboundedSender<Event>,
    monitor: ClipboardMonitor,
    peers: HashMap<String, PeerInfo>,
    trusted: Vec<TrustedDevice>,
    sync: SyncState,
    active: Option<ActiveSession>,
    connecting: Option<String>,
    last_attempt: HashMap<String, Instant>,
    pairing_until: Option<Instant>,
    last_clipboard: Option<String>,
    generation: u64,
}

/// Start the manager. Runs until the command channel closes.
pub async fn run(shared: Arc<Shared>, mut cmd_rx: mpsc::UnboundedReceiver<Command>) {
    let (tx, mut rx) = mpsc::unbounded_channel::<Event>();

    let (data_dir, name, port) = parse_args();
    let node = match Node::bind(data_dir, name, port).await {
        Ok(node) => Arc::new(node),
        Err(e) => {
            let mut s = shared.status.lock().unwrap();
            s.error = Some(format!("Nexus couldn't start: {e}"));
            push_log(&mut s, format!("fatal: {e}"));
            return;
        }
    };

    {
        let mut s = shared.status.lock().unwrap();
        s.ready = true;
        s.device_name = node.identity.name.clone();
        s.device_id = node.identity.device_id.clone();
        s.device_short_id = short_id(&node.identity.device_id).to_string();
        s.platform = node.identity.platform.clone();
        s.port = node.port();
        s.data_dir = node.data_dir.display().to_string();
        s.discovery_state = "starting".to_string();
        push_log(
            &mut s,
            format!(
                "node ready as \"{}\" on port {}",
                node.identity.name,
                node.port()
            ),
        );
    }

    let discovery = match Discovery::start(node.advertisement()) {
        Ok(discovery) => discovery,
        Err(e) => {
            let mut s = shared.status.lock().unwrap();
            s.error = Some(format!("device discovery couldn't start: {e}"));
            push_log(&mut s, format!("fatal: {e}"));
            return;
        }
    };

    let trusted = node.trust.read().await.devices.clone();
    {
        let mut s = shared.status.lock().unwrap();
        s.discovery_state = "browsing".to_string();
        s.trusted = trusted.iter().map(trusted_view).collect();
        push_log(
            &mut s,
            format!("discovery started; {} trusted device(s)", trusted.len()),
        );
    }

    // Discovery pump.
    {
        let tx = tx.clone();
        let mut discovery = discovery;
        tokio::spawn(async move {
            while let Some(event) = discovery.next_event().await {
                if tx.send(Event::Discovery(event)).is_err() {
                    break;
                }
            }
        });
    }

    // Accept loop.
    {
        let node = node.clone();
        let tx = tx.clone();
        tokio::spawn(async move {
            loop {
                match node.accept_tls().await {
                    Ok((session, addr)) => {
                        if tx.send(Event::Inbound { session, addr }).is_err() {
                            break;
                        }
                    }
                    Err(e) => {
                        tracing::debug!("accept failed: {e}");
                        tokio::time::sleep(Duration::from_millis(250)).await;
                    }
                }
            }
        });
    }

    // Housekeeping tick.
    {
        let tx = tx.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(TICK);
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                interval.tick().await;
                if tx.send(Event::Tick).is_err() {
                    break;
                }
            }
        });
    }

    // Clipboard monitor.
    let clipboard_enabled = shared.clipboard_enabled.clone();
    let monitor = {
        let tx = tx.clone();
        ClipboardMonitor::start(clipboard_enabled, move |event| {
            let _ = tx.send(Event::Clipboard(event));
        })
    };

    let mut manager = Manager {
        node,
        shared,
        tx,
        monitor,
        peers: HashMap::new(),
        trusted,
        sync: SyncState::new(),
        active: None,
        connecting: None,
        last_attempt: HashMap::new(),
        pairing_until: None,
        last_clipboard: None,
        generation: 0,
    };

    manager.status(|s| push_log(s, "manager started".to_string()));

    loop {
        let event = tokio::select! {
            Some(event) = rx.recv() => event,
            Some(command) = cmd_rx.recv() => Event::Command(command),
            else => break,
        };
        if !manager.handle(event).await {
            break;
        }
    }

    if let Some(active) = manager.active.take() {
        active.stop();
    }
    manager.status(|s| push_log(s, "manager stopped".to_string()));
}

impl Manager {
    /// Apply a mutation to the shared snapshot.
    fn status(&self, f: impl FnOnce(&mut Status)) {
        let mut s = self.shared.status.lock().unwrap();
        f(&mut s);
    }

    /// Handle one event. Returns false to stop the manager.
    async fn handle(&mut self, event: Event) -> bool {
        match event {
            Event::Command(Command::Shutdown) => return false,
            Event::Command(command) => self.handle_command(command).await,
            Event::Discovery(event) => self.handle_discovery(event),
            Event::Inbound { session, addr } => self.handle_inbound(session, addr),
            Event::Connected(conn) => {
                // A pairing that just completed has already been written to
                // the node's trust store; mirror it into the snapshot so the
                // trusted list and the dial loop see the new device without
                // an app restart.
                self.refresh_trusted().await;
                self.adopt(*conn)
            }
            Event::ConnectFailed(reason) => {
                self.connecting = None;
                self.status(|s| push_log(s, format!("connection failed: {reason}")));
            }
            Event::Message(id, message) => self.handle_message(id, message),
            Event::Closed(id, reason) => self.handle_closed(id, reason),
            Event::Clipboard(event) => self.handle_clipboard(event),
            Event::Tick => self.handle_tick(),
        }
        true
    }

    // -- UI commands ------------------------------------------------------

    async fn handle_command(&mut self, command: Command) {
        match command {
            Command::Pair(device_id) => self.begin_connect(&device_id, true),
            Command::Connect(device_id) => self.begin_connect(&device_id, false),
            Command::Disconnect => {
                if let Some(active) = self.active.take() {
                    let name = active.peer.name.clone();
                    active.stop();
                    self.status(|s| {
                        s.connection = None;
                        push_log(s, format!("disconnected from {name}"));
                    });
                } else {
                    self.status(|s| push_log(s, "not connected".to_string()));
                }
                self.connecting = None;
            }
            Command::Unpair(device_id) => {
                match self.node.untrust_device(&device_id).await {
                    Ok(true) => self.status(|s| push_log(s, format!("unpaired {device_id}"))),
                    Ok(false) => {}
                    Err(e) => self.status(|s| push_log(s, format!("couldn't unpair: {e}"))),
                }
                self.refresh_trusted().await;
                if self
                    .active
                    .as_ref()
                    .map(|a| a.peer.device_id == device_id)
                    .unwrap_or(false)
                {
                    if let Some(active) = self.active.take() {
                        active.stop();
                    }
                    self.status(|s| s.connection = None);
                }
            }
            Command::SetClipboardEnabled(enabled) => {
                self.shared
                    .clipboard_enabled
                    .store(enabled, Ordering::Relaxed);
                self.status(|s| {
                    s.clipboard_enabled = enabled;
                    s.clipboard_state = if enabled {
                        "enabled".to_string()
                    } else {
                        "disabled".to_string()
                    };
                    push_log(
                        s,
                        format!(
                            "clipboard sync {}",
                            if enabled { "enabled" } else { "disabled" }
                        ),
                    );
                });
                if enabled {
                    self.monitor.send(ClipboardCommand::Push);
                }
            }
            Command::AllowPairing => {
                self.pairing_until = Some(Instant::now() + PAIRING_WINDOW);
                self.status(|s| {
                    s.pairing_mode = true;
                    push_log(
                        s,
                        "pairing window open for 60s (awaiting a request)".to_string(),
                    );
                });
            }
            Command::PushClipboard => {
                self.monitor.send(ClipboardCommand::Push);
            }
            Command::Shutdown => {}
        }
    }

    /// Start an outbound connection (optionally pairing first).
    fn begin_connect(&mut self, device_id: &str, pair_if_needed: bool) {
        if self.active.is_some() {
            self.status(|s| push_log(s, "already connected; disconnect first".to_string()));
            return;
        }
        if self.connecting.is_some() {
            self.status(|s| push_log(s, "a connection attempt is already running".to_string()));
            return;
        }
        let Some(peer) = self.peers.get(device_id).cloned() else {
            self.status(|s| {
                push_log(
                    s,
                    format!("{device_id} is not currently visible on the network"),
                )
            });
            return;
        };
        if !peer.is_compatible() {
            self.status(|s| {
                push_log(
                    s,
                    format!(
                        "{} speaks protocol v{} — incompatible",
                        peer.name, peer.protocol_version
                    ),
                )
            });
            return;
        }

        self.connecting = Some(device_id.to_string());
        self.last_attempt
            .insert(device_id.to_string(), Instant::now());

        let node = self.node.clone();
        let tx = self.tx.clone();
        let prompt = self.shared.pairing.prompt();
        let verb = if pair_if_needed {
            "pairing with"
        } else {
            "connecting to"
        };
        self.status(|s| push_log(s, format!("{verb} {} …", peer.name)));

        tokio::spawn(async move {
            match connect_flow(&node, &peer, pair_if_needed, prompt).await {
                Ok(connection) => {
                    let _ = tx.send(Event::Connected(Box::new(connection)));
                }
                Err(e) => {
                    let _ = tx.send(Event::ConnectFailed(format!("{}: {e}", peer.name)));
                }
            }
        });
    }

    // -- Discovery --------------------------------------------------------

    fn handle_discovery(&mut self, event: DiscoveryEvent) {
        match event {
            DiscoveryEvent::Found(peer) | DiscoveryEvent::Updated(peer) => {
                let known = self.peers.contains_key(&peer.device_id);
                self.peers.insert(peer.device_id.clone(), peer.clone());
                self.status(|s| {
                    if known {
                        push_log(s, format!("updated {} ({})", peer.name, peer.platform));
                    } else {
                        push_log(
                            s,
                            format!(
                                "discovered {} ({}) at {}",
                                peer.name,
                                peer.platform,
                                peer_addr(&peer)
                            ),
                        );
                    }
                });
            }
            DiscoveryEvent::Lost { device_id, .. } => {
                if let Some(peer) = self.peers.remove(&device_id) {
                    self.status(|s| push_log(s, format!("lost {}", peer.name)));
                }
            }
        }
        self.refresh_peers();
    }

    fn refresh_peers(&self) {
        let active_id = self.active.as_ref().map(|a| a.peer.device_id.clone());
        let mut peers: Vec<PeerView> = self
            .peers
            .values()
            .map(|peer| PeerView {
                device_id: peer.device_id.clone(),
                name: peer.name.clone(),
                platform: peer.platform.clone(),
                address: peer_addr(peer),
                trusted: self.is_trusted(&peer.device_id),
                compatible: peer.is_compatible(),
                connected: active_id.as_deref() == Some(peer.device_id.as_str()),
            })
            .collect();
        peers.sort_by_key(|a| a.name.to_lowercase());
        self.status(|s| s.peers = peers);
    }

    fn is_trusted(&self, device_id: &str) -> bool {
        self.trusted.iter().any(|d| d.device_id == device_id)
    }

    async fn refresh_trusted(&mut self) {
        self.trusted = self.node.trust.read().await.devices.clone();
        let views: Vec<TrustedView> = self.trusted.iter().map(trusted_view).collect();
        self.status(|s| s.trusted = views);
        self.refresh_peers();
    }

    // -- Inbound connections ---------------------------------------------

    fn handle_inbound(&self, session: Session, addr: SocketAddr) {
        let node = self.node.clone();
        let tx = self.tx.clone();
        let shared = self.shared.clone();
        let pairing_open = self
            .pairing_until
            .map(|u| Instant::now() < u)
            .unwrap_or(false);

        tokio::spawn(async move {
            if session.is_trusted() {
                let name = session
                    .trusted
                    .as_ref()
                    .map(|d| d.name.clone())
                    .unwrap_or_else(|| "a trusted device".to_string());
                {
                    let mut s = shared.status.lock().unwrap();
                    push_log(&mut s, format!("inbound connection from {name} ({addr})"));
                }
                complete_handshake(&node, &tx, session).await;
                return;
            }

            if !pairing_open {
                let mut session = session;
                let _ = session
                    .send(
                        "system.error",
                        serde_json::json!({
                            "code": "not_paired",
                            "message": "this device isn't paired with Nexus on the other side yet",
                        }),
                    )
                    .await;
                let mut s = shared.status.lock().unwrap();
                push_log(
                    &mut s,
                    format!("rejected unpaired device at {addr} (open a pairing window to pair)"),
                );
                return;
            }

            {
                let mut s = shared.status.lock().unwrap();
                push_log(&mut s, format!("incoming pairing request from {addr}"));
            }
            match pair_as_responder(&node, session, shared.pairing.prompt()).await {
                Ok(session) => {
                    {
                        let mut s = shared.status.lock().unwrap();
                        push_log(&mut s, "pairing completed".to_string());
                    }
                    complete_handshake(&node, &tx, session).await
                }
                Err(e) => {
                    let _ = tx.send(Event::ConnectFailed(format!("pairing: {e}")));
                }
            }
        });
    }

    // -- Session adoption -------------------------------------------------

    fn adopt(&mut self, connection: Connection) {
        if let Some(previous) = self.active.take() {
            previous.stop();
        }

        let peer = connection.peer.clone();
        let role = match connection.session.role {
            Role::Initiator => "initiator (you connected)",
            Role::Acceptor => "responder (they connected)",
        }
        .to_string();
        let sender = connection.sender();

        self.generation += 1;
        let id = self.generation;
        let tx = self.tx.clone();
        let task = tokio::spawn(async move {
            let mut connection = connection;
            loop {
                match connection.next().await {
                    Ok(Some(message)) => {
                        if tx.send(Event::Message(id, message)).is_err() {
                            break;
                        }
                    }
                    Ok(None) => {
                        let _ = tx.send(Event::Closed(
                            id,
                            "the other device closed the connection".to_string(),
                        ));
                        break;
                    }
                    Err(e) => {
                        let _ = tx.send(Event::Closed(id, e.to_string()));
                        break;
                    }
                }
            }
        });

        self.status(|s| {
            s.connection = Some(ConnectionView {
                device_id: peer.device_id.clone(),
                name: peer.name.clone(),
                platform: peer.platform.clone(),
                role,
            });
            s.clipboard_state = "connected".to_string();
            push_log(
                s,
                format!(
                    "connected to {} ({}) — encrypted + authenticated",
                    peer.name, peer.platform
                ),
            );
        });

        self.active = Some(ActiveSession {
            id,
            peer,
            sender,
            task,
        });
        self.connecting = None;

        // Offer the current clipboard to the new peer. A fresh session must
        // not suppress the initial push just because we sent the same value to
        // a previous peer, so forget the last-sent hash first.
        self.sync.reset_sent();
        if self.shared.clipboard_enabled.load(Ordering::Relaxed) {
            self.monitor.send(ClipboardCommand::Push);
        }
        self.refresh_peers();
    }

    fn handle_closed(&mut self, id: u64, reason: String) {
        if self.active.as_ref().map(|a| a.id) != Some(id) {
            return;
        }
        let name = self
            .active
            .take()
            .map(|a| a.peer.name)
            .unwrap_or_else(|| "peer".to_string());
        self.connecting = None;
        self.status(|s| {
            s.connection = None;
            s.clipboard_state = "disconnected".to_string();
            push_log(s, format!("connection to {name} ended: {reason}"));
        });
        self.refresh_peers();
    }

    // -- Application messages --------------------------------------------

    fn handle_message(&mut self, id: u64, message: Message) {
        if self.active.as_ref().map(|a| a.id) != Some(id) {
            return;
        }
        match message.kind.as_str() {
            "clipboard.update" => self.handle_clipboard_update(&message),
            other => {
                self.status(|s| push_log(s, format!("unhandled message type {other}")));
                self.send_error("unknown_type", format!("unsupported message type: {other}"));
            }
        }
    }

    fn handle_clipboard_update(&mut self, message: &Message) {
        if !self.shared.clipboard_enabled.load(Ordering::Relaxed) {
            self.status(|s| push_log(s, "clipboard update ignored (sync disabled)".to_string()));
            return;
        }
        let Some(update) = ClipboardUpdate::from_payload(&message.payload) else {
            self.status(|s| {
                push_log(
                    s,
                    "clipboard update rejected: missing text/h fields".to_string(),
                )
            });
            self.send_error("invalid_payload", "clipboard update is missing text/h");
            return;
        };

        match self.sync.inbound(&update, self.last_clipboard.as_deref()) {
            Inbound::Apply { text, hash } => {
                let bytes = text.len();
                self.monitor.send(ClipboardCommand::Apply { text, hash });
                self.status(|s| {
                    s.clipboard_state = format!("receiving {bytes} bytes from peer");
                });
            }
            Inbound::SameContent { hash } => {
                self.sync.note_applied(&hash);
                self.status(|s| push_log(s, "clipboard update ignored (same content)".to_string()));
            }
            Inbound::Echo => self.status(|s| push_log(s, "clipboard echo suppressed".to_string())),
            Inbound::Duplicate => {
                self.status(|s| push_log(s, "clipboard duplicate suppressed".to_string()))
            }
            Inbound::InvalidHash => {
                self.status(|s| {
                    push_log(s, "clipboard update rejected: hash mismatch".to_string())
                });
                self.send_error("invalid_payload", "clipboard hash does not match text");
            }
            Inbound::TooLarge { bytes } => {
                self.status(|s| {
                    push_log(
                        s,
                        format!("clipboard update rejected: {bytes} bytes too large"),
                    )
                });
                self.send_error(
                    "payload_too_large",
                    "clipboard text exceeds the 16 KiB limit",
                );
            }
        }
    }

    fn send_error(&self, code: &str, message: impl Into<String>) {
        if let Some(active) = &self.active {
            let _ = active.sender.send(Message::error(code, message));
        }
    }

    // -- Clipboard events ------------------------------------------------

    fn handle_clipboard(&mut self, event: ClipboardEvent) {
        match event {
            ClipboardEvent::Applied { hash } => {
                self.sync.note_applied(&hash);
                self.status(|s| {
                    s.clipboard_state = "applied from peer".to_string();
                    s.clipboard_last_at = Some(now_ms());
                    push_log(s, "clipboard updated from peer".to_string());
                });
            }
            ClipboardEvent::Failed(message) => {
                self.status(|s| {
                    s.clipboard_state = "unavailable".to_string();
                    push_log(s, message);
                });
            }
            ClipboardEvent::Local(text) => {
                self.last_clipboard = Some(text.clone());
                let preview = preview_of(&text);
                let bytes = text.len();

                if !self.shared.clipboard_enabled.load(Ordering::Relaxed) {
                    self.status(|s| s.clipboard_preview = preview);
                    return;
                }

                match self.sync.outbound(&text) {
                    Outbound::Send(update) => {
                        if let Some(active) = &self.active {
                            match active.sender.send(update.to_message()) {
                                Ok(()) => {
                                    self.sync.note_sent(&update.h);
                                    self.status(|s| {
                                        s.clipboard_preview = preview;
                                        s.clipboard_last_at = Some(now_ms());
                                        s.clipboard_state = format!("sent {bytes} bytes to peer");
                                        push_log(s, format!("clipboard sent ({bytes} bytes)"));
                                    });
                                }
                                Err(_) => {
                                    self.status(|s| {
                                        s.clipboard_preview = preview;
                                        push_log(
                                            s,
                                            "clipboard change dropped: connection is closing"
                                                .to_string(),
                                        );
                                    });
                                }
                            }
                        } else {
                            // Best-effort: no session, the change is dropped.
                            self.sync.note_sent(&update.h);
                            self.status(|s| {
                                s.clipboard_preview = preview;
                                s.clipboard_state = "changed locally (no connection)".to_string();
                                push_log(
                                    s,
                                    "clipboard change captured; no peer connected".to_string(),
                                );
                            });
                        }
                    }
                    Outbound::Unchanged => {
                        self.status(|s| s.clipboard_preview = preview);
                    }
                    Outbound::TooLarge { bytes } => {
                        self.status(|s| {
                            s.clipboard_preview = preview;
                            push_log(
                                s,
                                format!("clipboard not synced: {bytes} bytes exceeds the limit"),
                            );
                        });
                    }
                }
            }
        }
    }

    // -- Housekeeping ----------------------------------------------------

    fn handle_tick(&mut self) {
        if let Some(until) = self.pairing_until {
            if Instant::now() >= until {
                self.pairing_until = None;
                self.status(|s| {
                    s.pairing_mode = false;
                    push_log(s, "pairing window closed".to_string());
                });
            }
        }

        if self.active.is_some() || self.connecting.is_some() {
            return;
        }

        // Try to (re)connect to a trusted device that is currently visible.
        let candidate = self
            .peers
            .values()
            .filter(|peer| self.is_trusted(&peer.device_id) && peer.is_compatible())
            .map(|peer| peer.device_id.clone())
            .find(|device_id| {
                self.last_attempt
                    .get(device_id)
                    .map(|t| t.elapsed() >= RECONNECT_THROTTLE)
                    .unwrap_or(true)
            });

        if let Some(device_id) = candidate {
            self.begin_connect(&device_id, false);
        }
    }
}

// ---------------------------------------------------------------------------
// Free helpers
// ---------------------------------------------------------------------------

/// Finish the connection-level handshake and hand the session to the manager.
async fn complete_handshake(node: &Node, tx: &mpsc::UnboundedSender<Event>, session: Session) {
    match Connection::new(session, &node.identity).await {
        Ok(connection) => {
            let _ = tx.send(Event::Connected(Box::new(connection)));
        }
        Err(e) => {
            let _ = tx.send(Event::ConnectFailed(e.to_string()));
        }
    }
}

/// Connect to a peer, pairing first when asked and needed, then run hello.
async fn connect_flow(
    node: &Node,
    peer: &PeerInfo,
    pair_if_needed: bool,
    prompt: PromptFn,
) -> nexus_core::Result<Connection> {
    let addrs = peer.socket_addrs();
    if addrs.is_empty() {
        return Err(nexus_core::Error::Config(format!(
            "{} did not advertise a usable address",
            peer.name
        )));
    }

    let mut last_error = None;
    for addr in addrs {
        match node.connect(addr).await {
            Ok(session) => {
                let session = if session.is_trusted() {
                    session
                } else if pair_if_needed {
                    pair_as_initiator(node, session, prompt).await?
                } else {
                    return Err(nexus_core::Error::NotPaired);
                };
                return Connection::new(session, &node.identity).await;
            }
            Err(e) => {
                tracing::debug!("connection to {addr} failed: {e}");
                last_error = Some(e);
            }
        }
    }
    Err(last_error.unwrap_or(nexus_core::Error::NotPaired))
}

fn trusted_view(device: &TrustedDevice) -> TrustedView {
    TrustedView {
        device_id: device.device_id.clone(),
        name: device.name.clone(),
        platform: device.platform.clone(),
        cert_sha256: device.cert_sha256.clone(),
        paired_at: device.paired_at,
    }
}

fn peer_addr(peer: &PeerInfo) -> String {
    match peer.socket_addrs().first() {
        Some(addr) => addr.to_string(),
        None => format!("(no address) port {}", peer.port),
    }
}

fn short_id(device_id: &str) -> &str {
    &device_id[..device_id.len().min(8)]
}

/// One-line, single-spaced preview of clipboard text for the UI.
fn preview_of(text: &str) -> Option<String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Some("(empty)".to_string());
    }
    let mut preview: String = trimmed.chars().take(80).collect();
    if trimmed.chars().count() > 80 {
        preview.push('…');
    }
    preview = preview.replace('\n', " ");
    Some(preview)
}

fn push_log(status: &mut Status, line: impl Into<String>) {
    let line = line.into();
    tracing::info!("{line}");
    if let Some((level, text)) = humanize(&line) {
        push_activity(status, level, text);
    }
    status.log.push(line);
    let len = status.log.len();
    if len > LOG_LIMIT {
        status.log.drain(0..len - LOG_LIMIT);
    }
}

/// Current wall-clock time in Unix milliseconds.
fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Append a user-facing activity entry, collapsing chatter.
fn push_activity(status: &mut Status, level: &str, text: String) {
    let at = now_ms();
    if let Some(last) = status.activities.last() {
        if last.text == text && at.saturating_sub(last.at) < ACTIVITY_DEDUPE.as_millis() as i64 {
            return;
        }
    }
    status.activities.push(ActivityView {
        at,
        level: level.to_string(),
        text,
    });
    let len = status.activities.len();
    if len > ACTIVITY_LIMIT {
        status.activities.drain(0..len - ACTIVITY_LIMIT);
    }
}

/// Decide whether a log line is meaningful to a human, and how to colour it.
///
/// Returns `None` for developer-only noise (internal lifecycle messages, peer
/// refreshes and clipboard echo suppression) so the activity feed stays calm.
fn humanize(line: &str) -> Option<(&'static str, String)> {
    if line.starts_with("manager ")
        || line.starts_with("node ready")
        || line.starts_with("discovery started")
        || line.starts_with("updated ")
        || line.contains("suppressed")
        || line.contains("same content")
        || line.contains("(sync disabled)")
    {
        return None;
    }

    let level = if line.contains("failed")
        || line.contains("couldn't")
        || line.contains("fatal")
        || line.contains("rejected")
    {
        "error"
    } else if line.contains("disconnected")
        || line.contains("ended:")
        || line.contains("unavailable")
        || line.contains("not synced")
        || line.contains("not currently")
        || line.contains("incompatible")
        || line.contains("no peer")
        || line.contains("lost ")
        || line.contains("blocked")
        || line.contains("already connected")
        || line.contains("not connected")
    {
        "warn"
    } else if line.starts_with("connected to ")
        || line.contains("clipboard sent")
        || line.contains("clipboard updated from peer")
        || line.contains("clipboard applied")
        || line.contains("pairing completed")
    {
        "success"
    } else {
        "info"
    };

    Some((level, line.to_string()))
}

/// Optional CLI overrides, mirroring `nexus-cli` so tests can isolate state.
fn parse_args() -> (Option<PathBuf>, Option<String>, Option<u16>) {
    let mut data_dir = None;
    let mut name = None;
    let mut port = None;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--data-dir" => data_dir = args.next().map(PathBuf::from),
            "--name" => name = args.next(),
            "--port" => port = args.next().and_then(|value| value.parse().ok()),
            _ => {}
        }
    }
    (data_dir, name, port)
}
