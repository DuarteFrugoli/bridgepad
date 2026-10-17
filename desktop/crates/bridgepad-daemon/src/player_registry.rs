//! One virtual gamepad per authenticated phone, regardless of its IP route.
//! Transport threads hold generation-bound leases; stale threads cannot update
//! or remove a replacement session.

use super::{AnyError, GamepadLease};
use crate::trust::PEER_ID_SIZE;
use bridgepad_virtual_device::GamepadReport;
use std::collections::HashMap;
use std::fmt;
use std::sync::{Arc, Mutex};

const MAX_PLAYERS: usize = 4;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub(super) enum PlayerKey {
    Authenticated([u8; PEER_ID_SIZE]),
    Unpaired(usize),
}

#[derive(Default)]
pub(super) struct PlayerRegistry {
    state: Mutex<RegistryState>,
}

#[derive(Default)]
struct RegistryState {
    next_generation: u64,
    players: HashMap<PlayerKey, PlayerEntry>,
}

struct PlayerEntry {
    generation: u64,
    device: Arc<PlayerDevice>,
}

pub(super) struct PlayerDevice {
    gamepad: Mutex<Option<GamepadLease>>,
}

impl PlayerDevice {
    pub(super) fn update(&self, report: GamepadReport) -> Result<(), AnyError> {
        let mut guard = self
            .gamepad
            .lock()
            .map_err(|_| "player gamepad lock is poisoned")?;
        guard
            .as_mut()
            .ok_or("player session was replaced")?
            .update(report)
    }

    pub(super) fn is_active(&self) -> bool {
        self.gamepad.lock().is_ok_and(|guard| guard.is_some())
    }

    fn revoke(&self) {
        let previous = self.gamepad.lock().ok().and_then(|mut guard| guard.take());
        drop(previous);
    }
}

pub(super) struct PlayerLease {
    registry: Arc<PlayerRegistry>,
    key: PlayerKey,
    generation: u64,
    device: Arc<PlayerDevice>,
    replaced: bool,
}

#[derive(Debug)]
pub(super) enum ClaimError {
    NoSlot,
    Backend(AnyError),
    RegistryPoisoned,
}

impl fmt::Display for ClaimError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NoSlot => write!(formatter, "no BridgePad controller slot is available"),
            Self::Backend(error) => write!(formatter, "virtual gamepad unavailable: {error}"),
            Self::RegistryPoisoned => write!(formatter, "player registry is unavailable"),
        }
    }
}

impl std::error::Error for ClaimError {}

impl PlayerLease {
    pub(super) fn replaced_previous(&self) -> bool {
        self.replaced
    }

    pub(super) fn device(&self) -> Arc<PlayerDevice> {
        Arc::clone(&self.device)
    }

    pub(super) fn is_active(&self) -> bool {
        self.device.is_active()
    }

    pub(super) fn update(&self, report: GamepadReport) -> Result<(), AnyError> {
        self.device.update(report)
    }
}

impl Drop for PlayerLease {
    fn drop(&mut self) {
        self.registry.release(self.key, self.generation);
    }
}

impl PlayerRegistry {
    pub(super) fn claim<F>(
        self: &Arc<Self>,
        key: PlayerKey,
        create_gamepad: F,
    ) -> Result<PlayerLease, ClaimError>
    where
        F: FnOnce() -> Result<GamepadLease, AnyError>,
    {
        let mut state = self
            .state
            .lock()
            .map_err(|_| ClaimError::RegistryPoisoned)?;
        if !state.players.contains_key(&key) && state.players.len() >= MAX_PLAYERS {
            return Err(ClaimError::NoSlot);
        }
        let replaced = if let Some(previous) = state.players.remove(&key) {
            previous.device.revoke();
            true
        } else {
            false
        };
        let gamepad = create_gamepad().map_err(ClaimError::Backend)?;
        state.next_generation = state.next_generation.wrapping_add(1).max(1);
        let generation = state.next_generation;
        let device = Arc::new(PlayerDevice {
            gamepad: Mutex::new(Some(gamepad)),
        });
        state.players.insert(
            key,
            PlayerEntry {
                generation,
                device: Arc::clone(&device),
            },
        );
        Ok(PlayerLease {
            registry: Arc::clone(self),
            key,
            generation,
            device,
            replaced,
        })
    }

    pub(super) fn count(&self) -> usize {
        self.state.lock().map_or(0, |state| state.players.len())
    }

    pub(super) fn revoke(&self, key: PlayerKey) {
        if let Ok(mut state) = self.state.lock()
            && let Some(entry) = state.players.remove(&key)
        {
            entry.device.revoke();
        }
    }

