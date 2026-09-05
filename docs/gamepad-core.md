# Gamepad core specification

The BridgePad gamepad core is independent of Android and every output transport.
It converts input events into an immutable logical state. Transport adapters
then encode that state for Bluetooth HID, the desktop bridge or another target.

## Pipeline

```text
RawInputEvent
    -> SourceStateReducer
    -> SourceGamepadState
    -> InputMerger
    -> VirtualGamepadState
    -> GamepadOutputTransport
       -> Bluetooth HID encoder
       -> Bridge protocol encoder
       -> future output adapter
```

Each input source has a stable, non-empty `SourceId`. Buttons from active
sources are combined using a logical union. Ownership of every analog axis and
the D-pad is explicit; an absent owner contributes the neutral value. Removing
a source immediately removes all of its contributions.

Stick axes use `[-1, 1]`, while triggers use `[0, 1]`. Values are clamped before
they enter a source state. Stick deadzones are radial and can optionally rescale
the remaining range so full travel is retained.

Session lifecycle is represented separately from controller input. Stopping a
session clears pending output and produces a neutral gamepad state.

`InputRouter` is the Android composition point for touchscreen, Android
`InputDevice` and direct USB sources. An output adapter consumes only the routed
logical state and cannot import those concrete input implementations.

`OutputScheduler` is transport-neutral timing policy. An output transport may
use it to preserve short button transitions while rate-limiting analog updates.

## Bluetooth HID input report

The report ID is passed separately to the Android HID API and is not present in
the payload below.

| Byte | Field | Encoding |
| --- | --- | --- |
| 0-1 | Buttons 1-16 | Little-endian bit field |
| 2 | D-pad | Hat values 0-7; neutral is 8 |
| 3 | Left X | Signed `[-127, 127]` |
| 4 | Left Y | Signed `[-127, 127]` |
| 5 | Right X | Signed `[-127, 127]` |
| 6 | Right Y | Signed `[-127, 127]` |
| 7 | Left trigger | Unsigned `[0, 255]` |
| 8 | Right trigger | Unsigned `[0, 255]` |

The neutral fixture is `00 00 08 00 00 00 00 00 00`. Unit tests also preserve
known fixtures for button, D-pad, stick and trigger extremes.

The HID descriptor and encoder belong to the Android Bluetooth adapter, not to
the domain module. The neutral fixture is retained as a Bluetooth protocol test.
