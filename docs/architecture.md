# Architecture

BridgePad separates controller input, logical behavior and output transport so
new connection methods and destinations do not change existing input adapters.

This document is the single source of truth for the current architecture. It
describes decisions that are still active rather than keeping a separate record
for every experiment or superseded direction. Git history preserves how these
choices evolved.

## Current architectural decisions

- Android is implemented natively with Kotlin and Jetpack Compose. The minimum
  supported version is Android 9 (`minSdk = 28`).
- All controller sources are normalized into `VirtualGamepadState` before an
  output transport sees them. Inputs and outputs must remain independently
  replaceable.
- Direct Bluetooth exposes one composite generic HID device for gamepad, mouse
  and keyboard. Bluetooth through BridgePad Desktop is a separate RFCOMM path
  that creates one native virtual controller and must never run alongside the
  direct-HID output.
- Wi-Fi and phone-to-PC USB use the authenticated BridgePad Desktop protocol.
  The production USB carrier is IP over Android USB tethering; Android Open
  Accessory remains a diagnostic experiment, not a product dependency.
- Network trust uses a persistent Desktop TLS identity, certificate pinning and
  explicit user-confirmed pairing. Saved credentials authorize later sessions;
  an unexpected identity change is rejected until the user forgets and pairs
  the Desktop again.
- Desktop session code depends on the `bridgepad-virtual-device` boundary.
  ViGEm is the current Windows development backend, not the final distribution
  commitment. Linux will use its own native backend behind the same boundary.
- WebRTC is the frozen streaming reference implementation, not the definitive
  local media backend. The production local Wi-Fi/USB direction is the dedicated
  BridgePad Media v1 path described below.

## Gradle modules

```text
:app ---------------------> :protocol -----------------> :domain
  |                           ^                           ^
  +----------------> :transport-network -----------------+
  |                           |
  |                           +---------> :streaming-core
  +------------------------------------> :streaming-core
  |                                                       ^
  +--------> :transport-bluetooth-desktop ----------------+
  |                                                       ^
  +----------------> :transport-bluetooth-hid ------------+
  |                                                       ^
  +-------------------------------------------------------+
```

- `:domain` is pure Kotlin. It owns logical controller state, mapping, merging,
  session configuration, scheduling policy and input/output ports. It must not
  import Android, Compose, Bluetooth, USB APIs or Android resources.
- `:protocol` owns versioned messages shared with a future BridgePad receiver.
  It depends only on `:domain`. A published wire format must remain independent
  of the desktop implementation language.
- `:streaming-core` owns transport- and platform-independent video/audio
  contracts, frame models, decoder boundaries and pipeline metrics.
- `:transport-network` owns the platform-independent encrypted socket client,
  pairing/authentication exchange, bounded input sender, authenticated media
  session negotiation and reconnect policy. WebRTC signalling remains in this
  module while the reference backend is available.
  Discovery and persistence remain above it and never enter input code.
- `:transport-bluetooth-hid` owns the reusable Android Bluetooth HID contract,
  generic Windows/Linux profile, descriptors and encoders.
- `:transport-bluetooth-desktop` owns Android-side RFCOMM diagnostics and the
  first playable gamepad client. It depends only on the domain scheduler and
  transport-independent protocol, never on direct HID or presentation code.
  Product trust and Home integration remain outside this initial adapter.
- `:app` is the Android composition root. It owns Compose UI, permissions,
  lifecycle, hardware input adapters, DNS-SD discovery, Android Keystore-backed
  trusted-desktop persistence and adapter registration. The
  active touchscreen layout is an app-owned, versioned set of normalized control
  positions and scales; it never enters the input or transport domain models.

## Runtime flow

```text
touch / Android InputDevice / direct USB
                  |
             InputRouter
                  |
        VirtualGamepadState
                  |
       GamepadOutputTransport
                  |
        Bluetooth HID / Wi-Fi / USB
```

Input implementations normalize platform events and never choose a destination.
`InputRouter` keeps touchscreen and detected physical-controller sources active
at the same time. Buttons are combined, while each analog axis and the D-pad are
owned by the last source that made an intentional non-neutral change; releasing
that source falls back to another source that is still held. This prevents idle
stick drift from permanently taking control. Output implementations receive only
logical gamepad or pointer reports and never import concrete input packages.

