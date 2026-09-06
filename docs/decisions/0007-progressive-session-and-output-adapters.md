# ADR 0007: Progressive session planning and destination-specific output adapters

- Status: Accepted
- Date: 2026-09-05

## Context

Connection method alone cannot identify an output implementation. Generic PC
HID and future PlayStation experiments may all use Bluetooth but require
different descriptors, device identities and host protocol behavior. The
existing setup validator and activity orchestration were specific to a paired
Windows PC over Bluetooth.

## Decision

- Model setup in dependency order: destination, connection, target and input.
- Clear downstream selections whenever an upstream selection changes.
- Give every output adapter a stable id and a descriptor declaring its supported
  destinations, connection method and target-selection behavior.
- Resolve and validate session drafts through the platform-independent
  `SessionPlanner` before starting an output implementation.
- Route Android presentation actions through `SessionCoordinator` and
  `OutputSessionAdapter`, not directly to a concrete service.
- Represent each Bluetooth HID device personality as a separate
  `BluetoothHidProfile`, including SDP metadata, report descriptor, encoders and
  host-request hooks.
- Keep the generic Windows/Linux HID implementation in the independent
  `:transport-bluetooth-hid` Android library. Console experiments belong in
  sibling adapter/profile modules.

## Consequences

- The UI can be redesigned independently around the progressive setup model.
- Multiple Bluetooth profiles no longer collide under one generic transport id.
- Unsupported destination/connection combinations cannot silently fall back to
  the generic Windows/Linux HID profile.
- Adding a console profile still depends on protocol research and hardware
  feasibility; modularity does not guarantee that a console will accept it.
