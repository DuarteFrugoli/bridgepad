//! Desktop loopback preflight for the `BridgePad` Media v1 transport bake-off.
//!
//! This does not select a production transport. It proves that both candidates
//! carry the same bounded Media v1 workload before the Android Wi-Fi/USB gate.

use bridgepad_media::assembly::{
    FrameAssembler, PacketizeRequest, packetize_with_max_datagram_size,
};
use bridgepad_media_protocol::security::{MAX_PLAINTEXT_SIZE, UdpAeadReceiver, UdpAeadSender};
use bridgepad_media_protocol::{PacketFlags, PacketKind};
use rcgen::{CertifiedKey, generate_simple_self_signed};
use rustls::RootCertStore;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::io;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use tokio::time::{MissedTickBehavior, interval, timeout};

const DEFAULT_SECONDS: u64 = 5;
const FRAMES_PER_SECOND: u32 = 60;
const TARGET_BITRATE_BITS_PER_SECOND: usize = 8_000_000;
const SESSION_ID: u64 = 0x4250_4d45_4449_4131;
const STREAM_ID: u32 = 1;
const KEY_EPOCH: u32 = 1;
const TEST_KEY: [u8; 32] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
    0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
];

type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Debug)]
struct Measurement {
    sent_packets: usize,
    received_packets: usize,
    completed_frames: usize,
    elapsed: Duration,
    arrival_gap_p95: Duration,
    arrival_gap_p99: Duration,
    arrival_gap_max: Duration,
}

#[tokio::main]
async fn main() -> Result<(), AnyError> {
    let seconds = argument("--seconds").unwrap_or(DEFAULT_SECONDS);
    if seconds == 0 {
        return Err("--seconds must be positive".into());
    }
    let frame_count = usize::try_from(seconds)? * usize::try_from(FRAMES_PER_SECOND)?;
    let bytes_per_frame = TARGET_BITRATE_BITS_PER_SECOND / 8 / usize::try_from(FRAMES_PER_SECOND)?;

    println!("BridgePad Media v1 desktop transport preflight");
    println!(
        "workload=720p60 timing, target=8 Mbps, frames={frame_count}, bytes/frame={bytes_per_frame}"
    );
    println!("This loopback preflight does not replace the Android Wi-Fi/USB Gate T1.\n");

    let quic = measure_quic(frame_count, bytes_per_frame).await?;
    let udp = measure_udp_aead(frame_count, bytes_per_frame).await?;
    print_measurement("QUIC DATAGRAM", &quic);
    print_measurement("UDP + AES-256-GCM", &udp);
    Ok(())
}

async fn measure_quic(frame_count: usize, bytes_per_frame: usize) -> Result<Measurement, AnyError> {
    let CertifiedKey { cert, signing_key } =
        generate_simple_self_signed(vec!["localhost".to_owned()])?;
    let certificate: CertificateDer<'static> = cert.der().clone();
    let private_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(signing_key.serialize_der()));
    let mut server_config =
        quinn::ServerConfig::with_single_cert(vec![certificate.clone()], private_key)?;
    configure_shared_quic_transport(&mut server_config.transport);
    let server_endpoint = quinn::Endpoint::server(server_config, "127.0.0.1:0".parse()?)?;
    let address = server_endpoint.local_addr()?;

    let mut roots = RootCertStore::empty();
    roots.add(certificate)?;
    let mut client_config = quinn::ClientConfig::with_root_certificates(Arc::new(roots))?;
    let mut client_transport = quinn::TransportConfig::default();
    configure_quic_transport(&mut client_transport);
    client_config.transport_config(Arc::new(client_transport));
    let mut client_endpoint = quinn::Endpoint::client("127.0.0.1:0".parse()?)?;
    client_endpoint.set_default_client_config(client_config);

    let packets_per_frame = packet_count(bytes_per_frame)?;
    let expected_packets = packets_per_frame * frame_count;
    let receiver_handle = tokio::spawn(async move {
        let incoming = server_endpoint.accept().await.ok_or_else(|| {
            io::Error::new(io::ErrorKind::ConnectionAborted, "QUIC endpoint closed")
        })?;
        let connection = incoming.await?;
        receive_quic(connection, expected_packets).await
    });

    let connection = client_endpoint.connect(address, "localhost")?.await?;
    let started = Instant::now();
    let sent_packets = send_frames(frame_count, bytes_per_frame, |packet| {
        connection
            .send_datagram(packet.to_vec().into())
            .map_err(io::Error::other)
    })
    .await?;
    let receive_measurement = receiver_handle.await??;
    connection.close(0_u32.into(), b"preflight complete");
    Ok(receive_measurement.finish(sent_packets, started.elapsed()))
}