The visible gameplay surface is presentation state, not input-routing state.
Opening the virtual controller or the large mouse touchpad never disables a
physical controller, stops touchscreen input, or restarts the output transport.

`SessionUiViewModel` owns that presentation state as a small reducer with an
explicit active transport and surface. Bluetooth and Wi-Fi publish connection
events to it; they never set shared screen booleans or close another transport's
surface directly. Automatic surface selection opens the virtual controller
without a physical gamepad and the mouse touchpad with one. Once the user chooses
a surface manually, input-device changes do not override that choice. The
ViewModel also preserves the active surface across Activity recreation and
orientation changes.

Virtual-controller customization is stored as one `TouchscreenLayoutProfile`
with separate portrait and landscape variants. Both variants belong to the same
preset and are saved atomically. `SessionOrientationStore` independently keeps
the session rotation policy (`AUTO`, portrait, reverse portrait, landscape, or
reverse landscape), so the same policy applies to the virtual controller, mouse
touchpad, and sessions driven by a physical controller. A configuration change
selects the matching layout variant without restarting the transport session.

Wi-Fi, USB-tethered and Bluetooth-via-Desktop gameplay share
`GameplaySessionService`, a connected-device foreground lifecycle host. It owns
CPU wakefulness, the Wi-Fi performance lock when applicable, direct USB capture
and the persisted minimum needed to restore an interrupted Desktop session.
Transport clients remain independent and do not own these Android resources.
The service releases every resource on an explicit stop or terminal failure.
The Activity may be stopped, rotated, removed from the foreground, or have the
screen turned off without owning the transport lifetime.

`BridgePadApplication` is the process-level composition root and owns the shared
input router. Direct Bluetooth HID retains its specialized foreground service
because Android's HID profile callbacks belong to that service, but it uses the
same `GameplaySessionResources` contract for CPU wakefulness and direct USB
capture. Its minimal adapter, destination, capture mode and selected host are
persisted so Android can recreate an unexpectedly reclaimed sticky service.
Neither lifecycle host owns controller mapping or touchscreen state.
`BluetoothHidOutputTransport` implements the same domain port intended for future
Wi-Fi and USB desktop adapters.

Input reports form a data plane that is kept separate from the UI and service
control plane. Source adapters enqueue every changed state, `InputRouter`
serializes updates from independent adapters, and `OutputScheduler` retains
button, D-pad and trigger press/release transitions while coalescing intermediate
stick positions and analog values that do not cross a trigger edge. The
Bluetooth service arbitrates gamepad, pointer and keyboard output on a dedicated
thread, sends at most one logical input per scheduling slot, retries rejected
input without consuming it and uses a low-rate keepalive instead of repeating an
unchanged gamepad state continuously. Session notices and foreground-notification
updates occur only when slow lifecycle or capture state changes, never for each
controller event.

Wi-Fi gamepad delivery refreshes a complete snapshot every 8 ms (125 Hz) and
briefly retains discrete button, D-pad and trigger transitions so a fast tap is
not collapsed before its next report. Stale transitions are discarded rather
than replayed after congestion. On Desktop, each snapshot renews a short input
lease; 150 ms without a valid refresh neutralizes the virtual gamepad, and the
next snapshot resumes it normally. RFCOMM refreshes unchanged held state every
50 ms. Both limits are normative protocol constants verified against the same
shared contract data by Kotlin and Rust tests.

Wi-Fi pointer and keyboard delivery uses a transactional producer contract: an
input is consumed only after the bounded network queue accepts it.
Backpressure therefore coalesces relative movement at the source while retaining
button press/release and keyboard ordering. The pointer queue intentionally holds
only a short burst so a temporary network stall cannot turn into a long trail of
delayed cursor movement. TCP/TLS remains responsible for ordered delivery after
acceptance; reconnects discard relative deltas because replaying them could move
the cursor twice.

`SessionCoordinator` is the Android application boundary used by presentation
code. It owns a catalog of `OutputSessionAdapter` implementations and selects an
adapter by stable id. `MainActivity` does not start a transport service directly.
The product network flow is composed separately by
`NetworkGameplayController` and `NetworkDesktopCoordinator`. The Home selects a
discovered, trusted desktop while the manual IP/fingerprint route remains an
advanced diagnostic; both consume only `InputRouter`'s normalized state.

