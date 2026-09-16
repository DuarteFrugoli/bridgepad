//! Windows pointer output isolated from the transport and session layers.

#[cfg(windows)]
mod platform {
    use bridgepad_virtual_device::{
        PointerReport, VirtualDeviceError, VirtualDeviceErrorKind, VirtualPointerDevice,
    };
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        INPUT, INPUT_0, INPUT_MOUSE, MOUSE_EVENT_FLAGS, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_MOVE, MOUSEINPUT, SendInput,
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
                        mouseData: 0,
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

    fn mouse_flags(previous_buttons: u8, report: PointerReport) -> MOUSE_EVENT_FLAGS {
        let mut flags = MOUSE_EVENT_FLAGS(0);
        if report.delta_x != 0 || report.delta_y != 0 {
            flags |= MOUSEEVENTF_MOVE;
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
        flags
    }

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
                },
            );
            assert!(down.contains(MOUSEEVENTF_MOVE));
            assert!(down.contains(MOUSEEVENTF_LEFTDOWN));
            assert!(!down.contains(MOUSEEVENTF_LEFTUP));

            let up = mouse_flags(1, PointerReport::default());
            assert!(up.contains(MOUSEEVENTF_LEFTUP));
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use bridgepad_virtual_device::{
        PointerReport, VirtualDeviceError, VirtualDeviceErrorKind, VirtualPointerDevice,
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
}

pub use platform::WindowsPointer;
