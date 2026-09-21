//! Windows pointer output isolated from the transport and session layers.

#[cfg(windows)]
mod platform {
    use bridgepad_virtual_device::{
        KeyboardInput, KeyboardKey, PointerReport, VirtualDeviceError, VirtualDeviceErrorKind,
        VirtualKeyboardDevice, VirtualPointerDevice,
    };
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYEVENTF_KEYUP,
        KEYEVENTF_UNICODE, MOUSE_EVENT_FLAGS, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_MOVE, MOUSEEVENTF_MOVE_NOCOALESCE, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP,
        MOUSEEVENTF_WHEEL, MOUSEINPUT, SendInput, VIRTUAL_KEY, VK_BACK, VK_ESCAPE, VK_RETURN,
        VK_TAB,
    };

    pub struct WindowsPointer {
        previous_buttons: u8,
    }

    impl WindowsPointer {
        #[must_use]
        pub fn connect() -> Self {
            Self {
                previous_buttons: 0,
            }
        }
    }

    impl VirtualPointerDevice for WindowsPointer {
        fn update(&mut self, report: PointerReport) -> Result<(), VirtualDeviceError> {
            let flags = mouse_flags(self.previous_buttons, report);
            if flags.0 == 0 {
                return Ok(());
            }
            let input = INPUT {
                r#type: INPUT_MOUSE,
                Anonymous: INPUT_0 {
                    mi: MOUSEINPUT {
                        dx: report.delta_x,
                        dy: report.delta_y,
                        mouseData: report.scroll_y.saturating_mul(WHEEL_DELTA) as u32,
                        dwFlags: flags,
                        time: 0,
                        dwExtraInfo: 0,
                    },
                },
            };
            // SAFETY: `input` is a fully initialized mouse INPUT value and its
            // slice remains valid for the duration of this synchronous call.
            let sent = unsafe {
                SendInput(
                    &[input],
                    i32::try_from(std::mem::size_of::<INPUT>()).expect("INPUT size fits i32"),
                )
            };
            if sent != 1 {
                return Err(VirtualDeviceError::new(
                    VirtualDeviceErrorKind::Update,
                    "Windows rejected a pointer input report",
                ));
            }
            self.previous_buttons = report.buttons;
            Ok(())
        }
    }

    pub struct WindowsKeyboard;

    impl WindowsKeyboard {
        #[must_use]
        pub fn connect() -> Self {
            Self
        }
    }

    impl VirtualKeyboardDevice for WindowsKeyboard {
        fn send(&mut self, input: KeyboardInput) -> Result<(), VirtualDeviceError> {
            let inputs = match input {
                KeyboardInput::Text(text) => text
                    .encode_utf16()
                    .flat_map(unicode_inputs)
                    .collect::<Vec<_>>(),
                KeyboardInput::Key(key) => virtual_key_inputs(match key {
                    KeyboardKey::Backspace => VK_BACK,
                    KeyboardKey::Enter => VK_RETURN,
                    KeyboardKey::Tab => VK_TAB,
                    KeyboardKey::Escape => VK_ESCAPE,
                }),
            };
            send_keyboard_inputs(&inputs)
        }
    }

    fn unicode_inputs(code_unit: u16) -> [INPUT; 2] {
        [
            keyboard_input(VIRTUAL_KEY(0), code_unit, KEYEVENTF_UNICODE),
            keyboard_input(
                VIRTUAL_KEY(0),
                code_unit,
                KEYEVENTF_UNICODE | KEYEVENTF_KEYUP,
            ),
        ]
    }

    fn virtual_key_inputs(key: VIRTUAL_KEY) -> Vec<INPUT> {
        vec![
            keyboard_input(key, 0, Default::default()),
            keyboard_input(key, 0, KEYEVENTF_KEYUP),
        ]
    }

    fn keyboard_input(
        virtual_key: VIRTUAL_KEY,
        scan_code: u16,
        flags: windows::Win32::UI::Input::KeyboardAndMouse::KEYBD_EVENT_FLAGS,
    ) -> INPUT {
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: virtual_key,
                    wScan: scan_code,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        }
    }

    fn send_keyboard_inputs(inputs: &[INPUT]) -> Result<(), VirtualDeviceError> {
        if inputs.is_empty() {
            return Ok(());
        }
        // SAFETY: every element is a fully initialized keyboard INPUT and the
        // slice remains valid for the duration of this synchronous call.
        let sent = unsafe {
            SendInput(
                inputs,
                i32::try_from(std::mem::size_of::<INPUT>()).expect("INPUT size fits i32"),
            )
        };
        if sent as usize != inputs.len() {
            return Err(VirtualDeviceError::new(
                VirtualDeviceErrorKind::Update,
                "Windows rejected a keyboard input report",
            ));
        }
        Ok(())
    }

    fn mouse_flags(previous_buttons: u8, report: PointerReport) -> MOUSE_EVENT_FLAGS {
        let mut flags = MOUSE_EVENT_FLAGS(0);
        if report.delta_x != 0 || report.delta_y != 0 {
            flags |= MOUSEEVENTF_MOVE | MOUSEEVENTF_MOVE_NOCOALESCE;
        }
        if report.scroll_y != 0 {
            flags |= MOUSEEVENTF_WHEEL;
        }
        let previous_left = previous_buttons & 1 != 0;
        let next_left = report.buttons & 1 != 0;
        if previous_left != next_left {
            flags |= if next_left {
                MOUSEEVENTF_LEFTDOWN
            } else {
                MOUSEEVENTF_LEFTUP
            };
        }
        let previous_right = previous_buttons & 2 != 0;
        let next_right = report.buttons & 2 != 0;
        if previous_right != next_right {
            flags |= if next_right {
                MOUSEEVENTF_RIGHTDOWN
            } else {
                MOUSEEVENTF_RIGHTUP
            };
        }
        flags
    }

    const WHEEL_DELTA: i32 = 120;

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn combines_relative_motion_with_button_transitions() {
            let down = mouse_flags(
                0,
                PointerReport {
                    buttons: 1,
                    delta_x: 4,
                    delta_y: -3,
                    scroll_y: 0,
                },
            );
            assert!(down.contains(MOUSEEVENTF_MOVE));
            assert!(down.contains(MOUSEEVENTF_MOVE_NOCOALESCE));
            assert!(down.contains(MOUSEEVENTF_LEFTDOWN));
            assert!(!down.contains(MOUSEEVENTF_LEFTUP));

            let up = mouse_flags(1, PointerReport::default());
            assert!(up.contains(MOUSEEVENTF_LEFTUP));
        }

        #[test]
        fn combines_right_click_and_wheel() {
            let down = mouse_flags(
                0,
                PointerReport {
                    buttons: 2,
                    scroll_y: -1,
                    ..PointerReport::default()
                },
            );
            assert!(down.contains(MOUSEEVENTF_RIGHTDOWN));
            assert!(down.contains(MOUSEEVENTF_WHEEL));

            let up = mouse_flags(2, PointerReport::default());
            assert!(up.contains(MOUSEEVENTF_RIGHTUP));
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use bridgepad_virtual_device::{
        KeyboardInput, PointerReport, VirtualDeviceError, VirtualDeviceErrorKind,
        VirtualKeyboardDevice, VirtualPointerDevice,
    };

    pub struct WindowsPointer;

    impl WindowsPointer {
        #[must_use]
        pub fn connect() -> Self {
            Self
        }
    }

    impl VirtualPointerDevice for WindowsPointer {
        fn update(&mut self, _report: PointerReport) -> Result<(), VirtualDeviceError> {
            Err(VirtualDeviceError::new(
                VirtualDeviceErrorKind::UnsupportedPlatform,
                "Windows pointer output is unavailable on this platform",
            ))
        }
    }

    pub struct WindowsKeyboard;

    impl WindowsKeyboard {
        #[must_use]
        pub fn connect() -> Self {
            Self
        }
    }

    impl VirtualKeyboardDevice for WindowsKeyboard {
        fn send(&mut self, _input: KeyboardInput) -> Result<(), VirtualDeviceError> {
            Err(VirtualDeviceError::new(
                VirtualDeviceErrorKind::UnsupportedPlatform,
                "Windows keyboard output is unavailable on this platform",
            ))
        }
    }
}

pub use platform::{WindowsKeyboard, WindowsPointer};
