//! UDP-only H.264 recovery. Normal playback uses relaxed recovery: after a
//! network gap it requests an IDR but keeps presenting complete access units.
//! Strict recovery remains available only for the A/B diagnostic. A decoder
//! failure still requires an IDR, and stale units are never shown.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RecoveryMode {
    Strict,
    Relaxed,
}

impl RecoveryMode {
    pub(crate) fn from_diagnostic_flag(flag: i32, normal_mode: Self) -> Option<Self> {
        match flag {
            -1 => Some(normal_mode),
            0 => Some(Self::Strict),
            1 => Some(Self::Relaxed),
            _ => None,
        }
    }
}

pub(crate) struct TestLossInjector {
    interval: u32,
    eligible_frames: u32,
    dropped_frames: u64,
}

impl TestLossInjector {
    pub(crate) fn new(interval: u32) -> Self {
        Self {
            interval,
            eligible_frames: 0,
            dropped_frames: 0,
        }
    }

    pub(crate) fn should_drop(&mut self, keyframe: bool, awaiting_keyframe: bool) -> bool {
        if self.interval == 0 || keyframe || awaiting_keyframe {
            return false;
        }
        self.eligible_frames += 1;
        if self.eligible_frames < self.interval {
            return false;
        }
        self.eligible_frames = 0;
        self.dropped_frames += 1;
        true
    }

