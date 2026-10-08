use std::error::Error;

fn main() -> Result<(), Box<dyn Error>> {
    let host = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "127.0.0.1".to_owned());
    let seconds = std::env::args()
        .nth(2)
        .map_or(Ok(5_u16), |value| value.parse())?;
    let result = bridgepad_android_quic::run_probe_diagnostic(&host, 39496, seconds)
        .map_err(std::io::Error::other)?;
    println!("{result}");
    Ok(())
}
