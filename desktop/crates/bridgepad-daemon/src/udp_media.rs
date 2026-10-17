//! Playable UDP media. TLS authenticates the peer and conveys fresh, single-use
//! credentials; only AEAD-protected Media v1 packets carry video and audio.

use super::{AnyError, write_response};
use bridgepad_media::assembly::{PacketizeRequest, packetize_with_max_datagram_size};
use bridgepad_media_protocol::security::{MAX_PLAINTEXT_SIZE, UdpAeadReceiver, UdpAeadSender};
use bridgepad_media_protocol::{PacketFlags, PacketKind, decode_datagram};
use bridgepad_protocol::{MessageType, PacketHeader};
use bridgepad_windows_audio::{AudioCaptureError, AudioConfig, WindowsAudioLoopback};
use bridgepad_windows_capture::{CaptureConfig, CaptureError, WindowsCapturePipeline};
use ring::rand::{SecureRandom, SystemRandom};
use std::io::{ErrorKind, Write};
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;

const HELLO_MAGIC: [u8; 8] = *b"BPMUPLAY";
const AUTH_TIMEOUT: Duration = Duration::from_secs(15);
const IDLE_TIMEOUT: Duration = Duration::from_secs(6);
const PACKET_BUDGET: Duration = Duration::from_millis(35);
const KEYFRAME_PACKET_BUDGET: Duration = Duration::from_millis(150);
const MAX_MEDIA_DATAGRAM: usize = 1_100;
const KEYFRAME_REQUEST_INTERVAL: Duration = Duration::from_millis(250);
const KEYFRAME_RECOVERY_GRACE: Duration = Duration::from_millis(500);

pub(super) fn run(
    signaling: &mut impl Write,
    request: PacketHeader,
    capture_config: CaptureConfig,
    peer: SocketAddr,
    cancelled: &AtomicBool,
) -> Result<(), AnyError> {
    let bind = if peer.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    };
    let socket = UdpSocket::bind(bind)?;
    socket.set_read_timeout(Some(Duration::from_millis(250)))?;
    let mut token = [0_u8; 32];
    let mut key = [0_u8; 32];
    let random = SystemRandom::new();
    random
        .fill(&mut token)
        .map_err(|_| "could not generate UDP media token")?;
    random
        .fill(&mut key)
        .map_err(|_| "could not generate UDP media key")?;
    let mut ready = Vec::with_capacity(66);
    ready.extend_from_slice(&socket.local_addr()?.port().to_be_bytes());
    ready.extend_from_slice(&token);
    ready.extend_from_slice(&key);
    write_response(signaling, request, MessageType::UdpMediaReady, &ready)?;

    let deadline = Instant::now() + AUTH_TIMEOUT;
    let mut control = UdpAeadReceiver::new(&key, 2)?;
    let mut incoming = [0_u8; 1_200];
    let remote = loop {
        if cancelled.load(Ordering::Acquire) || Instant::now() >= deadline {
            return Err("UDP media authentication timed out or was cancelled".into());
        }
        match socket.recv_from(&mut incoming) {
            Ok((size, source)) if source.ip() == peer.ip() => {
                let Ok(plaintext) = control.open(&incoming[..size]) else {
                    continue;
                };
                let Ok(datagram) = decode_datagram(&plaintext) else {
                    continue;
                };
                if datagram.header.session_id == request.session_id
                    && datagram.header.kind == PacketKind::Control
                    && datagram.header.stream_id == 3
                    && valid_hello(datagram.payload, request.session_id, &token)
                {
                    break source;
                }
            }
            Ok(_) => {}
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(error) => return Err(error.into()),
        }
    };
    socket.connect(remote)?;
    socket.set_nonblocking(true)?;
    println!("UDP media authenticated from {remote}");
    stream_media(
        &socket,
        request.session_id,
        capture_config,
        cancelled,
        peer,
        &key,
        control,
    )
}

fn valid_hello(bytes: &[u8], session_id: u64, token: &[u8; 32]) -> bool {
    bytes.len() == 48
        && bytes[..8] == HELLO_MAGIC
        && bytes[8..16] == session_id.to_be_bytes()
        && bool::from(bytes[16..].ct_eq(token))
}

