use bridgepad_media::assembly::{
    AssembledFrame, FrameAssembler, PacketizeRequest, packetize_with_max_datagram_size,
};
use bridgepad_media_protocol::security::{UdpAeadReceiver, UdpAeadSender};
use bridgepad_media_protocol::{PacketFlags, PacketKind};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use quinn::crypto::rustls::QuicClientConfig;
use quinn::{ClientConfig, Endpoint, TransportConfig};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use sha2::{Digest, Sha256};
use socket2::{Domain, Protocol, Socket, Type};
use std::io;
use std::net::{
    IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6, ToSocketAddrs, UdpSocket,
};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{SyncSender, TrySendError, sync_channel};
use std::thread;
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;
use tokio::time::timeout;

mod udp_recovery;
use udp_recovery::{UdpVideoRecovery, VideoDecision};

const FRAMES_PER_SECOND: u64 = 60;
const PACKETS_PER_FRAME: u64 = 16;
const SESSION_ID: u64 = 0x4250_4d45_4449_4131;
const STREAM_ID: u32 = 1;
const ASSEMBLY_DEADLINE_MICROS: u64 = 10_000;
const MAX_IN_FLIGHT_FRAMES: usize = 4;
const MAX_DATAGRAM_SIZE: usize = 1_200;
const SOCKET_RECEIVE_BUFFER_SIZE: usize = 4 * 1024 * 1024;
const UDP_HELLO_MAGIC: [u8; 8] = *b"BPT1HELO";
const UDP_DONE_MAGIC: [u8; 8] = *b"BPT1DONE";
const UDP_REPORT_MAGIC: [u8; 8] = *b"BPT1RPT!";
const UDP_KEY_EPOCH: u32 = 1;
const UDP_TEST_KEY: [u8; 32] = [
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
    26, 27, 28, 29, 30, 31,
];
const QUIC_HELLO_MAGIC: [u8; 8] = *b"BPTQHELO";
const QUIC_DONE_MAGIC: [u8; 8] = *b"BPTQDONE";
const QUIC_SUMMARY_MAGIC: [u8; 8] = *b"BPTQSUM!";
const QUIC_REPORT_MAGIC: [u8; 8] = *b"BPTQRPT!";
const TEST_SERVER_NAME: &str = "bridgepad.test";
const PLAY_SERVER_NAME: &str = "bridgepad.local";
const PLAY_HELLO_MAGIC: [u8; 8] = *b"BPMQPLAY";
const PLAY_UDP_HELLO_MAGIC: [u8; 8] = *b"BPMUPLAY";

static ACTIVE_RUN_ID: AtomicU64 = AtomicU64::new(0);
static CANCELLED_RUN_ID: AtomicU64 = AtomicU64::new(0);

