# Architecture

BridgePad separates controller input, logical behavior and output transport so
new connection methods and destinations do not change existing input adapters.

## Gradle modules

```text
:app ---------------------> :protocol -----------------> :domain
  |                                                       ^
  +----------------> :transport-network -----------------+
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
- `:transport-network` owns the platform-independent encrypted socket client.
  Its first API is a certificate-pinned diagnostic probe; discovery, trust
  persistence and product session lifecycle will build on it without entering
  Compose or Bluetooth modules.
- `:transport-bluetooth-hid` owns the reusable Android Bluetooth HID contract,
  generic Windows/Linux profile, descriptors and encoders.
- `:app` is the Android composition root. It owns Compose UI, permissions,
  lifecycle, hardware input adapters, persistence and adapter registration. The
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
`InputRouter` selects and merges them. Output implementations receive only
logical gamepad or pointer reports and never import concrete input packages.

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
Bluetooth service sends scheduled reports on a dedicated output thread. Session
notices and foreground-notification updates occur only when slow lifecycle or
capture state changes, never for each controller event.

`SessionCoordinator` is the Android application boundary used by presentation
code. It owns a catalog of `OutputSessionAdapter` implementations and selects an
adapter by stable id. `MainActivity` does not start a transport service directly.

Connection method and output-adapter identity are deliberately separate. The
generic Bluetooth HID profile and future Wi-Fi or USB desktop receivers can all
target a PC while retaining independent protocol behavior.

The setup model is a progressive dependency chain:

```text
destination -> connection -> target -> input
```

Changing an earlier choice clears every dependent choice. `SessionPlanner`
resolves a compatible adapter and rejects unsupported combinations before an
Android service is started. A single compatible adapter remains an internal
detail; multiple compatible adapters require an explicit product policy or user
choice.

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
- `bridgepad-virtual-device` is the only contract consumed by desktop session
  code. Windows backend details remain in replaceable adapters. ViGEm is the
  current development/alpha adapter; the proposed production direction is a
  Microsoft-signed UMDF2 package with an XUSB personality, subject to ADR 0010's
  acceptance gate.
- Bluetooth HID remains an Android-only adapter and does not use the desktop
  protocol.
- The existing `GenericCompositeHidProfile` contains the Windows/Linux Bluetooth
  descriptor and report encoding. Additional PC profiles can implement the same
  contract without modifying input routing or the generic profile.
