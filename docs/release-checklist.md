# Public release checklist

This is a **gate for the first public Android/Windows/Linux release**, not a
record of past prototype tests. All boxes begin unchecked: prior validation of
Bluetooth HID or a single Windows development device does not approve the full
product. Record results with the commit/version, Android device, Desktop OS,
controller, connection route, power profile, duration and known limitations.
The detailed implementation order is tracked privately; this public list
states what a release candidate must demonstrate.

## Scope and media

- [ ] Classify each feature as supported, experimental or excluded in the app,
  READMEs and store listing. Internet streaming is excluded from this release.
- [ ] Approve a measured local media path against the frozen
  [WebRTC baseline](./streaming-baseline.md): startup, frame presentation,
  audio sync, latency, loss recovery, quality and CPU/GPU usage.
- [ ] Validate 720p60 and synchronized audio on each *advertised* route and
  supported hardware; publish a lower profile when that is what testing proves.
- [ ] Repeatedly start, stop, rotate, disconnect and reconnect streaming without
  frozen frames, unbounded queues or an orphaned Desktop capture worker.
- [ ] Media congestion never blocks gamepad, mouse or keyboard input. Stopping
  video does not end or duplicate the controller session.
- [ ] Verify selected Wi-Fi and USB routes remain distinct when both are active.

## Input and multiplayer

- [ ] Approve the final authenticated IP-input transport independently of media;
  verify neutralization, ordering and reconnection under loss and congestion.
- [ ] Regress direct Bluetooth HID, Bluetooth Desktop/XInput, Wi-Fi and USB
  tethering with virtual and physical controllers, mouse and keyboard.
- [ ] Regress Compatibility and background USB capture, mapping persistence,
  screen-off behavior, source switching and removal while buttons are held.
- [ ] No stuck input, duplicate controller or cross-player interference after
  app rotation, transport loss, Desktop exit or rapid reconnect.
- [ ] If local co-op is in the advertised scope, validate independent player
  identity and slots with two phones on Wi-Fi, Bluetooth Desktop and USB, plus
  mixed routes. Reconnecting the same phone never creates a second XInput
  device; losing one phone never removes another player's device.
- [ ] If simultaneous streaming is advertised, validate two receivers and two
  controls in a real co-op game, including one slow/disconnecting receiver.
  State the supported viewer count and profile from measured hardware results.
- [ ] Explain that direct HID is not managed by Desktop player slots and that
  Windows/games, not BridgePad, assign the visible XInput player position.

## Product and compatibility

- [ ] Complete clean installation, onboarding, pairing, forgetting and recovery
  using only the packaged app and Desktop, without SDK, ADB or Rust tools.
- [ ] Errors identify the failed route, trust, permission or media stage and
  offer a useful recovery action. Developer tests are outside the normal flow.
- [ ] Validate accessibility, screen orientations, localization, permissions
  denied/retried, app backgrounding and PC sleep/wake.
- [ ] Run repeat-start cycles and sustained sessions on each advertised Android
  version/device class, Windows version and Linux distribution. Record the
  [compatibility matrix](./compatibility.md) and all known limitations.
- [ ] Validate Steam and non-Steam games on the supported Desktop platforms.
- [ ] No open blocking crash, ANR, security defect, stuck input or unrecoverable
  session. Any cut in scope is explicit in UI and release notes.

## Security and distribution

- [ ] Review authentication on Wi-Fi, USB and Bluetooth Desktop, certificate
  pinning, media keys, replay protection, pairing revocation and input/media
  authorization. Diagnostics cannot be mistaken for trusted product sessions.
- [ ] Review logs, diagnostics and local storage for secrets or unnecessary
  personal information; update [privacy](../PRIVACY.md) and
  [third-party notices](../THIRD_PARTY_NOTICES.md).
- [ ] Android tests, lint and build plus Desktop tests/build pass from a clean
  checkout. Version codes, changelog and release notes agree.
- [ ] Ship installable Windows and Linux Desktop packages with required driver
  or system permissions, scoped Firewall rules, clean updates/uninstallation
  and safe virtual-device teardown. Test on machines without development tools.
- [ ] Protect signing keys outside the repository. Sign and smoke-test Android
  APK/AAB and Desktop artifacts; publish checksums and matching downloads.
- [ ] Check current Play Console requirements for target SDK, permissions,
  foreground services, Data safety, store assets and testing tracks before
  submission. Complete internal/closed testing and resolve pre-launch reports.
- [ ] Audit the release commit and artifacts for secrets, private notes, local
  paths and debug-only or intentionally insecure spike behavior.
- [ ] Release the compatible Desktop packages before or with Android. Publish
  the supported matrix, setup instructions, known limitations and issue route.