type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_MediaTransportNative_runQuicProbe(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
    host: JString,
    interface_name: JString,
    port: jint,
    duration_seconds: jint,
) -> jstring {
    run_jni_probe(
        &mut env,
        operation_id,
        host,
        interface_name,
        port,
        duration_seconds,
        NativeTransport::Quic,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_MediaTransportNative_runUdpAeadProbe(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
    host: JString,
    interface_name: JString,
    port: jint,
    duration_seconds: jint,
) -> jstring {
    run_jni_probe(
        &mut env,
        operation_id,
        host,
        interface_name,
        port,
        duration_seconds,
        NativeTransport::UdpAead,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_MediaTransportNative_cancelProbe(
    _env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    cancel_operation(operation_id);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_MediaTransportNative_cancelPlayable(
    _env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    cancel_operation(operation_id);
}

fn cancel_operation(operation_id: jlong) {
    if let Ok(operation_id) = u64::try_from(operation_id)
        && operation_id != 0
    {
        let active = ACTIVE_RUN_ID.load(Ordering::Acquire);
        if active == 0 || active == operation_id {
            CANCELLED_RUN_ID.store(operation_id, Ordering::Release);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_MediaTransportNative_runQuicPlayable(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
    host: JString,
    local_address: JByteArray,
    local_scope_id: jint,
    port: jint,
    certificate_sha256: JString,
    token: JByteArray,
    session_id: jlong,
    callback: JObject,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), AnyError> {
        let operation_id = u64::try_from(operation_id).map_err(|_| "invalid operation ID")?;
        if operation_id == 0 || !(1..=u16::MAX.into()).contains(&port) || session_id <= 0 {
            return Err("invalid playable QUIC session parameters".into());
        }
        let host: String = env.get_string(&host)?.into();
        let local_address = env.convert_byte_array(local_address)?;
        let local_bind = playable_local_bind(&local_address, local_scope_id)?;
        let fingerprint: String = env.get_string(&certificate_sha256)?.into();
        let fingerprint = parse_fingerprint(&fingerprint)?;
        let token = env.convert_byte_array(token)?;
        let token: [u8; 32] = token.try_into().map_err(|_| "invalid media token length")?;
        let _run = ActiveRun::acquire(operation_id)?;
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?;
        runtime.block_on(run_quic_playable(
            &mut env,
            callback,
            &host,
            local_bind,
            port as u16,
            fingerprint,
            token,
            session_id as u64,
            operation_id,
        ))
    }));
    let message = match result {
        Ok(Ok(())) => "OK".to_owned(),
        Ok(Err(error)) => encode_error(&error.to_string()),
        Err(_) => encode_error("native playable QUIC receiver panicked"),
    };
    env.new_string(message)
        .map_or(ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_MediaTransportNative_runUdpPlayable(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
    host: JString,
    local_address: JByteArray,
    local_scope_id: jint,
    port: jint,
    token: JByteArray,
    key: JByteArray,
    session_id: jlong,
    callback: JObject,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), AnyError> {
        let operation_id = u64::try_from(operation_id).map_err(|_| "invalid operation ID")?;
        if operation_id == 0 || !(1..=u16::MAX.into()).contains(&port) || session_id <= 0 {
            return Err("invalid playable UDP session parameters".into());
        }
        let host: String = env.get_string(&host)?.into();
        let local_address = env.convert_byte_array(local_address)?;
        let local_bind = playable_local_bind(&local_address, local_scope_id)?;
        let token: [u8; 32] = env
            .convert_byte_array(token)?
            .try_into()
            .map_err(|_| "invalid media token length")?;
        let key: [u8; 32] = env
            .convert_byte_array(key)?
            .try_into()
            .map_err(|_| "invalid media key length")?;
        let _run = ActiveRun::acquire(operation_id)?;
        run_udp_playable(
            &mut env,
            callback,
            &host,
            local_bind,
            port as u16,
            token,
            key,
            session_id as u64,
            operation_id,
        )
    }));
    let message = match result {
        Ok(Ok(())) => "OK".to_owned(),
        Ok(Err(error)) => encode_error(&error.to_string()),
        Err(_) => encode_error("native playable UDP receiver panicked"),
    };
    env.new_string(message)
        .map_or(ptr::null_mut(), JString::into_raw)
}

fn parse_fingerprint(value: &str) -> Result<[u8; 32], AnyError> {
    let hex: String = value
        .chars()
        .filter(|character| *character != ':' && *character != '-' && !character.is_whitespace())
        .collect();
    if hex.len() != 64 {
        return Err("invalid Desktop certificate fingerprint".into());
    }
    let mut bytes = [0_u8; 32];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&hex[index * 2..index * 2 + 2], 16)?;
    }
    Ok(bytes)
}

fn playable_local_bind(address: &[u8], scope_id: jint) -> Result<SocketAddr, AnyError> {
    let bind = match address.len() {
        4 => SocketAddr::V4(SocketAddrV4::new(
            Ipv4Addr::from(<[u8; 4]>::try_from(address)?),
            0,
        )),
        16 if scope_id >= 0 => SocketAddr::V6(SocketAddrV6::new(
            Ipv6Addr::from(<[u8; 16]>::try_from(address)?),
            0,
            0,
            scope_id as u32,
        )),
        _ => return Err("invalid selected media route address".into()),
    };
    Ok(bind)
}

async fn run_quic_playable(
    env: &mut JNIEnv<'_>,
    callback: JObject<'_>,
    host: &str,
    local_bind: SocketAddr,
    port: u16,
    fingerprint: [u8; 32],
    token: [u8; 32],
    session_id: u64,
    operation_id: u64,
) -> Result<(), AnyError> {
    if is_cancelled(operation_id) {
        return Ok(());
    }
    let remote = resolve_remote_async(host, port).await?;
    if local_bind.is_ipv4() != remote.is_ipv4() {
        return Err("selected media route uses the wrong IP address family".into());
    }
    let mut endpoint = Endpoint::client(local_bind)?;
    endpoint.set_default_client_config(pinned_client_config(fingerprint)?);
    let mut connecting = Box::pin(endpoint.connect(remote, PLAY_SERVER_NAME)?);
    let connect_deadline = Instant::now() + Duration::from_secs(7);
    let connection = loop {
        if is_cancelled(operation_id) {
            return Ok(());
        }
        if Instant::now() >= connect_deadline {
            return Err("QUIC media handshake timed out after 7 seconds".into());
        }
        match timeout(Duration::from_millis(100), &mut connecting).await {
            Ok(result) => break result?,
            Err(_) => continue,
        }
    };
    let mut hello = Vec::with_capacity(48);
    hello.extend_from_slice(&PLAY_HELLO_MAGIC);
    hello.extend_from_slice(&session_id.to_be_bytes());
    hello.extend_from_slice(&token);
    let mut stream = timeout(Duration::from_secs(2), connection.open_uni()).await??;
    stream.write_all(&hello).await?;
    stream.finish()?;

    let mut video = FrameAssembler::new(session_id, 1, 100_000, 16);
    let mut audio = FrameAssembler::new(session_id, 2, 100_000, 16);
    let started = Instant::now();
    while !is_cancelled(operation_id) {
        let bytes = match timeout(Duration::from_millis(100), connection.read_datagram()).await {
            Ok(Ok(bytes)) => bytes,
            Ok(Err(error)) => return Err(error.into()),
            Err(_) => continue,
        };
        let received_at = duration_micros(&started.elapsed());
        let datagram = match bridgepad_media_protocol::decode_datagram(&bytes) {
            Ok(datagram) if datagram.header.session_id == session_id => datagram,
            _ => continue,
        };
        let assembler = match datagram.header.kind {
            bridgepad_media_protocol::PacketKind::Video => &mut video,
            bridgepad_media_protocol::PacketKind::Audio => &mut audio,
            bridgepad_media_protocol::PacketKind::Control => continue,
        };
        let Ok(Some(frame)) = assembler.push(&bytes, received_at) else {
            continue;
        };
        dispatch_playable_frame(env, &callback, frame)?;
    }
    connection.close(0_u32.into(), b"session stopped");
    endpoint.close(0_u32.into(), b"session stopped");
    Ok(())
}

fn dispatch_playable_frame(
    env: &mut JNIEnv<'_>,
    callback: &JObject<'_>,
    frame: AssembledFrame,
) -> Result<(), AnyError> {
    let payload = JObject::from(env.byte_array_from_slice(&frame.payload)?);
    let timestamp = JValue::Long(i64::try_from(frame.presentation_timestamp_micros)?);
    match frame.kind {
        PacketKind::Video => {
            let keyframe = JValue::Bool(if frame.flags.contains(PacketFlags::KEYFRAME) {
                1
            } else {
                0
            });
            env.call_method(
                callback,
                "onVideoFrame",
                "([BJZ)V",
                &[JValue::Object(&payload), timestamp, keyframe],
            )?;
        }
        PacketKind::Audio => {
            env.call_method(
                callback,
                "onAudioFrame",
                "([BJ)V",
                &[JValue::Object(&payload), timestamp],
            )?;
        }
        PacketKind::Control => {}
    }
    env.delete_local_ref(payload)?;
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn run_udp_playable(
    env: &mut JNIEnv<'_>,
    callback: JObject<'_>,
    host: &str,
    local_bind: SocketAddr,
    port: u16,
    token: [u8; 32],
    key: [u8; 32],
    session_id: u64,
    operation_id: u64,
) -> Result<(), AnyError> {
    if is_cancelled(operation_id) {
        return Ok(());
    }
    let remote = resolve_remote(host, port)?;
    if local_bind.is_ipv4() != remote.is_ipv4() {
        return Err("selected media route uses the wrong IP address family".into());
    }
    let socket = Socket::new(
        Domain::for_address(remote),
        Type::DGRAM,
        Some(Protocol::UDP),
    )?;
    socket.set_recv_buffer_size(SOCKET_RECEIVE_BUFFER_SIZE)?;
    socket.bind(&local_bind.into())?;
    socket.connect(&remote.into())?;
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(100)))?;
    let mut hello = [0_u8; 48];
    hello[..8].copy_from_slice(&PLAY_UDP_HELLO_MAGIC);
    hello[8..16].copy_from_slice(&session_id.to_be_bytes());
    hello[16..].copy_from_slice(&token);
    let mut receiver = UdpAeadReceiver::new(&key, 1)?;
    let mut control = UdpAeadSender::new(&key, 2)?;
    // A fragmented UDP IDR can take longer to transmit than an ordinary P frame.
    // Leave room for the sender's 150 ms keyframe budget plus network jitter.
    let mut video = FrameAssembler::new(session_id, 1, 250_000, 16);
    let mut audio = FrameAssembler::new(session_id, 2, 100_000, 16);
    let mut video_recovery = UdpVideoRecovery::new();
    let mut video_losses = 0_u64;
    let mut video_delivered = 0_u64;
    let mut video_dropped = 0_u64;
    let mut keyframe_requests = 0_u64;
    let started = Instant::now();
    let mut last_media = Instant::now();
    let mut last_hello = Instant::now() - Duration::from_secs(1);
    let mut last_ping = Instant::now() - Duration::from_secs(1);
    let mut last_keyframe_request = Instant::now() - Duration::from_secs(1);
    let mut last_stats = Instant::now();
    let mut last_video_expiration = Instant::now();
    let mut authenticated = false;
    let mut control_sequence = 0_u32;
    let mut incoming = [0_u8; MAX_DATAGRAM_SIZE];
    while !is_cancelled(operation_id) {
        if !authenticated && last_hello.elapsed() >= Duration::from_millis(250) {
            send_udp_control(
                &socket,
                &mut control,
                session_id,
                &mut control_sequence,
                &hello,
            )?;
            last_hello = Instant::now();
        }
        if last_ping.elapsed() >= Duration::from_secs(1) {
            send_udp_control(
                &socket,
                &mut control,
                session_id,
                &mut control_sequence,
                b"PING",
            )?;
            last_ping = Instant::now();
        }
        if last_video_expiration.elapsed() >= Duration::from_millis(20) {
            video.expire(duration_micros(&started.elapsed()));
            let losses = video
                .metrics()
                .expired_frames
                .saturating_add(video.metrics().capacity_dropped_frames);
            if losses > video_losses && video_recovery.frame_lost() {
                notify_video_discontinuity(env, &callback)?;
            }
            video_losses = losses;
            last_video_expiration = Instant::now();
        }
        if authenticated && last_stats.elapsed() >= Duration::from_secs(2) {
            let metrics = video.metrics();
            let report = format!(
                "STATS delivered={video_delivered} dropped={video_dropped} complete={} expired={} duplicate={} keyframe_requests={keyframe_requests} waiting={}",
                metrics.completed_frames,
                metrics
                    .expired_frames
                    .saturating_add(metrics.capacity_dropped_frames),
                metrics.duplicate_packets,
                video_recovery.awaiting_keyframe(),
            );
            send_udp_control(
                &socket,
                &mut control,
                session_id,
                &mut control_sequence,
                report.as_bytes(),
            )?;
            last_stats = Instant::now();
        }
        // Keep recovery active even when the socket receives a continuous
        // stream of incomplete video fragments or audio-only packets.
        if authenticated
            && video_recovery.awaiting_keyframe()
            && request_udp_keyframe(
                &socket,
                &mut control,
                session_id,
                &mut control_sequence,
                &mut last_keyframe_request,
            )?
        {
            keyframe_requests += 1;
        }
        if (!authenticated && started.elapsed() >= Duration::from_secs(15))
            || (authenticated && last_media.elapsed() >= Duration::from_secs(30))
        {
            return Err("UDP media timed out waiting for encrypted video or audio".into());
        }
        let size = match socket.recv(&mut incoming) {
            Ok(size) => size,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                if authenticated
                    && needs_video_keyframe(env, &callback)?
                    && video_recovery.frame_lost()
                {
                    notify_video_discontinuity(env, &callback)?;
                }
                continue;
            }
            Err(error) => return Err(error.into()),
        };
        let Ok(plaintext) = receiver.open(&incoming[..size]) else {
            continue;
        };
        let Ok(datagram) = bridgepad_media_protocol::decode_datagram(&plaintext) else {
            continue;
        };
        if datagram.header.session_id != session_id {
            continue;
        }
        authenticated = true;
        last_media = Instant::now();
        let received_at = duration_micros(&started.elapsed());
        match datagram.header.kind {
            PacketKind::Video => {
                if video_recovery.is_stale(datagram.header.frame_id) {
                    continue;
                }
                let assembled = video.push(&plaintext, received_at);
                let losses = video
                    .metrics()
                    .expired_frames
                    .saturating_add(video.metrics().capacity_dropped_frames);
                if losses > video_losses && video_recovery.frame_lost() {
                    notify_video_discontinuity(env, &callback)?;
                }
                video_losses = losses;
                if let Ok(Some(frame)) = assembled {
                    let keyframe = frame.flags.contains(PacketFlags::KEYFRAME);
                    match video_recovery.consider(frame.frame_id, keyframe) {
                        VideoDecision::Deliver => {
                            video.discard_older_than(frame.frame_id);
                            dispatch_playable_frame(env, &callback, frame)?;
                            video_delivered += 1;
                        }
                        VideoDecision::RecoverAndDeliver => {
                            video.discard_older_than(frame.frame_id);
                            notify_video_discontinuity(env, &callback)?;
                            dispatch_playable_frame(env, &callback, frame)?;
                            video_delivered += 1;
                        }
                        VideoDecision::RecoverAndDrop => {
                            notify_video_discontinuity(env, &callback)?;
                            video_dropped += 1;
                        }
                        VideoDecision::Drop => video_dropped += 1,
                    }
                    if needs_video_keyframe(env, &callback)? {
                        if video_recovery.frame_lost() {
                            notify_video_discontinuity(env, &callback)?;
                        }
                    }
                }
            }
            PacketKind::Audio => {
                if let Ok(Some(frame)) = audio.push(&plaintext, received_at) {
                    dispatch_playable_frame(env, &callback, frame)?;
                }
            }
            PacketKind::Control => {}
        }
    }
    if authenticated {
        let _ = send_udp_control(
            &socket,
            &mut control,
            session_id,
            &mut control_sequence,
            b"STOP",
        );
    }
    Ok(())
}

