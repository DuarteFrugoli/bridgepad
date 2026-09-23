#[cfg(not(windows))]
fn main() {
    eprintln!(
        "The AOA host spike currently targets Windows. Linux support follows after the transport gate."
    );
}

#[cfg(windows)]
fn main() {
    if let Err(error) = windows::run() {
        eprintln!("USB spike failed: {error}");
        std::process::exit(1);
    }
}

#[cfg(windows)]
mod windows {
    use bridgepad_protocol::{
        HEADER_SIZE, MAX_PAYLOAD_SIZE, MessageType, PacketHeader, decode_packet, decode_ping,
        encode_packet,
    };
    use rusb::{
        Context, Device, DeviceHandle, Direction, Recipient, RequestType, TransferType, UsbContext,
    };
    use std::error::Error;
    use std::fmt::Write as _;
    use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

    const GOOGLE_VENDOR_ID: u16 = 0x18d1;
    const ACCESSORY_PRODUCT_IDS: [u16; 4] = [0x2d00, 0x2d01, 0x2d04, 0x2d05];
    const GET_PROTOCOL: u8 = 51;
    const SEND_STRING: u8 = 52;
    const START_ACCESSORY: u8 = 53;
    const CONTROL_TIMEOUT: Duration = Duration::from_secs(2);
    const BULK_TIMEOUT: Duration = Duration::from_secs(2);

    pub fn run() -> Result<(), Box<dyn Error>> {
        println!("BridgePad Android Open Accessory spike");
        println!("Connect one Android phone with a data-capable USB cable and unlock it.");
        let context = Context::new()?;
        if find_accessory(&context)?.is_none() {
            switch_android_device(&context)?;
            println!("AOA start requested; waiting for the phone to re-enumerate...");
        }
        let device = wait_for_accessory(&context, Duration::from_secs(12))?
            .ok_or("the phone did not re-enumerate in Android accessory mode")?;
        serve(&device)
    }

    fn switch_android_device(context: &Context) -> Result<(), Box<dyn Error>> {
        let mut candidates = Vec::new();
        for device in context.devices()?.iter() {
            let descriptor = device.device_descriptor()?;
            if descriptor.vendor_id() == GOOGLE_VENDOR_ID
                && ACCESSORY_PRODUCT_IDS.contains(&descriptor.product_id())
            {
                continue;
            }
            let Ok(handle) = device.open() else { continue };
            let mut protocol = [0_u8; 2];
            if handle
                .read_control(
                    rusb::request_type(Direction::In, RequestType::Vendor, Recipient::Device),
                    GET_PROTOCOL,
                    0,
                    0,
                    &mut protocol,
                    CONTROL_TIMEOUT,
                )
                .is_ok_and(|count| count == 2 && u16::from_le_bytes(protocol) >= 1)
            {
                candidates.push((device, handle));
            }
        }
        if candidates.is_empty() {
            return Err("no accessible AOA-capable Android device was found; verify the cable and Windows USB driver".into());
        }
        if candidates.len() > 1 {
            return Err("more than one AOA-capable Android device is connected; leave only the test phone attached".into());
        }
        let (_, handle) = candidates.pop().expect("one candidate was checked");
        let strings = [
            "BridgePad",
            "BridgePad Desktop",
            "Low-latency BridgePad USB transport spike",
            env!("CARGO_PKG_VERSION"),
            "https://github.com/DuarteFrugoli/bridgepad",
            "bridgepad-usb-spike",
        ];
        for (index, value) in strings.iter().enumerate() {
            let mut bytes = value.as_bytes().to_vec();
            bytes.push(0);
            handle.write_control(
                rusb::request_type(Direction::Out, RequestType::Vendor, Recipient::Device),
                SEND_STRING,
                0,
                u16::try_from(index)?,
                &bytes,
                CONTROL_TIMEOUT,
            )?;
        }
        handle.write_control(
            rusb::request_type(Direction::Out, RequestType::Vendor, Recipient::Device),
            START_ACCESSORY,
            0,
            0,
            &[],
            CONTROL_TIMEOUT,
        )?;
        Ok(())
    }

