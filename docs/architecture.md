# Architecture

BridgePad separates controller input, logical behavior and output transport so
new connection methods and destinations do not change existing input adapters.

## Gradle modules

```text
:app ---------------------> :protocol -----------------> :domain
  |                                                       ^
  +----------------> :transport-network -----------------+
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
- `:transport-network` owns the platform-independent encrypted socket client,
  pairing/authentication exchange, bounded gamepad sender and reconnect policy.
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

Active Wi-Fi gameplay is hosted by `NetworkSessionService`, a connected-device
foreground service. The service holds CPU and Wi-Fi locks only for the lifetime
of the playable session, keeps direct USB capture alive when Background USB is
selected, and releases every resource on stop or terminal failure. The Activity
may be stopped, rotated, removed from the foreground, or have the screen turned
off without owning the network transport lifetime.

`BridgePadApplication` is the process-level composition root and owns the shared
input router. The Bluetooth foreground service is only an Android lifecycle host
for the Bluetooth adapter; it neither creates nor destroys the input pipeline and
does not own USB capture, controller mapping or touchscreen state.
`BluetoothHidOutputTransport` implements the same domain port intended for future
Wi-Fi and USB desktop adapters.

Input reports form a data plane that is kept separate from the UI and service
control plane. Source adapters enqueue every changed state, `InputRouter`
serializes updates from independent adapters, and `OutputScheduler` retains
button/D-pad transitions while coalescing intermediate analog positions. The
Bluetooth service arbitrates gamepad, pointer and keyboard output on a dedicated
thread, sends at most one logical input per scheduling slot, retries rejected
input without consuming it and uses a low-rate keepalive instead of repeating an
unchanged gamepad state continuously. Session notices and foreground-notification
updates occur only when slow lifecycle or capture state changes, never for each
controller event.

Wi-Fi pointer and keyboard delivery uses the same transactional producer
contract: an input is consumed only after the bounded network queue accepts it.
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
  Microsoft-signed UMDF2 package with an XUSB personality, subject to ADR 0010's
  acceptance gate.
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
- For an already paired Bluetooth PC, Home tries the Desktop RFCOMM path first.
  Direct HID is never started during that attempt. If Desktop is unavailable,
  the user can explicitly choose the direct-HID fallback. Pairing a new PC still
  uses the direct Bluetooth flow. The current RFCOMM product increment relies on
  the operating-system Bluetooth bond; BridgePad application-level
  authentication remains a release gate.
- The existing `GenericCompositeHidProfile` contains the Windows/Linux Bluetooth
  descriptor and report encoding. Additional PC profiles can implement the same
  contract without modifying input routing or the generic profile.