fn notify_video_discontinuity(
    env: &mut JNIEnv<'_>,
    callback: &JObject<'_>,
) -> Result<(), AnyError> {
    env.call_method(callback, "onVideoDiscontinuity", "()V", &[])?;
    Ok(())
}

fn needs_video_keyframe(env: &mut JNIEnv<'_>, callback: &JObject<'_>) -> Result<bool, AnyError> {
    Ok(env
        .call_method(callback, "needsVideoKeyframe", "()Z", &[])?
        .z()?)
}

fn request_udp_keyframe(
    socket: &UdpSocket,
    sender: &mut UdpAeadSender,
    session_id: u64,
    sequence: &mut u32,
    last_request: &mut Instant,
) -> Result<bool, AnyError> {
    if last_request.elapsed() >= Duration::from_millis(500) {
        send_udp_control(socket, sender, session_id, sequence, b"KEYFRAME")?;
        *last_request = Instant::now();
        return Ok(true);
    }
    Ok(false)
}

fn send_udp_control(
    socket: &UdpSocket,
    sender: &mut UdpAeadSender,
    session_id: u64,
    sequence: &mut u32,
    payload: &[u8],
) -> Result<(), AnyError> {
    let packets = packetize_with_max_datagram_size(
        PacketizeRequest {
            kind: PacketKind::Control,
            flags: PacketFlags::NONE,
            session_id,
            stream_id: 3,
            first_sequence: *sequence,
            frame_id: *sequence,
            presentation_timestamp_micros: 0,
            payload,
        },
        1_100,
    )?;
    for packet in packets {
        socket.send(&sender.seal(&packet)?)?;
        *sequence = sequence.wrapping_add(1);
    }
    Ok(())
}

