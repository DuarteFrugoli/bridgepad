# Local streaming baseline

This document freezes the working WebRTC implementation as the comparison
baseline for BridgePad's dedicated local media plane. It is a development
benchmark, not a claim that WebRTC is the definitive local backend or that the
release compatibility matrix is complete. ADR 0014 records that distinction.

Baseline date: 2026-10-04. Baseline source: the commit that first adds this
document.

## Frozen reference path

```text
Windows Graphics Capture (primary monitor)
  -> BridgePad C++ Media Foundation hardware H.264 encoder
  -> WebRTC ICE / DTLS / SRTP
  -> Android hardware decoder selected by HardwareVideoDecoderFactory
  -> SurfaceViewRenderer

WASAPI loopback -> Opus -> independent WebRTC audio track -> Android media audio
```

The authenticated TLS listener on port `39394` authorizes the media session and
exchanges signalling. Encoded media uses WebRTC-selected UDP sockets. Input
stays on its independent authenticated session on port `39393`; no media queue,
worker or lifecycle owns the virtual controller.

The reference profile is:

- H.264 Main, `1280x720`, 60 fps target;
- initial/maximum video bitrate of 8 Mbit/s, with a 500 kbit/s floor;
- at most two pending encoded capture frames; stale work is dropped before
  another expensive encode begins;
- Windows system audio encoded as 48 kHz stereo Opus, 20 ms packets at
  128 kbit/s;
- Android rendering directly to a surface, without a Bitmap/CPU frame path.

## Validated reference environment

The manually validated Android reference is a Samsung SM-A356E running Android
16 / API 36, paired with the Windows reference PC. The following scenarios have
passed:

- real H.264 streaming over local Wi-Fi;
- real H.264 streaming over Android USB tethering with Wi-Fi disabled;
- Wi-Fi to USB and USB to Wi-Fi transitions after clean stop;
- repeated stop, immediate restart and media teardown without restarting either
  application;
- correct Wi-Fi (`192.168.15.x`) and USB (`10.145.116.x`) ICE route selection;
- physical-controller gameplay while streaming;
- WASAPI/Opus playback on Android with no perceptible audio/video drift in the
  completed reference-device manual test;
- media failure and teardown without duplicating or terminating the independent
  input device.

These results do not close multi-device, multi-GPU, long-soak, degraded-network
or accessibility/release gates.

## Captured numbers

The current native capture smoke test produced:

```text
Testing the native hardware-only Media Foundation H.264 backend
Captured 180 H.264 frames (2 keyframes, 1999840 bytes) in 5.60s
```

This smoke result proves capture/encode integration. It is not an end-to-end fps
measurement: Windows Graphics Capture may suppress unchanged desktop frames, so
the visible source must keep changing for comparable runs.

The WebRTC path now reports the following rolling measurements separately:

- encoder p95;
- capture queue p95 and maximum;
- RTP write p95;
- capture-to-send p95;
- frames dropped before encode;
- current RTT, jitter and packet loss;
- Android WebRTC RTT estimate, average hardware decode time, average jitter
  buffer/presentation delay, frames decoded/dropped and bytes received.

No trustworthy numeric end-to-end glass-to-glass latency series was recorded
after the latest instrumentation change. It is intentionally marked missing
instead of inventing a number. The first comparison run must save at least 60
seconds of Desktop rolling metrics and the matching Android statistics.

## Reproducing the baseline

From `desktop/`, with changing content visible on the primary monitor:

```powershell
cargo run --release -p bridgepad-windows-capture --example capture_smoke
cargo run --release -p bridgepad-desktop
```

Start the normal streaming flow from Android, first over Wi-Fi and then USB
tethering. Keep the same device, monitor, content, `1280x720@60` profile and
8 Mbit/s limit when comparing transports. Record:

1. time to first presented frame and audio;
2. capture-to-send p50/p95 and queue maximum;
3. network loss, jitter and RTT;
4. decoder time, presentation-buffer delay and rendered/dropped fps;
5. audio/video drift after at least five minutes;
6. input responsiveness and neutralization under deliberate media congestion;
7. stop/restart and Wi-Fi/USB route-switch behavior.

## Promotion rule for the dedicated backend

BridgePad Media v1 can replace the local WebRTC reference only when the same
test matrix shows:

- no steadily growing receive, decode or presentation queue;
- lower or equal end-to-end and presentation latency at equivalent visual
  quality;
- stable 720p60 on the reference route, with stale frames discarded rather than
  replayed;
- audio without growing drift or backlog;
- clean teardown and immediate restart over both Wi-Fi and USB;
- no regression to gamepad, mouse or keyboard latency, lifetime or
  neutralization.

All future baseline captures must record the commit, build profile, device,
route, power mode, resolution, frame rate, bitrate and test duration. Results
from a battery-saving laptop profile must not be compared directly with an
AC-powered performance run.