async fn measure_udp_aead(
    frame_count: usize,
    bytes_per_frame: usize,
) -> Result<Measurement, AnyError> {
    let receiver_socket = UdpSocket::bind("127.0.0.1:0").await?;
    let receiver_address = receiver_socket.local_addr()?;
    let sender_socket = UdpSocket::bind("127.0.0.1:0").await?;
    sender_socket.connect(receiver_address).await?;
    let packets_per_frame = packet_count(bytes_per_frame)?;
    let expected_packets = packets_per_frame * frame_count;
    let receiver_handle = tokio::spawn(receive_udp(receiver_socket, expected_packets));
    let mut aead = UdpAeadSender::new(&TEST_KEY, KEY_EPOCH)?;

    let started = Instant::now();
    let sent_packets = send_frames(frame_count, bytes_per_frame, |packet| {
        let sealed = aead.seal(packet).map_err(io::Error::other)?;
        sender_socket
            .try_send(&sealed)
            .and_then(|written| ensure_complete_write(written, sealed.len()))
    })
    .await?;
    let receive_measurement = receiver_handle.await??;
    Ok(receive_measurement.finish(sent_packets, started.elapsed()))
}

fn configure_shared_quic_transport(config: &mut Arc<quinn::TransportConfig>) {
    let transport = Arc::get_mut(config).expect("new QUIC transport config is uniquely owned");
    configure_quic_transport(transport);
}

fn configure_quic_transport(config: &mut quinn::TransportConfig) {
    config.datagram_receive_buffer_size(Some(4 * 1024 * 1024));
    config.datagram_send_buffer_size(4 * 1024 * 1024);
}

fn packet_count(bytes_per_frame: usize) -> Result<usize, AnyError> {
    Ok(packetize_frame(0, 0, &vec![0x5a; bytes_per_frame])?.len())
}

async fn send_frames(
    frame_count: usize,
    bytes_per_frame: usize,
    mut send: impl FnMut(&[u8]) -> io::Result<()>,
) -> Result<usize, AnyError> {
    let payload = vec![0x5a; bytes_per_frame];
    let mut sequence = 0_u32;
    let mut sent_packets = 0_usize;
    let mut pacing = interval(Duration::from_secs_f64(1.0 / f64::from(FRAMES_PER_SECOND)));
    pacing.set_missed_tick_behavior(MissedTickBehavior::Skip);
    for frame_id in 0..frame_count {
        pacing.tick().await;
        let frame_id = u32::try_from(frame_id)?;
        let packets = packetize_frame(frame_id, sequence, &payload)?;
        sequence = sequence.wrapping_add(u32::try_from(packets.len())?);
        for packet in packets {
            send(&packet)?;
            sent_packets += 1;
        }
    }
    Ok(sent_packets)
}

fn packetize_frame(frame_id: u32, sequence: u32, payload: &[u8]) -> Result<Vec<Vec<u8>>, AnyError> {
    let request = PacketizeRequest {
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
        payload,
    };
    // Keep the same conservative inner datagram budget for this preflight.
    // QUIC reports a path-dependent maximum that can be smaller than the
    // Media v1 absolute limit once QUIC packet overhead is included.
    Ok(packetize_with_max_datagram_size(
        request,
        MAX_PLAINTEXT_SIZE,
    )?)
}

async fn receive_quic(
    connection: quinn::Connection,
    expected_packets: usize,
) -> Result<ReceiveMeasurement, AnyError> {
    let mut receiver = Receiver::new(false)?;
    while receiver.received_packets < expected_packets {
        let packet = timeout(Duration::from_secs(2), connection.read_datagram()).await??;
        receiver.push(&packet)?;
    }
    Ok(receiver.measurement)
}

