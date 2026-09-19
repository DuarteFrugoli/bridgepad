# Bluetooth Desktop transport spike

This Android adapter benchmarks two candidates for carrying BridgePad data to
BridgePad Desktop without creating a Bluetooth HID device:

- Bluetooth Classic RFCOMM;
- Bluetooth Low Energy GATT writes plus notifications.

Both candidates stream the same 20-byte report at 125 Hz. Desktop measures
one-way delivery, report cadence and maximum receive gap, then returns a single
aggregate summary. Lightweight periodic Ping/Pong samples measure RTT and keep
the duplex link active without echoing every input report. The diagnostic also
reports connection and reconnection time and rejected Android writes. It is
intentionally not a product session transport yet and must never run while
direct HID is active.
