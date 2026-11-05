//! One virtual gamepad per authenticated phone, regardless of its IP route.
//! Transport threads hold generation-bound leases; stale threads cannot update
//! or remove a replacement session.

use super::{AnyError, GamepadLease};
use crate::trust::PEER_ID_SIZE;
use bridgepad_virtual_device::{DpadDirection, GamepadReport};
use std::collections::HashMap;
use std::fmt;
use std::sync::{Arc, Mutex};

const MAX_PLAYERS: usize = 4;

fn controls_released(report: GamepadReport) -> bool {
    report.buttons == 0
        && report.dpad == DpadDirection::Neutral
        && report.left_trigger < 1_000
        && report.right_trigger < 1_000
        && [report.left_x, report.left_y, report.right_x, report.right_y]
            .into_iter()
            .all(|axis| i32::from(axis).abs() < 4_096)
}

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
    slot: usize,
    device: Arc<PlayerDevice>,
    awaiting_neutral: bool,
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
    replaced: bool,
}

#[derive(Clone)]
pub(super) struct PlayerInput {
    registry: Arc<PlayerRegistry>,
    key: PlayerKey,
    generation: u64,
}

impl PlayerInput {
    pub(super) fn update(&self, report: GamepadReport) -> Result<(), AnyError> {
        self.registry.update(self.key, self.generation, report)
    }
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

    pub(super) fn input(&self) -> PlayerInput {
        PlayerInput {
            registry: Arc::clone(&self.registry),
            key: self.key,
            generation: self.generation,
        }
    }

    pub(super) fn is_active(&self) -> bool {
        self.registry.is_active(self.key, self.generation)
    }

