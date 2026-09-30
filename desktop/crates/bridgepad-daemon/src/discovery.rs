use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

pub const SERVICE_TYPE: &str = "_bridgepad._tcp.local.";

pub fn advertise(
    desktop_name: &str,
    port: u16,
    media_port: u16,
    peer_id: &str,
    fingerprint: &str,
) -> Result<ServiceDaemon, Box<dyn std::error::Error + Send + Sync>> {
    let daemon = ServiceDaemon::new()?;
    // USB tethering creates and removes a network interface while the Desktop
    // keeps running. The mdns-sd default is five seconds, which is long enough
    // for Android to reuse the now-unreachable Wi-Fi endpoint. Detect route
    // changes promptly and let addr_auto announce the service on the new link.
    daemon.set_ip_check_interval(1)?;
    let mut properties = HashMap::new();
    properties.insert("id".to_owned(), peer_id.to_owned());
    properties.insert("fp".to_owned(), fingerprint.to_owned());
    properties.insert("v".to_owned(), "1".to_owned());
    properties.insert("pair".to_owned(), "1".to_owned());
    properties.insert("mp".to_owned(), media_port.to_string());
    let host_name = format!("bridgepad-{}.local.", &peer_id[..12]);
    let service = ServiceInfo::new(SERVICE_TYPE, desktop_name, &host_name, "", port, properties)?
        .enable_addr_auto();
    daemon.register(service)?;
    Ok(daemon)
}
