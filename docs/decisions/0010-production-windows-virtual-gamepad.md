# ADR 0010: Target a signed UMDF2/XUSB backend for Windows production releases

- Status: Proposed
- Date: 2026-09-16

## Context

BridgePad must be recognized automatically by Steam and by Windows games that
consume XInput directly. A generic HID gamepad can cover HID, DirectInput and
some Steam paths, but does not by itself guarantee an XInput controller slot.
The production backend must also be installable on a clean consumer machine,
run without elevation after installation and remain replaceable without changes
to the BridgePad protocol or normalized controller model.

The current ViGEmBus spike provides the required Xbox 360/XInput behavior and
uses an existing signed driver, but its upstream project is retired. It is a
practical development and alpha dependency, not a sustainable component that
BridgePad can maintain indefinitely.

Microsoft's Virtual HID Framework alone is not sufficient for the XInput
requirement. `InputInjector` is also unsuitable because it requires the
restricted `inputInjectionBrokered` capability and is not a general desktop
distribution API. Microsoft does support HID minidrivers in UMDF2, which keeps
BridgePad-specific driver code in user mode. Open implementations such as
HIDMaestro demonstrate a combined UMDF2 HID device and XUSB companion, but no
third-party implementation or runtime dependency is adopted by this decision.

## Proposed direction

Use two explicit stages:

1. Continue development and alpha validation through the isolated
   `bridgepad-windows-vigem` adapter.
2. Before a stable public desktop release, implement or adopt a narrowly scoped
   BridgePad-owned UMDF2 package with an Xbox 360-compatible XUSB personality.

The production package may also publish a correlated HID view where it improves
DirectInput and diagnostics, but XUSB compatibility remains required. The Rust
daemon communicates with the Windows package through a small local interface;
transport, protocol and session crates continue to depend only on
`bridgepad-virtual-device`.

HIDMaestro is a technical reference because its source is MIT-licensed and it
demonstrates the relevant user-mode architecture. Adoption, forking or reuse
requires a separate code, security, packaging and license review. A locally
self-signed root certificate is not an acceptable final consumer installation
strategy for BridgePad.

The preferred release package is signed through Microsoft's Windows hardware
distribution process and installed by the same BridgePad Desktop installer.
Installation may request administrator approval once; ordinary sessions must
not. ViGEm may remain a temporary fallback while the production backend is
validated, but it must not leak into the daemon or protocol interfaces.

## Production acceptance gate

The proposed backend is not accepted until all of the following pass:

- automatic recognition in `joy.cpl`, current Steam and representative native
  XInput games without manual mapping;
- buttons, D-pad, both sticks, independent triggers and rumble round-trip;
- create, update, neutralize and destroy behavior under normal exit, crash,
  disconnect, sleep and resume;
- clean installation, upgrade and removal on supported Windows versions;
- no test-signing mode and no locally trusted self-signed root certificate;
- a Microsoft-trusted driver package and reproducible installer build;
- normal operation without administrator rights;
- measured latency and CPU use no worse than the accepted ViGEm baseline;
- compatibility review for security software and representative anti-cheat
  environments without claiming universal support;
- an explicit Windows x64 and ARM64 support decision.

If this gate cannot be met at acceptable maintenance and signing cost,
BridgePad must reconsider a maintained commercially redistributable backend or
ship the desktop feature as a clearly labeled ViGEm-based beta. It must not
silently downgrade to a generic HID device while promising XInput compatibility.

## Consequences

- ViGEm remains useful now without becoming an architectural commitment.
- The likely production implementation adds a small WDK/C++ or equivalent
  Windows-driver project beside the Rust workspace.
- Driver signing, installer maintenance and Windows regression testing become
  release requirements rather than late packaging tasks.
- Linux remains independent and can use its native `uinput`/`uhid` backend.
- The final choice stays reversible because the desktop core consumes only the
  platform-independent virtual-device contract.

## References

- [Microsoft UMDF HID minidriver sample](https://github.com/microsoft/Windows-driver-samples/tree/main/hid/vhidmini2)
- [Microsoft Virtual HID Framework](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/virtual-hid-framework--vhf-)
- [Microsoft driver signing requirements](https://learn.microsoft.com/en-us/windows-hardware/drivers/dashboard/code-signing-reqs)
- [Microsoft restricted capability declarations](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/app-capability-declarations)
- [Retired ViGEmBus](https://github.com/nefarius/ViGEmBus)
- [HIDMaestro technical reference](https://github.com/hifihedgehog/HIDMaestro)
