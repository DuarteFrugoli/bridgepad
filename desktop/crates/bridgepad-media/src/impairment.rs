//! Deterministic network impairment used by protocol and transport tests.

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ImpairmentConfig {
    /// Drop every Nth submitted datagram. Zero disables loss.
    pub drop_every: usize,
    /// Duplicate every Nth submitted datagram. Zero disables duplication.
    pub duplicate_every: usize,
    /// Applied cyclically and added to the submitted timestamp.
    pub delay_pattern_micros: Vec<u64>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ScheduledDatagram {
    pub deliver_at_micros: u64,
    pub bytes: Vec<u8>,
    insertion_order: u64,
}

pub struct DeterministicImpairment {
    config: ImpairmentConfig,
    submitted: usize,
    insertion_order: u64,
    scheduled: Vec<ScheduledDatagram>,
}

impl DeterministicImpairment {
    #[must_use]
    pub const fn new(config: ImpairmentConfig) -> Self {
        Self {
            config,
            submitted: 0,
            insertion_order: 0,
            scheduled: Vec::new(),
        }
    }

    pub fn submit(&mut self, sent_at_micros: u64, bytes: Vec<u8>) {
        self.submitted += 1;
        if self.config.drop_every != 0 && self.submitted.is_multiple_of(self.config.drop_every) {
            return;
        }
        let delay = if self.config.delay_pattern_micros.is_empty() {
            0
        } else {
            self.config.delay_pattern_micros
                [(self.submitted - 1) % self.config.delay_pattern_micros.len()]
        };
        self.schedule(sent_at_micros.saturating_add(delay), bytes.clone());
        if self.config.duplicate_every != 0
            && self.submitted.is_multiple_of(self.config.duplicate_every)
        {
            self.schedule(
                sent_at_micros.saturating_add(delay).saturating_add(1),
                bytes,
            );
        }
    }

    #[must_use]
    pub fn drain_ready(&mut self, now_micros: u64) -> Vec<ScheduledDatagram> {
        self.scheduled
            .sort_by_key(|packet| (packet.deliver_at_micros, packet.insertion_order));
        let ready_count = self
            .scheduled
            .partition_point(|packet| packet.deliver_at_micros <= now_micros);
        self.scheduled.drain(..ready_count).collect()
    }

    #[must_use]
    pub fn finish(mut self) -> Vec<ScheduledDatagram> {
        self.scheduled
            .sort_by_key(|packet| (packet.deliver_at_micros, packet.insertion_order));
        self.scheduled
    }

    fn schedule(&mut self, deliver_at_micros: u64, bytes: Vec<u8>) {
        let packet = ScheduledDatagram {
            deliver_at_micros,
            bytes,
            insertion_order: self.insertion_order,
        };
        self.insertion_order = self.insertion_order.wrapping_add(1);
        self.scheduled.push(packet);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loss_duplication_delay_and_reordering_are_reproducible() {
        let config = ImpairmentConfig {
            drop_every: 3,
            duplicate_every: 2,
            delay_pattern_micros: vec![30, 0, 20, 0],
        };
        let mut network = DeterministicImpairment::new(config);
        for value in 1..=4 {
            network.submit(100, vec![value]);
        }
        let delivered: Vec<(u64, u8)> = network
            .finish()
            .into_iter()
            .map(|packet| (packet.deliver_at_micros, packet.bytes[0]))
            .collect();
        assert_eq!(
            delivered,
            vec![(100, 2), (100, 4), (101, 2), (101, 4), (130, 1)]
        );
    }
}
