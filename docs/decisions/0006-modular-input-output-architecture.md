# ADR 0006: Modular input and output architecture

- Status: Accepted
- Date: 2026-09-05

## Context

The MVP proved Android input and Bluetooth HID output, but the application must
later support Wi-Fi and USB desktop bridges, Windows and Linux receivers, and
research into console destinations. The original single-module structure allowed
the Bluetooth service to coordinate concrete input implementations and allowed
UI code to depend on Bluetooth session types.

## Decision

- Split the project into pure Kotlin `:domain`, platform-independent `:protocol`
  and Android `:app` modules.
- Normalize all controller sources into `VirtualGamepadState` before output.
- Define input and output ports in `:domain`.
- Compose Android inputs in an application-scoped `InputRouter`; output adapters
  consume only its normalized state and do not own its lifecycle.
- Keep HID descriptors and encoding inside the Bluetooth output adapter.
- Keep the desktop bridge protocol versioned and independent of implementation
  language.
- Treat `:app` as the composition root for Android APIs, UI and persistence.

This supersedes the temporary single-module decision in ADR 0001 now that reuse
and isolation are concrete requirements.

## Consequences

- Domain and protocol behavior can be tested without an Android runtime.
- New inputs and outputs can evolve independently behind explicit contracts.
- Bluetooth behavior remains unchanged but no longer owns the input pipeline.
- Some Android orchestration remains in `MainActivity` and can be moved into
  view models/use cases incrementally without changing module boundaries.
- Desktop and console implementations remain separate deliverables with their
  own platform restrictions.
