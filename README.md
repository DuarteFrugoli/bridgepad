# BridgePad

[English](./README.md) | [Português (Brasil)](./README.pt-BR.md)

> **Connect any controller. Use any input. Play anywhere.**

BridgePad is a free and open-source Android project that turns a phone or tablet
into a flexible gamepad bridge. It can use touchscreen controls or a physical
controller as input, normalize them into one logical gamepad state, and send
that state to a computer.

The project is in active development. The current Android-to-PC path works over
Bluetooth HID and has passed its primary hardware validation. It is not yet a
finished public release.

## Current status

The current development version is `0.1.0`.

Present in the current build:

- Android 9 or newer (`minSdk = 28`);
- virtual multitouch gamepad;
- persistent virtual-layout editor;
- symmetric, asymmetric and mobile built-in layouts;
- Android-normalized physical gamepad input;
- direct USB HID capture that can work in the background or with the screen off;
- optional saved controller mapping for both physical-input modes;
- Bluetooth HID gamepad and relative mouse output;
- integrated and full-screen mouse touchpads;
- simultaneous virtual and physical input during an active session;
- automatic Wi-Fi discovery and authenticated gameplay through BridgePad Desktop on Windows;
- guided pairing, reconnection, session notices and safe shutdown;
- live metrics and privacy-conscious diagnostic report export;
- English and Brazilian Portuguese interfaces;
- unit tests, Android lint and independent debug APK builds in CI.

The primary validated combination is:

```text
GameSir X5 Lite or touchscreen
              |
              v
Samsung Galaxy A35
Android 16 / API 36
              |
       Bluetooth HID
              |
              v
Windows 11 + Steam Input
```

Phase 7 stabilization passed on this combination, including a two-hour session,
20 start/connect/stop cycles, Bluetooth interruption recovery, physical
controller removal, Activity recreation and mouse validation. See
[`docs/compatibility.md`](./docs/compatibility.md) for the recorded evidence.

A distributable release still requires the remaining items in
[`docs/release-checklist.md`](./docs/release-checklist.md), especially release
signing and broader compatibility evidence.

## How it works today

BridgePad currently presents the Android device to a computer as a generic
composite Bluetooth HID gamepad and mouse. The computer does not need BridgePad
Desktop for this mode.

```text
Touchscreen / physical gamepad
               |
               v
       BridgePad Android
               |
       Bluetooth HID
               |
               v
      Windows gamepad + mouse
               |
               v
        Steam Input / game
```

This is a generic HID controller rather than a native XInput device. Steam Input
is therefore the primary compatibility layer. Steam recognizes BridgePad as a
generic controller, but an initial button configuration may be required. Games
that accept only XInput might not detect the current Bluetooth controller
directly.

## Session flow

The Home screen builds a session in dependency order:

1. **Destination** — currently a Windows or future Linux PC.
2. **Connection** — Bluetooth and Wi-Fi are available; phone-to-PC USB is shown
   as upcoming.
3. **Computer** — Bluetooth lists Android-paired PCs. Wi-Fi discovers BridgePad
   Desktop automatically and asks for its one-time code only on first pairing.

Input is automatic rather than another required setup choice. Virtual controls
and any detected physical controller may be used simultaneously.

No choice is preselected for a new setup. **Connect and play** remains disabled
until the required choices and permissions are valid. Selecting a paired PC does
not make the phone discoverable; new pairing only starts after the user chooses
that action.

During an active session, the player can switch between the virtual-controller
and mouse-touchpad screens without reconnecting. This changes only the visible
surface; virtual and physical inputs remain active together.

## Physical gamepad modes

When a physical controller is detected, BridgePad provides two optional capture
modes. They do not disable the virtual controls.

### Compatibility

Uses Android's normalized `InputDevice`, `KeyEvent` and `MotionEvent` APIs. It is
the broadest compatibility path, but BridgePad must remain visible and the screen
must stay on.

### Background USB

Claims a compatible standard USB HID controller directly. Input can continue
while BridgePad is in another window or the Android screen is off.

The mapping wizard is optional in both modes. Saved profiles are associated with
the controller identity and HID descriptor when that information is available.
Removing a source or switching modes neutralizes its state to prevent stuck or
duplicated commands.

In this name, USB describes the physical controller connected to the phone. It
does not yet mean USB output from the phone to the computer.

## Virtual gamepad and mouse

The virtual gamepad supports independent multitouch for:

- D-pad;
- left and right sticks;
- A, B, X and Y;
- bumpers and digital triggers;
- Start, Select, L3 and R3;
- a relative mouse touchpad.

The layout editor uses the same gameplay canvas and control geometry. Players
can move every component, resize width and height independently where applicable,
start from one of three built-in layouts, cancel a draft, reset it or save one
persistent active layout.

Named user presets, analog touchscreen triggers, gyroscope input and advanced
mouse gestures are not implemented yet.

When a physical controller is active, BridgePad can show a large mouse touchpad.
Android Back returns to Home without ending the current Bluetooth session.

## Architecture

BridgePad keeps input, logical behavior, output and presentation separate:

```text
touch / Android InputDevice / direct USB
                  |
             InputRouter
                  |
        VirtualGamepadState
                  |
          OutputScheduler
                  |
        GamepadOutputTransport
                  |
          Bluetooth HID today
```

The Gradle modules are:

- `:domain` — pure Kotlin gamepad state, mapping, merging, scheduling, session
  planning and ports;