    fn release(&self, key: PlayerKey, generation: u64) {
        if let Ok(mut state) = self.state.lock()
            && state
                .players
                .get(&key)
                .is_some_and(|entry| entry.generation == generation)
            && let Some(entry) = state.players.remove(&key)
        {
            entry.device.revoke();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bridgepad_virtual_device::{VirtualDeviceError, VirtualGamepadDevice};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::thread;

    struct RecordingGamepad {
        updates: Arc<AtomicUsize>,
        shutdowns: Arc<AtomicUsize>,
    }

    impl VirtualGamepadDevice for RecordingGamepad {
        fn update(&mut self, _report: GamepadReport) -> Result<(), VirtualDeviceError> {
            self.updates.fetch_add(1, Ordering::Relaxed);
            Ok(())
        }

        fn shutdown(&mut self) -> Result<(), VirtualDeviceError> {
            self.shutdowns.fetch_add(1, Ordering::Relaxed);
            Ok(())
        }
    }

    fn connect(
        updates: &Arc<AtomicUsize>,
        shutdowns: &Arc<AtomicUsize>,
    ) -> Result<GamepadLease, AnyError> {
        GamepadLease::new(Box::new(RecordingGamepad {
            updates: Arc::clone(updates),
            shutdowns: Arc::clone(shutdowns),
        }))
    }

    #[test]
    fn distinct_phones_are_isolated_and_old_generation_cannot_remove_replacement() {
        let registry = Arc::new(PlayerRegistry::default());
        let updates = Arc::new(AtomicUsize::new(0));
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let first_key = PlayerKey::Authenticated([1; PEER_ID_SIZE]);
        let second_key = PlayerKey::Authenticated([2; PEER_ID_SIZE]);
        let first = registry
            .claim(first_key, || connect(&updates, &shutdowns))
            .unwrap();
        let second = registry
            .claim(second_key, || connect(&updates, &shutdowns))
            .unwrap();
        assert_eq!(registry.count(), 2);
        let old_device = first.device();
        let replacement = registry
            .claim(first_key, || connect(&updates, &shutdowns))
            .unwrap();
        assert!(replacement.replaced_previous());
        assert!(!first.is_active());
        assert!(old_device.update(GamepadReport::default()).is_err());
        assert_eq!(shutdowns.load(Ordering::Relaxed), 1);
        drop(first);
        assert_eq!(registry.count(), 2);
        assert!(replacement.is_active());
        drop(second);
        assert_eq!(registry.count(), 1);
        assert!(replacement.is_active());
        drop(replacement);
        assert_eq!(registry.count(), 0);
        assert_eq!(shutdowns.load(Ordering::Relaxed), 3);
    }

    #[test]
    fn concurrent_claims_for_same_phone_keep_only_one_live_device() {
        let registry = Arc::new(PlayerRegistry::default());
        let updates = Arc::new(AtomicUsize::new(0));
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let key = PlayerKey::Authenticated([3; PEER_ID_SIZE]);
        let mut workers = Vec::new();
        for _ in 0..8 {
            let registry = Arc::clone(&registry);
            let updates = Arc::clone(&updates);
            let shutdowns = Arc::clone(&shutdowns);
            workers.push(thread::spawn(move || {
                registry
                    .claim(key, || connect(&updates, &shutdowns))
                    .unwrap()
            }));
        }
        let leases: Vec<_> = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .collect();
        assert_eq!(registry.count(), 1);
        assert_eq!(leases.iter().filter(|lease| lease.is_active()).count(), 1);
        drop(leases);
        assert_eq!(registry.count(), 0);
        assert_eq!(shutdowns.load(Ordering::Relaxed), 8);
    }

    #[test]
    fn fifth_distinct_phone_is_rejected_without_stealing_another_slot() {
        let registry = Arc::new(PlayerRegistry::default());
        let updates = Arc::new(AtomicUsize::new(0));
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let leases: Vec<_> = (0..4)
            .map(|index| {
                registry
                    .claim(PlayerKey::Authenticated([index; PEER_ID_SIZE]), || {
                        connect(&updates, &shutdowns)
                    })
                    .unwrap()
            })
            .collect();
        assert!(
            registry
                .claim(PlayerKey::Authenticated([4; PEER_ID_SIZE]), || connect(
                    &updates, &shutdowns
                ),)
                .is_err()
        );
        assert_eq!(registry.count(), 4);
        assert!(leases.iter().all(PlayerLease::is_active));
    }

    #[test]
    fn replacement_backend_failure_revokes_only_that_phone() {
        let registry = Arc::new(PlayerRegistry::default());
        let updates = Arc::new(AtomicUsize::new(0));
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let first_key = PlayerKey::Authenticated([5; PEER_ID_SIZE]);
        let second_key = PlayerKey::Authenticated([6; PEER_ID_SIZE]);
        let first = registry
            .claim(first_key, || connect(&updates, &shutdowns))
            .unwrap();
        let second = registry
            .claim(second_key, || connect(&updates, &shutdowns))
            .unwrap();
        assert!(matches!(
            registry.claim(first_key, || Err("simulated backend failure".into())),
            Err(ClaimError::Backend(_))
        ));
        assert!(!first.is_active());
        assert!(second.is_active());
        assert_eq!(registry.count(), 1);
        assert_eq!(shutdowns.load(Ordering::Relaxed), 1);
    }
}
