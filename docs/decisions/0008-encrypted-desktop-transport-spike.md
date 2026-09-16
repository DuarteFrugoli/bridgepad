# ADR 0008: Evaluate encrypted desktop transports before Wi-Fi integration

- Status: Accepted
- Date: 2026-09-15

## Context

BridgePad must carry low-latency controller input between an Android device and
BridgePad Desktop on Windows or Linux. The same application protocol will later
run over Wi-Fi and a USB transport. An untrusted local-network peer must not be
able to inject controller commands, impersonate a trusted desktop or replay an
old session.

Android provides DNS-SD/mDNS discovery through `NsdManager` and TLS through the
platform networking APIs. Android 10 and later support TLS 1.3 natively. Rustls
supports TLS 1.2 and TLS 1.3 on the desktop. Quinn provides QUIC streams and
unreliable datagrams on Windows and Linux.

Choosing only from theoretical protocol properties would leave Android
integration, reconnect behavior and real Wi-Fi latency untested. Adding
encryption after a plaintext transport would also force pairing and connection
lifecycle changes late in development.

## Decision

Encryption is part of the first network transport spike, not a later hardening
step. There is no production plaintext mode.

The spike compares:

1. TLS over TCP as the implementation-complexity baseline.
2. QUIC reliable streams for control and session messages.
3. QUIC datagrams for replaceable gamepad and pointer snapshots.

The existing BridgePad packet envelope remains identical in every candidate.
The test must measure plaintext only as a local diagnostic baseline; plaintext
must never become a selectable product transport.

The provisional discovery and trust design is:

- DNS-SD/mDNS service type `_bridgepad._tcp` for the TLS/TCP candidate and
  `_bridgepad._udp` for QUIC;
- a persistent cryptographic identity per installation;
- QR pairing containing the desktop endpoint and identity fingerprint;
- a short authentication string shown on both screens as the manual fallback;
- explicit confirmation before adding either peer to the trusted-device store;
- automatic authentication on later connections using the stored identity;
- Android private key operations backed by Android Keystore;
- an OS-protected credential store on Windows and Linux;
- explicit forget/revoke and identity-regeneration actions.

A six-digit value is never used as an encryption key. It only confirms that both
screens derived the same session identity. Pairing retries are rate-limited, and
logs never contain keys, pairing values or detailed input contents.

## Spike measurements

For each candidate, run 64-byte and 128-byte bidirectional traffic at 125 Hz and
250 Hz and record:

- handshake and resumed-handshake duration;
- input RTT p50, p95 and p99;
- jitter, loss, reordering and reconnect duration;
- CPU and memory on both peers;
- Android battery use and temperature;
- behavior on 2.4 GHz, 5 GHz and a congested network;
- behavior after screen-off, network changes and desktop sleep/resume.

The first Android target is the Samsung A35 on Android 16/API 36. Compatibility
must also be checked at the project's minimum API 28; if the platform cannot use
TLS 1.3 there, the spike must either validate a secure TLS 1.2 configuration or
justify bundling a maintained provider.

## Selection rule

TLS/TCP wins when its p99 latency and reconnect behavior are not perceptibly
worse and it substantially reduces product complexity. QUIC wins when datagrams
or connection migration remove measurable stalls without unacceptable Android,
packaging or maintenance cost.

## Initial loopback result

The first Linux-container run used 2,000 sequential 64-byte echo messages. It
measured the following steady RTT percentiles:

| Candidate | p50 | p95 | p99 | Connect + first RTT |
| --- | ---: | ---: | ---: | ---: |
| Plain TCP diagnostic baseline | 0.031 ms | 0.064 ms | 0.121 ms | 1.114 ms |
| TLS 1.3/TCP | 0.036 ms | 0.081 ms | 0.144 ms | 4.763 ms |
| QUIC datagrams | 0.088 ms | 0.224 ms | 0.296 ms | 3.611 ms |

This run demonstrates that encryption overhead itself is negligible for the
BridgePad message size. It does not select TLS/TCP: loopback has no Wi-Fi loss,
contention, Android scheduling or connection migration. The Android/LAN matrix
remains the deciding experiment.

## Consequences

- Pairing and trust are designed with the transport rather than retrofitted.
- Wi-Fi implementation waits for a short measured comparison, not the entire
  desktop product.
- The protocol module owns messages and session rules; TLS/QUIC libraries remain
  in transport modules.
- USB reuses the authenticated application session whenever its chosen carrier
  supports it.

## Implementation update — 2026-09-16

TLS 1.3/TCP is now the first product Wi-Fi carrier. The daemon advertises
`_bridgepad._tcp` through DNS-SD/mDNS and Android pins the advertised
certificate during the first pairing. Pairing uses a 12-digit, ten-minute code,
fresh PBKDF2-HMAC-SHA256 salt/nonces and separate server/client HMAC proofs. It
rotates after success or five failed proofs. Later sessions require a fresh
nonce challenge proved with a random 256-bit shared secret before any functional
input is accepted.

Android encrypts the saved shared secret with an AES-GCM key held by Android
Keystore. The current command-line desktop store remains a user-owned
development file; moving it to OS-protected Windows/Linux storage is still a
release requirement. QR scanning and explicit confirmation in a future desktop
UI may supplement the implemented PIN flow without changing the trust model.

## References

- [Android network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android SSLSocket](https://developer.android.com/reference/javax/net/ssl/SSLSocket)
- [rustls](https://docs.rs/rustls/latest/rustls/)
- [Quinn data transfer](https://quinn-rs.github.io/quinn/quinn/data-transfer.html)
