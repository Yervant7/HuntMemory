/*
 * HuntMemory - Process Memory Editor & Scanner for Android
 * Copyright (C) 2026 Yervant7
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::kpm;
use crate::types::*;

/// Writes a typed value into a virtual memory address of the target process via KPM.
pub fn write_value(
    pid: u32,
    address: u64,
    value: &str,
    value_type: ValueType,
) -> Result<(), String> {
    let bytes = value_str_to_bytes(value, value_type)?;
    kpm::write_memory(pid, address, &bytes)
        .map_err(|e| format!("KPM write failed for 0x{address:x}: {e}"))
}

/// Helper to generate a pseudorandom 32-bit key
fn generate_random_key32() -> u32 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0x12345678);
    nanos ^ 0xA5A55A5A
}

/// Helper to generate a pseudorandom 64-bit key
fn generate_random_key64() -> u64 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x123456789ABCDEF0);
    nanos ^ 0xA5A55A5A5A5AA5A5
}

/// Writes an obfuscated value (e.g. ACTk XOR-key pair) to memory
pub fn write_obscured(
    pid: u32,
    address: u64,
    value_str: &str,
    obscured_type: ObscuredType,
) -> Result<(), String> {
    let s = value_str.trim();
    match obscured_type {
        ObscuredType::ObscuredInt => {
            let target: i32 =
                if let Some(hex) = s.strip_prefix("0x").or_else(|| s.strip_prefix("0X")) {
                    i32::from_str_radix(hex, 16).map_err(|e| format!("Invalid hex: {e}"))?
                } else {
                    s.parse().map_err(|e| format!("Invalid int: {e}"))?
                };

            // Attempt to preserve existing cryptoKey if readable, or generate a fresh key
            let mut buf = [0u8; 8];
            let key = if kpm::read_memory(pid, address, &mut buf).is_ok() {
                u32::from_le_bytes(buf[..4].try_into().unwrap())
            } else {
                generate_random_key32()
            };

            let hidden = (target as u32) ^ key;
            let mut out = [0u8; 8];
            out[..4].copy_from_slice(&key.to_le_bytes());
            out[4..8].copy_from_slice(&hidden.to_le_bytes());

            kpm::write_memory(pid, address, &out)
                .map_err(|e| format!("Write ObscuredInt failed at 0x{address:x}: {e}"))
        }
        ObscuredType::ObscuredFloat => {
            let target: f32 = s.parse().map_err(|e| format!("Invalid float: {e}"))?;
            let mut buf = [0u8; 8];
            let key = if kpm::read_memory(pid, address, &mut buf).is_ok() {
                u32::from_le_bytes(buf[..4].try_into().unwrap())
            } else {
                generate_random_key32()
            };

            let hidden = target.to_bits() ^ key;
            let mut out = [0u8; 8];
            out[..4].copy_from_slice(&key.to_le_bytes());
            out[4..8].copy_from_slice(&hidden.to_le_bytes());

            kpm::write_memory(pid, address, &out)
                .map_err(|e| format!("Write ObscuredFloat failed at 0x{address:x}: {e}"))
        }
        ObscuredType::ObscuredDouble => {
            let target: f64 = s.parse().map_err(|e| format!("Invalid double: {e}"))?;
            let mut buf = [0u8; 16];
            let key = if kpm::read_memory(pid, address, &mut buf).is_ok() {
                u64::from_le_bytes(buf[..8].try_into().unwrap())
            } else {
                generate_random_key64()
            };

            let hidden = target.to_bits() ^ key;
            let mut out = [0u8; 16];
            out[..8].copy_from_slice(&key.to_le_bytes());
            out[8..16].copy_from_slice(&hidden.to_le_bytes());

            kpm::write_memory(pid, address, &out)
                .map_err(|e| format!("Write ObscuredDouble failed at 0x{address:x}: {e}"))
        }
        ObscuredType::ObscuredLong => {
            let target: i64 =
                if let Some(hex) = s.strip_prefix("0x").or_else(|| s.strip_prefix("0X")) {
                    i64::from_str_radix(hex, 16).map_err(|e| format!("Invalid hex: {e}"))?
                } else {
                    s.parse().map_err(|e| format!("Invalid long: {e}"))?
                };

            let mut buf = [0u8; 16];
            let key = if kpm::read_memory(pid, address, &mut buf).is_ok() {
                u64::from_le_bytes(buf[..8].try_into().unwrap())
            } else {
                generate_random_key64()
            };

            let hidden = (target as u64) ^ key;
            let mut out = [0u8; 16];
            out[..8].copy_from_slice(&key.to_le_bytes());
            out[8..16].copy_from_slice(&hidden.to_le_bytes());

            kpm::write_memory(pid, address, &out)
                .map_err(|e| format!("Write ObscuredLong failed at 0x{address:x}: {e}"))
        }
    }
}

/// Writes a BigDouble scientific structure (mantissa & exponent) to memory
pub fn write_big_double(pid: u32, address: u64, value_str: &str) -> Result<(), String> {
    let target = BigDouble::parse(value_str)?;

    let mut buf = [0u8; 16];
    let is_exp32 = if kpm::read_memory(pid, address, &mut buf).is_ok() {
        let exp64 = i64::from_le_bytes(buf[8..16].try_into().unwrap());
        let exp32 = i32::from_le_bytes(buf[8..12].try_into().unwrap()) as i64;
        exp64 == 0 && exp32 != 0
    } else {
        false
    };

    if is_exp32 {
        let mut out = [0u8; 12];
        out[..8].copy_from_slice(&target.mantissa.to_le_bytes());
        out[8..12].copy_from_slice(&(target.exponent as i32).to_le_bytes());
        kpm::write_memory(pid, address, &out)
            .map_err(|e| format!("Write BigDouble (32-bit exp) failed at 0x{address:x}: {e}"))
    } else {
        let mut out = [0u8; 16];
        out[..8].copy_from_slice(&target.mantissa.to_le_bytes());
        out[8..16].copy_from_slice(&target.exponent.to_le_bytes());
        kpm::write_memory(pid, address, &out)
            .map_err(|e| format!("Write BigDouble failed at 0x{address:x}: {e}"))
    }
}

/// Writes multiple values in a batch. Returns the count of successful writes.
pub fn batch_write(pid: u32, writes: &[(u64, String, ValueType)]) -> Result<u32, String> {
    if writes.is_empty() {
        return Ok(0);
    }

    let mut payloads = Vec::with_capacity(writes.len());
    for (addr, val_str, vtype) in writes {
        if let Ok(bytes) = value_str_to_bytes(val_str, *vtype) {
            payloads.push((*addr, bytes));
        }
    }

    if payloads.is_empty() {
        return Ok(0);
    }

    let batch_refs: Vec<(u64, &[u8])> = payloads
        .iter()
        .map(|(addr, bytes)| (*addr, bytes.as_slice()))
        .collect();

    match kpm::write_batch(pid, &batch_refs) {
        Ok(count) => Ok(count as u32),
        Err(_) => {
            // Fallback to individual writes if batch fails
            let mut success_count = 0u32;
            for (addr, bytes) in &payloads {
                if kpm::write_memory(pid, *addr, bytes).is_ok() {
                    success_count += 1;
                }
            }
            Ok(success_count)
        }
    }
}

#[derive(Clone)]
struct FreezeItem {
    pid: u32,
    address: u64,
    bytes: Vec<u8>,
}

struct FreezeState {
    items: HashMap<u64, FreezeItem>,
    running: bool,
    interval_ms: u64,
}

pub struct FreezeEngine {
    state: Arc<(Mutex<FreezeState>, Condvar)>,
}

impl FreezeEngine {
    fn new() -> Self {
        let state = Arc::new((
            Mutex::new(FreezeState {
                items: HashMap::new(),
                running: true,
                interval_ms: 100,
            }),
            Condvar::new(),
        ));

        let state_clone = state.clone();
        let _ = thread::Builder::new()
            .name("hmem-freeze".into())
            .spawn(move || {
                Self::worker_loop(state_clone);
            });

        Self { state }
    }

    fn worker_loop(state: Arc<(Mutex<FreezeState>, Condvar)>) {
        let (lock, cvar) = &*state;
        loop {
            let (items_to_write, interval) = {
                let mut s = lock.lock().unwrap();
                while s.running && s.items.is_empty() {
                    s = cvar.wait(s).unwrap();
                }
                if !s.running {
                    break;
                }
                let items: Vec<FreezeItem> = s.items.values().cloned().collect();
                let interval = s.interval_ms;
                (items, interval)
            };

            if items_to_write.is_empty() {
                continue;
            }

            for item in items_to_write {
                let _ = kpm::write_memory(item.pid, item.address, &item.bytes);
            }

            let s = lock.lock().unwrap();
            if !s.running {
                break;
            }
            let (guard, _) = cvar
                .wait_timeout(s, Duration::from_millis(interval))
                .unwrap();
            if !guard.running {
                break;
            }
        }
    }

    pub fn freeze(
        &self,
        pid: u32,
        address: u64,
        value: &str,
        value_type: ValueType,
    ) -> Result<(), String> {
        let bytes = value_str_to_bytes(value, value_type)?;
        kpm::write_memory(pid, address, &bytes)
            .map_err(|e| format!("KPM write failed for 0x{address:x}: {e}"))?;

        let (lock, cvar) = &*self.state;
        let mut s = lock.lock().unwrap();
        s.items.insert(
            address,
            FreezeItem {
                pid,
                address,
                bytes,
            },
        );
        cvar.notify_one();
        Ok(())
    }

    pub fn unfreeze(&self, address: u64) -> bool {
        let (lock, _) = &*self.state;
        let mut s = lock.lock().unwrap();
        s.items.remove(&address).is_some()
    }

    pub fn unfreeze_all(&self) -> usize {
        let (lock, _) = &*self.state;
        let mut s = lock.lock().unwrap();
        let count = s.items.len();
        s.items.clear();
        count
    }

    pub fn is_frozen(&self, address: u64) -> bool {
        let (lock, _) = &*self.state;
        let s = lock.lock().unwrap();
        s.items.contains_key(&address)
    }
}

static FREEZE_ENGINE: OnceLock<FreezeEngine> = OnceLock::new();

pub fn get_freeze_engine() -> &'static FreezeEngine {
    FREEZE_ENGINE.get_or_init(FreezeEngine::new)
}
