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

use crate::kpm;
use crate::logger;
use crate::types::*;
use std::sync::atomic::{AtomicU8, Ordering};

#[repr(u8)]
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum ScanEngineMode {
    RustNeon = 0,
    Kernel = 1,
    Auto = 2,
}

static SCAN_ENGINE_MODE: AtomicU8 = AtomicU8::new(ScanEngineMode::RustNeon as u8);

pub fn set_scan_engine_mode(mode: u8) {
    let m = match mode {
        1 => ScanEngineMode::Kernel as u8,
        2 => ScanEngineMode::Auto as u8,
        _ => ScanEngineMode::RustNeon as u8,
    };
    SCAN_ENGINE_MODE.store(m, Ordering::Relaxed);
}

pub fn get_scan_engine_mode() -> u8 {
    SCAN_ENGINE_MODE.load(Ordering::Relaxed)
}

const CHUNK_SIZE: usize = 4 * 1024 * 1024; // 4MB chunks per read

/// Prepares, sorts, and merges memory regions into non-overlapping scan ranges for kernel scanning.
fn prepare_scan_ranges(regions: &[MemoryRegion]) -> Vec<kpm::HmkpmScanRange> {
    let mut pending_ranges: Vec<kpm::HmkpmScanRange> = regions
        .iter()
        .filter(|r| r.end > r.start)
        .map(|r| kpm::HmkpmScanRange {
            start_va: r.start,
            size: r.end - r.start,
        })
        .collect();

    pending_ranges.sort_unstable_by_key(|r| r.start_va);

    let mut merged_ranges: Vec<kpm::HmkpmScanRange> = Vec::with_capacity(pending_ranges.len());
    for r in pending_ranges {
        if let Some(last) = merged_ranges.last_mut() {
            let last_end = last.start_va.saturating_add(last.size);
            if r.start_va <= last_end {
                let r_end = r.start_va.saturating_add(r.size);
                if r_end > last_end {
                    last.size = r_end - last.start_va;
                }
                continue;
            }
        }
        merged_ranges.push(r);
    }
    merged_ranges
}

/// Advances pending scan ranges by dropping fully scanned ranges and slicing partially scanned ones.
fn advance_scan_ranges(
    pending_ranges: &[kpm::HmkpmScanRange],
    next_start: u64,
) -> Vec<kpm::HmkpmScanRange> {
    let mut updated_ranges = Vec::new();
    for r in pending_ranges {
        let r_end = r.start_va.saturating_add(r.size);
        if r_end <= next_start {
            // Already completely scanned
            continue;
        } else if r.start_va < next_start {
            // Partially scanned: advance start_va to next_start
            let new_size = r_end - next_start;
            if new_size > 0 {
                updated_ranges.push(kpm::HmkpmScanRange {
                    start_va: next_start,
                    size: new_size,
                });
            }
        } else {
            // Future range: untouched
            updated_ranges.push(*r);
        }
    }
    updated_ranges
}

fn try_kernel_scan_exact(
    pid: u32,
    regions: &[MemoryRegion],
    vt: ValueType,
    target_bytes: &[u8],
) -> Option<Vec<CompactMatch>> {
    if !kpm::is_feature_supported(kpm::HMKPM_FEATURE_SCAN_KERNEL) {
        return None;
    }

    let (scan_type, criteria) = match vt {
        ValueType::Byte => {
            let val = target_bytes.first().copied()? as u64;
            (
                kpm::HMKPM_SCAN_TYPE_U8,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact { val, mask: 0 },
                },
            )
        }
        ValueType::Short => {
            let val = u16::from_le_bytes(target_bytes[..2].try_into().ok()?) as u64;
            (
                kpm::HMKPM_SCAN_TYPE_U16,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact { val, mask: 0 },
                },
            )
        }
        ValueType::Int => {
            let val = u32::from_le_bytes(target_bytes[..4].try_into().ok()?) as u64;
            (
                kpm::HMKPM_SCAN_TYPE_U32,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact { val, mask: 0 },
                },
            )
        }
        ValueType::Long => {
            let val = u64::from_le_bytes(target_bytes[..8].try_into().ok()?);
            (
                kpm::HMKPM_SCAN_TYPE_U64,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact { val, mask: 0 },
                },
            )
        }
        ValueType::Float => {
            let val = u32::from_le_bytes(target_bytes[..4].try_into().ok()?) as u64;
            (
                kpm::HMKPM_SCAN_TYPE_F32,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact { val, mask: 0 },
                },
            )
        }
        ValueType::Double => {
            let val = u64::from_le_bytes(target_bytes[..8].try_into().ok()?);
            (
                kpm::HMKPM_SCAN_TYPE_F64,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact { val, mask: 0 },
                },
            )
        }
        ValueType::Float16 => return None,
    };

    let step = vt.size();
    let raw_val = bytes_to_raw_u64(target_bytes, vt);

    let mut all_matches = Vec::new();
    let max_per_call = kpm::HMKPM_MAX_SCAN_MATCHES;
    let mut pending_ranges = prepare_scan_ranges(regions);

    while !pending_ranges.is_empty() {
        let batch_len = pending_ranges.len().min(kpm::HMKPM_MAX_SCAN_RANGES);
        let batch = &pending_ranges[..batch_len];

        match kpm::scan_kernel(
            pid,
            scan_type,
            kpm::HMKPM_SCAN_OP_EXACT,
            step as u8,
            criteria,
            None,
            None,
            batch,
            max_per_call,
        ) {
            Ok((addrs, total_matches_found)) => {
                let addrs_count = addrs.len();
                for &addr in &addrs {
                    let region_idx = regions
                        .iter()
                        .position(|r| addr >= r.start && addr < r.end)
                        .unwrap_or(0);
                    all_matches.push(CompactMatch {
                        address: addr,
                        raw_value: raw_val,
                        region_idx: region_idx as u32,
                        value_type: vt,
                    });
                }

                // If matches were truncated by the kernel buffer limit, resume from last_match_addr + step
                if addrs_count >= max_per_call && total_matches_found > addrs_count as u64 {
                    if let Some(&last_addr) = addrs.last() {
                        let next_start = last_addr.saturating_add(step as u64);
                        pending_ranges = advance_scan_ranges(&pending_ranges, next_start);
                    } else {
                        pending_ranges.drain(..batch_len);
                    }
                } else {
                    pending_ranges.drain(..batch_len);
                }
            }
            Err(e) => {
                logger::warn(
                    "Scanner",
                    &format!("Kernel scan failed ({e}), falling back to NEON SIMD"),
                );
                return None;
            }
        }
    }

    Some(all_matches)
}

fn scan_regions_chunk(
    pid: u32,
    chunk: &[(usize, &MemoryRegion)],
    valid_targets: &[(ValueType, Vec<u8>)],
    min_step: usize,
    max_step: usize,
    operator: ScanOperator,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf: Vec<usize> = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    for &(region_idx, region) in chunk {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < min_step {
                break;
            }

            let present_ranges = v2p.get_present_ranges(
                addr,
                addr + chunk_len as u64,
                crate::v2p::PAGE_FLAG_PRESENT,
            )?;

            for (start_addr, end_addr) in present_ranges {
                let range_len = (end_addr - start_addr) as usize;
                if range_len < min_step {
                    continue;
                }

                if chunk_buf.len() < range_len {
                    chunk_buf.resize(range_len, 0);
                }

                if crate::kpm::read_memory(pid, start_addr, &mut chunk_buf[..range_len]).is_ok() {
                    let block_data = &chunk_buf[..range_len];

                    for (vt, target_bytes) in valid_targets {
                        let step = vt.size();
                        if range_len < step {
                            continue;
                        }

                        if operator == ScanOperator::Unknown {
                            let mut off = 0;
                            while off + step <= range_len {
                                let match_addr = start_addr + off as u64;
                                let raw_val = bytes_to_raw_u64(&block_data[off..off + step], *vt);
                                matches.push(CompactMatch {
                                    address: match_addr,
                                    raw_value: raw_val,
                                    region_idx: region_idx as u32,
                                    value_type: *vt,
                                });
                                off += step;
                            }
                        } else {
                            offsets_buf.clear();
                            scan_buffer_to(
                                block_data,
                                target_bytes,
                                *vt,
                                operator,
                                &mut offsets_buf,
                            );
                            for &off in &offsets_buf {
                                let match_addr = start_addr + off as u64;
                                let raw_val = bytes_to_raw_u64(&block_data[off..off + step], *vt);
                                matches.push(CompactMatch {
                                    address: match_addr,
                                    raw_value: raw_val,
                                    region_idx: region_idx as u32,
                                    value_type: *vt,
                                });
                            }
                        }
                    }
                }
            }

            let overlap = if addr + (chunk_len as u64) < region.end {
                max_step.saturating_sub(1)
            } else {
                0
            };
            addr += (chunk_len.saturating_sub(overlap).max(1)) as u64;
        }
    }

    Ok(matches)
}

