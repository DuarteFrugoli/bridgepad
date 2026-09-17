//! Platform-independent virtual controller contract for `BridgePad` Desktop.

use std::error::Error;
use std::fmt;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum DpadDirection {
    #[default]
    Neutral,
    North,
    NorthEast,
    East,
    SouthEast,
    South,
    SouthWest,
    West,
    NorthWest,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct GamepadReport {
    pub buttons: u16,
    pub dpad: DpadDirection,
    pub left_x: i16,
    pub left_y: i16,
    pub right_x: i16,
    pub right_y: i16,
    pub left_trigger: u16,
    pub right_trigger: u16,
}

pub mod button {
    pub const SOUTH: u16 = 1 << 0;
    pub const EAST: u16 = 1 << 1;
    pub const WEST: u16 = 1 << 2;
    pub const NORTH: u16 = 1 << 3;
    pub const LEFT_BUMPER: u16 = 1 << 4;
    pub const RIGHT_BUMPER: u16 = 1 << 5;
    pub const START: u16 = 1 << 6;
    pub const SELECT: u16 = 1 << 7;
    pub const LEFT_STICK: u16 = 1 << 8;
    pub const RIGHT_STICK: u16 = 1 << 9;
    pub const GUIDE: u16 = 1 << 10;
    pub const CAPTURE: u16 = 1 << 11;
    pub const EXTRA_3: u16 = 1 << 12;
    pub const EXTRA_4: u16 = 1 << 13;
    pub const EXTRA_5: u16 = 1 << 14;
    pub const EXTRA_6: u16 = 1 << 15;
}

pub trait VirtualGamepadDevice: Send {
    /// Sends a complete controller snapshot to the operating-system device.
    ///
    /// # Errors
    ///
    /// Returns an adapter-specific error if the virtual device is unavailable
    /// or rejects the report.
    fn update(&mut self, report: GamepadReport) -> Result<(), VirtualDeviceError>;

    /// Releases every button and returns every axis to its neutral value.
    ///
    /// # Errors
    ///
    /// Returns an adapter-specific error if the neutral report cannot be sent.
    fn neutralize(&mut self) -> Result<(), VirtualDeviceError> {
        self.update(GamepadReport::default())
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PointerReport {
    pub buttons: u8,
    pub delta_x: i32,
    pub delta_y: i32,
}

pub trait VirtualPointerDevice: Send {
    /// Sends relative pointer movement and the complete pointer-button state.
    ///
    /// # Errors
    ///
    /// Returns an adapter-specific error if the operating system rejects the input.
    fn update(&mut self, report: PointerReport) -> Result<(), VirtualDeviceError>;

    /// Releases pointer buttons without moving the pointer.
    ///
    /// # Errors
    ///
    /// Returns an adapter-specific error if the neutral report is rejected.
    fn neutralize(&mut self) -> Result<(), VirtualDeviceError> {
        self.update(PointerReport::default())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum VirtualDeviceErrorKind {
    UnsupportedPlatform,
    BackendUnavailable,
    DeviceCreation,
    Update,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct VirtualDeviceError {
    pub kind: VirtualDeviceErrorKind,
    detail: String,
}

impl VirtualDeviceError {
    #[must_use]
    pub fn new(kind: VirtualDeviceErrorKind, detail: impl Into<String>) -> Self {
        Self {
            kind,
            detail: detail.into(),
        }
    }

    #[must_use]
    pub fn detail(&self) -> &str {
        &self.detail
    }
}

impl fmt::Display for VirtualDeviceError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}", self.detail)
    }
}

impl Error for VirtualDeviceError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn report_defaults_to_a_fully_neutral_controller() {
        assert_eq!(GamepadReport::default().buttons, 0);
        assert_eq!(GamepadReport::default().dpad, DpadDirection::Neutral);
        assert_eq!(GamepadReport::default().left_x, 0);
        assert_eq!(GamepadReport::default().left_trigger, 0);
    }
}
