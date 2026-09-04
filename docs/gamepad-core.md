# Gamepad core specification

The BridgePad gamepad core is independent of Android and Bluetooth. It converts
input events into an immutable logical state and then into the nine-byte HID
input report defined by the initial descriptor.

## Pipeline

```text
RawInputEvent
    -> SourceStateReducer
    -> SourceGamepadState
    -> InputMerger
    -> VirtualGamepadState
    -> OutputScheduler
    -> HidReportEncoder
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

## HID input report

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

The output scheduler consolidates analog updates to the newest state at its
configured rate. Button and D-pad transitions are queued so a press followed by
a release between two output ticks is still emitted as two distinct reports.
