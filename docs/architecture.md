# Architecture

BridgePad separates controller input, logical behavior and output transport so
new connection methods and destinations do not change existing input adapters.

## Gradle modules

```text
:app ---------------------> :protocol -----------------> :domain
  |                                                       ^
  +-------------------------------------------------------+
```

- `:domain` is pure Kotlin. It owns logical controller state, mapping, merging,
  session configuration, scheduling policy and input/output ports. It must not
  import Android, Compose, Bluetooth, USB APIs or Android resources.
- `:protocol` owns versioned messages shared with a future BridgePad receiver.
  It depends only on `:domain`. A published wire format must remain independent
  of the desktop implementation language.
- `:app` is the Android composition root. It owns Compose UI, permissions,
  lifecycle, hardware input adapters, persistence and output adapters.

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
   Bluetooth HID / Wi-Fi / USB / future adapter
```

Input implementations normalize platform events and never choose a destination.
`InputRouter` selects and merges them. Output implementations receive only
logical gamepad or pointer reports and never import concrete input packages.

`BridgePadApplication` is the process-level composition root and owns the shared
input router. The Bluetooth foreground service is only an Android lifecycle host
for the Bluetooth adapter; it neither creates nor destroys the input pipeline and
does not own USB capture, controller mapping or touchscreen state.
`BluetoothHidOutputTransport` implements the same domain port intended for future
Wi-Fi, USB and console-specific adapters.

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
- Bluetooth HID remains an Android-only adapter and does not use the desktop
  protocol.
- PlayStation and Xbox support must be isolated in destination-specific adapters.
  This architecture provides the boundary but does not bypass each platform's
  protocol, authentication or hardware restrictions.
