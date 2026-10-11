//! Authenticated local QUIC media session. The TCP/TLS media plane grants a
//! single-use token; QUIC presents the same persistent Desktop certificate.

use super::{AnyError, DiagnosticIdentity, write_response};
use bridgepad_media::assembly::{PacketizeRequest, packetize_with_max_datagram_size};
use bridgepad_media_protocol::{PacketFlags, PacketKind};
use bridgepad_protocol::{MessageType, PacketHeader};
use bridgepad_windows_audio::{AudioCaptureError, AudioConfig, WindowsAudioLoopback};
use bridgepad_windows_capture::{CaptureConfig, CaptureError, WindowsCapturePipeline};
use quinn::{Connection, Endpoint, ServerConfig};
use ring::rand::{SecureRandom, SystemRandom};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::io::Write;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;
use tokio::time::timeout;

const HELLO_MAGIC: [u8; 8] = *b"BPMQPLAY";
const AUTH_TIMEOUT: Duration = Duration::from_secs(15);
const PACKET_BUDGET: Duration = Duration::from_millis(35);
const MAX_MEDIA_DATAGRAM: usize = 1_100;

pub(super) fn run(
    signaling: &mut impl Write,
    request: PacketHeader,
    capture_config: CaptureConfig,
    peer: SocketAddr,
    cancelled: &AtomicBool,
    identity: &DiagnosticIdentity,
) -> Result<(), AnyError> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async {
        let mut config = ServerConfig::with_single_cert(
            vec![CertificateDer::from(identity.certificate.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.private_key.clone())),
        )?;
        let transport = Arc::get_mut(&mut config.transport)
            .ok_or("QUIC transport config is shared before setup")?;
        transport.datagram_send_buffer_size(4 * 1024 * 1024);
        transport.datagram_receive_buffer_size(Some(1024 * 1024));
        transport.max_idle_timeout(Some(quinn::IdleTimeout::try_from(Duration::from_secs(10))?));
        let endpoint = Endpoint::server(config, "0.0.0.0:0".parse()?)?;
        let port = endpoint.local_addr()?.port();
        let mut token = [0_u8; 32];
        SystemRandom::new()
            .fill(&mut token)
            .map_err(|_| "could not generate QUIC media token")?;
        let mut ready = Vec::with_capacity(34);
        ready.extend_from_slice(&port.to_be_bytes());
        ready.extend_from_slice(&token);
        write_response(signaling, request, MessageType::QuicReady, &ready)?;

        let deadline = Instant::now() + AUTH_TIMEOUT;
        let connection = loop {
            if cancelled.load(Ordering::Acquire) || Instant::now() >= deadline {
                return Err("QUIC media connection timed out or was cancelled".into());
            }
            let incoming = match timeout(Duration::from_millis(250), endpoint.accept()).await {
                Ok(Some(incoming)) => incoming,
                Ok(None) => return Err("QUIC media endpoint closed".into()),
                Err(_) => continue,
            };
            let Ok(Ok(candidate)) = timeout(Duration::from_secs(5), incoming).await else {
                continue;
            };
            if authenticate(&candidate, request.session_id, &token)
                .await
                .is_ok()
            {
                break candidate;
            }
            candidate.close(1_u32.into(), b"invalid media session token");
        };
        println!(
            "QUIC media authenticated from {}",
            connection.remote_address()
        );
        let result = stream_media(
            &connection,
            request.session_id,
            capture_config,
            cancelled,
            peer,
        )
        .await;
        connection.close(0_u32.into(), b"media session ended");
        endpoint.close(0_u32.into(), b"media session ended");
        result
    })
}

async fn authenticate(
    connection: &Connection,
    session_id: u64,
    token: &[u8; 32],
) -> Result<(), AnyError> {
    let mut stream = timeout(Duration::from_secs(5), connection.accept_uni()).await??;
    let mut hello = [0_u8; 48];
    timeout(Duration::from_secs(5), stream.read_exact(&mut hello)).await??;
    if !valid_hello(&hello, session_id, token) {
        return Err("QUIC media token does not match the authenticated TLS session".into());
    }
    Ok(())
}

