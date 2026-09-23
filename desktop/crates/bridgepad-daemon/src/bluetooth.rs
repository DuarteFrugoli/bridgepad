//! Windows RFCOMM receiver used by BridgePad Desktop.

#[cfg(windows)]
mod platform {
    use crate::AnyError;
    use bridgepad_protocol::{
        CAPABILITY_GAMEPAD, CAPABILITY_KEYBOARD, CAPABILITY_POINTER, HEADER_SIZE,
        KEYBOARD_MODIFIER_ALT, KEYBOARD_MODIFIER_CONTROL, KEYBOARD_MODIFIER_META,
        KEYBOARD_MODIFIER_SHIFT, KeyboardInput as ProtocolKeyboardInput,
        KeyboardKey as ProtocolKeyboardKey, MAX_PAYLOAD_SIZE, MessageType, PacketHeader,
        decode_gamepad_snapshot, decode_keyboard, decode_packet, decode_pointer,
        decode_session_start, encode_packet,
    };
    use bridgepad_virtual_device::{
        DpadDirection, GamepadReport, KeyboardInput, KeyboardKey, KeyboardModifiers, PointerReport,
        VirtualGamepadDevice, VirtualKeyboardDevice, VirtualPointerDevice,
    };
    use bridgepad_windows_pointer::{WindowsKeyboard, WindowsPointer};
    use bridgepad_windows_vigem::VigemGamepad;
    use futures_executor::block_on;
    use std::collections::HashMap;
    use std::future::IntoFuture;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::mpsc::{SyncSender, sync_channel};
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::{Duration, Instant};
    use windows::Devices::Bluetooth::Rfcomm::{RfcommServiceId, RfcommServiceProvider};
    use windows::Foundation::TypedEventHandler;
    use windows::Networking::Sockets::{
        SocketQualityOfService, StreamSocket, StreamSocketListener,
        StreamSocketListenerConnectionReceivedEventArgs,
    };
    use windows::Storage::Streams::{DataReader, DataWriter, InputStreamOptions};
    use windows::core::GUID;

    const RFCOMM_SERVICE_UUID: GUID = GUID::from_u128(0x7a1b8d5f_6c24_4e71_9f52_a4b8d9c30101);

    #[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
    pub struct BluetoothServerSnapshot {
        pub connected_clients: usize,
        pub active_sessions: usize,
    }

    pub struct BluetoothDesktopServer {
        provider: RfcommServiceProvider,
        listener: StreamSocketListener,
        connection_token: i64,
        connected_clients: Arc<AtomicUsize>,
        active_sessions: Arc<AtomicUsize>,
        active_sockets: Arc<Mutex<HashMap<usize, StreamSocket>>>,
        stopped: bool,
    }

    impl BluetoothDesktopServer {
        pub fn start() -> Result<Self, AnyError> {
            let service_id = RfcommServiceId::FromUuid(RFCOMM_SERVICE_UUID)?;
            let provider = wait(RfcommServiceProvider::CreateAsync(&service_id)?)?;
            let listener = StreamSocketListener::new()?;
            listener
                .Control()?
                .SetQualityOfService(SocketQualityOfService::LowLatency)?;
            let connected_clients = Arc::new(AtomicUsize::new(0));
            let active_sessions = Arc::new(AtomicUsize::new(0));
            let callback_clients = Arc::clone(&connected_clients);
            let callback_sessions = Arc::clone(&active_sessions);
            let active_sockets = Arc::new(Mutex::new(HashMap::new()));
            let callback_sockets = Arc::clone(&active_sockets);
            let next_connection_id = Arc::new(AtomicUsize::new(1));
            let callback_connection_id = Arc::clone(&next_connection_id);
            let connection_token =
                listener.ConnectionReceived(&TypedEventHandler::<
                    StreamSocketListener,
                    StreamSocketListenerConnectionReceivedEventArgs,
                >::new(move |_, event| {
                    let socket = event.ok()?.Socket()?;
                    let clients = Arc::clone(&callback_clients);
                    let sessions = Arc::clone(&callback_sessions);
                    let sockets = Arc::clone(&callback_sockets);
                    let connection_id = callback_connection_id.fetch_add(1, Ordering::Relaxed);
                    if let Ok(mut active) = sockets.lock() {
                        active.insert(connection_id, socket.clone());
                    }
                    thread::spawn(move || {
                        let _client = ConnectionLease::new(clients, sockets, connection_id);
                        println!("Bluetooth RFCOMM client connected");
                        if let Err(error) = serve_gamepad(socket, sessions) {
                            eprintln!("Bluetooth RFCOMM connection ended: {error}");
                        } else {
                            println!("Bluetooth RFCOMM client disconnected");
                        }
                    });
                    Ok(())
                }))?;
            wait(listener.BindServiceNameAsync(&provider.ServiceId()?.AsString()?)?)?;
            provider.StartAdvertisingWithRadioDiscoverability(&listener, false)?;
            println!("Bluetooth RFCOMM receiver ready");
            Ok(Self {
                provider,
                listener,
                connection_token,
                connected_clients,
                active_sessions,
                active_sockets,
                stopped: false,
            })
        }

