//! Experimental Windows virtual-controller adapter backed by `ViGEmBus`.
//!
//! `ViGEm` is deliberately contained in this crate so the daemon and protocol do
//! not depend on a specific Windows virtual-device backend.

#[cfg(windows)]
mod platform {
    use bridgepad_virtual_device::{
        DpadDirection, GamepadReport, VirtualDeviceError, VirtualDeviceErrorKind,
        VirtualGamepadDevice, button,
    };
    use vigem_rust::{Client, Ready, TargetHandle, X360Button, X360Report, Xbox360};

    pub struct VigemGamepad {
        _client: Client,
        target: TargetHandle<Xbox360, Ready>,
    }

    impl VigemGamepad {
        /// Connects to `ViGEmBus` and creates a ready Xbox 360 virtual device.
        ///
        /// # Errors
        ///
        /// Returns an error if the bus is missing, incompatible or cannot create
        /// a controller target.
        pub fn connect() -> Result<Self, VirtualDeviceError> {
            let client = Client::connect().map_err(|error| {
                VirtualDeviceError::new(
                    VirtualDeviceErrorKind::BackendUnavailable,
                    format!("could not connect to ViGEmBus: {error}"),
                )
            })?;
            let target = client
                .new_x360_target()
                .plug()
                .and_then(|pending| pending.wait_for_ready())
                .map_err(|error| {
                    VirtualDeviceError::new(
                        VirtualDeviceErrorKind::DeviceCreation,
                        format!("could not create the virtual controller: {error}"),
                    )
                })?;
            Ok(Self {
                _client: client,
                target,
            })
        }
    }

    impl VirtualGamepadDevice for VigemGamepad {
        fn update(&mut self, report: GamepadReport) -> Result<(), VirtualDeviceError> {
            self.target
                .update(&to_x360_report(report))
                .map_err(|error| {
                    VirtualDeviceError::new(
                        VirtualDeviceErrorKind::Update,
                        format!("could not update the virtual controller: {error}"),
                    )
                })
        }
    }

    fn to_x360_report(source: GamepadReport) -> X360Report {
        let mut buttons = X360Button::empty();
        insert_button(&mut buttons, source.buttons, button::SOUTH, X360Button::A);
        insert_button(&mut buttons, source.buttons, button::EAST, X360Button::B);
        insert_button(&mut buttons, source.buttons, button::WEST, X360Button::X);
        insert_button(&mut buttons, source.buttons, button::NORTH, X360Button::Y);
        insert_button(
            &mut buttons,
            source.buttons,
            button::LEFT_BUMPER,
            X360Button::LEFT_SHOULDER,
        );
        insert_button(
            &mut buttons,
            source.buttons,
            button::RIGHT_BUMPER,
            X360Button::RIGHT_SHOULDER,
        );
        insert_button(
            &mut buttons,
            source.buttons,
            button::START,
            X360Button::START,
        );
        insert_button(
            &mut buttons,
            source.buttons,
            button::SELECT,
            X360Button::BACK,
        );
        insert_button(
            &mut buttons,
            source.buttons,
            button::LEFT_STICK,
            X360Button::LEFT_THUMB,
        );
        insert_button(
            &mut buttons,
            source.buttons,
            button::RIGHT_STICK,
            X360Button::RIGHT_THUMB,
        );
        insert_button(
            &mut buttons,
            source.buttons,
            button::GUIDE,
            X360Button::GUIDE,
        );

        for dpad_button in dpad_buttons(source.dpad) {
            buttons.insert(*dpad_button);
        }

        X360Report {
            buttons,
            left_trigger: trigger_to_u8(source.left_trigger),
            right_trigger: trigger_to_u8(source.right_trigger),
            thumb_lx: source.left_x,
            thumb_ly: source.left_y.saturating_neg(),
            thumb_rx: source.right_x,
            thumb_ry: source.right_y.saturating_neg(),
        }
    }

