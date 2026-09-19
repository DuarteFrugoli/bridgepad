//! Diagnostic loopback comparison. This is not a production transport.

use rcgen::{CertifiedKey, generate_simple_self_signed};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::{
    ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection, StreamOwned,
};
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

const DEFAULT_ITERATIONS: usize = 2_000;
const DEFAULT_PAYLOAD_SIZE: usize = 64;

type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[tokio::main]
async fn main() -> Result<(), AnyError> {
    let iterations = argument("--iterations").unwrap_or(DEFAULT_ITERATIONS);
    let payload_size = argument("--payload-size").unwrap_or(DEFAULT_PAYLOAD_SIZE);
    if iterations < 2 || payload_size == 0 {
        return Err("iterations must be at least 2 and payload size must be positive".into());
    }

    println!("BridgePad encrypted transport loopback spike");
    println!("iterations={iterations}, payload_size={payload_size} bytes");
    println!("Plain TCP is diagnostic-only and can never be a production mode.\n");

    let plain = measure_plain(iterations, payload_size)?;
    let tls = measure_tls(iterations, payload_size)?;
    let quic = measure_quic(iterations, payload_size).await?;
    print_measurement("TCP diagnostic baseline", &plain);
    print_measurement("TLS/TCP", &tls);
    print_measurement("QUIC datagrams", &quic);
    println!(
        "\nTLS added {:.3} ms to p95 loopback RTT.",
        duration_ms(tls.p95.saturating_sub(plain.p95)),
    );
    println!(
        "QUIC datagrams added {:.3} ms to p95 loopback RTT.",
        duration_ms(quic.p95.saturating_sub(plain.p95)),
    );
    println!("Run the Android/LAN matrix before selecting the production transport.");
    Ok(())
}

#[derive(Debug)]
struct Measurement {
    connect_and_first_round_trip: Duration,
    p50: Duration,
    p95: Duration,
    p99: Duration,
}

fn measure_plain(iterations: usize, payload_size: usize) -> io::Result<Measurement> {
    let (address, server) = spawn_plain_echo(iterations, payload_size)?;
    let connected_at = Instant::now();
    let stream = TcpStream::connect(address)?;
    stream.set_nodelay(true)?;
    let measurement = measure_round_trips(stream, iterations, payload_size, connected_at)?;
    server.join().expect("plain echo server panicked")?;
    Ok(measurement)
}

fn measure_tls(iterations: usize, payload_size: usize) -> Result<Measurement, AnyError> {
    let (server_config, client_config) = tls_configs()?;
    let (address, server) = spawn_tls_echo(server_config, iterations, payload_size)?;
    let connected_at = Instant::now();
    let socket = TcpStream::connect(address)?;
    socket.set_nodelay(true)?;
    let connection =
        ClientConnection::new(client_config, ServerName::try_from("localhost")?.to_owned())?;
    let stream = StreamOwned::new(connection, socket);
    let measurement = measure_round_trips(stream, iterations, payload_size, connected_at)?;
    server.join().expect("TLS echo server panicked")?;
    Ok(measurement)
}

async fn measure_quic(iterations: usize, payload_size: usize) -> Result<Measurement, AnyError> {
    let CertifiedKey { cert, signing_key } =
        generate_simple_self_signed(vec!["localhost".to_owned()])?;
    let certificate: CertificateDer<'static> = cert.der().clone();
    let private_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(signing_key.serialize_der()));
    let server_config =
        quinn::ServerConfig::with_single_cert(vec![certificate.clone()], private_key)?;
    let server_endpoint = quinn::Endpoint::server(server_config, "127.0.0.1:0".parse()?)?;
    let address = server_endpoint.local_addr()?;

    let server = tokio::spawn(async move {
        let incoming = server_endpoint.accept().await.ok_or_else(|| {
            io::Error::new(io::ErrorKind::ConnectionAborted, "QUIC endpoint closed")
        })?;
        let connection = incoming.await?;
        for _ in 0..iterations {
            let payload = connection.read_datagram().await?;
            connection.send_datagram(payload)?;
        }
        connection.closed().await;
        Ok::<(), AnyError>(())
    });

    let mut roots = RootCertStore::empty();
    roots.add(certificate)?;
    let client_config = quinn::ClientConfig::with_root_certificates(Arc::new(roots))?;
    let mut client_endpoint = quinn::Endpoint::client("127.0.0.1:0".parse()?)?;
    client_endpoint.set_default_client_config(client_config);

    let connected_at = Instant::now();
    let connection = client_endpoint.connect(address, "localhost")?.await?;
    let outgoing = vec![0x5a_u8; payload_size];
    let mut samples = Vec::with_capacity(iterations - 1);
    let mut first_round_trip = Duration::ZERO;
    for index in 0..iterations {
        let started_at = Instant::now();
        connection.send_datagram(outgoing.clone().into())?;
        let incoming = connection.read_datagram().await?;
        if incoming.as_ref() != outgoing {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "echo payload changed").into());
        }
        if index == 0 {
            first_round_trip = connected_at.elapsed();
        } else {
            samples.push(started_at.elapsed());
        }
    }
    connection.close(0_u32.into(), b"spike complete");
    server.await??;
    samples.sort_unstable();
    Ok(Measurement {
        connect_and_first_round_trip: first_round_trip,
        p50: percentile(&samples, 50),
        p95: percentile(&samples, 95),
        p99: percentile(&samples, 99),
    })
}

