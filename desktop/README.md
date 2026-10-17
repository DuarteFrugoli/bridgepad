# BridgePad Desktop (development build)

This Rust workspace contains the PC receiver, media transport and platform
adapters. The currently playable graphical Desktop targets Windows; the Linux
receiver and packaging are not yet production-ready. The current Windows
virtual gamepad uses the replaceable ViGEmBus development backend, whose driver
must be installed separately. There is no end-user installer yet.

From the repository root:

```powershell
cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-desktop
cargo test --locked --manifest-path desktop/Cargo.toml
```

The Desktop displays pairing status, trusted phones and the temporary pairing
code. Closing its window leaves it running in the system tray; use **Exit** to
stop the receiver. Do not run debug, release and the terminal daemon at the
same time when testing discovery, pairing or ports. The graphical Desktop keeps
its identity in the operating system's local app-data directory; the terminal
diagnostic daemon may use a different development identity.

Android discovers the receiver on the local network through DNS-SD/mDNS and
uses authenticated TLS for pairing and control. Control/input uses TCP `39393`;
media negotiation uses TCP `39394`. Playable local video/audio uses a separate
QUIC or UDP+AEAD media path, selected in Android Settings. Wi-Fi and USB
tethering are distinct routes; USB requires Android's tethering mode, not file
transfer. Bluetooth via Desktop uses RFCOMM and creates an XInput-compatible
virtual gamepad; direct Bluetooth HID bypasses this application entirely.

The raw executable does not install Windows Firewall rules. Allow the exact
executable on the network profiles used for testing. A future installer must
manage narrowly scoped rules without asking people to mark a public network
private. Bluetooth Desktop currently relies on the OS Bluetooth bond and still
needs application-level authentication before public release.

Developer diagnostics remain in **Android Settings > Tests** and in dedicated
spike binaries; they do not establish product trust. For current component
boundaries and transport security, see [architecture](../docs/architecture.md)
and the [protocol specifications](../protocol-spec/). Historical experiments
and their superseded instructions are available in Git history.