Connection method and output-adapter identity are deliberately separate. The
generic Bluetooth HID profile and future Wi-Fi or USB desktop receivers can all
target a PC while retaining independent protocol behavior.

Streaming follows the same separation rule at a stricter boundary. The
`streaming-core` module defines platform-independent media contracts. On
Windows, `bridgepad-windows-capture` captures the primary monitor with Windows
Graphics Capture. `bridgepad-windows-media` is the isolated safe Rust boundary
for BridgePad's own C++ Media Foundation backend. The native backend enumerates
only hardware H.264 MFTs, requires an asynchronous D3D11-aware transform,
converts and scales BGRA capture textures to NV12 on the GPU, and processes the
MFT through `METransformNeedInput`/`METransformHaveOutput`. No software fallback
is allowed silently. `bridgepad-windows-audio` independently opens the default
Windows playback endpoint through WASAPI loopback and encodes 48 kHz stereo
Opus. These capture and codec boundaries are retained independently of the
selected media transport.

The currently implemented reference backend is `bridgepad-webrtc`. It
packetizes the Annex-B video and Opus audio through ICE/DTLS/SRTP; RTCP feedback
controls bitrate and keyframes. Android's WebRTC decoder factory selects a
MediaCodec decoder and renders directly into a `SurfaceViewRenderer`, without a
Bitmap/CPU frame path. TCP/TLS `39394` authenticates that media session and
exchanges SDP; no encoded media crosses the signalling socket. The exact
configuration and validated scenarios are frozen in
[`streaming-baseline.md`](./streaming-baseline.md).

WebRTC is not the definitive local backend. The target local Wi-Fi/USB path is
BridgePad Media v1: authenticated session negotiation on the
control plane plus independent encrypted UDP audio and video flows with
explicit packet/frame identifiers, monotonic timestamps, bounded reordering,
stale-frame disposal, IDR feedback and adaptive bitrate. Android will decode
H.264 directly to a `Surface` with an application-owned low-latency
presentation policy. The WebRTC path stays available as the measured reference
until the dedicated path meets or beats it.

The playable local streaming screen can select QUIC DATAGRAM (default) or
UDP+AEAD in Android Settings. Both use the existing authenticated TCP/TLS media
connection to negotiate H.264/Opus and a one-use 32-byte session token. QUIC
also pins the Desktop's paired certificate on its per-session UDP endpoint.
For raw UDP, TLS delivers a fresh random 256-bit media key; the Desktop accepts
the one-use token only in an encrypted packet from the authenticated TLS peer's
IP address, and all subsequent video/audio and client heartbeat/stop packets use the Media v1
AES-256-GCM envelope with distinct directional nonce epochs and replay checks.
The Android JNI receivers share frame assembly and the same MediaCodec H.264/
Opus decoders. H.264 renders directly to a Surface and decoded PCM goes to
AudioTrack. Input remains on its independent connection. The synthetic WebRTC
test remains available as the reference path. These playable paths are still
experimental: feedback-driven bitrate, loss recovery, and measured audio/video
synchronization are not complete.

For playable UDP+AEAD video, Android uses permissive H.264 loss recovery by
default: it requests a fresh IDR after a missing frame but continues presenting
complete frames while waiting. Decoder failure or queue overflow still pauses
presentation until an IDR. The strict policy (flush and wait after any frame
gap) remains available only in Settings > Tests for the UDP recovery A/B
diagnostic, not as a gameplay option. In the measured injected-loss run,
permissive recovery had 13.7 render gaps over 50 ms per minute versus 25.6 for
strict, with no visible artifacts reported. Retain the strict implementation
only while this comparison is useful; remove the diagnostic branch if it is no
longer needed rather than leaving an unexercised production fallback.

The first BridgePad Media v1 increment is intentionally transport independent.
`bridgepad-media-protocol` owns the fixed 1200-byte datagram contract and Rust
codec; `:streaming-core` contains the matching Kotlin codec and shared golden
vector. `bridgepad-media` owns bounded packetization, assembly deadlines and a
deterministic loss/duplication/reordering harness. `bridgepad-media-metrics`
collects bounded rolling p50/p95/p99 timings for every pipeline stage. Reliable
offer/answer, feedback, keyframe request and stop/ack messages share a second
Kotlin/Rust golden vector and remain independent of the selected transport.
None of these modules opens a socket or imports WebRTC, capture, codec, Android
UI or Windows APIs.

