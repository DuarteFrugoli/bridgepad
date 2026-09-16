# ADR 0009: Isolate the Windows virtual-gamepad spike behind a backend contract

- Status: Accepted for experimentation
- Date: 2026-09-15

## Context

BridgePad Desktop must expose a controller that Windows, Steam and games detect
without manual mapping. This device layer must not leak into the transport,
protocol or session code because Windows and Linux require different backends.

The official Windows Virtual HID Framework is maintained by Microsoft, but it
requires a KMDF or WDM kernel-mode HID source driver. Shipping that path also
requires driver signing, installation and long-term kernel-driver maintenance.
The user-mode `InputInjector` gamepad API is prerelease and requires the
restricted `inputInjectionBrokered` application capability, so it is not a
general desktop distribution path.

ViGEmBus can expose an Xbox 360-compatible controller from user-mode software
and has a signed released driver, but its original project is retired. It is
therefore useful for proving BridgePad's behavior, not a backend that may spread
through the architecture unchecked.

## Decision

Create a platform-independent `bridgepad-virtual-device` contract containing
only normalized reports, lifecycle-neutral updates and backend-independent
errors. Put ViGEm code in the Windows-only `bridgepad-windows-vigem` adapter.
Use a separate `bridgepad-gamepad-spike` executable to exercise the adapter and
validate automatic recognition in `joy.cpl`, Steam and a game.

The spike presents an Xbox 360-compatible controller because that gives Windows
games a standard XInput path. BridgePad reports remain independent of XInput.
The adapter owns button translation, Y-axis convention conversion and trigger
scaling.

ViGEm is not selected as the final production backend by this decision. The
proposed production direction and its acceptance gate are recorded separately
in ADR 0010. The rest of BridgePad must require no changes when this adapter is
replaced.

## Consequences

- The Windows recognition risk can be tested before Android/network integration.
- Linux CI can compile the workspace through a non-Windows unsupported-platform
  implementation without depending on the Windows driver.
- The daemon and wire protocol do not know whether ViGEm, VHF or another backend
  creates the controller.
- ViGEmBus installation is an explicit spike prerequisite, not silently bundled.
- Gate D0 remains open until the device is observed in `joy.cpl`, Steam and a
  real game and a sustainable production installation path is chosen.

## References

- [Microsoft Virtual HID Framework](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/virtual-hid-framework--vhf-)
- [Microsoft InputInjector gamepad API](https://learn.microsoft.com/en-us/uwp/api/windows.ui.input.preview.injection.inputinjector.initializegamepadinjection)
- [Retired ViGEm organization](https://github.com/ViGEm)
- [vigem-rust](https://github.com/arounre/vigem-rust)
