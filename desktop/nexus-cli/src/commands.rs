//! Subcommand implementations.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use nexus_core::protocol::now_secs;
use nexus_core::{
    config, Connection, DiscoveryEvent, Identity, Message, Node, PeerInfo, Session, TrustStore,
};
use serde_json::json;

use crate::input::{self, PromptFactory};
use crate::node_setup::{self, short_id, Started};
use crate::{Cli, TrustCommand};

/// How long to search for a named device before giving up.
const FIND_TIMEOUT: Duration = Duration::from_secs(10);

/// How long to collect the nearby-device list in interactive mode.
const SELECT_WINDOW: Duration = Duration::from_secs(3);

/// `nexus-cli identity`
pub async fn identity(cli: &Cli) -> Result<()> {
    let dir = resolve_dir(cli)?;
    let settings = config::Settings::load(&dir);
    let name = cli.name.clone().unwrap_or(settings.device_name);
    let identity = Identity::load_or_create(&dir, name)?;

    println!("  device id     {}", identity.device_id);
    println!("  name          {}", identity.name);
    println!("  platform      {}", identity.platform);
    println!("  protocol      v{}", nexus_core::PROTOCOL_VERSION);
    println!("  certificate   {}", identity.cert_sha256_hex());
    println!("  data dir      {}", dir.display());
    Ok(())
}

/// `nexus-cli trust ...`
pub async fn trust(cli: &Cli, cmd: &TrustCommand) -> Result<()> {
    let dir = resolve_dir(cli)?;
    let mut store = TrustStore::load(&dir);

    match cmd {
        TrustCommand::List => {
            if store.is_empty() {
                println!("  no trusted devices");
                println!("  run `nexus-cli pair` on both devices to pair them");
                return Ok(());
            }
            println!("  trusted devices\n");
            for device in &store.devices {
                println!("  ✓ {} ({})", device.name, device.platform);
                println!(
                    "      id {}   paired {}   cert {}",
                    short_id(&device.device_id),
                    humanize_age(device.paired_at),
                    &device.cert_sha256[..16.min(device.cert_sha256.len())]
                );
            }
        }
        TrustCommand::Remove { device } => {
            let matches: Vec<_> = store
                .devices
                .iter()
                .filter(|d| device_matches(d, device))
                .map(|d| d.device_id.clone())
                .collect();

            match matches.len() {
                0 => bail!("no trusted device matching \"{device}\""),
                1 => {
                    let id = &matches[0];
                    let name = store
                        .by_device_id(id)
                        .map(|d| d.name.clone())
                        .unwrap_or_default();
                    store.remove(id);
                    store.save(&dir)?;
                    println!("  removed {name} — it must pair again to reconnect");
                }
                _ => bail!(
                    "\"{device}\" matches {} devices; specify the device id instead",
                    matches.len()
                ),
            }
        }
    }
    Ok(())
}

/// `nexus-cli browse`
pub async fn browse(cli: &Cli) -> Result<()> {
    let Started {
        node: _node,
        mut discovery,
    } = node_setup::start(cli).await?;

    println!("  browsing for Nexus devices — Ctrl+C to stop\n");
    loop {
        tokio::select! {
            event = discovery.next_event() => match event {
                Some(DiscoveryEvent::Found(peer)) => print_peer("found  ", &peer),
                Some(DiscoveryEvent::Updated(peer)) => print_peer("updated", &peer),
                Some(DiscoveryEvent::Lost { device_id, .. }) => {
                    println!("  lost     {}", short_id(&device_id));
                }
                None => break,
            },
            _ = tokio::signal::ctrl_c() => {
                println!("\n  stopped");
                break;
            }
        }
    }
    discovery.shutdown();
    Ok(())
}

