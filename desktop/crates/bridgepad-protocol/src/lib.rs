//! BridgePad's transport-independent wire protocol.

use std::fmt;

pub const MAGIC: u32 = 0x4250_4431;
pub const MAJOR_VERSION: u8 = 1;
pub const MINOR_VERSION: u8 = 0;
pub const HEADER_SIZE: usize = 32;
pub const MAX_PAYLOAD_SIZE: usize = 4_096;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum MessageType {
    Hello = 0x01,
    HelloAck = 0x02,
    SessionStart = 0x03,
    SessionReady = 0x04,
    SessionStop = 0x05,
    GamepadSnapshot = 0x10,
    Pointer = 0x11,
    Ping = 0x20,
    Pong = 0x21,
    Status = 0x30,
    Error = 0x31,
    Rumble = 0x40,
}

impl TryFrom<u8> for MessageType {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, ProtocolError> {
        match value {
            0x01 => Ok(Self::Hello),
            0x02 => Ok(Self::HelloAck),
            0x03 => Ok(Self::SessionStart),
            0x04 => Ok(Self::SessionReady),
            0x05 => Ok(Self::SessionStop),
            0x10 => Ok(Self::GamepadSnapshot),
            0x11 => Ok(Self::Pointer),
            0x20 => Ok(Self::Ping),
            0x21 => Ok(Self::Pong),
            0x30 => Ok(Self::Status),
            0x31 => Ok(Self::Error),
            0x40 => Ok(Self::Rumble),
            _ => Err(ProtocolError::UnknownMessageType(value)),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PacketHeader {
    pub session_id: u64,
    pub sequence: u32,
    pub timestamp_micros: u64,
    pub message_type: MessageType,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Packet<'a> {
    pub header: PacketHeader,
    pub payload: &'a [u8],
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProtocolError {
    HeaderTooShort,
    InvalidMagic(u32),
    UnsupportedVersion { major: u8, minor: u8 },
    UnknownMessageType(u8),
    UnsupportedFlags(u8),
    NonZeroReserved(u16),
    PayloadTooLarge(usize),
    PayloadLengthMismatch { declared: usize, actual: usize },
    InvalidPayloadLength { expected: usize, actual: usize },
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for ProtocolError {}

pub fn decode_packet(bytes: &[u8]) -> Result<Packet<'_>, ProtocolError> {
    if bytes.len() < HEADER_SIZE {
        return Err(ProtocolError::HeaderTooShort);
    }

    let magic = read_u32(bytes, 0);
    if magic != MAGIC {
        return Err(ProtocolError::InvalidMagic(magic));
    }
    let major = bytes[4];
    let minor = bytes[5];
    if major != MAJOR_VERSION || minor != MINOR_VERSION {
        return Err(ProtocolError::UnsupportedVersion { major, minor });
    }
    let message_type = MessageType::try_from(bytes[6])?;
    if bytes[7] != 0 {
        return Err(ProtocolError::UnsupportedFlags(bytes[7]));
    }
    let reserved = read_u16(bytes, 30);
    if reserved != 0 {
        return Err(ProtocolError::NonZeroReserved(reserved));
    }

    let declared = usize::from(read_u16(bytes, 28));
    if declared > MAX_PAYLOAD_SIZE {
        return Err(ProtocolError::PayloadTooLarge(declared));
    }
    let actual = bytes.len() - HEADER_SIZE;
    if declared != actual {
        return Err(ProtocolError::PayloadLengthMismatch { declared, actual });
    }

    Ok(Packet {
        header: PacketHeader {
            session_id: read_u64(bytes, 8),
            sequence: read_u32(bytes, 16),
            timestamp_micros: read_u64(bytes, 20),
            message_type,
        },
        payload: &bytes[HEADER_SIZE..],
    })
}

pub fn encode_packet(header: PacketHeader, payload: &[u8]) -> Result<Vec<u8>, ProtocolError> {
    if payload.len() > MAX_PAYLOAD_SIZE {
        return Err(ProtocolError::PayloadTooLarge(payload.len()));
    }
    let payload_length = u16::try_from(payload.len())
        .map_err(|_| ProtocolError::PayloadTooLarge(payload.len()))?;
    let mut bytes = Vec::with_capacity(HEADER_SIZE + payload.len());
    bytes.extend_from_slice(&MAGIC.to_be_bytes());
    bytes.push(MAJOR_VERSION);
    bytes.push(MINOR_VERSION);
    bytes.push(header.message_type as u8);
    bytes.push(0);
    bytes.extend_from_slice(&header.session_id.to_be_bytes());
    bytes.extend_from_slice(&header.sequence.to_be_bytes());
    bytes.extend_from_slice(&header.timestamp_micros.to_be_bytes());
    bytes.extend_from_slice(&payload_length.to_be_bytes());
    bytes.extend_from_slice(&0_u16.to_be_bytes());
    bytes.extend_from_slice(payload);
    Ok(bytes)
}

pub fn decode_ping(packet: Packet<'_>) -> Result<u64, ProtocolError> {
    require_payload_length(packet.payload, 8)?;
    Ok(read_u64(packet.payload, 0))
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PointerReport {
    pub buttons: u8,
    pub delta_x: i32,
    pub delta_y: i32,
}

pub fn decode_pointer(packet: Packet<'_>) -> Result<PointerReport, ProtocolError> {
    require_payload_length(packet.payload, 9)?;
    Ok(PointerReport {
        buttons: packet.payload[0],
        delta_x: read_i32(packet.payload, 1),
        delta_y: read_i32(packet.payload, 5),
    })
}

fn require_payload_length(payload: &[u8], expected: usize) -> Result<(), ProtocolError> {
    if payload.len() == expected {
        Ok(())
    } else {
        Err(ProtocolError::InvalidPayloadLength {
            expected,
            actual: payload.len(),
        })
    }
}

fn read_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_be_bytes(bytes[offset..offset + 2].try_into().expect("validated field bounds"))
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes(bytes[offset..offset + 4].try_into().expect("validated field bounds"))
}

fn read_i32(bytes: &[u8], offset: usize) -> i32 {
    i32::from_be_bytes(bytes[offset..offset + 4].try_into().expect("validated field bounds"))
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_be_bytes(bytes[offset..offset + 8].try_into().expect("validated field bounds"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    const GOLDEN_VECTORS: &str = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../protocol-spec/vectors/v1.properties"
    ));

    #[test]
    fn kotlin_ping_vector_decodes_and_encodes_identically() {
        let expected = vector("ping");
        let packet = decode_packet(&expected).expect("Kotlin vector must decode");
        assert_eq!(packet.header.message_type, MessageType::Ping);
        assert_eq!(packet.header.session_id, 1);
        assert_eq!(packet.header.sequence, 2);
        assert_eq!(packet.header.timestamp_micros, 3);
        assert_eq!(decode_ping(packet), Ok(4));
        assert_eq!(
            encode_packet(packet.header, packet.payload).expect("packet must encode"),
            expected
        );
    }

    #[test]
    fn kotlin_pointer_vector_decodes_and_encodes_identically() {
        let expected = vector("pointer");
        let packet = decode_packet(&expected).expect("Kotlin vector must decode");
        assert_eq!(packet.header.message_type, MessageType::Pointer);
        assert_eq!(
            decode_pointer(packet),
            Ok(PointerReport {
                buttons: 3,
                delta_x: -250,
                delta_y: 500,
            })
        );
        assert_eq!(
            encode_packet(packet.header, packet.payload).expect("packet must encode"),
            expected
        );
    }

    #[test]
    fn invalid_envelopes_are_rejected() {
        let valid = vector("ping");
        assert!(matches!(decode_packet(&valid[..31]), Err(ProtocolError::HeaderTooShort)));

        let mut bad_magic = valid.clone();
        bad_magic[0] = 0;
        assert!(matches!(decode_packet(&bad_magic), Err(ProtocolError::InvalidMagic(_))));

        let mut trailing = valid;
        trailing.push(0);
        assert!(matches!(
            decode_packet(&trailing),
            Err(ProtocolError::PayloadLengthMismatch { .. })
        ));
    }

    fn vector(name: &str) -> Vec<u8> {
        let entries: HashMap<_, _> = GOLDEN_VECTORS
            .lines()
            .filter(|line| !line.is_empty() && !line.starts_with('#'))
            .filter_map(|line| line.split_once('='))
            .collect();
        decode_hex(entries.get(name).expect("missing golden vector"))
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        assert_eq!(value.len() % 2, 0, "hex must have an even number of digits");
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                let pair = std::str::from_utf8(pair).expect("hex must be ASCII");
                u8::from_str_radix(pair, 16).expect("invalid hex digit")
            })
            .collect()
    }
}
