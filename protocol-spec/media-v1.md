# BridgePad Media v1

Status: **draft 0.2**. This document defines the transport-independent media
datagram and reliable control messages shared by BridgePad Desktop and mobile
clients. It intentionally does not select QUIC DATAGRAM or UDP+AEAD; both
candidates must carry these exact bytes during the transport bake-off.

All multi-byte integers use network byte order. Every datagram is at most 1200
bytes, including the fixed 56-byte header. The maximum v1 payload is therefore
1144 bytes. Senders split encoded access units at this boundary and never rely
on IP fragmentation.

## Datagram header

| Offset | Size | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `BPM1` |
| 4 | 1 | major | `1` |
| 5 | 1 | minor | `0` |
| 6 | 1 | kind | `1` video, `2` audio |
| 7 | 1 | header size | `56` |
| 8 | 2 | flags | See below |
| 10 | 2 | reserved | Zero |
| 12 | 8 | session ID | Non-zero positive signed-63-bit media-session identifier |
| 20 | 4 | stream ID | Non-zero video/audio stream identifier |
| 24 | 4 | sequence | Per-stream wrapping packet sequence |
| 28 | 4 | frame ID | Per-stream wrapping encoded-unit sequence |
| 32 | 8 | presentation timestamp | Non-negative signed-63-bit monotonic microseconds in the negotiated clock |
| 40 | 2 | frame packet index | Source-packet index; `0xffff` for a repair shard |
| 42 | 2 | frame packet count | Number of source packets in the encoded unit |
| 44 | 4 | original frame bytes | Encoded-unit size before packetization |
| 48 | 2 | FEC block index | Zero when FEC is disabled |
| 50 | 2 | FEC shard index | Source shards precede repair shards |
| 52 | 1 | FEC source count | Zero when FEC is disabled |
| 53 | 1 | FEC repair count | Zero when FEC is disabled |
| 54 | 2 | reserved | Zero |

Known flag bits are:

| Bit | Name | Meaning |
| ---: | --- | --- |
| 0 | keyframe | Video random-access unit |
| 1 | config | Codec configuration is present |
| 2 | frame start | First source packet |
| 3 | frame end | Last source packet |
| 4 | FEC repair | Payload is a repair shard, not source data |
| 5 | discontinuity | Decoder/timeline state must be resynchronized |

Unknown kinds, flags, versions, non-zero reserved fields, empty payloads and
inconsistent packet/FEC metadata are rejected. A source packet marks `frame
start` exactly when its index is zero and `frame end` exactly when it is the
last source packet. Repair packets mark neither boundary.

`original frame bytes` is limited to 16 MiB in v1. The packet sequence and
frame ID wrap naturally; receivers compare them with wrapping arithmetic once
the reordering window is defined. A datagram from another authenticated session
or stream is discarded before it reaches frame assembly.

## Reliable control envelope

Negotiation, feedback and teardown travel on an authenticated reliable control
channel independent of video/audio queues. Its fixed 28-byte prefix is:

| Offset | Size | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `BPMC` |
| 4 | 1 | major | `1` |
| 5 | 1 | minor | Sender minor version |
| 6 | 1 | kind | Message table below |
| 7 | 1 | header size | `28` in v1.0 |
| 8 | 2 | flags | Zero in v1.0 |
| 10 | 2 | reserved | Zero |
| 12 | 8 | session ID | Same authenticated media session as the datagrams |
| 20 | 4 | request ID | Correlates offer/answer and stop/ack operations |
| 24 | 2 | payload length | At most 4096 bytes |
| 26 | 2 | reserved | Zero |

| Kind | Message | Fixed known prefix |
| ---: | --- | --- |
| 1 | Offer | Version range; video/audio/color masks; maximum dimensions/FPS/MTU; minimum, initial and maximum bitrate; reorder limit; capabilities; route and clock IDs; audio formats |
| 2 | Answer | Selected version/codecs/profile/level/format; dimensions/FPS/MTU/bitrates; reorder window; streams; route and clock IDs; key epoch and security mode |
| 3 | Feedback | Highest sequence; complete/presented frames; receive/loss/late/reorder/FEC counters; bitrate, RTT, jitter, assembly/decode/presentation time; requested bitrate; queue depth and flags |
| 4 | KeyframeRequest | Stream, last usable frame and reason |
| 5 | Stop | Reason and scope (`all`, `video` or `audio`) |
| 6 | StopAck | Empty v1.0 prefix |

The 56-byte Offer prefix encodes, in order: minor minimum/maximum (`u8`, `u8`),
video/audio/color bit sets (three `u16`), maximum width/height/FPS/datagram size
(four `u16`), minimum/initial/maximum bitrate, maximum reorder time and
capabilities (five `u32`), route ID and monotonic clock origin (two `u64`), then
audio sample-rate bits (`u16`), packet-duration bits (`u8`) and maximum channels
(`u8`).