/// First scan: read regions and find matching values across multiple types, returning compact matches.
/// Distributes region chunks across available hardware CPU threads via scoped concurrency.
pub fn scan_regions(
    pid: u32,
    regions: &[MemoryRegion],
    target_value_str: &str,
    value_types: &[ValueType],
    operator: ScanOperator,
) -> Result<ScanSession, String> {
    if value_types.is_empty() {
        return Err("No value types specified".into());
    }

    let mut valid_targets: Vec<(ValueType, Vec<u8>)> = Vec::new();
    let mut parse_errors = Vec::new();

    for &vt in value_types {
        if operator == ScanOperator::Unknown {
            valid_targets.push((vt, Vec::new()));
        } else {
            match value_str_to_bytes(target_value_str, vt) {
                Ok(b) => valid_targets.push((vt, b)),
                Err(e) => parse_errors.push(format!("{}: {}", vt.as_str(), e)),
            }
        }
    }

    if valid_targets.is_empty() {
        return Err(format!(
            "Value '{target_value_str}' is not valid for any selected types: {}",
            parse_errors.join(", ")
        ));
    }

    // Try in-kernel direct scan if operator is Equal, engine mode is Kernel or Auto, and all targets are supported
    let engine_mode = get_scan_engine_mode();
    if (engine_mode == ScanEngineMode::Kernel as u8 || engine_mode == ScanEngineMode::Auto as u8)
        && operator == ScanOperator::Equal
        && !regions.is_empty()
    {
        let mut kernel_matches = Vec::new();
        let mut kernel_success = true;

        for (vt, target_bytes) in &valid_targets {
            if let Some(partial) = try_kernel_scan_exact(pid, regions, *vt, target_bytes) {
                kernel_matches.extend(partial);
            } else {
                kernel_success = false;
                break;
            }
        }

        if kernel_success {
            kernel_matches.sort_by(|a, b| {
                a.address
                    .cmp(&b.address)
                    .then_with(|| a.value_type.cmp(&b.value_type))
            });
            kernel_matches.dedup_by(|a, b| a.address == b.address && a.value_type == b.value_type);

            return Ok(ScanSession {
                regions: regions.to_vec(),
                matches: kernel_matches,
                active_types: value_types.to_vec(),
            });
        }
    }

    let min_step = valid_targets
        .iter()
        .map(|(vt, _)| vt.size())
        .min()
        .unwrap_or(4);
    let max_step = valid_targets
        .iter()
        .map(|(vt, _)| vt.size())
        .max()
        .unwrap_or(8);

    let indexed_regions: Vec<(usize, &MemoryRegion)> = regions.iter().enumerate().collect();
    let worker_count = if indexed_regions.len() <= 1 {
        1
    } else {
        std::thread::available_parallelism()
            .map(|p| p.get())
            .unwrap_or(4)
            .min(indexed_regions.len())
            .min(8)
    };

    let mut matches = if worker_count <= 1 {
        scan_regions_chunk(
            pid,
            &indexed_regions,
            &valid_targets,
            min_step,
            max_step,
            operator,
        )?
    } else {
        let chunk_size = indexed_regions.len().div_ceil(worker_count);
        let chunks: Vec<&[(usize, &MemoryRegion)]> = indexed_regions.chunks(chunk_size).collect();

        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(chunks.len());
            for chunk in chunks {
                let valid_targets_ref = &valid_targets;
                let handle = s.spawn(move || {
                    scan_regions_chunk(pid, chunk, valid_targets_ref, min_step, max_step, operator)
                });
                handles.push(handle);
            }

            let mut all_matches = Vec::new();
            for handle in handles {
                let partial = handle
                    .join()
                    .map_err(|_| "Worker thread panicked during scan".to_string())??;
                all_matches.extend(partial);
            }
            Ok::<Vec<CompactMatch>, String>(all_matches)
        })?
    };

    matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.cmp(&b.value_type))
    });
    matches.dedup_by(|a, b| a.address == b.address && a.value_type == b.value_type);

    Ok(ScanSession {
        regions: regions.to_vec(),
        matches,
        active_types: value_types.to_vec(),
    })
}

/// Scan a buffer for values matching target, pushing offsets into the provided buffer.
pub fn scan_buffer_to(
    data: &[u8],
    target: &[u8],
    vtype: ValueType,
    op: ScanOperator,
    results: &mut Vec<usize>,
) {
    let step = vtype.size();
    if data.len() < step {
        return;
    }

    #[cfg(target_arch = "aarch64")]
    {
        match vtype {
            ValueType::Byte => {
                let v = target[0] as i8;
                let neon_op = match op {
                    ScanOperator::Equal => Some(NeonOp::Eq),
                    ScanOperator::NotEqual => Some(NeonOp::Ne),
                    ScanOperator::Greater => Some(NeonOp::Gt),
                    ScanOperator::Less => Some(NeonOp::Lt),
                    ScanOperator::GreaterEqual => Some(NeonOp::Ge),
                    ScanOperator::LessEqual => Some(NeonOp::Le),
                    _ => None,
                };
                if let Some(nop) = neon_op {
                    scan_buffer_i8_neon(data, v, nop, results);
                    return;
                }
            }
            ValueType::Short => {
                let v = i16::from_le_bytes(target[..2].try_into().unwrap());
                let neon_op = match op {
                    ScanOperator::Equal => Some(NeonOp::Eq),
                    ScanOperator::NotEqual => Some(NeonOp::Ne),
                    ScanOperator::Greater => Some(NeonOp::Gt),
                    ScanOperator::Less => Some(NeonOp::Lt),
                    ScanOperator::GreaterEqual => Some(NeonOp::Ge),
                    ScanOperator::LessEqual => Some(NeonOp::Le),
                    _ => None,
                };
                if let Some(nop) = neon_op {
                    scan_buffer_i16_neon(data, v, nop, results);
                    return;
                }
            }
            ValueType::Int => {
                let v = i32::from_le_bytes(target[..4].try_into().unwrap());
                let neon_op = match op {
                    ScanOperator::Equal => Some(NeonOp::Eq),
                    ScanOperator::NotEqual => Some(NeonOp::Ne),
                    ScanOperator::Greater => Some(NeonOp::Gt),
                    ScanOperator::Less => Some(NeonOp::Lt),
                    ScanOperator::GreaterEqual => Some(NeonOp::Ge),
                    ScanOperator::LessEqual => Some(NeonOp::Le),
                    _ => None,
                };
                if let Some(nop) = neon_op {
                    scan_buffer_i32_neon(data, v, nop, results);
                    return;
                }
            }
            ValueType::Long => {
                let v = i64::from_le_bytes(target[..8].try_into().unwrap());
                let neon_op = match op {
                    ScanOperator::Equal => Some(NeonOp::Eq),
                    ScanOperator::NotEqual => Some(NeonOp::Ne),
                    ScanOperator::Greater => Some(NeonOp::Gt),
                    ScanOperator::Less => Some(NeonOp::Lt),
                    ScanOperator::GreaterEqual => Some(NeonOp::Ge),
                    ScanOperator::LessEqual => Some(NeonOp::Le),
                    _ => None,
                };
                if let Some(nop) = neon_op {
                    scan_buffer_i64_neon(data, v, nop, results);
                    return;
                }
            }
            ValueType::Float => {
                let v = f32::from_le_bytes(target[..4].try_into().unwrap());
                let neon_op = match op {
                    ScanOperator::Equal => Some(NeonOp::Eq),
                    ScanOperator::NotEqual => Some(NeonOp::Ne),
                    ScanOperator::Greater => Some(NeonOp::Gt),
                    ScanOperator::Less => Some(NeonOp::Lt),
                    ScanOperator::GreaterEqual => Some(NeonOp::Ge),
                    ScanOperator::LessEqual => Some(NeonOp::Le),
                    _ => None,
                };
                if let Some(nop) = neon_op {
                    scan_buffer_f32_neon(data, v, nop, results);
                    return;
                }
            }
            ValueType::Double => {
                let v = f64::from_le_bytes(target[..8].try_into().unwrap());
                let neon_op = match op {
                    ScanOperator::Equal => Some(NeonOp::Eq),
                    ScanOperator::NotEqual => Some(NeonOp::Ne),
                    ScanOperator::Greater => Some(NeonOp::Gt),
                    ScanOperator::Less => Some(NeonOp::Lt),
                    ScanOperator::GreaterEqual => Some(NeonOp::Ge),
                    ScanOperator::LessEqual => Some(NeonOp::Le),
                    _ => None,
                };
                if let Some(nop) = neon_op {
                    scan_buffer_f64_neon(data, v, nop, results);
                    return;
                }
            }
            ValueType::Float16 => {
                // Float16 handled via scalar loop
            }
        }
    }

    // Scalar fallback
    let mut offset = 0;
    while offset + step <= data.len() {
        if compare_values(&data[offset..offset + step], target, vtype, op) {
            results.push(offset);
        }
        offset += step;
    }
}

// ============================================================
// NEON Generic Operator Type
// ============================================================

#[cfg(target_arch = "aarch64")]
#[derive(Clone, Copy)]
pub enum NeonOp {
    Eq,
    Ne,
    Gt,
    Lt,
    Ge,
    Le,
}

