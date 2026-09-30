fn main() -> Result<(), Box<dyn std::error::Error>> {
    let capabilities = bridgepad_windows_media::probe_h264_hardware()?;
    println!("BridgePad native Media Foundation probe");
    println!("Hardware H.264 encoders: {}", capabilities.encoder_count);
    println!("Selected encoder: {}", capabilities.selected_encoder_name);
    println!("Asynchronous MFT: {}", capabilities.asynchronous);
    println!("D3D11-aware MFT: {}", capabilities.d3d11_aware);
    Ok(())
}
