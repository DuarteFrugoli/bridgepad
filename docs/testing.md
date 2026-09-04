# Testing BridgePad

This document defines the repeatable test record used during development. A
phase is complete only when its automated and hardware evidence is recorded.

## Automated baseline

Run from the repository root on Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The same checks run as independent jobs in GitHub Actions after every push.

## Installing from VS Code on a physical device

1. Enable Developer options on the Android device by tapping **Build number**
   seven times in **Settings > About phone > Software information**.
2. Enable **USB debugging** in **Settings > Developer options**.
3. Connect the unlocked device to Windows with a data-capable USB cable.
4. Accept the RSA debugging prompt on the device.
5. Find the Android SDK path in the `sdk.dir` entry of `local.properties`.
6. In the VS Code terminal, verify the connection, replacing `<ANDROID_SDK>`
   with that path:

```powershell
& "<ANDROID_SDK>\platform-tools\adb.exe" devices
```

7. Install the current debug build:

```powershell
.\gradlew.bat installDebug
```

8. Open BridgePad on the device and confirm that its version, manufacturer,
   model, Android version and API level are displayed correctly.

If Windows does not list the device, change the USB mode to file transfer and
install the Samsung Android USB driver if necessary.

## Hardware test record

Create one record for each relevant combination:

```text
Date:
BridgePad version/commit:

Android manufacturer:
Android model:
Android version/API:

Input device:
Input connection:

Host manufacturer/model:
Host operating system:
Host Bluetooth adapter:

Scenario:
Expected result:
Actual result:
Result: PASS / FAIL / BLOCKED

Logs or evidence:
Notes:
```

Do not include Bluetooth addresses, account names or other unnecessary personal
identifiers in public reports.

## Phase 0 manual check

- install the debug APK on a clean or updated installation;
- launch BridgePad without a crash;
- confirm version `0.1.0` is visible;
- confirm manufacturer and model match the device;
- confirm Android version and API level match system settings;
- rotate, background and reopen the app;
- record the result in `docs/compatibility.md`.

## Phase 1 Bluetooth HID spike

Prerequisites:

- keep Bluetooth enabled on both devices;
- install the latest debug APK;
- open `joy.cpl` on Windows (`Win + R`, then enter `joy.cpl`).

Procedure:

1. Open BridgePad and grant the requested Nearby devices permission.
2. Tap **Start HID spike**.
3. Confirm that the session reaches `READY`.
4. Tap **Pair new PC** and approve the Android discoverability request.
5. On Windows, open **Bluetooth & devices**, add a Bluetooth device and select
   the phone. Confirm the pairing prompt on both devices when shown.
6. Select the paired Windows PC from the computer list in BridgePad.
7. Confirm that the session reaches `CONNECTED` and Windows lists BridgePad as
   a game controller.
8. Open the controller properties in `joy.cpl`.
9. Tap **Send test button** repeatedly and confirm that button 1 is pressed and
   released every time.
10. Move **Test X axis** and confirm that the Windows X-axis indicator follows
    it. Use **Minimum**, **Center** and **Maximum** to verify both extremes and
    the exact neutral position.
11. Tap **Stop HID spike** and confirm that no input remains pressed and the
    axis returns to center.
12. Repeat after backgrounding the app, turning the screen off and reconnecting.

If Windows and Android were already paired before the HID service was
registered, remove the pairing on both devices and repeat the procedure. The
USB cable may remain connected for installation and Logcat; it does not replace
or normally interfere with the Bluetooth HID connection.

Record the exact status message and relevant Logcat output if registration or
connection fails. This result decides Gate A; it is expected that some Android
devices may not expose or reliably maintain the HID Device profile.

## Phase 3 USB gamepad diagnostic

Prerequisites:

- install the latest debug APK on the Android device;
- connect the GameSir X5 Lite directly through USB-C;
- keep the BridgePad screen open during input diagnostics.

Procedure:

1. Confirm that the **Physical gamepad diagnostic** card displays the GameSir
   name, vendor/product IDs, descriptor and reported axes.
2. Press every face button, bumper, Start, Select, L3 and R3. Confirm that each
   logical button appears only while held and that no button remains stuck.
3. Press all eight D-pad directions and release it. Confirm the direction and
   final `NEUTRAL` state.
4. Move both sticks through their full range. Confirm values near `-1.000` and
   `1.000`, and confirm they settle at `0.000` without visible drift.
5. Press each trigger independently. Confirm each moves from `0.000` to near
   `1.000` and does not move the other trigger.
6. Disconnect the controller while holding a button or stick. Confirm the
   device disappears and its controls are neutralized.
7. Reconnect it without restarting BridgePad and repeat one button and one axis
   check.

If any control is missing or incorrect, record the **Last event**, axis names,
raw values, normalized values, vendor/product IDs and descriptor shown by the
app. A second USB gamepad should be checked when one is available, but its
absence does not prevent validating the primary GameSir mapping.

## Phase 4 end-to-end gamepad bridge

Prerequisites:

- complete the Phase 3 checks with the GameSir connected over USB-C;
- connect BridgePad to the Windows PC as a Bluetooth HID gamepad;
- open controller properties through `joy.cpl`.

