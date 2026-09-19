# ADR 0011: Use RFCOMM for Bluetooth via Desktop

## Status

Accepted on 2026-09-19.

## Context

Direct Bluetooth HID makes Android itself the controller. It works without
BridgePad Desktop, but Steam may require manual controller configuration.
BridgePad Desktop can instead receive BridgePad data and expose the already
validated Windows XInput backend, giving games and Steam the expected controller
identity.

These paths cannot run together. If HID and Desktop XInput were active for one
phone, Windows could expose two controllers and a game could process every input
twice.

Bluetooth Desktop still needs a transport choice. The candidates are Bluetooth
Classic RFCOMM and Bluetooth Low Energy GATT. They have different discovery,
connection and scheduling behavior, so the decision must be based on measured
behavior rather than an assumed protocol advantage.

## Spike design

Build an isolated transport benchmark before adding a production session
adapter:

- RFCOMM: BridgePad Desktop advertises a private service and Android connects as
  a stream client.
- BLE GATT: Android advertises a private service as the peripheral/server and
  BridgePad Desktop connects as the Windows central/client. Windows normally
  operates as a BLE central, while its peripheral role is hardware-dependent.
- Both candidates stream the same 20-byte report 1,000 times at a target rate
  of 125 Hz. Reports are not echoed.
- Desktop records unique received sequences, effective report frequency and
  maximum receive gap, then returns one aggregate summary to Android.
- Lightweight periodic Ping/Pong samples measure RTT and keep the duplex link
  active without echoing every report or building an artificial response queue.
- Android records connection time, accepted and rejected sends, Desktop
  delivery/loss/cadence, and RTT p50/p95/p99. Reconnection is measured by
  starting a new run after the previous socket has closed; the benchmark does
  not create an artificial immediate reconnect inside the same run.
- The diagnostic is disabled while any gameplay session is active. It never
  starts HID, the production desktop receiver, or a virtual XInput controller.

The benchmark Bluetooth link is not the production security model. It relies on
the already paired devices and uses deliberately small diagnostic payloads. The full
adapter must carry authenticated BridgePad protocol messages and preserve the
existing trust lifecycle before it can become a user-facing connection method.

## Measured evidence and decision

RFCOMM was exercised four consecutive times between the Samsung A35 running
Android 16 (API 36) and the Windows test PC without restarting the Desktop
process. The Desktop received all 4,000 of 4,000 reports. The four measured
rates were 125.07, 124.91, 125.25 and 124.99 Hz; maximum receive gaps were
50.25, 78.70, 83.82 and 78.80 ms. Every independent connection closed cleanly,
and the following run reconnected without an Android socket error.

RFCOMM therefore meets the delivery, target-cadence and repeated-connection
requirements for the first playable adapter. BLE GATT is not required for that
increment and is deferred rather than allowed to delay it. The occasional
receive-gap spikes remain a metric for real gameplay testing; production must
send current-state snapshots through a bounded latest-state queue so a delayed
radio interval cannot accumulate stale commands.

## Product policy after approval

When the production adapter exists, the Bluetooth setup flow should first look
for BridgePad Desktop. If a trusted compatible Desktop is available, the app
offers or selects Desktop Bluetooth and creates only XInput on the receiver. If
Desktop is unavailable, the app offers direct HID as the fallback and clearly
explains that Steam may require configuration. Switching paths must end and
neutralize the previous adapter before starting the next one.

## Follow-up acceptance gate

The first playable RFCOMM adapter was validated on 2026-09-19. The virtual Xbox
360 controller responded correctly in `joy.cpl`, Steam recognized it without
manual controller setup, gameplay worked correctly, and ending then restarting
the session produced no stuck input or reconnection failure. A previously
paired direct-HID BridgePad device remained visible in Windows but received no
input, so the two paths did not duplicate commands.

The playable spike is therefore approved. Product integration must preserve
that mutual exclusion, neutralize XInput on every disconnect and make the
Desktop-backed XInput path the preferred Bluetooth option. Direct HID remains
an explicit fallback when a compatible trusted Desktop is unavailable. The
normal flow must not present the inactive legacy HID device as a second active
BridgePad controller.
