# BridgePad

[English](./README.md) | [Português (Brasil)](./README.pt-BR.md)

BridgePad turns an Android phone into a PC controller, mouse and keyboard. It
also has experimental local PC-to-phone video and audio streaming. The project
is free and open source, but **v0.1.0 is a development build, not a public
release**.

## What works today

| Connection | PC companion | Current use |
| --- | --- | --- |
| Direct Bluetooth HID | No | Generic gamepad, mouse and keyboard. Steam Input may need controller setup; XInput-only games may not recognize it. |
| Bluetooth via Desktop | Yes | RFCOMM input to an Xbox-compatible virtual controller, plus mouse and keyboard on Windows. This is separate from direct HID. |
| Wi-Fi | Yes | Paired controller, mouse and keyboard sessions; experimental local H.264 video and Opus audio streaming. |
| USB tethering | Yes | The same authenticated IP session over the phone's USB-tethered network, not USB file-transfer mode. Streaming is experimental. |

The Android app supports a customizable multitouch gamepad, independent
portrait and landscape layouts, physical-controller Compatibility and direct
USB capture modes, and a mouse touchpad. A physical controller can be used
alongside on-screen controls. The Home screen lets the player choose the play
mode, connection, computer and, for streaming, physical-controller or
mouse/keyboard input. A virtual-controller overlay on video is not ready yet.

Local streaming can use **QUIC DATAGRAM** (default) or **UDP+AEAD**, selected in
Android Settings. Both are playable development paths; neither has passed the
final latency, loss-recovery and long-session gates. WebRTC remains a measured
reference, not the intended final local transport. Input has its own connection
and does not travel through the media socket.

The currently exercised desktop is Windows. The virtual XInput device uses the
development ViGEmBus backend; an installer and production backend decision are
still outstanding. Linux support is planned for the first public release but
is not playable yet. Multiple independent phone controllers and simultaneous
streams are also not release-ready. Internet streaming is outside the initial
scope. See [compatibility evidence](./docs/compatibility.md) and the
[release gates](./docs/release-checklist.md) before making support claims.

## Try the development build

You need Android 9+, a Windows development PC, an Android SDK, JDK 17, Rust and
the Android NDK/Rust target used by the native media module. The
[CI workflow](./.github/workflows/ci.yml) shows the exact build dependencies.
For XInput gameplay, install the ViGEmBus driver separately until BridgePad has
an installer.

From the repository root on Windows:

```powershell
.\gradlew.bat installDebug
cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-desktop
```

Open BridgePad on the phone, choose a play mode and connection, then select the
discovered Desktop. Enter the one-time pairing code shown on the PC. Subsequent
sessions use the saved trust record. Direct Bluetooth HID instead pairs through
Android/Windows Bluetooth and does not require the Desktop app. For USB, enable
**USB tethering** on the phone; merely connecting the cable or selecting file
transfer does not create the required network route. Closing the Desktop window
keeps it in the system tray; use **Exit** there to stop it.

Raw development executables do not install Windows Firewall rules. If
discovery or connection fails on a public network, check the executable's
Firewall permission rather than changing the network's privacy category.

## Build and checks

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat :domain:test :protocol:test :streaming-core:test :transport-network:test :transport-bluetooth-hid:testDebugUnitTest :transport-bluetooth-hid:lintDebug :transport-bluetooth-desktop:testDebugUnitTest :transport-bluetooth-desktop:lintDebug :transport-usb-accessory:testDebugUnitTest :transport-usb-accessory:lintDebug :app:testDebugUnitTest :app:lintDebug
cargo test --locked --manifest-path desktop/Cargo.toml
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Diagnostic
tools remain under **Settings > Tests** in the Android app; they are not the
normal setup flow. See [BridgePad Desktop](./desktop/README.md) for receiver
commands and development caveats.

## Project structure

- `app/`: Android UI, hardware adapters, session lifecycle and composition.
- `domain/`, `protocol/`, `streaming-core/`: shared input and media contracts.
- `transport-*/`: Android connection adapters.
- `native/`: native Android media transport.
- `desktop/`: Rust Desktop receiver, Windows adapters and transport diagnostics.
- `protocol-spec/`: versioned wire contracts and test vectors.

[Architecture](./docs/architecture.md) is the current technical source of
truth. [Gamepad core](./docs/gamepad-core.md) describes input normalization;
[streaming baseline](./docs/streaming-baseline.md) records the reference
measurements. [CHANGELOG](./CHANGELOG.md) records development changes.

## Privacy, license and contributions

BridgePad's core local features require no account, subscription or cloud
service. It is licensed under [Apache 2.0](./LICENSE). Read the
[privacy policy](./PRIVACY.md) and [third-party notices](./THIRD_PARTY_NOTICES.md).

Hardware reports and contributions are welcome. Include Android and PC
versions, controller model, connection method, BridgePad version and steps to
reproduce. Review diagnostic logs before sharing; do not publish Bluetooth
addresses, pairing secrets or other personal identifiers.
