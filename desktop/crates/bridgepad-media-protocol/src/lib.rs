//! Transport-independent wire types for `BridgePad` Media v1 datagrams.
//!
//! This crate deliberately contains no socket, codec, capture, or UI code. It
//! is shared by every candidate transport in the transport bake-off.

use std::fmt;

pub mod control;

pub const MAGIC: [u8; 4] = *b"BPM1";
pub const MAJOR_VERSION: u8 = 1;
pub const MINOR_VERSION: u8 = 0;
pub const HEADER_SIZE: usize = 56;
const HEADER_SIZE_WIRE: u8 = 56;
pub const MAX_DATAGRAM_SIZE: usize = 1_200;
pub const MAX_DATAGRAM_SIZE_WIRE: u16 = 1_200;
pub const MAX_PAYLOAD_SIZE: usize = MAX_DATAGRAM_SIZE - HEADER_SIZE;
pub const MAX_FRAME_BYTES: u32 = 16 * 1_024 * 1_024;
pub const REPAIR_PACKET_INDEX: u16 = u16::MAX;
pub const MAX_SIGNED_WIRE_VALUE: u64 = i64::MAX as u64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum PacketKind {
    Video = 1,
    Audio = 2,
}

impl TryFrom<u8> for PacketKind {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Video),
            2 => Ok(Self::Audio),
            _ => Err(ProtocolError::UnknownPacketKind(value)),
        }
    }
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
pub struct PacketFlags(u16);

impl PacketFlags {
    pub const NONE: Self = Self(0);
    pub const KEYFRAME: Self = Self(1 << 0);
    pub const CONFIG: Self = Self(1 << 1);
    pub const FRAME_START: Self = Self(1 << 2);
    pub const FRAME_END: Self = Self(1 << 3);
    pub const FEC_REPAIR: Self = Self(1 << 4);
    pub const DISCONTINUITY: Self = Self(1 << 5);
    const KNOWN_BITS: u16 = Self::KEYFRAME.0
        | Self::CONFIG.0
        | Self::FRAME_START.0
        | Self::FRAME_END.0
        | Self::FEC_REPAIR.0
        | Self::DISCONTINUITY.0;

    #[must_use]
    pub const fn from_bits(bits: u16) -> Option<Self> {
        if bits & !Self::KNOWN_BITS == 0 {
            Some(Self(bits))
        } else {
            None
        }
    }

    #[must_use]
    pub const fn bits(self) -> u16 {
        self.0
    }

    #[must_use]
    pub const fn contains(self, other: Self) -> bool {
        self.0 & other.0 == other.0
    }
}

impl std::ops::BitOr for PacketFlags {
    type Output = Self;

    fn bitor(self, rhs: Self) -> Self::Output {
        Self(self.0 | rhs.0)
    }
}

