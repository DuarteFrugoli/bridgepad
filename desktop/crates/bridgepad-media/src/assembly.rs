//! Bounded packetization and frame assembly for transport-independent tests.

use std::collections::{BTreeMap, VecDeque};
use std::fmt;

use bridgepad_media_protocol::{
    MAX_PAYLOAD_SIZE, MediaDatagram, MediaDatagramHeader, PacketFlags, PacketKind, ProtocolError,
    decode_datagram, encode_datagram,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PacketizeRequest<'a> {
    pub kind: PacketKind,
    /// Boundary and FEC flags are supplied by the packetizer.
    pub flags: PacketFlags,
    pub session_id: u64,
    pub stream_id: u32,
    pub first_sequence: u32,
    pub frame_id: u32,
    pub presentation_timestamp_micros: u64,
    pub payload: &'a [u8],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PacketizeError {
    EmptyFrame,
    FrameTooLarge,
    ReservedFlags,
    Protocol(ProtocolError),
}

impl fmt::Display for PacketizeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for PacketizeError {}

/// Splits one encoded unit into complete `BridgePad` Media v1 datagrams.
///
/// # Errors
///
/// Returns [`PacketizeError`] for an empty or oversized unit, caller-supplied
/// boundary/FEC flags, or invalid session metadata.
pub fn packetize(request: PacketizeRequest<'_>) -> Result<Vec<Vec<u8>>, PacketizeError> {
    if request.payload.is_empty() {
        return Err(PacketizeError::EmptyFrame);
    }
    let packet_count = request.payload.len().div_ceil(MAX_PAYLOAD_SIZE);
    let packet_count = u16::try_from(packet_count).map_err(|_| PacketizeError::FrameTooLarge)?;
    let original_frame_bytes =
        u32::try_from(request.payload.len()).map_err(|_| PacketizeError::FrameTooLarge)?;
    let reserved = PacketFlags::FRAME_START | PacketFlags::FRAME_END | PacketFlags::FEC_REPAIR;
    if request.flags.bits() & reserved.bits() != 0 {
        return Err(PacketizeError::ReservedFlags);
    }

    request
        .payload
        .chunks(MAX_PAYLOAD_SIZE)
        .enumerate()
        .map(|(index, payload)| {
            let index = u16::try_from(index).map_err(|_| PacketizeError::FrameTooLarge)?;
            let mut flags = request.flags;
            if index == 0 {
                flags = flags | PacketFlags::FRAME_START;
            }
            if index + 1 == packet_count {
                flags = flags | PacketFlags::FRAME_END;
            }
            encode_datagram(
                MediaDatagramHeader {
                    kind: request.kind,
                    flags,
                    session_id: request.session_id,
                    stream_id: request.stream_id,
                    sequence: request.first_sequence.wrapping_add(u32::from(index)),
                    frame_id: request.frame_id,
                    presentation_timestamp_micros: request.presentation_timestamp_micros,
                    frame_packet_index: index,
                    frame_packet_count: packet_count,
                    original_frame_bytes,
                    fec_block_index: 0,
                    fec_shard_index: 0,
                    fec_source_count: 0,
                    fec_repair_count: 0,
                },
                payload,
            )
            .map_err(PacketizeError::Protocol)
        })
        .collect()
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AssembledFrame {
    pub kind: PacketKind,
    pub flags: PacketFlags,
    pub frame_id: u32,
    pub presentation_timestamp_micros: u64,
    pub payload: Vec<u8>,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct AssemblyMetrics {
    pub accepted_packets: u64,
    pub duplicate_packets: u64,
    pub expired_frames: u64,
    pub capacity_dropped_frames: u64,
    pub completed_frames: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AssemblyError {
    Protocol(ProtocolError),
    WrongSession,
    WrongStream,
    FecNotImplemented,
    InconsistentFrameMetadata,
    ReassembledSizeMismatch,
}

impl fmt::Display for AssemblyError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for AssemblyError {}

struct IncompleteFrame {
    kind: PacketKind,
    common_flags: PacketFlags,
    presentation_timestamp_micros: u64,
    original_frame_bytes: u32,
    expires_at_micros: u64,
    packets: Vec<Option<Vec<u8>>>,
    received: usize,
}

pub struct FrameAssembler {
    session_id: u64,
    stream_id: u32,
    deadline_micros: u64,
    max_in_flight_frames: usize,
    frames: BTreeMap<u32, IncompleteFrame>,
    insertion_order: VecDeque<u32>,
    metrics: AssemblyMetrics,
}

impl FrameAssembler {
    /// Creates a bounded assembler for one authenticated media stream.
    ///
    /// # Panics
    ///
    /// Panics when `deadline_micros` or `max_in_flight_frames` is zero.
    #[must_use]
    pub fn new(
        session_id: u64,
        stream_id: u32,
        deadline_micros: u64,
        max_in_flight_frames: usize,
    ) -> Self {
        assert!(deadline_micros > 0);
        assert!(max_in_flight_frames > 0);
        Self {
            session_id,
            stream_id,
            deadline_micros,
            max_in_flight_frames,
            frames: BTreeMap::new(),
            insertion_order: VecDeque::new(),
            metrics: AssemblyMetrics::default(),
        }
    }

    /// Accepts one complete datagram and returns a frame only when every source
    /// packet arrived before its deadline.
    ///
    /// # Errors
    ///
    /// Returns [`AssemblyError`] for malformed packets, packets from another
    /// stream/session, unsupported FEC, or inconsistent frame metadata.
    pub fn push(
        &mut self,
        bytes: &[u8],
        received_at_micros: u64,
    ) -> Result<Option<AssembledFrame>, AssemblyError> {
        self.expire(received_at_micros);
        let datagram = decode_datagram(bytes).map_err(AssemblyError::Protocol)?;
        self.validate_route(datagram)?;
        if datagram.header.fec_source_count != 0 || datagram.header.fec_repair_count != 0 {
            return Err(AssemblyError::FecNotImplemented);
        }

        let frame_id = datagram.header.frame_id;
        if !self.frames.contains_key(&frame_id) {
            self.make_room();
            self.insertion_order.push_back(frame_id);
            self.frames.insert(
                frame_id,
                IncompleteFrame {
                    kind: datagram.header.kind,
                    common_flags: common_flags(datagram.header.flags),
                    presentation_timestamp_micros: datagram.header.presentation_timestamp_micros,
                    original_frame_bytes: datagram.header.original_frame_bytes,
                    expires_at_micros: received_at_micros.saturating_add(self.deadline_micros),
                    packets: vec![None; usize::from(datagram.header.frame_packet_count)],
                    received: 0,
                },
            );
        }

        let Some(frame) = self.frames.get_mut(&frame_id) else {
            return Err(AssemblyError::InconsistentFrameMetadata);
        };
        if frame.kind != datagram.header.kind
            || frame.common_flags != common_flags(datagram.header.flags)
            || frame.presentation_timestamp_micros != datagram.header.presentation_timestamp_micros
            || frame.original_frame_bytes != datagram.header.original_frame_bytes
            || frame.packets.len() != usize::from(datagram.header.frame_packet_count)
        {
            self.remove(frame_id);
            return Err(AssemblyError::InconsistentFrameMetadata);
        }

        let index = usize::from(datagram.header.frame_packet_index);
        if frame.packets[index].is_some() {
            self.metrics.duplicate_packets = self.metrics.duplicate_packets.saturating_add(1);
            return Ok(None);
        }
        frame.packets[index] = Some(datagram.payload.to_vec());
        frame.received += 1;
        self.metrics.accepted_packets = self.metrics.accepted_packets.saturating_add(1);
        if frame.received != frame.packets.len() {
            return Ok(None);
        }

        let Some(frame) = self.frames.remove(&frame_id) else {
            return Err(AssemblyError::InconsistentFrameMetadata);
        };
        self.remove_from_order(frame_id);
        let mut payload = Vec::with_capacity(frame.original_frame_bytes as usize);
        for packet in frame.packets {
            let Some(packet) = packet else {
                return Err(AssemblyError::InconsistentFrameMetadata);
            };
            payload.extend(packet);
        }
        if payload.len() != frame.original_frame_bytes as usize {
            return Err(AssemblyError::ReassembledSizeMismatch);
        }
        self.metrics.completed_frames = self.metrics.completed_frames.saturating_add(1);
        Ok(Some(AssembledFrame {
            kind: frame.kind,
            flags: frame.common_flags,
            frame_id,
            presentation_timestamp_micros: frame.presentation_timestamp_micros,
            payload,
        }))
    }

    pub fn expire(&mut self, now_micros: u64) {
        let expired: Vec<u32> = self
            .frames
            .iter()
            .filter_map(|(&frame_id, frame)| {
                (frame.expires_at_micros <= now_micros).then_some(frame_id)
            })
            .collect();
        for frame_id in expired {
            self.remove(frame_id);
            self.metrics.expired_frames = self.metrics.expired_frames.saturating_add(1);
        }
    }

    #[must_use]
    pub const fn metrics(&self) -> AssemblyMetrics {
        self.metrics
    }

    fn validate_route(&self, datagram: MediaDatagram<'_>) -> Result<(), AssemblyError> {
        if datagram.header.session_id != self.session_id {
            return Err(AssemblyError::WrongSession);
        }
        if datagram.header.stream_id != self.stream_id {
            return Err(AssemblyError::WrongStream);
        }
        Ok(())
    }

    fn make_room(&mut self) {
        while self.frames.len() >= self.max_in_flight_frames {
            if let Some(frame_id) = self.insertion_order.pop_front() {
                if self.frames.remove(&frame_id).is_some() {
                    self.metrics.capacity_dropped_frames =
                        self.metrics.capacity_dropped_frames.saturating_add(1);
                }
            } else {
                break;
            }
        }
    }

    fn remove(&mut self, frame_id: u32) {
        self.frames.remove(&frame_id);
        self.remove_from_order(frame_id);
    }

    fn remove_from_order(&mut self, frame_id: u32) {
        if let Some(position) = self.insertion_order.iter().position(|id| *id == frame_id) {
            self.insertion_order.remove(position);
        }
    }
}

fn common_flags(flags: PacketFlags) -> PacketFlags {
    PacketFlags::from_bits(
        flags.bits() & !(PacketFlags::FRAME_START | PacketFlags::FRAME_END).bits(),
    )
    .expect("removing known flags remains valid")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::impairment::{DeterministicImpairment, ImpairmentConfig};

    fn request(payload: &[u8], frame_id: u32) -> PacketizeRequest<'_> {
        PacketizeRequest {
            kind: PacketKind::Video,
            flags: PacketFlags::KEYFRAME,
            session_id: 7,
            stream_id: 9,
            first_sequence: 10,
            frame_id,
            presentation_timestamp_micros: 1_000,
            payload,
        }
    }

    #[test]
    fn reordered_packets_reassemble_without_backlog() {
        let payload = vec![0x5a; MAX_PAYLOAD_SIZE * 2 + 17];
        let packets = packetize(request(&payload, 1)).unwrap();
        let mut assembler = FrameAssembler::new(7, 9, 10_000, 2);
        assert!(assembler.push(&packets[2], 100).unwrap().is_none());
        assert!(assembler.push(&packets[0], 200).unwrap().is_none());
        let frame = assembler.push(&packets[1], 300).unwrap().unwrap();
        assert_eq!(frame.payload, payload);
        assert_eq!(assembler.metrics().completed_frames, 1);
    }

    #[test]
    fn duplicate_and_expired_frames_are_bounded() {
        let payload = vec![1; MAX_PAYLOAD_SIZE + 1];
        let packets = packetize(request(&payload, 2)).unwrap();
        let mut assembler = FrameAssembler::new(7, 9, 1_000, 1);
        assert!(assembler.push(&packets[0], 100).unwrap().is_none());
        assert!(assembler.push(&packets[0], 200).unwrap().is_none());
        assembler.expire(1_100);
        assert_eq!(assembler.metrics().duplicate_packets, 1);
        assert_eq!(assembler.metrics().expired_frames, 1);
    }

    #[test]
    fn deterministic_jitter_reorders_but_preserves_a_complete_frame() {
        let payload = vec![0x42; MAX_PAYLOAD_SIZE * 2 + 7];
        let packets = packetize(request(&payload, 3)).unwrap();
        let mut network = DeterministicImpairment::new(ImpairmentConfig {
            drop_every: 0,
            duplicate_every: 2,
            delay_pattern_micros: vec![3_000, 1_000, 2_000],
        });
        for packet in packets {
            network.submit(10_000, packet);
        }
        let mut assembler = FrameAssembler::new(7, 9, 10_000, 2);
        let mut completed = None;
        for packet in network.finish() {
            if let Some(frame) = assembler
                .push(&packet.bytes, packet.deliver_at_micros)
                .unwrap()
            {
                completed = Some(frame);
            }
        }
        assert_eq!(completed.unwrap().payload, payload);
        assert_eq!(assembler.metrics().duplicate_packets, 1);
    }
}
