# Session UI and localization

The Home screen contains three choices on one page:

1. **Input**: virtual gamepad or physical controller. Input can change during an
   active session without reconnecting the PC.
2. **Connection**: Bluetooth output. Wi-Fi and USB output are explicitly marked
   as future work, not selectable transports.
3. **Destination**: an already paired computer or **Pair a new PC**. Windows is
   the validated destination; console support is not currently offered.

Each new setup starts with no input, transport or destination selected, including
after explicitly ending a session. Previously saved setup preferences are ignored.
Choices survive rotation while configuring the current session, and reopening an
active session reflects its actual input/connection without interrupting it.
Onboarding completion and per-controller mappings remain persistent.
For physical input, the Input step also requires an explicit capture choice:
**Compatibility** reads Android game-controller events while BridgePad is visible;
**Background USB** claims a USB HID controller directly and can keep reading it
outside the app or with the screen off. This capture choice is independent from
the Bluetooth output transport and may be prepared before a PC session exists.

**Connect and play** remains disabled until the three steps and, when applicable,
the physical capture choice are valid, Bluetooth is on and access permission is
granted. A selected PC that is no longer paired does not count as a valid
destination. The button then
starts HID registration and connects to the chosen PC, or requests temporary
discoverability if **Pair a new PC** was explicitly selected. Picking a destination
only fills the setup; it never starts a session on its own.

While Bluetooth is off, Destination hides paired-host and new-pairing choices
and offers **Turn on Bluetooth**. Missing access permissions are requested first.
This action waits for the adapter to be enabled, refreshes paired PCs and opens
the destination picker. **Connect and play** stays disabled during preparation.
A previous new-pairing choice
is cleared; enabling Bluetooth never implies consent to become discoverable.
The phone must be added from Windows Bluetooth settings during discoverability.

After the HID connection is confirmed, virtual input opens the virtual gamepad;
physical input opens the large mouse touchpad. **Session menu** returns to Home
without ending the Bluetooth session. **Resume game** reopens the appropriate
input screen, and **End session** explicitly releases the connection.

## Contextual options

- **Virtual gamepad layout** describes the current standard layout. Alternative
  presets and a layout editor are future features, not implemented settings.
- Physical-controller capture is selected directly inside the Input step, before
  connecting. It can also be changed during an active session without reconnecting
  the Bluetooth HID output.
- **Configure controller buttons** is optional and becomes available when the
  selected capture path detects a controller. The same mapping workflow and
  logical layout apply to Compatibility and Background USB.
- Only Background USB supports physical controller input with BridgePad hidden
  or the screen off. USB here is controller-to-phone input; output stays Bluetooth.
- **Settings** contains language guidance, connection metrics, physical-input
  diagnostics, app/device details and diagnostic report actions.

## Languages

Default English resources live in `app/src/main/res/values/strings.xml`;
Brazilian Portuguese resources live in `values-pt-rBR/strings.xml`. UI labels,
onboarding, mapping instructions, session notices and foreground notifications
use Android string resources. Button legends such as A/B/X/Y and protocol/debug
identifiers intentionally retain their conventional names.
Session notices retain resource IDs and formatting arguments instead of resolved
text, so a locale change also translates a notice already on screen.

The app follows the Android locale. Android 13+ also exposes a per-app language
choice, declared through `locale_config.xml`; **Settings > Change app language**
opens that system page. Older Android versions follow the system language.
See the [Android per-app language documentation](https://developer.android.com/guide/topics/resources/app-languages).

Diagnostic reports retain technical field names and identifiers in English.
Review reports before sharing them. Personal host selections are not published.

`LocalizationResourcesTest` checks resource-key parity, duplicate/empty strings
and matching format arguments. Hardware validation of the redesigned flow must
be recorded separately; earlier gate approvals do not validate this UI revision.