The 68-byte Answer prefix encodes eight initial `u8` values (minor, video,
audio, color, profile, level, channels and audio packet duration), four `u16`
values (width, height, FPS and datagram size), five `u32` values (target/min/max
bitrate, reorder time and capabilities), video/audio stream IDs (`u32`, `u32`),
route ID and clock origin (`u64`, `u64`), key epoch (`u32`), security mode (`u8`)
and three zero bytes.

The 68-byte Feedback prefix has sixteen `u32` values in the order shown in the
message table, followed by queue depth (`u16`) and feedback flags (`u16`). A
KeyframeRequest is stream ID (`u32`), last good frame (`u32`), reason (`u8`) and
three zero bytes. Stop is reason (`u8`), scope (`u8`) and two zero bytes.

An answer selects one video codec and optionally one audio codec. Codec values
are one-based while offer capabilities are bit sets. Initial v1 capabilities
cover low-latency hardware decoding, Reed-Solomon FEC and deadline-aware
retransmission. Advertising a capability does not enable it; the answer must
select the intersection.

The security mode records whether the selected transport supplies TLS 1.3 or
whether the media session needs its own AEAD context. Keys are never sent in
these messages. Key derivation and replay windows belong to the selected
transport/security handshake and must be defined before Gate T1.

Stop is idempotent. The receiver neutralizes its media lifecycle, releases
capture/decoder/audio resources and replies with `StopAck`; input uses an
independent lifecycle and is not stopped or recreated by these messages.

## Version compatibility

- A different major version is rejected.
- An offer declares its supported minor range; peers select the highest common
  minor version or fail before media starts.
- For a known message, a newer minor version may append fields after the known
  fixed prefix. Older receivers ignore that tail after validating the envelope.
- Existing offsets and meanings never change inside one major version.
- Unknown flags, malformed lengths and unknown required message kinds are
  rejected. New mandatory semantics require a new major version.

## Raw UDP authenticated envelope

This envelope belongs only to the raw UDP+AEAD transport candidate. QUIC
DATAGRAM already provides authenticated encryption through TLS 1.3 and carries
the Media v1 datagram directly.

Raw UDP uses AES-256-GCM with a distinct, ephemeral 32-byte traffic key for each
direction. The complete UDP payload, including this envelope and the GCM tag,
remains at most 1200 bytes. Consequently, an enclosed Media v1 datagram is at
most 1164 bytes while this candidate is active.

The 20-byte authenticated header is:

| Offset | Size | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `BPA1` |
| 4 | 1 | major | `1` |
| 5 | 1 | minor | `0` |
| 6 | 1 | header size | `20` |
| 7 | 1 | flags | Zero in v1.0 |
| 8 | 4 | key epoch | Non-zero epoch selected by the authenticated control plane |
| 12 | 8 | packet counter | Monotonic counter within the directional traffic key |
| 20 | variable | ciphertext | One complete encoded Media v1 datagram |
| final 16 | 16 | authentication tag | AES-GCM 128-bit tag |

The entire 20-byte header is additional authenticated data. The 96-bit GCM
nonce is `key epoch || packet counter`, both in network byte order. A sender
starts at counter zero and must stop using the key after counter `0xffffffff`;
it never persists or restores a traffic key/counter pair. Every new media
session receives new directional keys, even if it reuses a numeric key epoch.

Receivers authenticate before changing replay state. They accept a packet only
once inside a 256-packet sliding window and reject older, duplicate, malformed,
wrong-epoch or unauthenticated packets before frame assembly. Authentication
failure never advances the window.

The authenticated control plane will derive and install the ephemeral
directional keys before opening media sockets. The exact key-agreement and HKDF
transcript are deliberately not frozen by this increment and must be specified
and implemented before the raw UDP candidate can pass Gate T1.

## Golden vector

The normative vector is also stored in
[`vectors/media-v1.properties`](vectors/media-v1.properties). It represents the
first of two H.264 keyframe packets with a two-byte payload `aa bb`:

```text
42504d3101000138000500000102030405060708111213142122232431323334
414243444546474800000002000000040000000000000000aabb
```

The file also contains the normative v1.0 Offer vector. Both Kotlin and Rust
suites must encode these exact byte sequences and decode them back to the same
fields.

It also contains a deterministic raw UDP+AEAD vector. Its key is test material
only and must never be used by a real session. Kotlin and Rust must produce the
same envelope and reject any mutation or replay.

## Deliberately not frozen yet

Deadline adaptation, pacing, congestion control, FEC algorithm, key derivation
and replay-window details will be frozen after the QUIC DATAGRAM versus UDP+AEAD
bake-off. Reserving their negotiated capabilities here allows the deterministic
loss harness to exercise assembly without coupling the wire shape to one
transport.
