//! Authenticated envelope used only by the raw UDP transport candidate.
//!
//! QUIC already authenticates and encrypts its datagrams and must not add this
//! envelope. Keeping this layer beside the wire codec makes the two bake-off
//! candidates consume the same validated `BridgePad` Media v1 datagram.

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce, Tag};
use std::fmt;

use crate::{MAX_DATAGRAM_SIZE, ProtocolError, decode_datagram};

pub const MAGIC: [u8; 4] = *b"BPA1";
pub const MAJOR_VERSION: u8 = 1;
pub const MINOR_VERSION: u8 = 0;
pub const HEADER_SIZE: usize = 20;
pub const TAG_SIZE: usize = 16;
pub const MAX_SEALED_DATAGRAM_SIZE: usize = MAX_DATAGRAM_SIZE;
pub const MAX_PLAINTEXT_SIZE: usize = MAX_SEALED_DATAGRAM_SIZE - HEADER_SIZE - TAG_SIZE;
pub const REPLAY_WINDOW_SIZE: usize = 256;
pub const MAX_PACKETS_PER_KEY: u64 = 4_294_967_295;

const HEADER_SIZE_WIRE: u8 = 20;
const KEY_SIZE: usize = 32;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SecurityError {
    InvalidKeyEpoch,
    InvalidKeyLength(usize),
    PacketCounterExhausted,
    DatagramTooShort(usize),
    DatagramTooLarge(usize),
    PlaintextTooLarge(usize),
    InvalidMagic,
    UnsupportedVersion { major: u8, minor: u8 },
    InvalidHeaderSize(u8),
    UnknownFlags(u8),
    InvalidPacketCounter(u64),
    UnexpectedKeyEpoch { expected: u32, actual: u32 },
    ReplayRejected(u64),
    AuthenticationFailed,
    InvalidMediaDatagram(ProtocolError),
}

impl fmt::Display for SecurityError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for SecurityError {}

impl From<ProtocolError> for SecurityError {
    fn from(value: ProtocolError) -> Self {
        Self::InvalidMediaDatagram(value)
    }
}

/// Stateful sender that guarantees one unique nonce for each traffic key.
pub struct UdpAeadSender {
    cipher: Aes256Gcm,
    key_epoch: u32,
    next_packet_counter: u64,
}

impl UdpAeadSender {
    /// Creates a sender at packet counter zero.
    ///
    /// # Errors
    ///
    /// Rejects an invalid key size or the reserved key epoch zero.
    pub fn new(key: &[u8], key_epoch: u32) -> Result<Self, SecurityError> {
        if key.len() != KEY_SIZE {
            return Err(SecurityError::InvalidKeyLength(key.len()));
        }
        if key_epoch == 0 {
            return Err(SecurityError::InvalidKeyEpoch);
        }
        Ok(Self {
            cipher: Aes256Gcm::new_from_slice(key)
                .map_err(|_| SecurityError::InvalidKeyLength(key.len()))?,
            key_epoch,
            next_packet_counter: 0,
        })
    }

    /// Authenticates and encrypts one already encoded Media v1 datagram.
    ///
    /// # Errors
    ///
    /// Rejects malformed media, an oversized sealed packet, or an exhausted
    /// packet counter. The counter advances only after encryption succeeds.
    pub fn seal(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, SecurityError> {
        decode_datagram(plaintext)?;
        if plaintext.len() > MAX_PLAINTEXT_SIZE {
            return Err(SecurityError::PlaintextTooLarge(plaintext.len()));
        }
        if self.next_packet_counter > MAX_PACKETS_PER_KEY {
            return Err(SecurityError::PacketCounterExhausted);
        }

        let counter = self.next_packet_counter;
        let header = encode_header(self.key_epoch, counter);
        let nonce_bytes = nonce(self.key_epoch, counter);
        let mut ciphertext = plaintext.to_vec();
        let tag = self
            .cipher
            .encrypt_in_place_detached(Nonce::from_slice(&nonce_bytes), &header, &mut ciphertext)
            .map_err(|_| SecurityError::AuthenticationFailed)?;

        let mut sealed = Vec::with_capacity(HEADER_SIZE + ciphertext.len() + TAG_SIZE);
        sealed.extend_from_slice(&header);
        sealed.extend_from_slice(&ciphertext);
        sealed.extend_from_slice(tag.as_slice());
        self.next_packet_counter = counter + 1;
        Ok(sealed)
    }
}

/// Stateful receiver with a bounded sliding replay window.
pub struct UdpAeadReceiver {
    cipher: Aes256Gcm,
    key_epoch: u32,
    replay: ReplayWindow,
}

impl UdpAeadReceiver {
    /// Creates a receiver for one directional traffic key and key epoch.
    ///
    /// # Errors
    ///
    /// Rejects an invalid key size or the reserved key epoch zero.
    pub fn new(key: &[u8], key_epoch: u32) -> Result<Self, SecurityError> {
        if key.len() != KEY_SIZE {
            return Err(SecurityError::InvalidKeyLength(key.len()));
        }
        if key_epoch == 0 {
            return Err(SecurityError::InvalidKeyEpoch);
        }
        Ok(Self {
            cipher: Aes256Gcm::new_from_slice(key)
                .map_err(|_| SecurityError::InvalidKeyLength(key.len()))?,
            key_epoch,
            replay: ReplayWindow::default(),
        })
    }