Procedure:

1. Start the gamepad bridge, select the paired PC and wait for `CONNECTED`.
2. Confirm the output rate settles close to `100 Hz` while connected.
3. Test every face button, bumper, Start, Select, L3 and R3 in `joy.cpl`.
4. Test all D-pad directions, including diagonals and neutral.
5. Move both sticks to their extremes and center, then press both triggers
   independently and together.
6. Hold multiple buttons while moving both sticks and pressing a trigger.
   Confirm all fields update without stuck inputs.
7. Disconnect the GameSir during active input. Confirm the Windows controller
   returns to neutral.
8. Reconnect the GameSir, then disconnect and reconnect Bluetooth during active
   input. Confirm the new session starts from the current logical state without
   stale controls.
9. Configure the generic controller in Steam Input and test a game through
   Steam Input.
10. When available, test a second game that accepts the HID/DirectInput device
    without Steam Input.
11. Keep the bridge connected and actively use it for at least one hour.

Record the displayed input rate, output rate and typical last-event latency.
Gate B is approved only after every required control is correct and the
one-hour session completes without stuck input or an app-caused disconnect.

## Phase 5 touchscreen gamepad

Prerequisites:

- connect BridgePad to Windows and open `joy.cpl`;
- start the gamepad bridge and tap **Open touchscreen controller**;
- keep the phone in landscape orientation.

Procedure:

1. Confirm the controller fills the usable display without overlapping system
   cutouts or gesture areas.
2. Test A, B, X, Y, L1, R1, Start, Select, L3 and R3 individually and confirm
   each control changes visual state while pressed.
3. Test L2 and R2. Each digital trigger must move independently between its
   neutral and maximum HID values.
4. Test all D-pad directions and diagonals, then release in each direction and
   confirm the hat returns to neutral.
5. Move both sticks through their full circular range. Confirm their knobs stay
   inside the visual base and return exactly to center when released.
6. Hold one stick while rapidly alternating face buttons and D-pad directions.
7. Use at least five simultaneous touches when the device supports them,
   including both sticks and multiple buttons.
8. Exchange fingers rapidly between controls and confirm one pointer never
   releases or moves a control owned by another pointer.
9. While controls are active, open Menu, press Android Back, background the app
   and rotate or lock the device. Confirm Windows returns to a neutral state in
   every case.
10. Repeat the layout check on a second screen size or aspect ratio when one is
    available.
11. Play through Steam Input using only the touchscreen for at least 30 minutes.

Record any inaccessible control, missed pointer, stuck state or uncomfortable
placement. Visual polish and editable presets are intentionally outside the MVP
phase; this check validates the fixed default layout and touch behavior.

## Phase 6 session UX and resilience

Use a clean installation, or clear BridgePad app storage, before checking the
first-run flow.

1. Open BridgePad and confirm that the onboarding explains Bluetooth, PC
   pairing, input choices and the Steam Input limitation.
2. Continue, deny the Bluetooth permission, and confirm that the app remains
   usable and offers the permission action again.
3. Grant permission and confirm that the home shows input, HID compatibility,
   session state and host state without exposing the full technical diagnostic.
4. Select touchscreen and complete a session through a paired Windows PC.
5. End the session and confirm that the PC receives no stuck controls.
6. Select physical gamepad and confirm that a missing-controller warning is
   shown until a gamepad is connected.
7. Disconnect the PC during an active session and use the explicit reconnect
   action. Repeated taps must not start concurrent connection attempts.
8. Cancel phone discoverability and confirm that BridgePad explains how to try
   again.
9. Open technical details, copy the diagnostic report, and share it to a local
   text destination. Confirm that it contains the app/device/controller data
   and recent categorized events, but no Bluetooth addresses.
10. Repeat start, connect, disconnect and stop after putting the app in the
    background and returning to it.

Record the phone, Android version, PC version and gamepad used. Phase 6 passes
when a new user can complete the flow from the app guidance and all failures
above remain recoverable without clearing app data.

### Background USB capture

With a physical USB gamepad selected and an active PC connection:

1. Confirm Compatibility mode still forwards every control while BridgePad is
   visible.
2. Select Background USB and approve the one-time Android USB permission.
3. Confirm that BridgePad reports that background capture is active.
4. Verify every button, D-pad direction, stick and trigger in `joy.cpl` and
   Steam Input.
5. Leave BridgePad, then repeat the input check from the Android home screen.
6. Turn the phone screen off and repeat the input check.
7. Return to BridgePad and switch back to Compatibility mode during gameplay;
   no control may remain pressed during the transition.
8. Remove the USB cable while holding controls and confirm that the PC returns
   to a neutral state.
9. If the descriptor is unsupported, confirm that BridgePad returns to
   Compatibility mode and presents an actionable warning.
10. Open the USB mapping wizard and follow every prompt without relying on the
    labels initially inferred by BridgePad.
11. Save the mapping and verify the complete controller in `joy.cpl` and Steam
    Input.
12. End and restart the session, enable Background USB again, and confirm that
    the saved mapping is restored without running the wizard again.