impl fmt::Debug for PacketFlags {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "PacketFlags({:#06x})", self.0)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MediaDatagramHeader {
    pub kind: PacketKind,
    pub flags: PacketFlags,
    pub session_id: u64,
    pub stream_id: u32,
    pub sequence: u32,
    pub frame_id: u32,
    pub presentation_timestamp_micros: u64,
    /// Index among source packets. Repair packets use [`REPAIR_PACKET_INDEX`].
    pub frame_packet_index: u16,
    /// Number of source packets required to reconstruct the encoded unit.
    pub frame_packet_count: u16,
    pub original_frame_bytes: u32,
    /// Zero when FEC is disabled for this packet.
    pub fec_block_index: u16,
    /// Position inside the FEC block, including repair shards.
    pub fec_shard_index: u16,
    /// Zero when FEC is disabled for this packet.
    pub fec_source_count: u8,
    /// Zero when FEC is disabled for this packet.
    pub fec_repair_count: u8,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MediaDatagram<'a> {
    pub header: MediaDatagramHeader,
    pub payload: &'a [u8],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ProtocolError {
    DatagramTooShort { actual: usize },
    DatagramTooLarge { actual: usize },
    InvalidMagic,
    UnsupportedVersion { major: u8, minor: u8 },
    InvalidHeaderSize(u8),
    UnknownPacketKind(u8),
    UnknownFlags(u16),
    ReservedFieldNotZero,
    EmptyPayload,
    InvalidSessionId,
    InvalidStreamId,
    InvalidTimestamp,
    InvalidFramePacketCount,
    InvalidFramePacketIndex,
    InvalidFrameSize(u32),
    InvalidBoundaryFlags,
    InvalidFecMetadata,
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for ProtocolError {}

/// Encodes one validated media datagram.
///
/// # Errors
///
/// Returns [`ProtocolError`] when header metadata is inconsistent, the payload
/// is empty, or the resulting datagram exceeds the v1 size limit.
pub fn encode_datagram(
    header: MediaDatagramHeader,
    payload: &[u8],
) -> Result<Vec<u8>, ProtocolError> {
    validate(header, payload.len())?;
    let mut bytes = Vec::with_capacity(HEADER_SIZE + payload.len());
    bytes.extend_from_slice(&MAGIC);
    bytes.push(MAJOR_VERSION);
    bytes.push(MINOR_VERSION);
    bytes.push(header.kind as u8);
    bytes.push(HEADER_SIZE_WIRE);
    bytes.extend_from_slice(&header.flags.bits().to_be_bytes());
    bytes.extend_from_slice(&0_u16.to_be_bytes());
    bytes.extend_from_slice(&header.session_id.to_be_bytes());
    bytes.extend_from_slice(&header.stream_id.to_be_bytes());
    bytes.extend_from_slice(&header.sequence.to_be_bytes());
    bytes.extend_from_slice(&header.frame_id.to_be_bytes());
    bytes.extend_from_slice(&header.presentation_timestamp_micros.to_be_bytes());
    bytes.extend_from_slice(&header.frame_packet_index.to_be_bytes());
    bytes.extend_from_slice(&header.frame_packet_count.to_be_bytes());
    bytes.extend_from_slice(&header.original_frame_bytes.to_be_bytes());
    bytes.extend_from_slice(&header.fec_block_index.to_be_bytes());
    bytes.extend_from_slice(&header.fec_shard_index.to_be_bytes());
    bytes.push(header.fec_source_count);
    bytes.push(header.fec_repair_count);
    bytes.extend_from_slice(&0_u16.to_be_bytes());
    debug_assert_eq!(bytes.len(), HEADER_SIZE);
    bytes.extend_from_slice(payload);
    Ok(bytes)
}

/// Decodes and validates one complete media datagram.
///
/// # Errors
///
/// Returns [`ProtocolError`] for malformed, unsupported, oversized, or
/// semantically inconsistent datagrams.
pub fn decode_datagram(bytes: &[u8]) -> Result<MediaDatagram<'_>, ProtocolError> {
    if bytes.len() < HEADER_SIZE {
        return Err(ProtocolError::DatagramTooShort {
            actual: bytes.len(),
        });
    }
    if bytes.len() > MAX_DATAGRAM_SIZE {
        return Err(ProtocolError::DatagramTooLarge {
            actual: bytes.len(),
        });
    }
    if bytes[..4] != MAGIC {
        return Err(ProtocolError::InvalidMagic);
    }
    let major = bytes[4];
    let minor = bytes[5];
    if major != MAJOR_VERSION || minor != MINOR_VERSION {
        return Err(ProtocolError::UnsupportedVersion { major, minor });
    }
    let kind = PacketKind::try_from(bytes[6])?;
    if bytes[7] != HEADER_SIZE_WIRE {
        return Err(ProtocolError::InvalidHeaderSize(bytes[7]));
    }
    let flag_bits = read_u16(bytes, 8);
    let flags = PacketFlags::from_bits(flag_bits).ok_or(ProtocolError::UnknownFlags(flag_bits))?;
    if read_u16(bytes, 10) != 0 || read_u16(bytes, 54) != 0 {
        return Err(ProtocolError::ReservedFieldNotZero);
    }
    let header = MediaDatagramHeader {
        kind,
        flags,
        session_id: read_u64(bytes, 12),
        stream_id: read_u32(bytes, 20),
        sequence: read_u32(bytes, 24),
        frame_id: read_u32(bytes, 28),
        presentation_timestamp_micros: read_u64(bytes, 32),
        frame_packet_index: read_u16(bytes, 40),
        frame_packet_count: read_u16(bytes, 42),
        original_frame_bytes: read_u32(bytes, 44),
        fec_block_index: read_u16(bytes, 48),
        fec_shard_index: read_u16(bytes, 50),
        fec_source_count: bytes[52],
        fec_repair_count: bytes[53],
    };
    let payload = &bytes[HEADER_SIZE..];
    validate(header, payload.len())?;
    Ok(MediaDatagram { header, payload })
}

