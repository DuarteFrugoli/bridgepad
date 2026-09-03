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
