# Compatibility

This document records hardware combinations tested with BridgePad. A device is
not considered supported until the relevant manual test has been completed and
its evidence recorded here.

## Primary Android test device

| Manufacturer | Model | Android version | Role | Status |
| --- | --- | --- | --- | --- |
| Samsung | Galaxy A35 | Android 16 (API 36) | Primary device for Gate A | Phase 0 passed |

## Test results

### 2026-09-03 — Phase 0 baseline

- BridgePad version: 0.1.0
- Device: Samsung Galaxy A35
- System: Android 16 (API 36)
- Result: PASS
- Evidence: debug APK installed and launched successfully; the application
  displayed the expected app version and device information.

### 2026-09-03 — Phase 1 partial validation

- Bluetooth HID registration and Windows connection: PASS
- Digital test button press/release in `joy.cpl`: PASS
- Foreground service while the app is in background: PASS
- Axis ranges, screen-off duration and long-session stability: pending
