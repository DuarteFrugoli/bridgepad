//! Encrypted BridgePad receiver with local discovery and paired client authentication.

mod auth;
mod discovery;
mod trust;

use auth::{
    authentication_transcript, create_proof, derive_pairing_key, pairing_transcript,
    role_transcript, secure_array, verify_proof, PairingWindow, NONCE_SIZE, PBKDF2_ITERATIONS,
    SALT_SIZE,
};
use bridgepad_protocol::{
    decode_auth_proof, decode_auth_request, decode_gamepad_snapshot, decode_keyboard,
    decode_packet, decode_pair_request, decode_pointer, decode_session_start, encode_packet,
    KeyboardInput as ProtocolKeyboardInput, KeyboardKey as ProtocolKeyboardKey, MessageType,
    PacketHeader, CAPABILITY_GAMEPAD, CAPABILITY_KEYBOARD, CAPABILITY_POINTER, HEADER_SIZE,
    MAX_PAYLOAD_SIZE, MAX_PEER_NAME_SIZE,
};
use bridgepad_virtual_device::{
    DpadDirection, GamepadReport, KeyboardInput, KeyboardKey, PointerReport,
    VirtualGamepadDevice, VirtualKeyboardDevice, VirtualPointerDevice,
};
use bridgepad_windows_pointer::{WindowsKeyboard, WindowsPointer};
use bridgepad_windows_vigem::VigemGamepad;
use rcgen::{generate_simple_self_signed, CertifiedKey};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};
use trust::{decode_array, encode_hex, TrustStore, TrustedPeer, PEER_ID_SIZE, SHARED_SECRET_SIZE};

pub const DEFAULT_ADDRESS: &str = "0.0.0.0:39393";
pub const DEFAULT_IDENTITY_DIRECTORY: &str = ".bridgepad-dev";

pub type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Clone, Debug)]
pub struct DaemonOptions {
    pub address: String,
    pub identity_directory: PathBuf,
    pub desktop_name: String,
    pub allow_unpaired: bool,
}

