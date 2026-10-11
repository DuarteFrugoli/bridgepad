use std::env;
use std::process::ExitCode;

fn main() -> ExitCode {
    let host = env::args().nth(1).unwrap_or_else(|| "127.0.0.1".into());
    let seconds = env::args()
        .nth(2)
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(1);
    match bridgepad_android_media::run_udp_aead_probe_diagnostic(&host, 39495, seconds) {
        Ok(result) => {
            println!("{result}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("UDP+AEAD probe failed: {error}");
            ExitCode::FAILURE
        }
    }
}
