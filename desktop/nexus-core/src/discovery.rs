//! mDNS device discovery.
//!
//! Implements `protocol/discovery.md`.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::error::{Error, Result};

/// mDNS service type used by Nexus.
pub const SERVICE_TYPE: &str = "_nexus._tcp.local.";

/// A device seen on the local network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerInfo {
    /// Persistent device id (UUID).
    pub device_id: String,
    /// Human-readable name.
    pub name: String,
    /// Platform identifier.
    pub platform: String,
    /// Advertised protocol version.
    pub protocol_version: u32,
    /// All known addresses for the peer.
    pub addresses: Vec<IpAddr>,
    /// TCP port of the peer's secure listener.
    pub port: u16,
    /// mDNS service instance full name (stable per advertisement).
    pub fullname: String,
    /// Advertised host name.
    pub hostname: String,
}

impl PeerInfo {
    /// Candidate socket addresses, IPv4 first (more reliable on LANs).
    pub fn socket_addrs(&self) -> Vec<SocketAddr> {
        let mut addrs: Vec<SocketAddr> = self
            .addresses
            .iter()
            .map(|ip| SocketAddr::new(*ip, self.port))
            .collect();

        addrs.sort_by(|a, b| {
            let a_preferred = addr_preference(a.ip());
            let b_preferred = addr_preference(b.ip());
            a_preferred
                .cmp(&b_preferred)
                .then_with(|| a.ip().cmp(&b.ip()))
        });
        addrs
    }

    /// Whether this peer speaks a protocol version we support.
    pub fn is_compatible(&self) -> bool {
        self.protocol_version == crate::protocol::PROTOCOL_VERSION
    }
}

/// Discovery notifications.
#[derive(Debug, Clone)]
pub enum DiscoveryEvent {
    /// A device appeared.
    Found(PeerInfo),
    /// A known device re-announced with changed details.
    Updated(PeerInfo),
    /// A device went away.
    Lost { device_id: String, fullname: String },
}

/// Parameters advertised in the mDNS record.
#[derive(Debug, Clone)]
pub struct Advertisement {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    pub port: u16,
    pub protocol_version: u32,
}

/// A running discovery service: advertise + browse.
pub struct Discovery {
    daemon: ServiceDaemon,
    events: mpsc::UnboundedReceiver<DiscoveryEvent>,
    browse_task: JoinHandle<()>,
    /// Full name of our own advertisement, used to filter self-reports.
    own_fullname: Option<String>,
}

impl Discovery {
    /// Start advertising this device and browsing for peers.
    pub fn start(advertisement: Advertisement) -> Result<Self> {
        let daemon = ServiceDaemon::new()
            .map_err(|e| Error::Discovery(format!("couldn't start mDNS: {e}")))?;

        let short_id: String = advertisement.device_id.chars().take(8).collect();
        let hostname = format!("nexus-{short_id}.local.");
        let instance = advertisement.name.clone();

        let mut properties = HashMap::new();
        properties.insert("pv".to_string(), advertisement.protocol_version.to_string());
        properties.insert("id".to_string(), advertisement.device_id.clone());
        properties.insert("nm".to_string(), advertisement.name.clone());
        properties.insert("pf".to_string(), advertisement.platform.clone());

        let service = ServiceInfo::new(
            SERVICE_TYPE,
            &instance,
            &hostname,
            "",
            advertisement.port,
            properties,
        )
        .map_err(|e| Error::Discovery(format!("invalid service record: {e}")))?
        .enable_addr_auto();

        let own_fullname = service.get_fullname().to_string();

        daemon
            .register(service)
            .map_err(|e| Error::Discovery(format!("couldn't advertise: {e}")))?;

        let browse_receiver = daemon
            .browse(SERVICE_TYPE)
            .map_err(|e| Error::Discovery(format!("couldn't browse: {e}")))?;

        let (events_tx, events_rx) = mpsc::unbounded_channel();
        let own_id = advertisement.device_id.clone();

        let browse_task = tokio::spawn(async move {
            let mut known: HashMap<String, PeerInfo> = HashMap::new();
            while let Ok(event) = browse_receiver.recv_async().await {
                match event {
                    ServiceEvent::ServiceResolved(info) => {
                        let Some(peer) = peer_from_service(&info) else {
                            tracing::debug!(
                                fullname = info.get_fullname(),
                                "ignoring malformed Nexus advertisement"
                            );
                            continue;
                        };
                        if peer.device_id == own_id {
                            continue; // our own advertisement
                        }
                        match known.get(&peer.fullname) {
                            Some(previous) if *previous == peer => {}
                            Some(_) => {
                                known.insert(peer.fullname.clone(), peer.clone());
                                let _ = events_tx.send(DiscoveryEvent::Updated(peer));
                            }
                            None => {
                                tracing::info!(
                                    device_id = %peer.device_id,
                                    name = %peer.name,
                                    addr = ?peer.socket_addrs(),
                                    "discovered device"
                                );
                                known.insert(peer.fullname.clone(), peer.clone());
                                let _ = events_tx.send(DiscoveryEvent::Found(peer));
                            }
                        }
                    }
                    ServiceEvent::ServiceRemoved(_ty, fullname) => {
                        if let Some(peer) = known.remove(&fullname) {
                            tracing::info!(
                                device_id = %peer.device_id,
                                name = %peer.name,
                                "device disappeared"
                            );
                            let _ = events_tx.send(DiscoveryEvent::Lost {
                                device_id: peer.device_id,
                                fullname,
                            });
                        }
                    }
                    _ => {}
                }
            }
        });

        Ok(Self {
            daemon,
            events: events_rx,
            browse_task,
            own_fullname: Some(own_fullname),
        })
    }

