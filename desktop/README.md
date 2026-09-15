# BridgePad Desktop

The desktop receiver is a Rust workspace targeting Windows and Linux. Its
crates remain independent from Android UI and transport details.

The `bridgepad-protocol` crate validates the shared v1 wire vectors.
`bridgepad-transport-spike` compares encrypted transport candidates without
becoming a production transport. Additional crates for session state, virtual
devices, daemon and UI will be introduced behind explicit interfaces as their
Phase 0 spikes pass.

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

## Android-to-desktop encrypted probe

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

The phone sends 250 encrypted Ping/Pong samples at 125 Hz and reports handshake,
p50, p95 and p99 RTT. This receiver does not create a virtual gamepad yet and is
not the final pairing flow.

GitHub Actions also publishes `bridgepad-desktop-windows` and
`bridgepad-desktop-linux` artifacts so the probe can be run without installing a
Rust toolchain.

This diagnostic certificate is stored as ordinary files and exists only for the
transport experiment. The product daemon will move identity material to the
operating system's protected credential storage before pairing is implemented.
