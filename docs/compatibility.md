# Compatibility

This document records hardware combinations tested with BridgePad. A device is
not considered supported until the relevant manual test has been completed and
its evidence recorded here.

## Primary Android test device

| Manufacturer | Model | Android version | Role | Status |
| --- | --- | --- | --- | --- |
| Samsung | Galaxy A35 | Android 16 (API 36) | Primary Android test device | Gates A and B passed |

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

### 2026-09-03 — Phase 3 USB gamepad input

- Android device: Samsung Galaxy A35, Android 16 (API 36)
- Input device: GameSir X5 Lite over USB-C
- Device detection and reconnection without restarting the app: PASS
- Face buttons, bumpers, Start, Select, L3 and R3: PASS
- D-pad directions and neutral state: PASS
- Both sticks, range, center and deadzone: PASS
- Independent triggers: PASS
- Removal while controls are active and state neutralization: PASS

### 2026-09-03 — Phase 4 end-to-end Gate B

- Result: GO
- Pipeline from GameSir X5 Lite through the Galaxy A35 to Windows: PASS
- All buttons, D-pad directions, sticks and independent triggers in `joy.cpl`: PASS
- Simultaneous sticks, triggers, diagonals and multiple buttons: PASS
- GameSir removal and Bluetooth disconnection during active input: PASS; the
  controller returned to neutral without stuck inputs
- Steam Input generic-controller configuration and gameplay: PASS
- Continuous gameplay session of at least one hour: PASS
- Observed HID output rate: approximately 73 Hz
- Observed event-to-report latency: at most approximately 26 ms
- DirectInput game outside Steam Input: not available for this test
- Known limitation: Steam recognizes BridgePad as a generic controller and
  requires initial manual button configuration before gameplay
