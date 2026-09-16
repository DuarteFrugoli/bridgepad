use bridgepad_virtual_device::{DpadDirection, GamepadReport, VirtualGamepadDevice, button};
use bridgepad_windows_vigem::VigemGamepad;
use std::error::Error;
use std::io::{self, Write};
use std::thread;
use std::time::Duration;

const ACTIVE_TIME: Duration = Duration::from_millis(450);
const NEUTRAL_TIME: Duration = Duration::from_millis(180);

fn main() -> Result<(), Box<dyn Error>> {
    let mut gamepad = VigemGamepad::connect()?;
    gamepad.neutralize()?;

    println!("BridgePad Windows virtual gamepad spike");
    println!("A virtual Xbox 360 controller is connected.");
    println!("Open joy.cpl or Steam's controller test to verify that it appears automatically.");

    if std::env::args().any(|argument| argument == "--demo") {
        run_demo(&mut gamepad)?;
        return Ok(());
    }

    println!("Type 'demo' to exercise every standard control, or 'quit' to disconnect.");
    let stdin = io::stdin();
    loop {
        print!("> ");
        io::stdout().flush()?;
        let mut command = String::new();
        if stdin.read_line(&mut command)? == 0 {
            break;
        }
        match command.trim().to_ascii_lowercase().as_str() {
            "demo" | "d" | "" => run_demo(&mut gamepad)?,
            "quit" | "q" | "exit" => break,
            _ => println!("Unknown command. Use 'demo' or 'quit'."),
        }
    }

    gamepad.neutralize()?;
    println!("Virtual controller disconnected.");
    Ok(())
}

fn run_demo(gamepad: &mut impl VirtualGamepadDevice) -> Result<(), Box<dyn Error>> {
    println!("Starting deterministic controller demo...");
    demo_buttons(gamepad)?;
    demo_dpad(gamepad)?;
    demo_axes(gamepad)?;
    gamepad.neutralize()?;
    println!("Demo complete; the controller is neutral and remains connected.");
    Ok(())
}

fn demo_buttons(gamepad: &mut impl VirtualGamepadDevice) -> Result<(), Box<dyn Error>> {
    for (label, pressed_button) in [
        ("A", button::SOUTH),
        ("B", button::EAST),
        ("X", button::WEST),
        ("Y", button::NORTH),
        ("LB", button::LEFT_BUMPER),
        ("RB", button::RIGHT_BUMPER),
        ("Back", button::SELECT),
        ("Start", button::START),
        ("L3", button::LEFT_STICK),
        ("R3", button::RIGHT_STICK),
    ] {
        pulse(
            gamepad,
            label,
            GamepadReport {
                buttons: pressed_button,
                ..GamepadReport::default()
            },
        )?;
    }
    Ok(())
}

fn demo_dpad(gamepad: &mut impl VirtualGamepadDevice) -> Result<(), Box<dyn Error>> {
    for (label, direction) in [
        ("D-pad up", DpadDirection::North),
        ("D-pad right", DpadDirection::East),
        ("D-pad down", DpadDirection::South),
        ("D-pad left", DpadDirection::West),
    ] {
        pulse(
            gamepad,
            label,
            GamepadReport {
                dpad: direction,
                ..GamepadReport::default()
            },
        )?;
    }
    Ok(())
}

fn demo_axes(gamepad: &mut impl VirtualGamepadDevice) -> Result<(), Box<dyn Error>> {
    for (label, report) in [
        (
            "Left stick left",
            GamepadReport {
                left_x: -32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Left stick right",
            GamepadReport {
                left_x: 32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Left stick up",
            GamepadReport {
                left_y: -32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Left stick down",
            GamepadReport {
                left_y: 32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Right stick left",
            GamepadReport {
                right_x: -32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Right stick right",
            GamepadReport {
                right_x: 32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Right stick up",
            GamepadReport {
                right_y: -32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Right stick down",
            GamepadReport {
                right_y: 32_767,
                ..GamepadReport::default()
            },
        ),
        (
            "Left trigger",
            GamepadReport {
                left_trigger: u16::MAX,
                ..GamepadReport::default()
            },
        ),
        (
            "Right trigger",
            GamepadReport {
                right_trigger: u16::MAX,
                ..GamepadReport::default()
            },
        ),
    ] {
        pulse(gamepad, label, report)?;
    }
    Ok(())
}

fn pulse(
    gamepad: &mut impl VirtualGamepadDevice,
    label: &str,
    report: GamepadReport,
) -> Result<(), Box<dyn Error>> {
    println!("Testing {label}");
    gamepad.update(report)?;
    thread::sleep(ACTIVE_TIME);
    gamepad.neutralize()?;
    thread::sleep(NEUTRAL_TIME);
    Ok(())
}
