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

/// Writes raw bytes into target process memory, validating that the page(s)
/// containing the address range are present in physical RAM via pagemap before writing.
pub fn write_raw_bytes(pid: u32, address: u64, bytes: &[u8]) -> Result<(), String> {
    if bytes.is_empty() {
        return Ok(());
    }

    if !crate::pagemap::is_page_present(pid, address, crate::pagemap::PM_PRESENT)? {
        return Err(format!("Write aborted: Page not resident at 0x{address:x}"));
    }

    let page_size = crate::pagemap::system_page_size();
    let end_addr = address.saturating_add(bytes.len() as u64 - 1);
    if end_addr / page_size != address / page_size
        && !crate::pagemap::is_page_present(pid, end_addr, crate::pagemap::PM_PRESENT)?
    {
        return Err(format!(
            "Write aborted: End page not resident at 0x{end_addr:x}"
        ));
    }

    kpm::write_memory(pid, address, bytes)
        .map_err(|e| format!("KPM write failed for 0x{address:x}: {e}"))
}

/// Writes a typed value into a virtual memory address of the target process via KPM.
pub fn write_value(
    pid: u32,
    address: u64,
    value: &str,
    value_type: ValueType,
) -> Result<(), String> {
    let bytes = value_str_to_bytes(value, value_type)?;
    write_raw_bytes(pid, address, &bytes)
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
    if !crate::pagemap::is_page_present(pid, address, crate::pagemap::PM_PRESENT)? {
        return Err(format!(
            "Write obscured aborted: Page not resident at 0x{address:x}"
        ));
    }

    let s = value_str.trim();
    match obscured_type {
        ObscuredType::ObscuredInt => {
            let target: i32 = parse_int_flexible::<i32>(s)
                .or_else(|_| parse_int_flexible::<u32>(s).map(|u| u as i32))
                .map_err(|e| format!("Invalid int '{s}': {e}"))?;

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

            write_raw_bytes(pid, address, &out)
                .map_err(|e| format!("Write ObscuredInt failed at 0x{address:x}: {e}"))
        }
        ObscuredType::ObscuredFloat => {
            let target: f32 = s.parse().map_err(|e| format!("Invalid float '{s}': {e}"))?;
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

            write_raw_bytes(pid, address, &out)
                .map_err(|e| format!("Write ObscuredFloat failed at 0x{address:x}: {e}"))
        }
        ObscuredType::ObscuredDouble => {
            let target: f64 = s
                .parse()
                .map_err(|e| format!("Invalid double '{s}': {e}"))?;
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

            write_raw_bytes(pid, address, &out)
                .map_err(|e| format!("Write ObscuredDouble failed at 0x{address:x}: {e}"))
        }
        ObscuredType::ObscuredLong => {
            let target: i64 = parse_int_flexible::<i64>(s)
                .or_else(|_| parse_int_flexible::<u64>(s).map(|u| u as i64))
                .map_err(|e| format!("Invalid long '{s}': {e}"))?;

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

            write_raw_bytes(pid, address, &out)
                .map_err(|e| format!("Write ObscuredLong failed at 0x{address:x}: {e}"))
        }
    }
}

