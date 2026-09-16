use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

pub const PEER_ID_SIZE: usize = 16;
pub const SHARED_SECRET_SIZE: usize = 32;

#[derive(Clone, Debug)]
pub struct TrustedPeer {
    pub id: [u8; PEER_ID_SIZE],
    pub name: String,
    pub secret: [u8; SHARED_SECRET_SIZE],
}

pub struct TrustStore {
    path: PathBuf,
    peers: HashMap<[u8; PEER_ID_SIZE], TrustedPeer>,
}

impl TrustStore {
    pub fn load(identity_directory: &Path) -> io::Result<Self> {
        let path = identity_directory.join("trusted-peers.db");
        let mut peers = HashMap::new();
        match fs::read_to_string(&path) {
            Ok(contents) => {
                for (index, line) in contents.lines().enumerate() {
                    if line.is_empty() {
                        continue;
                    }
                    let mut fields = line.split('\t');
                    let id = decode_array::<PEER_ID_SIZE>(fields.next().unwrap_or_default())
                        .map_err(|message| invalid_store(index, message))?;
                    let name_bytes = decode_hex(fields.next().unwrap_or_default())
                        .map_err(|message| invalid_store(index, message))?;
                    let name = String::from_utf8(name_bytes)
                        .map_err(|_| invalid_store(index, "peer name is not UTF-8"))?;
                    let secret =
                        decode_array::<SHARED_SECRET_SIZE>(fields.next().unwrap_or_default())
                            .map_err(|message| invalid_store(index, message))?;
                    if fields.next().is_some() {
                        return Err(invalid_store(index, "unexpected trailing field"));
                    }
                    peers.insert(id, TrustedPeer { id, name, secret });
                }
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
        Ok(Self { path, peers })
    }

    pub fn get(&self, id: &[u8; PEER_ID_SIZE]) -> Option<&TrustedPeer> {
        self.peers.get(id)
    }

    pub fn all(&self) -> impl Iterator<Item = &TrustedPeer> {
        self.peers.values()
    }

    pub fn insert(&mut self, peer: TrustedPeer) -> io::Result<()> {
        self.peers.insert(peer.id, peer);
        self.save()
    }

    pub fn forget(&mut self, id: &[u8; PEER_ID_SIZE]) -> io::Result<bool> {
        let removed = self.peers.remove(id).is_some();
        if removed {
            self.save()?;
        }
        Ok(removed)
    }

    pub fn clear(&mut self) -> io::Result<()> {
        self.peers.clear();
        self.save()
    }

    fn save(&self) -> io::Result<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut options = OpenOptions::new();
        options.create(true).truncate(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&self.path)?;
        let mut peers: Vec<_> = self.peers.values().collect();
        peers.sort_by_key(|peer| encode_hex(&peer.id));
        for peer in peers {
            writeln!(
                file,
                "{}\t{}\t{}",
                encode_hex(&peer.id),
                encode_hex(peer.name.as_bytes()),
                encode_hex(&peer.secret),
            )?;
        }
        file.sync_all()
    }
}

pub fn encode_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn decode_array<const SIZE: usize>(value: &str) -> Result<[u8; SIZE], &'static str> {
    let bytes = decode_hex(value)?;
    bytes.try_into().map_err(|_| "hex field has the wrong size")
}

fn decode_hex(value: &str) -> Result<Vec<u8>, &'static str> {
    if !value.len().is_multiple_of(2) {
        return Err("hex field has an odd length");
    }
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let pair = std::str::from_utf8(pair).map_err(|_| "hex field is not ASCII")?;
            u8::from_str_radix(pair, 16).map_err(|_| "hex field contains an invalid digit")
        })
        .collect()
}

fn invalid_store(index: usize, message: &str) -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        format!("invalid trusted peer at line {}: {message}", index + 1),
    )
}
