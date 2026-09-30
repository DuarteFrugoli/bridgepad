use bridgepad_windows_capture::{CaptureConfig, CaptureError, WindowsCapturePipeline};
use std::time::{Duration, Instant};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let config = CaptureConfig::default();
    println!("Testing the native hardware-only Media Foundation H.264 backend");
    let pipeline = WindowsCapturePipeline::start_primary(config)?;
    let started = Instant::now();
    let mut frames = 0_u64;
    let mut keyframes = 0_u64;
    let mut bytes = 0_u64;

    while frames < 180 && started.elapsed() < Duration::from_secs(10) {
        match pipeline.recv_timeout(Duration::from_secs(1)) {
            Ok(frame) => {
                frames += 1;
                keyframes += u64::from(frame.keyframe);
                bytes += frame.data.len() as u64;
            }
            Err(CaptureError::Timeout) => {}
            Err(error) => return Err(error.into()),
        }
    }

    if frames < 60 || keyframes == 0 || bytes == 0 {
        return Err(format!(
            "capture produced an incomplete sample: {frames} frames, {keyframes} keyframes, {bytes} bytes",
        )
        .into());
    }
    println!(
        "Captured {frames} H.264 frames ({keyframes} keyframes, {bytes} bytes) in {:.2}s",
        started.elapsed().as_secs_f64(),
    );
    Ok(())
}