fn validate(header: MediaDatagramHeader, payload_len: usize) -> Result<(), ProtocolError> {
    if payload_len == 0 {
        return Err(ProtocolError::EmptyPayload);
    }
    let datagram_len = HEADER_SIZE + payload_len;
    if datagram_len > MAX_DATAGRAM_SIZE {
        return Err(ProtocolError::DatagramTooLarge {
            actual: datagram_len,
        });
    }
    if header.session_id == 0 || header.session_id > MAX_SIGNED_WIRE_VALUE {
        return Err(ProtocolError::InvalidSessionId);
    }
    if header.stream_id == 0 {
        return Err(ProtocolError::InvalidStreamId);
    }
    if header.presentation_timestamp_micros > MAX_SIGNED_WIRE_VALUE {
        return Err(ProtocolError::InvalidTimestamp);
    }
    if header.frame_packet_count == 0 {
        return Err(ProtocolError::InvalidFramePacketCount);
    }
    let repair = header.flags.contains(PacketFlags::FEC_REPAIR);
    if repair {
        if header.frame_packet_index != REPAIR_PACKET_INDEX {
            return Err(ProtocolError::InvalidFramePacketIndex);
        }
    } else if header.frame_packet_index >= header.frame_packet_count {
        return Err(ProtocolError::InvalidFramePacketIndex);
    }
    if header.original_frame_bytes == 0 || header.original_frame_bytes > MAX_FRAME_BYTES {
        return Err(ProtocolError::InvalidFrameSize(header.original_frame_bytes));
    }
    let start = header.flags.contains(PacketFlags::FRAME_START);
    let end = header.flags.contains(PacketFlags::FRAME_END);
    if repair && (start || end) {
        return Err(ProtocolError::InvalidBoundaryFlags);
    }
    if !repair
        && (start != (header.frame_packet_index == 0)
            || end != (header.frame_packet_index + 1 == header.frame_packet_count))
    {
        return Err(ProtocolError::InvalidBoundaryFlags);
    }
    let fec_disabled = header.fec_source_count == 0 && header.fec_repair_count == 0;
    if fec_disabled {
        if repair || header.fec_block_index != 0 || header.fec_shard_index != 0 {
            return Err(ProtocolError::InvalidFecMetadata);
        }
    } else {
        let shard_count = u16::from(header.fec_source_count) + u16::from(header.fec_repair_count);
        if header.fec_source_count == 0
            || header.fec_repair_count == 0
            || header.fec_shard_index >= shard_count
            || repair != (header.fec_shard_index >= u16::from(header.fec_source_count))
        {
            return Err(ProtocolError::InvalidFecMetadata);
        }
    }
    Ok(())
}