impl Default for DaemonOptions {
    fn default() -> Self {
        Self {
            address: DEFAULT_ADDRESS.to_owned(),
            identity_directory: PathBuf::from(DEFAULT_IDENTITY_DIRECTORY),
            desktop_name: default_desktop_name(),
            allow_unpaired: false,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TrustedDevice {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DaemonSnapshot {
    pub desktop_name: String,
    pub desktop_id: String,
    pub certificate_fingerprint: String,
    pub listen_address: String,
    pub pairing_code: String,
    pub pairing_expires_in_seconds: u16,
    pub trusted_devices: Vec<TrustedDevice>,
    pub connected_clients: usize,
    pub active_sessions: usize,
    pub last_error: Option<String>,
}

pub struct DesktopServer {
    state: Arc<ServerState>,
    stop: Arc<AtomicBool>,
    worker: Option<thread::JoinHandle<()>>,
}

impl DesktopServer {
    pub fn start(options: DaemonOptions) -> Result<Self, AnyError> {
        install_crypto_provider();
        if options.desktop_name.as_bytes().len() > MAX_PEER_NAME_SIZE {
            return Err(format!("desktop name exceeds {MAX_PEER_NAME_SIZE} UTF-8 bytes").into());
        }

        let identity = load_or_create_identity(&options.identity_directory)?;
        let certificate_fingerprint: [u8; 32] = Sha256::digest(&identity.certificate).into();
        let peer_id: [u8; PEER_ID_SIZE] = certificate_fingerprint[..PEER_ID_SIZE]
            .try_into()
            .expect("fingerprint contains a peer id");
        let config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(identity.certificate)],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.private_key)),
            )?;
        let listener = TcpListener::bind(&options.address)?;
        listener.set_nonblocking(true)?;
        let local_address = listener.local_addr()?;
        let state = Arc::new(ServerState {
            peer_id,
            certificate_fingerprint,
            desktop_name: options.desktop_name.clone(),
            listen_address: local_address.to_string(),
            trust_store: Mutex::new(TrustStore::load(&options.identity_directory)?),
            pairing: Mutex::new(PairingWindow::new()?),
            allow_unpaired: options.allow_unpaired,
            connected_clients: AtomicUsize::new(0),
            active_sessions: AtomicUsize::new(0),
            last_error: Mutex::new(None),
        });
        let fingerprint_mdns = encode_hex(&state.certificate_fingerprint);
        let peer_id_hex = encode_hex(&state.peer_id);
        let discovery = discovery::advertise(
            &options.desktop_name,
            local_address.port(),
            &peer_id_hex,
            &fingerprint_mdns,
        )?;
        let config = Arc::new(config);
        let stop = Arc::new(AtomicBool::new(false));
        let worker_state = Arc::clone(&state);
        let worker_stop = Arc::clone(&stop);
        let worker = thread::spawn(move || {
            let _discovery = discovery;
            while !worker_stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((socket, _)) => {
                        if let Ok(mut last_error) = worker_state.last_error.lock() {
                            *last_error = None;
                        }
                        let config = Arc::clone(&config);
                        let state = Arc::clone(&worker_state);
                        thread::spawn(move || {
                            let _connection = ConnectionLease::new(&state);
                            if let Err(error) = serve(socket, config, Arc::clone(&state)) {
                                let message = error.to_string();
                                eprintln!("Connection ended: {message}");
                            }
                        });
                    }
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(50));
                    }
                    Err(error) => {
                        let message = format!("Accept failed: {error}");
                        eprintln!("{message}");
                        if let Ok(mut last_error) = worker_state.last_error.lock() {
                            *last_error = Some(message);
                        }
                        thread::sleep(Duration::from_millis(250));
                    }
                }
            }
        });

        Ok(Self {
            state,
            stop,
            worker: Some(worker),
        })
    }

    pub fn snapshot(&self) -> Result<DaemonSnapshot, AnyError> {
        let mut pairing = lock(&self.state.pairing)?;
        let pairing_code = pairing.formatted_code()?;
        let pairing_expires_in_seconds = pairing.expires_in_seconds();
        let trust_store = lock(&self.state.trust_store)?;
        let mut trusted_devices: Vec<_> = trust_store
            .all()
            .map(|peer| TrustedDevice {
                id: encode_hex(&peer.id),
                name: peer.name.clone(),
            })
            .collect();
        trusted_devices.sort_by(|left, right| left.name.cmp(&right.name));
        let last_error = lock(&self.state.last_error)?.clone();
        Ok(DaemonSnapshot {
            desktop_name: self.state.desktop_name.clone(),
            desktop_id: encode_hex(&self.state.peer_id),
            certificate_fingerprint: encode_fingerprint(&self.state.certificate_fingerprint),
            listen_address: self.state.listen_address.clone(),
            pairing_code,
            pairing_expires_in_seconds,
            trusted_devices,
            connected_clients: self.state.connected_clients.load(Ordering::Relaxed),
            active_sessions: self.state.active_sessions.load(Ordering::Relaxed),
            last_error,
        })
    }

    pub fn rotate_pairing_code(&self) -> Result<(), AnyError> {
        lock(&self.state.pairing)?.rotate()?;
        Ok(())
    }

    pub fn forget_peer(&self, id: &str) -> Result<bool, AnyError> {
        let id = decode_array::<PEER_ID_SIZE>(id)
            .map_err(|message| format!("invalid peer id: {message}"))?;
        Ok(lock(&self.state.trust_store)?.forget(&id)?)
    }

    pub fn forget_all(&self) -> Result<(), AnyError> {
        lock(&self.state.trust_store)?.clear()?;
        Ok(())
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

impl Drop for DesktopServer {
    fn drop(&mut self) {
        self.stop();
    }
}

struct ServerState {
    peer_id: [u8; PEER_ID_SIZE],
    certificate_fingerprint: [u8; 32],
    desktop_name: String,
    listen_address: String,
    trust_store: Mutex<TrustStore>,
    pairing: Mutex<PairingWindow>,
    allow_unpaired: bool,
    connected_clients: AtomicUsize,
    active_sessions: AtomicUsize,
    last_error: Mutex<Option<String>>,
}

struct PairAttempt {
    request: bridgepad_protocol::PairRequest,
    server_nonce: [u8; NONCE_SIZE],
    salt: [u8; SALT_SIZE],
    code: String,
}

struct AuthAttempt {
    request: bridgepad_protocol::AuthRequest,
    server_nonce: [u8; NONCE_SIZE],
    secret: [u8; SHARED_SECRET_SIZE],
    known_peer: bool,
}

pub fn run_cli() -> Result<(), AnyError> {
    install_crypto_provider();
    let address = argument("--listen").unwrap_or_else(|| DEFAULT_ADDRESS.to_owned());
    let identity_directory = argument("--identity-dir")
        .map_or_else(|| PathBuf::from(DEFAULT_IDENTITY_DIRECTORY), PathBuf::from);
    let desktop_name = argument("--name").unwrap_or_else(default_desktop_name);
    if desktop_name.as_bytes().len() > MAX_PEER_NAME_SIZE {
        return Err(format!("desktop name exceeds {MAX_PEER_NAME_SIZE} UTF-8 bytes").into());
    }
    let mut trust_store = TrustStore::load(&identity_directory)?;

    if argument_flag("--list-peers") {
        print_trusted_peers(&trust_store);
        return Ok(());
    }
    if let Some(id) = argument("--forget-peer") {
        let id = decode_array::<PEER_ID_SIZE>(&id)
            .map_err(|message| format!("invalid peer id: {message}"))?;
        if trust_store.forget(&id)? {
            println!("Forgot trusted device {}", encode_hex(&id));
        } else {
            println!("No trusted device matched {}", encode_hex(&id));
        }
        return Ok(());
    }
    if argument_flag("--forget-all") {
        trust_store.clear()?;
        println!("Forgot every trusted device.");
        return Ok(());
    }

    drop(trust_store);
    let server = DesktopServer::start(DaemonOptions {
        address,
        identity_directory,
        desktop_name,
        allow_unpaired: argument_flag("--allow-unpaired"),
    })?;
    let snapshot = server.snapshot()?;

    println!("BridgePad Desktop receiver");
    println!("Desktop name: {}", snapshot.desktop_name);
    println!("Desktop ID: {}", snapshot.desktop_id);
    println!("Listening on {}", snapshot.listen_address);
    println!("Certificate SHA-256: {}", snapshot.certificate_fingerprint);
    println!(
        "Pairing code: {} (valid for 10 minutes)",
        snapshot.pairing_code
    );
    if server.state.allow_unpaired {
        println!("WARNING: unauthenticated diagnostic gameplay is enabled.");
    }
    println!("Android discovery service: {}", discovery::SERVICE_TYPE);
    println!("Press Ctrl+C to stop.\n");

    loop {
        thread::park_timeout(Duration::from_secs(60));
    }
}

fn install_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

fn serve(
    socket: TcpStream,
    config: Arc<ServerConfig>,
    state: Arc<ServerState>,
) -> Result<(), AnyError> {
    let peer = socket.peer_addr()?;
    // On Windows an accepted socket can inherit the listener's nonblocking
    // mode. The listener must stay nonblocking so the desktop UI can poll it,
    // but rustls' StreamOwned expects a blocking stream for this worker.
    socket.set_nonblocking(false)?;
    socket.set_nodelay(true)?;
    socket.set_read_timeout(Some(Duration::from_secs(10)))?;
    let connection = ServerConnection::new(config)?;
    let mut stream = StreamOwned::new(connection, socket);
    println!("TCP client connected from {peer}");
    stream.conn.complete_io(&mut stream.sock)?;
    if stream.conn.is_handshaking() {
        return Err("TLS handshake did not finish".into());
    }
    println!("TLS client authenticated from {peer}");
    let mut gamepad: Option<GamepadLease> = None;
    let mut pointer: Option<PointerLease> = None;
    let mut keyboard: Option<KeyboardLease> = None;
    let mut active_session_id = None;
    let mut latest_gamepad_sequence = None;
    let mut _session_lease: Option<SessionLease> = None;
    let mut authenticated_peer = None;
    let mut pair_attempt: Option<PairAttempt> = None;
    let mut auth_attempt: Option<AuthAttempt> = None;

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
            MessageType::PairRequest => {
                println!("Pairing request received from {peer}");
                if gamepad.is_some() || authenticated_peer.is_some() {
                    return Err("pairing is unavailable during an active session".into());
                }
                let request = decode_pair_request(packet)?;
                let (code, expires_in_seconds) = {
                    let mut pairing = lock(&state.pairing)?;
                    (pairing.code()?, pairing.expires_in_seconds())
                };
                let server_nonce = secure_array::<NONCE_SIZE>()?;
                let salt = secure_array::<SALT_SIZE>()?;
                let key = derive_pairing_key(&code, &salt);
                let transcript = pairing_transcript(
                    &request.peer_id,
                    &state.peer_id,
                    &request.client_nonce,
                    &server_nonce,
                    &state.certificate_fingerprint,
                );
                let server_proof = create_proof(
                    &key,
                    &role_transcript(b"bridgepad-pair-server-v1", &transcript),
                );
                let mut payload = Vec::with_capacity(
                    PEER_ID_SIZE + NONCE_SIZE + SALT_SIZE + 6 + server_proof.len(),
                );
                payload.extend_from_slice(&state.peer_id);
                payload.extend_from_slice(&server_nonce);
                payload.extend_from_slice(&salt);
                payload.extend_from_slice(&PBKDF2_ITERATIONS.to_be_bytes());
                payload.extend_from_slice(&expires_in_seconds.to_be_bytes());
                payload.extend_from_slice(&server_proof);
                pair_attempt = Some(PairAttempt {
                    request,
                    server_nonce,
                    salt,
                    code,
                });
                write_response(
                    &mut stream,
                    packet.header,
                    MessageType::PairChallenge,
                    &payload,
                )?;
            }
            MessageType::PairProof => {
                let attempt = pair_attempt
                    .take()
                    .ok_or("pairing proof has no challenge")?;
                let actual = decode_auth_proof(packet)?;
                let key = derive_pairing_key(&attempt.code, &attempt.salt);
                let common_transcript = pairing_transcript(
                    &attempt.request.peer_id,
                    &state.peer_id,
                    &attempt.request.client_nonce,
                    &attempt.server_nonce,
                    &state.certificate_fingerprint,
                );
                let transcript = role_transcript(b"bridgepad-pair-client-v1", &common_transcript);
                let accepted = verify_proof(&key, &transcript, &actual);
                if accepted {
                    let secret = secure_array::<SHARED_SECRET_SIZE>()?;
                    lock(&state.trust_store)?.insert(TrustedPeer {
                        id: attempt.request.peer_id,
                        name: attempt.request.peer_name.clone(),
                        secret,
                    })?;
                    authenticated_peer = Some(attempt.request.peer_id);
                    lock(&state.pairing)?.record_success()?;
                    write_response(
                        &mut stream,
                        packet.header,
                        MessageType::PairResult,
                        &pair_result_payload(true, &secret, &state.desktop_name, "")?,
                    )?;
                    println!(
                        "Paired {} ({})",
                        attempt.request.peer_name,
                        encode_hex(&attempt.request.peer_id)
                    );
                    print_pairing_code(&state)?;
                } else {
                    lock(&state.pairing)?.record_failure()?;
                    write_response(
                        &mut stream,
                        packet.header,
                        MessageType::PairResult,
                        &pair_result_payload(false, &[], &state.desktop_name, "code_rejected")?,
                    )?;
                    println!("Rejected pairing proof from {peer}");
                }
            }
            MessageType::AuthRequest => {
                let request = decode_auth_request(packet)?;
                let trusted = lock(&state.trust_store)?.get(&request.peer_id).cloned();
                let known_peer = trusted.is_some();
                let secret =
                    trusted.map_or(secure_array::<SHARED_SECRET_SIZE>()?, |item| item.secret);
                let server_nonce = secure_array::<NONCE_SIZE>()?;
                let mut payload = Vec::with_capacity(PEER_ID_SIZE + NONCE_SIZE);
                payload.extend_from_slice(&state.peer_id);
                payload.extend_from_slice(&server_nonce);
                auth_attempt = Some(AuthAttempt {
                    request,
                    server_nonce,
                    secret,
                    known_peer,
                });
                write_response(
                    &mut stream,
                    packet.header,
                    MessageType::AuthChallenge,
                    &payload,
                )?;
            }
            MessageType::AuthProof => {
                let attempt = auth_attempt
                    .take()
                    .ok_or("authentication proof has no challenge")?;
                let actual = decode_auth_proof(packet)?;
                let transcript = authentication_transcript(
                    &attempt.request.peer_id,
                    &state.peer_id,
                    &attempt.request.client_nonce,
                    &attempt.server_nonce,
                    &state.certificate_fingerprint,
                );
                let accepted =
                    attempt.known_peer && verify_proof(&attempt.secret, &transcript, &actual);
                if accepted {
                    authenticated_peer = Some(attempt.request.peer_id);
                    println!("Authenticated {}", encode_hex(&attempt.request.peer_id));
                }
                write_response(
                    &mut stream,
                    packet.header,
                    MessageType::AuthResult,
                    &auth_result_payload(
                        accepted,
                        &state.desktop_name,
                        if accepted {
                            ""
                        } else {
                            "authentication_rejected"
                        },
                    )?,
                )?;
            }
            MessageType::SessionStart => {
                if authenticated_peer.is_none() && !state.allow_unpaired {
                    return Err("authentication is required before gameplay".into());
                }
                if gamepad.is_some() {
                    return Err("a gamepad session is already active on this connection".into());
                }
                let request = decode_session_start(packet)?;
                if request.input_kind > 2 {
                    return Err("unsupported input kind".into());
                }
                if request.requested_capabilities & CAPABILITY_GAMEPAD == 0 {
                    return Err("the client did not request the gamepad capability".into());
                }
                gamepad = Some(GamepadLease::new(Box::new(VigemGamepad::connect()?))?);
                pointer = Some(PointerLease::new(Box::new(WindowsPointer::connect()))?);
                keyboard = Some(KeyboardLease::new(Box::new(WindowsKeyboard::connect())));
                active_session_id = Some(packet.header.session_id);
                _session_lease = Some(SessionLease::new(&state));
                latest_gamepad_sequence = None;
                write_response(
                    &mut stream,
                    packet.header,
                    MessageType::SessionReady,
                    &(CAPABILITY_GAMEPAD | CAPABILITY_POINTER | CAPABILITY_KEYBOARD).to_be_bytes(),
                )?;
                stream.sock.set_read_timeout(Some(Duration::from_secs(3)))?;
                println!("Playable gamepad session started for {peer}");
            }
            MessageType::GamepadSnapshot => {
                if active_session_id != Some(packet.header.session_id) {
                    return Err("gamepad snapshot does not belong to the active session".into());
                }
                if is_newer_sequence(latest_gamepad_sequence, packet.header.sequence) {
                    let report = to_gamepad_report(decode_gamepad_snapshot(packet)?)?;
                    gamepad
                        .as_mut()
                        .ok_or("gamepad session is not active")?
                        .update(report)?;
                    latest_gamepad_sequence = Some(packet.header.sequence);
                }
            }
            MessageType::Pointer => {
                if active_session_id != Some(packet.header.session_id) {
                    return Err("pointer report does not belong to the active session".into());
                }
                let report = decode_pointer(packet)?;
                pointer
                    .as_mut()
                    .ok_or("pointer session is not active")?
                    .update(PointerReport {
                        buttons: report.buttons,
                        delta_x: report.delta_x,
                        delta_y: report.delta_y,
                    })?;
            }
            MessageType::Keyboard => {
                if active_session_id != Some(packet.header.session_id) {
                    return Err("keyboard input does not belong to the active session".into());
                }
                let input = match decode_keyboard(packet)? {
                    ProtocolKeyboardInput::Text(text) => KeyboardInput::Text(text),
                    ProtocolKeyboardInput::Key(key) => KeyboardInput::Key(match key {
                        ProtocolKeyboardKey::Backspace => KeyboardKey::Backspace,
                        ProtocolKeyboardKey::Enter => KeyboardKey::Enter,
                        ProtocolKeyboardKey::Tab => KeyboardKey::Tab,
                        ProtocolKeyboardKey::Escape => KeyboardKey::Escape,
                    }),
                };
                keyboard
                    .as_mut()
                    .ok_or("keyboard session is not active")?
                    .send(input)?;
            }
            MessageType::SessionStop => {
                if active_session_id == Some(packet.header.session_id) {
                    gamepad = None;
                    pointer = None;
                    keyboard = None;
                    active_session_id = None;
                    _session_lease = None;
                    latest_gamepad_sequence = None;
                    println!("Playable gamepad session stopped for {peer}");
                }
            }
            _ => return Err("message is not supported by the receiver".into()),
        }
    }
}