fn spawn_plain_echo(
    iterations: usize,
    payload_size: usize,
) -> io::Result<(SocketAddr, thread::JoinHandle<io::Result<()>>)> {
    let listener = TcpListener::bind(("127.0.0.1", 0))?;
    let address = listener.local_addr()?;
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept()?;
        stream.set_nodelay(true)?;
        echo(stream, iterations, payload_size)
    });
    Ok((address, server))
}

fn spawn_tls_echo(
    config: Arc<ServerConfig>,
    iterations: usize,
    payload_size: usize,
) -> io::Result<(SocketAddr, thread::JoinHandle<io::Result<()>>)> {
    let listener = TcpListener::bind(("127.0.0.1", 0))?;
    let address = listener.local_addr()?;
    let server = thread::spawn(move || {
        let (socket, _) = listener.accept()?;
        socket.set_nodelay(true)?;
        let connection = ServerConnection::new(config).map_err(io::Error::other)?;
        echo(
            StreamOwned::new(connection, socket),
            iterations,
            payload_size,
        )
    });
    Ok((address, server))
}

fn echo(mut stream: impl Read + Write, iterations: usize, payload_size: usize) -> io::Result<()> {
    let mut payload = vec![0_u8; payload_size];
    for _ in 0..iterations {
        stream.read_exact(&mut payload)?;
        stream.write_all(&payload)?;
        stream.flush()?;
    }
    Ok(())
}

fn measure_round_trips(
    mut stream: impl Read + Write,
    iterations: usize,
    payload_size: usize,
    connected_at: Instant,
) -> io::Result<Measurement> {
    let outgoing = vec![0x5a_u8; payload_size];
    let mut incoming = vec![0_u8; payload_size];
    let mut samples = Vec::with_capacity(iterations - 1);
    let mut first_round_trip = Duration::ZERO;

    for index in 0..iterations {
        let started_at = Instant::now();
        stream.write_all(&outgoing)?;
        stream.flush()?;
        stream.read_exact(&mut incoming)?;
        if incoming != outgoing {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "echo payload changed",
            ));
        }
        if index == 0 {
            first_round_trip = connected_at.elapsed();
        } else {
            samples.push(started_at.elapsed());
        }
    }
    samples.sort_unstable();
    Ok(Measurement {
        connect_and_first_round_trip: first_round_trip,
        p50: percentile(&samples, 50),
        p95: percentile(&samples, 95),
        p99: percentile(&samples, 99),
    })
}

fn tls_configs() -> Result<(Arc<ServerConfig>, Arc<ClientConfig>), AnyError> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| "a rustls crypto provider was already installed")?;
    let CertifiedKey { cert, signing_key } =
        generate_simple_self_signed(vec!["localhost".to_owned()])?;
    let certificate: CertificateDer<'static> = cert.der().clone();
    let private_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(signing_key.serialize_der()));

    let server = ServerConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
        .with_no_client_auth()
        .with_single_cert(vec![certificate.clone()], private_key)?;
    let mut roots = RootCertStore::empty();
    roots.add(certificate)?;
    let client = ClientConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
        .with_root_certificates(roots)
        .with_no_client_auth();
    Ok((Arc::new(server), Arc::new(client)))
}

fn percentile(sorted: &[Duration], percentile: usize) -> Duration {
    let index = ((sorted.len() - 1) * percentile).div_ceil(100);
    sorted[index]
}

fn print_measurement(name: &str, result: &Measurement) {
    println!("{name}:");
    println!(
        "  connect + first RTT: {:.3} ms",
        duration_ms(result.connect_and_first_round_trip),
    );
    println!(
        "  steady RTT p50/p95/p99: {:.3}/{:.3}/{:.3} ms",
        duration_ms(result.p50),
        duration_ms(result.p95),
        duration_ms(result.p99),
    );
}

fn duration_ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}

fn argument(name: &str) -> Option<usize> {
    let mut arguments = std::env::args();
    while let Some(argument) = arguments.next() {
        if argument == name {
            return arguments.next()?.parse().ok();
        }
    }
    None
}
