# BridgePad

[English](./README.md) | [Português (Brasil)](./README.pt-BR.md)

> **Connect any controller. Use any input. Play anywhere.**

BridgePad is a free and open-source project designed to turn Android phones and tablets into a **universal gamepad bridge**.

Instead of being limited to touchscreen controls, BridgePad aims to receive input from multiple sources — such as USB gamepads, mobile controllers, touchscreen controls and sensors — normalize them into a common virtual gamepad state, and forward that state to other devices through different connection methods.

The project is currently in early development.

---

## What is BridgePad?

BridgePad is not intended to be just another "phone as a controller" app.

The long-term goal is to make the Android device act as an **input hub** between controllers and target devices.

For example:

```text
GameSir X5 Lite
      │
    USB-C
      ▼
   Android
      │
  BridgePad
      │
 Bluetooth HID
      ▼
   Windows
      │
      ▼
 Steam / Games
```

Or, in the future:

```text
Bluetooth Controller
        │
        ▼
     Android
        │
    BridgePad
        │
      Wi-Fi
        ▼
 BridgePad Desktop
        │
        ▼
 Virtual Gamepad
        │
        ▼
       Game
```

The source of the input and the destination are intentionally kept independent.

Conceptually:

```text
InputSource
     ↓
Input Mapping
     ↓
VirtualGamepadState
     ↓
OutputTarget
```

This makes it possible to add new controllers, input methods and output protocols without rewriting the core of the application.

---

## Project Goals

BridgePad aims to support different input sources such as:

- Touchscreen controls
- USB gamepads
- USB-C mobile controllers
- Bluetooth gamepads
- Gyroscope
- Accelerometer
- Keyboard and mouse in the future

And different output methods such as:

- Bluetooth HID
- LAN / Wi-Fi
- USB through a desktop companion
- Additional output targets in the future

Not every combination will be available in the first release.

---

## MVP

The first BridgePad release will deliberately have a limited scope.

The initial goal is:

```text
Touchscreen
     OR
USB / USB-C Gamepad
        │
        ▼
 BridgePad Android
        │
        ▼
 Bluetooth HID
        │
        ▼
 Windows
        │
        ▼
  Steam Input
        │
        ▼
      Game
```

### Planned MVP support

- Android 9+ (API 28+)
- Touchscreen controller
- Relative mouse touchpad with tap-to-left-click
- USB / USB-C gamepads recognized by Android
- Direct USB HID capture with saved per-controller mapping
- Bluetooth HID gamepad output
- Windows 10
- Windows 11
- Steam Input
- Basic input mapping
- Basic deadzone handling
- Connection status
- Input diagnostics
- Diagnostic log export

The GameSir X5 Lite is the primary controller being used during early development and hardware validation.

The implementation itself, however, will not be tied specifically to GameSir hardware.

---

## What the MVP will not include

The initial release does **not** aim to provide:

- Direct XInput emulation
- Rumble / force feedback
- Bluetooth gamepad input while Android is simultaneously acting as a Bluetooth HID device
- Editable touchscreen layouts
- Advanced controller profiles
- Macros
- Guided calibration
- LAN / Wi-Fi output
- BridgePad Desktop
- USB output to a computer
- Linux support
- Xbox console support
- PlayStation console support
- Nintendo console support

Windows and Linux desktop support are planned first. PlayStation and Xbox are
long-term target platforms after the desktop bridge is stable; their exact
connection method still requires research and prototypes because console
authentication is proprietary. macOS and iOS are not currently planned.

---

## Physical Gamepad Capture Modes

When a physical gamepad is selected, BridgePad offers two capture modes:

- **Compatibility** uses Android's normalized `InputDevice`, `KeyEvent` and
  `MotionEvent` APIs. It supports more controllers, but BridgePad must remain
  visible with the screen on.
- **Background USB** opens a standard USB HID controller directly. Compatible
  controllers continue working while BridgePad is backgrounded or the screen
  is off.

Background USB includes a guided mapping wizard. Its profiles are stored on the
device and restored using the controller vendor ID, product ID and HID report
descriptor. Changing capture mode does not reconnect or change the Bluetooth
gamepad seen by Windows and Steam.

---

## Bluetooth HID

The first output backend will use Android's Bluetooth HID capabilities.

The goal is for the computer to see the Android device as a **generic Bluetooth HID gamepad** without requiring BridgePad software to be installed on the computer.

```text
Android
   │
Bluetooth HID
   │
   ▼
Windows
```

The initial device will be a generic HID gamepad, not an Xbox/XInput controller.

Because of this, **Steam Input will be the primary compatibility layer for the MVP**.

Games that only support XInput may not recognize the controller directly.

---

## Architecture

BridgePad is designed around an internal platform-independent gamepad representation.

The simplified pipeline is:

