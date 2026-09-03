# ADR 0001: Native Android and minimum SDK

- Status: Accepted
- Date: 2026-09-03

## Context

BridgePad depends directly on Android input, Bluetooth HID, USB, sensor and
foreground-service APIs. The MVP needs a stable platform baseline without
introducing a cross-platform abstraction before the core behavior is proven.

## Decision

- Build the Android application natively with Kotlin and Jetpack Compose.
- Use `dev.jonalakas.bridgepad` as the namespace and application ID.
- Support Android 9 and newer by setting `minSdk = 28`.
- Use semantic version names and monotonically increasing integer version
  codes. Initial development starts at version name `0.1.0`, version code `1`.
- Keep a single Gradle application module until isolation or reuse justifies
  additional modules.

## Consequences

- Android platform integrations can use their native APIs directly.
- Devices older than Android 9 are outside the supported scope.
- Bluetooth HID behavior can still vary by manufacturer, so support claims
  require tests on real hardware and a published compatibility matrix.
- A future iOS implementation will be a separate native application and may
  reuse protocol and domain concepts, not Android code.