fn pair_result_payload(
    accepted: bool,
    secret: &[u8],
    desktop_name: &str,
    detail: &str,
) -> Result<Vec<u8>, AnyError> {
    let mut payload = Vec::new();
    payload.push(u8::from(accepted));
    payload.push(u8::try_from(secret.len())?);
    payload.extend_from_slice(secret);
    write_string_u8(&mut payload, desktop_name)?;
    write_string_u16(&mut payload, detail)?;
    Ok(payload)
}

fn auth_result_payload(
    accepted: bool,
    desktop_name: &str,
    detail: &str,
) -> Result<Vec<u8>, AnyError> {
    let mut payload = vec![u8::from(accepted)];
    write_string_u8(&mut payload, desktop_name)?;
    write_string_u16(&mut payload, detail)?;
    Ok(payload)
}

fn write_string_u8(output: &mut Vec<u8>, value: &str) -> Result<(), AnyError> {
    let bytes = value.as_bytes();
    output.push(u8::try_from(bytes.len()).map_err(|_| "text is too long")?);
    output.extend_from_slice(bytes);
    Ok(())
}

fn write_string_u16(output: &mut Vec<u8>, value: &str) -> Result<(), AnyError> {
    let bytes = value.as_bytes();
    output.extend_from_slice(&u16::try_from(bytes.len())?.to_be_bytes());
    output.extend_from_slice(bytes);
    Ok(())
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

struct ConnectionLease<'a> {
    state: &'a ServerState,
}

