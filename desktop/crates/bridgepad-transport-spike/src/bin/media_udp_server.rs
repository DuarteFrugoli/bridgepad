//! LAN sender for the Android raw UDP+AEAD diagnostic.
//!
//! The fixed key and handshake are test-only. Production sessions must install
//! fresh directional keys through the authenticated control plane.

use bridgepad_media::assembly::{PacketizeRequest, packetize_with_max_datagram_size};
use bridgepad_media_protocol::security::{MAX_PLAINTEXT_SIZE, UdpAeadSender};
use bridgepad_media_protocol::{PacketFlags, PacketKind};
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

const DEFAULT_BIND: &str = "0.0.0.0:39495";
const FRAMES_PER_SECOND: u32 = 60;
const TARGET_BITRATE_BITS_PER_SECOND: usize = 8_000_000;
const SESSION_ID: u64 = 0x4250_4d45_4449_4131;
const STREAM_ID: u32 = 1;
const KEY_EPOCH: u32 = 1;
const HELLO_MAGIC: [u8; 8] = *b"BPT1HELO";
const DONE_MAGIC: [u8; 8] = *b"BPT1DONE";
const TEST_KEY: [u8; 32] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
    0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
];

type AnyError = Box<dyn std::error::Error + Send + Sync>;

fn main() -> Result<(), AnyError> {
    let bind = string_argument("--bind").unwrap_or_else(|| DEFAULT_BIND.to_owned());
    let socket = UdpSocket::bind(&bind)?;
    println!("BridgePad Media v1 UDP+AEAD Android diagnostic");
    println!("Listening on {}", socket.local_addr()?);
    println!("Open Settings > Tests > Media v1 transport test on Android.");
    println!("This diagnostic uses a public fixed test key and is not a product session.\n");

    let (remote, seconds) = wait_for_probe(&socket)?;
    println!("Android probe connected from {remote}; duration={seconds}s");
    let measurement = send_workload(&socket, remote, seconds)?;
    thread::sleep(Duration::from_millis(50));
    for _ in 0..3 {
        socket.send_to(&DONE_MAGIC, remote)?;
    }
    println!(
        "Sent {} packets / {} frames in {:.3}s",
        measurement.sent_packets,
        measurement.sent_frames,
        measurement.elapsed.as_secs_f64(),
    );
    Ok(())
}

fn wait_for_probe(socket: &UdpSocket) -> Result<(SocketAddr, u16), AnyError> {
    let mut buffer = [0_u8; 64];
    loop {
        let (size, remote) = socket.recv_from(&mut buffer)?;
        if size != 10 || buffer[..8] != HELLO_MAGIC {
            continue;
        }
        let seconds = u16::from_be_bytes([buffer[8], buffer[9]]);
        if (1..=300).contains(&seconds) {
            return Ok((remote, seconds));
        }
    }
}

fn send_workload(
    socket: &UdpSocket,
    remote: SocketAddr,
    seconds: u16,
) -> Result<SendMeasurement, AnyError> {
    let frame_count = usize::from(seconds) * usize::try_from(FRAMES_PER_SECOND)?;
    let bytes_per_frame = TARGET_BITRATE_BITS_PER_SECOND / 8 / usize::try_from(FRAMES_PER_SECOND)?;
    let payload = vec![0x5a; bytes_per_frame];
    let frame_interval = Duration::from_secs_f64(1.0 / f64::from(FRAMES_PER_SECOND));
    let started = Instant::now();
    let mut deadline = started;
    let mut sequence = 0_u32;
    let mut sent_packets = 0_usize;
    let mut aead = UdpAeadSender::new(&TEST_KEY, KEY_EPOCH)?;

    for frame_id in 0..frame_count {
        let now = Instant::now();
        if deadline > now {
            thread::sleep(deadline - now);
        }
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
            let sealed = aead.seal(&packet)?;
            let written = socket.send_to(&sealed, remote)?;
            if written != sealed.len() {
                return Err(format!("UDP wrote {written} of {} bytes", sealed.len()).into());
            }
            sent_packets += 1;
        }
        deadline = started + frame_interval.saturating_mul(frame_id + 1);
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
