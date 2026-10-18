//! Shared setup: node creation, discovery, and peer lookup.

use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};
use nexus_core::{Discovery, DiscoveryEvent, Node, PeerInfo, Session};

use crate::Cli;

/// A node with discovery running.
pub struct Started {
    pub node: Node,
    pub discovery: Discovery,
}

/// Bind the node and start advertising/browsing.
pub async fn start(cli: &Cli) -> Result<Started> {
    let node = Node::bind(cli.data_dir.clone(), cli.name.clone(), cli.port)
        .await
        .context("couldn't start the Nexus node")?;

    println!(
        "  device: {} ({})  id {}\n  listening on port {}  data {}",
        node.identity.name,
        node.identity.platform,
        short_id(&node.identity.device_id),
        node.port(),
        node.data_dir.display()
    );

    let discovery = Discovery::start(node.advertisement()).context("couldn't start discovery")?;
    Ok(Started { node, discovery })
}

/// First eight characters of a device id, for compact display.
pub fn short_id(device_id: &str) -> &str {
    &device_id[..device_id.len().min(8)]
}

/// Whether a peer matches a user-supplied name or id.
pub fn peer_matches(peer: &PeerInfo, target: &str) -> bool {
    let target = target.trim();
    let lowered = target.to_ascii_lowercase();
    peer.device_id.eq_ignore_ascii_case(target)
        || peer.device_id.starts_with(&lowered)
        || peer.name.eq_ignore_ascii_case(target)
        || peer.name.to_ascii_lowercase().contains(&lowered)
}

/// Wait for a specific peer to appear in discovery.
pub async fn find_peer(
    discovery: &mut Discovery,
    target: &str,
    timeout: Duration,
) -> Result<PeerInfo> {
    let deadline = Instant::now() + timeout;
    println!("  searching for \"{target}\"...");
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            bail!(
                "no device matching \"{target}\" was found.\n  \
                 Make sure both devices are on the same network and running Nexus."
            );
        }
        match tokio::time::timeout(remaining, discovery.next_event()).await {
            Ok(Some(DiscoveryEvent::Found(peer) | DiscoveryEvent::Updated(peer))) => {
                if peer_matches(&peer, target) {
                    return Ok(peer);
                }
            }
            Ok(Some(_)) => {}
            Ok(None) => bail!("device discovery stopped unexpectedly"),
            Err(_) => {
                bail!(
                    "no device matching \"{target}\" was found.\n  \
                     Make sure both devices are on the same network and running Nexus."
                )
            }
        }
    }
}

/// Collect nearby devices for a short while and let the user pick one.
pub async fn select_peer(discovery: &mut Discovery, wait: Duration) -> Result<Option<PeerInfo>> {
    let deadline = Instant::now() + wait;
    let mut found: Vec<PeerInfo> = Vec::new();

    println!("  looking for nearby devices...");
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match tokio::time::timeout(remaining, discovery.next_event()).await {
            Ok(Some(DiscoveryEvent::Found(peer) | DiscoveryEvent::Updated(peer))) => {
                if let Some(existing) = found.iter_mut().find(|p| p.device_id == peer.device_id) {
                    *existing = peer;
                } else {
                    println!(
                        "    found {} ({}){}",
                        peer.name,
                        peer.platform,
                        if peer.is_compatible() {
                            ""
                        } else {
                            "  [incompatible protocol version]"
                        }
                    );
                    found.push(peer);
                }
            }
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(_) => break,
        }
    }

    found.retain(|p| p.is_compatible());
    if found.is_empty() {
        return Ok(None);
    }

    println!();
    for (index, peer) in found.iter().enumerate() {
        println!(
            "  [{}] {} ({})  {}",
            index + 1,
            peer.name,
            peer.platform,
            short_id(&peer.device_id)
        );
    }
    let answer = crate::input::prompt_line("  Pair with which device? (number, or blank to wait) ");
    if answer.is_empty() {
        return Ok(None);
    }
    let index: usize = answer
        .parse()
        .ok()
        .filter(|i| *i >= 1 && *i <= found.len())
        .with_context(|| format!("invalid selection: {answer}"))?;
    Ok(Some(found[index - 1].clone()))
}

/// Connect to a peer, trying each advertised address in turn.
pub async fn connect_to_peer(node: &Node, peer: &PeerInfo) -> Result<Session> {
    let mut last_error = None;
    for addr in peer.socket_addrs() {
        match node.connect(addr).await {
            Ok(session) => return Ok(session),
            Err(e) => {
                tracing::debug!("connection to {addr} failed: {e}");
                last_error = Some(e);
            }
        }
    }
    match last_error {
        Some(e) => Err(e).with_context(|| {
            format!(
                "couldn't connect to {}.\n  Make sure both devices are on the same network.",
                peer.name
            )
        }),
        None => bail!("{} did not advertise a usable address", peer.name),
    }
}
