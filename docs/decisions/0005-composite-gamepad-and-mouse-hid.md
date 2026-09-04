# ADR 0005: Composite gamepad and relative mouse HID reports

- Status: Accepted
- Date: 2026-09-04

## Context

BridgePad needs a simple touchscreen surface that controls the host pointer.
Emulating a DualSense touch surface is outside the MVP scope. The Bluetooth HID
application previously exposed only report ID 1 for the gamepad.

## Decision

Keep the existing gamepad payload unchanged as report ID 1 and add a separate
standard relative mouse application collection as report ID 2. Mouse reports
contain three button bits followed by signed relative X and Y bytes. The MVP UI
uses drag for pointer movement and a short tap for left click.

## Consequences

- Windows can use the pointer without BridgePad Desktop or additional drivers.
- Steam continues to receive the unchanged gamepad report.
- Existing Bluetooth pairings may cache the old report descriptor and need to
  be removed and created again once after this change.
- Mouse movement is independent from the right gamepad stick.
- Scrolling, right click, gestures and DualSense-compatible touch data remain
  future work.

