#[cfg(not(windows))]
fn main() {
    eprintln!("The Bluetooth Desktop spike currently requires Windows 10 or newer.");
}

#[cfg(windows)]
fn main() -> windows::core::Result<()> {
    windows_spike::run()
}

#[cfg(windows)]
mod windows_spike {
    use bridgepad_protocol::{
        CAPABILITY_GAMEPAD, HEADER_SIZE, MAX_PAYLOAD_SIZE, MessageType, PacketHeader,
        decode_gamepad_snapshot, decode_packet, decode_session_start, encode_packet,
    };
    use bridgepad_virtual_device::{
        DpadDirection, GamepadReport, VirtualDeviceError, VirtualGamepadDevice,
    };
    use bridgepad_windows_vigem::VigemGamepad;
    use btleplug::api::{
        Central, CentralEvent, Characteristic, Manager as _, Peripheral as _, ScanFilter, WriteType,
    };
    use btleplug::platform::{Manager, Peripheral};
    use futures_executor::block_on;
    use futures_util::StreamExt;
    use std::collections::HashSet;
    use std::future::IntoFuture;
    use std::io;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc::{SyncSender, sync_channel};
    use std::thread;
    use std::time::{Duration, Instant};
    use tokio::sync::mpsc;
    use uuid::Uuid;
    use windows::Devices::Bluetooth::BluetoothAdapter;
    use windows::Devices::Bluetooth::Rfcomm::{RfcommServiceId, RfcommServiceProvider};
    use windows::Foundation::TypedEventHandler;
    use windows::Networking::Sockets::{
        StreamSocket, StreamSocketListener, StreamSocketListenerConnectionReceivedEventArgs,
    };
    use windows::Storage::Streams::{DataReader, DataWriter, InputStreamOptions};
    use windows::core::{GUID, Result};

    const RFCOMM_SERVICE_UUID: GUID = GUID::from_u128(0x7a1b8d5f_6c24_4e71_9f52_a4b8d9c30101);
    const BLE_SERVICE_UUID: Uuid = Uuid::from_u128(0x7a1b8d5f_6c24_4e71_9f52_a4b8d9c30201);
    const BLE_TRANSMIT_UUID: Uuid = Uuid::from_u128(0x7a1b8d5f_6c24_4e71_9f52_a4b8d9c30202);
    const BLE_RECEIVE_UUID: Uuid = Uuid::from_u128(0x7a1b8d5f_6c24_4e71_9f52_a4b8d9c30203);
    const PACKET_SIZE: usize = 20;
    const MAGIC: &[u8; 4] = b"BPBT";
    const PACKET_REPORT: u8 = 1;
    const PACKET_PING: u8 = 2;
    const PACKET_PONG: u8 = 3;
    const PACKET_SUMMARY_REQUEST: u8 = 4;
    const PACKET_SUMMARY_RESPONSE: u8 = 5;

    #[derive(Clone, Copy)]
    enum RfcommMode {
        Benchmark,
        Playable,
    }

    pub fn run() -> Result<()> {
        let adapter = wait(BluetoothAdapter::GetDefaultAsync()?)?;
        let requested = std::env::args()
            .nth(1)
            .unwrap_or_else(|| "rfcomm".to_owned());
        if requested != "rfcomm" && requested != "ble" && requested != "play" {
            return Err(spike_error(
                "usage: bridgepad-bluetooth-spike [rfcomm|ble|play]",
            ));
        }
        println!("BridgePad Bluetooth Desktop transport spike");
        println!("Classic supported: {}", adapter.IsClassicSupported()?);
        println!(
            "Bluetooth LE supported: {}",
            adapter.IsLowEnergySupported()?
        );

        if requested == "ble" {
            if !adapter.IsLowEnergySupported()? {
                return Err(spike_error(
                    "the Windows adapter does not support Bluetooth LE",
                ));
            }
            return run_ble_central();
        }
        if !adapter.IsClassicSupported()? {
            return Err(spike_error(
                "the Windows adapter does not support Bluetooth Classic",
            ));
        }

        let mode = if requested == "play" {
            RfcommMode::Playable
        } else {
            RfcommMode::Benchmark
        };
        let server = RfcommProbeServer::start(mode)?;
        if matches!(mode, RfcommMode::Playable) {
            println!("RFCOMM playable receiver: advertising and ready");
            println!(
                "WARNING: this spike trusts the paired Bluetooth device without app authentication."
            );
            println!("Use Settings > Bluetooth Desktop test > Start playable RFCOMM session.");
        } else {
            println!("RFCOMM: advertising and ready");
            println!("Use Settings > Bluetooth Desktop test in the Android app.");
        }
        println!("The PC and phone must already be paired for this spike.");
        println!("Press Enter to stop cleanly.");
        let mut line = String::new();
        let _ = io::stdin().read_line(&mut line);
        drop(server);
        println!("Bluetooth spike stopped.");
        Ok(())
    }

