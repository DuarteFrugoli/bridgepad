use bridgepad_windows_audio::{AudioConfig, WindowsAudioLoopback};
use std::time::{Duration, Instant};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("Capturing five seconds of Windows system audio as Opus");
    println!("Play any sound on the default Windows output device during this test.");
    let capture = WindowsAudioLoopback::start(AudioConfig::default())?;
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut packets = 0_u64;
    let mut bytes = 0_u64;
    while Instant::now() < deadline {
        match capture.recv_timeout(Duration::from_millis(100)) {
            Ok(packet) => {
                packets += 1;
                bytes += packet.data.len() as u64;
            }
            Err(bridgepad_windows_audio::AudioCaptureError::Timeout) => {}
            Err(error) => return Err(error.into()),
        }
    }
    if packets == 0 {
        return Err(
            "WASAPI produced no packets; play audio and verify the default output device".into(),
        );
    }
    println!("Captured {packets} Opus packets ({bytes} bytes)");
    Ok(())
}
