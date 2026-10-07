//! Bounded, transport-independent media pipeline metrics.

use std::collections::VecDeque;

pub const STAGE_COUNT: usize = 10;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(usize)]
pub enum MetricStage {
    Capture = 0,
    Convert = 1,
    Encode = 2,
    Packetize = 3,
    Send = 4,
    Receive = 5,
    Assemble = 6,
    DecoderWait = 7,
    Decode = 8,
    Present = 9,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct LatencySummary {
    pub samples: u64,
    pub minimum_micros: u32,
    pub maximum_micros: u32,
    pub p50_micros: u32,
    pub p95_micros: u32,
    pub p99_micros: u32,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct MediaCounters {
    pub captured_frames: u64,
    pub encoded_frames: u64,
    pub sent_packets: u64,
    pub sent_bytes: u64,
    pub received_packets: u64,
    pub received_bytes: u64,
    pub completed_frames: u64,
    pub dropped_frames: u64,
    pub duplicate_packets: u64,
    pub late_packets: u64,
    pub reordered_packets: u64,
    pub fec_recovered_packets: u64,
    pub audio_underruns: u64,
}

#[derive(Clone, Debug)]
struct RollingLatency {
    capacity: usize,
    total_samples: u64,
    samples: VecDeque<u32>,
}

impl RollingLatency {
    fn new(capacity: usize) -> Self {
        Self {
            capacity,
            total_samples: 0,
            samples: VecDeque::with_capacity(capacity),
        }
    }

    fn record(&mut self, micros: u32) {
        self.total_samples = self.total_samples.saturating_add(1);
        if self.samples.len() == self.capacity {
            self.samples.pop_front();
        }
        self.samples.push_back(micros);
    }

    fn summary(&self) -> LatencySummary {
        if self.samples.is_empty() {
            return LatencySummary::default();
        }
        let mut sorted: Vec<u32> = self.samples.iter().copied().collect();
        sorted.sort_unstable();
        LatencySummary {
            samples: self.total_samples,
            minimum_micros: sorted[0],
            maximum_micros: sorted[sorted.len() - 1],
            p50_micros: percentile(&sorted, 50),
            p95_micros: percentile(&sorted, 95),
            p99_micros: percentile(&sorted, 99),
        }
    }
}

#[derive(Clone, Debug)]
pub struct MediaMetrics {
    stages: [RollingLatency; STAGE_COUNT],
    pub counters: MediaCounters,
}

impl MediaMetrics {
    /// Creates a rolling collector that retains at most `capacity_per_stage`
    /// latency samples for each stage.
    ///
    /// # Panics
    ///
    /// Panics when `capacity_per_stage` is zero.
    #[must_use]
    pub fn new(capacity_per_stage: usize) -> Self {
        assert!(capacity_per_stage > 0);
        Self {
            stages: std::array::from_fn(|_| RollingLatency::new(capacity_per_stage)),
            counters: MediaCounters::default(),
        }
    }

    pub fn record(&mut self, stage: MetricStage, micros: u32) {
        self.stages[stage as usize].record(micros);
    }

    #[must_use]
    pub fn summary(&self, stage: MetricStage) -> LatencySummary {
        self.stages[stage as usize].summary()
    }

    #[must_use]
    pub fn snapshot(&self) -> [LatencySummary; STAGE_COUNT] {
        std::array::from_fn(|index| self.stages[index].summary())
    }
}

fn percentile(sorted: &[u32], percentile: usize) -> u32 {
    let rank = sorted.len().saturating_mul(percentile).div_ceil(100);
    sorted[rank.saturating_sub(1).min(sorted.len() - 1)]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rolling_window_is_bounded_and_reports_reproducible_percentiles() {
        let mut metrics = MediaMetrics::new(100);
        for micros in 1..=150 {
            metrics.record(MetricStage::Decode, micros);
        }
        let summary = metrics.summary(MetricStage::Decode);
        assert_eq!(summary.samples, 150);
        assert_eq!(summary.minimum_micros, 51);
        assert_eq!(summary.maximum_micros, 150);
        assert_eq!(summary.p50_micros, 100);
        assert_eq!(summary.p95_micros, 145);
        assert_eq!(summary.p99_micros, 149);
    }

    #[test]
    fn empty_stages_are_explicitly_zero() {
        let metrics = MediaMetrics::new(8);
        assert_eq!(
            metrics.summary(MetricStage::Present),
            LatencySummary::default()
        );
        assert_eq!(metrics.snapshot().len(), STAGE_COUNT);
    }
}
