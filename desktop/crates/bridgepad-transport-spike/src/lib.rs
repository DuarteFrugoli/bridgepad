//! Shared presentation helpers for transport diagnostics.

/// Prints the measurement returned by the Android native receiver.
///
/// The wire representation is intentionally private to the diagnostic tools;
/// production negotiation must use the versioned `BridgePad` control protocol.
///
/// # Errors
///
/// Returns an error when the report is truncated, malformed or contains an
/// invalid handshake measurement.
pub fn print_android_report(transport: &str, encoded: &str) -> Result<(), String> {
    let fields: Vec<_> = encoded.split('\t').collect();
    if fields.len() != 16 || fields[0] != "OK" {
        return Err("invalid Android diagnostic report".to_owned());
    }
    let handshake = fields[11]
        .parse::<i64>()
        .map_err(|_| "invalid Android handshake measurement")?;

    println!("Android {transport} result:");
    println!("  Route: {} on {}", fields[1], fields[15]);
    println!(
        "  Packets: {}/{} received, {} rejected, {} late/duplicate",
        fields[2], fields[3], fields[8], fields[7],
    );
    println!(
        "  Frames: {}/{} complete, {} expired",
        fields[4], fields[5], fields[6],
    );
    println!(
        "  Arrival gap p95/p99/max: {}/{}/{} us",
        fields[12], fields[13], fields[14],
    );
    println!("  Received: {} bytes in {} ms", fields[9], fields[10]);
    if handshake >= 0 {
        println!("  Handshake: {handshake} ms");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_complete_native_report() {
        let report = "OK\t192.168.0.196\t4800\t4800\t300\t300\t0\t0\t0\t5441400\t5035\t-1\t5705\t15066\t20021\twlan0";
        assert!(print_android_report("UDP+AEAD", report).is_ok());
    }

    #[test]
    fn rejects_truncated_native_report() {
        assert!(print_android_report("UDP+AEAD", "OK\tbroken").is_err());
    }
}
