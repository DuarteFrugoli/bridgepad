# BridgePad protocol

This module owns the versioned, platform-independent messages exchanged between
the Android client and a future BridgePad receiver. It depends only on `:domain`.

The draft v1 binary envelope, message codec and capability model are implemented
here and documented in [`protocol-spec/v1.md`](../protocol-spec/v1.md). Desktop
implementations must follow that language-neutral contract and do not need to
use Kotlin.

Discovery, pairing, authentication and encryption remain separate transport and
security decisions. The draft must not be used on an untrusted network until
those decisions are implemented.
