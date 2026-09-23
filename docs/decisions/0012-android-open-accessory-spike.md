# ADR 0012: Evaluate Android Open Accessory for USB

## Status

AOA deprioritized on 2026-09-22 after the first Windows driver test. IP over
USB tethering is now the leading candidate, but its measurement gate remains
open.

## Context

BridgePad needs a phone-to-PC USB path that works without root, ADB or developer
mode and reuses protocol v1 plus the Desktop virtual-device backends. Two
credible candidates exist:

- Android Open Accessory (AOA), where the PC is the USB host and Android exposes
  bulk endpoints to the BridgePad app;
- IP over USB tethering, where the existing network transport uses the private
  network created by the cable.

Both require BridgePad Desktop. Tethering replaces Wi-Fi with a cable-backed IP
link; it does not create XInput, mouse or keyboard devices by itself.

## Spike design

The first spike evaluates AOA in isolation:

- `bridgepad-usb-spike` performs the official AOA control handshake, waits for
  Android to re-enumerate and claims its bulk interface;
- `transport-usb-accessory` opens the accessory through `UsbManager`, requests
  Android permission and sends protocol-v1 Ping packets;
- the Android diagnostic sends 250 samples at 125 Hz and reports delivery rate,
  loss and RTT p50/p95/p99;
- the implementation does not depend on ADB, root or Android USB-host mode;
- CI builds the Android module and the Windows spike independently from the
  production session paths.

The spike must explicitly record whether a clean Windows installation can open
the pre-AOA device and the re-enumerated Google AOA interface. A dependency on
Zadig, a development-only driver replacement or manual Device Manager work is
not an acceptable product flow. A BridgePad-owned, signed and automatically
installed driver/helper may be acceptable only if installation, upgrade and
removal are reproducible.

## Decision gate

Do not select AOA until tests cover:

1. initial connection and Android permission;
2. repeated disconnect/reconnect without restarting either app;
3. latency, loss and receive gaps compared with Wi-Fi and USB tethering;
4. app background and screen-off behavior;
5. a charge-only cable and abrupt cable removal;
6. a clean Windows machine without Android developer tools;
7. at least three Android manufacturers;
8. Linux access and required `udev` rules.

After collecting the evidence, accept AOA, USB tethering or both. Production
must reuse the authenticated BridgePad session and neutralize every output when
the cable disappears.

## First AOA result

The Samsung A35 enumerated correctly on Windows as the Samsung composite device
`04e8:6860`, with working MTP, modem and Samsung ADB interfaces. The vendored
libusb host could not open the normal device to issue the initial AOA
`GET_PROTOCOL` request, so Android never entered accessory mode. This rules out
a cable or attach-detection failure and demonstrates the expected Windows
driver barrier on an ordinary machine.

AOA remains useful as research code, but it is not the primary product candidate
unless a signed installer can gain the required access without Zadig, manual
Device Manager work or breaking the manufacturer's MTP/ADB functions.

## USB network follow-up

The next spike reuses the existing TLS transport over Android USB tethering.
The dedicated Android diagnostic reports the socket's local and remote
addresses so the cable route can be verified with Wi-Fi disabled. It must test
both manual-IP connectivity and normal mDNS discovery, then run the playable
gamepad, pointer and keyboard paths without transport-specific input code.

The first encrypted USB-tethering run succeeded on the Samsung A35 and Windows
test PC. TLS 1.3 used `TLS_AES_128_GCM_SHA256`; the 250-sample route was
`10.232.206.111 -> 10.232.206.43`, proving that the USB subnet carried the
traffic. Handshake time was 64.329 ms and RTT was 2.055 ms p50, 3.049 ms p95
and 5.337 ms p99. The previously recorded Wi-Fi probe on the same project had
9.114/14.597/16.553 ms p50/p95/p99, so this first USB result is materially
lower latency.

The following playable session also passed through USB tethering. The existing
network gameplay controller drove the Windows virtual gamepad, pointer and
keyboard correctly without USB-specific input logic. This validates the core
architecture choice: USB networking can reuse the authenticated network
protocol and Desktop output backends. Reconnection, cable removal, background
behavior and automatic discovery remain open before accepting the gate.

The first normal discovery attempt exposed a multi-interface endpoint bug:
Android retained only one address from a Desktop advertising on both Wi-Fi and
USB, so pairing and gameplay could target the wrong interface. Discovery now
keeps all addresses for the same Desktop identity, pairing saves the endpoint
that succeeded, and gameplay falls back across the currently discovered
endpoints.

The correction passed on the Samsung A35 and Windows test PC. Normal discovery,
pairing and authenticated gameplay connected from `10.232.206.111` over USB.
After removing the cable, the same trusted Android identity authenticated and
started gameplay from `192.168.15.3` over Wi-Fi without pairing again. This
closes automatic discovery, pairing and cross-interface trust continuity for
the first USB-tethering device pair. Abrupt cable removal also neutralized the
active virtual controller correctly. Automatic reconnection and screen-off
behavior remain open.