        pub fn snapshot(&self) -> BluetoothServerSnapshot {
            BluetoothServerSnapshot {
                connected_clients: self.connected_clients.load(Ordering::Relaxed),
                active_sessions: self.active_sessions.load(Ordering::Relaxed),
            }
        }

        pub fn stop(&mut self) {
            if self.stopped {
                return;
            }
            self.stopped = true;
            let _ = self.provider.StopAdvertising();
            let _ = self
                .listener
                .RemoveConnectionReceived(self.connection_token);
            let _ = self.listener.Close();
            if let Ok(sockets) = self.active_sockets.lock() {
                for socket in sockets.values() {
                    let _ = socket.Close();
                }
            }
            let deadline = Instant::now() + Duration::from_secs(4);
            while self.connected_clients.load(Ordering::Relaxed) > 0 && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(10));
            }
        }
    }

    impl Drop for BluetoothDesktopServer {
        fn drop(&mut self) {
            self.stop();
        }
    }

    fn serve_gamepad(
        socket: StreamSocket,
        active_sessions: Arc<AtomicUsize>,
    ) -> Result<(), AnyError> {
        let input = socket.InputStream()?;
        let reader = DataReader::CreateDataReader(&input)?;
        reader.SetInputStreamOptions(InputStreamOptions::Partial)?;
        let (outbound, outbound_receiver) = sync_channel::<Vec<u8>>(32);
        let writer_socket = socket.clone();
        let writer_thread = thread::Builder::new()
            .name("BridgePad-RFCOMM-writer".to_owned())
            .spawn(move || -> Result<(), AnyError> {
                let output = writer_socket.OutputStream()?;
                let writer = DataWriter::CreateDataWriter(&output)?;
                while let Ok(packet) = outbound_receiver.recv() {
                    writer.WriteBytes(&packet)?;
                    wait(writer.StoreAsync()?)?;
                }
                let _ = writer.DetachStream();
                let _ = writer.Close();
                Ok(())
            })?;

        let mut buffered = Vec::with_capacity(HEADER_SIZE * 2);
        let mut gamepad: Option<GamepadLease> = None;
        let mut pointer: Option<PointerLease> = None;
        let mut keyboard: Option<WindowsKeyboard> = None;
        let mut active_session_id = None;
        let mut latest_sequence = None;
        let read_result = (|| -> Result<(), AnyError> {
            loop {
                let loaded = wait(reader.LoadAsync(512)?)?;
                if loaded == 0 {
                    break;
                }
                let mut chunk = vec![0_u8; loaded as usize];
                reader.ReadBytes(&mut chunk)?;
                buffered.extend_from_slice(&chunk);

                while let Some(frame_length) = frame_length(&buffered)? {
                    if buffered.len() < frame_length {
                        break;
                    }
                    let bytes: Vec<_> = buffered.drain(..frame_length).collect();
                    let packet = decode_packet(&bytes)?;
                    match packet.header.message_type {
                        MessageType::SessionStart => {
                            if gamepad.is_some() {
                                return Err("a Bluetooth gamepad session is already active".into());
                            }
                            let request = decode_session_start(packet)?;
                            if request.requested_capabilities & CAPABILITY_GAMEPAD == 0 {
                                return Err("Android did not request gamepad input".into());
                            }
                            if request.requested_capabilities & CAPABILITY_KEYBOARD == 0 {
                                return Err("Android did not request keyboard input".into());
                            }
                            if request.requested_capabilities & CAPABILITY_POINTER == 0 {
                                return Err("Android did not request pointer input".into());
                            }
                            gamepad = Some(GamepadLease::new(
                                VigemGamepad::connect().map_err(|error| error.to_string())?,
                                Arc::clone(&active_sessions),
                            )?);
                            pointer = Some(PointerLease::new(WindowsPointer::connect())?);
                            keyboard = Some(WindowsKeyboard::connect());
                            active_session_id = Some(packet.header.session_id);
                            latest_sequence = None;
                            queue(
                                &outbound,
                                response_packet(
                                    packet.header,
                                    MessageType::SessionReady,
                                    &(CAPABILITY_GAMEPAD
                                        | CAPABILITY_POINTER
                                        | CAPABILITY_KEYBOARD)
                                        .to_be_bytes(),
                                )?,
                            )?;
                            println!("Bluetooth XInput session started");
                        }
                        MessageType::GamepadSnapshot => {
                            if active_session_id != Some(packet.header.session_id) {
                                return Err("gamepad snapshot belongs to another session".into());
                            }
                            if is_newer_sequence(latest_sequence, packet.header.sequence) {
                                let snapshot = decode_gamepad_snapshot(packet)?;
                                gamepad
                                    .as_mut()
                                    .ok_or("Bluetooth gamepad session is not active")?
                                    .update(to_gamepad_report(snapshot))?;
                                latest_sequence = Some(packet.header.sequence);
                            }
                        }
                        MessageType::Keyboard => {
                            if active_session_id != Some(packet.header.session_id) {
                                return Err("keyboard input belongs to another session".into());
                            }
                            let input = match decode_keyboard(packet)? {
                                ProtocolKeyboardInput::Text(text) => KeyboardInput::Text(text),
                                ProtocolKeyboardInput::Key(key) => KeyboardInput::Key(match key {
                                    ProtocolKeyboardKey::Backspace => KeyboardKey::Backspace,
                                    ProtocolKeyboardKey::D => KeyboardKey::D,
                                    ProtocolKeyboardKey::Enter => KeyboardKey::Enter,
                                    ProtocolKeyboardKey::Left => KeyboardKey::Left,
                                    ProtocolKeyboardKey::M => KeyboardKey::M,
                                    ProtocolKeyboardKey::Right => KeyboardKey::Right,
                                    ProtocolKeyboardKey::Tab => KeyboardKey::Tab,
                                    ProtocolKeyboardKey::Escape => KeyboardKey::Escape,
                                }),
                                ProtocolKeyboardInput::Shortcut { modifiers, key } => {
                                    KeyboardInput::Shortcut {
                                        modifiers: KeyboardModifiers {
                                            alt: modifiers & KEYBOARD_MODIFIER_ALT != 0,
                                            control: modifiers & KEYBOARD_MODIFIER_CONTROL != 0,
                                            meta: modifiers & KEYBOARD_MODIFIER_META != 0,
                                            shift: modifiers & KEYBOARD_MODIFIER_SHIFT != 0,
                                        },
                                        key: match key {
                                            ProtocolKeyboardKey::Backspace => {
                                                KeyboardKey::Backspace
                                            }
                                            ProtocolKeyboardKey::D => KeyboardKey::D,
                                            ProtocolKeyboardKey::Enter => KeyboardKey::Enter,
                                            ProtocolKeyboardKey::Left => KeyboardKey::Left,
                                            ProtocolKeyboardKey::M => KeyboardKey::M,
                                            ProtocolKeyboardKey::Right => KeyboardKey::Right,
                                            ProtocolKeyboardKey::Tab => KeyboardKey::Tab,
                                            ProtocolKeyboardKey::Escape => KeyboardKey::Escape,
                                        },
                                    }
                                }
                            };
                            keyboard
                                .as_mut()
                                .ok_or("Bluetooth keyboard session is not active")?
                                .send(input)?;
                        }
                        MessageType::Pointer => {
                            if active_session_id != Some(packet.header.session_id) {
                                return Err("pointer input belongs to another session".into());
                            }
                            let report = decode_pointer(packet)?;
                            pointer
                                .as_mut()
                                .ok_or("Bluetooth pointer session is not active")?
                                .update(PointerReport {
                                    buttons: report.buttons,
                                    delta_x: report.delta_x,
                                    delta_y: report.delta_y,
                                    scroll_y: report.scroll_y,
                                    zoom_y: report.zoom_y,
                                })?;
                        }
                        MessageType::Ping => queue(
                            &outbound,
                            response_packet(packet.header, MessageType::Pong, packet.payload)?,
                        )?,
                        MessageType::SessionStop => {
                            if active_session_id == Some(packet.header.session_id) {
                                gamepad = None;
                                pointer = None;
                                keyboard = None;
                                println!("Bluetooth XInput session stopped");
                                return Ok(());
                            }
                        }
                        _ => return Err("message is not supported by Bluetooth Desktop".into()),
                    }
                }
            }
            Ok(())
        })();

        drop(gamepad);
        drop(pointer);
        drop(keyboard);
        drop(outbound);
        let writer_result = writer_thread
            .join()
            .map_err(|_| "Bluetooth RFCOMM writer thread panicked")?;
        let _ = reader.DetachStream();
        let _ = reader.Close();
        let _ = socket.Close();
        read_result?;
        writer_result
    }

    fn frame_length(buffered: &[u8]) -> Result<Option<usize>, AnyError> {
        if buffered.len() < HEADER_SIZE {
            return Ok(None);
        }
        let payload_length = usize::from(u16::from_be_bytes([buffered[28], buffered[29]]));
        if payload_length > MAX_PAYLOAD_SIZE {
            return Err("BridgePad payload exceeds the v1 limit".into());
        }
        Ok(Some(HEADER_SIZE + payload_length))
    }

    fn queue(outbound: &SyncSender<Vec<u8>>, packet: Vec<u8>) -> Result<(), AnyError> {
        outbound
            .send(packet)
            .map_err(|_| "Bluetooth RFCOMM writer stopped before sending a response".into())
    }

    fn response_packet(
        request: PacketHeader,
        message_type: MessageType,
        payload: &[u8],
    ) -> Result<Vec<u8>, AnyError> {
        Ok(encode_packet(
            PacketHeader {
                session_id: request.session_id,
                sequence: request.sequence,
                timestamp_micros: 0,
                message_type,
            },
            payload,
        )?)
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
            _ => unreachable!("the protocol decoder validates D-pad values"),
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

    struct GamepadLease {
        device: VigemGamepad,
        active_sessions: Arc<AtomicUsize>,
    }

    impl GamepadLease {
        fn new(
            mut device: VigemGamepad,
            active_sessions: Arc<AtomicUsize>,
        ) -> Result<Self, AnyError> {
            device.neutralize().map_err(|error| error.to_string())?;
            active_sessions.fetch_add(1, Ordering::Relaxed);
            Ok(Self {
                device,
                active_sessions,
            })
        }

        fn update(&mut self, report: GamepadReport) -> Result<(), AnyError> {
            self.device
                .update(report)
                .map_err(|error| error.to_string().into())
        }
    }

    impl Drop for GamepadLease {
        fn drop(&mut self) {
            if let Err(error) = self.device.shutdown() {
                eprintln!("Could not safely stop Bluetooth XInput gamepad: {error}");
            }
            self.active_sessions.fetch_sub(1, Ordering::Relaxed);
        }
    }

    struct PointerLease {
        device: WindowsPointer,
    }

    impl PointerLease {
        fn new(mut device: WindowsPointer) -> Result<Self, AnyError> {
            device.neutralize().map_err(|error| error.to_string())?;
            Ok(Self { device })
        }

        fn update(&mut self, report: PointerReport) -> Result<(), AnyError> {
            self.device
                .update(report)
                .map_err(|error| error.to_string().into())
        }
    }

    impl Drop for PointerLease {
        fn drop(&mut self) {
            if let Err(error) = self.device.neutralize() {
                eprintln!("Could not neutralize Bluetooth pointer: {error}");
            }
        }
    }

    struct ConnectionLease {
        connected_clients: Arc<AtomicUsize>,
        active_sockets: Arc<Mutex<HashMap<usize, StreamSocket>>>,
        connection_id: usize,
    }

    impl ConnectionLease {
        fn new(
            connected_clients: Arc<AtomicUsize>,
            active_sockets: Arc<Mutex<HashMap<usize, StreamSocket>>>,
            connection_id: usize,
        ) -> Self {
            connected_clients.fetch_add(1, Ordering::Relaxed);
            Self {
                connected_clients,
                active_sockets,
                connection_id,
            }
        }
    }

    impl Drop for ConnectionLease {
        fn drop(&mut self) {
            if let Ok(mut sockets) = self.active_sockets.lock() {
                sockets.remove(&self.connection_id);
            }
            self.connected_clients.fetch_sub(1, Ordering::Relaxed);
        }
    }

    fn wait<F>(operation: F) -> F::Output
    where
        F: IntoFuture,
    {
        block_on(operation.into_future())
    }
}

#[cfg(not(windows))]
mod platform {
    use crate::AnyError;

    #[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
    pub struct BluetoothServerSnapshot {
        pub connected_clients: usize,
        pub active_sessions: usize,
    }

    pub struct BluetoothDesktopServer;

    impl BluetoothDesktopServer {
        pub fn start() -> Result<Self, AnyError> {
            Err("Bluetooth Desktop is currently available only on Windows".into())
        }

        pub fn snapshot(&self) -> BluetoothServerSnapshot {
            BluetoothServerSnapshot::default()
        }
    }
}

pub use platform::{BluetoothDesktopServer, BluetoothServerSnapshot};
