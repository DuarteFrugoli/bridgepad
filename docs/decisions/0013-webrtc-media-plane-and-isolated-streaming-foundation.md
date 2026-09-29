# ADR 0013: WebRTC media plane and isolated streaming foundation

Status: accepted

## Context

BridgePad already transports latency-sensitive input over Bluetooth HID, RFCOMM
or an authenticated TLS connection. Streaming adds much larger, lossy and
adaptive video/audio traffic. Reusing the input writer or its queue would allow
video congestion to delay gamepad, pointer and keyboard events.

The production media path must work on Windows and Linux, use hardware codecs
when available, adapt to local-network conditions and leave a path to direct
peer-to-peer sessions in the future. The first implementation also needs a
small deterministic source that validates the boundaries before screen capture
and hardware codecs are introduced.

## Decision

WebRTC is the production media transport. It supplies the UDP-oriented media
stack, RTP/RTCP feedback, congestion control, encryption and ICE required for
future peer-to-peer connectivity. BridgePad will use native WebRTC clients; a
browser UI is not part of this decision. The existing BridgePad trust exchange
remains the application identity and authorization boundary, while WebRTC
signalling is carried by the authenticated control plane.

The current synthetic milestone deliberately does **not** pretend its TLS
stream is the production media transport. It uses a second authenticated TLS
connection on a separately advertised media port (`39394`; control/input
remains on `39393`) only to validate contracts, framing, bounded queues,
decoding, rendering and measurements before native WebRTC is integrated. No
video bytes share the gameplay connection or its port.

The following boundaries are stable:

- capture produces timestamped raw frames;
- an encoder consumes raw frames and accepts bitrate/keyframe requests;
- media transport sends encoded frames and returns receiver feedback;
- a decoder produces renderable frames;
- rendering reports presentation timing;
- audio capture, encoder, decoder and renderer are independent contracts;
- input and media always have different sockets, workers and bounded queues.

Video queues are intentionally short. When downstream work is late, stale
video is discarded instead of accumulating latency. The synthetic sender drains
to the newest encoded frame, abandons a frame after its deadline and bounds
socket write stalls. Input queues, ports and threads are never shared with media
and therefore retain priority. Full gamepad snapshots are refreshed at 125 Hz;
the Desktop treats them as a short lease and neutralizes the virtual device
after 150 ms without a valid refresh, preventing a lost connection from holding
an axis or button indefinitely.

## Timing and feedback

Capture timestamps use a monotonic clock and are relative to the stream. Wall
clock time is never used to compare peers. The synthetic probe measures an RTT
before starting and reports half of it as a network estimate; production WebRTC
will use its RTP clock mapping and RTCP statistics.

Every encoded frame carries a frame identifier, presentation timestamp,
generation duration, encode duration and keyframe flag. Receiver feedback is
defined with the last presented frame, loss count, receive bitrate, decode and
presentation durations, requested bitrate and a keyframe request. Under WebRTC,
these concepts map to RTCP loss/congestion feedback and PLI/FIR-style keyframe
requests rather than a second proprietary feedback loop.

The synthetic RGB565 source is a diagnostic format only. Production video is
H.264 initially, decoded to an Android `Surface` with `MediaCodec`; audio is
expected to use Opus.

## Consequences

- Starting or stopping video cannot create, stop or duplicate a virtual input
  device.
- A blocked video writer can consume only its own connection worker.
- A media-only QoS rule can exercise congestion without throttling the control
  port and invalidating the priority test.
- Screen capture, encoder and WebRTC dependencies can be replaced per desktop
  platform without changing the Android input stack.
- The synthetic stream is intentionally bandwidth-heavy and must not ship as a
  user-facing streaming mode.
- Native WebRTC integration and signalling remain required before real screen
  streaming.

## Sources

- [WebRTC native APIs](https://webrtc.github.io/webrtc-org/native-code/)
- [WebRTC connectivity and ICE](https://webrtc.org/getting-started/peer-connections)
- [W3C WebRTC recommendation](https://www.w3.org/TR/webrtc/)
- [Windows Graphics Capture](https://learn.microsoft.com/en-us/windows/uwp/audio-video-camera/screen-capture)
- [Android low-latency MediaCodec decoding](https://developer.android.com/about/versions/11/features#low-latency)
