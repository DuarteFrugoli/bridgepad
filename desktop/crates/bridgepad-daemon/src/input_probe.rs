//! Paired, transport-only TCP/TLS versus UDP+AEAD input echo diagnostic.

use super::AnyError;
use bridgepad_protocol::{MessageType, PacketHeader, decode_packet, encode_packet};
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use ring::rand::{SecureRandom, SystemRandom};
use std::io::ErrorKind;
use std::net::{IpAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;

const MAGIC: [u8; 4] = *b"BPI1";
const HEADER_SIZE: usize = 16;
const TAG_SIZE: usize = 16;

pub(super) struct InputProbeSession {
    stop: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
}

impl InputProbeSession {
    pub(super) fn start(
        session_id: u64,
        peer_ip: IpAddr,
    ) -> Result<(Self, u16, [u8; 32], [u8; 32]), AnyError> {
        let bind = if peer_ip.is_ipv4() {
            "0.0.0.0:0"
        } else {
            "[::]:0"
        };
        let socket = UdpSocket::bind(bind)?;
        socket.set_read_timeout(Some(Duration::from_millis(50)))?;
        let port = socket.local_addr()?.port();
        let mut uplink_key = [0_u8; 32];
        let mut downlink_key = [0_u8; 32];
        SystemRandom::new()
            .fill(&mut uplink_key)
            .map_err(|_| "could not generate probe uplink key")?;
        SystemRandom::new()
            .fill(&mut downlink_key)
            .map_err(|_| "could not generate probe downlink key")?;
        let uplink = LessSafeKey::new(
            UnboundKey::new(&AES_256_GCM, &uplink_key).map_err(|_| "invalid probe uplink key")?,
        );
        let downlink = LessSafeKey::new(
            UnboundKey::new(&AES_256_GCM, &downlink_key)
                .map_err(|_| "invalid probe downlink key")?,
        );
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let worker = thread::spawn(move || {
            if let Err(error) = run(socket, session_id, peer_ip, uplink, downlink, &worker_stop) {
                eprintln!("Input transport UDP probe ended: {error}");
            }
        });
        Ok((
            Self {
                stop,
                worker: Some(worker),
            },
            port,
            uplink_key,
            downlink_key,
        ))
    }
}

impl Drop for InputProbeSession {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

fn run(
    socket: UdpSocket,
    session_id: u64,
    peer_ip: IpAddr,
    uplink: LessSafeKey,
    downlink: LessSafeKey,
    stop: &AtomicBool,
) -> Result<(), AnyError> {
    let mut buffer = [0_u8; 1_200];
    let mut latest_counter = None;
    let mut peer_port = None;
    let mut send_counter = 0_u64;
    while !stop.load(Ordering::Acquire) {
        let (length, source) = match socket.recv_from(&mut buffer) {
            Ok(result) => result,
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                continue;
            }
            Err(error) => return Err(error.into()),
        };
        if source.ip() != peer_ip
            || peer_port.is_some_and(|port| port != source.port())
            || length < HEADER_SIZE + TAG_SIZE
            || buffer[..4] != MAGIC
            || buffer[12..16] != [0; 4]
        {
            continue;
        }
        let counter = u64::from_be_bytes(buffer[4..12].try_into()?);
        if latest_counter.is_some_and(|previous| counter <= previous) {
            continue;
        }
        let mut nonce = [0_u8; 12];
        nonce[4..].copy_from_slice(&counter.to_be_bytes());
        let (header, encrypted) = buffer[..length].split_at_mut(HEADER_SIZE);
        let Ok(plaintext) = uplink.open_in_place(
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
            || packet.header.message_type != MessageType::Ping
            || packet.payload.len() != 8
            || u64::from(packet.header.sequence) != (counter & 0xffff_ffff)
        {
            continue;
        }
        latest_counter = Some(counter);
        if peer_port.is_none() {
            peer_port = Some(source.port());
            println!("Input transport UDP probe authenticated from {source}");
        }
        let pong = encode_packet(
            PacketHeader {
                session_id,
                sequence: packet.header.sequence,
                timestamp_micros: packet.header.timestamp_micros,
                message_type: MessageType::Pong,
            },
            packet.payload,
        )?;
        let mut response_header = Vec::with_capacity(HEADER_SIZE);
        response_header.extend_from_slice(&MAGIC);
        response_header.extend_from_slice(&send_counter.to_be_bytes());
        response_header.extend_from_slice(&[0; 4]);
        let mut response_nonce = [0_u8; 12];
        response_nonce[4..].copy_from_slice(&send_counter.to_be_bytes());
        let mut sealed = pong;
        downlink
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key(response_nonce),
                Aad::from(&response_header[..]),
                &mut sealed,
            )
            .map_err(|_| "could not encrypt UDP probe reply")?;
        response_header.extend_from_slice(&sealed);
        socket.send_to(&response_header, source)?;
        send_counter += 1;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use bridgepad_protocol::decode_ping;

    #[test]
    fn authenticated_probe_echoes_ping_and_rejects_replay() {
        let peer_ip: IpAddr = "127.0.0.1".parse().expect("loopback");
        let (session, port, uplink_key, downlink_key) =
            InputProbeSession::start(7, peer_ip).expect("probe start");
        let client = UdpSocket::bind("127.0.0.1:0").expect("client socket");
        client
            .set_read_timeout(Some(Duration::from_millis(300)))
            .expect("timeout");
        let uplink = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &uplink_key).expect("key"));
        let downlink = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &downlink_key).expect("key"));
        let ping = encode_packet(
            PacketHeader {
                session_id: 7,
                sequence: 0,
                timestamp_micros: 1,
                message_type: MessageType::Ping,
            },
            &123_u64.to_be_bytes(),
        )
        .expect("encode");
        let mut header = Vec::from(MAGIC);
        header.extend_from_slice(&0_u64.to_be_bytes());
        header.extend_from_slice(&[0; 4]);
        let mut payload = ping;
        uplink
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key([0; 12]),
                Aad::from(&header[..]),
                &mut payload,
            )
            .expect("seal");
        header.extend_from_slice(&payload);
        client.send_to(&header, (peer_ip, port)).expect("send");
        let mut reply = [0_u8; 1_200];
        let (length, _) = client.recv_from(&mut reply).expect("echo");
        let mut ciphertext = reply[HEADER_SIZE..length].to_vec();
        let plaintext = downlink
            .open_in_place(
                Nonce::assume_unique_for_key([0; 12]),
                Aad::from(&reply[..HEADER_SIZE]),
                &mut ciphertext,
            )
            .expect("open");
        let decoded = decode_packet(plaintext).expect("packet");
        assert_eq!(decoded.header.message_type, MessageType::Pong);
        assert_eq!(decode_ping(decoded).expect("nonce"), 123);
        client.send_to(&header, (peer_ip, port)).expect("replay");
        assert!(
            client.recv_from(&mut reply).is_err(),
            "replayed ping must not be echoed"
        );
        drop(session);
    }
}
