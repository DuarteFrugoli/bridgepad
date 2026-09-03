# BridgePad

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
- USB / USB-C gamepads recognized by Android
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
- macOS support
- iOS support
- Xbox console support
- PlayStation console support
- Nintendo console support

These may be explored after the core Android → Bluetooth HID workflow is proven stable.

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

Potential future work includes:

- BridgePad Desktop for Windows
- LAN / Wi-Fi transport
- USB desktop companion transport
- Linux support
- macOS support
- Android TV
- Advanced touchscreen layouts
- Gyroscope controls
- Controller profiles
- Advanced remapping
- iOS / iPadOS client
- Research into console compatibility

Console support is considered **research**, not a promised feature.

Modern consoles such as Xbox and PlayStation use their own controller protocols and authentication mechanisms, so generic Bluetooth HID alone is not enough to guarantee compatibility.

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

A future iOS version would therefore be implemented natively rather than forcing the Android implementation through a cross-platform abstraction.

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
├── idea.md
├── plan.md
├── compatibility.md
├── testing.md
└── decisions/
```

The project will remain in a single repository/module while that is sufficient.

Additional repositories or modules should only be introduced when there is a demonstrated need for them.

---

## Documentation

More detailed documentation is available in the `docs` directory.

### `docs/idea.md`

Describes the long-term product vision, architecture, possible inputs, outputs and future platforms.

### `docs/plan.md`

Contains the executable development plan for the first MVP, including:

- Development phases
- Technical gates
- Acceptance criteria
- Hardware tests
- Risks
- Release requirements

The execution plan should take priority when deciding what to implement next.

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

🚧 **BridgePad is currently in early development.**

The first technical milestone is not the final UI.

It is proving that this works reliably:

```text
Android test input
        ↓
Bluetooth HID
        ↓
Windows
        ↓
joy.cpl
```

After that:

```text
GameSir X5 Lite
        ↓
Android
        ↓
BridgePad
        ↓
Bluetooth HID
        ↓
Windows
        ↓
Steam Input
```

Once this path is stable, development can expand toward touchscreen controls, additional hardware and eventually network-based outputs.

---

## License

BridgePad will be released under an open-source license.

The specific license will be defined before the first public release.