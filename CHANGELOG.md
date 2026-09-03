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
- Grace period for Windows-initiated HID connections and support for Android's
  already-plugged host callback, avoiding competing connection requests.

### Changed

- Product planning documents are now kept in the private documentation area.

## [0.1.0]

- Initial development version.
