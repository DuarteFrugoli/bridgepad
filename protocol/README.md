# BridgePad protocol

This module owns the versioned, platform-independent messages exchanged between
the Android client and a future BridgePad receiver. It depends only on `:domain`.

Transport framing, discovery, authentication and serialization will be specified
before Wi-Fi or USB desktop output is implemented. Desktop implementations must
follow the published wire format and do not need to use Kotlin.
