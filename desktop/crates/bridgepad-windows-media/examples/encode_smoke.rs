#[cfg(windows)]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    use bridgepad_windows_media::{H264EncoderConfig, H264HardwareEncoder};
    use std::time::Duration;
    use windows::Win32::Foundation::HMODULE;
    use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL_11_0};
    use windows::Win32::Graphics::Direct3D11::{
        D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_SDK_VERSION, D3D11_SUBRESOURCE_DATA,
        D3D11_TEXTURE2D_DESC, D3D11_USAGE_DEFAULT, D3D11CreateDevice, ID3D11Device,
        ID3D11DeviceContext,
    };
    use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};

    const WIDTH: u32 = 1280;
    const HEIGHT: u32 = 720;
    const FPS: u32 = 60;
    let mut device: Option<ID3D11Device> = None;
    let mut context: Option<ID3D11DeviceContext> = None;
    // SAFETY: All output pointers reference live Options and the feature-level
    // array remains valid for this synchronous Windows API call.
    unsafe {
        D3D11CreateDevice(
            None,
            D3D_DRIVER_TYPE_HARDWARE,
            HMODULE::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            Some(&[D3D_FEATURE_LEVEL_11_0]),
            D3D11_SDK_VERSION,
            Some(&raw mut device),
            None,
            Some(&raw mut context),
        )?;
    }
    let device = device.ok_or("D3D11CreateDevice returned no device")?;
    let _context = context.ok_or("D3D11CreateDevice returned no context")?;
    let pixels = vec![0x40_u8; (WIDTH * HEIGHT * 4) as usize];
    let initial = D3D11_SUBRESOURCE_DATA {
        pSysMem: pixels.as_ptr().cast(),
        SysMemPitch: WIDTH * 4,
        SysMemSlicePitch: 0,
    };
    let description = D3D11_TEXTURE2D_DESC {
        Width: WIDTH,
        Height: HEIGHT,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC {
            Count: 1,
            Quality: 0,
        },
        Usage: D3D11_USAGE_DEFAULT,
        BindFlags: 0,
        CPUAccessFlags: 0,
        MiscFlags: 0,
    };
    let mut texture = None;
    // SAFETY: The descriptor and initial BGRA allocation cover the complete
    // 1280x720 texture for the duration of this synchronous call.
    unsafe { device.CreateTexture2D(&description, Some(&initial), Some(&raw mut texture))? };
    let texture = texture.ok_or("CreateTexture2D returned no texture")?;
    let mut encoder = H264HardwareEncoder::from_d3d11_device(
        &device,
        H264EncoderConfig {
            width: WIDTH,
            height: HEIGHT,
            frames_per_second: FPS,
            bitrate_bits_per_second: 8_000_000,
            keyframe_interval_frames: FPS * 2,
        },
    )?;
    let frame_duration = Duration::from_nanos(1_000_000_000 / u64::from(FPS));
    let mut frames = 0_u32;
    let mut keyframes = 0_u32;
    let mut bytes = 0_usize;
    let mut first_keyframe_nal_types = Vec::new();
    for index in 0..120_u32 {
        if index == 40 {
            encoder.set_target_bitrate(4_000_000)?;
        }
        if index == 60 {
            encoder.request_keyframe()?;
        }
        let timestamp = frame_duration.saturating_mul(index);
        if let Some(frame) = encoder.encode_texture(&texture, timestamp, frame_duration)? {
            frames += 1;
            keyframes += u32::from(frame.keyframe);
            bytes += frame.data.len();
            if frame.keyframe && first_keyframe_nal_types.is_empty() {
                first_keyframe_nal_types = annex_b_nal_types(&frame.data);
            }
        }
    }
    if frames < 100
        || keyframes < 2
        || bytes == 0
        || !first_keyframe_nal_types.contains(&7)
        || !first_keyframe_nal_types.contains(&8)
        || !first_keyframe_nal_types.contains(&5)
    {
        return Err(format!(
            "native encoder produced an incomplete sample: {frames} frames, {keyframes} keyframes, {bytes} bytes, keyframe NALs {first_keyframe_nal_types:?}"
        )
        .into());
    }
    println!(
        "Native Media Foundation encoded {frames} frames ({keyframes} keyframes, {bytes} bytes)"
    );
    Ok(())
}

#[cfg(windows)]
fn annex_b_nal_types(bytes: &[u8]) -> Vec<u8> {
    let mut types = Vec::new();
    let mut index = 0;
    while index + 3 < bytes.len() {
        let start_length = if bytes[index..].starts_with(&[0, 0, 0, 1]) {
            4
        } else if bytes[index..].starts_with(&[0, 0, 1]) {
            3
        } else {
            index += 1;
            continue;
        };
        let nal = index + start_length;
        if nal < bytes.len() {
            types.push(bytes[nal] & 0x1f);
        }
        index = nal.saturating_add(1);
    }
    types
}

#[cfg(not(windows))]
fn main() {
    eprintln!("This example is only available on Windows");
}
