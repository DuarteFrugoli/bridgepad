//! Windows Graphics Capture and Media Foundation H.264 boundary.
//!
//! The rest of `BridgePad` only sees encoded Annex-B access units. Direct3D and
//! Media Foundation objects remain owned by the capture worker thread.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{Receiver, SyncSender};
use std::time::{Duration, Instant};

const CAPTURE_STOP_TIMEOUT: Duration = Duration::from_secs(2);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CaptureConfig {
    pub width: u32,
    pub height: u32,
    pub frames_per_second: u32,
    pub bitrate_bits_per_second: u32,
    pub keyframe_interval_frames: u32,
    pub capture_cursor: bool,
}

impl Default for CaptureConfig {
    fn default() -> Self {
        Self {
            width: 1280,
            height: 720,
            frames_per_second: 60,
            bitrate_bits_per_second: 8_000_000,
            keyframe_interval_frames: 120,
            capture_cursor: true,
        }
    }
}

impl CaptureConfig {
    fn validate(self) -> Result<Self, CaptureError> {
        if self.width == 0 || self.height == 0 {
            return Err(CaptureError::InvalidConfiguration(
                "capture dimensions must be non-zero",
            ));
        }
        if self.width & 1 != 0 || self.height & 1 != 0 {
            return Err(CaptureError::InvalidConfiguration(
                "H.264 dimensions must be even",
            ));
        }
        if !(1..=240).contains(&self.frames_per_second) {
            return Err(CaptureError::InvalidConfiguration(
                "frame rate must be between 1 and 240",
            ));
        }
        if self.bitrate_bits_per_second < 100_000 {
            return Err(CaptureError::InvalidConfiguration(
                "bitrate must be at least 100 kbps",
            ));
        }
        if self.keyframe_interval_frames == 0 {
            return Err(CaptureError::InvalidConfiguration(
                "keyframe interval must be non-zero",
            ));
        }
        Ok(self)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EncodedH264Frame {
    pub data: Vec<u8>,
    pub presentation_timestamp: Duration,
    pub keyframe: bool,
    pub captured_at: Instant,
    pub encoded_at: Instant,
    pub encode_duration: Duration,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CapturePipelineStats {
    pub captured_frames: u64,
    pub encoded_frames: u64,
    pub dropped_before_encode: u64,
    pub keyframes: u64,
    pub total_encode_micros: u64,
    pub total_queue_micros: u64,
    pub maximum_queue_micros: u64,
}

#[derive(Debug, Default)]
struct CaptureCounters {
    pending_frames: AtomicUsize,
    captured_frames: AtomicU64,
    encoded_frames: AtomicU64,
    dropped_before_encode: AtomicU64,
    keyframes: AtomicU64,
    total_encode_micros: AtomicU64,
    total_queue_micros: AtomicU64,
    maximum_queue_micros: AtomicU64,
}

impl CaptureCounters {
    fn snapshot(&self) -> CapturePipelineStats {
        CapturePipelineStats {
            captured_frames: self.captured_frames.load(Ordering::Relaxed),
            encoded_frames: self.encoded_frames.load(Ordering::Relaxed),
            dropped_before_encode: self.dropped_before_encode.load(Ordering::Relaxed),
            keyframes: self.keyframes.load(Ordering::Relaxed),
            total_encode_micros: self.total_encode_micros.load(Ordering::Relaxed),
            total_queue_micros: self.total_queue_micros.load(Ordering::Relaxed),
            maximum_queue_micros: self.maximum_queue_micros.load(Ordering::Relaxed),
        }
    }
}

#[derive(Debug)]
pub struct WindowsCapturePipeline {
    frames: Receiver<Result<EncodedH264Frame, CaptureError>>,
    commands: Option<SyncSender<CaptureCommand>>,
    counters: Arc<CaptureCounters>,
    stopped: Receiver<()>,
    worker: Option<std::thread::JoinHandle<()>>,
}

#[derive(Clone, Copy, Debug)]
enum CaptureCommand {
    SetBitrate(u32),
    RequestKeyframe,
    Stop,
}

impl WindowsCapturePipeline {
    /// Starts WGC for the primary monitor using the selected H.264 backend.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid settings, unavailable WGC/Direct3D, or
    /// when Windows has no compatible hardware H.264 encoder.
    pub fn start_primary(config: CaptureConfig) -> Result<Self, CaptureError> {
        platform::start_primary(config.validate()?)
    }

    /// Receives the next encoded access unit.
    ///
    /// # Errors
    ///
    /// Returns an encoder/capture error, `Timeout`, or `Stopped` when the
    /// capture worker ends.
    pub fn recv_timeout(&self, timeout: Duration) -> Result<EncodedH264Frame, CaptureError> {
        match self.frames.recv_timeout(timeout) {
            Ok(Ok(frame)) => {
                self.counters.pending_frames.fetch_sub(1, Ordering::Release);
                let queue_micros = duration_micros(frame.encoded_at.elapsed());
                self.counters
                    .total_queue_micros
                    .fetch_add(queue_micros, Ordering::Relaxed);
                self.counters
                    .maximum_queue_micros
                    .fetch_max(queue_micros, Ordering::Relaxed);
                Ok(frame)
            }
            Ok(Err(error)) => Err(error),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => Err(CaptureError::Timeout),
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => Err(CaptureError::Stopped),
        }
    }

    /// Requests a new encoder bitrate without restarting the WGC session.
    ///
    /// # Errors
    ///
    /// Returns an error for an invalid bitrate or when the control worker is
    /// busy/stopped.
    pub fn set_target_bitrate(&self, bits_per_second: u32) -> Result<(), CaptureError> {
        if bits_per_second < 100_000 {
            return Err(CaptureError::InvalidConfiguration(
                "bitrate must be at least 100 kbps",
            ));
        }
        self.try_send_command(CaptureCommand::SetBitrate(bits_per_second))
    }

    /// Requests the next encoded access unit to be an IDR frame.
    ///
    /// # Errors
    ///
    /// Returns `ControlBusy` when the bounded command queue is occupied or
    /// `Stopped` after the capture worker has ended.
    pub fn request_keyframe(&self) -> Result<(), CaptureError> {
        self.try_send_command(CaptureCommand::RequestKeyframe)
    }

    #[must_use]
    pub fn stats(&self) -> CapturePipelineStats {
        self.counters.snapshot()
    }

    fn try_send_command(&self, command: CaptureCommand) -> Result<(), CaptureError> {
        self.commands
            .as_ref()
            .ok_or(CaptureError::Stopped)?
            .try_send(command)
            .map_err(|error| match error {
                std::sync::mpsc::TrySendError::Full(_) => CaptureError::ControlBusy,
                std::sync::mpsc::TrySendError::Disconnected(_) => CaptureError::Stopped,
            })
    }
}

fn duration_micros(duration: Duration) -> u64 {
    u64::try_from(duration.as_micros()).unwrap_or(u64::MAX)
}

impl Drop for WindowsCapturePipeline {
    fn drop(&mut self) {
        if let Some(commands) = self.commands.take() {
            let _ = commands.try_send(CaptureCommand::Stop);
            drop(commands);
        }
        let stopped = self.stopped.recv_timeout(CAPTURE_STOP_TIMEOUT).is_ok();
        if let Some(worker) = self.worker.take()
            && stopped
        {
            let _ = worker.join();
        } else if !stopped {
            eprintln!("Windows capture stop timed out; detaching the capture worker");
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CaptureError {
    InvalidConfiguration(&'static str),
    UnsupportedPlatform,
    HardwareEncoderUnavailable(String),
    CaptureFailed(String),
    EncodeFailed(String),
    ControlBusy,
    Timeout,
    Stopped,
}

impl std::fmt::Display for CaptureError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidConfiguration(detail) => {
                write!(formatter, "invalid capture configuration: {detail}")
            }
            Self::UnsupportedPlatform => {
                formatter.write_str("Windows capture is only available on Windows")
            }
            Self::HardwareEncoderUnavailable(detail) => {
                write!(formatter, "hardware H.264 encoder unavailable: {detail}")
            }
            Self::CaptureFailed(detail) => {
                write!(formatter, "Windows Graphics Capture failed: {detail}")
            }
            Self::EncodeFailed(detail) => write!(formatter, "H.264 encode failed: {detail}"),
            Self::ControlBusy => formatter.write_str("capture control queue is busy"),
            Self::Timeout => formatter.write_str("timed out waiting for a captured frame"),
            Self::Stopped => formatter.write_str("capture pipeline stopped"),
        }
    }
}

impl std::error::Error for CaptureError {}

#[cfg(windows)]
mod platform {
    use super::{
        CAPTURE_STOP_TIMEOUT, CaptureCommand, CaptureConfig, CaptureCounters, CaptureError,
        EncodedH264Frame, WindowsCapturePipeline, duration_micros,
    };
    use bridgepad_windows_media::{H264EncoderConfig, H264HardwareEncoder};
    use std::cell::RefCell;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
    use std::sync::mpsc::{SyncSender, TrySendError, sync_channel};
    use std::time::Duration;
    use windows::Graphics::Capture::GraphicsCaptureItem;
    use windows_capture::capture::{Context, GraphicsCaptureApiHandler};
    use windows_capture::frame::Frame;
    use windows_capture::graphics_capture_api::InternalCaptureControl;
    use windows_capture::monitor::Monitor;
    use windows_capture::settings::{
        ColorFormat, CursorCaptureSettings, DirtyRegionSettings, DrawBorderSettings,
        GraphicsCaptureItemType, MinimumUpdateIntervalSettings, SecondaryWindowSettings, Settings,
    };

    struct PreparedMonitorItem {
        item: GraphicsCaptureItem,
        monitor: Monitor,
    }

    impl PreparedMonitorItem {
        fn new(monitor: Monitor) -> Result<Self, CaptureError> {
            let capture_item: GraphicsCaptureItemType = monitor.try_into().map_err(|error| {
                CaptureError::CaptureFailed(format!(
                    "Windows could not prepare the primary monitor for capture: {error}"
                ))
            })?;
            match capture_item {
                GraphicsCaptureItemType::Monitor((item, monitor)) => Ok(Self { item, monitor }),
                _ => Err(CaptureError::CaptureFailed(
                    "Windows returned an unexpected capture item for the primary monitor"
                        .to_owned(),
                )),
            }
        }
    }

    impl TryInto<GraphicsCaptureItemType> for PreparedMonitorItem {
        type Error = std::convert::Infallible;

        fn try_into(self) -> Result<GraphicsCaptureItemType, Self::Error> {
            Ok(GraphicsCaptureItemType::Monitor((self.item, self.monitor)))
        }
    }

    fn prepared_primary_monitor() -> Result<PreparedMonitorItem, CaptureError> {
        let monitor =
            Monitor::primary().map_err(|error| CaptureError::CaptureFailed(error.to_string()))?;
        PreparedMonitorItem::new(monitor)
    }

    struct HandlerConfig {
        capture: CaptureConfig,
        frames: SyncSender<Result<EncodedH264Frame, CaptureError>>,
        target_bitrate: Arc<AtomicU32>,
        keyframe_requested: Arc<AtomicBool>,
        counters: Arc<CaptureCounters>,
        stop_requested: Arc<AtomicBool>,
    }

    struct EncoderState {
        encoder: H264HardwareEncoder,
        bitrate_bits_per_second: u32,
        frame_duration: Duration,
        first_timestamp_100ns: Option<i64>,
    }

    thread_local! {
        // Media Foundation objects remain on the WGC callback thread. Keeping
        // them thread-local avoids pretending COM interfaces are Send.
        static ENCODER_STATE: RefCell<Option<EncoderState>> = const { RefCell::new(None) };
    }

    struct CaptureHandler {
        frames: SyncSender<Result<EncodedH264Frame, CaptureError>>,
        target_bitrate: Arc<AtomicU32>,
        keyframe_requested: Arc<AtomicBool>,
        counters: Arc<CaptureCounters>,
        stop_requested: Arc<AtomicBool>,
    }

    struct WorkerCompletion(SyncSender<()>);

    impl Drop for WorkerCompletion {
        fn drop(&mut self) {
            let _ = self.0.try_send(());
        }
    }

    impl CaptureHandler {
        fn report_and_fail(&self, error: CaptureError) -> CaptureError {
            let _ = self.frames.try_send(Err(error.clone()));
            error
        }
    }

    impl GraphicsCaptureApiHandler for CaptureHandler {
        type Flags = HandlerConfig;
        type Error = CaptureError;

        fn new(context: Context<Self::Flags>) -> Result<Self, Self::Error> {
            let native_config = H264EncoderConfig {
                width: context.flags.capture.width,
                height: context.flags.capture.height,
                frames_per_second: context.flags.capture.frames_per_second,
                bitrate_bits_per_second: context.flags.capture.bitrate_bits_per_second,
                keyframe_interval_frames: context.flags.capture.keyframe_interval_frames,
            };
            let encoder = H264HardwareEncoder::from_d3d11_device(&context.device, native_config)
                .map_err(|error| CaptureError::HardwareEncoderUnavailable(error.to_string()))?;
            ENCODER_STATE.with(|slot| {
                *slot.borrow_mut() = Some(EncoderState {
                    encoder,
                    bitrate_bits_per_second: context.flags.capture.bitrate_bits_per_second,
                    frame_duration: Duration::from_secs_f64(
                        1.0 / f64::from(context.flags.capture.frames_per_second),
                    ),
                    first_timestamp_100ns: None,
                });
            });
            Ok(Self {
                frames: context.flags.frames,
                target_bitrate: context.flags.target_bitrate,
                keyframe_requested: context.flags.keyframe_requested,
                counters: context.flags.counters,
                stop_requested: context.flags.stop_requested,
            })
        }

        fn on_frame_arrived(
            &mut self,
            frame: &mut Frame,
            capture_control: InternalCaptureControl,
        ) -> Result<(), Self::Error> {
            let captured_at = std::time::Instant::now();
            self.counters
                .captured_frames
                .fetch_add(1, Ordering::Relaxed);
            if self.stop_requested.load(Ordering::Relaxed) {
                // The Media Foundation encoder owns D3D resources on this
                // callback thread. Release it before asking WGC's dispatcher
                // to stop; otherwise ShutdownQueueAsync can wait forever.
                ENCODER_STATE.with(|slot| *slot.borrow_mut() = None);
                capture_control.stop();
                return Ok(());
            }
            if self.counters.pending_frames.load(Ordering::Acquire) >= 2 {
                self.counters
                    .dropped_before_encode
                    .fetch_add(1, Ordering::Relaxed);
                return Ok(());
            }
            let target_bitrate = self.target_bitrate.load(Ordering::Relaxed);
            let timestamp_100ns = frame
                .timestamp()
                .map_err(|error| {
                    self.report_and_fail(CaptureError::CaptureFailed(error.to_string()))
                })?
                .Duration;
            let encode_started = std::time::Instant::now();
            let encoded = ENCODER_STATE.with(|slot| {
                let mut slot = slot.borrow_mut();
                let state = slot
                    .as_mut()
                    .ok_or_else(|| self.report_and_fail(CaptureError::Stopped))?;
                if target_bitrate != state.bitrate_bits_per_second {
                    state
                        .encoder
                        .set_target_bitrate(target_bitrate)
                        .map_err(|error| {
                            self.report_and_fail(CaptureError::EncodeFailed(error.to_string()))
                        })?;
                    state.bitrate_bits_per_second = target_bitrate;
                }
                if self.keyframe_requested.swap(false, Ordering::AcqRel) {
                    state.encoder.request_keyframe().map_err(|error| {
                        self.report_and_fail(CaptureError::EncodeFailed(error.to_string()))
                    })?;
                }
                let first = *state.first_timestamp_100ns.get_or_insert(timestamp_100ns);
                let relative_100ns =
                    u64::try_from(timestamp_100ns.saturating_sub(first)).unwrap_or_default();
                let timestamp = Duration::from_nanos(relative_100ns.saturating_mul(100));
                state
                    .encoder
                    .encode_texture(frame.as_raw_texture(), timestamp, state.frame_duration)
                    .map_err(|error| {
                        self.report_and_fail(CaptureError::EncodeFailed(error.to_string()))
                    })
            });
            let encode_duration = encode_started.elapsed();
            let encoded = encoded?;
            if let Some(sample) = encoded {
                let encoded_at = std::time::Instant::now();
                let value = Ok(EncodedH264Frame {
                    data: sample.data,
                    presentation_timestamp: sample.presentation_timestamp,
                    keyframe: sample.keyframe,
                    captured_at,
                    encoded_at,
                    encode_duration,
                });
                self.counters.pending_frames.fetch_add(1, Ordering::Release);
                match self.frames.try_send(value) {
                    Ok(()) => {
                        self.counters.encoded_frames.fetch_add(1, Ordering::Relaxed);
                        self.counters
                            .total_encode_micros
                            .fetch_add(duration_micros(encode_duration), Ordering::Relaxed);
                        if sample.keyframe {
                            self.counters.keyframes.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                    Err(TrySendError::Full(_)) => {
                        self.counters.pending_frames.fetch_sub(1, Ordering::Release);
                        self.counters
                            .dropped_before_encode
                            .fetch_add(1, Ordering::Relaxed);
                    }
                    Err(TrySendError::Disconnected(_)) => {
                        self.counters.pending_frames.fetch_sub(1, Ordering::Release);
                        return Err(CaptureError::Stopped);
                    }
                }
            }
            Ok(())
        }

        fn on_closed(&mut self) -> Result<(), Self::Error> {
            ENCODER_STATE.with(|slot| *slot.borrow_mut() = None);
            Ok(())
        }
    }

    pub fn start_primary(config: CaptureConfig) -> Result<WindowsCapturePipeline, CaptureError> {
        let (ready_tx, ready_rx) = sync_channel(1);
        let (frame_tx, frame_rx) = sync_channel(2);
        let (command_tx, command_rx) = sync_channel(4);
        let (stopped_tx, stopped_rx) = sync_channel(1);
        let target_bitrate = Arc::new(AtomicU32::new(config.bitrate_bits_per_second));
        let keyframe_requested = Arc::new(AtomicBool::new(false));
        let counters = Arc::new(CaptureCounters::default());
        let worker_counters = Arc::clone(&counters);
        let stop_requested = Arc::new(AtomicBool::new(false));
        let worker_stop_requested = Arc::clone(&stop_requested);
        let worker = std::thread::Builder::new()
            .name("bridgepad-windows-capture".to_owned())
            .spawn(move || {
                let _completion = WorkerCompletion(stopped_tx);
                let monitor = match prepared_primary_monitor() {
                    Ok(value) => value,
                    Err(error) => {
                        let _ = ready_tx.send(Err(error));
                        return;
                    }
                };
                let cursor = if config.capture_cursor {
                    CursorCaptureSettings::WithCursor
                } else {
                    CursorCaptureSettings::WithoutCursor
                };
                let settings = Settings::new(
                    monitor,
                    cursor,
                    DrawBorderSettings::WithoutBorder,
                    SecondaryWindowSettings::Default,
                    MinimumUpdateIntervalSettings::Custom(Duration::from_secs_f64(
                        1.0 / f64::from(config.frames_per_second),
                    )),
                    DirtyRegionSettings::Default,
                    ColorFormat::Bgra8,
                    HandlerConfig {
                        capture: config,
                        frames: frame_tx,
                        target_bitrate: Arc::clone(&target_bitrate),
                        keyframe_requested: Arc::clone(&keyframe_requested),
                        counters: worker_counters,
                        stop_requested: Arc::clone(&worker_stop_requested),
                    },
                );
                let capture = match CaptureHandler::start_free_threaded(settings) {
                    Ok(value) => value,
                    Err(error) => {
                        let _ = ready_tx.send(Err(CaptureError::CaptureFailed(error.to_string())));
                        return;
                    }
                };
                if ready_tx.send(Ok(())).is_err() {
                    worker_stop_requested.store(true, Ordering::Relaxed);
                    let _ = capture.wait();
                    return;
                }
                while let Ok(command) = command_rx.recv() {
                    match command {
                        CaptureCommand::SetBitrate(bitrate) => {
                            target_bitrate.store(bitrate, Ordering::Relaxed);
                        }
                        CaptureCommand::RequestKeyframe => {
                            keyframe_requested.store(true, Ordering::Release);
                        }
                        CaptureCommand::Stop => {
                            worker_stop_requested.store(true, Ordering::Relaxed);
                            break;
                        }
                    }
                }
                // The next frame callback releases the encoder, posts WM_QUIT
                // and lets the library finish its dispatcher teardown.
                worker_stop_requested.store(true, Ordering::Relaxed);
                let _ = capture.wait();
            })
            .map_err(|error| CaptureError::CaptureFailed(error.to_string()))?;

        match ready_rx.recv_timeout(Duration::from_secs(10)) {
            Ok(Ok(())) => Ok(WindowsCapturePipeline {
                frames: frame_rx,
                commands: Some(command_tx),
                counters,
                stopped: stopped_rx,
                worker: Some(worker),
            }),
            Ok(Err(error)) => {
                if stopped_rx.recv_timeout(CAPTURE_STOP_TIMEOUT).is_ok() {
                    let _ = worker.join();
                }
                Err(error)
            }
            Err(_) => {
                let _ = command_tx.try_send(CaptureCommand::Stop);
                drop(command_tx);
                if stopped_rx.recv_timeout(CAPTURE_STOP_TIMEOUT).is_ok() {
                    let _ = worker.join();
                }
                Err(CaptureError::Timeout)
            }
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use super::{CaptureConfig, CaptureError, WindowsCapturePipeline};

    pub fn start_primary(_config: CaptureConfig) -> Result<WindowsCapturePipeline, CaptureError> {
        Err(CaptureError::UnsupportedPlatform)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_profile_is_720p60() {
        let config = CaptureConfig::default();
        assert_eq!((config.width, config.height), (1280, 720));
        assert_eq!(config.frames_per_second, 60);
        assert!(config.validate().is_ok());
    }

    #[test]
    fn rejects_odd_h264_dimensions() {
        let config = CaptureConfig {
            width: 1279,
            ..CaptureConfig::default()
        };
        assert!(matches!(
            config.validate(),
            Err(CaptureError::InvalidConfiguration(_))
        ));
    }
}
