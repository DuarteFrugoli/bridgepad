//! LAN sender for the Android QUIC DATAGRAM diagnostic.
//!
//! The certificate and private key are generated for each test process. Product
//! sessions must use identity and authorization negotiated by the control plane.

use bridgepad_media::assembly::{PacketizeRequest, packetize_with_max_datagram_size};
use bridgepad_media_protocol::security::MAX_PLAINTEXT_SIZE;
use bridgepad_media_protocol::{PacketFlags, PacketKind};
use bridgepad_transport_spike::print_android_report;
use quinn::{ConnectionError, Endpoint, SendDatagramError, ServerConfig, TransportConfig};
use rcgen::{CertifiedKey, generate_simple_self_signed};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::io;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::time::{MissedTickBehavior, interval, timeout};

const DEFAULT_BIND: &str = "0.0.0.0:39496";
const FRAMES_PER_SECOND: u32 = 60;
const TARGET_BITRATE_BITS_PER_SECOND: usize = 8_000_000;
const SESSION_ID: u64 = 0x4250_4d45_4449_4131;
const STREAM_ID: u32 = 1;
const HELLO_MAGIC: [u8; 8] = *b"BPTQHELO";
const DONE_MAGIC: [u8; 8] = *b"BPTQDONE";
const SUMMARY_MAGIC: [u8; 8] = *b"BPTQSUM!";
const REPORT_MAGIC: [u8; 8] = *b"BPTQRPT!";

type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[tokio::main]
async fn main() -> Result<(), AnyError> {
    let bind = string_argument("--bind").unwrap_or_else(|| DEFAULT_BIND.to_owned());
    let endpoint = Endpoint::server(server_config()?, bind.parse()?)?;
    println!("BridgePad Media v1 QUIC DATAGRAM Android diagnostic");
    println!("Listening on {}", endpoint.local_addr()?);
    println!("Open Settings > Tests > Media v1 transport test on Android.");
    println!("This diagnostic uses an ephemeral test identity and is not a product session.\n");

    loop {
        let incoming = endpoint.accept().await.ok_or_else(|| {
            io::Error::new(io::ErrorKind::ConnectionAborted, "QUIC endpoint closed")
        })?;
        let connecting_started = Instant::now();
        let connection = timeout(Duration::from_secs(10), incoming).await??;
        let handshake = connecting_started.elapsed();
        let remote = connection.remote_address();
        let seconds = match receive_hello(&connection).await {
            Ok(seconds) => seconds,
            Err(error) if client_cancelled(&error) => {
                println!("Android cancelled the QUIC diagnostic before it started; waiting again");
                continue;
            }
            Err(error) => return Err(error),
        };
        println!(
            "Android QUIC client connected from {remote}; handshake={:.3}ms; duration={seconds}s",
            handshake.as_secs_f64() * 1_000.0,
        );
        let measurement = match send_workload(&connection, seconds).await {
            Ok(measurement) => measurement,
            Err(error) if client_cancelled(&error) => {
                println!("Android cancelled the QUIC diagnostic; waiting for another attempt");
                continue;
            }
            Err(error) => return Err(error),
        };
        send_summary(&connection, measurement.sent_packets).await?;
        tokio::time::sleep(Duration::from_millis(50)).await;
        for _ in 0..3 {
            let _ = connection.send_datagram(DONE_MAGIC.to_vec().into());
        }
        println!(
            "Sent {} QUIC datagrams / {} complete frames ({} skipped after MTU changes) in {:.3}s",
            measurement.sent_packets,
            measurement.sent_frames,
            measurement.skipped_frames,
            measurement.elapsed.as_secs_f64(),
        );
        match receive_android_report(&connection).await? {
            Some(report) => print_android_report("QUIC DATAGRAM", &report)?,
            None => println!("Android result was not returned within 7 seconds"),
        }
        connection.close(0_u32.into(), b"diagnostic complete");
        endpoint.wait_idle().await;
        return Ok(());
    }
}

async fn send_summary(connection: &quinn::Connection, sent_packets: usize) -> Result<(), AnyError> {
    let mut stream = timeout(Duration::from_secs(2), connection.open_uni()).await??;
    stream
        .write_all(&quic_summary(u64::try_from(sent_packets)?))
        .await?;
    stream.finish()?;
    Ok(())
}

fn quic_summary(sent_packets: u64) -> [u8; 16] {
    let mut summary = [0_u8; 16];
    summary[..8].copy_from_slice(&SUMMARY_MAGIC);
    summary[8..].copy_from_slice(&sent_packets.to_be_bytes());
    summary
}

async fn receive_android_report(
    connection: &quinn::Connection,
) -> Result<Option<String>, AnyError> {
    let mut stream = match timeout(Duration::from_secs(7), connection.accept_uni()).await {
        Ok(result) => result?,
        Err(_) => return Ok(None),
    };
    let report = timeout(Duration::from_secs(2), stream.read_to_end(4_096)).await??;
    if !report.starts_with(&REPORT_MAGIC) {
        return Err("invalid QUIC Android report magic".into());
    }
    Ok(Some(String::from_utf8(
        report[REPORT_MAGIC.len()..].to_vec(),
    )?))
}

fn client_cancelled(error: &AnyError) -> bool {
    if let Some(error) = error.downcast_ref::<ConnectionError>() {
        return connection_error_cancelled(error);
    }
    error
        .downcast_ref::<SendDatagramError>()
        .is_some_and(|error| match error {
            SendDatagramError::ConnectionLost(error) => connection_error_cancelled(error),
            _ => false,
        })
}

fn connection_error_cancelled(error: &ConnectionError) -> bool {
    matches!(
        error,
        ConnectionError::ApplicationClosed(close) if close.reason.as_ref() == b"cancelled"
    )
}