    /// Receive the next discovery event.
    pub async fn next_event(&mut self) -> Option<DiscoveryEvent> {
        self.events.recv().await
    }

    /// Full name of this device's own mDNS advertisement.
    pub fn own_fullname(&self) -> Option<&str> {
        self.own_fullname.as_deref()
    }

    /// Stop advertising and browsing.
    pub fn shutdown(self) {
        self.browse_task.abort();
        let _ = self.daemon.shutdown();
    }
}

fn peer_from_service(info: &ServiceInfo) -> Option<PeerInfo> {
    let device_id = info.get_property_val_str("id")?.to_string();
    let name = info
        .get_property_val_str("nm")
        .unwrap_or("Unknown device")
        .to_string();
    let platform = info
        .get_property_val_str("pf")
        .unwrap_or("unknown")
        .to_string();
    let protocol_version = info
        .get_property_val_str("pv")
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(0);

    let mut addresses: Vec<IpAddr> = info.get_addresses().iter().copied().collect();
    addresses.sort();

    Some(PeerInfo {
        device_id,
        name,
        platform,
        protocol_version,
        addresses,
        port: info.get_port(),
        fullname: info.get_fullname().to_string(),
        hostname: info.get_hostname().to_string(),
    })
}

fn addr_preference(ip: IpAddr) -> u8 {
    match ip {
        IpAddr::V4(_) => 0,
        IpAddr::V6(v6) => {
            if v6.is_loopback() {
                1
            } else if v6.is_unicast_link_local() {
                10
            } else if v6.is_unique_local() {
                2
            } else {
                3
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn socket_addrs_prefers_ipv4_over_link_local() {
        use std::net::IpAddr;
        let peer = PeerInfo {
            device_id: "id".into(),
            name: "n".into(),
            platform: "linux".into(),
            protocol_version: 1,
            addresses: vec![
                "fe80::1".parse::<IpAddr>().unwrap(),
                "192.0.2.5".parse::<IpAddr>().unwrap(),
                "2001:db8::1".parse::<IpAddr>().unwrap(),
            ],
            port: 1234,
            fullname: "full".into(),
            hostname: "host.local.".into(),
        };
        let addrs = peer.socket_addrs();
        assert!(addrs[0].is_ipv4());
        assert_eq!(addrs[0].ip().to_string(), "192.0.2.5");
    }

    #[test]
    fn socket_addrs_still_includes_ipv6() {
        use std::net::IpAddr;
        let peer = PeerInfo {
            device_id: "id".into(),
            name: "n".into(),
            platform: "linux".into(),
            protocol_version: 1,
            addresses: vec!["fe80::abcd".parse::<IpAddr>().unwrap()],
            port: 9999,
            fullname: "full".into(),
            hostname: "host.local.".into(),
        };
        let addrs = peer.socket_addrs();
        assert!(!addrs.is_empty());
        assert!(addrs[0].is_ipv6());
    }

    #[test]
    fn compatibility_follows_protocol_version() {
        let mut peer = PeerInfo {
            device_id: "id".into(),
            name: "n".into(),
            platform: "linux".into(),
            protocol_version: crate::protocol::PROTOCOL_VERSION,
            addresses: vec![],
            port: 0,
            fullname: "full".into(),
            hostname: "host.local.".into(),
        };
        assert!(peer.is_compatible());
        peer.protocol_version = 99;
        assert!(!peer.is_compatible());
    }
}
