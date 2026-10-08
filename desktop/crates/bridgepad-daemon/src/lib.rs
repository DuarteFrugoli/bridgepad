//! Encrypted BridgePad receiver with local discovery and paired client authentication.

mod auth;
pub mod bluetooth;
mod discovery;
mod trust;

use auth::{
    NONCE_SIZE, PBKDF2_ITERATIONS, PairingWindow, SALT_SIZE, authentication_transcript,
    create_proof, derive_pairing_key, pairing_transcript, role_transcript, secure_array,
    verify_proof,
};
use bridgepad_protocol::{
    CAPABILITY_AUDIO, CAPABILITY_GAMEPAD, CAPABILITY_KEYBOARD, CAPABILITY_POINTER,
    CAPABILITY_VIDEO, GAMEPAD_WATCHDOG_TIMEOUT_MILLIS, HEADER_SIZE, KEYBOARD_MODIFIER_ALT,
    KEYBOARD_MODIFIER_CONTROL, KEYBOARD_MODIFIER_META, KEYBOARD_MODIFIER_SHIFT,
    KeyboardInput as ProtocolKeyboardInput, KeyboardKey as ProtocolKeyboardKey, MAX_PAYLOAD_SIZE,
    MAX_PEER_NAME_SIZE, MessageType, PacketHeader, decode_auth_proof, decode_auth_request,
    decode_gamepad_snapshot, decode_keyboard, decode_media_offer, decode_packet,
    decode_pair_request, decode_pointer, decode_session_description, decode_session_start,
    encode_media_answer, encode_packet, encode_session_description,
};
use bridgepad_virtual_device::{
    DpadDirection, GamepadReport, KeyboardInput, KeyboardKey, KeyboardModifiers, PointerReport,
    VirtualGamepadDevice, VirtualKeyboardDevice, VirtualPointerDevice,
};
use bridgepad_webrtc::{MediaAudioSender, MediaSendError, MediaWebRtcSession};
use bridgepad_windows_audio::{AudioCaptureError, AudioConfig, WindowsAudioLoopback};
use bridgepad_windows_capture::{CaptureConfig, CaptureError, WindowsCapturePipeline};
use bridgepad_windows_pointer::{WindowsKeyboard, WindowsPointer};
use bridgepad_windows_vigem::VigemGamepad;
use rcgen::{CertifiedKey, generate_simple_self_signed};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};
use trust::{PEER_ID_SIZE, SHARED_SECRET_SIZE, TrustStore, TrustedPeer, decode_array, encode_hex};

pub const DEFAULT_ADDRESS: &str = "0.0.0.0:39393";
pub const DEFAULT_MEDIA_ADDRESS: &str = "0.0.0.0:39394";
pub const DEFAULT_IDENTITY_DIRECTORY: &str = ".bridgepad-dev";

const GAMEPAD_WATCHDOG_TIMEOUT: Duration = Duration::from_millis(GAMEPAD_WATCHDOG_TIMEOUT_MILLIS);
const GAMEPAD_WATCHDOG_POLL_INTERVAL: Duration = Duration::from_millis(20);
const WEBRTC_CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const STREAM_BITRATE_UPDATE_INTERVAL: Duration = Duration::from_secs(1);
const STREAM_METRICS_INTERVAL: Duration = Duration::from_secs(2);

pub type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Clone, Debug)]
pub struct DaemonOptions {
    pub address: String,
    pub media_address: String,
    pub identity_directory: PathBuf,
    pub desktop_name: String,
    pub allow_unpaired: bool,
}

