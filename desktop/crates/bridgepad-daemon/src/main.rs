//! Minimal encrypted BridgePad receiver used by the Android network probe.

use bridgepad_protocol::{
    HEADER_SIZE, MAX_PAYLOAD_SIZE, MessageType, PacketHeader, decode_packet, encode_packet,
};
use rcgen::{CertifiedKey, generate_simple_self_signed};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::thread;
use std::time::Instant;

const DEFAULT_ADDRESS: &str = "0.0.0.0:39393";
const DEFAULT_IDENTITY_DIRECTORY: &str = ".bridgepad-dev";

type AnyError = Box<dyn std::error::Error + Send + Sync>;

fn main() -> Result<(), AnyError> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| "a rustls crypto provider was already installed")?;

    let address = argument("--listen").unwrap_or_else(|| DEFAULT_ADDRESS.to_owned());
    let identity_directory = argument("--identity-dir")
        .map_or_else(|| PathBuf::from(DEFAULT_IDENTITY_DIRECTORY), PathBuf::from);
    let identity = load_or_create_identity(&identity_directory)?;
    let fingerprint = sha256_fingerprint(&identity.certificate);
    let config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(identity.certificate)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.private_key)),
        )?;
    let listener = TcpListener::bind(&address)?;

    println!("BridgePad Desktop diagnostic receiver");
    println!("Listening on {}", listener.local_addr()?);
    println!("Certificate SHA-256: {fingerprint}");
    println!("Enter this fingerprint exactly in the Android diagnostic screen.");
    println!("Press Ctrl+C to stop.\n");

    let config = Arc::new(config);
    for connection in listener.incoming() {
        match connection {
            Ok(socket) => {
                let config = Arc::clone(&config);
                thread::spawn(move || {
                    if let Err(error) = serve(socket, config) {
                        eprintln!("Connection ended: {error}");
                    }
                });
            }
            Err(error) => eprintln!("Accept failed: {error}"),
        }
    }
    Ok(())
}

fn serve(socket: TcpStream, config: Arc<ServerConfig>) -> Result<(), AnyError> {
    let peer = socket.peer_addr()?;
    socket.set_nodelay(true)?;
    socket.set_read_timeout(Some(std::time::Duration::from_secs(10)))?;
    let connection = ServerConnection::new(config)?;
    let mut stream = StreamOwned::new(connection, socket);
    println!("Encrypted probe connected from {peer}");

    loop {
        let Some(bytes) = read_packet(&mut stream)? else {
            println!("Probe disconnected from {peer}");
            return Ok(());
        };
        let packet = decode_packet(&bytes)?;
        if packet.header.message_type != MessageType::Ping || packet.payload.len() != 8 {
            return Err("diagnostic receiver accepts only v1 Ping messages".into());
        }
        let response = encode_packet(
            PacketHeader {
                session_id: packet.header.session_id,
                sequence: packet.header.sequence,
                timestamp_micros: monotonic_micros(),
                message_type: MessageType::Pong,
            },
            packet.payload,
        )?;
        stream.write_all(&response)?;
        stream.flush()?;
    }
}

fn read_packet(stream: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0_u8; HEADER_SIZE];
    match stream.read_exact(&mut header) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error),
    }
    let payload_length = usize::from(u16::from_be_bytes([header[28], header[29]]));
    if payload_length > MAX_PAYLOAD_SIZE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "payload exceeds v1 limit"));
    }
    let mut packet = Vec::with_capacity(HEADER_SIZE + payload_length);
    packet.extend_from_slice(&header);
    packet.resize(HEADER_SIZE + payload_length, 0);
    stream.read_exact(&mut packet[HEADER_SIZE..])?;
    Ok(Some(packet))
}

struct DiagnosticIdentity {
    certificate: Vec<u8>,
    private_key: Vec<u8>,
}

fn load_or_create_identity(directory: &Path) -> Result<DiagnosticIdentity, AnyError> {
    let certificate_path = directory.join("certificate.der");
    let key_path = directory.join("private-key.der");
    match (fs::read(&certificate_path), fs::read(&key_path)) {
        (Ok(certificate), Ok(private_key)) => Ok(DiagnosticIdentity {
            certificate,
            private_key,
        }),
        (Err(certificate_error), Err(key_error))
            if certificate_error.kind() == io::ErrorKind::NotFound
                && key_error.kind() == io::ErrorKind::NotFound =>
        {
            fs::create_dir_all(directory)?;
            let CertifiedKey { cert, signing_key } =
                generate_simple_self_signed(vec!["bridgepad.local".to_owned()])?;
            let identity = DiagnosticIdentity {
                certificate: cert.der().to_vec(),
                private_key: signing_key.serialize_der(),
            };
            fs::write(certificate_path, &identity.certificate)?;
            fs::write(key_path, &identity.private_key)?;
            Ok(identity)
        }
        _ => Err("diagnostic identity is incomplete; delete its directory and retry".into()),
    }
}

fn sha256_fingerprint(certificate: &[u8]) -> String {
    Sha256::digest(certificate)
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

fn monotonic_micros() -> u64 {
    static STARTED_AT: OnceLock<Instant> = OnceLock::new();
    STARTED_AT
        .get_or_init(Instant::now)
        .elapsed()
        .as_micros()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn argument(name: &str) -> Option<String> {
    let mut arguments = std::env::args();
    while let Some(argument) = arguments.next() {
        if argument == name {
            return arguments.next();
        }
    }
    None
}