#[cfg(target_arch = "aarch64")]
fn scan_buffer_i8_neon(data: &[u8], target_val: i8, op: NeonOp, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    let len = data.len();
    let mut offset = 0;

    unsafe {
        let target_vec = vdupq_n_s8(target_val);
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i8;
            let chunk = vld1q_s8(ptr);
            let mask = match op {
                NeonOp::Eq => vceqq_s8(chunk, target_vec),
                NeonOp::Ne => vmvnq_u8(vceqq_s8(chunk, target_vec)),
                NeonOp::Gt => vcgtq_s8(chunk, target_vec),
                NeonOp::Lt => vcltq_s8(chunk, target_vec),
                NeonOp::Ge => vcgeq_s8(chunk, target_vec),
                NeonOp::Le => vcleq_s8(chunk, target_vec),
            };

            if vmaxvq_u8(mask) != 0 {
                let mut mask_bytes = [0u8; 16];
                vst1q_u8(mask_bytes.as_mut_ptr(), mask);
                for (i, &m) in mask_bytes.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i);
                    }
                }
            }
            offset += 16;
        }

        while offset < len {
            let val = data[offset] as i8;
            let hit = match op {
                NeonOp::Eq => val == target_val,
                NeonOp::Ne => val != target_val,
                NeonOp::Gt => val > target_val,
                NeonOp::Lt => val < target_val,
                NeonOp::Ge => val >= target_val,
                NeonOp::Le => val <= target_val,
            };
            if hit {
                results.push(offset);
            }
            offset += 1;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_buffer_i16_neon(data: &[u8], target_val: i16, op: NeonOp, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 2;
    let len = data.len();
    let mut offset = 0;

    unsafe {
        let target_vec = vdupq_n_s16(target_val);
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i16;
            let chunk = vld1q_s16(ptr);
            let mask = match op {
                NeonOp::Eq => vceqq_s16(chunk, target_vec),
                NeonOp::Ne => vmvnq_u16(vceqq_s16(chunk, target_vec)),
                NeonOp::Gt => vcgtq_s16(chunk, target_vec),
                NeonOp::Lt => vcltq_s16(chunk, target_vec),
                NeonOp::Ge => vcgeq_s16(chunk, target_vec),
                NeonOp::Le => vcleq_s16(chunk, target_vec),
            };

            if vmaxvq_u16(mask) != 0 {
                let mut mask_shorts = [0u16; 8];
                vst1q_u16(mask_shorts.as_mut_ptr(), mask);
                for (i, &m) in mask_shorts.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 2);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = i16::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            let hit = match op {
                NeonOp::Eq => val == target_val,
                NeonOp::Ne => val != target_val,
                NeonOp::Gt => val > target_val,
                NeonOp::Lt => val < target_val,
                NeonOp::Ge => val >= target_val,
                NeonOp::Le => val <= target_val,
            };
            if hit {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_buffer_i32_neon(data: &[u8], target_val: i32, op: NeonOp, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 4;
    let len = data.len();
    let mut offset = 0;

    unsafe {
        let target_vec = vdupq_n_s32(target_val);
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i32;
            let chunk = vld1q_s32(ptr);
            let mask = match op {
                NeonOp::Eq => vceqq_s32(chunk, target_vec),
                NeonOp::Ne => vmvnq_u32(vceqq_s32(chunk, target_vec)),
                NeonOp::Gt => vcgtq_s32(chunk, target_vec),
                NeonOp::Lt => vcltq_s32(chunk, target_vec),
                NeonOp::Ge => vcgeq_s32(chunk, target_vec),
                NeonOp::Le => vcleq_s32(chunk, target_vec),
            };

            if vmaxvq_u32(mask) != 0 {
                let mut mask_ints = [0u32; 4];
                vst1q_u32(mask_ints.as_mut_ptr(), mask);
                for (i, &m) in mask_ints.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 4);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = i32::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            let hit = match op {
                NeonOp::Eq => val == target_val,
                NeonOp::Ne => val != target_val,
                NeonOp::Gt => val > target_val,
                NeonOp::Lt => val < target_val,
                NeonOp::Ge => val >= target_val,
                NeonOp::Le => val <= target_val,
            };
            if hit {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_buffer_i64_neon(data: &[u8], target_val: i64, op: NeonOp, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 8;
    let len = data.len();
    let mut offset = 0;

    unsafe {
        let target_vec = vdupq_n_s64(target_val);
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i64;
            let chunk = vld1q_s64(ptr);
            let mask = match op {
                NeonOp::Eq => vceqq_s64(chunk, target_vec),
                NeonOp::Ne => veorq_u64(vceqq_s64(chunk, target_vec), vdupq_n_u64(u64::MAX)),
                NeonOp::Gt => vcgtq_s64(chunk, target_vec),
                NeonOp::Lt => vcltq_s64(chunk, target_vec),
                NeonOp::Ge => vcgeq_s64(chunk, target_vec),
                NeonOp::Le => vcleq_s64(chunk, target_vec),
            };

            let mask_u32 = vreinterpretq_u32_u64(mask);
            if vmaxvq_u32(mask_u32) != 0 {
                let mut mask_arr = [0u64; 2];
                vst1q_u64(mask_arr.as_mut_ptr(), mask);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 8);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = i64::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            let hit = match op {
                NeonOp::Eq => val == target_val,
                NeonOp::Ne => val != target_val,
                NeonOp::Gt => val > target_val,
                NeonOp::Lt => val < target_val,
                NeonOp::Ge => val >= target_val,
                NeonOp::Le => val <= target_val,
            };
            if hit {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_buffer_f32_neon(data: &[u8], target_val: f32, op: NeonOp, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 4;
    let len = data.len();
    let mut offset = 0;

    unsafe {
        let target_vec = vdupq_n_f32(target_val);
        let eps_vec = vdupq_n_f32(1e-5);
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const f32;
            let chunk = vld1q_f32(ptr);
            let mask = match op {
                NeonOp::Eq => {
                    let diff = vabdq_f32(chunk, target_vec);
                    vcltq_f32(diff, eps_vec)
                }
                NeonOp::Ne => {
                    let diff = vabdq_f32(chunk, target_vec);
                    vcgeq_f32(diff, eps_vec)
                }
                NeonOp::Gt => vcgtq_f32(chunk, target_vec),
                NeonOp::Lt => vcltq_f32(chunk, target_vec),
                NeonOp::Ge => vcgeq_f32(chunk, target_vec),
                NeonOp::Le => vcleq_f32(chunk, target_vec),
            };

            if vmaxvq_u32(mask) != 0 {
                let mut mask_floats = [0u32; 4];
                vst1q_u32(mask_floats.as_mut_ptr(), mask);
                for (i, &m) in mask_floats.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 4);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = f32::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            let hit = match op {
                NeonOp::Eq => (val - target_val).abs() < 1e-5,
                NeonOp::Ne => (val - target_val).abs() >= 1e-5,
                NeonOp::Gt => val > target_val,
                NeonOp::Lt => val < target_val,
                NeonOp::Ge => val >= target_val,
                NeonOp::Le => val <= target_val,
            };
            if hit {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_buffer_f64_neon(data: &[u8], target_val: f64, op: NeonOp, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 8;
    let len = data.len();
    let mut offset = 0;

    unsafe {
        let target_vec = vdupq_n_f64(target_val);
        let eps_vec = vdupq_n_f64(1e-9);
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const f64;
            let chunk = vld1q_f64(ptr);
            let mask = match op {
                NeonOp::Eq => {
                    let diff = vabdq_f64(chunk, target_vec);
                    vcltq_f64(diff, eps_vec)
                }
                NeonOp::Ne => {
                    let diff = vabdq_f64(chunk, target_vec);
                    vcgeq_f64(diff, eps_vec)
                }
                NeonOp::Gt => vcgtq_f64(chunk, target_vec),
                NeonOp::Lt => vcltq_f64(chunk, target_vec),
                NeonOp::Ge => vcgeq_f64(chunk, target_vec),
                NeonOp::Le => vcleq_f64(chunk, target_vec),
            };

            let mask_u32 = vreinterpretq_u32_u64(mask);
            if vmaxvq_u32(mask_u32) != 0 {
                let mut mask_arr = [0u64; 2];
                vst1q_u64(mask_arr.as_mut_ptr(), mask);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 8);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = f64::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            let hit = match op {
                NeonOp::Eq => (val - target_val).abs() < 1e-9,
                NeonOp::Ne => (val - target_val).abs() >= 1e-9,
                NeonOp::Gt => val > target_val,
                NeonOp::Lt => val < target_val,
                NeonOp::Ge => val >= target_val,
                NeonOp::Le => val <= target_val,
            };
            if hit {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

// ============================================================
// ARM NEON Range Scanning Functions
// ============================================================

#[cfg(target_arch = "aarch64")]
fn scan_range_buffer_i8_neon(data: &[u8], min_val: i8, max_val: i8, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    let len = data.len();
    let mut offset = 0;

    // SAFETY: Pointer bounds are checked against data.len() with offset + 16 <= len.
    unsafe {
        let min_vec = vdupq_n_s8(min_val);
        let max_vec = vdupq_n_s8(max_val);

        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i8;
            let chunk = vld1q_s8(ptr);
            let ge_mask = vcgeq_s8(chunk, min_vec);
            let le_mask = vcleq_s8(chunk, max_vec);
            let mask = vandq_u8(ge_mask, le_mask);

            if vmaxvq_u8(mask) != 0 {
                let mut mask_bytes = [0u8; 16];
                vst1q_u8(mask_bytes.as_mut_ptr(), mask);
                for (i, &m) in mask_bytes.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i);
                    }
                }
            }
            offset += 16;
        }

        while offset < len {
            let val = data[offset] as i8;
            if val >= min_val && val <= max_val {
                results.push(offset);
            }
            offset += 1;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_range_buffer_i16_neon(data: &[u8], min_val: i16, max_val: i16, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 2;
    let len = data.len();
    let mut offset = 0;

    // SAFETY: Pointer bounds are checked against data.len() with offset + 16 <= len.
    unsafe {
        let min_vec = vdupq_n_s16(min_val);
        let max_vec = vdupq_n_s16(max_val);

        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i16;
            let chunk = vld1q_s16(ptr);
            let ge_mask = vcgeq_s16(chunk, min_vec);
            let le_mask = vcleq_s16(chunk, max_vec);
            let mask = vandq_u16(ge_mask, le_mask);

            if vmaxvq_u16(mask) != 0 {
                let mut mask_shorts = [0u16; 8];
                vst1q_u16(mask_shorts.as_mut_ptr(), mask);
                for (i, &m) in mask_shorts.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 2);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = i16::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            if val >= min_val && val <= max_val {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_range_buffer_i32_neon(data: &[u8], min_val: i32, max_val: i32, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 4;
    let len = data.len();
    let mut offset = 0;

    // SAFETY: Pointer bounds are checked against data.len() with offset + 16 <= len.
    unsafe {
        let min_vec = vdupq_n_s32(min_val);
        let max_vec = vdupq_n_s32(max_val);

        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i32;
            let chunk = vld1q_s32(ptr);
            let ge_mask = vcgeq_s32(chunk, min_vec);
            let le_mask = vcleq_s32(chunk, max_vec);
            let mask = vandq_u32(ge_mask, le_mask);

            if vmaxvq_u32(mask) != 0 {
                let mut mask_ints = [0u32; 4];
                vst1q_u32(mask_ints.as_mut_ptr(), mask);
                for (i, &m) in mask_ints.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 4);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = i32::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            if val >= min_val && val <= max_val {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_range_buffer_i64_neon(data: &[u8], min_val: i64, max_val: i64, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 8;
    let len = data.len();
    let mut offset = 0;

    // SAFETY: Pointer bounds are checked against data.len() with offset + 16 <= len.
    unsafe {
        let min_vec = vdupq_n_s64(min_val);
        let max_vec = vdupq_n_s64(max_val);

        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const i64;
            let chunk = vld1q_s64(ptr);
            let ge_mask = vcgeq_s64(chunk, min_vec);
            let le_mask = vcleq_s64(chunk, max_vec);
            let mask = vandq_u64(ge_mask, le_mask);

            let mask_u32 = vreinterpretq_u32_u64(mask);
            if vmaxvq_u32(mask_u32) != 0 {
                let mut mask_arr = [0u64; 2];
                vst1q_u64(mask_arr.as_mut_ptr(), mask);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 8);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = i64::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            if val >= min_val && val <= max_val {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_range_buffer_f32_neon(data: &[u8], min_val: f32, max_val: f32, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 4;
    let len = data.len();
    let mut offset = 0;

    // SAFETY: Pointer bounds are checked against data.len() with offset + 16 <= len.
    unsafe {
        let min_vec = vdupq_n_f32(min_val);
        let max_vec = vdupq_n_f32(max_val);

        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const f32;
            let chunk = vld1q_f32(ptr);
            let ge_mask = vcgeq_f32(chunk, min_vec);
            let le_mask = vcleq_f32(chunk, max_vec);
            let mask = vandq_u32(ge_mask, le_mask);

            if vmaxvq_u32(mask) != 0 {
                let mut mask_floats = [0u32; 4];
                vst1q_u32(mask_floats.as_mut_ptr(), mask);
                for (i, &m) in mask_floats.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 4);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = f32::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            if val >= min_val && val <= max_val {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn scan_range_buffer_f64_neon(data: &[u8], min_val: f64, max_val: f64, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 8;
    let len = data.len();
    let mut offset = 0;

    // SAFETY: Pointer bounds are checked against data.len() with offset + 16 <= len.
    unsafe {
        let min_vec = vdupq_n_f64(min_val);
        let max_vec = vdupq_n_f64(max_val);

        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const f64;
            let chunk = vld1q_f64(ptr);
            let ge_mask = vcgeq_f64(chunk, min_vec);
            let le_mask = vcleq_f64(chunk, max_vec);
            let mask = vandq_u64(ge_mask, le_mask);

            let mask_u32 = vreinterpretq_u32_u64(mask);
            if vmaxvq_u32(mask_u32) != 0 {
                let mut mask_arr = [0u64; 2];
                vst1q_u64(mask_arr.as_mut_ptr(), mask);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 8);
                    }
                }
            }
            offset += 16;
        }

        while offset + STEP <= len {
            let val = f64::from_le_bytes(data[offset..offset + STEP].try_into().unwrap());
            if val >= min_val && val <= max_val {
                results.push(offset);
            }
            offset += STEP;
        }
    }
}

// ============================================================
// Obscured / Anti-Cheat XOR-Pair Scanning
// ============================================================

/// Scan buffer for 32-bit Obscured types (ObscuredInt / ObscuredFloat)
pub fn scan_buffer_obscured32(data: &[u8], target_bits: u32, results: &mut Vec<usize>) {
    let len = data.len();
    let mut offset = 0;
    const STEP: usize = 4;

    #[cfg(target_arch = "aarch64")]
    unsafe {
        use std::arch::aarch64::*;
        let target_vec = vdupq_n_u32(target_bits);
        // Requires 36 bytes (8 u32 pairs starting at offset, plus 1 u32 offset for the odd pass)
        while offset + 36 <= len {
            let ptr = data.as_ptr().add(offset) as *const u32;

            // Pass 1: Even offsets (offset + 0, 8, 16, 24)
            let pairs_even = vld2q_u32(ptr);
            let xored_even = veorq_u32(pairs_even.0, pairs_even.1);
            let mask_even = vceqq_u32(xored_even, target_vec);

            if vmaxvq_u32(mask_even) != 0 {
                let mut mask_arr = [0u32; 4];
                vst1q_u32(mask_arr.as_mut_ptr(), mask_even);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 8);
                    }
                }
            }

            // Pass 2: Odd offsets (offset + 4, 12, 20, 28)
            let pairs_odd = vld2q_u32(ptr.add(1));
            let xored_odd = veorq_u32(pairs_odd.0, pairs_odd.1);
            let mask_odd = vceqq_u32(xored_odd, target_vec);

            if vmaxvq_u32(mask_odd) != 0 {
                let mut mask_arr = [0u32; 4];
                vst1q_u32(mask_arr.as_mut_ptr(), mask_odd);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + 4 + i * 8);
                    }
                }
            }

            offset += 32;
        }
    }

    while offset + 8 <= len {
        let k = u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap());
        let v = u32::from_le_bytes(data[offset + 4..offset + 8].try_into().unwrap());
        if (k ^ v) == target_bits {
            results.push(offset);
        }
        offset += STEP;
    }
}

/// Scan buffer for 64-bit Obscured types (ObscuredDouble / ObscuredLong)
pub fn scan_buffer_obscured64(data: &[u8], target_bits: u64, results: &mut Vec<usize>) {
    let len = data.len();
    let mut offset = 0;
    const STEP: usize = 8;

    #[cfg(target_arch = "aarch64")]
    unsafe {
        use std::arch::aarch64::*;
        let target_vec = vdupq_n_u64(target_bits);
        // Requires 40 bytes (4 u64 pairs starting at offset, plus 1 u64 offset for the odd pass)
        while offset + 40 <= len {
            let ptr = data.as_ptr().add(offset) as *const u64;

            // Pass 1: Even offsets (offset + 0, 16)
            let pairs_even = vld2q_u64(ptr);
            let xored_even = veorq_u64(pairs_even.0, pairs_even.1);
            let mask_even = vceqq_u64(xored_even, target_vec);

            if vmaxvq_u32(vreinterpretq_u32_u64(mask_even)) != 0 {
                let mut mask_arr = [0u64; 2];
                vst1q_u64(mask_arr.as_mut_ptr(), mask_even);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 16);
                    }
                }
            }

            // Pass 2: Odd offsets (offset + 8, 24)
            let pairs_odd = vld2q_u64(ptr.add(1));
            let xored_odd = veorq_u64(pairs_odd.0, pairs_odd.1);
            let mask_odd = vceqq_u64(xored_odd, target_vec);

            if vmaxvq_u32(vreinterpretq_u32_u64(mask_odd)) != 0 {
                let mut mask_arr = [0u64; 2];
                vst1q_u64(mask_arr.as_mut_ptr(), mask_odd);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + 8 + i * 16);
                    }
                }
            }

            offset += 32;
        }
    }

    while offset + 16 <= len {
        let k = u64::from_le_bytes(data[offset..offset + 8].try_into().unwrap());
        let v = u64::from_le_bytes(data[offset + 8..offset + 16].try_into().unwrap());
        if (k ^ v) == target_bits {
            results.push(offset);
        }
        offset += STEP;
    }
}

fn scan_obscured_chunk(
    pid: u32,
    chunk: &[(usize, &MemoryRegion)],
    target_bits32: Option<u32>,
    target_bits64: Option<u64>,
    target_vtype: ValueType,
    step: usize,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    for &(region_idx, region) in chunk {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < step {
                break;
            }

            let present_ranges = v2p.get_present_ranges(
                addr,
                addr + chunk_len as u64,
                crate::v2p::PAGE_FLAG_PRESENT,
            )?;

            for (start_addr, end_addr) in present_ranges {
                let range_len = (end_addr - start_addr) as usize;
                if range_len < step {
                    continue;
                }

                if chunk_buf.len() < range_len {
                    chunk_buf.resize(range_len, 0);
                }

                if crate::kpm::read_memory(pid, start_addr, &mut chunk_buf[..range_len]).is_ok() {
                    let block_data = &chunk_buf[..range_len];

                    offsets_buf.clear();
                    if let Some(t32) = target_bits32 {
                        scan_buffer_obscured32(block_data, t32, &mut offsets_buf);
                    } else if let Some(t64) = target_bits64 {
                        scan_buffer_obscured64(block_data, t64, &mut offsets_buf);
                    }

                    for &off in &offsets_buf {
                        let match_addr = start_addr + off as u64;
                        let raw_val = if let Some(t32) = target_bits32 {
                            t32 as u64
                        } else {
                            target_bits64.unwrap_or(0)
                        };

                        matches.push(CompactMatch {
                            address: match_addr,
                            raw_value: raw_val,
                            region_idx: region_idx as u32,
                            value_type: target_vtype,
                        });
                    }
                }
            }

            let overlap = if addr + (chunk_len as u64) < region.end {
                step.saturating_sub(1)
            } else {
                0
            };
            addr += (chunk_len.saturating_sub(overlap).max(1)) as u64;
        }
    }

    Ok(matches)
}

pub fn scan_obscured(
    pid: u32,
    regions: &[MemoryRegion],
    target_value_str: &str,
    obscured_type: ObscuredType,
) -> Result<ScanSession, String> {
    let s = target_value_str.trim();
    let (target_bits32, target_bits64) = match obscured_type {
        ObscuredType::ObscuredInt => {
            let v: i32 = parse_int_flexible(s)?;
            (Some(v as u32), None)
        }
        ObscuredType::ObscuredFloat => {
            let v: f32 = s.parse().map_err(|e| format!("Invalid float '{s}': {e}"))?;
            (Some(v.to_bits()), None)
        }
        ObscuredType::ObscuredDouble => {
            let v: f64 = s
                .parse()
                .map_err(|e| format!("Invalid double '{s}': {e}"))?;
            (None, Some(v.to_bits()))
        }
        ObscuredType::ObscuredLong => {
            let v: i64 = parse_int_flexible(s)?;
            (None, Some(v as u64))
        }
    };

    let step = obscured_type.size();
    let target_vtype = match obscured_type {
        ObscuredType::ObscuredInt => ValueType::Int,
        ObscuredType::ObscuredFloat => ValueType::Float,
        ObscuredType::ObscuredDouble => ValueType::Double,
        ObscuredType::ObscuredLong => ValueType::Long,
    };

    let indexed_regions: Vec<(usize, &MemoryRegion)> = regions.iter().enumerate().collect();
    let worker_count = if indexed_regions.len() <= 1 {
        1
    } else {
        std::thread::available_parallelism()
            .map(|p| p.get())
            .unwrap_or(4)
            .min(indexed_regions.len())
            .min(8)
    };

    let mut matches = if worker_count <= 1 {
        scan_obscured_chunk(
            pid,
            &indexed_regions,
            target_bits32,
            target_bits64,
            target_vtype,
            step,
        )?
    } else {
        let chunk_size = indexed_regions.len().div_ceil(worker_count);
        let chunks: Vec<&[(usize, &MemoryRegion)]> = indexed_regions.chunks(chunk_size).collect();

        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(chunks.len());
            for chunk in chunks {
                let handle = s.spawn(move || {
                    scan_obscured_chunk(
                        pid,
                        chunk,
                        target_bits32,
                        target_bits64,
                        target_vtype,
                        step,
                    )
                });
                handles.push(handle);
            }

            let mut all_matches = Vec::new();
            for handle in handles {
                let partial = handle
                    .join()
                    .map_err(|_| "Worker thread panicked during obscured scan".to_string())??;
                all_matches.extend(partial);
            }
            Ok::<Vec<CompactMatch>, String>(all_matches)
        })?
    };

    matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.cmp(&b.value_type))
    });
    matches.dedup_by(|a, b| a.address == b.address && a.value_type == b.value_type);

    Ok(ScanSession {
        regions: regions.to_vec(),
        matches,
        active_types: vec![target_vtype],
    })
}

// ============================================================
// BigDouble / Scientific Notation Struct Scanning
// ============================================================

pub fn scan_buffer_big_double_to(data: &[u8], target: &BigDouble, results: &mut Vec<usize>) {
    let len = data.len();
    let mut offset = 0;
    const STEP: usize = 4;

    while offset + 12 <= len {
        // Layout A (16 bytes): f64 mantissa + i64 exponent
        if offset + 16 <= len {
            let m = f64::from_le_bytes(data[offset..offset + 8].try_into().unwrap());
            let exp64 = i64::from_le_bytes(data[offset + 8..offset + 16].try_into().unwrap());
            if exp64 == target.exponent && (m - target.mantissa).abs() < 1e-5 {
                results.push(offset);
                offset += 16;
                continue;
            }
        }

        // Layout B (12 bytes): f64 mantissa + i32 exponent
        let m = f64::from_le_bytes(data[offset..offset + 8].try_into().unwrap());
        let exp32 = i32::from_le_bytes(data[offset + 8..offset + 12].try_into().unwrap());
        if exp32 as i64 == target.exponent && (m - target.mantissa).abs() < 1e-5 {
            results.push(offset);
            offset += 12;
            continue;
        }

        offset += STEP;
    }
}

fn scan_big_double_chunk(
    pid: u32,
    chunk: &[(usize, &MemoryRegion)],
    target: &BigDouble,
    step: usize,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    for &(region_idx, region) in chunk {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < step {
                break;
            }

            let present_ranges = v2p.get_present_ranges(
                addr,
                addr + chunk_len as u64,
                crate::v2p::PAGE_FLAG_PRESENT,
            )?;

            for (start_addr, end_addr) in present_ranges {
                let range_len = (end_addr - start_addr) as usize;
                if range_len < step {
                    continue;
                }

                if chunk_buf.len() < range_len {
                    chunk_buf.resize(range_len, 0);
                }

                if crate::kpm::read_memory(pid, start_addr, &mut chunk_buf[..range_len]).is_ok() {
                    let block_data = &chunk_buf[..range_len];
                    offsets_buf.clear();
                    scan_buffer_big_double_to(block_data, target, &mut offsets_buf);

                    for &off in &offsets_buf {
                        let match_addr = start_addr + off as u64;
                        let raw_val = target.mantissa.to_bits();

                        matches.push(CompactMatch {
                            address: match_addr,
                            raw_value: raw_val,
                            region_idx: region_idx as u32,
                            value_type: ValueType::Double,
                        });
                    }
                }
            }

            let overlap = if addr + (chunk_len as u64) < region.end {
                step.saturating_sub(1)
            } else {
                0
            };
            addr += (chunk_len.saturating_sub(overlap).max(1)) as u64;
        }
    }

    Ok(matches)
}

pub fn scan_big_double(
    pid: u32,
    regions: &[MemoryRegion],
    target_str: &str,
) -> Result<ScanSession, String> {
    let target = BigDouble::parse(target_str)?;
    let step = 12;

    let indexed_regions: Vec<(usize, &MemoryRegion)> = regions.iter().enumerate().collect();
    let worker_count = if indexed_regions.len() <= 1 {
        1
    } else {
        std::thread::available_parallelism()
            .map(|p| p.get())
            .unwrap_or(4)
            .min(indexed_regions.len())
            .min(8)
    };

    let mut matches = if worker_count <= 1 {
        scan_big_double_chunk(pid, &indexed_regions, &target, step)?
    } else {
        let chunk_size = indexed_regions.len().div_ceil(worker_count);
        let chunks: Vec<&[(usize, &MemoryRegion)]> = indexed_regions.chunks(chunk_size).collect();

        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(chunks.len());
            for chunk in chunks {
                let target_ref = &target;
                let handle = s.spawn(move || scan_big_double_chunk(pid, chunk, target_ref, step));
                handles.push(handle);
            }

            let mut all_matches = Vec::new();
            for handle in handles {
                let partial = handle
                    .join()
                    .map_err(|_| "Worker thread panicked during big double scan".to_string())??;
                all_matches.extend(partial);
            }
            Ok::<Vec<CompactMatch>, String>(all_matches)
        })?
    };

    matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.cmp(&b.value_type))
    });
    matches.dedup_by(|a, b| a.address == b.address && a.value_type == b.value_type);

    Ok(ScanSession {
        regions: regions.to_vec(),
        matches,
        active_types: vec![ValueType::Double],
    })
}

