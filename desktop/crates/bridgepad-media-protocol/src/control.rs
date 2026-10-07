//! Reliable negotiation and feedback messages for `BridgePad` Media v1.

use std::fmt;

pub const CONTROL_MAGIC: [u8; 4] = *b"BPMC";
pub const CONTROL_HEADER_SIZE: usize = 28;
const CONTROL_HEADER_SIZE_WIRE: u8 = 28;
pub const MAX_CONTROL_PAYLOAD_SIZE: usize = 4_096;
pub const CURRENT_MINOR_VERSION: u8 = 0;

pub mod video_codecs {
    pub const H264: u16 = 1 << 0;
    pub const HEVC: u16 = 1 << 1;
    pub const AV1: u16 = 1 << 2;
}

pub mod audio_codecs {
    pub const OPUS: u16 = 1 << 0;
}

pub mod color_formats {
    pub const BT709_SDR: u16 = 1 << 0;
    pub const HDR10: u16 = 1 << 1;
}

pub mod media_capabilities {
    pub const LOW_LATENCY_DECODE: u32 = 1 << 0;
    pub const REED_SOLOMON_FEC: u32 = 1 << 1;
    pub const DEADLINE_RETRANSMIT: u32 = 1 << 2;
    pub const HARDWARE_DECODE: u32 = 1 << 3;
}

