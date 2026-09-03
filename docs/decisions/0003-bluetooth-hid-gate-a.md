# ADR 0003: Bluetooth HID Gate A

- Status: Accepted
- Date: 2026-09-03
- Decision: GO

## Context

Bluetooth HID Device support is the largest platform-specific risk for the
BridgePad MVP. Gate A requires evidence that the primary Android device can
register a public HID profile, connect to Windows, transmit digital and analog
reports and maintain the session through relevant lifecycle scenarios.

## Evidence

The spike was tested with a Samsung Galaxy A35 running Android 16 (API 36) and
a Windows host. The following checks passed:

- public `BluetoothHidDevice` application registration;
- Windows pairing and HID connection;
- digital button press and release in `joy.cpl`;
- X-axis minimum, center and maximum in `joy.cpl`;
- background and screen-off continuity;
- twenty connection lifecycle cycles;
- a session lasting at least 30 minutes;
- discovery by Steam Input as a generic controller;
- receipt of the digital button in Steam's controller configuration wizard.

## Decision

Gate A is approved as `GO`. Bluetooth HID remains the primary output for the
MVP, and development may proceed to the platform-independent gamepad core.

## Consequences

- The Galaxy A35/Android 16 combination is the first confirmed compatible test
  platform, not the only intended supported device.
- The current descriptor remains experimental until the full control set is
  validated at Gate B.
- Windows pairing and cached connection presentation require clear user-facing
  guidance but do not block the HID transport.