// ============================================================
// Range Scan
// ============================================================

fn scan_range_chunk(
    pid: u32,
    chunk: &[(usize, &MemoryRegion)],
    valid_targets: &[(ValueType, Vec<u8>, Vec<u8>)],
    min_step: usize,
    max_step: usize,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf: Vec<usize> = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    for &(region_idx, region) in chunk {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < min_step {
                break;
            }

            let present_ranges = v2p.get_present_ranges(
                addr,
                addr + chunk_len as u64,
                crate::v2p::PAGE_FLAG_PRESENT,
            )?;

            for (start_addr, end_addr) in present_ranges {
                let range_len = (end_addr - start_addr) as usize;
                if range_len < min_step {
                    continue;
                }

                if chunk_buf.len() < range_len {
                    chunk_buf.resize(range_len, 0);
                }

                if crate::kpm::read_memory(pid, start_addr, &mut chunk_buf[..range_len]).is_ok() {
                    let block_data = &chunk_buf[..range_len];

                    for (vt, min_bytes, max_bytes) in valid_targets {
                        let step = vt.size();
                        if range_len < step {
                            continue;
                        }

                        offsets_buf.clear();
                        scan_range_buffer_to(
                            block_data,
                            min_bytes,
                            max_bytes,
                            *vt,
                            &mut offsets_buf,
                        );

                        for &off in &offsets_buf {
                            let match_addr = start_addr + off as u64;
                            let raw_val = bytes_to_raw_u64(&block_data[off..off + step], *vt);
                            matches.push(CompactMatch {
                                address: match_addr,
                                raw_value: raw_val,
                                region_idx: region_idx as u32,
                                value_type: *vt,
                            });
                        }
                    }
                }
            }

            let overlap = if addr + (chunk_len as u64) < region.end {
                max_step.saturating_sub(1)
            } else {
                0
            };
            addr += (chunk_len.saturating_sub(overlap).max(1)) as u64;
        }
    }

    Ok(matches)
}