fn pinned_client_config(fingerprint: [u8; 32]) -> Result<ClientConfig, AnyError> {
    let crypto = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedCertificateVerifier {
            provider: Arc::new(rustls::crypto::ring::default_provider()),
            fingerprint,
        }))
        .with_no_client_auth();
    let mut config = ClientConfig::new(Arc::new(QuicClientConfig::try_from(crypto)?));
    let mut transport = TransportConfig::default();
    transport.datagram_receive_buffer_size(Some(SOCKET_RECEIVE_BUFFER_SIZE));
    transport.datagram_send_buffer_size(SOCKET_RECEIVE_BUFFER_SIZE);
    transport.max_idle_timeout(Some(quinn::IdleTimeout::try_from(Duration::from_secs(10))?));
    config.transport_config(Arc::new(transport));
    Ok(config)
}

#[derive(Debug)]
struct PinnedCertificateVerifier {
    provider: Arc<rustls::crypto::CryptoProvider>,
    fingerprint: [u8; 32],
}

impl rustls::client::danger::ServerCertVerifier for PinnedCertificateVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        let actual: [u8; 32] = Sha256::digest(end_entity.as_ref()).into();
        if !bool::from(actual.ct_eq(&self.fingerprint)) {
            return Err(rustls::Error::General(
                "Desktop certificate fingerprint does not match pairing".into(),
            ));
        }
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signed: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            certificate,
            signed,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signed: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            certificate,
            signed,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

fn run_jni_probe(
    env: &mut JNIEnv,
    operation_id: jlong,
    host: JString,
    interface_name: JString,
    port: jint,
    duration_seconds: jint,
    transport: NativeTransport,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let operation_id = validate_arguments(
            operation_id,
            port,
            duration_seconds,
            transport.display_name(),
        )?;
        let host: String = env.get_string(&host)?.into();
        let interface_name: String = env.get_string(&interface_name)?.into();
        let _run = ActiveRun::acquire(operation_id)?;
        transport.run(
            &host,
            port as u16,
            duration_seconds as u16,
            operation_id,
            &interface_name,
        )
    }));
    let encoded = match result {
        Ok(Ok(measurement)) => measurement.encode(),
        Ok(Err(error)) => encode_error(&error.to_string()),
        Err(_) => encode_error(&format!(
            "native {} diagnostic panicked",
            transport.display_name(),
        )),
    };
    env.new_string(encoded)
        .map_or(ptr::null_mut(), JString::into_raw)
}

