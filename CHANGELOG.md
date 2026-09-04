# Changelog

All notable changes to BridgePad will be documented in this file.

The project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Initial Android project using Kotlin and Jetpack Compose.
- GitHub Actions checks for unit tests, lint and debug builds.
- Device baseline screen with app and Android system information.
- Repeatable build, installation and hardware-testing documentation.
- Architecture decision record for native Android and API 28.
- Brazilian Portuguese README and a public English documentation policy.
- Bluetooth HID Device spike with runtime permission handling, foreground
  service, paired-host selection and a test gamepad report.
- Experimental HID descriptor and Gate A test procedure.
- Reusable warning and error cards for actionable session feedback.
- Deterministic HID cleanup when Bluetooth changes state, preventing stale hosts
  and late callback errors after a session ends.
- Guided PC pairing with temporary Bluetooth discoverability, live bond updates
  and filtering of paired devices to computers.
- Visible pairing-mode feedback with automatic timeout and completion messages.
- Connection-state checks that avoid duplicate HID connection requests while
  Windows is already connecting or connected.
- Interactive X-axis control with exact minimum, center and maximum test values.
- Grace period that avoids competing connection requests after explicit host
  selection.
- Explicit host authorization so starting HID always presents the existing-PC
  or new-pairing choice instead of reconnecting automatically.
- Gate A approval evidence for Bluetooth HID on the Galaxy A35, Windows and
  Steam Input.
- Platform-independent gamepad state, input reducer, multi-source merger,
  radial deadzone, HID report encoder and rate-controlled output scheduler.
- JVM fixtures and unit tests for logical controls, source removal, merging and
  HID report bytes.
- Public specification for the logical gamepad pipeline and report layout.
- USB/USB-C gamepad discovery and hot-plug handling using Android input APIs.
- Physical gamepad mapping for buttons, D-pad, sticks and separate triggers.
- On-device diagnostic view with device identity, raw axes and normalized state.
- End-to-end forwarding from a USB gamepad to the connected Bluetooth HID host.
- Live input rate, HID output rate and approximate event-to-report latency.
- Gate B approval evidence for the complete GameSir-to-Steam Input pipeline.
- Fixed landscape touchscreen controller with two sticks, eight-direction
  D-pad, face buttons, shoulders, digital triggers and system buttons.
- Independent multitouch handling and safe neutralization on cancellation,
  navigation or loss of focus.
- Touchscreen input integration with the existing gamepad merger and Bluetooth
  HID output pipeline.

### Changed

- Product planning documents are now kept in the private documentation area.
- The Phase 1 artificial button and axis controls were replaced by physical
  gamepad input.

## [0.1.0]

- Initial development version.
