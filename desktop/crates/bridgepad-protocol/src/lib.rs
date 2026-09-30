//! BridgePad's transport-independent wire protocol.

use std::fmt;

pub const MAGIC: u32 = 0x4250_4431;
pub const MAJOR_VERSION: u8 = 1;
pub const MINOR_VERSION: u8 = 0;
pub const HEADER_SIZE: usize = 32;
// Trickle ICE is intentionally avoided for the first local-network streaming
// milestone, so a complete SDP offer/answer must fit in one protocol packet.
pub const MAX_PAYLOAD_SIZE: usize = 16_384;
pub const MAX_PEER_NAME_SIZE: usize = 64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum MessageType {
    Hello = 0x01,
    HelloAck = 0x02,
    SessionStart = 0x03,
    SessionReady = 0x04,
    SessionStop = 0x05,
    PairRequest = 0x06,
    PairChallenge = 0x07,
    PairProof = 0x08,
    PairResult = 0x09,
    AuthRequest = 0x0a,
    AuthChallenge = 0x0b,
    AuthProof = 0x0c,
    AuthResult = 0x0d,
    GamepadSnapshot = 0x10,
    Pointer = 0x11,
    Keyboard = 0x12,
    Ping = 0x20,
    Pong = 0x21,
    Status = 0x30,
    Error = 0x31,
    Rumble = 0x40,
    MediaOffer = 0x50,
    MediaAnswer = 0x51,
    VideoChunk = 0x52,
    MediaFeedback = 0x53,
    MediaStop = 0x54,
    WebRtcOffer = 0x55,
    WebRtcAnswer = 0x56,
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
            0x06 => Ok(Self::PairRequest),
            0x07 => Ok(Self::PairChallenge),
            0x08 => Ok(Self::PairProof),
            0x09 => Ok(Self::PairResult),
            0x0a => Ok(Self::AuthRequest),
            0x0b => Ok(Self::AuthChallenge),
            0x0c => Ok(Self::AuthProof),
            0x0d => Ok(Self::AuthResult),
            0x10 => Ok(Self::GamepadSnapshot),
            0x11 => Ok(Self::Pointer),
            0x12 => Ok(Self::Keyboard),
            0x20 => Ok(Self::Ping),
            0x21 => Ok(Self::Pong),
            0x30 => Ok(Self::Status),
            0x31 => Ok(Self::Error),
            0x40 => Ok(Self::Rumble),
            0x50 => Ok(Self::MediaOffer),
            0x51 => Ok(Self::MediaAnswer),
            0x52 => Ok(Self::VideoChunk),
            0x53 => Ok(Self::MediaFeedback),
            0x54 => Ok(Self::MediaStop),
            0x55 => Ok(Self::WebRtcOffer),
            0x56 => Ok(Self::WebRtcAnswer),
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
    InvalidDpad(u8),
    UnknownKeyboardInput(u8),
    UnknownKeyboardKey(u8),
    UnknownKeyboardModifiers(u8),
    InvalidUtf8,
    InvalidPeerId,
    InvalidPeerName,
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
    let payload_length =
        u16::try_from(payload.len()).map_err(|_| ProtocolError::PayloadTooLarge(payload.len()))?;
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

pub const CAPABILITY_GAMEPAD: u32 = 1;
pub const CAPABILITY_POINTER: u32 = 1 << 1;
pub const CAPABILITY_VIDEO: u32 = 1 << 3;
pub const CAPABILITY_AUDIO: u32 = 1 << 4;
pub const CAPABILITY_KEYBOARD: u32 = 1 << 5;
pub const AUTH_NONCE_SIZE: usize = 32;
pub const AUTH_PROOF_SIZE: usize = 32;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionStart {
    pub input_kind: u8,
    pub requested_capabilities: u32,
}

pub fn decode_session_start(packet: Packet<'_>) -> Result<SessionStart, ProtocolError> {
    require_payload_length(packet.payload, 5)?;
    Ok(SessionStart {
        input_kind: packet.payload[0],
        requested_capabilities: read_u32(packet.payload, 1),
    })
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MediaOffer {
    pub requested_capabilities: u32,
    pub max_width: u16,
    pub max_height: u16,
    pub max_frames_per_second: u16,
    pub max_bitrate_bits_per_second: u32,
}

pub fn decode_media_offer(packet: Packet<'_>) -> Result<MediaOffer, ProtocolError> {
    require_payload_length(packet.payload, 14)?;
    Ok(MediaOffer {
        requested_capabilities: read_u32(packet.payload, 0),
        max_width: read_u16(packet.payload, 4),
        max_height: read_u16(packet.payload, 6),
        max_frames_per_second: read_u16(packet.payload, 8),
        max_bitrate_bits_per_second: read_u32(packet.payload, 10),
    })
}

pub fn encode_media_answer(
    enabled_capabilities: u32,
    codec: u8,
    width: u16,
    height: u16,
    frames_per_second: u16,
    target_bitrate_bits_per_second: u32,
    keyframe_interval_millis: u16,
) -> Vec<u8> {
    let mut payload = Vec::with_capacity(17);
    payload.extend_from_slice(&enabled_capabilities.to_be_bytes());
    payload.push(codec);
    payload.extend_from_slice(&width.to_be_bytes());
    payload.extend_from_slice(&height.to_be_bytes());
    payload.extend_from_slice(&frames_per_second.to_be_bytes());
    payload.extend_from_slice(&target_bitrate_bits_per_second.to_be_bytes());
    payload.extend_from_slice(&keyframe_interval_millis.to_be_bytes());
    payload
}

pub fn decode_session_description(packet: Packet<'_>) -> Result<&str, ProtocolError> {
    if packet.payload.is_empty() {
        return Err(ProtocolError::InvalidPayloadLength {
            expected: 1,
            actual: 0,
        });
    }
    std::str::from_utf8(packet.payload).map_err(|_| ProtocolError::InvalidUtf8)
}

pub fn encode_session_description(sdp: &str) -> Result<Vec<u8>, ProtocolError> {
    if sdp.is_empty() {
        return Err(ProtocolError::InvalidPayloadLength {
            expected: 1,
            actual: 0,
        });
    }
    if sdp.len() > MAX_PAYLOAD_SIZE {
        return Err(ProtocolError::PayloadTooLarge(sdp.len()));
    }
    Ok(sdp.as_bytes().to_vec())
}

pub const VIDEO_CHUNK_HEADER_SIZE: usize = 29;

#[derive(Clone, Copy, Debug)]
pub struct VideoChunk<'a> {
    pub frame_id: u32,
    pub presentation_timestamp_micros: u64,
    pub keyframe: bool,
    pub chunk_index: u16,
    pub chunk_count: u16,
    pub total_frame_bytes: u32,
    pub generation_micros: u32,
    pub encode_micros: u32,
    pub data: &'a [u8],
}

pub fn encode_video_chunk(chunk: VideoChunk<'_>) -> Result<Vec<u8>, ProtocolError> {
    if chunk.data.is_empty() || chunk.data.len() + VIDEO_CHUNK_HEADER_SIZE > MAX_PAYLOAD_SIZE {
        return Err(ProtocolError::PayloadTooLarge(
            chunk.data.len() + VIDEO_CHUNK_HEADER_SIZE,
        ));
    }
    let mut payload = Vec::with_capacity(VIDEO_CHUNK_HEADER_SIZE + chunk.data.len());
    payload.extend_from_slice(&chunk.frame_id.to_be_bytes());
    payload.extend_from_slice(&chunk.presentation_timestamp_micros.to_be_bytes());
    payload.push(u8::from(chunk.keyframe));
    payload.extend_from_slice(&chunk.chunk_index.to_be_bytes());
    payload.extend_from_slice(&chunk.chunk_count.to_be_bytes());
    payload.extend_from_slice(&chunk.total_frame_bytes.to_be_bytes());
    payload.extend_from_slice(&chunk.generation_micros.to_be_bytes());
    payload.extend_from_slice(&chunk.encode_micros.to_be_bytes());
    payload.extend_from_slice(chunk.data);
    Ok(payload)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GamepadSnapshot {
    pub buttons: u16,
    pub dpad: u8,
    pub left_x: i16,
    pub left_y: i16,
    pub right_x: i16,
    pub right_y: i16,
    pub left_trigger: u16,
    pub right_trigger: u16,
}

pub fn decode_gamepad_snapshot(packet: Packet<'_>) -> Result<GamepadSnapshot, ProtocolError> {
    require_payload_length(packet.payload, 15)?;
    let dpad = packet.payload[2];
    if dpad > 8 {
        return Err(ProtocolError::InvalidDpad(dpad));
    }
    Ok(GamepadSnapshot {
        buttons: read_u16(packet.payload, 0),
        dpad,
        left_x: read_i16(packet.payload, 3),
        left_y: read_i16(packet.payload, 5),
        right_x: read_i16(packet.payload, 7),
        right_y: read_i16(packet.payload, 9),
        left_trigger: read_u16(packet.payload, 11),
        right_trigger: read_u16(packet.payload, 13),
    })
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PointerReport {
    pub buttons: u8,
    pub delta_x: i32,
    pub delta_y: i32,
    pub scroll_y: i32,
    pub zoom_y: i32,
}

pub fn decode_pointer(packet: Packet<'_>) -> Result<PointerReport, ProtocolError> {
    if packet.payload.len() != 9 && packet.payload.len() != 13 && packet.payload.len() != 17 {
        return Err(ProtocolError::InvalidPayloadLength {
            expected: 9,
            actual: packet.payload.len(),
        });
    }
    Ok(PointerReport {
        buttons: packet.payload[0],
        delta_x: read_i32(packet.payload, 1),
        delta_y: read_i32(packet.payload, 5),
        scroll_y: if packet.payload.len() == 13 {
            read_i32(packet.payload, 9)
        } else if packet.payload.len() == 17 {
            read_i32(packet.payload, 9)
        } else {
            0
        },
        zoom_y: if packet.payload.len() == 17 {
            read_i32(packet.payload, 13)
        } else {
            0
        },
    })
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum KeyboardKey {
    Backspace,
    D,
    Enter,
    Left,
    M,
    Right,
    Tab,
    Escape,
}

pub const KEYBOARD_MODIFIER_CONTROL: u8 = 1 << 0;
pub const KEYBOARD_MODIFIER_META: u8 = 1 << 1;
pub const KEYBOARD_MODIFIER_ALT: u8 = 1 << 2;
pub const KEYBOARD_MODIFIER_SHIFT: u8 = 1 << 3;
const KEYBOARD_MODIFIERS: u8 = KEYBOARD_MODIFIER_CONTROL
    | KEYBOARD_MODIFIER_META
    | KEYBOARD_MODIFIER_ALT
    | KEYBOARD_MODIFIER_SHIFT;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum KeyboardInput {
    Text(String),
    Key(KeyboardKey),
    Shortcut { modifiers: u8, key: KeyboardKey },
}

pub fn decode_keyboard(packet: Packet<'_>) -> Result<KeyboardInput, ProtocolError> {
    let kind = *packet
        .payload
        .first()
        .ok_or(ProtocolError::InvalidPayloadLength {
            expected: 1,
            actual: 0,
        })?;
    match kind {
        0 => {
            if packet.payload.len() < 3 {
                return Err(ProtocolError::InvalidPayloadLength {
                    expected: 3,
                    actual: packet.payload.len(),
                });
            }
            let length = usize::from(read_u16(packet.payload, 1));
            let expected = 3 + length;
            require_payload_length(packet.payload, expected)?;
            let text = std::str::from_utf8(&packet.payload[3..])
                .map_err(|_| ProtocolError::InvalidUtf8)?
                .to_owned();
            if text.is_empty() {
                return Err(ProtocolError::InvalidPayloadLength {
                    expected: 4,
                    actual: packet.payload.len(),
                });
            }
            Ok(KeyboardInput::Text(text))
        }
        1 => {
            require_payload_length(packet.payload, 2)?;
            let key = match packet.payload[1] {
                0 => KeyboardKey::Backspace,
                1 => KeyboardKey::Enter,
                2 => KeyboardKey::Tab,
                3 => KeyboardKey::Escape,
                4 => KeyboardKey::D,
                5 => KeyboardKey::Left,
                6 => KeyboardKey::M,
                7 => KeyboardKey::Right,
                value => return Err(ProtocolError::UnknownKeyboardKey(value)),
            };
            Ok(KeyboardInput::Key(key))
        }
        2 => {
            require_payload_length(packet.payload, 3)?;
            let modifiers = packet.payload[1];
            if modifiers == 0 || modifiers & !KEYBOARD_MODIFIERS != 0 {
                return Err(ProtocolError::UnknownKeyboardModifiers(modifiers));
            }
            let key = match packet.payload[2] {
                0 => KeyboardKey::Backspace,
                1 => KeyboardKey::Enter,
                2 => KeyboardKey::Tab,
                3 => KeyboardKey::Escape,
                4 => KeyboardKey::D,
                5 => KeyboardKey::Left,
                6 => KeyboardKey::M,
                7 => KeyboardKey::Right,
                value => return Err(ProtocolError::UnknownKeyboardKey(value)),
            };
            Ok(KeyboardInput::Shortcut { modifiers, key })
        }
        value => Err(ProtocolError::UnknownKeyboardInput(value)),
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairRequest {
    pub peer_id: [u8; 16],
    pub peer_name: String,
    pub client_nonce: [u8; AUTH_NONCE_SIZE],
}

pub fn decode_pair_request(packet: Packet<'_>) -> Result<PairRequest, ProtocolError> {
    if packet.payload.len() < 16 + 1 + AUTH_NONCE_SIZE {
        return Err(ProtocolError::InvalidPayloadLength {
            expected: 16 + 1 + AUTH_NONCE_SIZE,
            actual: packet.payload.len(),
        });
    }
    let peer_id: [u8; 16] = packet.payload[..16]
        .try_into()
        .expect("validated peer id bounds");
    if peer_id.iter().all(|byte| *byte == 0) {
        return Err(ProtocolError::InvalidPeerId);
    }
    let name_length = usize::from(packet.payload[16]);
    if name_length > MAX_PEER_NAME_SIZE {
        return Err(ProtocolError::InvalidPeerName);
    }
    let expected = 16 + 1 + name_length + AUTH_NONCE_SIZE;
    require_payload_length(packet.payload, expected)?;
    let peer_name = std::str::from_utf8(&packet.payload[17..17 + name_length])
        .map_err(|_| ProtocolError::InvalidUtf8)?
        .to_owned();
    let client_nonce = packet.payload[17 + name_length..]
        .try_into()
        .expect("validated nonce bounds");
    Ok(PairRequest {
        peer_id,
        peer_name,
        client_nonce,
    })
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AuthRequest {
    pub peer_id: [u8; 16],
    pub client_nonce: [u8; AUTH_NONCE_SIZE],
}

pub fn decode_auth_request(packet: Packet<'_>) -> Result<AuthRequest, ProtocolError> {
    require_payload_length(packet.payload, 16 + AUTH_NONCE_SIZE)?;
    let peer_id: [u8; 16] = packet.payload[..16]
        .try_into()
        .expect("validated peer id bounds");
    if peer_id.iter().all(|byte| *byte == 0) {
        return Err(ProtocolError::InvalidPeerId);
    }
    Ok(AuthRequest {
        peer_id,
        client_nonce: packet.payload[16..]
            .try_into()
            .expect("validated nonce bounds"),
    })
}

pub fn decode_auth_proof(packet: Packet<'_>) -> Result<[u8; AUTH_PROOF_SIZE], ProtocolError> {
    require_payload_length(packet.payload, AUTH_PROOF_SIZE)?;
    Ok(packet.payload.try_into().expect("validated proof bounds"))
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
    u16::from_be_bytes(
        bytes[offset..offset + 2]
            .try_into()
            .expect("validated field bounds"),
    )
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("validated field bounds"),
    )
}

fn read_i16(bytes: &[u8], offset: usize) -> i16 {
    i16::from_be_bytes(
        bytes[offset..offset + 2]
            .try_into()
            .expect("validated field bounds"),
    )
}

fn read_i32(bytes: &[u8], offset: usize) -> i32 {
    i32::from_be_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("validated field bounds"),
    )
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_be_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("validated field bounds"),
    )
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
                scroll_y: 0,
                zoom_y: 0,
            })
        );
        assert_eq!(
            encode_packet(packet.header, packet.payload).expect("packet must encode"),
            expected
        );
    }

    #[test]
    fn extended_pointer_payload_decodes_zoom() {
        let payload = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3];
        let bytes = encode_packet(
            PacketHeader {
                session_id: 7,
                sequence: 9,
                timestamp_micros: 11,
                message_type: MessageType::Pointer,
            },
            &payload,
        )
        .expect("packet must encode");

        assert_eq!(
            decode_pointer(decode_packet(&bytes).expect("packet must decode")),
            Ok(PointerReport {
                buttons: 0,
                delta_x: 0,
                delta_y: 0,
                scroll_y: 0,
                zoom_y: 3,
            })
        );
    }

    #[test]
    fn invalid_envelopes_are_rejected() {
        let valid = vector("ping");
        assert!(matches!(
            decode_packet(&valid[..31]),
            Err(ProtocolError::HeaderTooShort)
        ));

        let mut bad_magic = valid.clone();
        bad_magic[0] = 0;
        assert!(matches!(
            decode_packet(&bad_magic),
            Err(ProtocolError::InvalidMagic(_))
        ));

        let mut trailing = valid;
        trailing.push(0);
        assert!(matches!(
            decode_packet(&trailing),
            Err(ProtocolError::PayloadLengthMismatch { .. })
        ));
    }

    #[test]
    fn gamepad_snapshot_decodes_complete_state() {
        let payload = [
            0x01, 0x21, 0x06, 0x80, 0x01, 0x7f, 0xff, 0xff, 0xff, 0x00, 0x00, 0x80, 0x00, 0xff,
            0xff,
        ];
        let bytes = encode_packet(
            PacketHeader {
                session_id: 7,
                sequence: 9,
                timestamp_micros: 11,
                message_type: MessageType::GamepadSnapshot,
            },
            &payload,
        )
        .expect("packet must encode");
        let packet = decode_packet(&bytes).expect("packet must decode");
        assert_eq!(
            decode_gamepad_snapshot(packet),
            Ok(GamepadSnapshot {
                buttons: 0x0121,
                dpad: 6,
                left_x: i16::MIN + 1,
                left_y: i16::MAX,
                right_x: -1,
                right_y: 0,
                left_trigger: 0x8000,
                right_trigger: u16::MAX,
            })
        );
    }

    #[test]
    fn keyboard_decodes_utf8_text_and_special_keys() {
        let text_payload = [0, 0, 3, b'o', 0xc3, 0xa1];
        let text_bytes = encode_packet(
            PacketHeader {
                session_id: 7,
                sequence: 10,
                timestamp_micros: 12,
                message_type: MessageType::Keyboard,
            },
            &text_payload,
        )
        .expect("packet must encode");
        assert_eq!(
            decode_keyboard(decode_packet(&text_bytes).expect("packet must decode")),
            Ok(KeyboardInput::Text("oá".to_owned()))
        );

        let key_payload = [1, 0];
        let key_bytes = encode_packet(
            PacketHeader {
                session_id: 7,
                sequence: 11,
                timestamp_micros: 13,
                message_type: MessageType::Keyboard,
            },
            &key_payload,
        )
        .expect("packet must encode");
        assert_eq!(
            decode_keyboard(decode_packet(&key_bytes).expect("packet must decode")),
            Ok(KeyboardInput::Key(KeyboardKey::Backspace))
        );
    }

    #[test]
    fn keyboard_decodes_atomic_shortcut() {
        let modifiers = KEYBOARD_MODIFIER_ALT | KEYBOARD_MODIFIER_SHIFT;
        let payload = [2, modifiers, 2];
        let bytes = encode_packet(
            PacketHeader {
                session_id: 7,
                sequence: 10,
                timestamp_micros: 12,
                message_type: MessageType::Keyboard,
            },
            &payload,
        )
        .expect("packet must encode");

        assert_eq!(
            decode_keyboard(decode_packet(&bytes).expect("packet must decode")),
            Ok(KeyboardInput::Shortcut {
                modifiers,
                key: KeyboardKey::Tab,
            })
        );
    }

    #[test]
    fn keyboard_decodes_window_navigation_keys() {
        for (wire_code, expected) in [
            (5, KeyboardKey::Left),
            (6, KeyboardKey::M),
            (7, KeyboardKey::Right),
        ] {
            let payload = [1, wire_code];
            let bytes = encode_packet(
                PacketHeader {
                    session_id: 7,
                    sequence: 10,
                    timestamp_micros: 12,
                    message_type: MessageType::Keyboard,
                },
                &payload,
            )
            .expect("packet must encode");
            assert_eq!(
                decode_keyboard(decode_packet(&bytes).expect("packet must decode")),
                Ok(KeyboardInput::Key(expected))
            );
        }
    }

    #[test]
    fn media_offer_and_video_chunk_have_stable_wire_shapes() {
        let offer_payload = [0, 0, 0, 0x18, 1, 0x40, 0, 0xb4, 0, 20, 1, 0x19, 0x40, 0];
        let offer_bytes = encode_packet(
            PacketHeader {
                session_id: 9,
                sequence: 1,
                timestamp_micros: 2,
                message_type: MessageType::MediaOffer,
            },
            &offer_payload,
        )
        .unwrap();
        let offer = decode_media_offer(decode_packet(&offer_bytes).unwrap()).unwrap();
        assert_eq!(
            offer.requested_capabilities,
            CAPABILITY_VIDEO | CAPABILITY_AUDIO
        );
        assert_eq!(offer.max_width, 320);
        assert_eq!(offer.max_height, 180);

        let payload = encode_video_chunk(VideoChunk {
            frame_id: 7,
            presentation_timestamp_micros: 8,
            keyframe: true,
            chunk_index: 0,
            chunk_count: 1,
            total_frame_bytes: 4,
            generation_micros: 10,
            encode_micros: 11,
            data: &[1, 2, 3, 4],
        })
        .unwrap();
        assert_eq!(payload.len(), VIDEO_CHUNK_HEADER_SIZE + 4);
        assert_eq!(&payload[VIDEO_CHUNK_HEADER_SIZE..], &[1, 2, 3, 4]);
    }

    #[test]
    fn session_description_round_trips_as_one_packet() {
        let sdp = format!("v=0\r\n{}", "a=candidate:test\r\n".repeat(300));
        let payload = encode_session_description(&sdp).unwrap();
        let bytes = encode_packet(
            PacketHeader {
                session_id: 9,
                sequence: 2,
                timestamp_micros: 3,
                message_type: MessageType::WebRtcOffer,
            },
            &payload,
        )
        .unwrap();

        let packet = decode_packet(&bytes).unwrap();
        assert_eq!(packet.header.message_type, MessageType::WebRtcOffer);
        assert_eq!(decode_session_description(packet).unwrap(), sdp);
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