    pub(crate) fn dropped_frames(&self) -> u64 {
        self.dropped_frames
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum VideoDecision {
    Deliver,
    Drop,
    RecoverAndDrop,
    RecoverAndDeliver,
    RequestAndDeliver,
}

pub(crate) struct UdpVideoRecovery {
    mode: RecoveryMode,
    last_delivered: Option<u32>,
    awaiting_keyframe: bool,
    holding_for_keyframe: bool,
}

impl UdpVideoRecovery {
    pub(crate) fn new(mode: RecoveryMode) -> Self {
        Self {
            mode,
            last_delivered: None,
            awaiting_keyframe: true,
            holding_for_keyframe: true,
        }
    }

    pub(crate) fn awaiting_keyframe(&self) -> bool {
        self.awaiting_keyframe
    }

    pub(crate) fn holding_for_keyframe(&self) -> bool {
        self.holding_for_keyframe
    }

    pub(crate) fn is_stale(&self, frame_id: u32) -> bool {
        self.last_delivered.is_some_and(|last| {
            let advance = frame_id.wrapping_sub(last);
            advance == 0 || advance >= 0x8000_0000
        })
    }

    /// Returns true only when this network loss starts a new recovery period.
    pub(crate) fn network_frame_lost(&mut self) -> bool {
        if self.awaiting_keyframe {
            return false;
        }
        self.awaiting_keyframe = true;
        self.holding_for_keyframe = self.mode == RecoveryMode::Strict;
        true
    }

    /// Decoder overflow or restart is not safe to handle permissively.
    pub(crate) fn decoder_needs_keyframe(&mut self) -> bool {
        if self.holding_for_keyframe {
            return false;
        }
        self.awaiting_keyframe = true;
        self.holding_for_keyframe = true;
        true
    }

    pub(crate) fn consider(&mut self, frame_id: u32, keyframe: bool) -> VideoDecision {
        let mut discontinuity = false;
        if let Some(last) = self.last_delivered {
            let advance = frame_id.wrapping_sub(last);
            if advance == 0 || advance >= 0x8000_0000 {
                return VideoDecision::Drop;
            }
            if advance > 1 && !self.awaiting_keyframe {
                self.awaiting_keyframe = true;
                self.holding_for_keyframe = self.mode == RecoveryMode::Strict;
                discontinuity = true;
            }
        }
        if self.holding_for_keyframe && !keyframe {
            return if discontinuity {
                VideoDecision::RecoverAndDrop
            } else {
                VideoDecision::Drop
            };
        }
        if keyframe {
            self.awaiting_keyframe = false;
            self.holding_for_keyframe = false;
        }
        self.last_delivered = Some(frame_id);
        if discontinuity {
            if self.mode == RecoveryMode::Strict {
                VideoDecision::RecoverAndDeliver
            } else {
                VideoDecision::RequestAndDeliver
            }
        } else {
            VideoDecision::Deliver
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{RecoveryMode, TestLossInjector, UdpVideoRecovery, VideoDecision};

    #[test]
    fn missing_frame_discards_dependent_units_until_keyframe() {
        let mut recovery = UdpVideoRecovery::new(RecoveryMode::Strict);
        assert_eq!(recovery.consider(10, false), VideoDecision::Drop);
        assert_eq!(recovery.consider(10, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(11, false), VideoDecision::Deliver);
        assert_eq!(recovery.consider(13, false), VideoDecision::RecoverAndDrop);
        assert!(recovery.awaiting_keyframe());
        assert_eq!(recovery.consider(14, false), VideoDecision::Drop);
        assert_eq!(recovery.consider(15, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(16, false), VideoDecision::Deliver);
    }

    #[test]
    fn assembler_loss_and_reordered_older_frame_do_not_reappear() {
        let mut recovery = UdpVideoRecovery::new(RecoveryMode::Strict);
        assert_eq!(recovery.consider(1, true), VideoDecision::Deliver);
        assert!(recovery.network_frame_lost());
        assert!(!recovery.network_frame_lost());
        assert_eq!(recovery.consider(2, false), VideoDecision::Drop);
        assert_eq!(recovery.consider(3, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(2, true), VideoDecision::Drop);
    }

    #[test]
    fn missing_frame_followed_by_keyframe_flushes_old_output() {
        let mut recovery = UdpVideoRecovery::new(RecoveryMode::Strict);
        assert_eq!(recovery.consider(5, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(7, true), VideoDecision::RecoverAndDeliver);
        assert_eq!(recovery.consider(8, false), VideoDecision::Deliver);
    }

    #[test]
    fn frame_id_wrap_preserves_order() {
        let mut recovery = UdpVideoRecovery::new(RecoveryMode::Strict);
        assert_eq!(recovery.consider(u32::MAX, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(0, false), VideoDecision::Deliver);
        assert_eq!(recovery.consider(u32::MAX, false), VideoDecision::Drop);
        assert!(recovery.is_stale(u32::MAX));
        assert!(!recovery.is_stale(1));
    }

    #[test]
    fn relaxed_gap_delivers_complete_frames_while_requesting_idr() {
        let mut recovery = UdpVideoRecovery::new(RecoveryMode::Relaxed);
        assert_eq!(recovery.consider(10, false), VideoDecision::Drop);
        assert_eq!(recovery.consider(10, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(11, false), VideoDecision::Deliver);
        assert_eq!(
            recovery.consider(13, false),
            VideoDecision::RequestAndDeliver
        );
        assert!(recovery.awaiting_keyframe());
        assert!(!recovery.holding_for_keyframe());
        assert_eq!(recovery.consider(14, false), VideoDecision::Deliver);
        assert_eq!(recovery.consider(12, false), VideoDecision::Drop);
        assert_eq!(recovery.consider(15, true), VideoDecision::Deliver);
        assert!(!recovery.awaiting_keyframe());
    }

    #[test]
    fn relaxed_assembler_loss_keeps_delivering_until_idr() {
        let mut recovery = UdpVideoRecovery::new(RecoveryMode::Relaxed);
        assert_eq!(recovery.consider(1, true), VideoDecision::Deliver);
        assert!(recovery.network_frame_lost());
        assert!(!recovery.network_frame_lost());
        assert_eq!(recovery.consider(3, false), VideoDecision::Deliver);
        assert!(recovery.awaiting_keyframe());
        assert_eq!(recovery.consider(4, true), VideoDecision::Deliver);
        assert!(!recovery.awaiting_keyframe());
    }

    #[test]
    fn relaxed_decoder_overflow_still_holds_until_idr() {
        let mut recovery = UdpVideoRecovery::new(RecoveryMode::Relaxed);
        assert_eq!(recovery.consider(1, true), VideoDecision::Deliver);
        assert!(recovery.network_frame_lost());
        assert!(recovery.decoder_needs_keyframe());
        assert!(recovery.holding_for_keyframe());
        assert_eq!(recovery.consider(3, false), VideoDecision::Drop);
        assert_eq!(recovery.consider(4, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(5, false), VideoDecision::Deliver);
    }

    #[test]
    fn diagnostic_loss_skips_only_periodic_delta_frames() {
        let mut injector = TestLossInjector::new(3);
        assert!(!injector.should_drop(true, false));
        assert!(!injector.should_drop(false, true));
        assert!(!injector.should_drop(false, false));
        assert!(!injector.should_drop(false, false));
        assert!(injector.should_drop(false, false));
        assert_eq!(injector.dropped_frames(), 1);
        assert!(!injector.should_drop(false, false));
        assert_eq!(injector.dropped_frames(), 1);
    }

    #[test]
    fn normal_session_never_injects_loss() {
        let mut injector = TestLossInjector::new(0);
        for _ in 0..1000 {
            assert!(!injector.should_drop(false, false));
        }
        assert_eq!(injector.dropped_frames(), 0);
    }
}
