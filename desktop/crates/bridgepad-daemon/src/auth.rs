use ring::{hmac, pbkdf2, rand};
use std::io;
use std::num::NonZeroU32;
use std::time::{Duration, Instant};

use crate::trust::PEER_ID_SIZE;

pub const NONCE_SIZE: usize = 32;
pub const SALT_SIZE: usize = 16;
pub const PROOF_SIZE: usize = 32;
pub const PBKDF2_ITERATIONS: u32 = 210_000;
const PAIRING_VALIDITY: Duration = Duration::from_secs(10 * 60);
const MAX_FAILURES: u8 = 5;

pub struct PairingWindow {
    code: String,
    expires_at: Instant,
    failures: u8,
}

impl PairingWindow {
    pub fn new() -> io::Result<Self> {
        let mut window = Self {
            code: String::new(),
            expires_at: Instant::now(),
            failures: 0,
        };
        window.rotate()?;
        Ok(window)
    }

    pub fn code(&mut self) -> io::Result<String> {
        if Instant::now() >= self.expires_at {
            self.rotate()?;
        }
        Ok(self.code.clone())
    }

    pub fn formatted_code(&mut self) -> io::Result<String> {
        let code = self.code()?;
        Ok(format!("{}-{}-{}", &code[..4], &code[4..8], &code[8..]))
    }

    pub fn expires_in_seconds(&self) -> u16 {
        self.expires_at
            .saturating_duration_since(Instant::now())
            .as_secs()
            .min(u64::from(u16::MAX)) as u16
    }

    pub fn record_failure(&mut self) -> io::Result<()> {
        self.failures = self.failures.saturating_add(1);
        if self.failures >= MAX_FAILURES {
            self.rotate()?;
        }
        Ok(())
    }

    pub fn record_success(&mut self) -> io::Result<()> {
        self.rotate()
    }

    pub fn rotate(&mut self) -> io::Result<()> {
        let mut random = [0_u8; 8];
        secure_fill(&mut random)?;
        let value = u64::from_be_bytes(random) % 1_000_000_000_000;
        self.code = format!("{value:012}");
        self.expires_at = Instant::now() + PAIRING_VALIDITY;
        self.failures = 0;
        Ok(())
    }
}

pub fn secure_array<const SIZE: usize>() -> io::Result<[u8; SIZE]> {
    let mut output = [0_u8; SIZE];
    secure_fill(&mut output)?;
    Ok(output)
}

pub fn derive_pairing_key(code: &str, salt: &[u8; SALT_SIZE]) -> [u8; PROOF_SIZE] {
    let mut output = [0_u8; PROOF_SIZE];
    pbkdf2::derive(
        pbkdf2::PBKDF2_HMAC_SHA256,
        NonZeroU32::new(PBKDF2_ITERATIONS).expect("iterations are non-zero"),
        salt,
        code.as_bytes(),
        &mut output,
    );
    output
}

pub fn verify_proof(key: &[u8], transcript: &[u8], actual: &[u8]) -> bool {
    hmac::verify(&hmac::Key::new(hmac::HMAC_SHA256, key), transcript, actual).is_ok()
}

pub fn create_proof(key: &[u8], transcript: &[u8]) -> [u8; PROOF_SIZE] {
    let tag = hmac::sign(&hmac::Key::new(hmac::HMAC_SHA256, key), transcript);
    tag.as_ref().try_into().expect("SHA-256 tag has fixed size")
}

pub fn role_transcript(context: &[u8], transcript: &[u8]) -> Vec<u8> {
    let mut output = Vec::with_capacity(context.len() + transcript.len());
    output.extend_from_slice(context);
    output.extend_from_slice(transcript);
    output
}

pub fn pairing_transcript(
    client_peer_id: &[u8; PEER_ID_SIZE],
    server_peer_id: &[u8; PEER_ID_SIZE],
    client_nonce: &[u8; NONCE_SIZE],
    server_nonce: &[u8; NONCE_SIZE],
    fingerprint: &[u8; 32],
) -> Vec<u8> {
    transcript(
        b"bridgepad-pair-v1",
        client_peer_id,
        server_peer_id,
        client_nonce,
        server_nonce,
        fingerprint,
    )
}

pub fn authentication_transcript(
    client_peer_id: &[u8; PEER_ID_SIZE],
    server_peer_id: &[u8; PEER_ID_SIZE],
    client_nonce: &[u8; NONCE_SIZE],
    server_nonce: &[u8; NONCE_SIZE],
    fingerprint: &[u8; 32],
) -> Vec<u8> {
    transcript(
        b"bridgepad-auth-v1",
        client_peer_id,
        server_peer_id,
        client_nonce,
        server_nonce,
        fingerprint,
    )
}

fn transcript(
    context: &[u8],
    client_peer_id: &[u8; PEER_ID_SIZE],
    server_peer_id: &[u8; PEER_ID_SIZE],
    client_nonce: &[u8; NONCE_SIZE],
    server_nonce: &[u8; NONCE_SIZE],
    fingerprint: &[u8; 32],
) -> Vec<u8> {
    let mut output =
        Vec::with_capacity(context.len() + PEER_ID_SIZE * 2 + NONCE_SIZE * 2 + fingerprint.len());
    output.extend_from_slice(context);
    output.extend_from_slice(client_peer_id);
    output.extend_from_slice(server_peer_id);
    output.extend_from_slice(client_nonce);
    output.extend_from_slice(server_nonce);
    output.extend_from_slice(fingerprint);
    output
}

fn secure_fill(output: &mut [u8]) -> io::Result<()> {
    use ring::rand::SecureRandom;
    rand::SystemRandom::new()
        .fill(output)
        .map_err(|_| io::Error::other("secure random generator failed"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pbkdf2_uses_the_protocol_sha256_parameters() {
        let salt: [u8; SALT_SIZE] = *b"saltsaltsaltsalt";
        let mut output = [0_u8; PROOF_SIZE];
        pbkdf2::derive(
            pbkdf2::PBKDF2_HMAC_SHA256,
            NonZeroU32::new(1).unwrap(),
            &salt,
            b"password",
            &mut output,
        );
        assert_eq!(
            output,
            [
                0xb1, 0x3d, 0x66, 0x97, 0xe9, 0x9c, 0xd6, 0xd1, 0x74, 0x5d, 0xa0, 0x97, 0xee, 0x03,
                0xe4, 0xbe, 0x50, 0x13, 0x41, 0xe7, 0x6f, 0xe9, 0x16, 0x1a, 0x78, 0x8d, 0xe3, 0xd4,
                0xcd, 0x0b, 0xe2, 0x19,
            ]
        );
    }

    #[test]
    fn client_and_server_proofs_use_different_roles() {
        let key = [7_u8; PROOF_SIZE];
        let transcript = [9_u8; 128];
        let client = create_proof(
            &key,
            &role_transcript(b"bridgepad-pair-client-v1", &transcript),
        );
        let server = create_proof(
            &key,
            &role_transcript(b"bridgepad-pair-server-v1", &transcript),
        );
        assert_ne!(client, server);
    }
}