impl<'a> ConnectionLease<'a> {
    fn new(state: &'a ServerState) -> Self {
        state.connected_clients.fetch_add(1, Ordering::Relaxed);
        Self { state }
    }
}

impl Drop for ConnectionLease<'_> {
    fn drop(&mut self) {
        self.state.connected_clients.fetch_sub(1, Ordering::Relaxed);
    }
}

struct SessionLease<'a> {
    state: &'a ServerState,
}

impl<'a> SessionLease<'a> {
    fn new(state: &'a ServerState) -> Self {
        state.active_sessions.fetch_add(1, Ordering::Relaxed);
        Self { state }
    }
}

impl Drop for SessionLease<'_> {
    fn drop(&mut self) {
        self.state.active_sessions.fetch_sub(1, Ordering::Relaxed);
    }
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

struct PointerLease {
    device: Box<dyn VirtualPointerDevice>,
}

struct KeyboardLease {
    device: Box<dyn VirtualKeyboardDevice>,
}

impl KeyboardLease {
    fn new(device: Box<dyn VirtualKeyboardDevice>) -> Self {
        Self { device }
    }

    fn send(&mut self, input: KeyboardInput) -> Result<(), AnyError> {
        self.device.send(input)?;
        Ok(())
    }
}

impl PointerLease {
    fn new(mut device: Box<dyn VirtualPointerDevice>) -> Result<Self, AnyError> {
        device.neutralize()?;
        Ok(Self { device })
    }

