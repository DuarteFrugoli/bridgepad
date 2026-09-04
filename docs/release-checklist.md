# MVP Release Checklist

The first MVP is releasable only when every required item below is complete and
the result is recorded. A code-complete feature is not sufficient evidence.

## Repository

- [ ] CI unit-test and lint job passes.
- [ ] CI APK build job passes independently.
- [ ] The release commit contains no secrets, local SDK paths or private notes.
- [ ] Version name/code and changelog match the intended release.
- [ ] License, privacy policy and third-party dependency notices are present.

## Clean installation and permissions

- [x] Install on a device with no existing BridgePad app data.
- [x] Complete onboarding using only the instructions shown by the app.
- [x] Validate permission acceptance, denial and a later successful retry.
- [x] Validate pairing a new Windows PC and reconnecting an existing PC.

## Inputs and output

- [x] Complete touchscreen controls work in `joy.cpl` and Steam Input.
- [x] Physical Compatibility mode works while BridgePad remains visible.
- [x] Background USB mode works outside BridgePad and with the screen off.
- [x] The saved direct-USB mapping survives a new session.
- [x] Switching capture modes produces no duplicate or stuck input.
- [x] Removing the controller while controls are held returns to neutral.

## Resilience

- [x] Complete 20 start/connect/stop cycles.
- [x] Complete a continuous two-hour session.
- [x] Recreate the Activity and return from the background.
- [x] Turn Bluetooth off/on during setup and during a connection.
- [x] Disconnect or turn off the Windows host during a connection.
- [x] Cancel pairing/discoverability and recover without clearing app data.
- [x] Copy/share a diagnostic report after a failure.

## Compatibility evidence

- [x] Samsung Galaxy A35 on Android 16 / API 36.
- [ ] At least one Android device from another manufacturer, if available.
- [ ] At least one additional supported Android version, if available.
- [x] Windows 11 and current stable Steam.
- [ ] Windows 10, if available.
- [ ] GameSir X5 Lite and one additional USB gamepad, if available.
- [x] Results and known limitations are recorded in `docs/compatibility.md`.

## Distribution

- [ ] A release APK is signed with the project release key.
- [ ] The signing key is backed up securely and is not committed.
- [ ] The signed APK installs and launches on a clean device.
- [ ] The APK checksum is recorded with the release.
- [ ] Gate C has zero open blocking defects.
