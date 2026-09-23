# Compatibility

This document records hardware combinations tested with BridgePad. A device is
not considered supported until the relevant manual test has been completed and
its evidence recorded here.

## Primary Android test device

| Manufacturer | Model | Android version | Role | Status |
| --- | --- | --- | --- | --- |
| Samsung | Galaxy A35 | Android 16 (API 36) | Primary Android test device | Gates A, B and Phase 7 passed |

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
- Observed event-to-report latency: typically around 10 ms, generally between
  4 ms and 20 ms, with a maximum observed value of 26 ms
- DirectInput game outside Steam Input: not available for this test
- Known limitation: Steam recognizes BridgePad as a generic controller and
  requires initial manual button configuration before gameplay

### 2026-09-04 — Phase 5 touchscreen gamepad

- Result: PASS
- Android device: Samsung Galaxy A35, Android 16 (API 36)
- Steam Input gameplay using only the touchscreen: PASS
- Continuous touchscreen gameplay session of at least 30 minutes: PASS
- Five or more simultaneous touches: PASS
- Stick held while alternating buttons and D-pad directions: PASS
- Rapid pointer changes without cross-control interference: PASS
- Menu, background, screen lock and gesture cancellation neutralization: PASS
- No stuck input or inaccessible control was observed
- The fixed layout was accepted for the MVP; user-created named presets and
  analog touchscreen triggers remain post-MVP roadmap items

### 2026-09-04 — Background USB capture

- Android device: Samsung Galaxy A35, Android 16 (API 36)
- Input device: GameSir X5 Lite over USB-C
- Direct USB HID interface acquisition: PASS
- Input forwarding through the existing Bluetooth HID output: PASS
- Unchanged USB reports excluded from user-input metrics: PASS
- Capture-mode status correctly limits background/screen-off guidance to
  Background USB mode: PASS
- Known issue addressed after the first test: automatically inferred controls
  did not match every physical label; a persistent mapping wizard was added
- Saved mapping, every mapped control, app background, screen off, cable
  removal while held and capture-mode switching: PASS

### 2026-09-04 — Mouse touchpad and Phase 7 stabilization

- Android device: Samsung Galaxy A35, Android 16 (API 36)
- Host: Windows 11 with current stable Steam
- Composite Bluetooth HID gamepad and mouse recognition: PASS
- Integrated and full-screen touchpad movement and left click: PASS
- Signed horizontal and vertical relative movement: PASS
- Clean installation, onboarding and permission recovery: PASS
- New PC pairing and existing PC reconnection: PASS
- Touchscreen, Compatibility and Background USB input modes: PASS
- Saved USB mapping and live capture-mode switching: PASS
- Bluetooth, host disconnection and pairing cancellation recovery: PASS
- Twenty start/connect/stop cycles and continuous two-hour session: PASS
- Activity recreation, background return and diagnostic sharing: PASS
- Result: no blocking defect observed during Phase 7 validation

### 2026-09-16 — Adaptive multi-source Wi-Fi gameplay

- Android device: Samsung Galaxy A35, Android 16 (API 36)
- Host: Windows with BridgePad Desktop and its development virtual-gamepad backend
- Virtual controller over the encrypted Wi-Fi session in `joy.cpl`: PASS
- Virtual controller over the encrypted Wi-Fi session in a game: PASS
- Physical controller forwarded through the same Wi-Fi session in `joy.cpl` and
  a game: PASS
- Automatic virtual/physical input coexistence and initial session surface: PASS
- Live switching between virtual-controller and mouse-touchpad surfaces without
  interrupting the desktop controller: PASS
- Relative Windows mouse movement and click over Wi-Fi: PASS
- Compatibility and Background USB capture with the shared router: PASS
- Result: no blocking defect observed

### 2026-09-22 — USB tethering transport spike

- Android device: Samsung Galaxy A35, Android 16 (API 36)
- Host: Windows with the standard Microsoft Remote NDIS driver
- Android USB function: `rndis,adb`; USB subnet: `10.232.206.0/24`
- Encrypted BridgePad protocol over USB tethering: PASS
- Verified socket route: `10.232.206.111 -> 10.232.206.43`
- TLS: TLS 1.3 with `TLS_AES_128_GCM_SHA256`
- Samples: 250
- TLS handshake: 64.329 ms
- RTT: p50 2.055 ms, p95 3.049 ms, p99 5.337 ms
- Compared Wi-Fi evidence: p50 9.114 ms, p95 14.597 ms, p99 16.553 ms
- Playable virtual-controller session through the same USB route: PASS
- Gamepad, mouse touchpad and keyboard behavior: PASS
- Normal Desktop discovery and pairing over the USB route: PASS
- Trusted-session authentication over USB after pairing: PASS
- Cable removal followed by a Wi-Fi session with the same trusted identity,
  without pairing again: PASS
- Abrupt cable removal safely neutralized the active virtual controller: PASS
- Verified session source transition: `10.232.206.111` over USB to
  `192.168.15.3` over Wi-Fi
- Status: transport reachability, encryption, automatic discovery, pairing,
  playable input, cable-removal neutralization and cross-interface trust
  continuity passed; reconnection and screen-off behavior remain pending