```text
Android / Touch Input
        │
        ▼
  RawInputEvent
        │
        ▼
    InputMapper
        │
        ▼
 SourceGamepadState
        │
        ▼
    InputMerger
        │
        ▼
VirtualGamepadState
        │
        ▼
  OutputScheduler
        │
        ▼
    OutputTarget
```

### VirtualGamepadState

`VirtualGamepadState` represents the logical controller state without depending on:

- Android key codes
- HID report bytes
- Controller manufacturer
- Connection method
- Destination platform

It contains normalized controls such as:

```text
Face buttons
D-Pad

Left Stick
Right Stick

Left Trigger
Right Trigger

Left Bumper
Right Bumper

Start
Select

L3
R3
```

Analog values use normalized ranges:

```text
Sticks:
-1.0 ───── 0 ───── +1.0

Triggers:
 0.0 ───────────── 1.0
```

Internally, controls use logical positions such as:

```text
FACE_SOUTH
FACE_EAST
FACE_WEST
FACE_NORTH
```

instead of assuming Xbox, PlayStation or Nintendo button labels.

---

## Multiple Input Sources

The architecture is designed so that each input source maintains its own state.

For example:

```text
USB Gamepad ──────┐
                  │
Touchscreen ──────┼──► InputMerger ─► VirtualGamepadState
                  │
Gyroscope ────────┘
```

This allows BridgePad to eventually combine different input methods.

It also prevents problems such as stuck buttons when a controller is disconnected while a button is being held.

The MVP will keep input combinations intentionally simple while this architecture is validated.

---

## Input Diagnostics

Hardware compatibility is one of the main challenges of BridgePad.

A diagnostic interface is planned to expose information such as:

```text
LEFT STICK
X:  0.432
Y: -0.218

RIGHT STICK
X: -0.012
Y:  0.823

BUTTONS
SOUTH   ●
EAST    ○
WEST    ○
NORTH   ○

L1      ○
R1      ●
```

This will help with:

- Controller compatibility
- Axis mapping
- Deadzones
- Debugging
- Calibration
- GitHub issues
- Testing hardware from different manufacturers

---

## Future: BridgePad Desktop

After the Bluetooth HID MVP is stable, one of the major planned additions is **BridgePad Desktop**.

Instead of emulating a Bluetooth HID device directly, the Android app will be able to send its virtual gamepad state through the local network.

```text
Controller
    │
    ▼
 Android
    │
 BridgePad
    │
 Wi-Fi / LAN
    │
    ▼
BridgePad Desktop
    │
    ▼
Virtual Controller
    │
    ▼
   Game
```

This mode is especially important for scenarios such as:

```text
Bluetooth Controller
        │
        ▼
     Android
        │
      Wi-Fi
        │
        ▼
 BridgePad Desktop
        │
        ▼
       PC
```

The network mode should work entirely on the local network and should not require an internet connection.

---

## Future Platforms

The architecture is intended to make additional platforms possible without coupling the core to them.

Planned future work includes:

- BridgePad Desktop for Windows
- LAN / Wi-Fi transport
- USB desktop companion transport
- Linux support
- Android TV
- Advanced touchscreen layouts with saved user presets
- Configurable analog touchscreen triggers using touch position or drag distance
- Touchpad scrolling, additional mouse buttons and configurable gestures
- Research into a PlayStation-style high-resolution touch surface with a
  clickable pad and left/right regions for Steam Input
- Gyroscope controls
- Controller profiles
- Advanced remapping
- PlayStation and Xbox research, prototypes and viable implementations

Console support is a long-term project goal. Research and prototypes come first
because Xbox and PlayStation use their own controller protocols and
authentication mechanisms, so generic Bluetooth HID alone is not sufficient.

---

## Development Roadmap

The project will be developed incrementally.

### Phase 0 — Project baseline

Create a reproducible Android project with:

- Kotlin
- Jetpack Compose
- Tests
- Lint
- CI
- Debug APK generation

### Phase 1 — Bluetooth HID viability

Before building the complete application, validate the biggest technical risk:

```text
Test button on Android
        │
        ▼
 Bluetooth HID
        │
        ▼
 Windows joy.cpl
```

If this cannot be made sufficiently reliable using public Android APIs, the initial output strategy will be reconsidered before investing heavily in the rest of the application.

### Phase 2 — Gamepad core

Implement the platform-independent gamepad model:

- Input mapping
- Source state
- Virtual gamepad state
- Deadzones
- Input merging
- HID report encoding
- Output scheduling

### Phase 3 — USB / USB-C gamepads

Detect and map physical controllers connected to Android.

Primary initial test device:

- GameSir X5 Lite

### Phase 4 — First complete vertical slice

Prove the complete workflow:

```text
GameSir
   ↓
Android Input
   ↓
BridgePad Core
   ↓
Bluetooth HID
   ↓
Windows
   ↓
Steam Input
   ↓
Game
```

### Phase 5 — Touchscreen controller

