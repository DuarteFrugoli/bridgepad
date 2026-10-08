use bridgepad_media::assembly::FrameAssembler;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use quinn::crypto::rustls::QuicClientConfig;
use quinn::{ClientConfig, Endpoint, TransportConfig};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use std::io;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tokio::time::timeout;

const FRAMES_PER_SECOND: u64 = 60;
const PACKETS_PER_FRAME: u64 = 16;
const SESSION_ID: u64 = 0x4250_4d45_4449_4131;
const STREAM_ID: u32 = 1;
const ASSEMBLY_DEADLINE_MICROS: u64 = 10_000;
const MAX_IN_FLIGHT_FRAMES: usize = 4;
const HELLO_MAGIC: [u8; 8] = *b"BPTQHELO";
const DONE_MAGIC: [u8; 8] = *b"BPTQDONE";
const TEST_SERVER_NAME: &str = "bridgepad.test";

static CANCELLED: AtomicBool = AtomicBool::new(false);
static RUNNING: AtomicBool = AtomicBool::new(false);

type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_QuicMediaNative_runProbe(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: jint,
    duration_seconds: jint,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let host: String = env.get_string(&host)?.into();
        if !(1..=u16::MAX.into()).contains(&port) {
            return Err("invalid QUIC port".into());
        }
        if !(1..=300).contains(&duration_seconds) {
            return Err("invalid QUIC diagnostic duration".into());
        }
        if RUNNING.swap(true, Ordering::AcqRel) {
            return Err("another QUIC diagnostic is already running".into());
        }
        CANCELLED.store(false, Ordering::Release);
        let result = run_probe(&host, port as u16, duration_seconds as u16);
        RUNNING.store(false, Ordering::Release);
        result
    }));
    let encoded = match result {
        Ok(Ok(measurement)) => measurement.encode(),
        Ok(Err(error)) => encode_error(&error.to_string()),
        Err(_) => encode_error("native QUIC diagnostic panicked"),
    };
    env.new_string(encoded)
        .map_or(ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_jonalakas_bridgepad_streaming_QuicMediaNative_cancelProbe(
    _env: JNIEnv,
    _class: JClass,
) {
    CANCELLED.store(true, Ordering::Release);
}

fn run_probe(host: &str, port: u16, duration_seconds: u16) -> Result<Measurement, AnyError> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_io()
        .enable_time()
        .build()?;
    runtime.block_on(run_probe_async(host, port, duration_seconds))
}

/// Runs the same diagnostic used by JNI and returns its wire-neutral result.
///
/// This exists so the fixed-certificate Desktop/Quinn path can be smoke-tested
/// on the build host before installing an Android APK.
pub fn run_probe_diagnostic(
    host: &str,
    port: u16,
    duration_seconds: u16,
) -> Result<String, String> {
    CANCELLED.store(false, Ordering::Release);
    run_probe(host, port, duration_seconds)
        .map(|measurement| measurement.encode())
        .map_err(|error| error.to_string())
}