fn stream_media(
    socket: &UdpSocket,
    session_id: u64,
    config: CaptureConfig,
    cancelled: &AtomicBool,
    peer: SocketAddr,
    key: &[u8; 32],
    mut control: UdpAeadReceiver,
) -> Result<(), AnyError> {
    let capture = WindowsCapturePipeline::start_primary(config)?;
    let audio = WindowsAudioLoopback::start(AudioConfig::default())?;
    let mut sender = UdpAeadSender::new(key, 1)?;
    let mut video_sequence = 0_u32;
    let mut video_frame_id = 0_u32;
    let mut audio_sequence = 0_u32;
    let mut audio_frame_id = 0_u32;
    let mut sent_video = 0_u64;
    let mut dropped_video = 0_u64;
    let mut sent_keyframes = 0_u64;
    let mut failed_keyframes = 0_u64;
    let mut keyframe_requests = 0_u64;
    let mut awaiting_keyframe = false;
    let mut last_keyframe_request = Instant::now() - KEYFRAME_REQUEST_INTERVAL;
    let mut last_sent_keyframe: Option<Instant> = None;
    let mut last_keyframe_failure_log = Instant::now() - Duration::from_secs(2);
    let mut last_report = Instant::now();
    let mut last_heartbeat = Instant::now();
    let started = Instant::now();
    let mut incoming = [0_u8; 1_200];
    println!(
        "UDP playable stream started for {peer}: {}x{}@{} H.264 + Opus",
        config.width, config.height, config.frames_per_second,
    );
    while !cancelled.load(Ordering::Acquire) && last_heartbeat.elapsed() < IDLE_TIMEOUT {
        loop {
            match socket.recv(&mut incoming) {
                Ok(size) => {
                    let Ok(plaintext) = control.open(&incoming[..size]) else {
                        continue;
                    };
                    let Ok(datagram) = decode_datagram(&plaintext) else {
                        continue;
                    };
                    if datagram.header.session_id != session_id
                        || datagram.header.kind != PacketKind::Control
                        || datagram.header.stream_id != 3
                    {
                        continue;
                    }
                    if datagram.payload == b"STOP" {
                        return Ok(());
                    }
                    if datagram.payload == b"PING" {
                        last_heartbeat = Instant::now();
                    }
                    if datagram.payload == b"KEYFRAME"
                        && last_keyframe_request.elapsed() >= KEYFRAME_REQUEST_INTERVAL
                        && last_sent_keyframe
                            .is_none_or(|sent| sent.elapsed() >= KEYFRAME_RECOVERY_GRACE)
                        && capture.request_keyframe().is_ok()
                    {
                        last_keyframe_request = Instant::now();
                        keyframe_requests += 1;
                    }
                    if let Some(stats) = datagram.payload.strip_prefix(b"STATS ")
                        && stats.len() <= 160
                        && stats
                            .iter()
                            .all(|byte| byte.is_ascii_graphic() || *byte == b' ')
                        && let Ok(stats) = std::str::from_utf8(stats)
                    {
                        println!("UDP Android: {stats}");
                    }
                }
                Err(error) if error.kind() == ErrorKind::WouldBlock => break,
                Err(error) => return Err(error.into()),
            }
        }
        loop {
            match audio.try_recv() {
                Ok(Some(frame)) => {
                    let timestamp = u64::try_from(started.elapsed().as_micros())?;
                    send_frame(
                        socket,
                        &mut sender,
                        PacketKind::Audio,
                        PacketFlags::NONE,
                        session_id,
                        2,
                        &mut audio_sequence,
                        &mut audio_frame_id,
                        timestamp,
                        &frame.data,
                    )?;
                }
                Ok(None) => break,
                Err(AudioCaptureError::Disconnected) => {
                    return Err("Windows audio capture stopped".into());
                }
                Err(error) => return Err(error.into()),
            }
        }
        match capture.recv_timeout(Duration::from_millis(3)) {
            Ok(frame) => {
                if awaiting_keyframe && !frame.keyframe {
                    dropped_video += 1;
                    if last_keyframe_request.elapsed() >= KEYFRAME_REQUEST_INTERVAL
                        && capture.request_keyframe().is_ok()
                    {
                        last_keyframe_request = Instant::now();
                        keyframe_requests += 1;
                    }
                    continue;
                }
                let flags = if frame.keyframe {
                    PacketFlags::KEYFRAME
                } else {
                    PacketFlags::NONE
                };
                let timestamp = u64::try_from(frame.presentation_timestamp.as_micros())?;
                let send = send_frame(
                    socket,
                    &mut sender,
                    PacketKind::Video,
                    flags,
                    session_id,
                    1,
                    &mut video_sequence,
                    &mut video_frame_id,
                    timestamp,
                    &frame.data,
                )?;
                if send.complete {
                    sent_video += 1;
                    if frame.keyframe {
                        awaiting_keyframe = false;
                        sent_keyframes += 1;
                        last_sent_keyframe = Some(Instant::now());
                    }
                } else {
                    dropped_video += 1;
                    if frame.keyframe {
                        failed_keyframes += 1;
                        if last_keyframe_failure_log.elapsed() >= Duration::from_secs(2) {
                            println!(
                                "UDP keyframe incomplete: {}/{} packets in {} ms ({} bytes, {})",
                                send.sent_packets,
                                send.total_packets,
                                send.elapsed.as_millis(),
                                frame.data.len(),
                                send.failure_reason.unwrap_or("unknown"),
                            );
                            last_keyframe_failure_log = Instant::now();
                        }
                    }
                    awaiting_keyframe = true;
                    if last_keyframe_request.elapsed() >= KEYFRAME_REQUEST_INTERVAL
                        && capture.request_keyframe().is_ok()
                    {
                        last_keyframe_request = Instant::now();
                        keyframe_requests += 1;
                    }
                }
            }
            Err(CaptureError::Timeout) => {}
            Err(CaptureError::Stopped) => break,
            Err(error) => return Err(error.into()),
        }
        if last_report.elapsed() >= Duration::from_secs(2) {
            let capture_stats = capture.stats();
            println!(
                "UDP media: video sent={sent_video}, skipped={dropped_video}, keyframes sent/failed={sent_keyframes}/{failed_keyframes}, keyframe requests={keyframe_requests}, capture captured/encoded/dropped={}/{}/{}",
                capture_stats.captured_frames,
                capture_stats.encoded_frames,
                capture_stats.dropped_before_encode,
            );
            last_report = Instant::now();
        }
    }
    println!("UDP playable stream stopped for {peer}");
    Ok(())
}