/// `nexus-cli advertise`
pub async fn advertise(cli: &Cli) -> Result<()> {
    let Started {
        node,
        mut discovery,
    } = node_setup::start(cli).await?;
    let node = Arc::new(node);

    println!(
        "  advertising as \"{}\" — accepting paired devices only\n",
        node.identity.name
    );

    let accept = tokio::spawn(serve_inbound(node.clone(), None));

    loop {
        tokio::select! {
            event = discovery.next_event() => match event {
                Some(DiscoveryEvent::Found(peer)) => print_peer("found  ", &peer),
                Some(DiscoveryEvent::Updated(peer)) => print_peer("updated", &peer),
                Some(DiscoveryEvent::Lost { device_id, .. }) => {
                    println!("  lost     {}", short_id(&device_id));
                }
                None => break,
            },
            _ = tokio::signal::ctrl_c() => {
                println!("\n  stopped");
                break;
            }
        }
    }

    accept.abort();
    discovery.shutdown();
    Ok(())
}

/// `nexus-cli pair`
pub async fn pair(cli: &Cli, to: Option<String>, code: Option<String>) -> Result<()> {
    let Started {
        node,
        mut discovery,
    } = node_setup::start(cli).await?;
    let node = Arc::new(node);

    let prompts: PromptFactory = match code {
        Some(code) => input::auto_prompts(code),
        None => input::interactive_prompts(),
    };

    let accept = tokio::spawn(serve_inbound(node.clone(), Some(prompts.clone())));

    let target = match to {
        Some(target) => Some(node_setup::find_peer(&mut discovery, &target, FIND_TIMEOUT).await?),
        None => node_setup::select_peer(&mut discovery, SELECT_WINDOW).await?,
    };

    let Some(target) = target else {
        println!("  waiting for an incoming pairing request — Ctrl+C to stop\n");
        tokio::signal::ctrl_c().await.ok();
        accept.abort();
        discovery.shutdown();
        return Ok(());
    };

    println!("  connecting to {} …", target.name);
    let session = node_setup::connect_to_peer(&node, &target).await?;

    let session = if session.is_trusted() {
        println!(
            "  already paired with {} — opening the session",
            target.name
        );
        session
    } else {
        let session = nexus_core::pair_as_initiator(&node, session, prompts()).await?;
        println!("  paired with {} ✓", target.name);
        session
    };

    let connection = Connection::new(session, &node.identity).await?;
    print_connected(&connection);
    run_message_loop(connection, "peer").await?;

    accept.abort();
    discovery.shutdown();
    Ok(())
}

/// `nexus-cli connect <device>`
pub async fn connect(cli: &Cli, target: String) -> Result<()> {
    let Started {
        node,
        mut discovery,
    } = node_setup::start(cli).await?;
    let node = Arc::new(node);

    let accept = tokio::spawn(serve_inbound(node.clone(), None));

    let peer = node_setup::find_peer(&mut discovery, &target, FIND_TIMEOUT).await?;
    let session = node_setup::connect_to_peer(&node, &peer).await?;
    if !session.is_trusted() {
        bail!(
            "{} isn't paired with this device yet.\n  \
             Run `nexus-cli pair` on both devices first.",
            peer.name
        );
    }

    let connection = Connection::new(session, &node.identity).await?;
    print_connected(&connection);
    run_message_loop(connection, "peer").await?;

    accept.abort();
    discovery.shutdown();
    Ok(())
}

/// Accept inbound connections forever.
///
/// `prompts` decides what happens to unpaired devices: `None` rejects them,
/// `Some(..)` runs the pairing flow with the user.
async fn serve_inbound(node: Arc<Node>, prompts: Option<PromptFactory>) -> Result<()> {
    loop {
        let (session, addr) = match node.accept_tls().await {
            Ok(value) => value,
            Err(e) => {
                tracing::warn!("inbound connection failed: {e}");
                continue;
            }
        };
        let node = node.clone();
        let prompts = prompts.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_inbound(node, session, addr, prompts).await {
                tracing::debug!("inbound session from {addr} ended: {e}");
            }
        });
    }
}