async fn run_probe_async(
    host: &str,
    port: u16,
    duration_seconds: u16,
) -> Result<Measurement, AnyError> {
    let remote = resolve_remote(host, port).await?;
    let local_ip = route_local_ip(remote)?;
    let mut endpoint = Endpoint::client(match remote {
        SocketAddr::V4(_) => "0.0.0.0:0".parse()?,
        SocketAddr::V6(_) => "[::]:0".parse()?,
    })?;
    endpoint.set_default_client_config(client_config()?);

    let handshake_started = Instant::now();
    let connecting = endpoint.connect(remote, TEST_SERVER_NAME)?;
    let connection = timeout(Duration::from_secs(7), connecting).await??;
    let handshake_millis = duration_millis(handshake_started.elapsed());
    let mut hello = Vec::with_capacity(10);
    hello.extend_from_slice(&HELLO_MAGIC);
    hello.extend_from_slice(&duration_seconds.to_be_bytes());
    let mut hello_stream = timeout(Duration::from_secs(2), connection.open_uni()).await??;
    hello_stream.write_all(&hello).await?;
    hello_stream.finish()?;

    let expected_frames = u64::from(duration_seconds) * FRAMES_PER_SECOND;
    let expected_packets = expected_frames * PACKETS_PER_FRAME;
    let started = Instant::now();
    let mut previous_arrival = None;
    let mut arrival_gaps = Vec::with_capacity(usize::try_from(expected_packets).unwrap_or(0));
    let mut received_packets = 0_u64;
    let mut received_bytes = 0_u64;
    let mut rejected_packets = 0_u64;
    let mut assembler = FrameAssembler::new(
        SESSION_ID,
        STREAM_ID,
        ASSEMBLY_DEADLINE_MICROS,
        MAX_IN_FLIGHT_FRAMES,
    );
    let overall_timeout = Duration::from_secs(u64::from(duration_seconds) + 5);

    while received_packets < expected_packets && started.elapsed() < overall_timeout {
        if CANCELLED.load(Ordering::Acquire) {
            connection.close(1_u32.into(), b"cancelled");
            endpoint.wait_idle().await;
            return Err("cancelled".into());
        }
        let datagram = match timeout(Duration::from_millis(100), connection.read_datagram()).await {
            Ok(Ok(datagram)) => datagram,
            Ok(Err(error)) => return Err(error.into()),
            Err(_) => continue,
        };
        if datagram.as_ref() == DONE_MAGIC {
            continue;
        }
        let arrival = Instant::now();
        if let Some(previous) = previous_arrival.replace(arrival) {
            arrival_gaps.push(arrival.duration_since(previous));
        }
        received_bytes = received_bytes.saturating_add(datagram.len() as u64);
        let received_at = u64::try_from(started.elapsed().as_micros()).unwrap_or(u64::MAX);
        match assembler.push(&datagram, received_at) {
            Ok(_) => received_packets = received_packets.saturating_add(1),
            Err(_) => rejected_packets = rejected_packets.saturating_add(1),
        }
    }

    let elapsed = started.elapsed();
    let elapsed_micros = u64::try_from(elapsed.as_micros()).unwrap_or(u64::MAX);
    assembler.expire(elapsed_micros.saturating_add(ASSEMBLY_DEADLINE_MICROS));
    arrival_gaps.sort_unstable();
    let metrics = assembler.metrics();
    connection.close(0_u32.into(), b"diagnostic complete");
    endpoint.wait_idle().await;
    Ok(Measurement {
        local_ip,
        received_packets,
        expected_packets,
        completed_frames: metrics.completed_frames,
        expected_frames,
        expired_frames: metrics.expired_frames,
        duplicate_packets: metrics.duplicate_packets,
        rejected_packets,
        received_bytes,
        elapsed_millis: duration_millis(elapsed),
        handshake_millis,
        arrival_gap_p95_micros: percentile_micros(&arrival_gaps, 95),
        arrival_gap_p99_micros: percentile_micros(&arrival_gaps, 99),
        arrival_gap_max_micros: arrival_gaps.last().map_or(0, duration_micros),
    })
}

fn client_config() -> Result<ClientConfig, AnyError> {
    let crypto = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(DiagnosticCertificateVerifier::new())
        .with_no_client_auth();
    let mut config = ClientConfig::new(Arc::new(QuicClientConfig::try_from(crypto)?));
    let mut transport = TransportConfig::default();
    transport.datagram_receive_buffer_size(Some(4 * 1024 * 1024));
    transport.datagram_send_buffer_size(4 * 1024 * 1024);
    transport.max_idle_timeout(Some(quinn::IdleTimeout::try_from(Duration::from_secs(15))?));
    config.transport_config(Arc::new(transport));
    Ok(config)
}

/// Test-only verifier for the ephemeral identity created by `media_quic_server`.
/// It skips chain trust while retaining TLS handshake signature verification.
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

async fn resolve_remote(host: &str, port: u16) -> Result<SocketAddr, AnyError> {
    tokio::net::lookup_host((host, port))
        .await?
        .next()
        .ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "host has no address").into()
        })
}

fn route_local_ip(remote: SocketAddr) -> Result<IpAddr, AnyError> {
    let socket = UdpSocket::bind(match remote {
        SocketAddr::V4(_) => "0.0.0.0:0",
        SocketAddr::V6(_) => "[::]:0",
    })?;
    socket.connect(remote)?;
    Ok(socket.local_addr()?.ip())
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
    handshake_millis: u64,
    arrival_gap_p95_micros: u64,
    arrival_gap_p99_micros: u64,
    arrival_gap_max_micros: u64,
}

impl Measurement {
    fn encode(&self) -> String {
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
            self.handshake_millis,
            self.arrival_gap_p95_micros,
            self.arrival_gap_p99_micros,
            self.arrival_gap_max_micros,
        )
    }
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