fn read_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_be_bytes(
        bytes[offset..offset + 2]
            .try_into()
            .expect("validated bounds"),
    )
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

    fn sample_header() -> MediaDatagramHeader {
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
        }
    }

    #[test]
    fn golden_video_datagram_round_trips() {
        let payload = [0xaa, 0xbb];
        let encoded = encode_datagram(sample_header(), &payload).unwrap();
        let expected = decode_hex(
            "42504d3101000138000500000102030405060708111213142122232431323334\
             414243444546474800000002000000040000000000000000aabb",
        );
        assert_eq!(encoded, expected);
        assert_eq!(encoded.len(), HEADER_SIZE + payload.len());
        let decoded = decode_datagram(&encoded).unwrap();
        assert_eq!(decoded.header, sample_header());
        assert_eq!(decoded.payload, payload);
    }

    #[test]
    fn maximum_datagram_size_is_accepted() {
        let mut header = sample_header();
        header.flags = PacketFlags::KEYFRAME | PacketFlags::FRAME_START | PacketFlags::FRAME_END;
        header.frame_packet_count = 1;
        header.original_frame_bytes = u32::try_from(MAX_PAYLOAD_SIZE).unwrap();
        let encoded = encode_datagram(header, &vec![0x5a; MAX_PAYLOAD_SIZE]).unwrap();
        assert_eq!(encoded.len(), MAX_DATAGRAM_SIZE);
        assert!(decode_datagram(&encoded).is_ok());
    }

    #[test]
    fn malformed_and_oversized_datagrams_are_rejected() {
        assert_eq!(
            decode_datagram(&[0; 10]),
            Err(ProtocolError::DatagramTooShort { actual: 10 })
        );
        let mut encoded = encode_datagram(sample_header(), &[1]).unwrap();
        encoded[8] = 0x80;
        assert_eq!(
            decode_datagram(&encoded),
            Err(ProtocolError::UnknownFlags(0x8005))
        );
        assert_eq!(
            encode_datagram(sample_header(), &vec![0; MAX_PAYLOAD_SIZE + 1]),
            Err(ProtocolError::DatagramTooLarge {
                actual: MAX_DATAGRAM_SIZE + 1
            })
        );
    }

    #[test]
    fn packet_boundaries_and_fec_metadata_are_consistent() {
        let mut header = sample_header();
        header.flags = PacketFlags::KEYFRAME;
        assert_eq!(
            encode_datagram(header, &[1]),
            Err(ProtocolError::InvalidBoundaryFlags)
        );

        let mut repair = sample_header();
        repair.flags = PacketFlags::KEYFRAME | PacketFlags::FEC_REPAIR;
        repair.frame_packet_index = REPAIR_PACKET_INDEX;
        repair.fec_shard_index = 4;
        repair.fec_source_count = 4;
        repair.fec_repair_count = 2;
        assert!(encode_datagram(repair, &[9, 9]).is_ok());
    }

    #[test]
    fn signed_cross_language_limits_are_enforced() {
        let mut header = sample_header();
        header.session_id = 1_u64 << 63;
        assert_eq!(
            encode_datagram(header, &[1]),
            Err(ProtocolError::InvalidSessionId)
        );
        let mut header = sample_header();
        header.presentation_timestamp_micros = 1_u64 << 63;
        assert_eq!(
            encode_datagram(header, &[1]),
            Err(ProtocolError::InvalidTimestamp)
        );
    }

    #[test]
    fn deterministic_malformed_corpus_never_panics() {
        let mut state = 0x1234_5678_u32;
        for length in 0..=MAX_DATAGRAM_SIZE + 32 {
            let mut bytes = vec![0_u8; length];
            for byte in &mut bytes {
                state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                *byte = state.to_be_bytes()[0];
            }
            assert!(std::panic::catch_unwind(|| decode_datagram(&bytes)).is_ok());
            assert!(std::panic::catch_unwind(|| control::decode_control_message(&bytes)).is_ok());
        }
    }

    #[test]
    fn single_byte_mutations_of_valid_packets_never_panic() {
        let datagram = encode_datagram(sample_header(), &[0xaa, 0xbb]).unwrap();
        let offer = control::encode_control_message(
            control::ControlHeader {
                minor_version: 0,
                session_id: 1,
                request_id: 2,
            },
            control::ControlMessage::Stop(control::StopMessage {
                reason: control::StopReason::UserRequest,
                scope: control::StopScope::All,
            }),
        )
        .unwrap();
        for original in [&datagram, &offer] {
            for index in 0..original.len() {
                for replacement in [0, 1, 0x7f, 0xff, original[index] ^ 1] {
                    let mut mutated = original.clone();
                    mutated[index] = replacement;
                    assert!(std::panic::catch_unwind(|| decode_datagram(&mutated)).is_ok());
                    assert!(
                        std::panic::catch_unwind(|| { control::decode_control_message(&mutated) })
                            .is_ok()
                    );
                }
            }
        }
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        let compact: String = value
            .chars()
            .filter(|character| !character.is_whitespace())
            .collect();
        assert_eq!(compact.len() % 2, 0);
        let mut decoded = Vec::with_capacity(compact.len() / 2);
        for offset in (0..compact.len()).step_by(2) {
            decoded.push(u8::from_str_radix(&compact[offset..offset + 2], 16).unwrap());
        }
        decoded
    }
}
