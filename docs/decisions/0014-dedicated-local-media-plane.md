# ADR 0014: Dedicated local media plane

Status: accepted

## Context

ADR 0013 selected WebRTC for the first complete Windows-to-Android streaming
path. That implementation proved the capture, hardware H.264 encoding, Android
hardware decoding, system-audio and input-isolation boundaries over both Wi-Fi
and USB tethering. It is now a useful, working and measurable reference.

Manual comparison with mature local game-streaming software also exposed a
product mismatch. WebRTC owns buffering, pacing, congestion and decoder/render
policy that is designed for general real-time communication. BridgePad needs a
local-first path whose primary objective is the newest playable frame, with
explicit control over every queue and no conferencing-oriented behavior hidden
below the application.

Changing this transport must not discard the working native capture and codec
backends, couple media to input, or copy code from projects with incompatible
licences. It must also preserve the possibility of a separate remote mode in
the future.

## Decision

WebRTC is **not** the definitive backend for local BridgePad streaming. The
current WebRTC implementation is frozen as a reference backend and diagnostic
baseline. Local Wi-Fi and USB streaming will move to a dedicated BridgePad
media plane optimized for low latency.

The stable contracts introduced by ADR 0013 remain unchanged:

- Windows Graphics Capture produces timestamped frames;
- BridgePad's native Media Foundation backend produces hardware H.264;
- Windows system audio is captured independently and encoded as Opus;
- Android decodes video directly to a `Surface` through `MediaCodec`;
- capture, encode, transport, decode, rendering, audio and input use separate
  bounded queues and workers;
- video congestion is allowed to reduce quality or discard frames, but never to
  delay gamepad, pointer or keyboard input.

The new local media protocol will use an authenticated control connection to
negotiate capabilities, ports, ephemeral session keys and route. Media itself
will use independent UDP flows with:

- a versioned packet header containing session, stream, frame and packet
  identifiers plus monotonic presentation timestamps;
- MTU-safe fragmentation and bounded reordering;
- immediate disposal of stale or incomplete video frames instead of backlog;
- receiver feedback for loss, jitter, presentation delay and explicit IDR
  requests;
- adaptive bitrate and optional forward-error correction based on measured
  local-network behavior;
- authenticated encryption, replay protection and authorization inherited from
  the existing BridgePad trust relationship;
- independent audio timing and buffering so audio cannot block video or input.

Android will expose at least `Low latency` and `Balanced` presentation policies.
The low-latency policy will keep no more decoded or received video than needed
to present the newest usable frame. Exact reorder and audio-buffer windows must
be selected from measurements rather than copied blindly from another product.

The authenticated TLS services remain the initial control plane during this
migration. The existing WebRTC path remains runnable until the dedicated path
meets or beats its frozen baseline and passes the same Wi-Fi and USB matrix.
Removing it is a separate cleanup decision.

Input transport is deliberately not decided by this ADR. TCP/TLS input remains
independent while the media path changes. After the dedicated local streaming
path is validated, input may move to its own authenticated low-latency
transport, but it will receive a separate ADR and will not be coupled to either
the WebRTC reference backend or the new media queues.

BridgePad may study observable architecture, measurements and published
documentation from Moonlight, Sunshine and similar systems. It will implement
its own protocol and code. No Sunshine process is introduced as an intermediary
and no GPL implementation is copied into the BridgePad codebase merely to keep
the current project licence unchanged.

WebRTC remains a possible building block for a future internet/P2P mode, where
ICE and broad NAT traversal have different value. That decision will be made
from remote-mode requirements and does not change the local-media decision.

## Consequences

- The current working WebRTC implementation is no longer treated as the local
  streaming destination architecture.
- Capture, hardware encoders, audio capture and most platform contracts remain
  reusable; the migration is concentrated in transport, feedback and Android
  presentation policy.
- BridgePad must own packetization, congestion behavior, encryption, recovery,
  compatibility and protocol testing that WebRTC previously supplied.
- A synthetic source and the frozen WebRTC baseline make transport comparisons
  repeatable before real gameplay is used as the acceptance test.
- Wi-Fi and USB use one media protocol, with explicit route binding rather than
  separate cable-specific semantics.
- Remote streaming is not blocked, but local simplicity cannot silently become
  dependent on a public signalling or relay service.

## Delivery order

1. Freeze the current WebRTC implementation, configuration and measurements as
   the comparison baseline.
2. Specify BridgePad Media v1, including security, packetization, clocks,
   feedback, teardown and compatibility behavior.
3. Validate the protocol with a synthetic source over Wi-Fi and USB.
4. Connect the existing Windows H.264/Opus producers and Android
   `MediaCodec`/audio consumers.
5. Compare both backends under the same device, route, content and profile;
   promote the dedicated backend only when latency, stability and input
   isolation pass.
6. Decide the future IP input transport in a separate ADR, then run the complete
   Bluetooth, Wi-Fi, USB and streaming regression matrix.

## Related records

- [ADR 0013](./0013-webrtc-media-plane-and-isolated-streaming-foundation.md)
  describes the superseded WebRTC production decision and the implementation
  retained as the reference backend.
- [Streaming baseline](../streaming-baseline.md) freezes the current reference
  configuration, validated scenarios and comparison procedure.