struct FrameSendStatus {
    complete: bool,
    sent_packets: usize,
    total_packets: usize,
    elapsed: Duration,
    failure_reason: Option<&'static str>,
}

#[allow(clippy::too_many_arguments)]
fn send_frame(
    socket: &UdpSocket,
    sender: &mut UdpAeadSender,
    kind: PacketKind,
    flags: PacketFlags,
    session_id: u64,
    stream_id: u32,
    sequence: &mut u32,
    frame_id: &mut u32,
    timestamp: u64,
    payload: &[u8],
) -> Result<FrameSendStatus, AnyError> {
    let packets = packetize_with_max_datagram_size(
        PacketizeRequest {
            kind,
            flags,
            session_id,
            stream_id,
            first_sequence: *sequence,
            frame_id: *frame_id,
            presentation_timestamp_micros: timestamp,
            payload,
        },
        MAX_MEDIA_DATAGRAM.min(MAX_PLAINTEXT_SIZE),
    )?;
    *sequence = sequence.wrapping_add(u32::try_from(packets.len())?);
    *frame_id = frame_id.wrapping_add(1);
    let total_packets = packets.len();
    let started = Instant::now();
    let keyframe = kind == PacketKind::Video && flags.contains(PacketFlags::KEYFRAME);
    let budget = if keyframe {
        KEYFRAME_PACKET_BUDGET
    } else {
        PACKET_BUDGET
    };
    let deadline = started + budget;
    for (index, packet) in packets.into_iter().enumerate() {
        let incomplete = |reason| FrameSendStatus {
            complete: false,
            sent_packets: index,
            total_packets,
            elapsed: started.elapsed(),
            failure_reason: Some(reason),
        };
        let sealed = sender.seal(&packet)?;
        loop {
            if Instant::now() >= deadline {
                return Ok(incomplete("time budget"));
            }
            match socket.send(&sealed) {
                Ok(size) if size == sealed.len() => break,
                Ok(_) => return Ok(incomplete("short send")),
                Err(error)
                    if error.kind() == ErrorKind::WouldBlock && kind == PacketKind::Video =>
                {
                    std::thread::yield_now();
                }
                Err(error) if error.kind() == ErrorKind::WouldBlock => {
                    return Ok(incomplete("socket busy"));
                }
                Err(error) => return Err(error.into()),
            }
        }
    }
    Ok(FrameSendStatus {
        complete: true,
        sent_packets: total_packets,
        total_packets,
        elapsed: started.elapsed(),
        failure_reason: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn udp_media_requires_the_tls_session_and_one_use_token() {
        let token = [0x5a; 32];
        let mut hello = [0_u8; 48];
        hello[..8].copy_from_slice(&HELLO_MAGIC);
        hello[8..16].copy_from_slice(&42_u64.to_be_bytes());
        hello[16..].copy_from_slice(&token);
        assert!(valid_hello(&hello, 42, &token));
        assert!(!valid_hello(&hello, 43, &token));
        assert!(!valid_hello(&hello[..47], 42, &token));
        hello[47] ^= 1;
        assert!(!valid_hello(&hello, 42, &token));
    }

    #[test]
    fn encrypted_hello_cannot_be_replayed_or_read_as_downlink() {
        let key = [0x33; 32];
        let token = [0x5a; 32];
        let mut hello = [0_u8; 48];
        hello[..8].copy_from_slice(&HELLO_MAGIC);
        hello[8..16].copy_from_slice(&42_u64.to_be_bytes());
        hello[16..].copy_from_slice(&token);
        let packet = packetize_with_max_datagram_size(
            PacketizeRequest {
                kind: PacketKind::Control,
                flags: PacketFlags::NONE,
                session_id: 42,
                stream_id: 3,
                first_sequence: 0,
                frame_id: 0,
                presentation_timestamp_micros: 0,
                payload: &hello,
            },
            MAX_MEDIA_DATAGRAM,
        )
        .unwrap()
        .remove(0);
        let sealed = UdpAeadSender::new(&key, 2).unwrap().seal(&packet).unwrap();
        let mut uplink = UdpAeadReceiver::new(&key, 2).unwrap();
        let opened = uplink.open(&sealed).unwrap();
        assert!(valid_hello(
            decode_datagram(&opened).unwrap().payload,
            42,
            &token
        ));
        assert!(uplink.open(&sealed).is_err());
        assert!(
            UdpAeadReceiver::new(&key, 1)
                .unwrap()
                .open(&sealed)
                .is_err()
        );
    }
}