fn try_kernel_scan_range(
    pid: u32,
    regions: &[MemoryRegion],
    vt: ValueType,
    min_bytes: &[u8],
    max_bytes: &[u8],
) -> Option<Vec<CompactMatch>> {
    if !kpm::is_feature_supported(kpm::HMKPM_FEATURE_SCAN_KERNEL) {
        return None;
    }

    let (scan_type, criteria) = match vt {
        ValueType::Byte => {
            let min = min_bytes.first().copied()? as i8 as i64;
            let max = max_bytes.first().copied()? as i8 as i64;
            (
                kpm::HMKPM_SCAN_TYPE_I8,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )
        }
        ValueType::Short => {
            let min = i16::from_le_bytes(min_bytes[..2].try_into().ok()?) as i64;
            let max = i16::from_le_bytes(max_bytes[..2].try_into().ok()?) as i64;
            (
                kpm::HMKPM_SCAN_TYPE_I16,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )
        }
        ValueType::Int => {
            let min = i32::from_le_bytes(min_bytes[..4].try_into().ok()?) as i64;
            let max = i32::from_le_bytes(max_bytes[..4].try_into().ok()?) as i64;
            (
                kpm::HMKPM_SCAN_TYPE_I32,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )
        }
        ValueType::Long => {
            let min = i64::from_le_bytes(min_bytes[..8].try_into().ok()?);
            let max = i64::from_le_bytes(max_bytes[..8].try_into().ok()?);
            (
                kpm::HMKPM_SCAN_TYPE_I64,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )
        }
        ValueType::Float => {
            let min = u32::from_le_bytes(min_bytes[..4].try_into().ok()?) as u64;
            let max = u32::from_le_bytes(max_bytes[..4].try_into().ok()?) as u64;
            (
                kpm::HMKPM_SCAN_TYPE_F32,
                kpm::HmkpmScanCriteria {
                    range_u: kpm::HmkpmScanCriteriaRangeU { min, max },
                },
            )
        }
        ValueType::Double => {
            let min = u64::from_le_bytes(min_bytes[..8].try_into().ok()?);
            let max = u64::from_le_bytes(max_bytes[..8].try_into().ok()?);
            (
                kpm::HMKPM_SCAN_TYPE_F64,
                kpm::HmkpmScanCriteria {
                    range_u: kpm::HmkpmScanCriteriaRangeU { min, max },
                },
            )
        }
        ValueType::Float16 => return None,
    };

    let step = vt.size();
    let mut all_matches = Vec::new();
    let max_per_call = kpm::HMKPM_MAX_SCAN_MATCHES;
    let mut pending_ranges = prepare_scan_ranges(regions);
    const BATCH_WINDOW: u64 = 65536;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + step);

    while !pending_ranges.is_empty() {
        let batch_len = pending_ranges.len().min(kpm::HMKPM_MAX_SCAN_RANGES);
        let batch = &pending_ranges[..batch_len];

        match kpm::scan_kernel(
            pid,
            scan_type,
            kpm::HMKPM_SCAN_OP_RANGE,
            step as u8,
            criteria,
            None,
            None,
            batch,
            max_per_call,
        ) {
            Ok((addrs, total_matches_found)) => {
                let addrs_count = addrs.len();

                // Batch-read matched values in 64KB windows to minimize syscall overhead
                let mut i = 0;
                while i < addrs.len() {
                    let base = addrs[i];
                    let mut j = i;
                    while j < addrs.len() && addrs[j] < base + BATCH_WINDOW && addrs[j] >= base {
                        j += 1;
                    }

                    let block_len = (addrs[j - 1] - base) as usize + step;
                    if block_buf.len() < block_len {
                        block_buf.resize(block_len, 0);
                    }

                    let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();
                    let mut single_buf = [0u8; 8];

                    for &addr in &addrs[i..j] {
                        let region_idx = regions
                            .iter()
                            .position(|r| addr >= r.start && addr < r.end)
                            .unwrap_or(0);
                        let raw_val = if read_ok {
                            let off = (addr - base) as usize;
                            if off + step <= block_len {
                                bytes_to_raw_u64(&block_buf[off..off + step], vt)
                            } else {
                                0
                            }
                        } else if kpm::read_memory(pid, addr, &mut single_buf[..step]).is_ok() {
                            bytes_to_raw_u64(&single_buf[..step], vt)
                        } else {
                            0
                        };

                        all_matches.push(CompactMatch {
                            address: addr,
                            raw_value: raw_val,
                            region_idx: region_idx as u32,
                            value_type: vt,
                        });
                    }
                    i = j;
                }

                // If matches were truncated by the kernel buffer limit, resume from last_match_addr + step
                if addrs_count >= max_per_call && total_matches_found > addrs_count as u64 {
                    if let Some(&last_addr) = addrs.last() {
                        let next_start = last_addr.saturating_add(step as u64);
                        pending_ranges = advance_scan_ranges(&pending_ranges, next_start);
                    } else {
                        pending_ranges.drain(..batch_len);
                    }
                } else {
                    pending_ranges.drain(..batch_len);
                }
            }
            Err(e) => {
                logger::warn(
                    "Scanner",
                    &format!("Kernel range scan failed ({e}), falling back to NEON SIMD"),
                );
                return None;
            }
        }
    }

    Some(all_matches)
}