The S2 transport bake-off keeps the same inner Media v1 bytes. QUIC DATAGRAM
authenticates those bytes with TLS 1.3. The raw UDP candidate wraps them in the
normative AES-256-GCM `BPA1` envelope implemented by
`bridgepad-media-protocol` and `:streaming-core`. The envelope uses ephemeral
session keys, direction-separated monotonic nonces and a bounded replay window; it is not a second
encryption layer for QUIC. Socket ownership and key agreement remain outside
the protocol codec so neither candidate can leak into capture, decode or UI.

Both Android bake-off receivers run in the same isolated Rust JNI library for
`arm64-v8a` and share the Rust frame assembler, timing model and lifecycle.
QUIC uses Quinn on Android and Desktop. Raw UDP uses the Rust AES-256-GCM
receiver from `bridgepad-media-protocol` and requests a 4 MiB socket receive
buffer, avoiding JVM allocation and cipher-creation costs in the comparison.
Its socket reader runs independently from authentication and frame assembly so
processing one packet cannot delay the recorded arrival time of the following
packet.
The diagnostic JNI calls are separate from the playable media JNI call. The
diagnostic QUIC sender creates an ephemeral self-signed identity and its
test-only client skips chain trust while retaining TLS handshake signature
verification. This exception is confined to the public diagnostic on UDP
`39496`; the playable receiver requires the paired certificate fingerprint.
UDP+AEAD remains on `39495`; both candidates carry the same Media v1 inner
datagrams and use the same strict assembly deadline.

Control/input remains independent of media. The default IP input path is
TCP/TLS `39393`; a Settings opt-in tests gamepad snapshots on a separate
per-session UDP+AEAD socket. The existing paired TLS control connection still
authenticates, negotiates a fresh UDP key and ephemeral port, carries pointer
and keyboard input plus heartbeats, and owns the gamepad lifetime. The Desktop
accepts UDP only from the TLS peer IP, authenticates each packet, rejects old
counters and neutralizes stale state after 150 ms. Button/trigger/D-pad edges
are sent twice in this experiment, while full snapshots continue at 125 Hz.
This is a playable transport test, not a decision to migrate pointer/keyboard
or to use UDP as the final input protocol. Bluetooth HID and RFCOMM are outside
this IP migration.

For Wi-Fi and USB control sessions, the Desktop now keeps one virtual gamepad
per authenticated phone identity, not per IP address or socket. A reconnect or
Wi-Fi/USB route switch revokes the previous generation and removes its virtual
device before activating the replacement; late reports and teardown from the
old connection cannot affect the new session. The registry caps managed IP
sessions at four and rejects a fifth with an explicit no-slot error. This is
the first multi-controller milestone, not full multi-transport support:
Bluetooth Desktop still has its separate RFCOMM receiver and needs
application-level identity before it can join the same registry. Direct HID
remains outside Desktop ownership, and media still supports one viewer.

Settings > Tests also offers a paired IP input transport comparison. It sends
300 serial Ping/Pong echoes through TCP/TLS and 300 through a separately keyed
UDP+AEAD probe on the same selected Wi-Fi or USB route. The UDP probe reuses the
playable BPI1 envelope but does not create a virtual gamepad or inject input.
The report includes RTT percentiles and unanswered UDP probes; it is a
transport measurement, not an Android-touch-to-game latency measurement.

Capture and media teardown are bounded. The capture callback releases its Media
Foundation/D3D resources before stopping capture, route loss is a normal session
termination and a completed media session must release its connection count
before another Wi-Fi or USB route is selected. Capture queues drop stale work
instead of accumulating delay, so congestion or stopping video cannot block,
recreate or neutralize input.

The setup model is a progressive dependency chain:

```text
destination -> connection -> target
```

Changing an earlier choice clears every dependent choice. `SessionPlanner`
resolves a compatible adapter and rejects unsupported combinations before an
Android service is started. A single compatible adapter remains an internal
detail; multiple compatible adapters require an explicit product policy or user
choice. Input routing defaults to automatic and is deliberately outside this
connection dependency chain. Physical-controller capture is an optional advanced
setting that becomes relevant only when compatible hardware is detected.

