//! Windows system-audio loopback capture encoded as WebRTC-compatible Opus.
//!
//! WASAPI, COM and Opus remain on one dedicated worker. The rest of BridgePad
//! only receives complete 20 ms packets through a bounded queue.

use std::sync::mpsc::{Receiver, RecvTimeoutError, SyncSender, TryRecvError};
use std::time::Duration;

const AUDIO_STOP_TIMEOUT: Duration = Duration::from_secs(2);

pub const OPUS_SAMPLE_RATE: u32 = 48_000;
pub const OPUS_CHANNELS: u16 = 2;
pub const OPUS_FRAME_DURATION: Duration = Duration::from_millis(20);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AudioConfig {
    pub bitrate_bits_per_second: u32,
}

impl Default for AudioConfig {
    fn default() -> Self {
        Self {
            bitrate_bits_per_second: 128_000,
        }
    }
}

impl AudioConfig {
    fn validate(self) -> Result<Self, AudioCaptureError> {
        if !(32_000..=510_000).contains(&self.bitrate_bits_per_second) {
            return Err(AudioCaptureError::InvalidConfiguration(
                "Opus bitrate must be between 32 and 510 kbps",
            ));
        }
        Ok(self)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EncodedOpusFrame {
    pub data: Vec<u8>,
    pub duration: Duration,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum AudioCaptureError {
    UnsupportedPlatform,
    InvalidConfiguration(&'static str),
    Backend(String),
    Timeout,
    Disconnected,
}

impl std::fmt::Display for AudioCaptureError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UnsupportedPlatform => {
                formatter.write_str("system audio capture is only available on Windows")
            }
            Self::InvalidConfiguration(detail) => {
                write!(formatter, "invalid audio config: {detail}")
            }
            Self::Backend(detail) => write!(formatter, "Windows audio capture failed: {detail}"),
            Self::Timeout => formatter.write_str("audio capture timed out"),
            Self::Disconnected => formatter.write_str("audio capture stopped"),
        }
    }
}

impl std::error::Error for AudioCaptureError {}

#[derive(Debug)]
pub struct WindowsAudioLoopback {
    frames: Receiver<Result<EncodedOpusFrame, AudioCaptureError>>,
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
    stopped: Receiver<()>,
    worker: Option<std::thread::JoinHandle<()>>,
}

impl WindowsAudioLoopback {
    /// Starts capture of the default Windows render endpoint.
    ///
    /// # Errors
    ///
    /// Returns an error when WASAPI or the bundled Opus encoder cannot start.
    pub fn start(config: AudioConfig) -> Result<Self, AudioCaptureError> {
        platform::start(config.validate()?)
    }

    /// Returns the next encoded packet without waiting.
    ///
    /// # Errors
    ///
    /// Propagates a worker failure or reports a stopped capture.
    pub fn try_recv(&self) -> Result<Option<EncodedOpusFrame>, AudioCaptureError> {
        match self.frames.try_recv() {
            Ok(Ok(frame)) => Ok(Some(frame)),
            Ok(Err(error)) => Err(error),
            Err(TryRecvError::Empty) => Ok(None),
            Err(TryRecvError::Disconnected) => Err(AudioCaptureError::Disconnected),
        }
    }

    /// Waits for the next encoded packet.
    ///
    /// # Errors
    ///
    /// Returns a timeout, worker error, or stopped-capture error.
    pub fn recv_timeout(&self, timeout: Duration) -> Result<EncodedOpusFrame, AudioCaptureError> {
        match self.frames.recv_timeout(timeout) {
            Ok(result) => result,
            Err(RecvTimeoutError::Timeout) => Err(AudioCaptureError::Timeout),
            Err(RecvTimeoutError::Disconnected) => Err(AudioCaptureError::Disconnected),
        }
    }
}

impl Drop for WindowsAudioLoopback {
    fn drop(&mut self) {
        self.stop.store(true, std::sync::atomic::Ordering::Relaxed);
        if self.stopped.recv_timeout(AUDIO_STOP_TIMEOUT).is_ok()
            && let Some(worker) = self.worker.take()
        {
            let _ = worker.join();
        }
    }
}

#[cfg(windows)]
mod platform {
    use super::{
        AudioCaptureError, AudioConfig, EncodedOpusFrame, OPUS_CHANNELS, OPUS_FRAME_DURATION,
        OPUS_SAMPLE_RATE, SyncSender, WindowsAudioLoopback,
    };
    use opus_pure::{Application, MAX_PACKET_BYTES, OpusEncoder};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;
    use windows::Win32::Media::Audio::{
        AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
        AUDCLNT_STREAMFLAGS_LOOPBACK, AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, IAudioCaptureClient,
        IAudioClient, IMMDeviceEnumerator, MMDeviceEnumerator, WAVEFORMATEX, eConsole, eRender,
    };
    use windows::Win32::Media::Multimedia::WAVE_FORMAT_IEEE_FLOAT;
    use windows::Win32::System::Com::{
        CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoUninitialize,
    };

