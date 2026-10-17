//! UDP-only H.264 recovery. A missing access unit invalidates dependent
//! pictures until an IDR arrives; a complete but older reordered unit must
//! never be presented after a newer one.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum VideoDecision {
    Deliver,
    Drop,
    RecoverAndDrop,
    RecoverAndDeliver,
}

pub(crate) struct UdpVideoRecovery {
    last_delivered: Option<u32>,
    awaiting_keyframe: bool,
}

impl UdpVideoRecovery {
    pub(crate) fn new() -> Self {
        Self {
            last_delivered: None,
            awaiting_keyframe: true,
        }
    }

    pub(crate) fn awaiting_keyframe(&self) -> bool {
        self.awaiting_keyframe
    }

    pub(crate) fn is_stale(&self, frame_id: u32) -> bool {
        self.last_delivered.is_some_and(|last| {
            let advance = frame_id.wrapping_sub(last);
            advance == 0 || advance >= 0x8000_0000
        })
    }

    /// Returns true only when this loss starts a new recovery period.
    pub(crate) fn frame_lost(&mut self) -> bool {
        if self.awaiting_keyframe {
            return false;
        }
        self.awaiting_keyframe = true;
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
                discontinuity = true;
            }
        }
        if self.awaiting_keyframe && !keyframe {
            return if discontinuity {
                VideoDecision::RecoverAndDrop
            } else {
                VideoDecision::Drop
            };
        }
        self.awaiting_keyframe = false;
        self.last_delivered = Some(frame_id);
        if discontinuity {
            VideoDecision::RecoverAndDeliver
        } else {
            VideoDecision::Deliver
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{UdpVideoRecovery, VideoDecision};

    #[test]
    fn missing_frame_discards_dependent_units_until_keyframe() {
        let mut recovery = UdpVideoRecovery::new();
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
        let mut recovery = UdpVideoRecovery::new();
        assert_eq!(recovery.consider(1, true), VideoDecision::Deliver);
        assert!(recovery.frame_lost());
        assert!(!recovery.frame_lost());
        assert_eq!(recovery.consider(2, false), VideoDecision::Drop);
        assert_eq!(recovery.consider(3, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(2, true), VideoDecision::Drop);
    }

    #[test]
    fn missing_frame_followed_by_keyframe_flushes_old_output() {
        let mut recovery = UdpVideoRecovery::new();
        assert_eq!(recovery.consider(5, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(7, true), VideoDecision::RecoverAndDeliver);
        assert_eq!(recovery.consider(8, false), VideoDecision::Deliver);
    }

    #[test]
    fn frame_id_wrap_preserves_order() {
        let mut recovery = UdpVideoRecovery::new();
        assert_eq!(recovery.consider(u32::MAX, true), VideoDecision::Deliver);
        assert_eq!(recovery.consider(0, false), VideoDecision::Deliver);
        assert_eq!(recovery.consider(u32::MAX, false), VideoDecision::Drop);
        assert!(recovery.is_stale(u32::MAX));
        assert!(!recovery.is_stale(1));
    }
}