    fn wait_for_accessory(
        context: &Context,
        timeout: Duration,
    ) -> Result<Option<Device<Context>>, rusb::Error> {
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(device) = find_accessory(context)? {
                return Ok(Some(device));
            }
            if Instant::now() >= deadline {
                return Ok(None);
            }
            std::thread::sleep(Duration::from_millis(200));
        }
    }

    fn find_accessory(context: &Context) -> Result<Option<Device<Context>>, rusb::Error> {
        for device in context.devices()?.iter() {
            let descriptor = device.device_descriptor()?;
            if descriptor.vendor_id() == GOOGLE_VENDOR_ID
                && ACCESSORY_PRODUCT_IDS.contains(&descriptor.product_id())
            {
                return Ok(Some(device));
            }
        }
        Ok(None)
    }

    fn serve(device: &Device<Context>) -> Result<(), Box<dyn Error>> {
        let descriptor = device.device_descriptor()?;
        println!(
            "Accessory detected as {:04x}:{:04x}",
            descriptor.vendor_id(),
            descriptor.product_id(),
        );
        let handle = device.open().map_err(|error| {
            format!(
                "Windows cannot open the AOA interface ({error}). This spike must determine whether BridgePad can install a dedicated WinUSB-compatible driver without developer tooling"
            )
        })?;
        let (interface, input_endpoint, output_endpoint) = bulk_endpoints(device)?;
        let _ = handle.set_auto_detach_kernel_driver(true);
        handle.claim_interface(interface).map_err(|error| {
            format!(
                "Windows could not claim the AOA interface ({error}). A WinUSB-compatible driver may be required"
            )
        })?;
        println!("USB bulk link ready. Open Settings > USB Desktop test on Android.");
        println!("Press Ctrl+C to stop the spike.");
        echo_packets(&handle, input_endpoint, output_endpoint)
    }

    fn bulk_endpoints(device: &Device<Context>) -> Result<(u8, u8, u8), Box<dyn Error>> {
        let descriptor = device.active_config_descriptor()?;
        for interface in descriptor.interfaces() {
            for setting in interface.descriptors() {
                let mut input = None;
                let mut output = None;
                for endpoint in setting.endpoint_descriptors() {
                    if endpoint.transfer_type() != TransferType::Bulk {
                        continue;
                    }
                    match endpoint.direction() {
                        Direction::In => input = Some(endpoint.address()),
                        Direction::Out => output = Some(endpoint.address()),
                    }
                }
                if let (Some(input), Some(output)) = (input, output) {
                    return Ok((setting.interface_number(), input, output));
                }
            }
        }
        Err("the AOA device does not expose matching bulk endpoints".into())
    }

    fn echo_packets(
        handle: &DeviceHandle<Context>,
        input_endpoint: u8,
        output_endpoint: u8,
    ) -> Result<(), Box<dyn Error>> {
        let mut transfer = vec![0_u8; 16_384];
        let mut pending = Vec::new();
        let mut received = 0_u64;
        let mut benchmark_started = None;
        loop {
            let count = match handle.read_bulk(input_endpoint, &mut transfer, BULK_TIMEOUT) {
                Ok(count) => count,
                Err(rusb::Error::Timeout) => continue,
                Err(error) => return Err(error.into()),
            };
            pending.extend_from_slice(&transfer[..count]);
            while pending.len() >= HEADER_SIZE {
                let payload_length = usize::from(u16::from_be_bytes([pending[28], pending[29]]));
                if payload_length > MAX_PAYLOAD_SIZE {
                    return Err("Android sent a payload larger than the BridgePad v1 limit".into());
                }
                let frame_length = HEADER_SIZE + payload_length;
                if pending.len() < frame_length {
                    break;
                }
                let frame: Vec<u8> = pending.drain(..frame_length).collect();
                let packet = decode_packet(&frame)?;
                if packet.header.message_type != MessageType::Ping {
                    continue;
                }
                let nonce = decode_ping(packet)?;
                let response = encode_packet(
                    PacketHeader {
                        session_id: packet.header.session_id,
                        sequence: packet.header.sequence,
                        timestamp_micros: now_micros(),
                        message_type: MessageType::Pong,
                    },
                    &nonce.to_be_bytes(),
                )?;
                write_all_bulk(handle, output_endpoint, &response)?;
                received += 1;
                let started = benchmark_started.get_or_insert_with(Instant::now);
                if received.is_multiple_of(250) {
                    println!(
                        "Echoed {received} packets; last 250 completed in {:.2} s",
                        started.elapsed().as_secs_f64(),
                    );
                    benchmark_started = Some(Instant::now());
                }
            }
        }
    }

    fn write_all_bulk(
        handle: &DeviceHandle<Context>,
        endpoint: u8,
        bytes: &[u8],
    ) -> Result<(), Box<dyn Error>> {
        let mut written = 0;
        while written < bytes.len() {
            written += handle.write_bulk(endpoint, &bytes[written..], BULK_TIMEOUT)?;
        }
        Ok(())
    }

    fn now_micros() -> u64 {
        let micros = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_micros();
        u64::try_from(micros).unwrap_or(u64::MAX)
    }

    #[allow(dead_code)]
    fn device_id(device: &Device<Context>) -> String {
        let mut result = String::new();
        if let Ok(descriptor) = device.device_descriptor() {
            let _ = write!(
                &mut result,
                "{:04x}:{:04x}",
                descriptor.vendor_id(),
                descriptor.product_id()
            );
        }
        result
    }
}