async fn receive_udp(
    socket: UdpSocket,
    expected_packets: usize,
) -> Result<ReceiveMeasurement, AnyError> {
    let mut receiver = Receiver::new(true)?;
    let mut buffer = vec![0_u8; 1_200];
    while receiver.received_packets < expected_packets {
        let received_bytes = timeout(Duration::from_secs(2), socket.recv(&mut buffer)).await??;
        receiver.push(&buffer[..received_bytes])?;
    }
    Ok(receiver.measurement)
}

struct Receiver {
    aead: Option<UdpAeadReceiver>,
    assembler: FrameAssembler,
    origin: Instant,
    previous_arrival: Option<Instant>,
    received_packets: usize,
    measurement: ReceiveMeasurement,
}

impl Receiver {
    fn new(encrypted: bool) -> Result<Self, AnyError> {
        Ok(Self {
            aead: encrypted
                .then(|| UdpAeadReceiver::new(&TEST_KEY, KEY_EPOCH))
                .transpose()?,
            assembler: FrameAssembler::new(SESSION_ID, STREAM_ID, 10_000, 4),
            origin: Instant::now(),
            previous_arrival: None,
            received_packets: 0,
            measurement: ReceiveMeasurement::default(),
        })
    }

    fn push(&mut self, packet: &[u8]) -> Result<(), AnyError> {
        let now = Instant::now();
        if let Some(previous) = self.previous_arrival.replace(now) {
            self.measurement.arrival_gaps.push(now - previous);
        }
        let plaintext = if let Some(aead) = &mut self.aead {
            aead.open(packet)?
        } else {
            packet.to_vec()
        };
        self.received_packets += 1;
        self.measurement.received_packets = self.received_packets;
        let received_at = u64::try_from(self.origin.elapsed().as_micros()).unwrap_or(u64::MAX);
        if self.assembler.push(&plaintext, received_at)?.is_some() {
            self.measurement.completed_frames += 1;
        }
        Ok(())
    }
}

#[derive(Default)]
struct ReceiveMeasurement {
    received_packets: usize,
    completed_frames: usize,
    arrival_gaps: Vec<Duration>,
}

impl ReceiveMeasurement {
    fn finish(mut self, sent_packets: usize, elapsed: Duration) -> Measurement {
        self.arrival_gaps.sort_unstable();
        Measurement {
            sent_packets,
            received_packets: self.received_packets,
            completed_frames: self.completed_frames,
            elapsed,
            arrival_gap_p95: percentile(&self.arrival_gaps, 95),
            arrival_gap_p99: percentile(&self.arrival_gaps, 99),
            arrival_gap_max: self.arrival_gaps.last().copied().unwrap_or_default(),
        }
    }
}

fn ensure_complete_write(written: usize, expected: usize) -> io::Result<()> {
    if written == expected {
        Ok(())
    } else {
        Err(io::Error::new(
            io::ErrorKind::WriteZero,
            format!("UDP wrote {written} of {expected} bytes"),
        ))
    }
}

fn percentile(sorted: &[Duration], percentile: usize) -> Duration {
    if sorted.is_empty() {
        return Duration::ZERO;
    }
    let index = ((sorted.len() - 1) * percentile).div_ceil(100);
    sorted[index]
}

fn print_measurement(name: &str, result: &Measurement) {
    let lost_packets = u32::try_from(result.sent_packets.saturating_sub(result.received_packets))
        .unwrap_or(u32::MAX);
    let sent_packets = u32::try_from(result.sent_packets).unwrap_or(u32::MAX);
    let loss = 100.0 * f64::from(lost_packets) / f64::from(sent_packets);
    println!("{name}:");
    println!(
        "  packets {}/{} | loss {:.3}% | complete frames {}",
        result.received_packets, result.sent_packets, loss, result.completed_frames
    );
    println!("  elapsed {:.3} s", result.elapsed.as_secs_f64());
    println!(
        "  arrival gap p95/p99/max {:.3}/{:.3}/{:.3} ms\n",
        duration_ms(result.arrival_gap_p95),
        duration_ms(result.arrival_gap_p99),
        duration_ms(result.arrival_gap_max),
    );
}

fn duration_ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}

fn argument(name: &str) -> Option<u64> {
    let mut arguments = std::env::args();
    while let Some(argument) = arguments.next() {
        if argument == name {
            return arguments.next()?.parse().ok();
        }
    }
    None
}