## Dependency rules

1. Dependencies point inward toward `:domain`.
2. `:domain` never references platform or presentation types.
3. Inputs produce normalized state; outputs consume normalized state.
4. Mapping is applied before transport encoding.
5. Transport-specific descriptors, drivers and connection state remain in their
   adapter.
6. UI reads application session state and invokes application actions; it does
   not import an output adapter.
7. Adding a transport must not require changes to an input implementation.
8. Adding an input must not require changes to an output implementation.

## Adding future support

- Wi-Fi and phone-to-PC USB use `:protocol` and require a BridgePad receiver on
  Windows or Linux to create the native virtual controller.
- `desktop/` is a Rust workspace. Its protocol crate consumes the same normative
  binary vectors as Kotlin; transport and virtual-device crates must depend on
  it instead of duplicating wire logic.
- `bridgepad-daemon` exposes the encrypted receiver as a reusable Rust library
  and retains a thin command-line binary for diagnostics. `bridgepad-desktop`
  owns only the Tauri window, tray and presentation commands; it calls the same
  server library and does not duplicate pairing, authentication, protocol or
  virtual-device behavior.
- `bridgepad-virtual-device` is the only contract consumed by desktop session
  code. Windows backend details remain in replaceable adapters. ViGEm is the
  current development/alpha adapter; the proposed production direction is a
  Microsoft-signed UMDF2 package with an XUSB personality, subject to the
  production acceptance gate below.
- Bluetooth HID remains an Android-only adapter and does not use the desktop
  protocol.
- Bluetooth via BridgePad Desktop is a separate adapter, not a mode of
  Bluetooth HID. The two must be mutually exclusive: direct HID creates the
  controller on the host, while Desktop Bluetooth sends BridgePad data to the
  receiver that creates XInput. Running both would expose duplicate controllers.
- The transport spike compares identical 125 Hz one-way report traffic over two
  role arrangements. Desktop measures delivery and cadence and returns an
  aggregate summary; lightweight periodic Ping/Pong samples measure RTT and
  keep the duplex link active without introducing per-report echo backpressure.
  RFCOMM uses Windows as the service provider and Android as the client. BLE GATT uses Android as the
  peripheral/server and Windows as the central/client because the central role
  is the broadly supported Windows path. This role difference belongs inside
  the adapters and does not change the domain or wire protocol direction.
- The RFCOMM gamepad path sends v1 `SessionStart`, complete
  `GamepadSnapshot` messages and `SessionStop` through a latest-state scheduler.
  BridgePad Desktop advertises the private RFCOMM service while its normal
  network receiver is running, and delegates decoded state to the existing
  ViGEm virtual-device boundary. It always neutralizes the device when a session
  stops or the connection disappears.
- Home exposes Desktop RFCOMM and direct HID as explicit, mutually exclusive
  Bluetooth outputs. It never silently changes output after a failed connection.
  Pairing a new PC belongs to the direct-HID flow; Desktop Bluetooth selects an
  existing operating-system bond and requires the companion to be open. The
  current RFCOMM product increment relies on that bond; BridgePad
  application-level authentication remains a release gate.
- The existing `GenericCompositeHidProfile` contains the Windows/Linux Bluetooth
  descriptor and report encoding. Additional PC profiles can implement the same
  contract without modifying input routing or the generic profile.

### Windows production virtual-device gate

The Windows backend is not production-ready until it satisfies all of these
requirements:

- automatic recognition in `joy.cpl`, current Steam and representative games;
- correct buttons, D-pad, sticks, independent triggers and rumble round-trip;
- reliable create, update, neutralize and destroy behavior during normal exit,
  crash, disconnect, sleep/resume and upgrade;
- clean installation, update and removal on every supported Windows version;
- no test-signing mode or locally trusted self-signed root certificate;
- a Microsoft-trusted driver package and reproducible installer build;
- normal gameplay operation without administrator privileges;
- latency and CPU use no worse than the accepted ViGEm development baseline;
- compatibility testing with representative security and anti-cheat software;
- an explicit Windows x64 and ARM64 support decision.
