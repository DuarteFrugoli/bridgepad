//! Transport-independent media contracts and a bounded synthetic-video pipeline.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, SyncSender, sync_channel};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub mod assembly;
pub mod impairment;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VideoCodec {
    RawRgb565,
    H264,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VideoFormat {
    pub codec: VideoCodec,
    pub width: u16,
    pub height: u16,
    pub frames_per_second: u16,
    pub target_bitrate_bits_per_second: u32,
}

#[derive(Debug)]
pub struct CapturedVideoFrame {
    pub frame_id: u32,
    pub presentation_timestamp_micros: u64,
    pub width: u16,
    pub height: u16,
    pub rgb888: Vec<u8>,
    pub generation_micros: u32,
}

#[derive(Debug)]
pub struct EncodedVideoFrame {
    pub frame_id: u32,
    pub presentation_timestamp_micros: u64,
    pub format: VideoFormat,
    pub keyframe: bool,
    pub payload: Vec<u8>,
    pub generation_micros: u32,
    pub encode_micros: u32,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct MediaFeedback {
    pub last_presented_frame_id: u32,
    pub lost_frames: u32,
    pub receive_bitrate_bits_per_second: u32,
    pub decode_micros: u32,
    pub presentation_micros: u32,
    pub requested_bitrate_bits_per_second: u32,
    pub keyframe_requested: bool,
}

pub trait VideoCaptureSource: Send {
    fn capture(&mut self) -> CapturedVideoFrame;
}

pub trait VideoEncoder: Send {
    fn output_format(&self) -> VideoFormat;
    fn encode(&mut self, frame: CapturedVideoFrame, force_keyframe: bool) -> EncodedVideoFrame;
    fn set_target_bitrate(&mut self, bits_per_second: u32);
}

pub trait MediaTransport {
    type Error;
    /// Sends the newest complete encoded frame.
    ///
    /// # Errors
    ///
    /// Returns the transport-specific error when the frame cannot be queued.
    fn send_video(&mut self, frame: &EncodedVideoFrame) -> Result<(), Self::Error>;
    /// Polls receiver feedback without blocking the media producer.
    ///
    /// # Errors
    ///
    /// Returns the transport-specific error when feedback cannot be read.
    fn poll_feedback(&mut self) -> Result<Option<MediaFeedback>, Self::Error>;
}

pub trait AudioCaptureSource: Send {
    fn read(&mut self, target: &mut [u8]) -> usize;
}

pub trait AudioEncoder: Send {
    fn encode(&mut self, pcm: &[u8], presentation_timestamp_micros: u64) -> Vec<u8>;
}

pub trait AudioDecoder: Send {
    fn decode(&mut self, encoded: &[u8], presentation_timestamp_micros: u64) -> Vec<u8>;
}

pub trait AudioRenderer: Send {
    fn render(&mut self, pcm: &[u8], presentation_timestamp_micros: u64);
}

pub struct SyntheticVideoSource {
    format: VideoFormat,
    origin: Instant,
    frame_id: u32,
}

impl SyntheticVideoSource {
    #[must_use]
    pub fn new(format: VideoFormat) -> Self {
        Self {
            format,
            origin: Instant::now(),
            frame_id: 0,
        }
    }
}

impl VideoCaptureSource for SyntheticVideoSource {
    fn capture(&mut self) -> CapturedVideoFrame {
        let started = Instant::now();
        let width = usize::from(self.format.width);
        let height = usize::from(self.format.height);
        let mut pixels = vec![0_u8; width * height * 3];
        let moving_x = usize::try_from(self.frame_id).unwrap_or_default() % width;
        for y in 0..height {
            for x in 0..width {
                let offset = (y * width + x) * 3;
                let band = x * 6 / width;
                let (red, green, blue) = match band {
                    0 => (255, 70, 70),
                    1 => (255, 190, 60),
                    2 => (70, 220, 120),
                    3 => (60, 190, 255),
                    4 => (100, 100, 255),
                    _ => (210, 90, 255),
                };
                let pulse = if x.abs_diff(moving_x) < 3 { 255 } else { 0 };
                pixels[offset] = red.max(pulse);
                pixels[offset + 1] = green.max(pulse);
                pixels[offset + 2] = blue.max(pulse);
            }
        }
        let result = CapturedVideoFrame {
            frame_id: self.frame_id,
            presentation_timestamp_micros: micros(self.origin.elapsed()),
            width: self.format.width,
            height: self.format.height,
            rgb888: pixels,
            generation_micros: micros_u32(started.elapsed()),
        };
        self.frame_id = self.frame_id.wrapping_add(1);
        result
    }
}

pub struct RawRgb565Encoder {
    format: VideoFormat,
}

impl RawRgb565Encoder {
    /// Creates the diagnostic RGB565 encoder.
    ///
    /// # Panics
    ///
    /// Panics when `format` requests a codec other than [`VideoCodec::RawRgb565`].
    #[must_use]
    pub fn new(format: VideoFormat) -> Self {
        assert_eq!(format.codec, VideoCodec::RawRgb565);
        Self { format }
    }
}

impl VideoEncoder for RawRgb565Encoder {
    fn output_format(&self) -> VideoFormat {
        self.format
    }

