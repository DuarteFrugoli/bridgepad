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

The completed synthetic milestone deliberately did **not** pretend its TLS
stream was the production media transport. The production Windows path now uses
the second authenticated TLS port (`39394`; control/input remains on `39393`)
only for authorization, capability negotiation and SDP exchange. Captured H.264
video travels over WebRTC ICE/DTLS/SRTP UDP sockets. No video bytes share the
gameplay connection or its port.

The following boundaries are stable:

- capture produces timestamped raw frames;
- an encoder consumes raw frames and accepts bitrate/keyframe requests;
- media transport sends encoded frames and returns receiver feedback;
- a decoder produces renderable frames;
- rendering reports presentation timing;
- audio capture, encoder, decoder and renderer are independent contracts;
- input and media always have different sockets, workers and bounded queues.

The input data plane is intentionally migrated only after the first real
streaming implementation has been exercised. Until that gate, Desktop input on
Wi-Fi and USB tethering continues to use authenticated TLS/TCP on `39393`, which
keeps failures in the new capture, codec and media path separate from an input
transport change.

After that validation, IP-based Desktop input will move to its own WebRTC peer
connection and data channels. Replaceable, latency-sensitive state such as full
gamepad snapshots and pointer movement uses an unordered channel without
retransmission; a newer snapshot supersedes an older one. Keyboard/text,
non-idempotent actions and session control use a reliable ordered channel. The
existing short gamepad lease and explicit neutral reports remain mandatory.
Pairing, authorization, capability negotiation and WebRTC signalling stay on
authenticated TLS/TCP. Therefore “UDP input” means authenticated WebRTC
DataChannels over ICE/DTLS/SCTP, normally carried by UDP, not a new raw UDP
protocol.

This migration applies to Desktop connections over IP: Wi-Fi, USB tethering and
future remote sessions. Bluetooth HID continues to send HID reports directly,
and Bluetooth via Desktop continues to use RFCOMM unless a separate Bluetooth
transport decision replaces it. Both Bluetooth paths must still be included in
the regression run after the IP migration.

Video queues are intentionally short. When downstream work is late, stale
video is discarded instead of accumulating latency. The Windows capture adapter
keeps at most two encoded frames and drops new stale work under backpressure.
Input queues, ports and threads are never shared with media
and therefore retain priority. Full gamepad snapshots are refreshed at 125 Hz;
the Desktop treats them as a short lease and neutralizes the virtual device
after 150 ms without a valid refresh, preventing a lost connection from holding
an axis or button indefinitely.

## Timing and feedback

Capture timestamps use the monotonic Windows Graphics Capture clock and are
relative to the stream. Wall clock time is never used to compare peers. WebRTC
uses its RTP clock mapping and RTCP receiver reports. The first adaptive policy
reduces bitrate rapidly above approximately 10% reported loss and recovers it
slowly below approximately 2%, bounded by the negotiated 720p60 profile.

Every encoded frame carries a frame identifier, presentation timestamp,
generation duration, encode duration and keyframe flag. Receiver feedback is
defined with the last presented frame, loss count, receive bitrate, decode and
presentation durations, requested bitrate and a keyframe request. Under WebRTC,
these concepts map to RTCP loss/congestion feedback and PLI/FIR-style keyframe
requests rather than a second proprietary feedback loop.

The synthetic RGB565 source remains a historical diagnostic only. Production
video is H.264 Main profile, captured with Windows Graphics Capture and decoded
through Android WebRTC's MediaCodec path directly to a rendering surface. The
initial `win-native-media` hardware MFT path proved unsuitable: its own source
marks that allocator path unfinished and it returns `E_UNEXPECTED`
(`0x8000FFFF`) on the first frame on tested hardware. BridgePad therefore owns a
small C++ Media Foundation backend behind a versioned C ABI and a safe Rust
wrapper instead of migrating the whole Desktop to native libwebrtc. It requires
an asynchronous D3D11-aware hardware MFT, performs BGRA-to-NV12 conversion on
the GPU and follows the MFT event protocol. There is no silent software fallback.
The implementation still must pass its end-to-end and multi-GPU gates before
the hardware encoding and 720p60 requirements are approved. Windows system
audio is captured independently through WASAPI loopback and encoded as 48 kHz
stereo Opus in 20 ms packets. It travels as a second track in the same media
peer connection and is reproduced through Android's media audio route. Audio
and video share neither capture workers nor queues with input.

## Consequences

- Starting or stopping video cannot create, stop or duplicate a virtual input
  device.
- A blocked video writer can consume only its own connection worker.
- A media-only QoS rule can exercise congestion without throttling the control
  port and invalidating the priority test.
- Screen capture, encoder and WebRTC dependencies can be replaced per desktop
  platform without changing the Android input stack.
- TLS port `39394` remains independently firewallable, but allowing that TCP
  port alone is insufficient: local WebRTC UDP traffic must also be permitted.
- Linux capture/encode and audio remain separate platform increments and do not
  change the input or signalling protocol.
- The initial TCP input implementation remains a temporary compatibility path
  until the independent WebRTC input connection passes the complete Wi-Fi and
  USB regression matrix.

## Delivery order

1. Validate the real Windows capture, H.264 and Android rendering path without
   simultaneously changing input transport.
2. Introduce the independent WebRTC input peer connection and its reliable and
   time-sensitive data channels.
3. Run the complete Bluetooth HID, Bluetooth RFCOMM, Wi-Fi, USB tethering,
   Wi-Fi streaming and USB streaming matrix.
4. Only after this functional baseline is stable, perform the production UX
   redesign and remove or hide spikes, diagnostics and other development-only
   surfaces.

## Sources

- [WebRTC native APIs](https://webrtc.github.io/webrtc-org/native-code/)
- [WebRTC connectivity and ICE](https://webrtc.org/getting-started/peer-connections)
- [W3C WebRTC recommendation](https://www.w3.org/TR/webrtc/)
- [Windows Graphics Capture](https://learn.microsoft.com/en-us/windows/uwp/audio-video-camera/screen-capture)
- [Android low-latency MediaCodec decoding](https://developer.android.com/about/versions/11/features#low-latency)
