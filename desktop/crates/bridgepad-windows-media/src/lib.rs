//! Safe ownership boundary for BridgePad's native Windows media backend.
//!
//! COM, Direct3D and Media Foundation never cross this crate as Rust types. The
//! native implementation is private and exposes a versioned C ABI instead.

use std::time::Duration;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct H264EncoderConfig {
    pub width: u32,
    pub height: u32,
    pub frames_per_second: u32,
    pub bitrate_bits_per_second: u32,
    pub keyframe_interval_frames: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct H264AccessUnit {
    pub data: Vec<u8>,
    pub presentation_timestamp: Duration,
    pub keyframe: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct H264HardwareCapabilities {
    pub encoder_count: u32,
    pub selected_encoder_name: String,
    pub asynchronous: bool,
    pub d3d11_aware: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum WindowsMediaError {
    UnsupportedPlatform,
    Native { status: i32, detail: String },
    InvalidNativeResponse(&'static str),
}

impl std::fmt::Display for WindowsMediaError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UnsupportedPlatform => {
                formatter.write_str("the Windows media backend is only available on Windows")
            }
            Self::Native { status, detail } => {
                write!(formatter, "Windows media backend error {status}: {detail}")
            }
            Self::InvalidNativeResponse(detail) => {
                write!(
                    formatter,
                    "invalid response from Windows media backend: {detail}"
                )
            }
        }
    }
}

impl std::error::Error for WindowsMediaError {}

/// Finds an activatable hardware H.264 MFT that accepts NV12 input.
///
/// # Errors
///
/// Returns a categorized native error when COM/Media Foundation cannot start,
/// enumeration fails, or the machine has no compatible hardware encoder.
pub fn probe_h264_hardware() -> Result<H264HardwareCapabilities, WindowsMediaError> {
    platform::probe_h264_hardware()
}

#[cfg(windows)]
pub use platform::H264HardwareEncoder;

#[cfg(windows)]
mod platform {
    use super::{H264AccessUnit, H264EncoderConfig, H264HardwareCapabilities, WindowsMediaError};
    use std::ffi::{CStr, c_char, c_void};
    use std::marker::PhantomData;
    use std::ptr::NonNull;
    use std::rc::Rc;
    use std::time::Duration;
    use windows::Win32::Graphics::Direct3D11::{ID3D11Device, ID3D11Texture2D};
    use windows::core::Interface;

    const ABI_VERSION: u32 = 1;

    #[repr(C)]
    struct NativeCapabilities {
        abi_version: u32,
        hardware_encoder_count: u32,
        selected_encoder_is_async: u8,
        selected_encoder_is_d3d11_aware: u8,
        reserved: [u8; 2],
        selected_encoder_name: [c_char; 256],
    }

    #[repr(C)]
    struct NativeEncoderConfig {
        width: u32,
        height: u32,
        frames_per_second: u32,
        bitrate_bits_per_second: u32,
        keyframe_interval_frames: u32,
    }

    #[repr(C)]
    struct NativeEncodedFrame {
        data: *mut u8,
        data_length: usize,
        timestamp_100ns: i64,
        keyframe: u8,
        reserved: [u8; 7],
    }

    impl Default for NativeEncodedFrame {
        fn default() -> Self {
            Self {
                data: std::ptr::null_mut(),
                data_length: 0,
                timestamp_100ns: 0,
                keyframe: 0,
                reserved: [0; 7],
            }
        }
    }

    enum NativeEncoder {}

    impl Default for NativeCapabilities {
        fn default() -> Self {
            Self {
                abi_version: 0,
                hardware_encoder_count: 0,
                selected_encoder_is_async: 0,
                selected_encoder_is_d3d11_aware: 0,
                reserved: [0; 2],
                selected_encoder_name: [0; 256],
            }
        }
    }

    unsafe extern "C" {
        fn bp_media_probe_h264_hardware(
            capabilities: *mut NativeCapabilities,
            error_message: *mut c_char,
            error_message_capacity: usize,
        ) -> i32;
        fn bp_media_encoder_create(
            config: *const NativeEncoderConfig,
            d3d11_device: *mut c_void,
            encoder: *mut *mut NativeEncoder,
            error_message: *mut c_char,
            error_message_capacity: usize,
        ) -> i32;
        fn bp_media_encoder_encode_texture(
            encoder: *mut NativeEncoder,
            d3d11_texture_2d: *mut c_void,
            timestamp_100ns: i64,
            duration_100ns: i64,
            encoded_frame: *mut NativeEncodedFrame,
            error_message: *mut c_char,
            error_message_capacity: usize,
        ) -> i32;
        fn bp_media_encoder_set_bitrate(
            encoder: *mut NativeEncoder,
            bitrate_bits_per_second: u32,
            error_message: *mut c_char,
            error_message_capacity: usize,
        ) -> i32;
        fn bp_media_encoder_request_keyframe(
            encoder: *mut NativeEncoder,
            error_message: *mut c_char,
            error_message_capacity: usize,
        ) -> i32;
        fn bp_media_encoded_frame_release(frame: *mut NativeEncodedFrame);
        fn bp_media_encoder_destroy(encoder: *mut NativeEncoder);
    }

    fn native_error(status: i32, error: &[c_char]) -> WindowsMediaError {
        // SAFETY: Every native function receives the full buffer capacity and
        // guarantees NUL termination before returning an error.
        let detail = unsafe { CStr::from_ptr(error.as_ptr()) }
            .to_string_lossy()
            .into_owned();
        WindowsMediaError::Native { status, detail }
    }

    pub fn probe_h264_hardware() -> Result<H264HardwareCapabilities, WindowsMediaError> {
        let mut capabilities = NativeCapabilities::default();
        let mut error = [0_i8; 512];
        // SAFETY: Both pointers refer to writable, correctly sized values for
        // the duration of the synchronous C call. The native function always
        // NUL-terminates the error buffer when its capacity is non-zero.
        let status = unsafe {
            bp_media_probe_h264_hardware(&raw mut capabilities, error.as_mut_ptr(), error.len())
        };
        if status != 0 {
            return Err(native_error(status, &error));
        }
        if capabilities.abi_version != ABI_VERSION {
            return Err(WindowsMediaError::InvalidNativeResponse(
                "unsupported ABI version",
            ));
        }
        if capabilities.hardware_encoder_count == 0 {
            return Err(WindowsMediaError::InvalidNativeResponse(
                "successful probe returned no encoders",
            ));
        }
        // SAFETY: The fixed-size name field is zero-initialized by the native
        // implementation and explicitly NUL-terminated after copying.
        let selected_encoder_name =
            unsafe { CStr::from_ptr(capabilities.selected_encoder_name.as_ptr()) }
                .to_string_lossy()
                .into_owned();
        Ok(H264HardwareCapabilities {
            encoder_count: capabilities.hardware_encoder_count,
            selected_encoder_name,
            asynchronous: capabilities.selected_encoder_is_async != 0,
            d3d11_aware: capabilities.selected_encoder_is_d3d11_aware != 0,
        })
    }

    pub struct H264HardwareEncoder {
        native: NonNull<NativeEncoder>,
        // Media Foundation and D3D objects belong to their creating callback
        // thread. Prevent accidental Send/Sync at the safe Rust boundary.
        _thread_bound: PhantomData<Rc<()>>,
    }

    impl std::fmt::Debug for H264HardwareEncoder {
        fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            formatter
                .debug_struct("H264HardwareEncoder")
                .finish_non_exhaustive()
        }
    }

    impl H264HardwareEncoder {
        /// Creates a hardware-only Media Foundation encoder on a D3D11 device.
        ///
        pub fn from_d3d11_device(
            d3d11_device: &ID3D11Device,
            config: H264EncoderConfig,
        ) -> Result<Self, WindowsMediaError> {
            let native_config = NativeEncoderConfig {
                width: config.width,
                height: config.height,
                frames_per_second: config.frames_per_second,
                bitrate_bits_per_second: config.bitrate_bits_per_second,
                keyframe_interval_frames: config.keyframe_interval_frames,
            };
            let mut native = std::ptr::null_mut();
            let mut error = [0_i8; 512];
            // SAFETY: The pointer contract is delegated from this constructor;
            // all other pointers are valid local out-parameters.
            let status = unsafe {
                bp_media_encoder_create(
                    &raw const native_config,
                    d3d11_device.as_raw(),
                    &raw mut native,
                    error.as_mut_ptr(),
                    error.len(),
                )
            };
            if status != 0 {
                return Err(native_error(status, &error));
            }
            let native = NonNull::new(native).ok_or(WindowsMediaError::InvalidNativeResponse(
                "encoder creation succeeded with a null handle",
            ))?;
            Ok(Self {
                native,
                _thread_bound: PhantomData,
            })
        }

        /// Encodes one borrowed `ID3D11Texture2D` into an Annex-B access unit.
        ///
        pub fn encode_texture(
            &mut self,
            texture: &ID3D11Texture2D,
            presentation_timestamp: Duration,
            frame_duration: Duration,
        ) -> Result<Option<H264AccessUnit>, WindowsMediaError> {
            let timestamp_100ns = duration_to_100ns(presentation_timestamp)?;
            let duration_100ns = duration_to_100ns(frame_duration)?;
            let mut frame = NativeEncodedFrame::default();
            let mut error = [0_i8; 512];
            // SAFETY: The texture contract is delegated from this method. The
            // native handle and output/error buffers are owned and valid here.
            let status = unsafe {
                bp_media_encoder_encode_texture(
                    self.native.as_ptr(),
                    texture.as_raw(),
                    timestamp_100ns,
                    duration_100ns,
                    &raw mut frame,
                    error.as_mut_ptr(),
                    error.len(),
                )
            };
            if status == 7 {
                return Ok(None);
            }
            if status != 0 {
                return Err(native_error(status, &error));
            }
            if frame.data.is_null() || frame.data_length == 0 || frame.timestamp_100ns < 0 {
                // SAFETY: Release accepts zero-initialized or partially filled
                // frames and clears any allocation it owns.
                unsafe { bp_media_encoded_frame_release(&raw mut frame) };
                return Err(WindowsMediaError::InvalidNativeResponse(
                    "encoder returned an empty frame",
                ));
            }
            // SAFETY: A successful native call owns exactly data_length bytes
            // until bp_media_encoded_frame_release is invoked below.
            let data = unsafe {
                std::slice::from_raw_parts(frame.data.cast_const(), frame.data_length).to_vec()
            };
            let result = H264AccessUnit {
                data,
                presentation_timestamp: Duration::from_nanos(
                    u64::try_from(frame.timestamp_100ns)
                        .unwrap_or_default()
                        .saturating_mul(100),
                ),
                keyframe: frame.keyframe != 0,
            };
            // SAFETY: The frame came from the matching native encoder ABI and
            // has not been released previously.
            unsafe { bp_media_encoded_frame_release(&raw mut frame) };
            Ok(Some(result))
        }

        pub fn set_target_bitrate(
            &mut self,
            bits_per_second: u32,
        ) -> Result<(), WindowsMediaError> {
            let mut error = [0_i8; 512];
            // SAFETY: native is a live handle uniquely borrowed through &mut.
            let status = unsafe {
                bp_media_encoder_set_bitrate(
                    self.native.as_ptr(),
                    bits_per_second,
                    error.as_mut_ptr(),
                    error.len(),
                )
            };
            if status == 0 {
                Ok(())
            } else {
                Err(native_error(status, &error))
            }
        }

        pub fn request_keyframe(&mut self) -> Result<(), WindowsMediaError> {
            let mut error = [0_i8; 512];
            // SAFETY: native is a live handle uniquely borrowed through &mut.
            let status = unsafe {
                bp_media_encoder_request_keyframe(
                    self.native.as_ptr(),
                    error.as_mut_ptr(),
                    error.len(),
                )
            };
            if status == 0 {
                Ok(())
            } else {
                Err(native_error(status, &error))
            }
        }
    }

    impl Drop for H264HardwareEncoder {
        fn drop(&mut self) {
            // SAFETY: this handle was created by bp_media_encoder_create and is
            // released exactly once here on its owner thread.
            unsafe { bp_media_encoder_destroy(self.native.as_ptr()) };
        }
    }

    fn duration_to_100ns(value: Duration) -> Result<i64, WindowsMediaError> {
        i64::try_from(value.as_nanos() / 100).map_err(|_| {
            WindowsMediaError::InvalidNativeResponse("timestamp exceeds Media Foundation range")
        })
    }
}

#[cfg(not(windows))]
mod platform {
    use super::{H264HardwareCapabilities, WindowsMediaError};

    pub fn probe_h264_hardware() -> Result<H264HardwareCapabilities, WindowsMediaError> {
        Err(WindowsMediaError::UnsupportedPlatform)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(not(windows))]
    #[test]
    fn probe_is_explicitly_unsupported_off_windows() {
        assert_eq!(
            probe_h264_hardware(),
            Err(WindowsMediaError::UnsupportedPlatform)
        );
    }

    #[test]
    fn native_error_is_actionable() {
        let error = WindowsMediaError::Native {
            status: 5,
            detail: "no compatible encoder".to_owned(),
        };
        assert!(error.to_string().contains("no compatible encoder"));
    }
}
