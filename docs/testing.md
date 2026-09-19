# Testing BridgePad

This document defines the repeatable test record used during development. A
phase is complete only when its automated and hardware evidence is recorded.

## Automated baseline

Run from the repository root on Windows:

```powershell
.\gradlew.bat :domain:test :protocol:test :transport-network:test :transport-bluetooth-hid:testDebugUnitTest :transport-bluetooth-desktop:testDebugUnitTest :app:testDebugUnitTest
.\gradlew.bat :transport-bluetooth-hid:lintDebug :transport-bluetooth-desktop:lintDebug :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The same checks run as independent jobs in GitHub Actions after every push.

## Bluetooth via Desktop transport spike

This diagnostic compares RFCOMM and BLE GATT without starting direct HID or a
virtual XInput controller. End any active BridgePad session before opening it.
The phone and PC must already be paired.

### RFCOMM

1. From `desktop/`, run
   `cargo run -p bridgepad-bluetooth-spike -- rfcomm`.
2. Install/open the Android app, then open **Settings > Bluetooth Desktop test**.
3. Select the paired PC and tap **Test RFCOMM**.
4. Keep the desktop process running until Android reports 1,000 streamed
   reports and the Desktop aggregate.
5. Record connection time, Desktop received/Android accepted count, rejected
   writes, loss, report rate, maximum receive gap, and RTT
   p50/p95/p99. Delivery and cadence come from the Desktop aggregate; RTT comes
   from lightweight periodic Ping/Pong samples during the report stream.
6. Tap **Test RFCOMM** again without restarting the Desktop process. Treat that
   second independent run as the reconnection test; it must complete without a
   raw socket error or stale state from the first run.

### BLE GATT

1. Stop the RFCOMM process cleanly with Enter.
2. From `desktop/`, run `cargo run -p bridgepad-bluetooth-spike -- ble`.
   The Windows process scans as a BLE central; it does not advertise a service.
3. On the same Android diagnostic screen, tap **Test BLE GATT**. Android now
   advertises the private GATT service and waits for Desktop to subscribe.
4. Keep the process running through the automatic disconnect/reconnect cycle.
5. Record the same metrics as RFCOMM.

Repeat each test at least three times with the devices in the same positions.
Also repeat after toggling Bluetooth off/on and after restarting Desktop. Do not
compare a run made during phone hotspot use with one made without it, because
2.4 GHz radio coexistence can alter Bluetooth performance.

The provisional winner shown by Android uses loss first and p95 RTT second. It
is a convenience, not the architecture decision: reconnect reliability,
maximum gaps and repeated runs must also be considered. This spike does not
prove authentication or XInput integration; those belong to the production
phase after a transport is selected.

### First playable RFCOMM/XInput path

1. Stop the benchmark with Enter and, from `desktop/`, run
   `cargo run -p bridgepad-bluetooth-spike -- play`.
2. Confirm ViGEmBus is installed and close any direct Bluetooth HID session so
   Windows cannot expose duplicate controllers.
3. On Android, open **Settings > Bluetooth Desktop test**, select the paired PC
   and tap **Start playable RFCOMM session**.
4. Confirm the virtual Xbox 360 controller appears in `joy.cpl`. Test every
   button, both sticks, both triggers and every D-pad direction with the virtual
   touchscreen controller.
5. Open the session menu with Android Back, return to the virtual controller,
   and confirm gamepad state remains neutral while no control is pressed.
   Pointer transport is intentionally hidden because it is not part of this
   increment.
6. If a physical gamepad is available, repeat in Compatibility and Background
   USB capture modes. The RFCOMM adapter must receive the same merged
   `InputRouter` state without transport-specific mapping.
7. End the session from its menu. Confirm the controller disappears or is
   neutral in `joy.cpl` and the terminal prints a clean session stop.
8. Start another session without restarting the Desktop process, then validate
   Steam and one game. No Android socket error, duplicated controller, stuck
   button or delayed command backlog is acceptable.

This playable command is deliberately unauthenticated above the paired
Bluetooth link and remains a spike. Product integration requires BridgePad
trust/authentication, automatic Desktop preference with direct-HID fallback,
and lifecycle/status integration in the normal Home flow.

On 2026-09-19, the Samsung Galaxy A35 completed this playable gate against the
Windows test PC. The RFCOMM path drove the XInput controller correctly in
`joy.cpl`, Steam recognized it automatically, a native-controller game worked,
and ending then restarting the session left no stuck state. Windows still
listed the phone's previously paired direct-HID controller, but it remained
inactive and did not duplicate any command.

### Normal Bluetooth Desktop flow

BridgePad Desktop now starts the RFCOMM gamepad receiver automatically together
with its Wi-Fi receiver. Run `cargo run -p bridgepad-desktop`, then use the
normal Home flow on Android:

1. Choose PC, Bluetooth and an already paired computer.
2. Tap **Connect and play**. Confirm Home reports a Desktop/XInput connection
   and does not start or register direct HID.
3. Validate the virtual and physical controls in `joy.cpl`, Steam and a game.
4. End and restart the session. Confirm the virtual controller is neutralized
   and no second active controller receives the same input.
5. Close BridgePad Desktop and try again. After the Desktop attempt fails, tap
   **Use direct Bluetooth** and confirm the existing HID flow still connects.
6. While RFCOMM is active, switch between Compatibility and Background USB and
   confirm the same XInput controller continues receiving the selected source.

The RFCOMM path currently trusts the Windows/Android Bluetooth bond. Do not
treat this as the final release security model until application-level peer
authentication is implemented and tested.

## Windows virtual gamepad spike

This test validates the virtual-device boundary independently from Android and
the network transport.

1. Install the last signed ViGEmBus driver.
2. From `desktop/`, run `cargo run -p bridgepad-gamepad-spike`.
3. Open `joy.cpl` and confirm an Xbox 360 controller appears without manual
   mapping.
4. Enter `demo` and confirm A/B/X/Y, bumpers, Back/Start, L3/R3, D-pad, both
   sticks and both triggers follow the printed sequence and return to neutral.
5. Open Steam's controller test while the spike remains active. Confirm it is
   recognized without starting Steam's manual controller setup.
6. Enter `quit` and confirm the device disappears without a stuck input.
7. Repeat the recognition check in a game with native controller support.

On 2026-09-15, the local Windows machine successfully created, updated,
neutralized and removed the controller through ViGEmBus 1.21.442. On 2026-09-16,
current Steam recognized the virtual controller automatically without manual
setup, and the complete deterministic demo produced the expected buttons,
D-pad, sticks and triggers. The controller also appeared in `joy.cpl` and worked
in a native controller game. The functional Windows recognition spike is
approved; sustainable signed production distribution remains a separate gate
under ADR 0010.

## Encrypted Android-to-desktop network probe

The probe validates TLS, certificate pinning and protocol v1 Ping/Pong before
Wi-Fi is exposed as a gameplay connection.

1. Build or download the `bridgepad-daemon` Windows artifact described in
   [`desktop/README.md`](../desktop/README.md).
2. Start the receiver and allow it through Windows Firewall on **private
   networks only**.
3. Run `ipconfig` and note the PC's local IPv4 address.
4. Install and open the current Android debug build.
5. Open **Settings > Encrypted network test**.
6. Enter the PC address, port `39393`, and the complete certificate SHA-256
   fingerprint printed by the receiver.
7. Run the test and record TLS version, handshake, RTT p50, p95 and p99.
8. Change one fingerprint digit and confirm that the connection is rejected.
9. Repeat on 2.4 GHz, 5 GHz and, when possible, a congested network.

The initial target is the Samsung A35 on Android 16/API 36. The application's
minimum API level is 28, meaning Android 9 devices are currently allowed to
install it. TLS 1.3 is native from API 29; API 28 must therefore negotiate the
secure TLS 1.2 fallback or be removed from the supported range before release.

### Observed Wi-Fi baseline — 2026-09-15

The Samsung A35 on Android 16/API 36 completed all 250 sequential Ping/Pong
samples against the native Windows daemon over the local Wi-Fi network:

- TLS: `TLSv1.3`;
- cipher suite: `TLS_AES_128_GCM_SHA256`;
- connect and handshake: `63.182 ms`;
- RTT p50: `9.114 ms`;
- RTT p95: `14.597 ms`;
- RTT p99: `16.553 ms`.

The first attempt timed out because the diagnostic firewall rule applied only to
private networks while Windows classified the current network as public. Changing
the trusted home network to private allowed the spike to complete. This is a
diagnostic limitation, not the intended product flow: the desktop installer must
eventually manage narrowly scoped rules for both profiles after mutual device
authentication is implemented.

On 2026-09-16, the same home-network setup was repeated with one certificate
fingerprint digit changed. The Android probe rejected the desktop certificate
and did not complete the encrypted test, validating the negative pinning path.
The daemon was then stopped and restarted with the same identity directory. It
reported the same certificate fingerprint and the Android probe connected
successfully with the previously pinned value, validating diagnostic identity
persistence across process restarts.

## Product Wi-Fi discovery, pairing and reconnection

1. Stop older daemon instances and run the authenticated receiver from the
   repository root:

   ```powershell
   cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-daemon
   ```

2. Keep the desktop output visible. It must show its name, persistent ID,
   certificate fingerprint, current 12-digit pairing code and the
   `_bridgepad._tcp.local.` discovery service.
3. In Android, choose **PC > Wi-Fi** and confirm the desktop appears without
   entering an IP, port or fingerprint.
4. Choose **Pair**, enter the displayed code and confirm the desktop becomes a
   trusted selectable destination. A wrong or expired code must fail without
   creating a trust record.
5. Tap **Connect and play**. Confirm the XInput controller appears in `joy.cpl`,
   Steam and a game; test the virtual controller, physical controller and mouse.
6. Stop and reconnect without entering the code. Restart the daemon with the same
   `.bridgepad-dev` directory and repeat; its identity and trust must persist.
7. During gameplay, interrupt Wi-Fi or restart the receiver briefly. Confirm the
   Android UI shows bounded reconnection attempts, recovers when possible and
   never leaves a pressed button or non-neutral axis behind.
8. Use **Forget** on Android. With the receiver stopped, inspect and revoke its
   side as well:

   ```powershell
   cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-daemon -- --list-peers
   cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-daemon -- --forget-peer <device-id>
   ```

9. Start the receiver again and confirm a revoked phone cannot start gameplay
   until it pairs with the current code. Repeat on Windows public and private
   profiles after installer-managed firewall rules exist; the current development
   binary may still require an explicit Windows Firewall allowance.

## First playable Wi-Fi diagnostic flow

This development-only fallback joins the encrypted Android transport to the
Windows virtual-gamepad adapter using a manually entered endpoint.

1. Install the signed ViGEmBus driver used by the current Windows spike.
2. From `desktop/`, start the receiver with
   `cargo run --release -p bridgepad-daemon -- --identity-dir ..\.bridgepad-dev --allow-unpaired`.
   The explicit flag is required because product gameplay rejects unpaired clients.
3. Install the current Android debug build and open **Settings > Encrypted
   network test**.
4. Enter the PC IPv4 address, port `39393` and the fingerprint printed by the
   receiver.
5. Without a physical controller attached, tap **Start playable Wi-Fi session**.
   The touchscreen controller must open only after the desktop accepts the
   session.
6. Confirm the controller appears in `joy.cpl`, Steam and a game without manual
   Steam mapping. Exercise every visible button, D-pad, both sticks and triggers.
7. Press Android Back, switch between the virtual-controller and mouse-touchpad
   surfaces, and confirm that the desktop controller remains connected. End the
   session from the menu and confirm it disappears with every input neutral.
8. Repeat while holding a button, then disable Wi-Fi or terminate the Android
   app. Within the receiver timeout, the desktop must neutralize and remove the
   virtual controller.

The Android sender keeps at most one pending complete state and sends a
heartbeat every 500 ms. The development receiver times out after two seconds,
neutralizes on every disconnect/error path and ignores duplicate or older
gamepad sequence numbers. Record hardware evidence here before treating this
increment as validated.

### Physical controller over Wi-Fi follow-up

Validate the adaptive physical-controller path over the same playable Wi-Fi
session rather than only through Bluetooth HID:

1. Start a Wi-Fi session with a physical controller already connected to the
   phone and confirm its complete mapping in `joy.cpl`, Steam and a game.
2. Repeat with Android Compatibility capture and direct background USB capture.
3. Connect and disconnect the physical controller while the Wi-Fi session stays
   active; the desktop virtual controller must remain present and neutralize
   only the removed source.
4. Use physical and touchscreen controls simultaneously. Buttons must remain
   pressed while either source holds them, and analog ownership must move to the
   last intentional source without drift taking permanent control.
5. Change between the virtual-controller and touchpad screens without restarting
   TLS, recreating the desktop controller or interrupting physical input.
6. On the touchpad screen, verify relative movement and click through the native
   Windows pointer output.
7. Turn off Wi-Fi while holding a physical button and with an analog axis away
   from center. The desktop must neutralize and remove the virtual controller
   within the receiver timeout.

These checks are not replaced by the successful touchscreen Wi-Fi test: they
validate the independent physical-capture and multi-source routing path.

### Validation evidence — 2026-09-16

- Android device: Samsung Galaxy A35, Android 16 (API 36)
- Host: Windows with the BridgePad Desktop development receiver
- Wi-Fi session using only the virtual controller: PASS in `joy.cpl` and a game
- Wi-Fi session with a physical controller connected to the phone: PASS in
  `joy.cpl` and a game
- Automatic initial surface selection, simultaneous input routing and switching
  between the virtual-controller and mouse-touchpad surfaces: PASS
- Compatibility and Background USB capture through the shared adaptive router:
  PASS
- Native Windows relative-pointer movement and click over Wi-Fi: PASS
- Controller state remained usable while changing the visible session surface:
  PASS
- Result: no blocking defect was observed in the adaptive multi-source gameplay
  validation

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

8. Open BridgePad, complete onboarding if shown, then open **Settings**. Confirm
   the version, device model, Android version and API level are correct.

If Windows does not list the device, change the USB mode to file transfer and
install the Samsung Android USB driver if necessary.

## Hardware test record

### Current Home and localization regression checklist

The earlier phase procedures below describe historical milestone screens. For
the current navigation use [Session UI and localization](ui-flow.md) and this
checklist. These checks are pending hardware validation for the UI redesign.

- Set Android to Brazilian Portuguese and then English. Verify onboarding,
  the three Home steps, notices, mapping prompts and foreground notifications.
- On Android 13+, use **Settings > Change app language**, reopen BridgePad and
  verify the selection persists. On older versions use the system language.
- On both fresh and upgraded installations, confirm input, connection and
  destination are not preselected on a new launch without an active session.
  Explicitly end a session and verify all three choices are cleared. Rotate
  during setup and reopen an active session: current choices/session must survive.
  Omit each setup choice in turn and verify **Connect and play** stays disabled.
  Completing all three choices with Bluetooth enabled must enable the button.
- With Bluetooth off, confirm **Connect and play** is disabled.
  **End session** must remain hidden while granting permissions, enabling
  Bluetooth or choosing a PC; it appears only after the HID session starts.
  Connection must show **Turn on Bluetooth**, with no paired-PC list,
  missing-PC warning or selected new-pairing option. Accept enabling Bluetooth
  and confirm the paired-PC choices appear inline without a visibility request
  or a second picker.
- Repeat using **Turn on Bluetooth** in Connection, including with missing
  Bluetooth permission, a saved PC, and a previous new-pairing choice. All paths
  must refresh paired PCs and require a destination choice before connecting.
- Cancel Bluetooth enablement, enable it from Android quick settings, and turn
  it off while the paired-PC choices are visible. Confirm the UI follows the actual adapter
  state and no hidden new-pairing selection or automatic visibility remains.
- Deny or cancel permissions/discoverability. Confirm there is actionable
  localized feedback, no false connected state and no automatic retry loop.
- With an already paired PC selected, turn off Bluetooth on the PC and try to
  connect. The app must say it is trying to connect and suggest checking power/
  range, then report that it could not connect. It must not claim that Windows
  is connecting or that an established connection was lost. Next connect
  successfully, turn off PC Bluetooth and confirm the distinct connection-lost
  message is shown.
- With no saved destination and an already paired PC, select it inline in
  Connection after Bluetooth preparation. No session or visibility prompt should
  start until **Connect and play** is pressed with all choices complete.
  Repeat with no paired PCs: new pairing still requires an explicit choice.
  Return without choosing a PC and verify no session starts. A forgotten saved PC
  must keep the button disabled until a valid destination is selected.
- Choose **Pair a new PC**, tap **Connect and play**, allow visibility and add
  the phone in Windows Bluetooth settings. Confirm the input screen opens only
  after connection, without restarting the session.
- End the session, choose the input, connection and same paired PC again, then
  connect. Merely opening BridgePad must not select options or reconnect a session.
- Open **Session menu**, switch virtual/physical input and **Resume game**.
  Confirm the PC remains connected, inactive controls are released, and both
  mouse touchpads still move/click correctly.
- Select virtual input and open **Edit virtual layout**. Move every control,
  confirm the canvas uses the same full usable area and component sizes as the
  gameplay screen, and confirm the floating options never shift the controls.
  Collapse and reopen the panel, then verify it closes automatically when editing.
  Confirm only the selected item shows resize handles, and resize representative
  buttons, the D-pad and mouse surface horizontally, vertically and from corners.
  Confirm analog sticks only resize proportionally from corners, then save.
  Confirm the gameplay screen applies the layout, every control remains usable,
  and the layout survives rotation and an app restart. Reopen the editor, change
  the draft and cancel; the saved layout must remain unchanged. Finally restore
  the standard draft, save it and confirm the built-in arrangement returns.
- Load the **Symmetric**, **Asymmetric** and **Mobile** starting layouts. Confirm
  their previews differ, each remains editable, cancel does not apply a selected
  preset, and saving each preset changes the gameplay arrangement.
- Select physical input and confirm **Connect and play** remains disabled until
  either Compatibility or Background USB is selected. Choose each capture mode
  before connecting, then configure buttons in both modes. Verify cancellation,
  controller removal and rotation leave Home usable and preserve saved mappings.
- For Background USB, approve access before connecting the PC and confirm this
  does not start a Bluetooth session or show its foreground notification. Switch
  back to virtual input before connecting and confirm the USB device is released.
- Test the physical controller with the app visible in Compatibility mode and
  with the screen off in Background USB mode. Check the notification wording.
- Rotate Home and the controller screens, reopen the app, turn Bluetooth off
  during setup/gameplay, and unpair a selected PC. No stale play screen, stuck
  preparing state or unrequested connection should remain.
- Use a large system font and a small screen in both languages. All actions
  should remain reachable by scrolling; translated labels must not be clipped.
- Open **Settings** and confirm it replaces Home with a dedicated scrollable
  screen. Check metrics/device details and copy/share diagnostics. Open the layout
  editor from Settings and confirm both Save and Cancel return to Settings; Back
  must return to Home. Confirm unavailable Wi-Fi and USB outputs cannot be selected.
- Hold buttons while moving both sticks continuously for at least 30 seconds in
  virtual, Compatibility and Background USB modes. Confirm there are no missing
  press/release transitions or periodic pauses. In **Settings**, verify metrics
  update at a readable rate and record **Maximum output delay** for comparison.
- While generating continuous controller input, confirm the foreground
  notification does not flicker or repeatedly refresh. Changing between virtual,
  Compatibility and Background USB modes must still update its text immediately.

### Recording results

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
placement. This check validates the default layout and touch behavior; the later
regression checklist covers the editable layouts and built-in starting presets.

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
11. Open the physical-controller touchpad. Confirm that it fills the usable
    screen, has no permanent menu button or redundant status banner, and shows
    the Android Back instruction as plain text in a corner without looking
    clickable or substantially reducing the touch surface.
12. Press Android Back or use the system Back gesture from the touchpad. Confirm
    that Home opens while the Bluetooth session remains active and **Resume game**
    reopens the touchpad.
13. With Bluetooth enabled and a physical USB controller attached, select a
    paired PC and virtual input. Confirm that no stale Bluetooth-off notice is
    shown and that the physical controller does not affect connection readiness.

Record the phone, Android version, PC version and gamepad used. Phase 6 passes
when a new user can complete the flow from the app guidance and all failures
above remain recoverable without clearing app data.

### Physical controller capture and mapping

Start with no PC session. Connect a physical USB gamepad to the phone:

1. Select physical input and confirm a capture mode is required before connecting.
2. Select Compatibility, open the mapping wizard and save a mapping. Connect the
   PC and confirm every mapped control is forwarded while BridgePad is visible.
3. End the session, select Background USB and approve the one-time Android USB
   permission before connecting the PC. Confirm no Bluetooth session starts yet.
4. Open the same mapping workflow, save it and confirm BridgePad reports that
   background capture is active.
5. Connect and verify every button, D-pad direction, stick and trigger in `joy.cpl` and
   Steam Input.
6. Leave BridgePad, then repeat the input check from the Android home screen.
7. Turn the phone screen off and repeat the input check.
8. Return to BridgePad and switch back to Compatibility mode during gameplay;
   no control may remain pressed during the transition.
9. Remove the USB cable while holding controls and confirm that the PC returns
   to a neutral state.
10. If the descriptor is unsupported, confirm that BridgePad presents an
    actionable warning and still allows Compatibility to be selected.
11. Open the mapping wizard and follow every prompt without relying on the
    labels initially inferred by BridgePad.
12. Save the mapping and verify the complete controller in `joy.cpl` and Steam
    Input.
13. End and restart the session, enable Background USB again, and confirm that
    the saved mapping is restored without running the wizard again.
14. Run the wizard again and verify its order: A/B/X/Y; D-pad left/right/up/down;
    left stick left/right/up/down and L3; right stick left/right/up/down and R3;
    L1/L2/R1/R2; Back/Select/Share; Start/Options/Menu; Guide/PS/Home; and
    Share/Capture.
15. On a nonessential step, press the physical button mapped as A and confirm
    that the step is skipped without assigning A to that control.

## Phase 7 MVP stabilization

Use [`release-checklist.md`](./release-checklist.md) as the authoritative Gate C
record. Complete every available device/host scenario, record unavailable
matrix entries explicitly, and add the observed results to
[`compatibility.md`](./compatibility.md).

Any crash, stuck input, unrecoverable pairing/reconnection failure, incorrect
required control on supported hardware, or unexpected termination of a mode
that explicitly supports background use blocks the MVP release.

### Switching input during a session

1. Connect BridgePad to the PC with Touchscreen selected and verify an input.
2. Exit the touchscreen controls without ending the HID session.
3. Select Physical gamepad and verify that physical input works without a new
   Bluetooth connection.
4. Switch back to Touchscreen, open its controls and verify them again.
5. During every transition, confirm in `joy.cpl` that all controls briefly
   return to neutral and that the inactive source cannot affect the PC.

### Mouse touchpad

Because this feature changes the HID report descriptor, remove the existing
BridgePad/phone pairing from Windows and Android, then pair it again before the
first test.

1. Connect with Touchscreen selected and open the touchscreen controller.
2. Drag across the center touchpad and confirm that the Windows pointer follows
   the relative movement in every direction.
3. Lift and place the finger elsewhere without dragging; the pointer must not
   jump to an absolute screen position.
4. Tap the touchpad and confirm exactly one left click.
5. Drag and release; the release must not produce an accidental click.
6. Use the touchpad simultaneously with a gamepad stick and buttons.
7. Switch to Physical gamepad input and confirm that touching the old surface
   cannot move or click the pointer.