    fn encode(&mut self, frame: CapturedVideoFrame, force_keyframe: bool) -> EncodedVideoFrame {
        let started = Instant::now();
        let mut payload = Vec::with_capacity(frame.rgb888.len() / 3 * 2);
        let (pixels, remainder) = frame.rgb888.as_chunks::<3>();
        debug_assert!(remainder.is_empty());
        for pixel in pixels {
            let red = u16::from(pixel[0]) >> 3;
            let green = u16::from(pixel[1]) >> 2;
            let blue = u16::from(pixel[2]) >> 3;
            payload.extend_from_slice(&((red << 11) | (green << 5) | blue).to_be_bytes());
        }
        EncodedVideoFrame {
            frame_id: frame.frame_id,
            presentation_timestamp_micros: frame.presentation_timestamp_micros,
            format: self.format,
            keyframe: force_keyframe || frame.frame_id == 0,
            payload,
            generation_micros: frame.generation_micros,
            encode_micros: micros_u32(started.elapsed()),
        }
    }

    fn set_target_bitrate(&mut self, bits_per_second: u32) {
        self.format.target_bitrate_bits_per_second = bits_per_second.max(1);
    }
}

pub struct MediaPipeline {
    pub frames: Receiver<EncodedVideoFrame>,
    stop: Arc<AtomicBool>,
    workers: Vec<JoinHandle<()>>,
}

impl MediaPipeline {
    #[must_use]
    pub fn start_synthetic(format: VideoFormat) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let (capture_sender, capture_receiver) = sync_channel(2);
        let (encoded_sender, encoded_receiver) = sync_channel(2);
        let capture_stop = Arc::clone(&stop);
        let capture = thread::spawn(move || {
            let mut source = SyntheticVideoSource::new(format);
            let interval = Duration::from_secs_f64(1.0 / f64::from(format.frames_per_second));
            while !capture_stop.load(Ordering::Relaxed) {
                let started = Instant::now();
                send_latest(&capture_sender, source.capture());
                let remaining = interval.saturating_sub(started.elapsed());
                if !remaining.is_zero() {
                    thread::sleep(remaining);
                }
            }
        });
        let encode_stop = Arc::clone(&stop);
        let encode = thread::spawn(move || {
            let mut encoder = RawRgb565Encoder::new(format);
            while !encode_stop.load(Ordering::Relaxed) {
                match capture_receiver.recv_timeout(Duration::from_millis(50)) {
                    Ok(frame) => {
                        let force_keyframe =
                            frame.frame_id % u32::from(format.frames_per_second) == 0;
                        send_latest(&encoded_sender, encoder.encode(frame, force_keyframe));
                    }
                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                    Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                }
            }
        });
        Self {
            frames: encoded_receiver,
            stop,
            workers: vec![capture, encode],
        }
    }
}

impl Drop for MediaPipeline {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        for worker in self.workers.drain(..) {
            let _ = worker.join();
        }
    }
}

fn send_latest<T>(sender: &SyncSender<T>, value: T) {
    let _ = sender.try_send(value);
}

fn micros(duration: Duration) -> u64 {
    u64::try_from(duration.as_micros()).unwrap_or(u64::MAX)
}

fn micros_u32(duration: Duration) -> u32 {
    u32::try_from(micros(duration)).unwrap_or(u32::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn synthetic_pipeline_produces_complete_rgb565_frames() {
        let format = VideoFormat {
            codec: VideoCodec::RawRgb565,
            width: 16,
            height: 9,
            frames_per_second: 30,
            target_bitrate_bits_per_second: 100_000,
        };
        let pipeline = MediaPipeline::start_synthetic(format);
        let frame = pipeline
            .frames
            .recv_timeout(Duration::from_secs(1))
            .unwrap();
        assert_eq!(frame.payload.len(), 16 * 9 * 2);
        assert_eq!(frame.format, format);
    }
}
