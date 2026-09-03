# ADR 0002: Initial Bluetooth HID gamepad report

- Status: Experimental
- Date: 2026-09-03

## Context

The first product gate must establish whether supported Android hardware can
register as a Bluetooth HID Device and send gamepad input to Windows. The report
format should be small enough to inspect manually while representing the
controls required by the MVP.

## Decision

The Phase 1 spike uses HID report ID `1` with this nine-byte payload:

| Bytes | Field |
| --- | --- |
| 0-1 | 16 digital buttons |
| 2, low nibble | Hat switch; `0-7` directions and `8` neutral |
| 3-6 | X, Y, Rx and Ry signed 8-bit axes |
| 7-8 | Z and Rz unsigned 8-bit triggers |

The SDP subclass is Gamepad (`0x02`). The test action sets button bit 0 for
100 ms and then sends the neutral report.

## Consequences

- Windows should expose a generic HID/DirectInput gamepad rather than an XInput
  controller.
- The descriptor remains experimental until Gate B. Byte order and ranges may
  change based on `joy.cpl` and Steam Input evidence.
- Every disconnect or explicit stop attempts to send a neutral report before
  releasing the HID Device profile.