fn valid_hello(hello: &[u8; 48], session_id: u64, token: &[u8; 32]) -> bool {
    hello[..8] == HELLO_MAGIC
        && hello[8..16] == session_id.to_be_bytes()
        && bool::from(hello[16..].ct_eq(token))
}

async fn stream_media(
    connection: &Connection,
    session_id: u64,
    config: CaptureConfig,
    cancelled: &AtomicBool,
    peer: SocketAddr,
) -> Result<(), AnyError> {
    let capture = WindowsCapturePipeline::start_primary(config)?;
    let audio = WindowsAudioLoopback::start(AudioConfig::default())?;
    let mut video_sequence = 0_u32;
    let mut video_frame_id = 0_u32;
    let mut audio_sequence = 0_u32;
    let mut audio_frame_id = 0_u32;
    let mut sent_video = 0_u64;
    let mut dropped_video = 0_u64;
    let mut last_report = Instant::now();
    let started = Instant::now();
    println!(
        "QUIC playable stream started for {peer}: {}x{}@{} H.264 + Opus",
        config.width, config.height, config.frames_per_second,
    );

    while !cancelled.load(Ordering::Acquire) && connection.close_reason().is_none() {
        loop {
            match audio.try_recv() {
                Ok(Some(frame)) => {
                    let timestamp = u64::try_from(started.elapsed().as_micros())?;
                    send_frame(
                        connection,
                        PacketKind::Audio,
                        PacketFlags::NONE,
                        session_id,
                        2,
                        &mut audio_sequence,
                        &mut audio_frame_id,
                        timestamp,
                        &frame.data,
                    )
                    .await?;
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
                let flags = if frame.keyframe {
                    PacketFlags::KEYFRAME
                } else {
                    PacketFlags::NONE
                };
                let timestamp = u64::try_from(frame.presentation_timestamp.as_micros())?;
                let sent = send_frame(
                    connection,
                    PacketKind::Video,
                    flags,
                    session_id,
                    1,
                    &mut video_sequence,
                    &mut video_frame_id,
                    timestamp,
                    &frame.data,
                )
                .await?;
                if sent {
                    sent_video += 1;
                } else {
                    dropped_video += 1;
                    if frame.keyframe {
                        let _ = capture.request_keyframe();
                    }
                }
            }
            Err(CaptureError::Timeout) => {}
            Err(CaptureError::Stopped) => break,
            Err(error) => return Err(error.into()),
        }
        if last_report.elapsed() >= Duration::from_secs(2) {
            println!("QUIC media: video sent={sent_video}, skipped={dropped_video}");
            last_report = Instant::now();
        }
        tokio::task::yield_now().await;
    }
    println!("QUIC playable stream stopped for {peer}");
    Ok(())
}

async fn send_frame(
    connection: &Connection,
    kind: PacketKind,
    flags: PacketFlags,
    session_id: u64,
    stream_id: u32,
    sequence: &mut u32,
    frame_id: &mut u32,
    timestamp: u64,
    payload: &[u8],
) -> Result<bool, AnyError> {
    let budget = connection
        .max_datagram_size()
        .ok_or("QUIC peer disabled datagrams")?
        .min(MAX_MEDIA_DATAGRAM);
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
        budget,
    )?;
    *sequence = sequence.wrapping_add(u32::try_from(packets.len())?);
    *frame_id = frame_id.wrapping_add(1);
    let deadline = Instant::now() + PACKET_BUDGET;
    for packet in packets {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Ok(false);
        }
        match timeout(remaining, connection.send_datagram_wait(packet.into())).await {
            Ok(Ok(())) => {}
            Ok(Err(error)) => return Err(error.into()),
            Err(_) => return Ok(false),
        }
    }
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quic_media_requires_the_tls_session_and_its_one_use_token() {
        let token = [0x5a; 32];
        let mut hello = [0_u8; 48];
        hello[..8].copy_from_slice(&HELLO_MAGIC);
        hello[8..16].copy_from_slice(&42_u64.to_be_bytes());
        hello[16..].copy_from_slice(&token);
        assert!(valid_hello(&hello, 42, &token));
        assert!(!valid_hello(&hello, 43, &token));
        hello[47] ^= 1;
        assert!(!valid_hello(&hello, 42, &token));
    }
}