async fn handle_inbound(
    node: Arc<Node>,
    session: Session,
    addr: SocketAddr,
    prompts: Option<PromptFactory>,
) -> Result<()> {
    if session.is_trusted() {
        let connection = Connection::new(session, &node.identity).await?;
        print_connected(&connection);
        run_message_loop(connection, "peer").await?;
        return Ok(());
    }

    match prompts {
        None => {
            println!("  rejected unpaired device at {addr}");
            let mut session = session;
            let _ = session
                .send(
                    "system.error",
                    json!({
                        "code": "not_paired",
                        "message": "this device isn't paired with Nexus on the other side yet",
                    }),
                )
                .await;
        }
        Some(prompts) => {
            println!("  incoming connection from {addr}");
            let session = nexus_core::pair_as_responder(&node, session, prompts()).await?;
            println!("  paired ✓");
            let connection = Connection::new(session, &node.identity).await?;
            print_connected(&connection);
            run_message_loop(connection, "peer").await?;
        }
    }
    Ok(())
}

/// Print a live session and relay typed lines until it ends.
async fn run_message_loop(mut connection: Connection, label: &str) -> Result<()> {
    println!("  type a message and press Enter to send it (Ctrl+C to quit)\n");

    let sender = connection.sender();
    tokio::task::spawn_blocking(move || {
        while let Some(line) = input::read_line_opt() {
            if line.is_empty() {
                continue;
            }
            if line == "/quit" {
                break;
            }
            if sender
                .send(Message::new("system.demo", json!({ "text": line })))
                .is_err()
            {
                break;
            }
        }
    });

    loop {
        match connection.next().await {
            Ok(Some(message)) => {
                let payload = serde_json::to_string(&message.payload).unwrap_or_default();
                if message.kind == "system.demo" {
                    println!(
                        "  ← [{}] {}",
                        label,
                        message.str_field("text").unwrap_or("")
                    );
                } else {
                    println!("  ← [{}] {} {}", label, message.kind, payload);
                }
            }
            Ok(None) => {
                println!("  the other device closed the connection");
                return Ok(());
            }
            Err(e) => {
                println!("  connection ended: {e}");
                return Ok(());
            }
        }
    }
}

fn print_connected(connection: &Connection) {
    println!();
    println!(
        "  ● connected to {} ({})   protocol v{}",
        connection.peer.name, connection.peer.platform, connection.peer.pv
    );
    println!(
        "    encrypted + authenticated   features: {}",
        if connection.peer.features.is_empty() {
            "none".to_string()
        } else {
            connection.peer.features.join(", ")
        }
    );
}

fn print_peer(label: &str, peer: &PeerInfo) {
    println!(
        "  {label} {} ({})  id {}  port {}{}",
        peer.name,
        peer.platform,
        short_id(&peer.device_id),
        peer.port,
        if peer.is_compatible() {
            String::new()
        } else {
            format!("  [incompatible pv{}]", peer.protocol_version)
        }
    );
}

fn resolve_dir(cli: &Cli) -> Result<std::path::PathBuf> {
    match &cli.data_dir {
        Some(dir) => Ok(dir.clone()),
        None => config::default_data_dir().context("couldn't locate the Nexus data directory"),
    }
}

fn device_matches(device: &nexus_core::TrustedDevice, target: &str) -> bool {
    let target = target.trim().to_ascii_lowercase();
    device.device_id.eq_ignore_ascii_case(&target)
        || device.device_id.to_ascii_lowercase().starts_with(&target)
        || device.name.to_ascii_lowercase() == target
}

/// Coarse "how long ago" text for a unix timestamp.
fn humanize_age(then: i64) -> String {
    let delta = now_secs() - then;
    if delta < 0 {
        return "just now".to_string();
    }
    let minutes = delta / 60;
    let hours = minutes / 60;
    let days = hours / 24;
    if days > 0 {
        format!("{days}d ago")
    } else if hours > 0 {
        format!("{hours}h ago")
    } else if minutes > 0 {
        format!("{minutes}m ago")
    } else {
        "just now".to_string()
    }
}