    fn update(&mut self, report: PointerReport) -> Result<(), AnyError> {
        self.device.update(report)?;
        Ok(())
    }
}

impl Drop for PointerLease {
    fn drop(&mut self) {
        if let Err(error) = self.device.neutralize() {
            eprintln!("Could not neutralize virtual pointer: {error}");
        }
    }
}

fn read_packet(stream: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0_u8; HEADER_SIZE];
    match stream.read_exact(&mut header) {
        Ok(()) => {}
        Err(error) if is_expected_disconnect(&error) => return Ok(None),
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
    match stream.read_exact(&mut packet[HEADER_SIZE..]) {
        Ok(()) => {}
        Err(error) if is_expected_disconnect(&error) => return Ok(None),
        Err(error) => return Err(error),
    }
    Ok(Some(packet))
}

fn is_expected_disconnect(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::UnexpectedEof
            | io::ErrorKind::WouldBlock
            | io::ErrorKind::TimedOut
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::NotConnected
    )
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

fn print_trusted_peers(store: &TrustStore) {
    let mut peers: Vec<_> = store.all().collect();
    peers.sort_by(|left, right| left.name.cmp(&right.name));
    if peers.is_empty() {
        println!("No trusted devices.");
        return;
    }
    for peer in peers {
        println!("{}  {}", encode_hex(&peer.id), peer.name);
    }
}

fn print_pairing_code(state: &ServerState) -> Result<(), AnyError> {
    let code = lock(&state.pairing)?.formatted_code()?;
    println!("Pairing code: {code} (valid for 10 minutes)");
    Ok(())
}

fn encode_fingerprint(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

pub fn default_desktop_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "BridgePad Desktop".to_owned())
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

fn argument_flag(name: &str) -> bool {
    std::env::args().any(|argument| argument == name)
}

fn lock<T>(mutex: &Mutex<T>) -> Result<std::sync::MutexGuard<'_, T>, AnyError> {
    mutex
        .lock()
        .map_err(|_| "shared state lock was poisoned".into())
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

    #[test]
    fn ordinary_socket_shutdowns_are_not_reported_as_service_errors() {
        for kind in [
            io::ErrorKind::UnexpectedEof,
            io::ErrorKind::WouldBlock,
            io::ErrorKind::TimedOut,
            io::ErrorKind::ConnectionReset,
            io::ErrorKind::ConnectionAborted,
            io::ErrorKind::NotConnected,
        ] {
            assert!(is_expected_disconnect(&io::Error::from(kind)));
        }
        assert!(!is_expected_disconnect(&io::Error::from(
            io::ErrorKind::InvalidData,
        )));
    }
}
