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

## Android-to-desktop Wi-Fi connection

Start the receiver from the repository root:

```sh
cargo run --release --manifest-path desktop/Cargo.toml \
  -p bridgepad-daemon
```

It listens on TCP port `39393`, creates a persistent identity and trust store in
`.bridgepad-dev/`, announces `_bridgepad._tcp` through DNS-SD/mDNS and prints a
12-digit pairing code. Keep the process open, choose **PC > Wi-Fi** in the
Android Home and select the discovered desktop. The code is required only for
the first pairing; following sessions mutually authenticate with the saved
device secret. The Android copy is encrypted by Android Keystore.

Gameplay requires a paired client by default. The daemon rejects untrusted
`SessionStart`, gamepad and pointer messages. Transient disconnects trigger
bounded automatic reconnection in Android, and the UI distinguishes an offline
desktop, changed identity, rejected authentication and a lost connection.

List or revoke the desktop's trusted phones while the receiver is stopped:

```sh
cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-daemon -- --list-peers
cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-daemon -- --forget-peer <32-hex-character-id>
cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-daemon -- --forget-all
```

Also use **Forget** in Android to delete that side's protected credentials.
Revoking both copies ensures neither side retains a usable trust record.

### Manual diagnostic route

To test with a typed IP and fingerprint in **Settings > Encrypted network test**,
start the daemon with the explicit development bypass:

```sh
cargo run --release --manifest-path desktop/Cargo.toml \
  -p bridgepad-daemon -- --allow-unpaired
```

In the Android app, open **Settings > Encrypted network test** and enter:

1. The PC's local IPv4 address.
2. Port `39393`.
3. The complete fingerprint printed by the receiver.

The Android screen exposes two development actions. The encrypted probe sends
250 Ping/Pong samples at 125 Hz and reports handshake, p50, p95 and p99 RTT. On
Windows, **Start playable Wi-Fi session** creates the virtual controller and
accepts touchscreen and physical-controller input simultaneously. It initially
opens the large mouse touchpad when a physical controller is already connected,
or the virtual controller otherwise. Android Back opens a session menu that can
switch between those surfaces without ending the TLS session. Pointer reports
drive the native Windows mouse; ending the session neutralizes and removes the
desktop controller.

The bypass trusts any client that can reach the receiver and is diagnostic only.
Never use `--allow-unpaired` as the normal Home flow or on an untrusted network.

GitHub Actions also publishes `bridgepad-desktop-windows` and
`bridgepad-desktop-linux` artifacts so the probe can be run without installing a
Rust toolchain.

The current desktop identity and trust database are ordinary user-owned files.
Production installers still need operating-system-protected credential storage,
appropriate Windows ACLs/Linux permissions and automatic firewall rules.

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