impl Default for DaemonOptions {
    fn default() -> Self {
        Self {
            address: DEFAULT_ADDRESS.to_owned(),
            media_address: DEFAULT_MEDIA_ADDRESS.to_owned(),
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
    pub media_listen_address: String,
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ConnectionPlane {
    Control,
    Media,
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
        let media_listener = TcpListener::bind(&options.media_address)?;
        media_listener.set_nonblocking(true)?;
        let media_local_address = media_listener.local_addr()?;
        let state = Arc::new(ServerState {
            peer_id,
            certificate_fingerprint,
            desktop_name: options.desktop_name.clone(),
            listen_address: local_address.to_string(),
            media_listen_address: media_local_address.to_string(),
            trust_store: Mutex::new(TrustStore::load(&options.identity_directory)?),
            pairing: Mutex::new(PairingWindow::new()?),
            allow_unpaired: options.allow_unpaired,
            connected_clients: AtomicUsize::new(0),
            active_sessions: AtomicUsize::new(0),
            active_sockets: Mutex::new(HashMap::new()),
            active_media: Mutex::new(None),
            next_connection_id: AtomicUsize::new(1),
            last_error: Mutex::new(None),
        });
        let fingerprint_mdns = encode_hex(&state.certificate_fingerprint);
        let peer_id_hex = encode_hex(&state.peer_id);
        let discovery = discovery::advertise(
            &options.desktop_name,
            local_address.port(),
            media_local_address.port(),
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
                let mut accepted = false;
                for (current_listener, plane) in [
                    (&listener, ConnectionPlane::Control),
                    (&media_listener, ConnectionPlane::Media),
                ] {
                    match current_listener.accept() {
                        Ok((socket, _)) => {
                            accepted = true;
                            spawn_connection(
                                socket,
                                Arc::clone(&config),
                                Arc::clone(&worker_state),
                                plane,
                            );
                        }
                        Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
                        Err(error) => record_accept_error(&worker_state, plane, &error),
                    }
                }
                if !accepted {
                    thread::sleep(Duration::from_millis(10));
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
            media_listen_address: self.state.media_listen_address.clone(),
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
        self.state.close_active_connections();
        // Active gameplay sockets use a three-second read timeout. Waiting a
        // little longer guarantees their leases can neutralize and unplug the
        // virtual devices even if a platform socket ignores shutdown briefly.
        let deadline = Instant::now() + Duration::from_secs(4);
        while self.state.connected_clients.load(Ordering::Relaxed) > 0 && Instant::now() < deadline
        {
            thread::sleep(Duration::from_millis(10));
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
    media_listen_address: String,
    trust_store: Mutex<TrustStore>,
    pairing: Mutex<PairingWindow>,
    allow_unpaired: bool,
    connected_clients: AtomicUsize,
    active_sessions: AtomicUsize,
    active_sockets: Mutex<HashMap<usize, TcpStream>>,
    active_media: Mutex<Option<ActiveMediaSession>>,
    next_connection_id: AtomicUsize,
    last_error: Mutex<Option<String>>,
}

impl ServerState {
    fn close_active_connections(&self) {
        if let Ok(active_media) = self.active_media.lock()
            && let Some(session) = active_media.as_ref()
        {
            session.cancel.store(true, Ordering::Relaxed);
        }
        if let Ok(sockets) = self.active_sockets.lock() {
            for socket in sockets.values() {
                let _ = socket.shutdown(Shutdown::Both);
            }
        }
    }
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
    let media_address =
        argument("--media-listen").unwrap_or_else(|| DEFAULT_MEDIA_ADDRESS.to_owned());
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
        media_address,
        identity_directory,
        desktop_name,
        allow_unpaired: argument_flag("--allow-unpaired"),
    })?;
    let snapshot = server.snapshot()?;

    println!("BridgePad Desktop receiver");
    println!("Desktop name: {}", snapshot.desktop_name);
    println!("Desktop ID: {}", snapshot.desktop_id);
    println!("Listening on {}", snapshot.listen_address);
    println!("Media listening on {}", snapshot.media_listen_address);
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

fn spawn_connection(
    socket: TcpStream,
    config: Arc<ServerConfig>,
    state: Arc<ServerState>,
    plane: ConnectionPlane,
) {
    if let Ok(mut last_error) = state.last_error.lock() {
        *last_error = None;
    }
    let connection_id = state.next_connection_id.fetch_add(1, Ordering::Relaxed);
    if let Ok(tracked_socket) = socket.try_clone()
        && let Ok(mut active_sockets) = state.active_sockets.lock()
    {
        active_sockets.insert(connection_id, tracked_socket);
    }
    thread::spawn(move || {
        let _connection = ConnectionLease::new(&state, connection_id, plane);
        if let Err(error) = serve(socket, config, Arc::clone(&state), plane, connection_id) {
            let message = error.to_string();
            eprintln!("{plane:?} connection ended: {message}");
        }
    });
}

fn record_accept_error(state: &ServerState, plane: ConnectionPlane, error: &io::Error) {
    let message = format!("{plane:?} accept failed: {error}");
    eprintln!("{message}");
    if let Ok(mut last_error) = state.last_error.lock() {
        *last_error = Some(message);
    }
}

fn serve(
    socket: TcpStream,
    config: Arc<ServerConfig>,
    state: Arc<ServerState>,
    plane: ConnectionPlane,
    connection_id: usize,
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
    println!("{plane:?} TCP client connected from {peer}");
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
                if plane != ConnectionPlane::Control {
                    return Err("pairing is only available on the control port".into());
                }
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
                if plane != ConnectionPlane::Control {
                    return Err("gameplay is only available on the control port".into());
                }
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
            MessageType::MediaOffer => {
                if plane != ConnectionPlane::Media {
                    return Err("media is only available on the media port".into());
                }
                if authenticated_peer.is_none() && !state.allow_unpaired {
                    return Err("authentication is required before streaming".into());
                }
                if gamepad.is_some() {
                    return Err("media must use its own connection".into());
                }
                let offer = decode_media_offer(packet)?;
                if offer.requested_capabilities & CAPABILITY_VIDEO == 0 {
                    return Err("the client did not request the video capability".into());
                }
                if offer.requested_capabilities & CAPABILITY_AUDIO == 0 {
                    return Err("the client did not request the audio capability".into());
                }
                let width = offer.max_width.min(1_280);
                let height = offer.max_height.min(720);
                let frames_per_second = offer.max_frames_per_second.min(60);
                if width == 0 || height == 0 || frames_per_second == 0 {
                    return Err("invalid media limits".into());
                }
                let maximum_bitrate = offer.max_bitrate_bits_per_second.max(500_000);
                let target_bitrate = maximum_bitrate.min(8_000_000);
                let answer = encode_media_answer(
                    CAPABILITY_VIDEO | CAPABILITY_AUDIO,
                    1,
                    width,
                    height,
                    frames_per_second,
                    target_bitrate,
                    1_000,
                );
                write_response(
                    &mut stream,
                    packet.header,
                    MessageType::MediaAnswer,
                    &answer,
                )?;
                stream
                    .sock
                    .set_read_timeout(Some(Duration::from_secs(20)))?;
                let media_session = MediaSessionLease::replace(&state, connection_id)?;
                return negotiate_and_stream_webrtc(
                    &mut stream,
                    packet.header,
                    CaptureConfig {
                        width: u32::from(width),
                        height: u32::from(height),
                        frames_per_second: u32::from(frames_per_second),
                        bitrate_bits_per_second: target_bitrate,
                        keyframe_interval_frames: u32::from(frames_per_second) * 2,
                        capture_cursor: true,
                    },
                    maximum_bitrate,
                    peer,
                    media_session.cancel_token(),
                );
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
                        scroll_y: report.scroll_y,
                        zoom_y: report.zoom_y,
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
                        ProtocolKeyboardKey::D => KeyboardKey::D,
                        ProtocolKeyboardKey::Enter => KeyboardKey::Enter,
                        ProtocolKeyboardKey::Left => KeyboardKey::Left,
                        ProtocolKeyboardKey::M => KeyboardKey::M,
                        ProtocolKeyboardKey::Right => KeyboardKey::Right,
                        ProtocolKeyboardKey::Tab => KeyboardKey::Tab,
                        ProtocolKeyboardKey::Escape => KeyboardKey::Escape,
                    }),
                    ProtocolKeyboardInput::Shortcut { modifiers, key } => KeyboardInput::Shortcut {
                        modifiers: KeyboardModifiers {
                            alt: modifiers & KEYBOARD_MODIFIER_ALT != 0,
                            control: modifiers & KEYBOARD_MODIFIER_CONTROL != 0,
                            meta: modifiers & KEYBOARD_MODIFIER_META != 0,
                            shift: modifiers & KEYBOARD_MODIFIER_SHIFT != 0,
                        },
                        key: match key {
                            ProtocolKeyboardKey::Backspace => KeyboardKey::Backspace,
                            ProtocolKeyboardKey::D => KeyboardKey::D,
                            ProtocolKeyboardKey::Enter => KeyboardKey::Enter,
                            ProtocolKeyboardKey::Left => KeyboardKey::Left,
                            ProtocolKeyboardKey::M => KeyboardKey::M,
                            ProtocolKeyboardKey::Right => KeyboardKey::Right,
                            ProtocolKeyboardKey::Tab => KeyboardKey::Tab,
                            ProtocolKeyboardKey::Escape => KeyboardKey::Escape,
                        },
                    },
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

fn negotiate_and_stream_webrtc(
    stream: &mut StreamOwned<ServerConnection, TcpStream>,
    request: PacketHeader,
    capture_config: CaptureConfig,
    maximum_bitrate: u32,
    peer: std::net::SocketAddr,
    cancelled: &AtomicBool,
) -> Result<(), AnyError> {
    let Some(bytes) = read_packet(stream)? else {
        return Err("client disconnected before sending its WebRTC offer".into());
    };
    let offer_packet = decode_packet(&bytes)?;
    if offer_packet.header.session_id != request.session_id
        || offer_packet.header.message_type != MessageType::WebRtcOffer
    {
        return Err("expected a WebRTC offer for the active media session".into());
    }
    let offer_sdp = decode_session_description(offer_packet)?;
    let (mut webrtc, answer_sdp) = MediaWebRtcSession::answer_offer(
        offer_sdp,
        capture_config.bitrate_bits_per_second,
        maximum_bitrate,
    )?;
    write_response(
        stream,
        offer_packet.header,
        MessageType::WebRtcAnswer,
        &encode_session_description(&answer_sdp)?,
    )?;
    webrtc.wait_connected(WEBRTC_CONNECT_TIMEOUT)?;
    if cancelled.load(Ordering::Relaxed) {
        return Ok(());
    }

    run_media_stream(capture_config, peer, cancelled, &webrtc)
}

fn run_media_stream(
    capture_config: CaptureConfig,
    peer: std::net::SocketAddr,
    cancelled: &AtomicBool,
    webrtc: &MediaWebRtcSession,
) -> Result<(), AnyError> {
    let capture = WindowsCapturePipeline::start_primary(capture_config)?;
    let audio = WindowsAudioLoopback::start(AudioConfig::default())?;
    let frame_duration = Duration::from_secs_f64(1.0 / f64::from(capture_config.frames_per_second));
    let mut applied_bitrate = capture_config.bitrate_bits_per_second;
    let mut last_bitrate_update = Instant::now();
    let mut previous_video_timestamp = None;
    let mut pending_keyframe = false;
    let mut latency = StreamLatencyMetrics::new();
    println!(
        "WebRTC stream started for {peer}: {}x{}@{} H.264 at {} kbps + 48 kHz stereo Opus",
        capture_config.width,
        capture_config.height,
        capture_config.frames_per_second,
        applied_bitrate / 1_000,
    );
    let media_stop = AtomicBool::new(false);
    let audio_failure = Mutex::new(None::<String>);
    let stream_result = thread::scope(|scope| -> Result<(), AnyError> {
        let media_stop = &media_stop;
        let audio_failure = &audio_failure;
        let audio_sender = webrtc.audio_sender();
        scope.spawn(move || {
            run_audio_stream(&audio, &audio_sender, media_stop, cancelled, audio_failure);
        });
        let video_sender = webrtc.video_sender();

        while !webrtc.is_closed() && !cancelled.load(Ordering::Relaxed) {
            if audio_failure
                .lock()
                .expect("audio failure lock poisoned")
                .is_some()
            {
                break;
            }
            pending_keyframe |= webrtc.take_keyframe_request();
            if pending_keyframe && capture.request_keyframe().is_ok() {
                pending_keyframe = false;
            }
            match capture.recv_timeout(Duration::from_millis(5)) {
                Ok(frame) => {
                    let queue_delay = frame.encoded_at.elapsed();
                    webrtc.observe_sender_queue_delay(queue_delay);
                    let actual_duration = previous_video_timestamp
                        .and_then(|previous: Duration| {
                            frame.presentation_timestamp.checked_sub(previous)
                        })
                        .filter(|duration| {
                            *duration >= Duration::from_millis(1)
                                && *duration <= Duration::from_millis(100)
                        })
                        .unwrap_or(frame_duration);
                    previous_video_timestamp = Some(frame.presentation_timestamp);
                    let send_started = Instant::now();
                    match video_sender.send_h264(frame.data, actual_duration) {
                        Ok(()) => latency.record(
                            frame.encode_duration,
                            queue_delay,
                            send_started.elapsed(),
                            frame.captured_at.elapsed(),
                        ),
                        Err(MediaSendError::NotActive) => break,
                        Err(MediaSendError::Transport(error))
                            if is_expected_media_route_disconnect(error.as_ref()) =>
                        {
                            println!("WebRTC media route disconnected for {peer}");
                            break;
                        }
                        Err(error) => return Err(error.into()),
                    }
                }
                Err(CaptureError::Timeout) => {}
                Err(error) => return Err(error.into()),
            }
            if last_bitrate_update.elapsed() >= STREAM_BITRATE_UPDATE_INTERVAL {
                let target = webrtc.target_bitrate_bits_per_second();
                let difference = target.abs_diff(applied_bitrate);
                if difference >= applied_bitrate / 10 && capture.set_target_bitrate(target).is_ok()
                {
                    applied_bitrate = target;
                    println!("WebRTC bitrate adjusted to {} kbps", target / 1_000);
                }
                last_bitrate_update = Instant::now();
            }
            latency.print_if_due(&capture, webrtc);
        }
        media_stop.store(true, Ordering::Relaxed);
        Ok(())
    });
    media_stop.store(true, Ordering::Relaxed);
    stream_result?;
    if let Some(error) = audio_failure
        .into_inner()
        .expect("audio failure lock poisoned")
    {
        return Err(error.into());
    }
    println!("WebRTC stream stopped for {peer}");
    Ok(())
}

fn run_audio_stream(
    audio: &WindowsAudioLoopback,
    sender: &MediaAudioSender,
    stop: &AtomicBool,
    cancelled: &AtomicBool,
    failure: &Mutex<Option<String>>,
) {
    while !stop.load(Ordering::Relaxed) && !cancelled.load(Ordering::Relaxed) {
        let result = match audio.recv_timeout(Duration::from_millis(5)) {
            Ok(frame) => sender.send_opus(frame.data, frame.duration),
            Err(AudioCaptureError::Timeout) => continue,
            Err(AudioCaptureError::Disconnected) => {
                *failure.lock().expect("audio failure lock poisoned") =
                    Some("Windows system audio capture stopped unexpectedly".to_owned());
                break;
            }
            Err(error) => {
                *failure.lock().expect("audio failure lock poisoned") = Some(error.to_string());
                break;
            }
        };
        match result {
            Ok(()) => {}
            Err(MediaSendError::NotActive) => break,
            Err(MediaSendError::Transport(error))
                if is_expected_media_route_disconnect(error.as_ref()) =>
            {
                break;
            }
            Err(error) => {
                *failure.lock().expect("audio failure lock poisoned") = Some(error.to_string());
                break;
            }
        }
    }
}

struct StreamLatencyMetrics {
    last_report: Instant,
    encode_samples: Vec<u64>,
    queue_samples: Vec<u64>,
    send_samples: Vec<u64>,
    end_to_send_samples: Vec<u64>,
}

impl StreamLatencyMetrics {
    fn new() -> Self {
        Self {
            last_report: Instant::now(),
            encode_samples: Vec::with_capacity(128),
            queue_samples: Vec::with_capacity(128),
            send_samples: Vec::with_capacity(128),
            end_to_send_samples: Vec::with_capacity(128),
        }
    }

    fn record(&mut self, encode: Duration, queue: Duration, send: Duration, end_to_send: Duration) {
        self.encode_samples.push(duration_micros(encode));
        self.queue_samples.push(duration_micros(queue));
        self.send_samples.push(duration_micros(send));
        self.end_to_send_samples.push(duration_micros(end_to_send));
    }

    fn print_if_due(&mut self, capture: &WindowsCapturePipeline, webrtc: &MediaWebRtcSession) {
        if self.last_report.elapsed() < STREAM_METRICS_INTERVAL {
            return;
        }
        let capture_stats = capture.stats();
        let transport = webrtc.transport_metrics();
        println!(
            "Streaming latency: encode p95 {} us | capture queue p95/max {}/{} us | RTP write p95 {} us | capture-to-send p95 {} us | dropped before encode {} | RTT {} us | jitter {} us | loss {:.1}%",
            percentile_95(&mut self.encode_samples),
            percentile_95(&mut self.queue_samples),
            capture_stats.maximum_queue_micros,
            percentile_95(&mut self.send_samples),
            percentile_95(&mut self.end_to_send_samples),
            capture_stats.dropped_before_encode,
            transport.round_trip_micros,
            transport.jitter_micros,
            f64::from(transport.fraction_lost) * 100.0 / 256.0,
        );
        self.encode_samples.clear();
        self.queue_samples.clear();
        self.send_samples.clear();
        self.end_to_send_samples.clear();
        self.last_report = Instant::now();
    }
}

fn duration_micros(duration: Duration) -> u64 {
    u64::try_from(duration.as_micros()).unwrap_or(u64::MAX)
}

fn percentile_95(samples: &mut [u64]) -> u64 {
    if samples.is_empty() {
        return 0;
    }
    samples.sort_unstable();
    samples[(samples.len() - 1) * 95 / 100]
}

fn is_expected_media_route_disconnect(error: &(dyn std::error::Error + 'static)) -> bool {
    let mut current = Some(error);
    while let Some(candidate) = current {
        if let Some(io_error) = candidate.downcast_ref::<io::Error>()
            && (matches!(
                io_error.kind(),
                io::ErrorKind::AddrNotAvailable
                    | io::ErrorKind::BrokenPipe
                    | io::ErrorKind::ConnectionAborted
                    | io::ErrorKind::ConnectionReset
                    | io::ErrorKind::NotConnected
            ) || matches!(io_error.raw_os_error(), Some(10049 | 10053 | 10054 | 10058)))
        {
            return true;
        }
        current = candidate.source();
    }
    false
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
    shared: Arc<Mutex<GamepadWatchdogState>>,
    stop: Arc<AtomicBool>,
    watchdog: Option<thread::JoinHandle<()>>,
}

struct GamepadWatchdogState {
    device: Box<dyn VirtualGamepadDevice>,
    last_update: Instant,
    neutral: bool,
}

struct ConnectionLease<'a> {
    state: &'a ServerState,
    connection_id: usize,
    plane: ConnectionPlane,
}

struct ActiveMediaSession {
    connection_id: usize,
    cancel: Arc<AtomicBool>,
}

struct MediaSessionLease {
    state: Arc<ServerState>,
    connection_id: usize,
    cancel: Arc<AtomicBool>,
}

impl MediaSessionLease {
    fn replace(state: &Arc<ServerState>, connection_id: usize) -> Result<Self, AnyError> {
        let cancel = Arc::new(AtomicBool::new(false));
        let mut active = lock(&state.active_media)?;
        if let Some(previous) = active.replace(ActiveMediaSession {
            connection_id,
            cancel: Arc::clone(&cancel),
        }) {
            previous.cancel.store(true, Ordering::Relaxed);
        }
        drop(active);
        Ok(Self {
            state: Arc::clone(state),
            connection_id,
            cancel,
        })
    }

    fn cancel_token(&self) -> &AtomicBool {
        &self.cancel
    }
}

impl Drop for MediaSessionLease {
    fn drop(&mut self) {
        if let Ok(mut active) = self.state.active_media.lock()
            && active
                .as_ref()
                .is_some_and(|session| session.connection_id == self.connection_id)
        {
            active.take();
        }
    }
}

impl<'a> ConnectionLease<'a> {
    fn new(state: &'a ServerState, connection_id: usize, plane: ConnectionPlane) -> Self {
        state.connected_clients.fetch_add(1, Ordering::Relaxed);
        Self {
            state,
            connection_id,
            plane,
        }
    }
}

impl Drop for ConnectionLease<'_> {
    fn drop(&mut self) {
        if let Ok(mut sockets) = self.state.active_sockets.lock() {
            sockets.remove(&self.connection_id);
        }
        let previous = self.state.connected_clients.fetch_sub(1, Ordering::Relaxed);
        println!(
            "{:?} client disconnected ({} connection(s) remaining)",
            self.plane,
            previous.saturating_sub(1),
        );
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
    fn new(device: Box<dyn VirtualGamepadDevice>) -> Result<Self, AnyError> {
        Self::new_with_watchdog(
            device,
            GAMEPAD_WATCHDOG_TIMEOUT,
            GAMEPAD_WATCHDOG_POLL_INTERVAL,
        )
    }

    fn new_with_watchdog(
        mut device: Box<dyn VirtualGamepadDevice>,
        timeout: Duration,
        poll_interval: Duration,
    ) -> Result<Self, AnyError> {
        device.neutralize()?;
        let shared = Arc::new(Mutex::new(GamepadWatchdogState {
            device,
            last_update: Instant::now(),
            neutral: true,
        }));
        let stop = Arc::new(AtomicBool::new(false));
        let worker_shared = Arc::clone(&shared);
        let worker_stop = Arc::clone(&stop);
        let watchdog = thread::spawn(move || {
            while !worker_stop.load(Ordering::Relaxed) {
                thread::sleep(poll_interval);
                let Ok(mut current) = worker_shared.lock() else {
                    return;
                };
                if !current.neutral && current.last_update.elapsed() >= timeout {
                    match current.device.neutralize() {
                        Ok(()) => current.neutral = true,
                        Err(error) => {
                            eprintln!("Could not neutralize stale gamepad state: {error}")
                        }
                    }
                }
            }
        });
        Ok(Self {
            shared,
            stop,
            watchdog: Some(watchdog),
        })
    }

    fn update(&mut self, report: GamepadReport) -> Result<(), AnyError> {
        let mut current = lock(&self.shared)?;
        current.device.update(report)?;
        current.last_update = Instant::now();
        current.neutral = report == GamepadReport::default();
        Ok(())
    }
}

impl Drop for GamepadLease {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(watchdog) = self.watchdog.take() {
            let _ = watchdog.join();
        }
        if let Ok(mut current) = self.shared.lock()
            && let Err(error) = current.device.shutdown()
        {
            eprintln!("Could not safely stop virtual gamepad: {error}");
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
    use bridgepad_virtual_device::VirtualDeviceError;

    struct RecordingGamepad {
        reports: Arc<Mutex<Vec<GamepadReport>>>,
    }

    impl VirtualGamepadDevice for RecordingGamepad {
        fn update(&mut self, report: GamepadReport) -> Result<(), VirtualDeviceError> {
            self.reports.lock().expect("report lock").push(report);
            Ok(())
        }
    }

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

    #[test]
    fn removed_windows_route_is_a_normal_media_disconnect() {
        let removed_route = io::Error::from_raw_os_error(10049);
        assert!(is_expected_media_route_disconnect(&removed_route));
        assert!(!is_expected_media_route_disconnect(&io::Error::from(
            io::ErrorKind::PermissionDenied,
        )));
    }

    #[test]
    fn watchdog_neutralizes_a_stale_gamepad_snapshot() {
        let reports = Arc::new(Mutex::new(Vec::new()));
        let mut lease = GamepadLease::new_with_watchdog(
            Box::new(RecordingGamepad {
                reports: Arc::clone(&reports),
            }),
            Duration::from_millis(20),
            Duration::from_millis(2),
        )
        .expect("watchdog lease");
        lease
            .update(GamepadReport {
                left_x: i16::MAX,
                ..GamepadReport::default()
            })
            .expect("non-neutral update");

        let deadline = Instant::now() + Duration::from_millis(250);
        loop {
            let neutralized = reports
                .lock()
                .expect("report lock")
                .last()
                .is_some_and(|report| *report == GamepadReport::default());
            if neutralized {
                break;
            }
            assert!(Instant::now() < deadline, "watchdog did not neutralize");
            thread::sleep(Duration::from_millis(2));
        }
    }
}