#[derive(Clone, Copy)]
enum NativeTransport {
    Quic,
    UdpAead,
}

impl NativeTransport {
    fn display_name(self) -> &'static str {
        match self {
            Self::Quic => "QUIC",
            Self::UdpAead => "UDP+AEAD",
        }
    }

    fn run(
        self,
        host: &str,
        port: u16,
        duration_seconds: u16,
        operation_id: u64,
        interface_name: &str,
    ) -> Result<Measurement, AnyError> {
        match self {
            Self::Quic => {
                run_quic_probe(host, port, duration_seconds, operation_id, interface_name)
            }
            Self::UdpAead => {
                run_udp_aead_probe(host, port, duration_seconds, operation_id, interface_name)
            }
        }
    }
}

fn validate_arguments(
    operation_id: jlong,
    port: jint,
    duration_seconds: jint,
    transport_name: &str,
) -> Result<u64, AnyError> {
    let operation_id = u64::try_from(operation_id).map_err(|_| "invalid operation ID")?;
    if operation_id == 0 {
        return Err("invalid operation ID".into());
    }
    if !(1..=u16::MAX.into()).contains(&port) {
        return Err(format!("invalid {transport_name} port").into());
    }
    if !(1..=300).contains(&duration_seconds) {
        return Err(format!("invalid {transport_name} diagnostic duration").into());
    }
    Ok(operation_id)
}

struct ActiveRun(u64);

impl ActiveRun {
    fn acquire(operation_id: u64) -> Result<Self, AnyError> {
        let deadline = Instant::now() + Duration::from_millis(500);
        loop {
            match ACTIVE_RUN_ID.compare_exchange(
                0,
                operation_id,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => {
                    if CANCELLED_RUN_ID.load(Ordering::Acquire) != operation_id {
                        CANCELLED_RUN_ID.store(0, Ordering::Release);
                    }
                    return Ok(Self(operation_id));
                }
                Err(active_id)
                    if CANCELLED_RUN_ID.load(Ordering::Acquire) == active_id
                        && Instant::now() < deadline =>
                {
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(_) => return Err("another native media operation is already running".into()),
            }
        }
    }
}

impl Drop for ActiveRun {
    fn drop(&mut self) {
        let _ = CANCELLED_RUN_ID.compare_exchange(self.0, 0, Ordering::AcqRel, Ordering::Acquire);
        let _ = ACTIVE_RUN_ID.compare_exchange(self.0, 0, Ordering::AcqRel, Ordering::Acquire);
    }
}

fn is_cancelled(operation_id: u64) -> bool {
    CANCELLED_RUN_ID.load(Ordering::Acquire) == operation_id
}

fn run_quic_probe(
    host: &str,
    port: u16,
    duration_seconds: u16,
    operation_id: u64,
    interface_name: &str,
) -> Result<Measurement, AnyError> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_io()
        .enable_time()
        .build()?;
    runtime.block_on(run_quic_probe_async(
        host,
        port,
        duration_seconds,
        operation_id,
        interface_name,
    ))
}

/// Runs the QUIC diagnostic outside JNI for host-side smoke tests.
pub fn run_quic_probe_diagnostic(
    host: &str,
    port: u16,
    duration_seconds: u16,
) -> Result<String, String> {
    let operation_id = 1;
    let _run = ActiveRun::acquire(operation_id).map_err(|error| error.to_string())?;
    run_quic_probe(host, port, duration_seconds, operation_id, "host")
        .map(|measurement| measurement.encode())
        .map_err(|error| error.to_string())
}

/// Runs the UDP+AEAD diagnostic outside JNI for host-side smoke tests.
pub fn run_udp_aead_probe_diagnostic(
    host: &str,
    port: u16,
    duration_seconds: u16,
) -> Result<String, String> {
    let operation_id = 1;
    let _run = ActiveRun::acquire(operation_id).map_err(|error| error.to_string())?;
    run_udp_aead_probe(host, port, duration_seconds, operation_id, "host")
        .map(|measurement| measurement.encode())
        .map_err(|error| error.to_string())
}

