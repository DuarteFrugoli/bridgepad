//! Windows Graphics Capture and Media Foundation H.264 boundary.
//!
//! The rest of `BridgePad` only sees encoded Annex-B access units. Direct3D and
//! Media Foundation objects remain owned by the capture worker thread.

use std::sync::mpsc::{Receiver, SyncSender};
use std::time::Duration;

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
}

#[derive(Debug)]
pub struct WindowsCapturePipeline {
    frames: Receiver<Result<EncodedH264Frame, CaptureError>>,
    commands: Option<SyncSender<CaptureCommand>>,
    stopped: Receiver<()>,
    worker: Option<std::thread::JoinHandle<()>>,
}

#[derive(Clone, Copy, Debug)]
enum CaptureCommand {
    SetBitrate(u32),
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
            Ok(result) => result,
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
        self.commands
            .as_ref()
            .ok_or(CaptureError::Stopped)?
            .try_send(CaptureCommand::SetBitrate(bits_per_second))
            .map_err(|error| match error {
                std::sync::mpsc::TrySendError::Full(_) => CaptureError::ControlBusy,
                std::sync::mpsc::TrySendError::Disconnected(_) => CaptureError::Stopped,
            })
    }
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
        CAPTURE_STOP_TIMEOUT, CaptureCommand, CaptureConfig, CaptureError, EncodedH264Frame,
        WindowsCapturePipeline,
    };
    use bridgepad_windows_media::{H264EncoderConfig, H264HardwareEncoder};
    use std::cell::RefCell;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
    use std::sync::mpsc::{SyncSender, TrySendError, sync_channel};
    use std::time::Duration;
    use windows_capture::capture::{Context, GraphicsCaptureApiHandler};
    use windows_capture::frame::Frame;
    use windows_capture::graphics_capture_api::InternalCaptureControl;
    use windows_capture::monitor::Monitor;
    use windows_capture::settings::{
        ColorFormat, CursorCaptureSettings, DirtyRegionSettings, DrawBorderSettings,
        MinimumUpdateIntervalSettings, SecondaryWindowSettings, Settings,
    };

    struct HandlerConfig {
        capture: CaptureConfig,
        frames: SyncSender<Result<EncodedH264Frame, CaptureError>>,
        target_bitrate: Arc<AtomicU32>,
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
                stop_requested: context.flags.stop_requested,
            })
        }

        fn on_frame_arrived(
            &mut self,
            frame: &mut Frame,
            capture_control: InternalCaptureControl,
        ) -> Result<(), Self::Error> {
            if self.stop_requested.load(Ordering::Relaxed) {
                // The Media Foundation encoder owns D3D resources on this
                // callback thread. Release it before asking WGC's dispatcher
                // to stop; otherwise ShutdownQueueAsync can wait forever.
                ENCODER_STATE.with(|slot| *slot.borrow_mut() = None);
                capture_control.stop();
                return Ok(());
            }
            let target_bitrate = self.target_bitrate.load(Ordering::Relaxed);
            let timestamp_100ns = frame
                .timestamp()
                .map_err(|error| {
                    self.report_and_fail(CaptureError::CaptureFailed(error.to_string()))
                })?
                .Duration;
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
            })?;
            if let Some(sample) = encoded {
                let value = Ok(EncodedH264Frame {
                    data: sample.data,
                    presentation_timestamp: sample.presentation_timestamp,
                    keyframe: sample.keyframe,
                });
                match self.frames.try_send(value) {
                    Ok(()) | Err(TrySendError::Full(_)) => {}
                    Err(TrySendError::Disconnected(_)) => return Err(CaptureError::Stopped),
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
        let stop_requested = Arc::new(AtomicBool::new(false));
        let worker_stop_requested = Arc::clone(&stop_requested);
        let worker = std::thread::Builder::new()
            .name("bridgepad-windows-capture".to_owned())
            .spawn(move || {
                let _completion = WorkerCompletion(stopped_tx);
                let monitor = match Monitor::primary() {
                    Ok(value) => value,
                    Err(error) => {
                        let _ = ready_tx.send(Err(CaptureError::CaptureFailed(error.to_string())));
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
