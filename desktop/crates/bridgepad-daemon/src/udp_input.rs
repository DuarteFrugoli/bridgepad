//! Experimental gamepad-only UDP input. TLS owns authentication and lifetime;
//! each control session receives a fresh one-direction AEAD key and UDP socket.

use super::{AnyError, GamepadLease, to_gamepad_report};
use bridgepad_protocol::{MessageType, decode_gamepad_snapshot, decode_packet};
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use ring::rand::{SecureRandom, SystemRandom};
use std::io::ErrorKind;
use std::net::{IpAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::{self, JoinHandle};
use std::time::Duration;

const MAGIC: [u8; 4] = *b"BPI1";
const HEADER_SIZE: usize = 16;
const TAG_SIZE: usize = 16;

pub(super) struct UdpInputSession {
    stop: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
    first_packet: Receiver<()>,
}

impl UdpInputSession {
    pub(super) fn start(
        gamepad: GamepadLease,
        session_id: u64,
        peer_ip: IpAddr,
    ) -> Result<(Self, u16, [u8; 32]), AnyError> {
        let bind = if peer_ip.is_ipv4() {
            "0.0.0.0:0"
        } else {
            "[::]:0"
        };
        let socket = UdpSocket::bind(bind)?;
        socket.set_read_timeout(Some(Duration::from_millis(50)))?;
        let port = socket.local_addr()?.port();
        let mut key = [0_u8; 32];
        SystemRandom::new()
            .fill(&mut key)
            .map_err(|_| "could not generate UDP input key")?;
        let cipher = LessSafeKey::new(
            UnboundKey::new(&AES_256_GCM, &key).map_err(|_| "invalid UDP input key")?,
        );
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let (first_packet_sender, first_packet) = mpsc::channel();
        let worker = thread::spawn(move || {
            if let Err(error) = receive(
                socket,
                cipher,
                gamepad,
                session_id,
                peer_ip,
                &worker_stop,
                first_packet_sender,
            ) {
                eprintln!("UDP input receiver ended: {error}");
            }
        });
        Ok((
            Self {
                stop,
                worker: Some(worker),
                first_packet,
            },
            port,
            key,
        ))
    }

    pub(super) fn wait_for_first_packet(&self) -> Result<(), AnyError> {
        self.first_packet
            .recv_timeout(Duration::from_millis(1_500))
            .map_err(|_| "UDP gamepad route did not deliver an authenticated packet".into())
    }
}

impl Drop for UdpInputSession {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

fn receive(
    socket: UdpSocket,
    cipher: LessSafeKey,
    mut gamepad: GamepadLease,
    session_id: u64,
    peer_ip: IpAddr,
    stop: &AtomicBool,
    first_packet: Sender<()>,
) -> Result<(), AnyError> {
    let mut buffer = [0_u8; 1_200];
    let mut latest_counter = None;
    let mut peer_port = None;
    let mut received = 0_u64;
    while !stop.load(Ordering::Acquire) {
        let (length, source) = match socket.recv_from(&mut buffer) {
            Ok(result) => result,
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                continue;
            }
            Err(error) => return Err(error.into()),
        };
        if source.ip() != peer_ip || peer_port.is_some_and(|port| port != source.port()) {
            continue;
        }
        if length < HEADER_SIZE + TAG_SIZE || buffer[..4] != MAGIC {
            continue;
        }
        let counter = u64::from_be_bytes(buffer[4..12].try_into()?);
        if latest_counter.is_some_and(|previous| counter <= previous) {
            continue;
        }
        if buffer[12..16] != [0; 4] {
            continue;
        }
        let mut nonce = [0_u8; 12];
        nonce[4..].copy_from_slice(&counter.to_be_bytes());
        let (header, encrypted) = buffer[..length].split_at_mut(HEADER_SIZE);
        let Ok(plaintext) = cipher.open_in_place(
            Nonce::assume_unique_for_key(nonce),
            Aad::from(&header[..]),
            encrypted,
        ) else {
            continue;
        };
        let Ok(packet) = decode_packet(plaintext) else {
            continue;
        };
        if packet.header.session_id != session_id
            || packet.header.message_type != MessageType::GamepadSnapshot
            || u64::from(packet.header.sequence) != (counter & 0xffff_ffff)
        {
            continue;
        }
        let Ok(snapshot) = decode_gamepad_snapshot(packet) else {
            continue;
        };
        let report = to_gamepad_report(snapshot)?;
        gamepad.update(report)?;
        latest_counter = Some(counter);
        peer_port.get_or_insert(source.port());
        received += 1;
        if received == 1 {
            let _ = first_packet.send(());
            println!("UDP gamepad input authenticated from {source}");
        }
    }
    Ok(())
}