    fn insert_button(
        target: &mut X360Button,
        source: u16,
        source_button: u16,
        target_button: X360Button,
    ) {
        if source & source_button != 0 {
            target.insert(target_button);
        }
    }

    fn dpad_buttons(direction: DpadDirection) -> &'static [X360Button] {
        match direction {
            DpadDirection::Neutral => &[],
            DpadDirection::North => &[X360Button::DPAD_UP],
            DpadDirection::NorthEast => &[X360Button::DPAD_UP, X360Button::DPAD_RIGHT],
            DpadDirection::East => &[X360Button::DPAD_RIGHT],
            DpadDirection::SouthEast => &[X360Button::DPAD_DOWN, X360Button::DPAD_RIGHT],
            DpadDirection::South => &[X360Button::DPAD_DOWN],
            DpadDirection::SouthWest => &[X360Button::DPAD_DOWN, X360Button::DPAD_LEFT],
            DpadDirection::West => &[X360Button::DPAD_LEFT],
            DpadDirection::NorthWest => &[X360Button::DPAD_UP, X360Button::DPAD_LEFT],
        }
    }

    fn trigger_to_u8(value: u16) -> u8 {
        let scaled = (u32::from(value) * 255 + 32_767) / 65_535;
        u8::try_from(scaled).expect("scaled u16 trigger always fits in u8")
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn maps_bridgepad_buttons_and_diagonal_dpad_to_xinput() {
            let report = to_x360_report(GamepadReport {
                buttons: button::SOUTH | button::LEFT_BUMPER | button::START | button::GUIDE,
                dpad: DpadDirection::SouthWest,
                ..GamepadReport::default()
            });

            assert!(report.buttons.contains(X360Button::A));
            assert!(report.buttons.contains(X360Button::LEFT_SHOULDER));
            assert!(report.buttons.contains(X360Button::START));
            assert!(report.buttons.contains(X360Button::GUIDE));
            assert!(report.buttons.contains(X360Button::DPAD_DOWN));
            assert!(report.buttons.contains(X360Button::DPAD_LEFT));
            assert!(!report.buttons.contains(X360Button::B));
        }

        #[test]
        fn maps_axes_and_scales_triggers_for_xinput() {
            let report = to_x360_report(GamepadReport {
                left_x: -32_767,
                left_y: 12_345,
                right_x: 32_767,
                right_y: -12_345,
                left_trigger: 32_768,
                right_trigger: u16::MAX,
                ..GamepadReport::default()
            });

            assert_eq!(report.thumb_lx, -32_767);
            assert_eq!(report.thumb_ly, -12_345);
            assert_eq!(report.thumb_rx, 32_767);
            assert_eq!(report.thumb_ry, 12_345);
            assert_eq!(report.left_trigger, 128);
            assert_eq!(report.right_trigger, 255);
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use bridgepad_virtual_device::{
        GamepadReport, VirtualDeviceError, VirtualDeviceErrorKind, VirtualGamepadDevice,
    };

    pub struct VigemGamepad;

    impl VigemGamepad {
        /// Reports that this experimental backend is unavailable off Windows.
        ///
        /// # Errors
        ///
        /// Always returns [`VirtualDeviceErrorKind::UnsupportedPlatform`].
        pub fn connect() -> Result<Self, VirtualDeviceError> {
            Err(VirtualDeviceError::new(
                VirtualDeviceErrorKind::UnsupportedPlatform,
                "the ViGEm spike is available only on Windows",
            ))
        }
    }

    impl VirtualGamepadDevice for VigemGamepad {
        fn update(&mut self, _report: GamepadReport) -> Result<(), VirtualDeviceError> {
            Err(VirtualDeviceError::new(
                VirtualDeviceErrorKind::UnsupportedPlatform,
                "the ViGEm spike is available only on Windows",
            ))
        }
    }
}

pub use platform::VigemGamepad;
