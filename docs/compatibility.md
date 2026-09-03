# Compatibility

This document records hardware combinations tested with BridgePad. A device is
not considered supported until the relevant manual test has been completed and
its evidence recorded here.

## Primary Android test device

| Manufacturer | Model | Android version | Role | Status |
| --- | --- | --- | --- | --- |
| Samsung | Galaxy A35 | Android 16 (API 36) | Primary device for Gate A | Gate A passed |

## Test results

### 2026-09-03 — Phase 0 baseline

- BridgePad version: 0.1.0
- Device: Samsung Galaxy A35
- System: Android 16 (API 36)
- Result: PASS
- Evidence: debug APK installed and launched successfully; the application
  displayed the expected app version and device information.

### 2026-09-03 — Phase 1 Bluetooth HID Gate A

- Result: GO
- Bluetooth HID registration and Windows connection: PASS
- Digital test button press/release in `joy.cpl`: PASS
- X-axis minimum, center and maximum in `joy.cpl`: PASS
- Foreground service while the app is in background: PASS
- Screen-off session continuity: PASS
- Twenty start/connect/stop cycles: PASS
- Session of at least 30 minutes: PASS
- Steam Input generic-controller detection: PASS
- Steam Input configuration wizard received the digital test button: PASS
- Known behavior: Windows may retain the paired controller and display cached
  connection state after the Android HID session ends. No HID reports are sent
  while the BridgePad session is inactive.