- `:protocol` — platform-independent, versioned messages for the future desktop
  receiver;
- `:transport-bluetooth-hid` — Android Bluetooth HID profiles, descriptors and
  report encoding;
- `:app` — Android UI, permissions, lifecycle, physical inputs, persistence and
  dependency composition.

Dependencies point inward toward `:domain`. New input sources must not depend on
an output transport, and new transports must consume only normalized gamepad or
pointer state. See [`docs/architecture.md`](./docs/architecture.md) and the ADRs
in [`docs/decisions`](./docs/decisions/).

## Next direction

The current major work is evolving the first playable BridgePad Desktop path
into a product connection, first for Windows and then Linux.

```text
BridgePad Android
       |
   Wi-Fi or USB
       |
       v
BridgePad Desktop
       |
virtual OS controller
       |
       v
      Game
```

The intended order is:

1. specify and test the versioned desktop protocol;
2. create a sustainable Windows virtual-controller backend;
3. validate and harden automatic discovery, secure pairing and Wi-Fi sessions;
4. automate Windows installation, firewall rules and desktop credential protection;
5. implement phone-to-PC USB without root or ADB in the normal user flow;
6. add the Linux virtual-controller backend and packaging;
7. add optional low-latency PC-to-phone video streaming, followed by audio.

Wi-Fi and USB will require BridgePad Desktop because the computer must receive
the normalized state and create a native virtual controller. Bluetooth HID will
remain available as a direct path that does not require the companion.

## Not implemented yet

- phone-to-PC USB output;
- a production Windows virtual-controller backend and installer;
- Linux receiver support;
- rumble/force-feedback return;
- gyroscope or accelerometer control;
- named user layout presets;
- analog touchscreen triggers;
- PC-to-phone video or audio streaming;
- multi-phone local co-op with one independent virtual-controller slot per player;
- advanced per-game controller profiles;
- macros or guided calibration.

## Technology

The Android application uses:

- Kotlin;
- Jetpack Compose;
- Android SDK;
- Coroutines and `Flow`/`StateFlow`;
- Gradle.

The application ID is `dev.jonalakas.bridgepad`. Android, Windows and Linux are
the currently planned platforms.

## Build and test

Requirements:

- Android SDK configured in `local.properties`;
- JDK 17 or newer;
- a terminal opened at the repository root.

On Windows, run the complete unit-test and lint set used by CI:

```powershell
.\gradlew.bat :domain:test :protocol:test :transport-bluetooth-hid:testDebugUnitTest :transport-bluetooth-hid:lintDebug :app:testDebugUnitTest :app:lintDebug
```

Build the debug APK independently:

```powershell
.\gradlew.bat assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on an online ADB device with:

```powershell
.\gradlew.bat installDebug
```

Detailed procedures are in [`docs/testing.md`](./docs/testing.md).

## Repository structure

```text
app/                       Android application and composition root
domain/                    platform-independent gamepad domain
protocol/                  future desktop wire messages
transport-bluetooth-hid/   Android Bluetooth HID adapter
docs/                      public English documentation and ADRs
README.md                  English project overview
README.pt-BR.md            Brazilian Portuguese overview
```

Product-planning notes are private and intentionally ignored by Git. Public
documentation and source code use English; the two README versions are kept in
parallel.

## Documentation

- [`docs/architecture.md`](./docs/architecture.md) — current module and runtime
  boundaries;
- [`docs/gamepad-core.md`](./docs/gamepad-core.md) — normalized gamepad pipeline
  and HID report contract;
- [`docs/ui-flow.md`](./docs/ui-flow.md) — session setup, settings and localization;
- [`docs/testing.md`](./docs/testing.md) — build and hardware test procedures;
- [`docs/compatibility.md`](./docs/compatibility.md) — recorded hardware results;
- [`docs/release-checklist.md`](./docs/release-checklist.md) — remaining release
  gates;
- [`docs/decisions`](./docs/decisions/) — architecture decision records.

## Compatibility

Bluetooth HID and Android input behavior can vary between Android versions,
manufacturers, controllers, Bluetooth implementations and host systems.
Compatibility claims are based on real hardware tests, not only API support.

The Samsung Galaxy A35 is the primary Android test device, and the GameSir X5
Lite is the primary physical controller. They are validation references, not
hard-coded product requirements. Windows 11 has been validated; Windows 10 and
Linux have not yet passed the project compatibility gate.

## Open source and privacy

BridgePad is intended to remain:

- free and open source;
- ad-free;
- subscription-free;
- account-free;
- cloud-free for core functionality.

There is no paid controller-functionality tier. A voluntary donation option may
be offered later.

BridgePad is licensed under the Apache License 2.0. See [`LICENSE`](./LICENSE),
[`PRIVACY.md`](./PRIVACY.md) and
[`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md).

## Contributing

Contributions and hardware reports are welcome, especially for:

- Android device and controller compatibility;
- Bluetooth behavior;
- controller mappings;
- input, reconnection and lifecycle bugs;
- Windows/Linux desktop receiver development;
- tests, translations and documentation.

When reporting compatibility, include the Android device and version, controller,
connection type, controls tested and BridgePad version. Do not publish Bluetooth
addresses or other personal identifiers. Review diagnostic reports before
sharing them.

## Continuous integration

Every push runs two independent GitHub Actions jobs:

- unit tests and Android lint;
- debug APK build.

The build does not depend on the test job, so both run even when one fails.
Workflow email behavior is controlled by each contributor's GitHub notification
settings.
