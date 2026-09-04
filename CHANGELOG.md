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
- Phase 5 hardware validation for multitouch touchscreen gameplay on the Galaxy
  A35 through Steam Input.
- Persistent first-run onboarding with Bluetooth, pairing and input guidance.
- Task-oriented session home with touchscreen/physical-input selection and
  device HID preflight information.
- Explicit controlled reconnection and protection against concurrent connection
  attempts.
- Privacy-conscious structured session logs and copy/share diagnostic reports.
- Actionable permission, discoverability, connection and shutdown feedback.
- Switchable physical-controller capture modes: Android compatibility mode and
  direct USB HID background mode.
- Generic USB HID report-descriptor parsing for buttons, sticks, D-pad and
  common trigger usages while the screen is off or BridgePad is backgrounded.

### Fixed

- Direct USB polling no longer counts unchanged periodic HID reports as user
  input in the live bridge metrics.
- Physical-gamepad status now states that background and screen-off operation
  is available only in Background USB mode.
- Direct USB gamepad mapping wizard for face buttons, shoulders, system
  buttons, stick clicks, stick axes and triggers.
- Persistent per-controller USB mappings keyed by vendor, product and HID
  report descriptor, automatically restored on later connections.
- MVP privacy policy and repeatable Gate C release checklist.
- Local app data is excluded from Android backup so controller mappings and
  session preferences remain on the device where they were created.
- USB mapping now follows the familiar face buttons, D-pad, left controls,
  right controls, shoulders/triggers and system-button order, with mapped-A
  skip support for unavailable controls.
- The USB mapping wizard can return to and replace the previous binding instead
  of resetting the current empty step.
- The USB mapping wizard rejects physical buttons, D-pad directions and axes
  already assigned to a different logical control and identifies the conflict.
- Opposite directions of one logical stick axis must now come from the same
  physical axis with a consistent orientation.
- Touchscreen and physical gamepad input can be switched during an active HID
  session without reconnecting the controller seen by Windows.
- Input-source transitions send a neutral HID report and exclude the inactive
  source from merging to prevent duplicate or stuck controls.
- Phase 5 hardware validation for multitouch touchscreen gameplay on the Galaxy
  A35 through Steam Input.

### Changed

- Product planning documents are now kept in the private documentation area.
- The Phase 1 artificial button and axis controls were replaced by physical
  gamepad input.
- The post-MVP roadmap now includes configurable analog touchscreen triggers
  driven by touch position or drag distance.
- The post-MVP roadmap now includes a mouse touchpad and research into a
  PlayStation-style clickable touch surface for Steam Input.

## [0.1.0]

- Initial development version.