    const QUEUE_CAPACITY: usize = 8;
    const START_TIMEOUT: Duration = Duration::from_secs(3);
    const POLL_INTERVAL: Duration = Duration::from_millis(2);
    const FRAME_SAMPLES_PER_CHANNEL: usize = 960;
    const FRAME_SAMPLE_COUNT: usize = FRAME_SAMPLES_PER_CHANNEL * OPUS_CHANNELS as usize;

    pub fn start(config: AudioConfig) -> Result<WindowsAudioLoopback, AudioCaptureError> {
        let (frame_tx, frame_rx) = std::sync::mpsc::sync_channel(QUEUE_CAPACITY);
        let (started_tx, started_rx) = std::sync::mpsc::sync_channel(1);
        let (stopped_tx, stopped_rx) = std::sync::mpsc::sync_channel(1);
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let worker = std::thread::Builder::new()
            .name("bridgepad-wasapi-opus".to_owned())
            .spawn(move || {
                let result = capture_loop(config, &frame_tx, &started_tx, &worker_stop);
                if let Err(error) = result {
                    let _ = started_tx.try_send(Err(error.clone()));
                    let _ = frame_tx.try_send(Err(error));
                }
                let _ = stopped_tx.send(());
            })
            .map_err(|error| AudioCaptureError::Backend(error.to_string()))?;

        match started_rx.recv_timeout(START_TIMEOUT) {
            Ok(Ok(())) => Ok(WindowsAudioLoopback {
                frames: frame_rx,
                stop,
                stopped: stopped_rx,
                worker: Some(worker),
            }),
            Ok(Err(error)) => {
                let _ = worker.join();
                Err(error)
            }
            Err(_) => {
                stop.store(true, Ordering::Relaxed);
                let _ = worker.join();
                Err(AudioCaptureError::Backend(
                    "WASAPI did not start within 3 seconds".to_owned(),
                ))
            }
        }
    }

    fn capture_loop(
        config: AudioConfig,
        frames: &SyncSender<Result<EncodedOpusFrame, AudioCaptureError>>,
        started: &SyncSender<Result<(), AudioCaptureError>>,
        stop: &AtomicBool,
    ) -> Result<(), AudioCaptureError> {
        // SAFETY: This dedicated worker owns its COM apartment and all WASAPI
        // interfaces. No COM value or captured buffer escapes the thread.
        unsafe { capture_loop_in_com(config, frames, started, stop) }
    }

    unsafe fn capture_loop_in_com(
        config: AudioConfig,
        frames: &SyncSender<Result<EncodedOpusFrame, AudioCaptureError>>,
        started: &SyncSender<Result<(), AudioCaptureError>>,
        stop: &AtomicBool,
    ) -> Result<(), AudioCaptureError> {
        // SAFETY: The worker is newly created and uninitializes the apartment
        // through `ComApartment` after every return path.
        unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }
            .ok()
            .map_err(|error| backend("initialize COM", error))?;
        let _com = ComApartment;

        // SAFETY: COM is initialized on this thread and returned interfaces stay
        // thread-local for their entire lifetime.
        let enumerator: IMMDeviceEnumerator = unsafe {
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)
                .map_err(|error| backend("create the audio device enumerator", error))?
        };
        let device = unsafe { enumerator.GetDefaultAudioEndpoint(eRender, eConsole) }
            .map_err(|error| backend("open the default playback device", error))?;
        let client: IAudioClient = unsafe { device.Activate(CLSCTX_ALL, None) }
            .map_err(|error| backend("activate WASAPI loopback", error))?;

        let format = WAVEFORMATEX {
            wFormatTag: WAVE_FORMAT_IEEE_FLOAT as u16,
            nChannels: OPUS_CHANNELS,
            nSamplesPerSec: OPUS_SAMPLE_RATE,
            nAvgBytesPerSec: OPUS_SAMPLE_RATE * u32::from(OPUS_CHANNELS) * 4,
            nBlockAlign: OPUS_CHANNELS * 4,
            wBitsPerSample: 32,
            cbSize: 0,
        };
        let flags = AUDCLNT_STREAMFLAGS_LOOPBACK
            | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
            | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        unsafe {
            client.Initialize(
                AUDCLNT_SHAREMODE_SHARED,
                flags,
                1_000_000,
                0,
                &raw const format,
                None,
            )
        }
        .map_err(|error| backend("initialize 48 kHz stereo loopback", error))?;
        let capture: IAudioCaptureClient = unsafe { client.GetService() }
            .map_err(|error| backend("create the WASAPI capture client", error))?;

