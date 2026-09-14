# Session UI and localization

The Home screen progressively reveals three choices on one page:

1. **Destination**: PC running Windows or Linux.
2. **Connection**: the compatible methods for the selected destination. For PC,
   Bluetooth is available while Wi-Fi and USB are visible as future work. With
   PC and Bluetooth selected, this step also asks for an already paired computer
   or **Pair a new PC**.
3. **Input**: virtual gamepad or physical controller. This step appears after a
   connection target has been chosen. Input can change during an active session
   without reconnecting the PC.

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

Changing destination, connection or PC clears every dependent choice below it.
**Connect and play** remains disabled until the three steps and, when applicable,
the physical capture choice are valid, Bluetooth is on and access permission is
granted. A selected PC that is no longer paired does not count as a valid
destination. The button then
starts HID registration and connects to the chosen PC, or requests temporary
discoverability if **Pair a new PC** was explicitly selected. Picking a destination
only fills the setup; it never starts a session on its own.

While Bluetooth is off, Connection hides paired-host and new-pairing choices
and offers **Turn on Bluetooth**. Missing access permissions are requested first.
This action waits for the adapter to be enabled and refreshes the paired PCs
shown directly in Connection. **Connect and play** stays disabled during
preparation.
A previous new-pairing choice
is cleared; enabling Bluetooth never implies consent to become discoverable.
The phone must be added from Windows Bluetooth settings during discoverability.

After the HID connection is confirmed, virtual input opens the virtual gamepad;
physical input opens a mouse touchpad that fills the usable screen. The physical
touchpad has no permanent menu button: Android's Back button or gesture returns
to Home without ending the Bluetooth session. A plain localized text hint remains
in a corner without a container or button styling. The touchpad does not repeat
connection state already available on Home and in the notification. **Resume game** reopens the
appropriate input screen, and **End session** explicitly releases the connection.

## Contextual options

- **Edit virtual layout**, available from Settings and the selected virtual-input
  options, opens a landscape canvas with the exact same safe area, margins and
  control sizes used during gameplay. Editor actions float above this canvas and
  never reduce or rescale it. A persistent compact bar provides Cancel, Save and
  an options toggle; the scrollable options panel contains instructions, presets
  and Reset to standard, and collapses automatically when a control is moved or
  resized. Every virtual control can be selected and dragged. Only the selected
  control shows its bounding box:
  side handles resize width or height independently and corner handles change
  both dimensions. Analog sticks expose only corner handles and always keep their
  proportions. Resizing has a minimum but no maximum. Saving immediately
  replaces the active layout and persists it across launches; canceling discards
  the draft. **Reset
  to standard** restores the built-in arrangement in the draft, which is only
  persisted when saved. **Symmetric**, **Asymmetric** and **Mobile** are built-in
  starting layouts; choosing one updates only the draft, and every control can
  still be moved or resized before saving.
- Physical-controller capture is selected directly inside the Input step, before
  connecting. It can also be changed during an active session without reconnecting
  the Bluetooth HID output.
- **Configure controller buttons** is optional and becomes available when the
  selected capture path detects a controller. The same mapping workflow and
  logical layout apply to Compatibility and Background USB.
- Only Background USB supports physical controller input with BridgePad hidden
  or the screen off. USB here is controller-to-phone input; output stays Bluetooth.
- **Settings** opens as a dedicated scrollable screen rather than a Home overlay.
  It contains virtual-controller customization, language guidance, connection
  metrics, physical-input diagnostics, app/device details and diagnostic report
  actions. Android Back and the visible Back action return to Home. Opening the
  layout editor from Settings returns to Settings after either Save or Cancel.

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