async fn run_quic_probe_async(
    host: &str,
    port: u16,
    duration_seconds: u16,
    operation_id: u64,
    interface_name: &str,
) -> Result<Measurement, AnyError> {
    let remote = resolve_remote_async(host, port).await?;
    let local_ip = route_local_ip(remote)?;
    let mut endpoint = Endpoint::client(unspecified_address(remote))?;
    endpoint.set_default_client_config(client_config()?);

    let handshake_started = Instant::now();
    let connecting = endpoint.connect(remote, TEST_SERVER_NAME)?;
    let connection = match timeout(Duration::from_secs(7), connecting).await {
        Ok(result) => result?,
        Err(_) => {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "QUIC handshake timed out after 7 seconds",
            )
            .into());
        }
    };
    let handshake_millis = duration_millis(handshake_started.elapsed());
    let mut hello = Vec::with_capacity(10);
    hello.extend_from_slice(&QUIC_HELLO_MAGIC);
    hello.extend_from_slice(&duration_seconds.to_be_bytes());
    let mut hello_stream = match timeout(Duration::from_secs(2), connection.open_uni()).await {
        Ok(result) => result?,
        Err(_) => {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "QUIC control stream opening timed out after 2 seconds",
            )
            .into());
        }
    };
    hello_stream.write_all(&hello).await?;
    hello_stream.finish()?;

    let expected_frames = u64::from(duration_seconds) * FRAMES_PER_SECOND;
    let started = Instant::now();
    let mut measurements = ReceiveMeasurements::new(expected_frames * PACKETS_PER_FRAME);
    let mut assembler = frame_assembler();
    let overall_timeout = Duration::from_secs(u64::from(duration_seconds) + 5);
    let mut expected_packets = None;
    let mut summary_received_at = None;

    while started.elapsed() < overall_timeout {
        if is_cancelled(operation_id) {
            connection.close(1_u32.into(), b"cancelled");
            endpoint.wait_idle().await;
            return Err("cancelled".into());
        }
        if expected_packets.is_some_and(|expected| measurements.received_packets >= expected)
            || summary_received_at
                .is_some_and(|received: Instant| received.elapsed() >= Duration::from_millis(500))
        {
            break;
        }
        tokio::select! {
            incoming = connection.accept_uni(), if expected_packets.is_none() => {
                let mut stream = incoming?;
                let bytes = timeout(Duration::from_secs(2), stream.read_to_end(16)).await??;
                expected_packets = Some(parse_quic_summary(&bytes)?);
                summary_received_at = Some(Instant::now());
            }
            incoming = connection.read_datagram() => {
                let datagram = incoming?;
                if datagram.as_ref() == QUIC_DONE_MAGIC {
                    continue;
                }
                let arrival = Instant::now();
                measurements.record_arrival(arrival, datagram.len());
                let received_at = duration_micros(&arrival.duration_since(started));
                match assembler.push(&datagram, received_at) {
                    Ok(_) => measurements.received_packets += 1,
                    Err(_) => measurements.rejected_packets += 1,
                }
            }
            () = tokio::time::sleep(Duration::from_millis(100)) => {}
        }
    }

    let expected_packets = expected_packets.ok_or("QUIC sender did not return a packet summary")?;
    let elapsed = started.elapsed();
    let measurement = finish_measurement(
        local_ip,
        expected_frames,
        expected_packets,
        elapsed,
        Some(handshake_millis),
        measurements,
        assembler,
    );
    send_quic_report(&connection, &measurement, interface_name).await?;
    connection.close(0_u32.into(), b"diagnostic complete");
    endpoint.wait_idle().await;
    Ok(measurement)
}

fn run_udp_aead_probe(
    host: &str,
    port: u16,
    duration_seconds: u16,
    operation_id: u64,
    interface_name: &str,
) -> Result<Measurement, AnyError> {
    let remote = resolve_remote(host, port)?;
    let socket = connected_udp_socket(remote)?;
    let local_ip = socket.local_addr()?.ip();
    let mut hello = Vec::with_capacity(10);
    hello.extend_from_slice(&UDP_HELLO_MAGIC);
    hello.extend_from_slice(&duration_seconds.to_be_bytes());
    socket.send(&hello)?;

    let expected_frames = u64::from(duration_seconds) * FRAMES_PER_SECOND;
    let expected_packets = expected_frames * PACKETS_PER_FRAME;
    let started = Instant::now();
    let overall_deadline = started + Duration::from_secs(u64::from(duration_seconds) + 5);
    let mut measurements = ReceiveMeasurements::new(expected_packets);
    let mut assembler = frame_assembler();
    let mut receiver = UdpAeadReceiver::new(&UDP_TEST_KEY, UDP_KEY_EPOCH)?;
    let receive_socket = socket.try_clone()?;
    let (sender, events) = sync_channel(4_096);
    let receive_stop = Arc::new(AtomicBool::new(false));
    let worker_stop = Arc::clone(&receive_stop);
    let receive_worker = thread::Builder::new()
        .name("BridgePad-udp-receive".to_owned())
        .spawn(move || {
            receive_udp_datagrams(
                receive_socket,
                sender,
                operation_id,
                overall_deadline,
                &worker_stop,
            );
        })?;

    while measurements.received_packets < expected_packets && Instant::now() < overall_deadline {
        if is_cancelled(operation_id) {
            break;
        }
        let event = match events.recv_timeout(Duration::from_millis(100)) {
            Ok(event) => event,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue,
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
        };
        let ReceivedUdpDatagram { bytes, arrival } = event;
        measurements.record_arrival(arrival, bytes.len());
        match receiver.open(&bytes) {
            Ok(plaintext) => {
                let received_at = duration_micros(&arrival.duration_since(started));
                match assembler.push(&plaintext, received_at) {
                    Ok(_) => measurements.received_packets += 1,
                    Err(_) => measurements.rejected_packets += 1,
                }
            }
            Err(_) => measurements.rejected_packets += 1,
        }
    }

    let elapsed = started.elapsed();
    receive_stop.store(true, Ordering::Release);
    drop(events);
    receive_worker
        .join()
        .map_err(|_| "native UDP receive worker panicked")?;
    if is_cancelled(operation_id) {
        return Err("cancelled".into());
    }

    let measurement = finish_measurement(
        local_ip,
        expected_frames,
        expected_packets,
        elapsed,
        None,
        measurements,
        assembler,
    );
    send_udp_report(&socket, &measurement, interface_name)?;
    Ok(measurement)
}

fn parse_quic_summary(bytes: &[u8]) -> Result<u64, AnyError> {
    if bytes.len() != 16 || bytes[..8] != QUIC_SUMMARY_MAGIC {
        return Err("invalid QUIC diagnostic packet summary".into());
    }
    Ok(u64::from_be_bytes(bytes[8..16].try_into()?))
}

struct ReceivedUdpDatagram {
    bytes: Vec<u8>,
    arrival: Instant,
}

fn receive_udp_datagrams(
    socket: UdpSocket,
    sender: SyncSender<ReceivedUdpDatagram>,
    operation_id: u64,
    overall_deadline: Instant,
    stop: &AtomicBool,
) {
    let mut buffer = [0_u8; MAX_DATAGRAM_SIZE];
    let mut drain_deadline = None;
    while !stop.load(Ordering::Acquire)
        && !is_cancelled(operation_id)
        && Instant::now() < overall_deadline
    {
        if drain_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            break;
        }
        let length = match socket.recv(&mut buffer) {
            Ok(length) => length,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                continue;
            }
            Err(_) => break,
        };
        if buffer[..length] == UDP_DONE_MAGIC {
            drain_deadline = Some(Instant::now() + Duration::from_millis(250));
            continue;
        }
        let arrival = Instant::now();
        let event = ReceivedUdpDatagram {
            bytes: buffer[..length].to_vec(),
            arrival,
        };
        if !send_udp_event(&sender, event, operation_id, stop) {
            break;
        }
    }
}