pub fn scan_range(
    pid: u32,
    regions: &[MemoryRegion],
    min_value_str: &str,
    max_value_str: &str,
    value_types: &[ValueType],
) -> Result<ScanSession, String> {
    if value_types.is_empty() {
        return Err("No value types specified".into());
    }

    let mut valid_targets: Vec<(ValueType, Vec<u8>, Vec<u8>)> = Vec::new();
    let mut parse_errors = Vec::new();

    for &vt in value_types {
        let min_res = value_str_to_bytes(min_value_str, vt);
        let max_res = value_str_to_bytes(max_value_str, vt);
        match (min_res, max_res) {
            (Ok(mn), Ok(mx)) => valid_targets.push((vt, mn, mx)),
            (Err(e), _) | (_, Err(e)) => parse_errors.push(format!("{}: {}", vt.as_str(), e)),
        }
    }

    if valid_targets.is_empty() {
        return Err(format!(
            "Range '{min_value_str}..{max_value_str}' is not valid for any selected types: {}",
            parse_errors.join(", ")
        ));
    }

    // Try in-kernel direct range scan if engine mode is Kernel or Auto and all targets are supported
    let engine_mode = get_scan_engine_mode();
    if (engine_mode == ScanEngineMode::Kernel as u8 || engine_mode == ScanEngineMode::Auto as u8)
        && !regions.is_empty()
    {
        let mut kernel_matches = Vec::new();
        let mut kernel_success = true;

        for (vt, min_bytes, max_bytes) in &valid_targets {
            if let Some(partial) = try_kernel_scan_range(pid, regions, *vt, min_bytes, max_bytes) {
                kernel_matches.extend(partial);
            } else {
                kernel_success = false;
                break;
            }
        }

        if kernel_success {
            kernel_matches.sort_by(|a, b| {
                a.address
                    .cmp(&b.address)
                    .then_with(|| a.value_type.cmp(&b.value_type))
            });
            kernel_matches.dedup_by(|a, b| a.address == b.address && a.value_type == b.value_type);

            return Ok(ScanSession {
                regions: regions.to_vec(),
                matches: kernel_matches,
                active_types: value_types.to_vec(),
            });
        }
    }

    let min_step = valid_targets
        .iter()
        .map(|(vt, _, _)| vt.size())
        .min()
        .unwrap_or(4);
    let max_step = valid_targets
        .iter()
        .map(|(vt, _, _)| vt.size())
        .max()
        .unwrap_or(8);

    let indexed_regions: Vec<(usize, &MemoryRegion)> = regions.iter().enumerate().collect();
    let worker_count = if indexed_regions.len() <= 1 {
        1
    } else {
        std::thread::available_parallelism()
            .map(|p| p.get())
            .unwrap_or(4)
            .min(indexed_regions.len())
            .min(8)
    };

    let mut matches = if worker_count <= 1 {
        scan_range_chunk(pid, &indexed_regions, &valid_targets, min_step, max_step)?
    } else {
        let chunk_size = indexed_regions.len().div_ceil(worker_count);
        let chunks: Vec<&[(usize, &MemoryRegion)]> = indexed_regions.chunks(chunk_size).collect();

        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(chunks.len());
            for chunk in chunks {
                let valid_targets_ref = &valid_targets;
                let handle = s.spawn(move || {
                    scan_range_chunk(pid, chunk, valid_targets_ref, min_step, max_step)
                });
                handles.push(handle);
            }

            let mut all_matches = Vec::new();
            for handle in handles {
                let partial = handle
                    .join()
                    .map_err(|_| "Worker thread panicked during range scan".to_string())??;
                all_matches.extend(partial);
            }
            Ok::<Vec<CompactMatch>, String>(all_matches)
        })?
    };

    matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.cmp(&b.value_type))
    });
    matches.dedup_by(|a, b| a.address == b.address && a.value_type == b.value_type);

    Ok(ScanSession {
        regions: regions.to_vec(),
        matches,
        active_types: value_types.to_vec(),
    })
}

pub fn scan_range_buffer_to(
    data: &[u8],
    min: &[u8],
    max: &[u8],
    vtype: ValueType,
    results: &mut Vec<usize>,
) {
    let step = vtype.size();
    if data.len() < step {
        return;
    }

    #[cfg(target_arch = "aarch64")]
    {
        match vtype {
            ValueType::Byte => {
                let mn = min[0] as i8;
                let mx = max[0] as i8;
                scan_range_buffer_i8_neon(data, mn, mx, results);
                return;
            }
            ValueType::Short => {
                let mn = i16::from_le_bytes(min[..2].try_into().unwrap());
                let mx = i16::from_le_bytes(max[..2].try_into().unwrap());
                scan_range_buffer_i16_neon(data, mn, mx, results);
                return;
            }
            ValueType::Int => {
                let mn = i32::from_le_bytes(min[..4].try_into().unwrap());
                let mx = i32::from_le_bytes(max[..4].try_into().unwrap());
                scan_range_buffer_i32_neon(data, mn, mx, results);
                return;
            }
            ValueType::Long => {
                let mn = i64::from_le_bytes(min[..8].try_into().unwrap());
                let mx = i64::from_le_bytes(max[..8].try_into().unwrap());
                scan_range_buffer_i64_neon(data, mn, mx, results);
                return;
            }
            ValueType::Float => {
                let mn = f32::from_le_bytes(min[..4].try_into().unwrap());
                let mx = f32::from_le_bytes(max[..4].try_into().unwrap());
                scan_range_buffer_f32_neon(data, mn, mx, results);
                return;
            }
            ValueType::Double => {
                let mn = f64::from_le_bytes(min[..8].try_into().unwrap());
                let mx = f64::from_le_bytes(max[..8].try_into().unwrap());
                scan_range_buffer_f64_neon(data, mn, mx, results);
                return;
            }
            ValueType::Float16 => {}
        }
    }

    // Scalar fallback
    let mut offset = 0;
    while offset + step <= data.len() {
        if value_in_range(&data[offset..offset + step], min, max, vtype) {
            results.push(offset);
        }
        offset += step;
    }
}

// ============================================================
// Group Scan (Heterogeneous & Uniform)
// ============================================================

pub fn scan_group(
    pid: u32,
    regions: &[MemoryRegion],
    group_spec: &str,
    default_value_type: ValueType,
) -> Result<ScanSession, String> {
    let (target_items, distance) = parse_group_spec(group_spec, default_value_type)?;
    let first_item = &target_items[0];
    let remaining_items = &target_items[1..];
    let first_step = first_item.value_type.size();
    let remaining_size: usize = remaining_items
        .iter()
        .map(|item| item.value_type.size())
        .sum();

    let estimated = regions
        .iter()
        .map(|r| r.end.saturating_sub(r.start))
        .sum::<u64>() as usize
        / first_step
        / 1000;
    let mut matches = Vec::with_capacity(estimated.min(131_072));
    let mut first_match_offsets = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    for (region_idx, region) in regions.iter().enumerate() {
        let mut addr = region.start;
        let mut overlap_buffer: Vec<u8> = Vec::new();

        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < first_step && overlap_buffer.is_empty() {
                break;
            }

            let present_ranges = v2p.get_present_ranges(
                addr,
                addr + chunk_len as u64,
                crate::v2p::PAGE_FLAG_PRESENT,
            )?;

            for (start_addr, end_addr) in present_ranges {
                let range_len = (end_addr - start_addr) as usize;
                if range_len < first_step && overlap_buffer.is_empty() {
                    continue;
                }

                if chunk_buf.len() < range_len {
                    chunk_buf.resize(range_len, 0);
                }

                if crate::kpm::read_memory(pid, start_addr, &mut chunk_buf[..range_len]).is_ok() {
                    let block_slice = &chunk_buf[..range_len];

                    let (data_cow, overlap_len): (std::borrow::Cow<[u8]>, usize) =
                        if overlap_buffer.is_empty() {
                            (std::borrow::Cow::Borrowed(block_slice), 0)
                        } else {
                            let mut full = overlap_buffer.clone();
                            full.extend_from_slice(block_slice);
                            let olen = overlap_buffer.len();
                            (std::borrow::Cow::Owned(full), olen)
                        };
                    let data = &data_cow[..];

                    let effective_start_addr = start_addr.saturating_sub(overlap_len as u64);
                    let processable_len = if addr + chunk_len as u64 == region.end {
                        data.len()
                    } else {
                        data.len().saturating_sub(distance + remaining_size)
                    };

                    first_match_offsets.clear();
                    scan_buffer_to(
                        &data[..processable_len.min(data.len())],
                        &first_item.target_bytes,
                        first_item.value_type,
                        ScanOperator::Equal,
                        &mut first_match_offsets,
                    );

                    for &first_off in &first_match_offsets {
                        let search_end = first_off
                            .saturating_add(distance)
                            .saturating_add(remaining_size)
                            .min(data.len());

                        let mut all_found = true;
                        let mut prev_off = first_off;

                        for rem_item in remaining_items {
                            let mut found = false;
                            let rem_step = rem_item.value_type.size();
                            let mut scan_off = prev_off + first_step;

                            // Step by 1 byte so heterogeneous structs with custom padding or packed layouts are never skipped
                            while scan_off + rem_step <= search_end {
                                if compare_values(
                                    &data[scan_off..scan_off + rem_step],
                                    &rem_item.target_bytes,
                                    rem_item.value_type,
                                    ScanOperator::Equal,
                                ) {
                                    found = true;
                                    prev_off = scan_off;
                                    break;
                                }
                                scan_off += 1;
                            }
                            if !found {
                                all_found = false;
                                break;
                            }
                        }

                        if all_found {
                            let match_addr = effective_start_addr + first_off as u64;
                            let raw_val = bytes_to_raw_u64(
                                &data[first_off..first_off + first_step],
                                first_item.value_type,
                            );
                            matches.push(CompactMatch {
                                address: match_addr,
                                raw_value: raw_val,
                                region_idx: region_idx as u32,
                                value_type: first_item.value_type,
                            });
                        }
                    }

                    if start_addr + (range_len as u64) < region.end {
                        let overlap_start = data.len().saturating_sub(distance + remaining_size);
                        overlap_buffer = data[overlap_start..].to_vec();
                    } else {
                        overlap_buffer.clear();
                    }
                }
            }

            addr += chunk_len as u64;
        }
    }

    Ok(ScanSession {
        regions: regions.to_vec(),
        matches,
        active_types: vec![first_item.value_type],
    })
}