    /// Authenticates, replay-checks and decodes one sealed UDP payload.
    ///
    /// The replay window is updated only after the authentication tag and the
    /// enclosed Media v1 datagram are both valid.
    ///
    /// # Errors
    ///
    /// Rejects malformed envelopes, another key epoch, replayed packets,
    /// invalid authentication tags, and malformed enclosed media.
    pub fn open(&mut self, sealed: &[u8]) -> Result<Vec<u8>, SecurityError> {
        let (epoch, counter) = decode_header(sealed)?;
        if epoch != self.key_epoch {
            return Err(SecurityError::UnexpectedKeyEpoch {
                expected: self.key_epoch,
                actual: epoch,
            });
        }
        if !self.replay.may_accept(counter) {
            return Err(SecurityError::ReplayRejected(counter));
        }

        let ciphertext_end = sealed.len() - TAG_SIZE;
        let mut plaintext = sealed[HEADER_SIZE..ciphertext_end].to_vec();
        let tag = Tag::from_slice(&sealed[ciphertext_end..]);
        let nonce_bytes = nonce(epoch, counter);
        self.cipher
            .decrypt_in_place_detached(
                Nonce::from_slice(&nonce_bytes),
                &sealed[..HEADER_SIZE],
                &mut plaintext,
                tag,
            )
            .map_err(|_| SecurityError::AuthenticationFailed)?;
        decode_datagram(&plaintext)?;
        self.replay.accept(counter);
        Ok(plaintext)
    }
}

#[derive(Default)]
struct ReplayWindow {
    highest: Option<u64>,
    words: [u64; REPLAY_WINDOW_SIZE / 64],
}

impl ReplayWindow {
    fn may_accept(&self, counter: u64) -> bool {
        let Some(highest) = self.highest else {
            return true;
        };
        if counter > highest {
            return true;
        }
        let distance = highest - counter;
        if distance >= REPLAY_WINDOW_SIZE as u64 {
            return false;
        }
        !self.is_set(usize::try_from(distance).expect("distance is bounded"))
    }

    fn accept(&mut self, counter: u64) {
        let Some(highest) = self.highest else {
            self.highest = Some(counter);
            self.words[0] = 1;
            return;
        };
        if counter > highest {
            let distance = usize::try_from(counter - highest).unwrap_or(usize::MAX);
            self.shift(distance);
            self.highest = Some(counter);
            self.words[0] |= 1;
        } else {
            self.set(usize::try_from(highest - counter).expect("counter was prechecked"));
        }
    }

    fn is_set(&self, distance: usize) -> bool {
        self.words[distance / 64] & (1_u64 << (distance % 64)) != 0
    }

    fn set(&mut self, distance: usize) {
        self.words[distance / 64] |= 1_u64 << (distance % 64);
    }