Add a fixed multitouch gamepad layout so BridgePad can also work without a physical controller.

### Phase 6 — Session UX and resilience

Implement:

- Pairing flow
- Connection management
- Reconnection
- Permissions
- Diagnostics
- Logging
- Safe session shutdown
- Error handling

### Phase 7 — MVP stabilization

Validate:

- Long sessions
- Reconnection
- Controller removal
- Bluetooth interruptions
- Background behavior
- Different Android devices
- Different USB controllers
- Windows 10 / 11
- Steam Input

Only after these phases will the first MVP be considered ready.

---

## Technology

The Android application is being developed natively with:

- **Kotlin**
- **Jetpack Compose**
- **Android SDK**
- **Kotlin Coroutines**
- **Flow / StateFlow**

BridgePad is Android-first because its core functionality depends heavily on platform-specific APIs such as:

- Bluetooth HID
- Bluetooth
- `InputDevice`
- `KeyEvent`
- `MotionEvent`
- USB
- Sensors
- Android services

The currently planned platforms are Android, Windows and Linux, followed by
research and prototypes for PlayStation and Xbox.

---

## Build and Test

The Android application uses the namespace and application ID
`dev.jonalakas.bridgepad`, supports Android 9 or newer (`minSdk = 28`) and starts
at version `0.1.0`.

### Requirements

- Android SDK configured in `local.properties`;
- JDK 17 or newer (the JDK bundled with Android Studio is supported);
- a terminal opened at the repository root.

On Windows, run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The generated APK is available at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To install it on an Android device connected through ADB:

```powershell
.\gradlew.bat installDebug
```

Detailed physical-device setup and the hardware test record are documented in
[`docs/testing.md`](./docs/testing.md).

---

## Repository Structure

The project is expected to evolve approximately like this:

```text
app/
└── src/main/java/.../bridgepad/
    ├── core/
    │   ├── gamepad/
    │   └── mapping/
    │
    ├── input/
    │   ├── android/
    │   └── touch/
    │
    ├── output/
    │   └── hid/
    │
    ├── session/
    │
    ├── ui/
    │   ├── home/
    │   ├── controller/
    │   └── debug/
    │
    └── diagnostics/

docs/
├── compatibility.md
├── testing.md
└── decisions/
```

The project will remain in a single repository/module while that is sufficient.

Additional repositories or modules should only be introduced when there is a demonstrated need for them.

---

## Documentation

More detailed documentation is available in the `docs` directory.

### `docs/compatibility.md`

Records the Android devices, controllers and host combinations validated on real hardware.

### `docs/testing.md`

Contains repeatable build, installation and hardware-test procedures.

### `docs/decisions/`

Contains architecture decision records explaining important technical choices.

---

## Compatibility

BridgePad interacts directly with Android Bluetooth and input APIs, whose behavior may vary between:

- Android versions
- Device manufacturers
- Controllers
- Bluetooth implementations
- Host operating systems

Because of this, compatibility will be based on **real hardware testing**, not assumptions.

Tested combinations will eventually be documented in:

```text
docs/compatibility.md
```

Community hardware reports will be especially valuable.

---

## Open Source

BridgePad is intended to remain:

- Free
- Open source
- Ad-free
- Subscription-free
- Account-free
- Cloud-free for core functionality

There will be no paid "Pro" version required to unlock controller functionality.

A voluntary donation option may be provided for users who want to support development.

---

## Contributing

Contributions will be welcome, especially for:

- Testing different Android devices
- Testing different controllers
- Controller mappings
- Bluetooth compatibility
- Bug fixes
- Documentation
- Platform research

When reporting controller compatibility, please include information such as:

```text
Controller:
Connection type:
Android device:
Android version:

Buttons working:
Axes working:
Triggers working:

Additional notes:
```

Diagnostic logs generated by BridgePad should make hardware-specific issues easier to investigate.

---

## Current Status

🚧 **BridgePad is in MVP stabilization.**

Bluetooth HID output, touchscreen controls, Android-normalized physical input
and direct background USB HID capture have been implemented and validated on
the primary Galaxy A35 / GameSir X5 Lite setup. The project is now completing
the resilience and compatibility matrix required by the MVP release gate.

See [`docs/compatibility.md`](./docs/compatibility.md) for recorded hardware
results and [`docs/release-checklist.md`](./docs/release-checklist.md) for the
remaining Gate C work.

---

## License

BridgePad is licensed under the Apache License 2.0.

See [`LICENSE`](./LICENSE) for the complete terms.

See [`PRIVACY.md`](./PRIVACY.md) for the privacy policy and
[`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md) for dependency notices.

---

## Continuous Integration

Every push runs two independent GitHub Actions jobs:

- unit tests and Android lint;
- debug APK build.

The build does not depend on the test job, so both execute even if one fails.
Workflow notifications are handled by each contributor's GitHub notification
settings.