// ============================================================
// Next Scan / Refine Filtering
// ============================================================

pub fn filter_matches(
    pid: u32,
    session: &ScanSession,
    target_value_str: &str,
    operator: ScanOperator,
) -> Result<ScanSession, String> {
    const BATCH_WINDOW: u64 = 65536;
    let items = &session.matches;
    let mut updated_matches = Vec::with_capacity(items.len());

    let max_step = session
        .active_types
        .iter()
        .map(|t| t.size())
        .max()
        .unwrap_or(8);

    // Precompute target bytes per active type once before the loop (OPT-1)
    let mut targets_by_type: std::collections::HashMap<ValueType, Option<Vec<u8>>> =
        std::collections::HashMap::with_capacity(session.active_types.len());
    for &vt in &session.active_types {
        let tb = if (operator.is_relative() && target_value_str.trim().is_empty())
            || operator == ScanOperator::Update
        {
            Some(Vec::new())
        } else {
            value_str_to_bytes(target_value_str, vt).ok()
        };
        targets_by_type.insert(vt, tb);
    }

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + max_step);

    while i < items.len() {
        let base = items[i].address;
        let region_idx = items[i].region_idx;
        let mut j = i;

        while j < items.len()
            && items[j].address < base + BATCH_WINDOW
            && items[j].address >= base
            && items[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (items[j - 1].address - base) as usize + max_step;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &items[i..j] {
            let step = m.value_type.size();
            let mut single_buf = [0u8; 8];
            let current_bytes = if read_ok {
                let off = (m.address - base) as usize;
                if off + step <= block_len {
                    &block_buf[off..off + step]
                } else {
                    continue;
                }
            } else {
                if kpm::read_memory(pid, m.address, &mut single_buf[..step]).is_ok() {
                    &single_buf[..step]
                } else {
                    continue;
                }
            };

            let target_bytes = match targets_by_type.get(&m.value_type) {
                Some(Some(b)) => b.as_slice(),
                _ => continue,
            };

            let matches_cond = if operator == ScanOperator::Update {
                true
            } else if operator.is_relative() {
                compare_relative_values(
                    current_bytes,
                    m.raw_value,
                    target_bytes,
                    m.value_type,
                    operator,
                )
            } else {
                compare_values(current_bytes, target_bytes, m.value_type, operator)
            };

            if matches_cond {
                let new_raw = bytes_to_raw_u64(current_bytes, m.value_type);
                updated_matches.push(CompactMatch {
                    address: m.address,
                    raw_value: new_raw,
                    region_idx: m.region_idx,
                    value_type: m.value_type,
                });
            }
        }

        i = j;
    }

    Ok(ScanSession {
        regions: session.regions.clone(),
        matches: updated_matches,
        active_types: session.active_types.clone(),
    })
}

pub fn filter_range_matches(
    pid: u32,
    session: &ScanSession,
    min_str: &str,
    max_str: &str,
) -> Result<ScanSession, String> {
    const BATCH_WINDOW: u64 = 65536;
    let items = &session.matches;
    let mut updated_matches = Vec::with_capacity(items.len());

    let max_step = session
        .active_types
        .iter()
        .map(|t| t.size())
        .max()
        .unwrap_or(8);

    type RangeBytes = Option<(Vec<u8>, Vec<u8>)>;
    let mut ranges_by_type: std::collections::HashMap<ValueType, RangeBytes> =
        std::collections::HashMap::with_capacity(session.active_types.len());
    for &vt in &session.active_types {
        let r = match (
            value_str_to_bytes(min_str, vt),
            value_str_to_bytes(max_str, vt),
        ) {
            (Ok(mn), Ok(mx)) => Some((mn, mx)),
            _ => None,
        };
        ranges_by_type.insert(vt, r);
    }

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + max_step);

    while i < items.len() {
        let base = items[i].address;
        let region_idx = items[i].region_idx;
        let mut j = i;

        while j < items.len()
            && items[j].address < base + BATCH_WINDOW
            && items[j].address >= base
            && items[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (items[j - 1].address - base) as usize + max_step;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &items[i..j] {
            let step = m.value_type.size();
            let mut single_buf = [0u8; 8];
            let current_bytes = if read_ok {
                let off = (m.address - base) as usize;
                if off + step <= block_len {
                    &block_buf[off..off + step]
                } else {
                    continue;
                }
            } else {
                if kpm::read_memory(pid, m.address, &mut single_buf[..step]).is_ok() {
                    &single_buf[..step]
                } else {
                    continue;
                }
            };

            let (min_bytes, max_bytes) = match ranges_by_type.get(&m.value_type) {
                Some(Some((mn, mx))) => (mn.as_slice(), mx.as_slice()),
                _ => continue,
            };

            if value_in_range(current_bytes, min_bytes, max_bytes, m.value_type) {
                let new_raw = bytes_to_raw_u64(current_bytes, m.value_type);
                updated_matches.push(CompactMatch {
                    address: m.address,
                    raw_value: new_raw,
                    region_idx: m.region_idx,
                    value_type: m.value_type,
                });
            }
        }

        i = j;
    }

    Ok(ScanSession {
        regions: session.regions.clone(),
        matches: updated_matches,
        active_types: session.active_types.clone(),
    })
}

pub fn filter_obscured_matches(
    pid: u32,
    session: &ScanSession,
    target_value_str: &str,
    obscured_type: ObscuredType,
) -> Result<ScanSession, String> {
    let s = target_value_str.trim();
    let (target_bits32, target_bits64) = match obscured_type {
        ObscuredType::ObscuredInt => {
            let v: i32 = parse_int_flexible(s)?;
            (Some(v as u32), None)
        }
        ObscuredType::ObscuredFloat => {
            let v: f32 = s.parse().map_err(|e| format!("Invalid float '{s}': {e}"))?;
            (Some(v.to_bits()), None)
        }
        ObscuredType::ObscuredDouble => {
            let v: f64 = s
                .parse()
                .map_err(|e| format!("Invalid double '{s}': {e}"))?;
            (None, Some(v.to_bits()))
        }
        ObscuredType::ObscuredLong => {
            let v: i64 = parse_int_flexible(s)?;
            (None, Some(v as u64))
        }
    };

    let step = obscured_type.size();
    const BATCH_WINDOW: u64 = 65536;
    let items = &session.matches;
    let mut updated_matches = Vec::with_capacity(items.len());

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + step);

    while i < items.len() {
        let base = items[i].address;
        let region_idx = items[i].region_idx;
        let mut j = i;

        while j < items.len()
            && items[j].address < base + BATCH_WINDOW
            && items[j].address >= base
            && items[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (items[j - 1].address - base) as usize + step;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &items[i..j] {
            let mut single_buf = [0u8; 16];
            let buf_slice: &[u8] = if read_ok {
                let off = (m.address - base) as usize;
                if off + step <= block_len {
                    &block_buf[off..off + step]
                } else {
                    continue;
                }
            } else if kpm::read_memory(pid, m.address, &mut single_buf[..step]).is_ok() {
                &single_buf[..step]
            } else {
                continue;
            };

            let matched = if let Some(t32) = target_bits32 {
                let k = u32::from_le_bytes(buf_slice[..4].try_into().unwrap());
                let v = u32::from_le_bytes(buf_slice[4..8].try_into().unwrap());
                (k ^ v) == t32
            } else if let Some(t64) = target_bits64 {
                let k = u64::from_le_bytes(buf_slice[..8].try_into().unwrap());
                let v = u64::from_le_bytes(buf_slice[8..16].try_into().unwrap());
                (k ^ v) == t64
            } else {
                false
            };

            if matched {
                let raw_val = if let Some(t32) = target_bits32 {
                    t32 as u64
                } else {
                    target_bits64.unwrap_or(0)
                };
                updated_matches.push(CompactMatch {
                    address: m.address,
                    raw_value: raw_val,
                    region_idx: m.region_idx,
                    value_type: m.value_type,
                });
            }
        }

        i = j;
    }

    Ok(ScanSession {
        regions: session.regions.clone(),
        matches: updated_matches,
        active_types: session.active_types.clone(),
    })
}