fn send_udp_event(
    sender: &SyncSender<ReceivedUdpDatagram>,
    mut event: ReceivedUdpDatagram,
    operation_id: u64,
    stop: &AtomicBool,
) -> bool {
    loop {
        match sender.try_send(event) {
            Ok(()) => return true,
            Err(TrySendError::Full(returned))
                if !stop.load(Ordering::Acquire) && !is_cancelled(operation_id) =>
            {
                event = returned;
                thread::yield_now();
            }
            Err(TrySendError::Full(_) | TrySendError::Disconnected(_)) => return false,
        }
    }
}

async fn send_quic_report(
    connection: &quinn::Connection,
    measurement: &Measurement,
    interface_name: &str,
) -> Result<(), AnyError> {
    let report = measurement.report(interface_name, &QUIC_REPORT_MAGIC);
    let mut stream = timeout(Duration::from_secs(2), connection.open_uni()).await??;
    stream.write_all(&report).await?;
    stream.finish()?;
    let _ = timeout(Duration::from_secs(2), stream.stopped()).await;
    Ok(())
}

fn send_udp_report(
    socket: &UdpSocket,
    measurement: &Measurement,
    interface_name: &str,
) -> Result<(), AnyError> {
    let report = measurement.report(interface_name, &UDP_REPORT_MAGIC);
    for _ in 0..3 {
        socket.send(&report)?;
    }
    Ok(())
}

fn connected_udp_socket(remote: SocketAddr) -> Result<UdpSocket, AnyError> {
    let socket = Socket::new(
        Domain::for_address(remote),
        Type::DGRAM,
        Some(Protocol::UDP),
    )?;
    socket.set_recv_buffer_size(SOCKET_RECEIVE_BUFFER_SIZE)?;
    socket.bind(&unspecified_address(remote).into())?;
    socket.connect(&remote.into())?;
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(100)))?;
    Ok(socket)
}

fn frame_assembler() -> FrameAssembler {
    FrameAssembler::new(
        SESSION_ID,
        STREAM_ID,
        ASSEMBLY_DEADLINE_MICROS,
        MAX_IN_FLIGHT_FRAMES,
    )
}

struct ReceiveMeasurements {
    previous_arrival: Option<Instant>,
    arrival_gaps: Vec<Duration>,
    received_packets: u64,
    received_bytes: u64,
    rejected_packets: u64,
}

impl ReceiveMeasurements {
    fn new(expected_packets: u64) -> Self {
        Self {
            previous_arrival: None,
            arrival_gaps: Vec::with_capacity(usize::try_from(expected_packets).unwrap_or(0)),
            received_packets: 0,
            received_bytes: 0,
            rejected_packets: 0,
        }
    }

    fn record_arrival(&mut self, arrival: Instant, bytes: usize) {
        if let Some(previous) = self.previous_arrival.replace(arrival) {
            self.arrival_gaps.push(arrival.duration_since(previous));
        }
        self.received_bytes = self.received_bytes.saturating_add(bytes as u64);
    }
}

fn finish_measurement(
    local_ip: IpAddr,
    expected_frames: u64,
    expected_packets: u64,
    elapsed: Duration,
    handshake_millis: Option<u64>,
    mut measurements: ReceiveMeasurements,
    mut assembler: FrameAssembler,
) -> Measurement {
    let elapsed_micros = duration_micros(&elapsed);
    assembler.expire(elapsed_micros.saturating_add(ASSEMBLY_DEADLINE_MICROS));
    measurements.arrival_gaps.sort_unstable();
    let metrics = assembler.metrics();
    Measurement {
        local_ip,
        received_packets: measurements.received_packets,
        expected_packets,
        completed_frames: metrics.completed_frames,
        expected_frames,
        expired_frames: metrics.expired_frames,
        duplicate_packets: metrics.duplicate_packets,
        rejected_packets: measurements.rejected_packets,
        received_bytes: measurements.received_bytes,
        elapsed_millis: duration_millis(elapsed),
        handshake_millis,
        arrival_gap_p95_micros: percentile_micros(&measurements.arrival_gaps, 95),
        arrival_gap_p99_micros: percentile_micros(&measurements.arrival_gaps, 99),
        arrival_gap_max_micros: measurements.arrival_gaps.last().map_or(0, duration_micros),
    }
}

fn client_config() -> Result<ClientConfig, AnyError> {
    let crypto = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(DiagnosticCertificateVerifier::new())
        .with_no_client_auth();
    let mut config = ClientConfig::new(Arc::new(QuicClientConfig::try_from(crypto)?));
    let mut transport = TransportConfig::default();
    transport.datagram_receive_buffer_size(Some(SOCKET_RECEIVE_BUFFER_SIZE));
    transport.datagram_send_buffer_size(SOCKET_RECEIVE_BUFFER_SIZE);
    transport.max_idle_timeout(Some(quinn::IdleTimeout::try_from(Duration::from_secs(15))?));
    config.transport_config(Arc::new(transport));
    Ok(config)
}

/// Test-only verifier for the ephemeral identity created by `media_quic_server`.
#[derive(Debug)]
struct DiagnosticCertificateVerifier(Arc<rustls::crypto::CryptoProvider>);

impl DiagnosticCertificateVerifier {
    fn new() -> Arc<Self> {
        Arc::new(Self(Arc::new(rustls::crypto::ring::default_provider())))
    }
}

impl rustls::client::danger::ServerCertVerifier for DiagnosticCertificateVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signed: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            certificate,
            signed,
            &self.0.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signed: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            certificate,
            signed,
            &self.0.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