        let mut encoder = OpusEncoder::new(
            OPUS_SAMPLE_RATE as i32,
            OPUS_CHANNELS as usize,
            Application::Audio,
        )
        .map_err(|error| AudioCaptureError::Backend(format!("create Opus encoder: {error}")))?;
        encoder.bitrate_bps = config.bitrate_bits_per_second as i32;

        unsafe { client.Start() }.map_err(|error| backend("start WASAPI loopback", error))?;
        started
            .send(Ok(()))
            .map_err(|_| AudioCaptureError::Disconnected)?;

        let mut pending = Vec::<f32>::with_capacity(FRAME_SAMPLE_COUNT * 3);
        let mut consumed = 0_usize;
        let mut encoded = vec![0_u8; MAX_PACKET_BYTES];
        while !stop.load(Ordering::Relaxed) {
            let mut received_packet = false;
            loop {
                let packet_frames = unsafe { capture.GetNextPacketSize() }
                    .map_err(|error| backend("query loopback packet size", error))?;
                if packet_frames == 0 {
                    break;
                }
                received_packet = true;
                let mut data = std::ptr::null_mut();
                let mut frame_count = 0_u32;
                let mut capture_flags = 0_u32;
                unsafe {
                    capture.GetBuffer(
                        &raw mut data,
                        &raw mut frame_count,
                        &raw mut capture_flags,
                        None,
                        None,
                    )
                }
                .map_err(|error| backend("read loopback packet", error))?;

                let sample_count = frame_count as usize * OPUS_CHANNELS as usize;
                if capture_flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 {
                    pending.resize(pending.len() + sample_count, 0.0);
                } else {
                    if data.is_null() {
                        unsafe { capture.ReleaseBuffer(frame_count) }
                            .map_err(|error| backend("release empty loopback packet", error))?;
                        return Err(AudioCaptureError::Backend(
                            "WASAPI returned a null non-silent buffer".to_owned(),
                        ));
                    }
                    // SAFETY: WASAPI owns `data` until ReleaseBuffer. The
                    // requested format is interleaved stereo f32 and the slice
                    // is copied into `pending` before releasing it.
                    let samples =
                        unsafe { std::slice::from_raw_parts(data.cast::<f32>(), sample_count) };
                    pending.extend_from_slice(samples);
                }
                unsafe { capture.ReleaseBuffer(frame_count) }
                    .map_err(|error| backend("release loopback packet", error))?;

                while pending.len().saturating_sub(consumed) >= FRAME_SAMPLE_COUNT {
                    let input = &pending[consumed..consumed + FRAME_SAMPLE_COUNT];
                    let size = encoder
                        .encode(input, FRAME_SAMPLES_PER_CHANNEL, &mut encoded)
                        .map_err(|error| {
                            AudioCaptureError::Backend(format!("encode Opus frame: {error}"))
                        })?;
                    let _ = frames.try_send(Ok(EncodedOpusFrame {
                        data: encoded[..size].to_vec(),
                        duration: OPUS_FRAME_DURATION,
                    }));
                    consumed += FRAME_SAMPLE_COUNT;
                }
                if consumed >= FRAME_SAMPLE_COUNT * 2 {
                    pending.drain(..consumed);
                    consumed = 0;
                }
            }
            if !received_packet {
                std::thread::sleep(POLL_INTERVAL);
            }
        }
        let _ = unsafe { client.Stop() };
        Ok(())
    }

    fn backend(stage: &str, error: windows::core::Error) -> AudioCaptureError {
        AudioCaptureError::Backend(format!("{stage}: {error}"))
    }

    struct ComApartment;

    impl Drop for ComApartment {
        fn drop(&mut self) {
            // SAFETY: A successful CoInitializeEx created exactly one apartment
            // for this worker and this guard never leaves that thread.
            unsafe { CoUninitialize() };
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use super::{AudioCaptureError, AudioConfig, WindowsAudioLoopback};

    pub fn start(_config: AudioConfig) -> Result<WindowsAudioLoopback, AudioCaptureError> {
        Err(AudioCaptureError::UnsupportedPlatform)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_audio_profile_is_web_rtc_opus() {
        assert_eq!(OPUS_SAMPLE_RATE, 48_000);
        assert_eq!(OPUS_CHANNELS, 2);
        assert_eq!(OPUS_FRAME_DURATION, Duration::from_millis(20));
        assert_eq!(AudioConfig::default().bitrate_bits_per_second, 128_000);
    }

    #[test]
    fn rejects_invalid_opus_bitrates() {
        assert!(
            AudioConfig {
                bitrate_bits_per_second: 31_999,
            }
            .validate()
            .is_err()
        );
    }
}
