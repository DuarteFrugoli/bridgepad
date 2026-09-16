# BridgePad Desktop

The desktop receiver is a Rust workspace targeting Windows and Linux. Its
crates remain independent from Android UI and transport details.

The `bridgepad-protocol` crate validates the shared v1 wire vectors.
`bridgepad-transport-spike` compares encrypted transport candidates without
becoming a production transport. `bridgepad-virtual-device` defines the
platform-independent virtual controller contract. The experimental
`bridgepad-windows-vigem` adapter implements that contract without exposing its
Windows dependency to the protocol or daemon.

Run all current desktop checks with:

```sh
cargo test --manifest-path desktop/Cargo.toml
```

Run the first encrypted transport measurement with:

```sh
cargo run --release --manifest-path desktop/Cargo.toml \
  -p bridgepad-transport-spike -- --iterations 2000 --payload-size 64
```

The spike compares TLS 1.3/TCP against a diagnostic plaintext TCP baseline on
loopback. It does not select the production transport by itself; QUIC and real
Android/Wi-Fi measurements are still required. Plaintext is never a product
mode.

## Android-to-desktop encrypted connection

Start the diagnostic receiver:

```sh
cargo run --release --manifest-path desktop/Cargo.toml \
  -p bridgepad-daemon
```

It listens on TCP port `39393`, creates a persistent diagnostic identity in
`.bridgepad-dev/` and prints its certificate SHA-256 fingerprint. On Windows,
allow the process on private networks if the firewall asks. Use `ipconfig` to
find the PC's local IPv4 address.

In the Android app, open **Settings > Encrypted network test** and enter:

1. The PC's local IPv4 address.
2. Port `39393`.
3. The complete fingerprint printed by the receiver.

The Android screen exposes two development actions. The encrypted probe sends
250 Ping/Pong samples at 125 Hz and reports handshake, p50, p95 and p99 RTT. On
Windows, **Start playable Wi-Fi session** creates the virtual controller and
opens the touchscreen layout. Leaving the layout sends a neutral snapshot and
ends the desktop session.

The playable path is still a manual development flow: it uses the typed IP and
pinned certificate fingerprint, supports gamepad snapshots only, and trusts any
client that can reach the manually started receiver. Use it only on a trusted
development network. Discovery, mutual pairing and protected identity storage
remain required before Wi-Fi is exposed as a normal Home connection.

GitHub Actions also publishes `bridgepad-desktop-windows` and
`bridgepad-desktop-linux` artifacts so the probe can be run without installing a
Rust toolchain.

This diagnostic certificate is stored as ordinary files and exists only for the
transport experiment. The product daemon will move identity material to the
operating system's protected credential storage before pairing is implemented.

## Windows virtual gamepad spike

The Windows spike creates an Xbox 360-compatible virtual controller that should
appear automatically in `joy.cpl`, Steam and games. It currently uses ViGEmBus
only as a replaceable experimental adapter; ViGEm is archived and is not yet the
final production-backend decision.

Install the last signed ViGEmBus driver, then run from the `desktop` directory:

```powershell
cargo run -p bridgepad-gamepad-spike
```

Keep the process running while opening `joy.cpl` or Steam's controller test.
Enter `demo` to exercise the standard buttons, D-pad, both sticks and triggers,
or `quit` to neutralize and remove the controller. To run the sequence once and
exit automatically:

```powershell
cargo run -p bridgepad-gamepad-spike -- --demo
```

The adapter converts BridgePad's downward-positive Y axes to XInput's
upward-positive convention and scales 16-bit trigger values to XInput's 8-bit
range. BridgePad's first extra button maps to Guide; the remaining extra buttons
have no Xbox 360 equivalent.