async fn resolve_remote_async(host: &str, port: u16) -> Result<SocketAddr, AnyError> {
    tokio::net::lookup_host((host, port))
        .await?
        .next()
        .ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "host has no address").into()
        })
}

fn resolve_remote(host: &str, port: u16) -> Result<SocketAddr, AnyError> {
    (host, port).to_socket_addrs()?.next().ok_or_else(|| {
        io::Error::new(io::ErrorKind::AddrNotAvailable, "host has no address").into()
    })
}

fn route_local_ip(remote: SocketAddr) -> Result<IpAddr, AnyError> {
    let socket = UdpSocket::bind(unspecified_address(remote))?;
    socket.connect(remote)?;
    Ok(socket.local_addr()?.ip())
}

fn unspecified_address(remote: SocketAddr) -> SocketAddr {
    match remote {
        SocketAddr::V4(_) => "0.0.0.0:0".parse().expect("static IPv4 address is valid"),
        SocketAddr::V6(_) => "[::]:0".parse().expect("static IPv6 address is valid"),
    }
}

struct Measurement {
    local_ip: IpAddr,
    received_packets: u64,
    expected_packets: u64,
    completed_frames: u64,
    expected_frames: u64,
    expired_frames: u64,
    duplicate_packets: u64,
    rejected_packets: u64,
    received_bytes: u64,
    elapsed_millis: u64,
    handshake_millis: Option<u64>,
    arrival_gap_p95_micros: u64,
    arrival_gap_p99_micros: u64,
    arrival_gap_max_micros: u64,
}

impl Measurement {
    fn encode(&self) -> String {
        let handshake = self
            .handshake_millis
            .map_or_else(|| "-1".to_owned(), |value| value.to_string());
        format!(
            "OK\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            self.local_ip,
            self.received_packets,
            self.expected_packets,
            self.completed_frames,
            self.expected_frames,
            self.expired_frames,
            self.duplicate_packets,
            self.rejected_packets,
            self.received_bytes,
            self.elapsed_millis,
            handshake,
            self.arrival_gap_p95_micros,
            self.arrival_gap_p99_micros,
            self.arrival_gap_max_micros,
        )
    }

    fn report(&self, interface_name: &str, magic: &[u8; 8]) -> Vec<u8> {
        let mut report = Vec::with_capacity(256);
        report.extend_from_slice(magic);
        report.extend_from_slice(self.encode().as_bytes());
        report.push(b'\t');
        report.extend_from_slice(sanitize_field(interface_name).as_bytes());
        report
    }
}

fn sanitize_field(value: &str) -> String {
    value.replace(['\t', '\r', '\n'], " ")
}

fn encode_error(error: &str) -> String {
    format!("ERR\t{}", error.replace(['\t', '\r', '\n'], " "))
}

fn percentile_micros(sorted: &[Duration], percentile: usize) -> u64 {
    if sorted.is_empty() {
        return 0;
    }
    let index = ((sorted.len() - 1) * percentile).div_ceil(100);
    duration_micros(&sorted[index])
}

fn duration_micros(duration: &Duration) -> u64 {
    u64::try_from(duration.as_micros()).unwrap_or(u64::MAX)
}

fn duration_millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_cancellation_does_not_cancel_the_next_run() {
        let first = ActiveRun::acquire(41).expect("first run");
        CANCELLED_RUN_ID.store(41, Ordering::Release);
        assert!(is_cancelled(41));
        drop(first);

        let second = ActiveRun::acquire(42).expect("second run");
        assert!(!is_cancelled(42));
        assert!(!is_cancelled(41));
        drop(second);
    }

    #[test]
    fn measurement_encodes_absent_handshake_as_negative_one() {
        let measurement = Measurement {
            local_ip: "127.0.0.1".parse().unwrap(),
            received_packets: 1,
            expected_packets: 1,
            completed_frames: 1,
            expected_frames: 1,
            expired_frames: 0,
            duplicate_packets: 0,
            rejected_packets: 0,
            received_bytes: 100,
            elapsed_millis: 10,
            handshake_millis: None,
            arrival_gap_p95_micros: 0,
            arrival_gap_p99_micros: 0,
            arrival_gap_max_micros: 0,
        };

        assert_eq!(measurement.encode().split('\t').nth(11), Some("-1"));
    }

    #[test]
    fn quic_summary_sets_actual_packet_count() {
        let mut summary = [0_u8; 16];
        summary[..8].copy_from_slice(&QUIC_SUMMARY_MAGIC);
        summary[8..].copy_from_slice(&5_137_u64.to_be_bytes());
        assert_eq!(parse_quic_summary(&summary).unwrap(), 5_137);
        assert!(parse_quic_summary(&summary[..15]).is_err());
        summary[0] ^= 1;
        assert!(parse_quic_summary(&summary).is_err());
    }

    #[test]
    fn playable_fingerprint_parser_accepts_the_paired_desktop_format() {
        let fingerprint = (0_u8..32)
            .map(|byte| format!("{byte:02X}"))
            .collect::<Vec<_>>()
            .join(":");
        assert_eq!(
            parse_fingerprint(&fingerprint).unwrap(),
            std::array::from_fn(|i| i as u8)
        );
        assert!(parse_fingerprint("not-a-fingerprint").is_err());
    }

    #[test]
    fn playable_quic_binds_the_selected_local_address() {
        let ipv4 = playable_local_bind(&[10, 2, 3, 4], 0).unwrap();
        assert_eq!(ipv4, "10.2.3.4:0".parse().unwrap());

        let ipv6 = playable_local_bind(&[0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 3)
            .unwrap();
        assert_eq!(ipv6, "[fe80::1%3]:0".parse().unwrap());
        assert!(playable_local_bind(&[1, 2, 3], 0).is_err());
    }
}
