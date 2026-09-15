# BridgePad network transport

This module owns platform-independent network transport code used by Android.
It depends on `:protocol`, not on UI, Bluetooth or concrete input sources.

The first increment is an explicitly diagnostic TLS probe with certificate
fingerprint pinning. It validates Android-to-desktop latency before the product
transport, discovery and pairing lifecycle are selected.