pub fn filter_big_double_matches(
    pid: u32,
    session: &ScanSession,
    target_str: &str,
) -> Result<ScanSession, String> {
    let target = BigDouble::parse(target_str)?;
    const BATCH_WINDOW: u64 = 65536;
    const MAX_STRUCT_SIZE: usize = 16;
    let items = &session.matches;
    let mut updated_matches = Vec::with_capacity(items.len());

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + MAX_STRUCT_SIZE);

    while i < items.len() {
        let base = items[i].address;
        let region_idx = items[i].region_idx;
        let mut j = i;

        while j < items.len()
            && items[j].address < base + BATCH_WINDOW
            && items[j].address >= base
            && items[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (items[j - 1].address - base) as usize + MAX_STRUCT_SIZE;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &items[i..j] {
            let mut single_buf = [0u8; 16];
            let buf_slice: &[u8] = if read_ok {
                let off = (m.address - base) as usize;
                if off + MAX_STRUCT_SIZE <= block_len {
                    &block_buf[off..off + MAX_STRUCT_SIZE]
                } else if off + 12 <= block_len {
                    &block_buf[off..off + 12]
                } else {
                    continue;
                }
            } else if kpm::read_memory(pid, m.address, &mut single_buf).is_ok() {
                &single_buf
            } else {
                continue;
            };

            let m_val = f64::from_le_bytes(buf_slice[..8].try_into().unwrap());
            let exp64 = if buf_slice.len() >= 16 {
                i64::from_le_bytes(buf_slice[8..16].try_into().unwrap())
            } else {
                i64::MIN
            };
            let exp32 = if buf_slice.len() >= 12 {
                i32::from_le_bytes(buf_slice[8..12].try_into().unwrap()) as i64
            } else {
                i64::MIN
            };

            if (m_val - target.mantissa).abs() < 1e-5
                && (exp64 == target.exponent || exp32 == target.exponent)
            {
                updated_matches.push(CompactMatch {
                    address: m.address,
                    raw_value: target.mantissa.to_bits(),
                    region_idx: m.region_idx,
                    value_type: m.value_type,
                });
            }
        }

        i = j;
    }

    Ok(ScanSession {
        regions: session.regions.clone(),
        matches: updated_matches,
        active_types: session.active_types.clone(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_scan_buffer_i32() {
        let mut data = vec![0u8; 64];
        let target = 1337i32;
        data[8..12].copy_from_slice(&target.to_le_bytes());
        data[24..28].copy_from_slice(&target.to_le_bytes());

        let mut results = Vec::new();
        scan_buffer_to(
            &data,
            &target.to_le_bytes(),
            ValueType::Int,
            ScanOperator::Equal,
            &mut results,
        );
        assert_eq!(results, vec![8, 24]);
    }

    #[test]
    fn test_scan_buffer_float16() {
        let mut data = vec![0u8; 32];
        let h_val = f32_to_f16(123.5);
        data[4..6].copy_from_slice(&h_val.to_le_bytes());
        data[16..18].copy_from_slice(&h_val.to_le_bytes());

        let mut results = Vec::new();
        scan_buffer_to(
            &data,
            &h_val.to_le_bytes(),
            ValueType::Float16,
            ScanOperator::Equal,
            &mut results,
        );
        assert_eq!(results, vec![4, 16]);
    }

    #[test]
    fn test_scan_buffer_obscured_int() {
        let mut data = vec![0u8; 64];
        let real_val = 999999i32;
        let key1 = 0x12345678u32;
        let hidden1 = (real_val as u32) ^ key1;

        data[8..12].copy_from_slice(&key1.to_le_bytes());
        data[12..16].copy_from_slice(&hidden1.to_le_bytes());

        let key2 = 0xAABBCCDDu32;
        let hidden2 = (real_val as u32) ^ key2;
        data[24..28].copy_from_slice(&key2.to_le_bytes());
        data[28..32].copy_from_slice(&hidden2.to_le_bytes());

        let mut results = Vec::new();
        scan_buffer_obscured32(&data, real_val as u32, &mut results);
        assert_eq!(results, vec![8, 24]);
    }

    #[test]
    fn test_scan_buffer_big_double() {
        let mut data = vec![0u8; 64];
        let target = BigDouble {
            mantissa: 1.5,
            exponent: 6,
        };

        // Insert at offset 16 (layout A: f64 + i64)
        data[16..24].copy_from_slice(&1.5f64.to_le_bytes());
        data[24..32].copy_from_slice(&6i64.to_le_bytes());

        let mut results = Vec::new();
        scan_buffer_big_double_to(&data, &target, &mut results);
        assert_eq!(results, vec![16]);
    }

    #[test]
    fn test_scan_buffer_obscured_odd_offsets() {
        let mut data = vec![0u8; 64];
        let real_val = 123456i32;
        let key = 0x55AA55AAu32;
        let hidden = (real_val as u32) ^ key;

        // Place at odd-4 offset 4 (key at 4..8, hidden at 8..12)
        data[4..8].copy_from_slice(&key.to_le_bytes());
        data[8..12].copy_from_slice(&hidden.to_le_bytes());

        // Place at odd-4 offset 20 (key at 20..24, hidden at 24..28)
        let key2 = 0x12344321u32;
        let hidden2 = (real_val as u32) ^ key2;
        data[20..24].copy_from_slice(&key2.to_le_bytes());
        data[24..28].copy_from_slice(&hidden2.to_le_bytes());

        let mut results = Vec::new();
        scan_buffer_obscured32(&data, real_val as u32, &mut results);
        assert!(results.contains(&4));
        assert!(results.contains(&20));
    }

    #[test]
    fn test_scan_group_heterogeneous_unaligned_step() {
        let mut data = [0u8; 128];
        // Insert Byte: 1 at 0, Int: 500 at 4
        data[0] = 1;
        data[4..8].copy_from_slice(&500i32.to_le_bytes());

        let (items, dist) = parse_group_spec("byte:1; int:500 : 16", ValueType::Int).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(dist, 16);
    }

    #[test]
    fn test_scan_buffer_range_i32() {
        let mut data = vec![0u8; 64];
        let val1 = 150i32;
        let val2 = 250i32;
        let val_out = 500i32;
        data[8..12].copy_from_slice(&val1.to_le_bytes());
        data[24..28].copy_from_slice(&val2.to_le_bytes());
        data[40..44].copy_from_slice(&val_out.to_le_bytes());

        let min_b = 100i32.to_le_bytes();
        let max_b = 300i32.to_le_bytes();
        let mut results = Vec::new();
        scan_range_buffer_to(&data, &min_b, &max_b, ValueType::Int, &mut results);
        assert_eq!(results, vec![8, 24]);
    }

    #[test]
    fn test_scan_buffer_range_f32() {
        let mut data = vec![0u8; 64];
        let val1 = 10.5f32;
        let val2 = 19.9f32;
        let val_out = 35.0f32;
        data[8..12].copy_from_slice(&val1.to_le_bytes());
        data[24..28].copy_from_slice(&val2.to_le_bytes());
        data[40..44].copy_from_slice(&val_out.to_le_bytes());

        let min_b = 10.0f32.to_le_bytes();
        let max_b = 20.0f32.to_le_bytes();
        let mut results = Vec::new();
        scan_range_buffer_to(&data, &min_b, &max_b, ValueType::Float, &mut results);
        assert_eq!(results, vec![8, 24]);
    }

    #[test]
    fn test_prepare_scan_ranges() {
        let regions = vec![
            MemoryRegion {
                start: 0x3000,
                end: 0x4000,
                permissions: "rw-p".to_string(),
                offset: 0,
                path: "b".to_string(),
                merged_count: 1,
            },
            MemoryRegion {
                start: 0x1000,
                end: 0x2000,
                permissions: "rw-p".to_string(),
                offset: 0,
                path: "a".to_string(),
                merged_count: 1,
            },
            MemoryRegion {
                start: 0x2000,
                end: 0x2800,
                permissions: "rw-p".to_string(),
                offset: 0,
                path: "merged_with_prev".to_string(),
                merged_count: 1,
            },
            MemoryRegion {
                start: 0x5000,
                end: 0x5000, // zero size, should be ignored
                permissions: "rw-p".to_string(),
                offset: 0,
                path: "empty".to_string(),
                merged_count: 1,
            },
        ];

        let prepared = prepare_scan_ranges(&regions);
        assert_eq!(prepared.len(), 2);
        assert_eq!(prepared[0].start_va, 0x1000);
        assert_eq!(prepared[0].size, 0x1800); // 0x1000..0x2800 merged
        assert_eq!(prepared[1].start_va, 0x3000);
        assert_eq!(prepared[1].size, 0x1000);
    }

    #[test]
    fn test_advance_scan_ranges() {
        let ranges = vec![
            kpm::HmkpmScanRange {
                start_va: 0x1000,
                size: 0x1000, // 0x1000..0x2000
            },
            kpm::HmkpmScanRange {
                start_va: 0x3000,
                size: 0x2000, // 0x3000..0x5000
            },
            kpm::HmkpmScanRange {
                start_va: 0x6000,
                size: 0x1000, // 0x6000..0x7000
            },
        ];

        // Advance to mid-second-range: 0x3800
        let advanced = advance_scan_ranges(&ranges, 0x3800);
        assert_eq!(advanced.len(), 2);
        assert_eq!(advanced[0].start_va, 0x3800);
        assert_eq!(advanced[0].size, 0x1800); // 0x3800..0x5000
        assert_eq!(advanced[1].start_va, 0x6000);
        assert_eq!(advanced[1].size, 0x1000);

        // Advance to exact end of second-range: 0x5000
        let advanced2 = advance_scan_ranges(&ranges, 0x5000);
        assert_eq!(advanced2.len(), 1);
        assert_eq!(advanced2[0].start_va, 0x6000);
        assert_eq!(advanced2[0].size, 0x1000);

        // Advance past all ranges: 0x8000
        let advanced3 = advance_scan_ranges(&ranges, 0x8000);
        assert!(advanced3.is_empty());
    }

    #[test]
    fn test_multitype_sort_and_dedup() {
        let mut matches = vec![
            CompactMatch {
                address: 0x1000,
                raw_value: 100,
                region_idx: 0,
                value_type: ValueType::Int,
            },
            CompactMatch {
                address: 0x1000,
                raw_value: 100,
                region_idx: 0,
                value_type: ValueType::Float,
            },
            CompactMatch {
                address: 0x1000,
                raw_value: 100,
                region_idx: 0,
                value_type: ValueType::Int,
            },
            CompactMatch {
                address: 0x1000,
                raw_value: 100,
                region_idx: 0,
                value_type: ValueType::Float,
            },
        ];

        matches.sort_by(|a, b| {
            a.address
                .cmp(&b.address)
                .then_with(|| a.value_type.cmp(&b.value_type))
        });
        matches.dedup_by(|a, b| a.address == b.address && a.value_type == b.value_type);

        assert_eq!(matches.len(), 2);
        assert_eq!(matches[0].address, 0x1000);
        assert_eq!(matches[0].value_type, ValueType::Int);
        assert_eq!(matches[1].address, 0x1000);
        assert_eq!(matches[1].value_type, ValueType::Float);
    }
}
