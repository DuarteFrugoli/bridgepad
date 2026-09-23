# BridgePad Desktop

The desktop receiver is a Rust workspace targeting Windows and Linux. Its
crates remain independent from Android UI and transport details.

The `bridgepad-protocol` crate validates the shared v1 wire vectors.
`bridgepad-transport-spike` compares encrypted transport candidates without
becoming a production transport. `bridgepad-virtual-device` defines the
platform-independent virtual controller contract. The experimental
`bridgepad-windows-vigem` adapter implements that contract without exposing its
Windows dependency to the protocol or daemon.

`bridgepad-desktop` is the first graphical companion. It uses Tauri 2 with a
small framework-free HTML/CSS/JavaScript frontend and calls the same Rust server
library as the terminal daemon. It shows receiver state, a rotating pairing
code and trusted phones, and keeps the receiver alive in the system tray when
its window is closed.

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

### Graphical app

On Windows, start the development build from the repository root with:

```powershell
cargo run --manifest-path desktop/Cargo.toml -p bridgepad-desktop
```

The normal interface does not require an IP address, port or certificate
fingerprint. Choose the discovered computer in Android and enter the temporary
code shown by BridgePad Desktop on the first connection. Closing the window
hides it; use the tray icon to reopen it, rotate the pairing code or exit. The
trusted-device list can revoke an individual phone.

The graphical app stores its identity below the operating system's local app
data directory. This is intentionally separate from the repository-local
diagnostic identity described below.

The raw development executable does not install Windows Firewall rules yet. A
public network can therefore still block discovery or incoming sessions unless
the user has allowed the executable for that profile. The planned Windows
installer must create narrowly scoped rules for both public and private
profiles and remove them during uninstall; the product flow must never require
changing the network to private.

### Terminal diagnostic receiver

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

GitHub Actions publishes a `bridgepad-desktop-windows` artifact containing the
graphical app, terminal daemon, gamepad spike and Bluetooth transport spike. The
`bridgepad-desktop-linux` artifact currently contains the terminal receiver.
These raw development binaries can be tried without installing a Rust
toolchain; installers remain a later product increment.

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

## Bluetooth via Desktop transport spike

Run one candidate at a time from `desktop/` while the phone and PC are already
paired. RFCOMM makes Windows the service provider:

```powershell
cargo run -p bridgepad-bluetooth-spike -- rfcomm
```

BLE uses Windows in its normal central role and waits for the Android diagnostic
to advertise its GATT service:

```powershell
cargo run -p bridgepad-bluetooth-spike -- ble
```

Then open **Settings > Bluetooth Desktop test** on Android, select the paired PC
and run the matching candidate. The spike streams 1,000 reports at 125 Hz.
Desktop returns aggregate delivery/cadence statistics, while lightweight
periodic Ping/Pong samples measure RTT without echoing every report. It does not
create XInput or start direct HID. Its purpose is to select a transport; the
winning transport will be integrated with the authenticated BridgePad protocol
and existing virtual-device backend in a later phase.

The first playable RFCOMM increment reuses the v1 gamepad snapshots and creates
an Xbox 360 virtual controller through ViGEm:

```powershell
cargo run -p bridgepad-bluetooth-spike -- play
```

On Android, open **Settings > Bluetooth Desktop test**, select the paired PC and
tap **Start playable RFCOMM session**. This remains a diagnostic path: Bluetooth
pairing is required, but application-level BridgePad authentication is not yet
implemented.

The normal `bridgepad-desktop` application now advertises the same playable
RFCOMM service automatically. In the Android Home flow, selecting an already
paired Bluetooth PC tries Desktop/XInput first. If the Desktop service is not
available, Android offers direct HID as an explicit fallback; it never keeps
both output paths active for the same session.

## Android Open Accessory USB spike

Install the current Android debug build before starting the Desktop spike. AOA
can temporarily disconnect USB debugging when the phone re-enumerates. Then
connect one unlocked Android phone with a data-capable cable and run from
`desktop/`:

```powershell
cargo run -p bridgepad-usb-spike
```

The executable performs the official AOA handshake and waits for the phone to
appear as a Google accessory. On Android, approve the accessory/app prompt,
open **Settings > USB Desktop test**, and run the 250-sample benchmark.

This is a transport experiment, not a playable session. Windows may refuse to
open or claim the AOA interface unless a WinUSB-compatible driver is associated
with it. Do not use Zadig as evidence that the product flow is solved: the spike
must establish whether a signed BridgePad installer can configure everything
on a clean machine without ADB or developer tools.

USB tethering is the comparison candidate. It can reuse the Wi-Fi/network
transport over the cable-created private network, but it still requires
BridgePad Desktop to authenticate the phone and create XInput, mouse and
keyboard devices.

### USB tethering network spike

Connect the data cable and enable USB tethering on Android. Turn off phone Wi-Fi
during the first run so a successful socket cannot silently use the wireless
route. Start the existing receiver from `desktop/`:

```powershell
cargo run -p bridgepad-daemon -- --identity-dir ..\.bridgepad-dev --allow-unpaired
```

Run `ipconfig` and locate the IPv4 address assigned to the new Ethernet/USB
adapter. In Android, open **Settings > USB network test**, enter that address,
port `39393`, and the certificate fingerprint printed by the daemon. The result
prints the socket's local and remote addresses in addition to TLS and latency
metrics. Then start the playable session and validate gamepad, mouse and
keyboard.

After the manual route succeeds, restart with the regular BridgePad Desktop and
test Home discovery while Wi-Fi remains off. This distinguishes basic USB/IP
reachability from automatic mDNS discovery across Android's tethering link.