/// Writes a BigDouble scientific structure (mantissa & exponent) to memory
pub fn write_big_double(pid: u32, address: u64, value_str: &str) -> Result<(), String> {
    if !crate::pagemap::is_page_present(pid, address, crate::pagemap::PM_PRESENT)? {
        return Err(format!(
            "Write BigDouble aborted: Page not resident at 0x{address:x}"
        ));
    }

    let target = BigDouble::parse(value_str)?;

    let mut buf = [0u8; 16];
    let is_exp32 = if kpm::read_memory(pid, address, &mut buf).is_ok() {
        // Detect 12-byte layout { f64 mantissa, i32 exponent }:
        // If upper 4 bytes of the exponent region are zero but lower 4 bytes are non-zero,
        // the struct likely uses a 32-bit exponent.
        let exp32 = i32::from_le_bytes(buf[8..12].try_into().unwrap());
        let upper = u32::from_le_bytes(buf[12..16].try_into().unwrap());
        upper == 0 && exp32 != 0
    } else {
        false
    };

    if is_exp32 {
        let mut out = [0u8; 12];
        out[..8].copy_from_slice(&target.mantissa.to_le_bytes());
        out[8..12].copy_from_slice(&(target.exponent as i32).to_le_bytes());
        write_raw_bytes(pid, address, &out)
            .map_err(|e| format!("Write BigDouble (32-bit exp) failed at 0x{address:x}: {e}"))
    } else {
        let mut out = [0u8; 16];
        out[..8].copy_from_slice(&target.mantissa.to_le_bytes());
        out[8..16].copy_from_slice(&target.exponent.to_le_bytes());
        write_raw_bytes(pid, address, &out)
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
        if crate::pagemap::is_page_present(pid, *addr, crate::pagemap::PM_PRESENT).unwrap_or(false)
            && let Ok(bytes) = value_str_to_bytes(val_str, *vtype)
        {
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
                if write_raw_bytes(pid, *addr, bytes).is_ok() {
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
    items: HashMap<(u32, u64), FreezeItem>,
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

            // Group frozen items by PID to dispatch all writes in a single batch syscall
            let mut by_pid: HashMap<u32, Vec<(u64, &[u8])>> = HashMap::new();
            for item in &items_to_write {
                by_pid
                    .entry(item.pid)
                    .or_default()
                    .push((item.address, item.bytes.as_slice()));
            }

            for (pid, writes) in by_pid {
                if writes.len() == 1 {
                    let _ = kpm::write_memory(pid, writes[0].0, writes[0].1);
                } else if kpm::write_batch(pid, &writes).is_err() {
                    // Fallback to individual writes if batch syscall fails
                    for (addr, data) in writes {
                        let _ = kpm::write_memory(pid, addr, data);
                    }
                }
            }

            let s = lock.lock().unwrap();
            if !s.running {
                break;
            }
            // If new items were added while unlocked, loop immediately without waiting
            if s.items.len() > items_to_write.len() {
                continue;
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
        write_raw_bytes(pid, address, &bytes)?;

        let (lock, cvar) = &*self.state;
        let mut s = lock.lock().unwrap();
        s.items.insert(
            (pid, address),
            FreezeItem {
                pid,
                address,
                bytes,
            },
        );
        cvar.notify_one();
        Ok(())
    }

    pub fn unfreeze(&self, pid: u32, address: u64) -> bool {
        let (lock, _) = &*self.state;
        let mut s = lock.lock().unwrap();
        s.items.remove(&(pid, address)).is_some()
    }

    pub fn unfreeze_all(&self) -> usize {
        let (lock, _) = &*self.state;
        let mut s = lock.lock().unwrap();
        let count = s.items.len();
        s.items.clear();
        count
    }

    pub fn is_frozen(&self, pid: u32, address: u64) -> bool {
        let (lock, _) = &*self.state;
        let s = lock.lock().unwrap();
        s.items.contains_key(&(pid, address))
    }
}

static FREEZE_ENGINE: OnceLock<FreezeEngine> = OnceLock::new();

pub fn get_freeze_engine() -> &'static FreezeEngine {
    FREEZE_ENGINE.get_or_init(FreezeEngine::new)
}

/// Resolves a multi-level pointer chain in target process memory.
///
/// Follows the standard multi-level pointer dereference contract:
/// - Step 1: Read 64-bit pointer (`u64`) from `base_addr`.
/// - Step 2: Add `offsets[0]` to get next address.
/// - Step 3: Repeat for each subsequent offset in `offsets[1..]`.
/// - Returns the final resolved 64-bit virtual memory address.
pub fn resolve_pointer_chain(pid: u32, base_addr: u64, offsets: &[i64]) -> Result<u64, String> {
    if base_addr == 0 {
        return Err("Base address cannot be 0x0".to_string());
    }

    if offsets.is_empty() {
        return Ok(base_addr);
    }

    let mut current_addr = base_addr;

    for (idx, &offset) in offsets.iter().enumerate() {
        if current_addr < 0x1000 {
            return Err(format!(
                "Null or invalid pointer at level {idx} (address: 0x{current_addr:x})"
            ));
        }

        if !crate::pagemap::is_page_present(pid, current_addr, crate::pagemap::PM_PRESENT)? {
            return Err(format!(
                "Page not resident at level {idx} (address: 0x{current_addr:x})"
            ));
        }

        let mut buf = [0u8; 8];
        kpm::read_memory(pid, current_addr, &mut buf).map_err(|e| {
            format!("Failed to dereference pointer at 0x{current_addr:x} (level {idx}): {e}")
        })?;

        let ptr = u64::from_le_bytes(buf);
        if ptr == 0 {
            return Err(format!(
                "Null pointer encountered at level {idx} (0x{current_addr:x} -> 0x0)"
            ));
        }

        // Apply signed offset safely
        current_addr = if offset >= 0 {
            ptr.checked_add(offset as u64).ok_or_else(|| {
                format!("Address overflow at level {idx}: 0x{ptr:x} + 0x{offset:x}")
            })?
        } else {
            ptr.checked_sub(offset.unsigned_abs()).ok_or_else(|| {
                format!(
                    "Address underflow at level {idx}: 0x{ptr:x} - 0x{:x}",
                    offset.unsigned_abs()
                )
            })?
        };
    }

    Ok(current_addr)
}

/// Reads a typed value by dereferencing a multi-level pointer chain.
pub fn read_pointer_value(
    pid: u32,
    base_addr: u64,
    offsets: &[i64],
    vtype: ValueType,
) -> Result<String, String> {
    let target_addr = resolve_pointer_chain(pid, base_addr, offsets)?;
    if !crate::pagemap::is_page_present(pid, target_addr, crate::pagemap::PM_PRESENT)? {
        return Err(format!("Page not resident at 0x{target_addr:x}"));
    }
    let mut buf = vec![0u8; vtype.size()];
    kpm::read_memory(pid, target_addr, &mut buf)
        .map_err(|e| format!("Read pointer target at 0x{target_addr:x} failed: {e}"))?;
    Ok(bytes_to_value_str(&buf, vtype))
}

/// Writes a typed value by dereferencing a multi-level pointer chain.
pub fn write_pointer_value(
    pid: u32,
    base_addr: u64,
    offsets: &[i64],
    value_str: &str,
    vtype: ValueType,
) -> Result<(), String> {
    let target_addr = resolve_pointer_chain(pid, base_addr, offsets)?;
    write_value(pid, target_addr, value_str, vtype)
}

/// Reads a value encrypted with a custom static XOR mask.
pub fn read_xor_value(
    pid: u32,
    address: u64,
    xor_key: u64,
    vtype: ValueType,
) -> Result<String, String> {
    if !crate::pagemap::is_page_present(pid, address, crate::pagemap::PM_PRESENT)? {
        return Err(format!("Page not resident at 0x{address:x}"));
    }
    let mut buf = vec![0u8; vtype.size()];
    kpm::read_memory(pid, address, &mut buf)
        .map_err(|e| format!("Read XOR memory at 0x{address:x} failed: {e}"))?;

    let key_bytes = xor_key.to_le_bytes();
    for (i, b) in buf.iter_mut().enumerate() {
        *b ^= key_bytes[i % key_bytes.len()];
    }

    Ok(bytes_to_value_str(&buf, vtype))
}

/// Writes a value encrypted with a custom static XOR mask.
pub fn write_xor_value(
    pid: u32,
    address: u64,
    value_str: &str,
    xor_key: u64,
    vtype: ValueType,
) -> Result<(), String> {
    let mut bytes = value_str_to_bytes(value_str, vtype)?;
    let key_bytes = xor_key.to_le_bytes();
    for (i, b) in bytes.iter_mut().enumerate() {
        *b ^= key_bytes[i % key_bytes.len()];
    }

    write_raw_bytes(pid, address, &bytes)
}
