use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

pub const SERVICE_TYPE: &str = "_bridgepad._tcp.local.";

pub fn advertise(
    desktop_name: &str,
    port: u16,
    peer_id: &str,
    fingerprint: &str,
) -> Result<ServiceDaemon, Box<dyn std::error::Error + Send + Sync>> {
    let daemon = ServiceDaemon::new()?;
    let mut properties = HashMap::new();
    properties.insert("id".to_owned(), peer_id.to_owned());
    properties.insert("fp".to_owned(), fingerprint.to_owned());
    properties.insert("v".to_owned(), "1".to_owned());
    properties.insert("pair".to_owned(), "1".to_owned());
    let host_name = format!("bridgepad-{}.local.", &peer_id[..12]);
    let service = ServiceInfo::new(SERVICE_TYPE, desktop_name, &host_name, "", port, properties)?
        .enable_addr_auto();
    daemon.register(service)?;
    Ok(daemon)
}
