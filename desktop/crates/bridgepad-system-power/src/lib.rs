//! Host power status used only for optional performance guidance.

const LOW_BATTERY_THRESHOLD_PERCENT: u8 = 20;
const AC_OFFLINE: u8 = 0;
const BATTERY_STATUS_UNKNOWN: u8 = 255;
const NO_SYSTEM_BATTERY: u8 = 128;

/// Returns the charge percentage only while a notebook is running on a low battery.
/// Unknown readings, AC power, and desktops without a battery never trigger a warning.
pub fn low_battery_percentage() -> Option<u8> {
    let (ac_line_status, battery_flag, percentage) = platform::read_power_status()?;
    classify_low_battery(ac_line_status, battery_flag, percentage)
}

fn classify_low_battery(ac_line_status: u8, battery_flag: u8, percentage: u8) -> Option<u8> {
    if ac_line_status != AC_OFFLINE
        || battery_flag == BATTERY_STATUS_UNKNOWN
        || battery_flag & NO_SYSTEM_BATTERY != 0
        || percentage > LOW_BATTERY_THRESHOLD_PERCENT
    {
        return None;
    }
    Some(percentage)
}

#[cfg(windows)]
mod platform {
    use windows::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};

    pub(super) fn read_power_status() -> Option<(u8, u8, u8)> {
        let mut status = SYSTEM_POWER_STATUS::default();
        // SAFETY: status is a valid, writable SYSTEM_POWER_STATUS for this call.
        unsafe { GetSystemPowerStatus(&raw mut status) }.ok()?;
        Some((
            status.ACLineStatus,
            status.BatteryFlag,
            status.BatteryLifePercent,
        ))
    }
}

#[cfg(not(windows))]
mod platform {
    pub(super) fn read_power_status() -> Option<(u8, u8, u8)> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::{classify_low_battery, low_battery_percentage};

    #[test]
    fn warns_only_on_low_battery_without_ac() {
        assert_eq!(classify_low_battery(0, 2, 20), Some(20));
        assert_eq!(classify_low_battery(0, 4, 3), Some(3));
        assert_eq!(classify_low_battery(0, 2, 21), None);
        assert_eq!(classify_low_battery(1, 2, 10), None);
    }

    #[test]
    fn ignores_missing_or_unknown_battery_status() {
        assert_eq!(classify_low_battery(0, 128, 0), None);
        assert_eq!(classify_low_battery(0, 255, 10), None);
        assert_eq!(classify_low_battery(255, 2, 10), None);
        assert_eq!(classify_low_battery(0, 2, 255), None);
    }

    #[test]
    fn reads_host_power_status_without_panicking() {
        let _ = low_battery_percentage();
    }
}
