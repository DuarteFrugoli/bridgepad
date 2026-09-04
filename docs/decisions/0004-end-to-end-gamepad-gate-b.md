# ADR 0004: End-to-end gamepad Gate B

- Status: Accepted
- Date: 2026-09-03
- Decision: GO

## Context

Gate B determines whether the complete GameSir-to-Windows pipeline represents
every required gamepad control correctly and is stable enough to justify
building the touchscreen controller.

## Evidence

The pipeline was tested with a GameSir X5 Lite connected over USB-C to a Samsung
Galaxy A35 running Android 16 (API 36), forwarding reports over Bluetooth HID to
Windows. The following checks passed:

- every required button, D-pad direction, stick and trigger in `joy.cpl`;
- simultaneous analog movement, diagonals, triggers and multiple buttons;
- physical gamepad removal and Bluetooth disconnection during active input,
  with no stuck controls;
- Steam Input configuration and gameplay;
- a continuous gameplay session lasting at least one hour.

The observed output rate was approximately 73 Hz and the observed event-to-
report latency did not exceed approximately 26 ms. These measurements produced
responsive gameplay during the validation session.

## Decision

Gate B is approved as `GO`. The current HID descriptor and logical report layout
represent the complete MVP control set correctly, so development may proceed to
the fixed touchscreen controller.

## Consequences

- The current report format becomes the compatibility baseline for subsequent
  phases and must not change without a new compatibility decision.
- Steam detects BridgePad as a generic DirectInput controller. Initial manual
  button configuration is currently required and remains a product usability
  limitation.
- Automatic Steam mapping should be investigated separately; it does not block
  Gate B because the configured controller works correctly in gameplay.
- A non-Steam DirectInput game was not available for this validation and remains
  useful additional compatibility evidence when one becomes available.
