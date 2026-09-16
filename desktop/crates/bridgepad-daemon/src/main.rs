//! Encrypted BridgePad receiver for the network probe and first playable flow.

use bridgepad_protocol::{
    CAPABILITY_GAMEPAD, HEADER_SIZE, MAX_PAYLOAD_SIZE, MessageType, PacketHeader,
    decode_gamepad_snapshot, decode_packet, decode_session_start, encode_packet,
};
use bridgepad_virtual_device::{DpadDirection, GamepadReport, VirtualGamepadDevice};
use bridgepad_windows_vigem::VigemGamepad;
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

    println!("BridgePad Desktop receiver");
    println!("Listening on {}", listener.local_addr()?);
    println!("Certificate SHA-256: {fingerprint}");
    println!("Enter this fingerprint exactly in the Android network screen.");
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
    socket.set_read_timeout(Some(std::time::Duration::from_secs(2)))?;
    let connection = ServerConnection::new(config)?;
    let mut stream = StreamOwned::new(connection, socket);
    println!("Encrypted client connected from {peer}");
    let mut gamepad: Option<GamepadLease> = None;
    let mut active_session_id = None;
    let mut latest_gamepad_sequence = None;

    loop {
        let Some(bytes) = read_packet(&mut stream)? else {
            println!("Client disconnected from {peer}");
            return Ok(());
        };
        let packet = decode_packet(&bytes)?;
        match packet.header.message_type {
            MessageType::Ping => {
                if packet.payload.len() != 8 {
                    return Err("invalid Ping payload".into());
                }
                write_response(
                    &mut stream,
                    packet.header,
                    MessageType::Pong,
                    packet.payload,
                )?;
            }
            MessageType::SessionStart => {
                if gamepad.is_some() {
                    return Err("a gamepad session is already active on this connection".into());
                }
                let request = decode_session_start(packet)?;
                if request.input_kind > 1 {
                    return Err("unsupported input kind".into());
                }
                if request.requested_capabilities & CAPABILITY_GAMEPAD == 0 {
                    return Err("the client did not request the gamepad capability".into());
                }
                let device = VigemGamepad::connect()?;
                gamepad = Some(GamepadLease::new(Box::new(device))?);
                active_session_id = Some(packet.header.session_id);
                latest_gamepad_sequence = None;
                write_response(
                    &mut stream,
                    packet.header,
                    MessageType::SessionReady,
                    &CAPABILITY_GAMEPAD.to_be_bytes(),
                )?;
                println!("Playable gamepad session started for {peer}");
            }
            MessageType::GamepadSnapshot => {
                if active_session_id != Some(packet.header.session_id) {
                    return Err("gamepad snapshot does not belong to the active session".into());
                }
                if is_newer_sequence(latest_gamepad_sequence, packet.header.sequence) {
                    let snapshot = decode_gamepad_snapshot(packet)?;
                    let report = to_gamepad_report(snapshot)?;
                    gamepad
                        .as_mut()
                        .ok_or("gamepad session is not active")?
                        .update(report)?;
                    latest_gamepad_sequence = Some(packet.header.sequence);
                }
            }
            MessageType::SessionStop => {
                if active_session_id == Some(packet.header.session_id) {
                    gamepad = None;
                    active_session_id = None;
                    latest_gamepad_sequence = None;
                    println!("Playable gamepad session stopped for {peer}");
                }
            }
            _ => return Err("message is not supported by the first playable flow".into()),
        }
    }
}

fn write_response(
    stream: &mut impl Write,
    request: PacketHeader,
    message_type: MessageType,
    payload: &[u8],
) -> Result<(), AnyError> {
    let response = encode_packet(
        PacketHeader {
            session_id: request.session_id,
            sequence: request.sequence,
            timestamp_micros: monotonic_micros(),
            message_type,
        },
        payload,
    )?;
    stream.write_all(&response)?;
    stream.flush()?;
    Ok(())
}

fn is_newer_sequence(latest: Option<u32>, candidate: u32) -> bool {
    latest.is_none_or(|previous| {
        let distance = candidate.wrapping_sub(previous);
        distance != 0 && distance < 0x8000_0000
    })
}

fn to_gamepad_report(
    snapshot: bridgepad_protocol::GamepadSnapshot,
) -> Result<GamepadReport, AnyError> {
    let dpad = match snapshot.dpad {
        0 => DpadDirection::Neutral,
        1 => DpadDirection::North,
        2 => DpadDirection::NorthEast,
        3 => DpadDirection::East,
        4 => DpadDirection::SouthEast,
        5 => DpadDirection::South,
        6 => DpadDirection::SouthWest,
        7 => DpadDirection::West,
        8 => DpadDirection::NorthWest,
        _ => return Err("invalid d-pad direction".into()),
    };
    Ok(GamepadReport {
        buttons: snapshot.buttons,
        dpad,
        left_x: snapshot.left_x,
        left_y: snapshot.left_y,
        right_x: snapshot.right_x,
        right_y: snapshot.right_y,
        left_trigger: snapshot.left_trigger,
        right_trigger: snapshot.right_trigger,
    })
}

struct GamepadLease {
    device: Box<dyn VirtualGamepadDevice>,
}

impl GamepadLease {
    fn new(mut device: Box<dyn VirtualGamepadDevice>) -> Result<Self, AnyError> {
        device.neutralize()?;
        Ok(Self { device })
    }

    fn update(&mut self, report: GamepadReport) -> Result<(), AnyError> {
        self.device.update(report)?;
        Ok(())
    }
}

impl Drop for GamepadLease {
    fn drop(&mut self) {
        if let Err(error) = self.device.neutralize() {
            eprintln!("Could not neutralize virtual gamepad: {error}");
        }
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
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "payload exceeds v1 limit",
        ));
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

#[cfg(test)]
mod tests {
    use super::*;
    use bridgepad_protocol::GamepadSnapshot;

    #[test]
    fn maps_wire_snapshot_without_transport_specific_changes() {
        let report = to_gamepad_report(GamepadSnapshot {
            buttons: 0x0123,
            dpad: 6,
            left_x: -12_345,
            left_y: 23_456,
            right_x: i16::MIN + 1,
            right_y: i16::MAX,
            left_trigger: 32_768,
            right_trigger: u16::MAX,
        })
        .expect("valid snapshot must map");

        assert_eq!(report.buttons, 0x0123);
        assert_eq!(report.dpad, DpadDirection::SouthWest);
        assert_eq!(report.left_x, -12_345);
        assert_eq!(report.left_y, 23_456);
        assert_eq!(report.right_x, i16::MIN + 1);
        assert_eq!(report.right_y, i16::MAX);
        assert_eq!(report.left_trigger, 32_768);
        assert_eq!(report.right_trigger, u16::MAX);
    }

    #[test]
    fn sequence_filter_rejects_duplicates_and_old_packets_across_wraparound() {
        assert!(is_newer_sequence(None, u32::MAX));
        assert!(!is_newer_sequence(Some(7), 7));
        assert!(!is_newer_sequence(Some(7), 6));
        assert!(is_newer_sequence(Some(u32::MAX), 0));
    }
}