    fn shift(&mut self, distance: usize) {
        if distance >= REPLAY_WINDOW_SIZE {
            self.words.fill(0);
            return;
        }
        let word_shift = distance / 64;
        let bit_shift = distance % 64;
        let old = self.words;
        self.words.fill(0);
        for destination in (word_shift..self.words.len()).rev() {
            let source = destination - word_shift;
            self.words[destination] |= old[source] << bit_shift;
            if bit_shift != 0 && source > 0 {
                self.words[destination] |= old[source - 1] >> (64 - bit_shift);
            }
        }
    }
}

fn encode_header(key_epoch: u32, packet_counter: u64) -> [u8; HEADER_SIZE] {
    let mut header = [0_u8; HEADER_SIZE];
    header[..4].copy_from_slice(&MAGIC);
    header[4] = MAJOR_VERSION;
    header[5] = MINOR_VERSION;
    header[6] = HEADER_SIZE_WIRE;
    header[7] = 0;
    header[8..12].copy_from_slice(&key_epoch.to_be_bytes());
    header[12..20].copy_from_slice(&packet_counter.to_be_bytes());
    header
}

fn decode_header(sealed: &[u8]) -> Result<(u32, u64), SecurityError> {
    let minimum = HEADER_SIZE + TAG_SIZE + crate::HEADER_SIZE + 1;
    if sealed.len() < minimum {
        return Err(SecurityError::DatagramTooShort(sealed.len()));
    }
    if sealed.len() > MAX_SEALED_DATAGRAM_SIZE {
        return Err(SecurityError::DatagramTooLarge(sealed.len()));
    }
    if sealed[..4] != MAGIC {
        return Err(SecurityError::InvalidMagic);
    }
    if sealed[4] != MAJOR_VERSION || sealed[5] != MINOR_VERSION {
        return Err(SecurityError::UnsupportedVersion {
            major: sealed[4],
            minor: sealed[5],
        });
    }
    if sealed[6] != HEADER_SIZE_WIRE {
        return Err(SecurityError::InvalidHeaderSize(sealed[6]));
    }
    if sealed[7] != 0 {
        return Err(SecurityError::UnknownFlags(sealed[7]));
    }
    let packet_counter = read_u64(sealed, 12);
    if packet_counter > MAX_PACKETS_PER_KEY {
        return Err(SecurityError::InvalidPacketCounter(packet_counter));
    }
    Ok((read_u32(sealed, 8), packet_counter))
}

fn nonce(key_epoch: u32, packet_counter: u64) -> [u8; 12] {
    let mut nonce = [0_u8; 12];
    nonce[..4].copy_from_slice(&key_epoch.to_be_bytes());
    nonce[4..].copy_from_slice(&packet_counter.to_be_bytes());
    nonce
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("validated bounds"),
    )
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_be_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("validated bounds"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{MediaDatagramHeader, PacketFlags, PacketKind, encode_datagram};

    const KEY: [u8; 32] = [
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
        0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d,
        0x1e, 0x1f,
    ];

    #[test]
    fn sealed_datagram_round_trips_and_replay_is_rejected() {
        let plaintext = sample_datagram();
        let mut sender = UdpAeadSender::new(&KEY, 0x0102_0304).unwrap();
        let sealed = sender.seal(&plaintext).unwrap();
        let mut receiver = UdpAeadReceiver::new(&KEY, 0x0102_0304).unwrap();
        assert_eq!(receiver.open(&sealed).unwrap(), plaintext);
        assert_eq!(
            receiver.open(&sealed),
            Err(SecurityError::ReplayRejected(0))
        );
    }

    #[test]
    fn tampering_does_not_advance_replay_window() {
        let plaintext = sample_datagram();
        let mut sender = UdpAeadSender::new(&KEY, 7).unwrap();
        let sealed = sender.seal(&plaintext).unwrap();
        let mut tampered = sealed.clone();
        tampered[HEADER_SIZE + 3] ^= 1;
        let mut receiver = UdpAeadReceiver::new(&KEY, 7).unwrap();
        assert_eq!(
            receiver.open(&tampered),
            Err(SecurityError::AuthenticationFailed)
        );
        assert_eq!(receiver.open(&sealed).unwrap(), plaintext);
    }

    #[test]
    fn out_of_order_packets_are_accepted_once_inside_window() {
        let plaintext = sample_datagram();
        let mut sender = UdpAeadSender::new(&KEY, 9).unwrap();
        let packets: Vec<_> = (0..300).map(|_| sender.seal(&plaintext).unwrap()).collect();
        let mut receiver = UdpAeadReceiver::new(&KEY, 9).unwrap();
        receiver.open(&packets[299]).unwrap();
        receiver.open(&packets[44]).unwrap();
        assert_eq!(
            receiver.open(&packets[43]),
            Err(SecurityError::ReplayRejected(43))
        );
        assert_eq!(
            receiver.open(&packets[44]),
            Err(SecurityError::ReplayRejected(44))
        );
    }

    #[test]
    fn sealed_datagram_matches_cross_language_golden_vector() {
        let mut sender = UdpAeadSender::new(&KEY, 0x0102_0304).unwrap();
        let sealed = sender.seal(&sample_datagram()).unwrap();
        let expected = decode_hex(
            "42504131010014000102030400000000000000006305906c16917949fbe297bb\
             b1c2b46af5195df9c1b31a4769d31a7b53a0f72da0958ce0a37a50bce77d\
             de3313c2f96f7355f0c2514f12fb33dd68c42e7fbe76262e4bcd87e2085a1\
             1c1",
        );
        assert_eq!(sealed, expected);
    }

    fn sample_datagram() -> Vec<u8> {
        encode_datagram(
            MediaDatagramHeader {
                kind: PacketKind::Video,
                flags: PacketFlags::KEYFRAME | PacketFlags::FRAME_START,
                session_id: 0x0102_0304_0506_0708,
                stream_id: 0x1112_1314,
                sequence: 0x2122_2324,
                frame_id: 0x3132_3334,
                presentation_timestamp_micros: 0x4142_4344_4546_4748,
                frame_packet_index: 0,
                frame_packet_count: 2,
                original_frame_bytes: 4,
                fec_block_index: 0,
                fec_shard_index: 0,
                fec_source_count: 0,
                fec_repair_count: 0,
            },
            &[0xaa, 0xbb],
        )
        .unwrap()
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        let compact: String = value
            .chars()
            .filter(|character| !character.is_whitespace())
            .collect();
        (0..compact.len())
            .step_by(2)
            .map(|offset| u8::from_str_radix(&compact[offset..offset + 2], 16).unwrap())
            .collect()
    }
}