fn server_config() -> Result<ServerConfig, AnyError> {
    let CertifiedKey { cert, signing_key } =
        generate_simple_self_signed(vec!["bridgepad.test".to_owned()])?;
    let certificate: CertificateDer<'static> = cert.der().clone();
    let private_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(signing_key.serialize_der()));
    let mut config = ServerConfig::with_single_cert(vec![certificate], private_key)?;
    let transport =
        Arc::get_mut(&mut config.transport).expect("new QUIC transport config is uniquely owned");
    configure_transport(transport);
    Ok(config)
}

fn configure_transport(config: &mut TransportConfig) {
    config.datagram_receive_buffer_size(Some(4 * 1024 * 1024));
    config.datagram_send_buffer_size(4 * 1024 * 1024);
    config.max_idle_timeout(Some(
        quinn::IdleTimeout::try_from(Duration::from_secs(15)).expect("valid idle timeout"),
    ));
}

async fn receive_hello(connection: &quinn::Connection) -> Result<u16, AnyError> {
    let mut stream = timeout(Duration::from_secs(5), connection.accept_uni()).await??;
    let mut hello = [0_u8; 10];
    timeout(Duration::from_secs(5), stream.read_exact(&mut hello)).await??;
    if hello[..8] != HELLO_MAGIC {
        return Err("invalid QUIC diagnostic hello".into());
    }
    let seconds = u16::from_be_bytes([hello[8], hello[9]]);
    if !(1..=300).contains(&seconds) {
        return Err("invalid QUIC diagnostic duration".into());
    }
    Ok(seconds)
}

async fn send_workload(
    connection: &quinn::Connection,
    seconds: u16,
) -> Result<SendMeasurement, AnyError> {
    let frame_count = usize::from(seconds) * usize::try_from(FRAMES_PER_SECOND)?;
    let bytes_per_frame = TARGET_BITRATE_BITS_PER_SECOND / 8 / usize::try_from(FRAMES_PER_SECOND)?;
    let payload = vec![0x5a; bytes_per_frame];
    let started = Instant::now();
    let mut pacing = interval(Duration::from_secs_f64(1.0 / f64::from(FRAMES_PER_SECOND)));
    pacing.set_missed_tick_behavior(MissedTickBehavior::Skip);
    let mut sequence = 0_u32;
    let mut sent_packets = 0_usize;
    let mut sent_frames = 0_usize;
    let mut skipped_frames = 0_usize;

    'frames: for frame_id in 0..frame_count {
        pacing.tick().await;
        let frame_id = u32::try_from(frame_id)?;
        // QUIC's payload budget includes its own packet overhead and can vary
        // with the path MTU. The raw UDP+AEAD plaintext limit is only a cap.
        let datagram_budget = connection
            .max_datagram_size()
            .ok_or("QUIC peer disabled datagrams")?
            .min(MAX_PLAINTEXT_SIZE);
        let packets = packetize_with_max_datagram_size(
            PacketizeRequest {
                kind: PacketKind::Video,
                flags: if frame_id.is_multiple_of(FRAMES_PER_SECOND) {
                    PacketFlags::KEYFRAME
                } else {
                    PacketFlags::NONE
                },
                session_id: SESSION_ID,
                stream_id: STREAM_ID,
                first_sequence: sequence,
                frame_id,
                presentation_timestamp_micros: u64::from(frame_id) * 1_000_000
                    / u64::from(FRAMES_PER_SECOND),
                payload: &payload,
            },
            datagram_budget,
        )?;
        sequence = sequence.wrapping_add(u32::try_from(packets.len())?);
        for packet in packets {
            match connection.send_datagram(packet.into()) {
                Ok(()) => sent_packets += 1,
                // The path MTU can shrink between packetization and send.
                // Discard this frame; the next one uses the updated budget.
                Err(SendDatagramError::TooLarge) => {
                    skipped_frames += 1;
                    continue 'frames;
                }
                Err(error) => return Err(error.into()),
            }
        }
        sent_frames += 1;
    }
    if sent_packets == 0 {
        return Err("QUIC path could not accommodate any media datagram".into());
    }
    Ok(SendMeasurement {
        sent_packets,
        sent_frames,
        skipped_frames,
        elapsed: started.elapsed(),
    })
}

struct SendMeasurement {
    sent_packets: usize,
    sent_frames: usize,
    skipped_frames: usize,
    elapsed: Duration,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quic_summary_carries_the_actual_packet_count() {
        let summary = quic_summary(5_137);
        assert_eq!(&summary[..8], &SUMMARY_MAGIC);
        assert_eq!(u64::from_be_bytes(summary[8..].try_into().unwrap()), 5_137);
    }

    #[test]
    fn smaller_quic_path_budget_produces_more_bounded_packets() {
        let payload = vec![0x5a; TARGET_BITRATE_BITS_PER_SECOND / 8 / FRAMES_PER_SECOND as usize];
        let packets = packetize_with_max_datagram_size(
            PacketizeRequest {
                kind: PacketKind::Video,
                flags: PacketFlags::NONE,
                session_id: SESSION_ID,
                stream_id: STREAM_ID,
                first_sequence: 0,
                frame_id: 0,
                presentation_timestamp_micros: 0,
                payload: &payload,
            },
            1_080,
        )
        .unwrap();
        assert_eq!(packets.len(), 17);
        assert!(packets.iter().all(|packet| packet.len() <= 1_080));
    }
}

fn string_argument(name: &str) -> Option<String> {
    let mut arguments = std::env::args();
    while let Some(argument) = arguments.next() {
        if argument == name {
            return arguments.next();
        }
    }
    None
}
