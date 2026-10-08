//! LAN sender for the Android QUIC DATAGRAM diagnostic.
//!
//! The certificate and private key are generated for each test process. Product
//! sessions must use identity and authorization negotiated by the control plane.

use bridgepad_media::assembly::{PacketizeRequest, packetize_with_max_datagram_size};
use bridgepad_media_protocol::security::MAX_PLAINTEXT_SIZE;
use bridgepad_media_protocol::{PacketFlags, PacketKind};
use quinn::{Endpoint, ServerConfig, TransportConfig};
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

type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[tokio::main]
async fn main() -> Result<(), AnyError> {
    let bind = string_argument("--bind").unwrap_or_else(|| DEFAULT_BIND.to_owned());
    let endpoint = Endpoint::server(server_config()?, bind.parse()?)?;
    println!("BridgePad Media v1 QUIC DATAGRAM Android diagnostic");
    println!("Listening on {}", endpoint.local_addr()?);
    println!("Open Settings > Tests > Media v1 transport test on Android.");
    println!("This diagnostic uses an ephemeral test identity and is not a product session.\n");

    let incoming = endpoint
        .accept()
        .await
        .ok_or_else(|| io::Error::new(io::ErrorKind::ConnectionAborted, "QUIC endpoint closed"))?;
    let connecting_started = Instant::now();
    let connection = timeout(Duration::from_secs(10), incoming).await??;
    let handshake = connecting_started.elapsed();
    let remote = connection.remote_address();
    let seconds = receive_hello(&connection).await?;
    println!(
        "Android QUIC client connected from {remote}; handshake={:.3}ms; duration={seconds}s",
        handshake.as_secs_f64() * 1_000.0,
    );
    let measurement = send_workload(&connection, seconds).await?;
    tokio::time::sleep(Duration::from_millis(50)).await;
    for _ in 0..3 {
        let _ = connection.send_datagram(DONE_MAGIC.to_vec().into());
    }
    println!(
        "Sent {} QUIC datagrams / {} frames in {:.3}s",
        measurement.sent_packets,
        measurement.sent_frames,
        measurement.elapsed.as_secs_f64(),
    );
    tokio::time::sleep(Duration::from_millis(100)).await;
    connection.close(0_u32.into(), b"diagnostic complete");
    endpoint.wait_idle().await;
    Ok(())
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

    for frame_id in 0..frame_count {
        pacing.tick().await;
        let frame_id = u32::try_from(frame_id)?;
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
            MAX_PLAINTEXT_SIZE,
        )?;
        sequence = sequence.wrapping_add(u32::try_from(packets.len())?);
        for packet in packets {
            connection.send_datagram(packet.into())?;
            sent_packets += 1;
        }
    }
    Ok(SendMeasurement {
        sent_packets,
        sent_frames: frame_count,
        elapsed: started.elapsed(),
    })
}

struct SendMeasurement {
    sent_packets: usize,
    sent_frames: usize,
    elapsed: Duration,
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