    pub(super) fn update(&self, report: GamepadReport) -> Result<(), AnyError> {
        self.registry.update(self.key, self.generation, report)
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
        let previous_slot = state.players.get(&key).map(|entry| entry.slot);
        let slot = previous_slot.unwrap_or_else(|| {
            (0..MAX_PLAYERS)
                .find(|slot| state.players.values().all(|entry| entry.slot != *slot))
                .expect("a free controller slot exists")
        });
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
                slot,
                device,
                awaiting_neutral: false,
            },
        );
        Ok(PlayerLease {
            registry: Arc::clone(self),
            key,
            generation,
            replaced,
        })
    }

    fn update(
        &self,
        key: PlayerKey,
        generation: u64,
        report: GamepadReport,
    ) -> Result<(), AnyError> {
        // Keep the routing lock through the device update so a swap cannot race
        // with a report already on its way to the former destination.
        let mut state = self
            .state
            .lock()
            .map_err(|_| "player registry is unavailable")?;
        let entry = state
            .players
            .get_mut(&key)
            .ok_or("player session was replaced")?;
        if entry.generation != generation {
            return Err("player session was replaced".into());
        }
        if entry.awaiting_neutral {
            if !controls_released(report) {
                return Ok(());
            }
            entry.awaiting_neutral = false;
            // Do not forward small physical-stick noise as the first report.
            return entry.device.update(GamepadReport::default());
        }
        entry.device.update(report)
    }

    fn is_active(&self, key: PlayerKey, generation: u64) -> bool {
        self.state.lock().is_ok_and(|state| {
            state
                .players
                .get(&key)
                .is_some_and(|entry| entry.generation == generation && entry.device.is_active())
        })
    }

    pub(super) fn slots(&self) -> Vec<(usize, PlayerKey)> {
        self.state.lock().map_or_else(
            |_| Vec::new(),
            |state| {
                let mut slots: Vec<_> = state
                    .players
                    .iter()
                    .map(|(&key, entry)| (entry.slot, key))
                    .collect();
                slots.sort_by_key(|(slot, _)| *slot);
                slots
            },
        )
    }

    pub(super) fn swap_slots(&self, first: usize, second: usize) -> Result<(), AnyError> {
        if first >= MAX_PLAYERS || second >= MAX_PLAYERS || first == second {
            return Err("select two different occupied BridgePad positions from 1 to 4".into());
        }
        let mut state = self
            .state
            .lock()
            .map_err(|_| "player registry is unavailable")?;
        let first_key = state
            .players
            .iter()
            .find(|(_, entry)| entry.slot == first)
            .map(|(&key, _)| key)
            .ok_or("the first position has no controller")?;
        let second_key = state
            .players
            .iter()
            .find(|(_, entry)| entry.slot == second)
            .map(|(&key, _)| key)
            .ok_or("the second position has no controller")?;
        let first_device = Arc::clone(&state.players[&first_key].device);
        let second_device = Arc::clone(&state.players[&second_key].device);
        first_device.update(GamepadReport::default())?;
        second_device.update(GamepadReport::default())?;
        let first_entry = state
            .players
            .get_mut(&first_key)
            .expect("slot owner exists");
        first_entry.slot = second;
        first_entry.device = second_device;
        first_entry.awaiting_neutral = true;
        let second_entry = state
            .players
            .get_mut(&second_key)
            .expect("slot owner exists");
        second_entry.slot = first;
        second_entry.device = first_device;
        second_entry.awaiting_neutral = true;
        Ok(())
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
        let old_input = first.input();
        let replacement = registry
            .claim(first_key, || connect(&updates, &shutdowns))
            .unwrap();
        assert!(replacement.replaced_previous());
        assert!(!first.is_active());
        assert!(old_input.update(GamepadReport::default()).is_err());
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

    struct ReportGamepad(Arc<Mutex<Vec<GamepadReport>>>);

    impl VirtualGamepadDevice for ReportGamepad {
        fn update(&mut self, report: GamepadReport) -> Result<(), VirtualDeviceError> {
            self.0.lock().unwrap().push(report);
            Ok(())
        }

        fn shutdown(&mut self) -> Result<(), VirtualDeviceError> {
            Ok(())
        }
    }

    #[test]
    fn swapping_routes_reports_without_recreating_devices_and_clears_held_inputs() {
        let registry = Arc::new(PlayerRegistry::default());
        let first_reports = Arc::new(Mutex::new(Vec::new()));
        let second_reports = Arc::new(Mutex::new(Vec::new()));
        let first_key = PlayerKey::Authenticated([10; PEER_ID_SIZE]);
        let second_key = PlayerKey::Authenticated([11; PEER_ID_SIZE]);
        let first = registry
            .claim(first_key, || {
                GamepadLease::new(Box::new(ReportGamepad(first_reports.clone())))
            })
            .unwrap();
        let second = registry
            .claim(second_key, || {
                GamepadLease::new(Box::new(ReportGamepad(second_reports.clone())))
            })
            .unwrap();
        let held = GamepadReport {
            buttons: 1,
            ..GamepadReport::default()
        };
        first.update(held).unwrap();
        second.update(held).unwrap();
        let first_before = first_reports.lock().unwrap().len();
        let second_before = second_reports.lock().unwrap().len();
        registry.swap_slots(0, 1).unwrap();
        assert_eq!(registry.slots(), vec![(0, second_key), (1, first_key)]);
        assert_eq!(
            first_reports.lock().unwrap().last(),
            Some(&GamepadReport::default())
        );
        assert_eq!(
            second_reports.lock().unwrap().last(),
            Some(&GamepadReport::default())
        );
        assert_eq!(first_reports.lock().unwrap().len(), first_before + 1);
        assert_eq!(second_reports.lock().unwrap().len(), second_before + 1);
        // A queued pressed snapshot cannot reactivate a device after the swap.
        first.update(held).unwrap();
        second.update(held).unwrap();
        assert_eq!(first_reports.lock().unwrap().len(), first_before + 1);
        assert_eq!(second_reports.lock().unwrap().len(), second_before + 1);
        first
            .update(GamepadReport {
                left_x: 250,
                ..GamepadReport::default()
            })
            .unwrap();
        second.update(GamepadReport::default()).unwrap();
        first.update(held).unwrap();
        assert_eq!(second_reports.lock().unwrap().last(), Some(&held));
        assert_eq!(
            first_reports.lock().unwrap().last(),
            Some(&GamepadReport::default())
        );
        drop(second);
        assert_eq!(registry.slots(), vec![(1, first_key)]);
        assert!(first.is_active());
    }

    #[test]
    fn empty_or_invalid_swap_does_not_change_routing() {
        let registry = Arc::new(PlayerRegistry::default());
        let updates = Arc::new(AtomicUsize::new(0));
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let key = PlayerKey::Authenticated([12; PEER_ID_SIZE]);
        let _first = registry
            .claim(key, || connect(&updates, &shutdowns))
            .unwrap();
        assert!(registry.swap_slots(0, 1).is_err());
        assert!(registry.swap_slots(0, 4).is_err());
        assert_eq!(registry.slots(), vec![(0, key)]);
    }

    #[test]
    fn reconnect_after_swap_preserves_logical_position_and_rejects_old_reports() {
        let registry = Arc::new(PlayerRegistry::default());
        let updates = Arc::new(AtomicUsize::new(0));
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let first_key = PlayerKey::Authenticated([13; PEER_ID_SIZE]);
        let second_key = PlayerKey::Authenticated([14; PEER_ID_SIZE]);
        let first = registry
            .claim(first_key, || connect(&updates, &shutdowns))
            .unwrap();
        let second = registry
            .claim(second_key, || connect(&updates, &shutdowns))
            .unwrap();
        registry.swap_slots(0, 1).unwrap();
        let old_input = first.input();
        let replacement = registry
            .claim(first_key, || connect(&updates, &shutdowns))
            .unwrap();
        assert_eq!(registry.slots(), vec![(0, second_key), (1, first_key)]);
        assert!(old_input.update(GamepadReport::default()).is_err());
        assert!(second.is_active());
        drop(first);
        assert!(replacement.is_active());
    }
}