    fn run_ble_central() -> Result<()> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|error| spike_error(&format!("could not start BLE runtime: {error}")))?;
        runtime.block_on(async {
            let manager = Manager::new()
                .await
                .map_err(|error| spike_error(&format!("BLE manager failed: {error}")))?;
            let adapters = manager
                .adapters()
                .await
                .map_err(|error| spike_error(&format!("BLE adapter lookup failed: {error}")))?;
            let central = adapters
                .into_iter()
                .next()
                .ok_or_else(|| spike_error("no BLE adapter was found"))?;
            central
                .start_scan(ScanFilter {
                    services: vec![BLE_SERVICE_UUID],
                })
                .await
                .map_err(|error| spike_error(&format!("BLE scan failed: {error}")))?;
            println!("BLE GATT central: scanning for the Android benchmark service");
            println!("Now tap Test BLE GATT in the Android app.");
            println!("Press Enter to stop cleanly.");

            let mut events = central
                .events()
                .await
                .map_err(|error| spike_error(&format!("BLE event stream failed: {error}")))?;
            let busy = Arc::new(AtomicBool::new(false));
            let (stop_tx, mut stop_rx) = mpsc::channel::<()>(1);
            thread::spawn(move || {
                let mut line = String::new();
                let _ = io::stdin().read_line(&mut line);
                let _ = stop_tx.blocking_send(());
            });

            loop {
                tokio::select! {
                    _ = stop_rx.recv() => break,
                    event = events.next() => {
                        let Some(event) = event else { break };
                        let id = match event {
                            CentralEvent::DeviceDiscovered(id) | CentralEvent::DeviceUpdated(id) => id,
                            _ => continue,
                        };
                        if busy.swap(true, Ordering::AcqRel) {
                            continue;
                        }
                        let peripheral = central.peripheral(&id).await;
                        let busy_after = Arc::clone(&busy);
                        match peripheral {
                            Ok(peripheral) => {
                                tokio::spawn(async move {
                                    if let Err(error) = serve_ble(peripheral).await {
                                        eprintln!("BLE connection ended: {error}");
                                    }
                                    busy_after.store(false, Ordering::Release);
                                });
                            }
                            Err(error) => {
                                eprintln!("BLE peripheral lookup failed: {error}");
                                busy.store(false, Ordering::Release);
                            }
                        }
                    }
                }
            }
            let _ = central.stop_scan().await;
            println!("Bluetooth spike stopped.");
            Ok(())
        })
    }

    async fn serve_ble(peripheral: Peripheral) -> std::result::Result<(), String> {
        let properties = peripheral
            .properties()
            .await
            .map_err(|error| error.to_string())?;
        if !properties
            .as_ref()
            .is_some_and(|value| value.services.contains(&BLE_SERVICE_UUID))
        {
            return Ok(());
        }
        peripheral
            .connect()
            .await
            .map_err(|error| error.to_string())?;
        peripheral
            .discover_services()
            .await
            .map_err(|error| error.to_string())?;
        let characteristics = peripheral.characteristics();
        let transmit = find_characteristic(&characteristics, BLE_TRANSMIT_UUID)?;
        let receive = find_characteristic(&characteristics, BLE_RECEIVE_UUID)?;
        peripheral
            .subscribe(&transmit)
            .await
            .map_err(|error| error.to_string())?;
        let mut notifications = peripheral
            .notifications()
            .await
            .map_err(|error| error.to_string())?;
        println!("BLE GATT client connected and subscribed");
        let mut stats = ProbeStats::default();
        loop {
            tokio::select! {
                notification = notifications.next() => {
                    let Some(notification) = notification else { break };
                    if notification.uuid == BLE_TRANSMIT_UUID {
                        match packet_type(&notification.value) {
                            Some(PACKET_REPORT) => stats.record(packet_primary_value(&notification.value)),
                            Some(PACKET_PING) => {
                                let mut pong = notification.value;
                                pong[5] = PACKET_PONG;
                                peripheral
                                    .write(&receive, &pong, WriteType::WithoutResponse)
                                    .await
                                    .map_err(|error| error.to_string())?;
                            }
                            Some(PACKET_SUMMARY_REQUEST) => {
                                let summary = stats.summary_packet();
                                print_summary(
                                    "BLE GATT",
                                    packet_primary_value(&notification.value),
                                    &summary,
                                );
                                peripheral
                                    .write(&receive, &summary, WriteType::WithoutResponse)
                                    .await
                                    .map_err(|error| error.to_string())?;
                            }
                            _ => {}
                        }
                    }
                }
                _ = tokio::time::sleep(Duration::from_millis(100)) => {
                    if !peripheral.is_connected().await.map_err(|error| error.to_string())? {
                        break;
                    }
                }
            }
        }
        let _ = peripheral.disconnect().await;
        println!("BLE GATT client disconnected; scanning for reconnection");
        Ok(())
    }

    fn find_characteristic(
        characteristics: &std::collections::BTreeSet<Characteristic>,
        uuid: Uuid,
    ) -> std::result::Result<Characteristic, String> {
        characteristics
            .iter()
            .find(|characteristic| characteristic.uuid == uuid)
            .cloned()
            .ok_or_else(|| format!("BLE characteristic {uuid} was not found"))
    }

    struct RfcommProbeServer {
        provider: RfcommServiceProvider,
        listener: StreamSocketListener,
        connection_token: i64,
    }

    impl RfcommProbeServer {
        fn start(mode: RfcommMode) -> Result<Self> {
            let service_id = RfcommServiceId::FromUuid(RFCOMM_SERVICE_UUID)?;
            let provider = wait(RfcommServiceProvider::CreateAsync(&service_id)?)?;
            let listener = StreamSocketListener::new()?;
            let connection_token =
                listener.ConnectionReceived(&TypedEventHandler::<
                    StreamSocketListener,
                    StreamSocketListenerConnectionReceivedEventArgs,
                >::new(move |_, event| {
                    let socket = event.ok()?.Socket()?;
                    thread::spawn(move || {
                        println!("RFCOMM client connected");
                        if let Err(error) = serve_rfcomm(socket, mode) {
                            eprintln!("RFCOMM connection ended: {error}");
                        } else {
                            println!("RFCOMM client disconnected");
                        }
                    });
                    Ok(())
                }))?;
            wait(listener.BindServiceNameAsync(&provider.ServiceId()?.AsString()?)?)?;
            provider.StartAdvertisingWithRadioDiscoverability(&listener, false)?;
            Ok(Self {
                provider,
                listener,
                connection_token,
            })
        }
    }

    impl Drop for RfcommProbeServer {
        fn drop(&mut self) {
            let _ = self.provider.StopAdvertising();
            let _ = self
                .listener
                .RemoveConnectionReceived(self.connection_token);
            let _ = self.listener.Close();
        }
    }

    fn serve_rfcomm(socket: StreamSocket, mode: RfcommMode) -> Result<()> {
        match mode {
            RfcommMode::Benchmark => serve_rfcomm_benchmark(socket),
            RfcommMode::Playable => serve_rfcomm_gamepad(socket),
        }
    }

    fn serve_rfcomm_benchmark(socket: StreamSocket) -> Result<()> {
        let input = socket.InputStream()?;
        let reader = DataReader::CreateDataReader(&input)?;
        reader.SetInputStreamOptions(InputStreamOptions::Partial)?;
        let (outbound, outbound_receiver) = sync_channel::<[u8; PACKET_SIZE]>(32);
        let writer_socket = socket.clone();
        let writer_thread = thread::Builder::new()
            .name("BridgePad-RFCOMM-writer".to_owned())
            .spawn(move || -> Result<()> {
                let output = writer_socket.OutputStream()?;
                let writer = DataWriter::CreateDataWriter(&output)?;
                while let Ok(packet) = outbound_receiver.recv() {
                    writer.WriteBytes(&packet)?;
                    wait(writer.StoreAsync()?)?;
                }
                let _ = writer.DetachStream();
                let _ = writer.Close();
                Ok(())
            })
            .map_err(|error| spike_error(&format!("could not start RFCOMM writer: {error}")))?;
        let mut buffered = Vec::with_capacity(PACKET_SIZE * 2);
        let mut stats = ProbeStats::default();
        let read_result = (|| -> Result<()> {
            loop {
                let loaded = wait(reader.LoadAsync(PACKET_SIZE as u32)?)?;
                if loaded == 0 {
                    break;
                }
                let mut chunk = vec![0_u8; loaded as usize];
                reader.ReadBytes(&mut chunk)?;
                buffered.extend_from_slice(&chunk);
                while buffered.len() >= PACKET_SIZE {
                    let packet: Vec<_> = buffered.drain(..PACKET_SIZE).collect();
                    match packet_type(&packet) {
                        Some(PACKET_REPORT) => stats.record(packet_primary_value(&packet)),
                        Some(PACKET_PING) => {
                            let mut pong = packet;
                            pong[5] = PACKET_PONG;
                            queue_packet(&outbound, pong.try_into().expect("fixed packet size"))?;
                        }
                        Some(PACKET_SUMMARY_REQUEST) => {
                            let summary = stats.summary_packet();
                            print_summary("RFCOMM", packet_primary_value(&packet), &summary);
                            queue_packet(&outbound, summary)?;
                        }
                        _ => {}
                    }
                }
            }
            Ok(())
        })();
        drop(outbound);
        let writer_result = writer_thread
            .join()
            .map_err(|_| spike_error("RFCOMM writer thread panicked"))?;
        let _ = reader.DetachStream();
        let _ = reader.Close();
        let _ = socket.Close();
        read_result?;
        writer_result
    }

    fn queue_packet(
        outbound: &SyncSender<[u8; PACKET_SIZE]>,
        packet: [u8; PACKET_SIZE],
    ) -> Result<()> {
        outbound
            .send(packet)
            .map_err(|_| spike_error("RFCOMM writer stopped before the response was sent"))
    }

    fn serve_rfcomm_gamepad(socket: StreamSocket) -> Result<()> {
        let input = socket.InputStream()?;
        let reader = DataReader::CreateDataReader(&input)?;
        reader.SetInputStreamOptions(InputStreamOptions::Partial)?;
        let (outbound, outbound_receiver) = sync_channel::<Vec<u8>>(32);
        let writer_socket = socket.clone();
        let writer_thread = thread::Builder::new()
            .name("BridgePad-RFCOMM-gamepad-writer".to_owned())
            .spawn(move || -> Result<()> {
                let output = writer_socket.OutputStream()?;
                let writer = DataWriter::CreateDataWriter(&output)?;
                while let Ok(packet) = outbound_receiver.recv() {
                    writer.WriteBytes(&packet)?;
                    wait(writer.StoreAsync()?)?;
                }
                let _ = writer.DetachStream();
                let _ = writer.Close();
                Ok(())
            })
            .map_err(|error| spike_error(&format!("could not start RFCOMM writer: {error}")))?;

        let mut buffered = Vec::with_capacity(HEADER_SIZE * 2);
        let mut gamepad: Option<PlayableGamepad> = None;
        let mut active_session_id = None;
        let mut latest_sequence = None;
        let read_result = (|| -> Result<()> {
            loop {
                let loaded = wait(reader.LoadAsync(512)?)?;
                if loaded == 0 {
                    break;
                }
                let mut chunk = vec![0_u8; loaded as usize];
                reader.ReadBytes(&mut chunk)?;
                buffered.extend_from_slice(&chunk);

                while let Some(frame_length) = bridge_frame_length(&buffered)? {
                    if buffered.len() < frame_length {
                        break;
                    }
                    let bytes: Vec<_> = buffered.drain(..frame_length).collect();
                    let packet = decode_packet(&bytes).map_err(|error| {
                        spike_error(&format!("invalid BridgePad packet: {error}"))
                    })?;
                    match packet.header.message_type {
                        MessageType::SessionStart => {
                            if gamepad.is_some() {
                                return Err(spike_error(
                                    "a Bluetooth gamepad session is already active",
                                ));
                            }
                            let request = decode_session_start(packet).map_err(|error| {
                                spike_error(&format!("invalid SessionStart: {error}"))
                            })?;
                            if request.requested_capabilities & CAPABILITY_GAMEPAD == 0 {
                                return Err(spike_error("Android did not request gamepad input"));
                            }
                            gamepad = Some(PlayableGamepad::connect()?);
                            active_session_id = Some(packet.header.session_id);
                            latest_sequence = None;
                            queue_bytes(
                                &outbound,
                                response_packet(
                                    packet.header,
                                    MessageType::SessionReady,
                                    &CAPABILITY_GAMEPAD.to_be_bytes(),
                                )?,
                            )?;
                            println!("Playable RFCOMM gamepad session started");
                        }
                        MessageType::GamepadSnapshot => {
                            if active_session_id != Some(packet.header.session_id) {
                                return Err(spike_error(
                                    "gamepad snapshot belongs to another session",
                                ));
                            }
                            if is_newer_sequence(latest_sequence, packet.header.sequence) {
                                let snapshot =
                                    decode_gamepad_snapshot(packet).map_err(|error| {
                                        spike_error(&format!("invalid gamepad snapshot: {error}"))
                                    })?;
                                gamepad
                                    .as_mut()
                                    .ok_or_else(|| spike_error("gamepad session is not active"))?
                                    .update(to_gamepad_report(snapshot))
                                    .map_err(|error| spike_error(&error.to_string()))?;
                                latest_sequence = Some(packet.header.sequence);
                            }
                        }
                        MessageType::Ping => {
                            queue_bytes(
                                &outbound,
                                response_packet(packet.header, MessageType::Pong, packet.payload)?,
                            )?;
                        }
                        MessageType::SessionStop => {
                            if active_session_id == Some(packet.header.session_id) {
                                gamepad = None;
                                active_session_id = None;
                                latest_sequence = None;
                                println!("Playable RFCOMM gamepad session stopped");
                                return Ok(());
                            }
                        }
                        _ => {
                            return Err(spike_error(
                                "message is not supported by the playable spike",
                            ));
                        }
                    }
                }
            }
            Ok(())
        })();

        drop(gamepad);
        drop(outbound);
        let writer_result = writer_thread
            .join()
            .map_err(|_| spike_error("RFCOMM gamepad writer thread panicked"))?;
        let _ = reader.DetachStream();
        let _ = reader.Close();
        let _ = socket.Close();
        read_result?;
        writer_result
    }

    fn bridge_frame_length(buffered: &[u8]) -> Result<Option<usize>> {
        if buffered.len() < HEADER_SIZE {
            return Ok(None);
        }
        let payload_length = usize::from(u16::from_be_bytes([buffered[28], buffered[29]]));
        if payload_length > MAX_PAYLOAD_SIZE {
            return Err(spike_error("BridgePad payload exceeds the v1 limit"));
        }
        Ok(Some(HEADER_SIZE + payload_length))
    }

    fn queue_bytes(outbound: &SyncSender<Vec<u8>>, packet: Vec<u8>) -> Result<()> {
        outbound
            .send(packet)
            .map_err(|_| spike_error("RFCOMM writer stopped before the response was sent"))
    }

    fn response_packet(
        request: PacketHeader,
        message_type: MessageType,
        payload: &[u8],
    ) -> Result<Vec<u8>> {
        encode_packet(
            PacketHeader {
                session_id: request.session_id,
                sequence: request.sequence,
                timestamp_micros: 0,
                message_type,
            },
            payload,
        )
        .map_err(|error| spike_error(&format!("could not encode response: {error}")))
    }

    fn is_newer_sequence(latest: Option<u32>, candidate: u32) -> bool {
        latest.is_none_or(|previous| {
            let distance = candidate.wrapping_sub(previous);
            distance != 0 && distance < 0x8000_0000
        })
    }

    fn to_gamepad_report(snapshot: bridgepad_protocol::GamepadSnapshot) -> GamepadReport {
        let dpad = match snapshot.dpad {
            0 => DpadDirection::Neutral,
            1 => DpadDirection::North,
            2 => DpadDirection::NorthEast,
            3 => DpadDirection::East,
            4 => DpadDirection::SouthEast,
            5 => DpadDirection::South,
            6 => DpadDirection::SouthWest,
            7 => DpadDirection::West,
            8 => DpadDirection::NorthWest,
            _ => unreachable!("the protocol decoder validates d-pad values"),
        };
        GamepadReport {
            buttons: snapshot.buttons,
            dpad,
            left_x: snapshot.left_x,
            left_y: snapshot.left_y,
            right_x: snapshot.right_x,
            right_y: snapshot.right_y,
            left_trigger: snapshot.left_trigger,
            right_trigger: snapshot.right_trigger,
        }
    }

    struct PlayableGamepad {
        device: VigemGamepad,
    }

    impl PlayableGamepad {
        fn connect() -> Result<Self> {
            let mut device =
                VigemGamepad::connect().map_err(|error| spike_error(&error.to_string()))?;
            device
                .neutralize()
                .map_err(|error| spike_error(&error.to_string()))?;
            Ok(Self { device })
        }

        fn update(&mut self, report: GamepadReport) -> std::result::Result<(), VirtualDeviceError> {
            self.device.update(report)
        }
    }

    impl Drop for PlayableGamepad {
        fn drop(&mut self) {
            if let Err(error) = self.device.neutralize() {
                eprintln!("Could not neutralize RFCOMM gamepad: {error}");
            }
        }
    }

    #[derive(Default)]
    struct ProbeStats {
        sequences: HashSet<u32>,
        first_received: Option<Instant>,
        last_received: Option<Instant>,
        maximum_gap: Duration,
    }

    impl ProbeStats {
        fn record(&mut self, sequence: u32) {
            if !self.sequences.insert(sequence) {
                return;
            }
            let now = Instant::now();
            if let Some(previous) = self.last_received {
                self.maximum_gap = self
                    .maximum_gap
                    .max(now.saturating_duration_since(previous));
            } else {
                self.first_received = Some(now);
            }
            self.last_received = Some(now);
        }

        fn summary_packet(&self) -> [u8; PACKET_SIZE] {
            let duration = match (self.first_received, self.last_received) {
                (Some(first), Some(last)) => last.saturating_duration_since(first),
                _ => Duration::ZERO,
            };
            let mut packet = [0_u8; PACKET_SIZE];
            packet[..MAGIC.len()].copy_from_slice(MAGIC);
            packet[4] = 1;
            packet[5] = PACKET_SUMMARY_RESPONSE;
            packet[8..12].copy_from_slice(&(self.sequences.len() as u32).to_be_bytes());
            packet[12..16].copy_from_slice(&duration_micros_u32(duration).to_be_bytes());
            packet[16..20].copy_from_slice(&duration_micros_u32(self.maximum_gap).to_be_bytes());
            packet
        }
    }

    fn packet_type(bytes: &[u8]) -> Option<u8> {
        if bytes.len() != PACKET_SIZE || &bytes[..MAGIC.len()] != MAGIC || bytes[4] != 1 {
            return None;
        }
        matches!(
            bytes[5],
            PACKET_REPORT
                | PACKET_PING
                | PACKET_PONG
                | PACKET_SUMMARY_REQUEST
                | PACKET_SUMMARY_RESPONSE
        )
        .then_some(bytes[5])
    }

    fn packet_primary_value(bytes: &[u8]) -> u32 {
        u32::from_be_bytes(bytes[8..12].try_into().expect("fixed packet field"))
    }

    fn print_summary(label: &str, expected: u32, packet: &[u8; PACKET_SIZE]) {
        let received = packet_primary_value(packet);
        let duration_micros = u32::from_be_bytes(packet[12..16].try_into().expect("duration"));
        let maximum_gap_micros =
            u32::from_be_bytes(packet[16..20].try_into().expect("maximum gap"));
        let duration_seconds = duration_micros as f64 / 1_000_000.0;
        let report_rate = if duration_seconds > 0.0 {
            received.saturating_sub(1) as f64 / duration_seconds
        } else {
            0.0
        };
        println!(
            "{label} summary: received {received}/{expected}, rate {report_rate:.2} Hz, maximum gap {:.2} ms",
            maximum_gap_micros as f64 / 1_000.0,
        );
    }

    fn duration_micros_u32(duration: Duration) -> u32 {
        duration.as_micros().min(u32::MAX as u128) as u32
    }

    fn wait<F>(operation: F) -> F::Output
    where
        F: IntoFuture,
    {
        block_on(operation.into_future())
    }

    fn spike_error(message: &str) -> windows::core::Error {
        windows::core::Error::new(
            windows::core::HRESULT(0x8000_4005_u32.cast_signed()),
            message,
        )
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn accepts_only_complete_version_one_probe_packets() {
            let mut packet = [0_u8; PACKET_SIZE];
            packet[..4].copy_from_slice(MAGIC);
            packet[4] = 1;
            packet[5] = PACKET_REPORT;
            assert_eq!(packet_type(&packet), Some(PACKET_REPORT));

            packet[4] = 2;
            assert_eq!(packet_type(&packet), None);
            assert_eq!(packet_type(&packet[..PACKET_SIZE - 1]), None);
        }

        #[test]
        fn summary_reports_unique_delivery_and_receive_gap() {
            let mut stats = ProbeStats::default();
            stats.record(7);
            stats.record(7);
            stats.record(8);

            let packet = stats.summary_packet();

            assert_eq!(packet_type(&packet), Some(PACKET_SUMMARY_RESPONSE));
            assert_eq!(packet_primary_value(&packet), 2);
        }
    }
}
