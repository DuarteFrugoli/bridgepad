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