pub mod feedback_flags {
    pub const REQUEST_KEYFRAME: u16 = 1 << 0;
    pub const CONGESTED: u16 = 1 << 1;
    pub const DECODER_STARVED: u16 = 1 << 2;
    pub const KNOWN: u16 = REQUEST_KEYFRAME | CONGESTED | DECODER_STARVED;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum ControlMessageKind {
    Offer = 1,
    Answer = 2,
    Feedback = 3,
    KeyframeRequest = 4,
    Stop = 5,
    StopAck = 6,
}

impl TryFrom<u8> for ControlMessageKind {
    type Error = ControlProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Offer),
            2 => Ok(Self::Answer),
            3 => Ok(Self::Feedback),
            4 => Ok(Self::KeyframeRequest),
            5 => Ok(Self::Stop),
            6 => Ok(Self::StopAck),
            _ => Err(ControlProtocolError::UnknownMessageKind(value)),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ControlHeader {
    /// Sender minor version. Known message prefixes remain backwards compatible.
    pub minor_version: u8,
    pub session_id: u64,
    pub request_id: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MediaOffer {
    pub minimum_minor_version: u8,
    pub maximum_minor_version: u8,
    pub video_codecs: u16,
    pub audio_codecs: u16,
    pub color_formats: u16,
    pub max_width: u16,
    pub max_height: u16,
    pub max_frames_per_second: u16,
    pub max_datagram_size: u16,
    pub min_bitrate_bits_per_second: u32,
    pub initial_bitrate_bits_per_second: u32,
    pub max_bitrate_bits_per_second: u32,
    pub max_reorder_micros: u32,
    pub capabilities: u32,
    pub route_id: u64,
    pub clock_origin_micros: u64,
    pub audio_sample_rates: u16,
    pub audio_packet_durations: u8,
    pub max_audio_channels: u8,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum TransportSecurity {
    TransportTls13 = 1,
    SessionAead = 2,
}

impl TryFrom<u8> for TransportSecurity {
    type Error = ControlProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::TransportTls13),
            2 => Ok(Self::SessionAead),
            _ => Err(ControlProtocolError::InvalidMessage),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MediaAnswer {
    pub selected_minor_version: u8,
    pub video_codec: u8,
    pub audio_codec: u8,
    pub color_format: u8,
    pub video_profile: u8,
    pub video_level: u8,
    pub audio_channels: u8,
    pub audio_packet_duration_millis: u8,
    pub width: u16,
    pub height: u16,
    pub frames_per_second: u16,
    pub datagram_size: u16,
    pub target_bitrate_bits_per_second: u32,
    pub min_bitrate_bits_per_second: u32,
    pub max_bitrate_bits_per_second: u32,
    pub reorder_window_micros: u32,
    pub capabilities: u32,
    pub video_stream_id: u32,
    pub audio_stream_id: u32,
    pub route_id: u64,
    pub clock_origin_micros: u64,
    pub key_epoch: u32,
    pub security: TransportSecurity,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MediaFeedback {
    pub stream_id: u32,
    pub highest_sequence: u32,
    pub last_complete_frame: u32,
    pub last_presented_frame: u32,
    pub received_packets: u32,
    pub lost_packets: u32,
    pub late_packets: u32,
    pub reordered_packets: u32,
    pub fec_recovered_packets: u32,
    pub receive_bitrate_bits_per_second: u32,
    pub round_trip_micros: u32,
    pub jitter_micros: u32,
    pub assembly_micros: u32,
    pub decode_micros: u32,
    pub presentation_micros: u32,
    pub requested_bitrate_bits_per_second: u32,
    pub receiver_queue_depth: u16,
    pub flags: u16,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct KeyframeRequest {
    pub stream_id: u32,
    pub last_good_frame: u32,
    pub reason: u8,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum StopReason {
    UserRequest = 0,
    SourceEnded = 1,
    TransportLost = 2,
    ProtocolError = 3,
    ReplacedByNewSession = 4,
}

impl TryFrom<u8> for StopReason {
    type Error = ControlProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::UserRequest),
            1 => Ok(Self::SourceEnded),
            2 => Ok(Self::TransportLost),
            3 => Ok(Self::ProtocolError),
            4 => Ok(Self::ReplacedByNewSession),
            _ => Err(ControlProtocolError::InvalidMessage),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum StopScope {
    All = 0,
    Video = 1,
    Audio = 2,
}

impl TryFrom<u8> for StopScope {
    type Error = ControlProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::All),
            1 => Ok(Self::Video),
            2 => Ok(Self::Audio),
            _ => Err(ControlProtocolError::InvalidMessage),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct StopMessage {
    pub reason: StopReason,
    pub scope: StopScope,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ControlMessage {
    Offer(MediaOffer),
    Answer(MediaAnswer),
    Feedback(MediaFeedback),
    KeyframeRequest(KeyframeRequest),
    Stop(StopMessage),
    StopAck,
}

impl ControlMessage {
    const fn kind(self) -> ControlMessageKind {
        match self {
            Self::Offer(_) => ControlMessageKind::Offer,
            Self::Answer(_) => ControlMessageKind::Answer,
            Self::Feedback(_) => ControlMessageKind::Feedback,
            Self::KeyframeRequest(_) => ControlMessageKind::KeyframeRequest,
            Self::Stop(_) => ControlMessageKind::Stop,
            Self::StopAck => ControlMessageKind::StopAck,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DecodedControlMessage {
    pub header: ControlHeader,
    pub message: ControlMessage,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ControlProtocolError {
    MessageTooShort { actual: usize },
    MessageTooLarge { actual: usize },
    InvalidMagic,
    UnsupportedMajorVersion(u8),
    InvalidHeaderSize(u8),
    UnknownMessageKind(u8),
    UnknownFlags(u16),
    ReservedFieldNotZero,
    InvalidLength,
    InvalidSessionId,
    InvalidMessage,
    IncompatibleMinorVersion,
}

impl fmt::Display for ControlProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for ControlProtocolError {}

/// Encodes one reliable control message.
///
/// # Errors
///
/// Returns [`ControlProtocolError`] when the header or typed payload violates
/// the v1 contract.
pub fn encode_control_message(
    header: ControlHeader,
    message: ControlMessage,
) -> Result<Vec<u8>, ControlProtocolError> {
    validate_header(header)?;
    validate_message(message)?;
    let payload = encode_payload(message);
    let payload_length =
        u16::try_from(payload.len()).map_err(|_| ControlProtocolError::MessageTooLarge {
            actual: payload.len(),
        })?;
    let mut bytes = Vec::with_capacity(CONTROL_HEADER_SIZE + payload.len());
    bytes.extend_from_slice(&CONTROL_MAGIC);
    bytes.push(crate::MAJOR_VERSION);
    bytes.push(header.minor_version);
    bytes.push(message.kind() as u8);
    bytes.push(CONTROL_HEADER_SIZE_WIRE);
    bytes.extend_from_slice(&0_u16.to_be_bytes());
    bytes.extend_from_slice(&0_u16.to_be_bytes());
    bytes.extend_from_slice(&header.session_id.to_be_bytes());
    bytes.extend_from_slice(&header.request_id.to_be_bytes());
    bytes.extend_from_slice(&payload_length.to_be_bytes());
    bytes.extend_from_slice(&0_u16.to_be_bytes());
    bytes.extend_from_slice(&payload);
    Ok(bytes)
}

/// Decodes one complete reliable control message.
///
/// Known message payloads may contain trailing fields from a newer minor
/// version. The known prefix is decoded and the extension is ignored.
///
/// # Errors
///
/// Returns [`ControlProtocolError`] for malformed messages or unsupported major
/// versions. A newer minor version is accepted for known message prefixes.
pub fn decode_control_message(bytes: &[u8]) -> Result<DecodedControlMessage, ControlProtocolError> {
    if bytes.len() < CONTROL_HEADER_SIZE {
        return Err(ControlProtocolError::MessageTooShort {
            actual: bytes.len(),
        });
    }
    if bytes[..4] != CONTROL_MAGIC {
        return Err(ControlProtocolError::InvalidMagic);
    }
    if bytes[4] != crate::MAJOR_VERSION {
        return Err(ControlProtocolError::UnsupportedMajorVersion(bytes[4]));
    }
    let minor_version = bytes[5];
    let kind = ControlMessageKind::try_from(bytes[6])?;
    let header_size = usize::from(bytes[7]);
    if header_size < CONTROL_HEADER_SIZE || header_size > bytes.len() {
        return Err(ControlProtocolError::InvalidHeaderSize(bytes[7]));
    }
    let flags = read_u16(bytes, 8);
    if flags != 0 {
        return Err(ControlProtocolError::UnknownFlags(flags));
    }
    if read_u16(bytes, 10) != 0 || read_u16(bytes, 26) != 0 {
        return Err(ControlProtocolError::ReservedFieldNotZero);
    }
    let header = ControlHeader {
        minor_version,
        session_id: read_u64(bytes, 12),
        request_id: read_u32(bytes, 20),
    };
    validate_header(header)?;
    let payload_length = usize::from(read_u16(bytes, 24));
    if payload_length > MAX_CONTROL_PAYLOAD_SIZE {
        return Err(ControlProtocolError::MessageTooLarge {
            actual: payload_length,
        });
    }
    if bytes.len() != header_size + payload_length {
        return Err(ControlProtocolError::InvalidLength);
    }
    let payload = &bytes[header_size..];
    let message = decode_payload(kind, payload)?;
    validate_message(message)?;
    Ok(DecodedControlMessage { header, message })
}

fn encode_payload(message: ControlMessage) -> Vec<u8> {
    let mut payload = Vec::new();
    match message {
        ControlMessage::Offer(value) => {
            payload.push(value.minimum_minor_version);
            payload.push(value.maximum_minor_version);
            push_u16(&mut payload, value.video_codecs);
            push_u16(&mut payload, value.audio_codecs);
            push_u16(&mut payload, value.color_formats);
            push_u16(&mut payload, value.max_width);
            push_u16(&mut payload, value.max_height);
            push_u16(&mut payload, value.max_frames_per_second);
            push_u16(&mut payload, value.max_datagram_size);
            push_u32(&mut payload, value.min_bitrate_bits_per_second);
            push_u32(&mut payload, value.initial_bitrate_bits_per_second);
            push_u32(&mut payload, value.max_bitrate_bits_per_second);
            push_u32(&mut payload, value.max_reorder_micros);
            push_u32(&mut payload, value.capabilities);
            push_u64(&mut payload, value.route_id);
            push_u64(&mut payload, value.clock_origin_micros);
            push_u16(&mut payload, value.audio_sample_rates);
            payload.push(value.audio_packet_durations);
            payload.push(value.max_audio_channels);
        }
        ControlMessage::Answer(value) => {
            payload.extend_from_slice(&[
                value.selected_minor_version,
                value.video_codec,
                value.audio_codec,
                value.color_format,
                value.video_profile,
                value.video_level,
                value.audio_channels,
                value.audio_packet_duration_millis,
            ]);
            push_u16(&mut payload, value.width);
            push_u16(&mut payload, value.height);
            push_u16(&mut payload, value.frames_per_second);
            push_u16(&mut payload, value.datagram_size);
            push_u32(&mut payload, value.target_bitrate_bits_per_second);
            push_u32(&mut payload, value.min_bitrate_bits_per_second);
            push_u32(&mut payload, value.max_bitrate_bits_per_second);
            push_u32(&mut payload, value.reorder_window_micros);
            push_u32(&mut payload, value.capabilities);
            push_u32(&mut payload, value.video_stream_id);
            push_u32(&mut payload, value.audio_stream_id);
            push_u64(&mut payload, value.route_id);
            push_u64(&mut payload, value.clock_origin_micros);
            push_u32(&mut payload, value.key_epoch);
            payload.push(value.security as u8);
            payload.extend_from_slice(&[0, 0, 0]);
        }
        ControlMessage::Feedback(value) => {
            for field in [
                value.stream_id,
                value.highest_sequence,
                value.last_complete_frame,
                value.last_presented_frame,
                value.received_packets,
                value.lost_packets,
                value.late_packets,
                value.reordered_packets,
                value.fec_recovered_packets,
                value.receive_bitrate_bits_per_second,
                value.round_trip_micros,
                value.jitter_micros,
                value.assembly_micros,
                value.decode_micros,
                value.presentation_micros,
                value.requested_bitrate_bits_per_second,
            ] {
                push_u32(&mut payload, field);
            }
            push_u16(&mut payload, value.receiver_queue_depth);
            push_u16(&mut payload, value.flags);
        }
        ControlMessage::KeyframeRequest(value) => {
            push_u32(&mut payload, value.stream_id);
            push_u32(&mut payload, value.last_good_frame);
            payload.push(value.reason);
            payload.extend_from_slice(&[0, 0, 0]);
        }
        ControlMessage::Stop(value) => {
            payload.extend_from_slice(&[value.reason as u8, value.scope as u8, 0, 0]);
        }
        ControlMessage::StopAck => {}
    }
    payload
}

// Keeping the fixed-layout table together makes offsets auditable against the
// protocol specification and the Kotlin implementation.
#[allow(clippy::too_many_lines)]
fn decode_payload(
    kind: ControlMessageKind,
    payload: &[u8],
) -> Result<ControlMessage, ControlProtocolError> {
    match kind {
        ControlMessageKind::Offer => {
            require_prefix(payload, 56)?;
            Ok(ControlMessage::Offer(MediaOffer {
                minimum_minor_version: payload[0],
                maximum_minor_version: payload[1],
                video_codecs: read_u16(payload, 2),
                audio_codecs: read_u16(payload, 4),
                color_formats: read_u16(payload, 6),
                max_width: read_u16(payload, 8),
                max_height: read_u16(payload, 10),
                max_frames_per_second: read_u16(payload, 12),
                max_datagram_size: read_u16(payload, 14),
                min_bitrate_bits_per_second: read_u32(payload, 16),
                initial_bitrate_bits_per_second: read_u32(payload, 20),
                max_bitrate_bits_per_second: read_u32(payload, 24),
                max_reorder_micros: read_u32(payload, 28),
                capabilities: read_u32(payload, 32),
                route_id: read_u64(payload, 36),
                clock_origin_micros: read_u64(payload, 44),
                audio_sample_rates: read_u16(payload, 52),
                audio_packet_durations: payload[54],
                max_audio_channels: payload[55],
            }))
        }
        ControlMessageKind::Answer => {
            require_prefix(payload, 68)?;
            if payload[65..68] != [0, 0, 0] {
                return Err(ControlProtocolError::ReservedFieldNotZero);
            }
            Ok(ControlMessage::Answer(MediaAnswer {
                selected_minor_version: payload[0],
                video_codec: payload[1],
                audio_codec: payload[2],
                color_format: payload[3],
                video_profile: payload[4],
                video_level: payload[5],
                audio_channels: payload[6],
                audio_packet_duration_millis: payload[7],
                width: read_u16(payload, 8),
                height: read_u16(payload, 10),
                frames_per_second: read_u16(payload, 12),
                datagram_size: read_u16(payload, 14),
                target_bitrate_bits_per_second: read_u32(payload, 16),
                min_bitrate_bits_per_second: read_u32(payload, 20),
                max_bitrate_bits_per_second: read_u32(payload, 24),
                reorder_window_micros: read_u32(payload, 28),
                capabilities: read_u32(payload, 32),
                video_stream_id: read_u32(payload, 36),
                audio_stream_id: read_u32(payload, 40),
                route_id: read_u64(payload, 44),
                clock_origin_micros: read_u64(payload, 52),
                key_epoch: read_u32(payload, 60),
                security: TransportSecurity::try_from(payload[64])?,
            }))
        }
        ControlMessageKind::Feedback => {
            require_prefix(payload, 68)?;
            Ok(ControlMessage::Feedback(MediaFeedback {
                stream_id: read_u32(payload, 0),
                highest_sequence: read_u32(payload, 4),
                last_complete_frame: read_u32(payload, 8),
                last_presented_frame: read_u32(payload, 12),
                received_packets: read_u32(payload, 16),
                lost_packets: read_u32(payload, 20),
                late_packets: read_u32(payload, 24),
                reordered_packets: read_u32(payload, 28),
                fec_recovered_packets: read_u32(payload, 32),
                receive_bitrate_bits_per_second: read_u32(payload, 36),
                round_trip_micros: read_u32(payload, 40),
                jitter_micros: read_u32(payload, 44),
                assembly_micros: read_u32(payload, 48),
                decode_micros: read_u32(payload, 52),
                presentation_micros: read_u32(payload, 56),
                requested_bitrate_bits_per_second: read_u32(payload, 60),
                receiver_queue_depth: read_u16(payload, 64),
                flags: read_u16(payload, 66),
            }))
        }
        ControlMessageKind::KeyframeRequest => {
            require_prefix(payload, 12)?;
            if payload[9..12] != [0, 0, 0] {
                return Err(ControlProtocolError::ReservedFieldNotZero);
            }
            Ok(ControlMessage::KeyframeRequest(KeyframeRequest {
                stream_id: read_u32(payload, 0),
                last_good_frame: read_u32(payload, 4),
                reason: payload[8],
            }))
        }
        ControlMessageKind::Stop => {
            require_prefix(payload, 4)?;
            if payload[2..4] != [0, 0] {
                return Err(ControlProtocolError::ReservedFieldNotZero);
            }
            Ok(ControlMessage::Stop(StopMessage {
                reason: StopReason::try_from(payload[0])?,
                scope: StopScope::try_from(payload[1])?,
            }))
        }
        ControlMessageKind::StopAck => Ok(ControlMessage::StopAck),
    }
}

fn validate_header(header: ControlHeader) -> Result<(), ControlProtocolError> {
    if header.session_id == 0 || header.session_id > crate::MAX_SIGNED_WIRE_VALUE {
        return Err(ControlProtocolError::InvalidSessionId);
    }
    Ok(())
}

fn validate_message(message: ControlMessage) -> Result<(), ControlProtocolError> {
    match message {
        ControlMessage::Offer(value) => {
            if value.minimum_minor_version > value.maximum_minor_version
                || value.video_codecs == 0
                || value.color_formats == 0
                || value.max_width == 0
                || value.max_height == 0
                || value.max_frames_per_second == 0
                || !(576..=crate::MAX_DATAGRAM_SIZE_WIRE).contains(&value.max_datagram_size)
                || value.min_bitrate_bits_per_second == 0
                || value.min_bitrate_bits_per_second > value.initial_bitrate_bits_per_second
                || value.initial_bitrate_bits_per_second > value.max_bitrate_bits_per_second
                || value.route_id == 0
                || value.route_id > crate::MAX_SIGNED_WIRE_VALUE
                || value.clock_origin_micros > crate::MAX_SIGNED_WIRE_VALUE
                || (value.audio_codecs != 0
                    && (value.audio_sample_rates == 0
                        || value.audio_packet_durations == 0
                        || value.max_audio_channels == 0))
                || (value.audio_codecs == 0
                    && (value.audio_sample_rates != 0
                        || value.audio_packet_durations != 0
                        || value.max_audio_channels != 0))
            {
                return Err(ControlProtocolError::InvalidMessage);
            }
        }
        ControlMessage::Answer(value) => {
            if value.selected_minor_version > CURRENT_MINOR_VERSION
                || value.video_codec == 0
                || value.color_format == 0
                || value.width == 0
                || value.height == 0
                || value.frames_per_second == 0
                || !(576..=crate::MAX_DATAGRAM_SIZE_WIRE).contains(&value.datagram_size)
                || value.min_bitrate_bits_per_second == 0
                || value.min_bitrate_bits_per_second > value.target_bitrate_bits_per_second
                || value.target_bitrate_bits_per_second > value.max_bitrate_bits_per_second
                || value.video_stream_id == 0
                || value.route_id == 0
                || value.route_id > crate::MAX_SIGNED_WIRE_VALUE
                || value.clock_origin_micros > crate::MAX_SIGNED_WIRE_VALUE
                || (value.audio_codec != 0
                    && (value.audio_stream_id == 0
                        || value.audio_channels == 0
                        || value.audio_packet_duration_millis == 0))
                || (value.audio_codec == 0
                    && (value.audio_stream_id != 0
                        || value.audio_channels != 0
                        || value.audio_packet_duration_millis != 0))
            {
                return Err(ControlProtocolError::InvalidMessage);
            }
        }
        ControlMessage::Feedback(value) => {
            if value.stream_id == 0 || value.flags & !feedback_flags::KNOWN != 0 {
                return Err(ControlProtocolError::InvalidMessage);
            }
        }
        ControlMessage::KeyframeRequest(value) => {
            if value.stream_id == 0 {
                return Err(ControlProtocolError::InvalidMessage);
            }
        }
        ControlMessage::Stop(_) | ControlMessage::StopAck => {}
    }
    Ok(())
}

/// Selects the highest mutually supported minor version.
///
/// # Errors
///
/// Returns [`ControlProtocolError::IncompatibleMinorVersion`] when the ranges
/// do not overlap.
pub fn select_minor_version(
    local_minimum: u8,
    local_maximum: u8,
    remote_minimum: u8,
    remote_maximum: u8,
) -> Result<u8, ControlProtocolError> {
    let minimum = local_minimum.max(remote_minimum);
    let maximum = local_maximum.min(remote_maximum);
    (minimum <= maximum)
        .then_some(maximum)
        .ok_or(ControlProtocolError::IncompatibleMinorVersion)
}

/// Validates that an answer only selects values offered by the receiver.
///
/// # Errors
///
/// Returns [`ControlProtocolError::InvalidMessage`] when any selected codec,
/// capability, route, dimension, bitrate, or audio mode is outside the offer.
pub fn validate_answer_for_offer(
    offer: MediaOffer,
    answer: MediaAnswer,
) -> Result<(), ControlProtocolError> {
    validate_message(ControlMessage::Offer(offer))?;
    validate_message(ControlMessage::Answer(answer))?;
    let video_bit = selected_bit(answer.video_codec)?;
    let color_bit = selected_bit(answer.color_format)?;
    let audio_bit = if answer.audio_codec == 0 {
        0
    } else {
        selected_bit(answer.audio_codec)?
    };
    let audio_duration_bit = match answer.audio_packet_duration_millis {
        0 if answer.audio_codec == 0 => 0,
        5 => 1,
        10 => 1 << 1,
        20 => 1 << 2,
        _ => return Err(ControlProtocolError::InvalidMessage),
    };
    if !(offer.minimum_minor_version..=offer.maximum_minor_version)
        .contains(&answer.selected_minor_version)
        || offer.video_codecs & video_bit == 0
        || offer.color_formats & color_bit == 0
        || offer.audio_codecs & audio_bit != audio_bit
        || offer.audio_packet_durations & audio_duration_bit != audio_duration_bit
        || answer.audio_channels > offer.max_audio_channels
        || answer.width > offer.max_width
        || answer.height > offer.max_height
        || answer.frames_per_second > offer.max_frames_per_second
        || answer.datagram_size > offer.max_datagram_size
        || answer.min_bitrate_bits_per_second < offer.min_bitrate_bits_per_second
        || answer.max_bitrate_bits_per_second > offer.max_bitrate_bits_per_second
        || answer.reorder_window_micros > offer.max_reorder_micros
        || answer.capabilities & !offer.capabilities != 0
        || answer.route_id != offer.route_id
    {
        return Err(ControlProtocolError::InvalidMessage);
    }
    Ok(())
}

fn selected_bit(value: u8) -> Result<u16, ControlProtocolError> {
    if !(1..=16).contains(&value) {
        return Err(ControlProtocolError::InvalidMessage);
    }
    Ok(1_u16 << (value - 1))
}

fn require_prefix(payload: &[u8], length: usize) -> Result<(), ControlProtocolError> {
    if payload.len() < length {
        Err(ControlProtocolError::InvalidLength)
    } else {
        Ok(())
    }
}

fn push_u16(target: &mut Vec<u8>, value: u16) {
    target.extend_from_slice(&value.to_be_bytes());
}

fn push_u32(target: &mut Vec<u8>, value: u32) {
    target.extend_from_slice(&value.to_be_bytes());
}

fn push_u64(target: &mut Vec<u8>, value: u64) {
    target.extend_from_slice(&value.to_be_bytes());
}

fn read_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_be_bytes([bytes[offset], bytes[offset + 1]])
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("validated prefix"),
    )
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_be_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("validated prefix"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header() -> ControlHeader {
        ControlHeader {
            minor_version: 0,
            session_id: 0x0102_0304_0506_0708,
            request_id: 0x1112_1314,
        }
    }

    fn offer() -> MediaOffer {
        MediaOffer {
            minimum_minor_version: 0,
            maximum_minor_version: 0,
            video_codecs: video_codecs::H264,
            audio_codecs: audio_codecs::OPUS,
            color_formats: color_formats::BT709_SDR,
            max_width: 1_280,
            max_height: 720,
            max_frames_per_second: 60,
            max_datagram_size: 1_200,
            min_bitrate_bits_per_second: 500_000,
            initial_bitrate_bits_per_second: 8_000_000,
            max_bitrate_bits_per_second: 12_000_000,
            max_reorder_micros: 10_000,
            capabilities: 0x0f,
            route_id: 0x2122_2324_2526_2728,
            clock_origin_micros: 0x3132_3334_3536_3738,
            audio_sample_rates: 1,
            audio_packet_durations: 7,
            max_audio_channels: 2,
        }
    }

    #[test]
    fn offer_golden_vector_round_trips() {
        let message = ControlMessage::Offer(offer());
        let bytes = encode_control_message(header(), message).unwrap();
        let expected = decode_hex(
            "42504d430100011c0000000001020304050607081112131400380000\
             0000000100010001050002d0003c04b00007a120007a120000b71b00\
             000027100000000f2122232425262728313233343536373800010702",
        );
        assert_eq!(bytes, expected);
        assert_eq!(decode_control_message(&bytes).unwrap().message, message);
        assert_eq!(bytes.len(), CONTROL_HEADER_SIZE + 56);
    }

    #[test]
    fn newer_minor_accepts_known_prefix_and_ignores_payload_extension() {
        let mut bytes = encode_control_message(header(), ControlMessage::Offer(offer())).unwrap();
        bytes[5] = 4;
        bytes[24..26].copy_from_slice(&58_u16.to_be_bytes());
        bytes.extend_from_slice(&[0xaa, 0xbb]);
        let decoded = decode_control_message(&bytes).unwrap();
        assert_eq!(decoded.header.minor_version, 4);
        assert_eq!(decoded.message, ControlMessage::Offer(offer()));
    }

    #[test]
    fn incompatible_minor_ranges_are_rejected() {
        assert_eq!(
            select_minor_version(0, 1, 2, 3),
            Err(ControlProtocolError::IncompatibleMinorVersion)
        );
        assert_eq!(select_minor_version(0, 3, 1, 2), Ok(2));
    }

    #[test]
    fn malformed_lengths_and_feedback_flags_are_rejected() {
        let mut bytes = encode_control_message(header(), ControlMessage::Offer(offer())).unwrap();
        bytes.pop();
        assert_eq!(
            decode_control_message(&bytes),
            Err(ControlProtocolError::InvalidLength)
        );

        let feedback = ControlMessage::Feedback(MediaFeedback {
            stream_id: 1,
            highest_sequence: 0,
            last_complete_frame: 0,
            last_presented_frame: 0,
            received_packets: 0,
            lost_packets: 0,
            late_packets: 0,
            reordered_packets: 0,
            fec_recovered_packets: 0,
            receive_bitrate_bits_per_second: 0,
            round_trip_micros: 0,
            jitter_micros: 0,
            assembly_micros: 0,
            decode_micros: 0,
            presentation_micros: 0,
            requested_bitrate_bits_per_second: 0,
            receiver_queue_depth: 0,
            flags: 0x8000,
        });
        assert_eq!(
            encode_control_message(header(), feedback),
            Err(ControlProtocolError::InvalidMessage)
        );
    }

    #[test]
    fn answer_cannot_select_unoffered_capabilities() {
        let valid = MediaAnswer {
            selected_minor_version: 0,
            video_codec: 1,
            audio_codec: 1,
            color_format: 1,
            video_profile: 100,
            video_level: 31,
            audio_channels: 2,
            audio_packet_duration_millis: 10,
            width: 1_280,
            height: 720,
            frames_per_second: 60,
            datagram_size: 1_200,
            target_bitrate_bits_per_second: 8_000_000,
            min_bitrate_bits_per_second: 500_000,
            max_bitrate_bits_per_second: 12_000_000,
            reorder_window_micros: 10_000,
            capabilities: 0x0f,
            video_stream_id: 1,
            audio_stream_id: 2,
            route_id: offer().route_id,
            clock_origin_micros: 0,
            key_epoch: 1,
            security: TransportSecurity::TransportTls13,
        };
        assert_eq!(validate_answer_for_offer(offer(), valid), Ok(()));
        assert_eq!(
            validate_answer_for_offer(
                offer(),
                MediaAnswer {
                    video_codec: 2,
                    ..valid
                }
            ),
            Err(ControlProtocolError::InvalidMessage)
        );
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
