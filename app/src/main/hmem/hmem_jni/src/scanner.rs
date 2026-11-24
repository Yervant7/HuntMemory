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
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicUsize, Ordering};

#[repr(u8)]
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum ScanEngineMode {
    RustNeon = 0,
    Kernel = 1,
    Auto = 2,
}

static SCAN_ENGINE_MODE: AtomicU8 = AtomicU8::new(ScanEngineMode::RustNeon as u8);
static SCAN_CANCELLED: AtomicBool = AtomicBool::new(false);

/// Hard memory safety cap for `ScanOperator::Unknown` initial scans (~96 MiB at 24 B/match).
pub const MAX_UNKNOWN_SCAN_MATCHES: usize = 4_000_000;

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

/// Signals any in-flight scan or filter operation to terminate cooperatively.
pub fn cancel_scan() {
    SCAN_CANCELLED.store(true, Ordering::Release);
}

/// Clears the cooperative scan cancellation flag at the start of a new scan/filter.
pub fn reset_scan_cancel() {
    SCAN_CANCELLED.store(false, Ordering::Release);
}

/// Returns true if cancellation has been requested for the active scan/filter.
#[inline]
pub fn is_scan_cancelled() -> bool {
    SCAN_CANCELLED.load(Ordering::Acquire)
}

const CHUNK_SIZE: usize = 4 * 1024 * 1024; // 4MB chunks per read

/// Builds a start-sorted index of `(start, end, original_index)` for O(log R) region lookups.
pub(crate) fn build_sorted_region_index(regions: &[MemoryRegion]) -> Vec<(u64, u64, u32)> {
    let mut idx: Vec<(u64, u64, u32)> = regions
        .iter()
        .enumerate()
        .map(|(i, r)| (r.start, r.end, i as u32))
        .collect();
    idx.sort_unstable_by_key(|&(start, _, _)| start);
    idx
}

/// Resolves the original `MemoryRegion` index containing `addr` in O(log R) time.
#[inline]
pub(crate) fn find_region_idx(sorted_regions: &[(u64, u64, u32)], addr: u64) -> u32 {
    let pos = sorted_regions.partition_point(|&(start, _, _)| start <= addr);
    if pos > 0 {
        let (start, end, orig_idx) = sorted_regions[pos - 1];
        if addr >= start && addr < end {
            return orig_idx;
        }
    }
    0
}

/// A fixed-size work-stealing slice of a `MemoryRegion`.
///
/// Workers read `[read_start, read_end)` (bounded to `<= CHUNK_SIZE`) and only emit matches
/// whose start address lies in `[emit_start, emit_end)`, preventing boundary duplicates and
/// keeping consecutive chunks 4 KiB-aligned.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct ScanTask {
    pub region_idx: usize,
    pub emit_start: u64,
    pub emit_end: u64,
    pub read_start: u64,
    pub read_end: u64,
}

/// Splits memory regions into byte-balanced work-stealing tasks with exact boundary overlap.
pub(crate) fn build_scan_tasks(
    regions: &[MemoryRegion],
    chunk_size: usize,
    overlap: usize,
) -> Vec<ScanTask> {
    const PAGE_ALIGN: usize = 4096;
    let effective_chunk = chunk_size.max(PAGE_ALIGN);
    let effective_overlap = overlap.min(effective_chunk / 2);
    let stride = if effective_overlap == 0 {
        effective_chunk
    } else {
        let raw = effective_chunk - effective_overlap;
        if raw >= PAGE_ALIGN {
            raw & !(PAGE_ALIGN - 1)
        } else {
            raw.max(1)
        }
    } as u64;

    let mut tasks = Vec::with_capacity(regions.len());
    for (region_idx, region) in regions.iter().enumerate() {
        if region.end <= region.start {
            continue;
        }
        let mut emit_start = region.start;
        while emit_start < region.end {
            let emit_end = emit_start.saturating_add(stride).min(region.end);
            let read_end = if emit_end < region.end {
                emit_end
                    .saturating_add(effective_overlap as u64)
                    .min(region.end)
            } else {
                region.end
            };
            tasks.push(ScanTask {
                region_idx,
                emit_start,
                emit_end,
                read_start: emit_start,
                read_end,
            });
            emit_start = emit_end;
        }
    }
    tasks
}

/// Atomically reserves up to `requested` slots against a global `cap` in a lock-free CAS loop.
#[inline]
pub(crate) fn reserve_unknown_slots(counter: &AtomicUsize, requested: usize, cap: usize) -> usize {
    if requested == 0 {
        return 0;
    }
    let mut curr = counter.load(Ordering::Acquire);
    loop {
        if curr >= cap {
            return 0;
        }
        let take = requested.min(cap - curr);
        match counter.compare_exchange_weak(curr, curr + take, Ordering::AcqRel, Ordering::Acquire)
        {
            Ok(_) => return take,
            Err(actual) => curr = actual,
        }
    }
}

/// Computes optimal worker thread count for a slice of work-stealing tasks.
#[inline]
fn optimal_worker_count(task_count: usize) -> usize {
    if task_count <= 1 {
        1
    } else {
        std::thread::available_parallelism()
            .map(|p| p.get())
            .unwrap_or(4)
            .min(task_count)
            .min(8)
    }
}

/// Prepares, sorts, and merges memory regions into non-overlapping scan ranges for kernel scanning.
pub(crate) fn prepare_scan_ranges(regions: &[MemoryRegion]) -> Vec<kpm::HmkpmScanRange> {
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

/// Maps a `ValueType` to its signed/floating HMKPM v3.0.0 scan type code.
pub(crate) fn value_type_to_kernel_scan_type(vt: ValueType) -> Result<u8, String> {
    match vt {
        ValueType::Byte => Ok(kpm::HMKPM_SCAN_TYPE_I8),
        ValueType::Short => Ok(kpm::HMKPM_SCAN_TYPE_I16),
        ValueType::Int => Ok(kpm::HMKPM_SCAN_TYPE_I32),
        ValueType::Long => Ok(kpm::HMKPM_SCAN_TYPE_I64),
        ValueType::Float => Ok(kpm::HMKPM_SCAN_TYPE_F32),
        ValueType::Double => Ok(kpm::HMKPM_SCAN_TYPE_F64),
        ValueType::Float16 => Err("Kernel scan does not support value type 'float16'".to_string()),
    }
}

/// Converts a scalar `(vt, target_bytes, operator)` into `(scan_type, scan_op, criteria)`
/// for `HMKPM_MAGIC_SCAN_KERNEL`.
///
/// Returns `Ok(None)` when the comparison range is mathematically empty (e.g., `i32 > i32::MAX`
/// or ordered float comparison against NaN), avoiding a wasted syscall.
pub(crate) fn operator_to_kernel_criteria(
    vt: ValueType,
    target_bytes: &[u8],
    op: ScanOperator,
) -> Result<Option<(u8, u8, kpm::HmkpmScanCriteria)>, String> {
    let scan_type = value_type_to_kernel_scan_type(vt)?;

    match op {
        ScanOperator::Equal | ScanOperator::NotEqual => {
            let scan_op = if op == ScanOperator::Equal {
                kpm::HMKPM_SCAN_OP_EXACT
            } else {
                kpm::HMKPM_SCAN_OP_NEQ
            };

            let raw_val = match vt {
                ValueType::Byte => target_bytes
                    .first()
                    .copied()
                    .ok_or_else(|| "Invalid Byte target bytes".to_string())?
                    as u64,
                ValueType::Short => u16::from_le_bytes(
                    target_bytes
                        .get(..2)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Short target bytes".to_string())?,
                ) as u64,
                ValueType::Int => u32::from_le_bytes(
                    target_bytes
                        .get(..4)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Int target bytes".to_string())?,
                ) as u64,
                ValueType::Long => u64::from_le_bytes(
                    target_bytes
                        .get(..8)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Long target bytes".to_string())?,
                ),
                ValueType::Float => {
                    let f = f32::from_le_bytes(
                        target_bytes
                            .get(..4)
                            .and_then(|s| s.try_into().ok())
                            .ok_or_else(|| "Invalid Float target bytes".to_string())?,
                    );
                    if f.is_nan() {
                        if op == ScanOperator::Equal {
                            return Ok(None);
                        }
                        return Err("Kernel float NEQ scan rejects NaN target".to_string());
                    }
                    f.to_bits() as u64
                }
                ValueType::Double => {
                    let f = f64::from_le_bytes(
                        target_bytes
                            .get(..8)
                            .and_then(|s| s.try_into().ok())
                            .ok_or_else(|| "Invalid Double target bytes".to_string())?,
                    );
                    if f.is_nan() {
                        if op == ScanOperator::Equal {
                            return Ok(None);
                        }
                        return Err("Kernel double NEQ scan rejects NaN target".to_string());
                    }
                    f.to_bits()
                }
                ValueType::Float16 => unreachable!(),
            };

            Ok(Some((
                scan_type,
                scan_op,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact {
                        val: raw_val,
                        mask: 0,
                    },
                },
            )))
        }
        ScanOperator::Greater
        | ScanOperator::Less
        | ScanOperator::GreaterEqual
        | ScanOperator::LessEqual => match vt {
            ValueType::Byte => {
                let v = target_bytes
                    .first()
                    .copied()
                    .ok_or_else(|| "Invalid Byte target bytes".to_string())?
                    as i8;
                let (lo, hi) = match op {
                    ScanOperator::Greater => match v.checked_add(1) {
                        Some(lo) => (lo, i8::MAX),
                        None => return Ok(None),
                    },
                    ScanOperator::GreaterEqual => (v, i8::MAX),
                    ScanOperator::Less => match v.checked_sub(1) {
                        Some(hi) => (i8::MIN, hi),
                        None => return Ok(None),
                    },
                    ScanOperator::LessEqual => (i8::MIN, v),
                    _ => unreachable!(),
                };
                Ok(Some((
                    scan_type,
                    kpm::HMKPM_SCAN_OP_RANGE,
                    kpm::HmkpmScanCriteria {
                        range_i: kpm::HmkpmScanCriteriaRangeI {
                            min: lo as i64,
                            max: hi as i64,
                        },
                    },
                )))
            }
            ValueType::Short => {
                let v = i16::from_le_bytes(
                    target_bytes
                        .get(..2)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Short target bytes".to_string())?,
                );
                let (lo, hi) = match op {
                    ScanOperator::Greater => match v.checked_add(1) {
                        Some(lo) => (lo, i16::MAX),
                        None => return Ok(None),
                    },
                    ScanOperator::GreaterEqual => (v, i16::MAX),
                    ScanOperator::Less => match v.checked_sub(1) {
                        Some(hi) => (i16::MIN, hi),
                        None => return Ok(None),
                    },
                    ScanOperator::LessEqual => (i16::MIN, v),
                    _ => unreachable!(),
                };
                Ok(Some((
                    scan_type,
                    kpm::HMKPM_SCAN_OP_RANGE,
                    kpm::HmkpmScanCriteria {
                        range_i: kpm::HmkpmScanCriteriaRangeI {
                            min: lo as i64,
                            max: hi as i64,
                        },
                    },
                )))
            }
            ValueType::Int => {
                let v = i32::from_le_bytes(
                    target_bytes
                        .get(..4)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Int target bytes".to_string())?,
                );
                let (lo, hi) = match op {
                    ScanOperator::Greater => match v.checked_add(1) {
                        Some(lo) => (lo, i32::MAX),
                        None => return Ok(None),
                    },
                    ScanOperator::GreaterEqual => (v, i32::MAX),
                    ScanOperator::Less => match v.checked_sub(1) {
                        Some(hi) => (i32::MIN, hi),
                        None => return Ok(None),
                    },
                    ScanOperator::LessEqual => (i32::MIN, v),
                    _ => unreachable!(),
                };
                Ok(Some((
                    scan_type,
                    kpm::HMKPM_SCAN_OP_RANGE,
                    kpm::HmkpmScanCriteria {
                        range_i: kpm::HmkpmScanCriteriaRangeI {
                            min: lo as i64,
                            max: hi as i64,
                        },
                    },
                )))
            }
            ValueType::Long => {
                let v = i64::from_le_bytes(
                    target_bytes
                        .get(..8)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Long target bytes".to_string())?,
                );
                let (lo, hi) = match op {
                    ScanOperator::Greater => match v.checked_add(1) {
                        Some(lo) => (lo, i64::MAX),
                        None => return Ok(None),
                    },
                    ScanOperator::GreaterEqual => (v, i64::MAX),
                    ScanOperator::Less => match v.checked_sub(1) {
                        Some(hi) => (i64::MIN, hi),
                        None => return Ok(None),
                    },
                    ScanOperator::LessEqual => (i64::MIN, v),
                    _ => unreachable!(),
                };
                Ok(Some((
                    scan_type,
                    kpm::HMKPM_SCAN_OP_RANGE,
                    kpm::HmkpmScanCriteria {
                        range_i: kpm::HmkpmScanCriteriaRangeI { min: lo, max: hi },
                    },
                )))
            }
            ValueType::Float => {
                let v = f32::from_le_bytes(
                    target_bytes
                        .get(..4)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Float target bytes".to_string())?,
                );
                if v.is_nan() {
                    return Ok(None);
                }
                let (lo, hi) = match op {
                    ScanOperator::Greater => {
                        if v == f32::INFINITY {
                            return Ok(None);
                        }
                        (v.next_up(), f32::INFINITY)
                    }
                    ScanOperator::GreaterEqual => (v, f32::INFINITY),
                    ScanOperator::Less => {
                        if v == f32::NEG_INFINITY {
                            return Ok(None);
                        }
                        (f32::NEG_INFINITY, v.next_down())
                    }
                    ScanOperator::LessEqual => (f32::NEG_INFINITY, v),
                    _ => unreachable!(),
                };
                Ok(Some((
                    scan_type,
                    kpm::HMKPM_SCAN_OP_RANGE,
                    kpm::HmkpmScanCriteria {
                        range_u: kpm::HmkpmScanCriteriaRangeU {
                            min: lo.to_bits() as u64,
                            max: hi.to_bits() as u64,
                        },
                    },
                )))
            }
            ValueType::Double => {
                let v = f64::from_le_bytes(
                    target_bytes
                        .get(..8)
                        .and_then(|s| s.try_into().ok())
                        .ok_or_else(|| "Invalid Double target bytes".to_string())?,
                );
                if v.is_nan() {
                    return Ok(None);
                }
                let (lo, hi) = match op {
                    ScanOperator::Greater => {
                        if v == f64::INFINITY {
                            return Ok(None);
                        }
                        (v.next_up(), f64::INFINITY)
                    }
                    ScanOperator::GreaterEqual => (v, f64::INFINITY),
                    ScanOperator::Less => {
                        if v == f64::NEG_INFINITY {
                            return Ok(None);
                        }
                        (f64::NEG_INFINITY, v.next_down())
                    }
                    ScanOperator::LessEqual => (f64::NEG_INFINITY, v),
                    _ => unreachable!(),
                };
                Ok(Some((
                    scan_type,
                    kpm::HMKPM_SCAN_OP_RANGE,
                    kpm::HmkpmScanCriteria {
                        range_u: kpm::HmkpmScanCriteriaRangeU {
                            min: lo.to_bits(),
                            max: hi.to_bits(),
                        },
                    },
                )))
            }
            ValueType::Float16 => unreachable!(),
        },
        _ => Err(format!(
            "Kernel scan does not support operator '{op:?}'; requires Auto or RustNeon mode"
        )),
    }
}

/// Converts inclusive `(min_bytes, max_bytes)` range endpoints into kernel scan criteria.
pub(crate) fn range_bytes_to_kernel_criteria(
    vt: ValueType,
    min_bytes: &[u8],
    max_bytes: &[u8],
) -> Result<Option<(u8, kpm::HmkpmScanCriteria)>, String> {
    let scan_type = value_type_to_kernel_scan_type(vt)?;
    match vt {
        ValueType::Byte => {
            let min = min_bytes
                .first()
                .copied()
                .ok_or_else(|| "Invalid Byte range min".to_string())? as i8
                as i64;
            let max = max_bytes
                .first()
                .copied()
                .ok_or_else(|| "Invalid Byte range max".to_string())? as i8
                as i64;
            Ok(Some((
                scan_type,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )))
        }
        ValueType::Short => {
            let min = i16::from_le_bytes(
                min_bytes
                    .get(..2)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Short range min".to_string())?,
            ) as i64;
            let max = i16::from_le_bytes(
                max_bytes
                    .get(..2)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Short range max".to_string())?,
            ) as i64;
            Ok(Some((
                scan_type,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )))
        }
        ValueType::Int => {
            let min = i32::from_le_bytes(
                min_bytes
                    .get(..4)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Int range min".to_string())?,
            ) as i64;
            let max = i32::from_le_bytes(
                max_bytes
                    .get(..4)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Int range max".to_string())?,
            ) as i64;
            Ok(Some((
                scan_type,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )))
        }
        ValueType::Long => {
            let min = i64::from_le_bytes(
                min_bytes
                    .get(..8)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Long range min".to_string())?,
            );
            let max = i64::from_le_bytes(
                max_bytes
                    .get(..8)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Long range max".to_string())?,
            );
            Ok(Some((
                scan_type,
                kpm::HmkpmScanCriteria {
                    range_i: kpm::HmkpmScanCriteriaRangeI { min, max },
                },
            )))
        }
        ValueType::Float => {
            let mn_f = f32::from_le_bytes(
                min_bytes
                    .get(..4)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Float range min".to_string())?,
            );
            let mx_f = f32::from_le_bytes(
                max_bytes
                    .get(..4)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Float range max".to_string())?,
            );
            if mn_f.is_nan() || mx_f.is_nan() {
                return Ok(None);
            }
            Ok(Some((
                scan_type,
                kpm::HmkpmScanCriteria {
                    range_u: kpm::HmkpmScanCriteriaRangeU {
                        min: mn_f.to_bits() as u64,
                        max: mx_f.to_bits() as u64,
                    },
                },
            )))
        }
        ValueType::Double => {
            let mn_f = f64::from_le_bytes(
                min_bytes
                    .get(..8)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Double range min".to_string())?,
            );
            let mx_f = f64::from_le_bytes(
                max_bytes
                    .get(..8)
                    .and_then(|s| s.try_into().ok())
                    .ok_or_else(|| "Invalid Double range max".to_string())?,
            );
            if mn_f.is_nan() || mx_f.is_nan() {
                return Ok(None);
            }
            Ok(Some((
                scan_type,
                kpm::HmkpmScanCriteria {
                    range_u: kpm::HmkpmScanCriteriaRangeU {
                        min: mn_f.to_bits(),
                        max: mx_f.to_bits(),
                    },
                },
            )))
        }
        ValueType::Float16 => unreachable!(),
    }
}

/// Executes a v3.0.0 in-kernel scan across all prepared ranges using cursor-based resumption
/// (`HMKPM_SCAN_FLAG_RESUME`) and zero-readback `(va, value)` output (`HMKPM_SCAN_FLAG_WITH_VALUES`).
#[allow(clippy::too_many_arguments)]
fn run_kernel_scan_batches(
    pid: u32,
    regions: &[MemoryRegion],
    sorted_regions: &[(u64, u64, u32)],
    vt: ValueType,
    scan_type: u8,
    scan_op: u8,
    criteria: kpm::HmkpmScanCriteria,
    known_exact_raw: Option<u64>,
) -> Result<Vec<CompactMatch>, String> {
    if !kpm::is_feature_supported(kpm::HMKPM_FEATURE_SCAN_KERNEL) {
        return Err(
            "HMKPM kernel scan feature (HMKPM_FEATURE_SCAN_KERNEL) is not supported by active module"
                .to_string(),
        );
    }

    let supports_ext = kpm::is_feature_supported(kpm::HMKPM_FEATURE_SCAN_EXT);
    if (scan_op == kpm::HMKPM_SCAN_OP_NEQ || scan_op == kpm::HMKPM_SCAN_OP_NOT_RANGE)
        && !supports_ext
    {
        return Err(
            "HMKPM extended scan feature (HMKPM_FEATURE_SCAN_EXT) is required for NEQ/NOT_RANGE"
                .to_string(),
        );
    }
    if known_exact_raw.is_none() && !supports_ext {
        return Err(
            "HMKPM extended scan feature (HMKPM_FEATURE_SCAN_EXT) is required for WITH_VALUES"
                .to_string(),
        );
    }

    let step = vt.size();
    let max_per_call = kpm::HMKPM_MAX_SCAN_MATCHES;
    let prepared_ranges = prepare_scan_ranges(regions);
    let mut all_matches = Vec::new();

    for batch in prepared_ranges.chunks(kpm::HMKPM_MAX_SCAN_RANGES) {
        let mut resume_cursor: Option<kpm::HmkpmScanCursor> = None;

        loop {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }

            let (status, next_cursor) = if let Some(raw_val) = known_exact_raw {
                let outcome = kpm::scan_kernel_addrs(
                    pid,
                    scan_type,
                    scan_op,
                    step as u8,
                    0,
                    criteria,
                    None,
                    None,
                    batch,
                    max_per_call,
                    resume_cursor,
                )?;
                all_matches.reserve(outcome.matches.len());
                for addr in outcome.matches {
                    let region_idx = find_region_idx(sorted_regions, addr);
                    all_matches.push(CompactMatch {
                        address: addr,
                        raw_value: raw_val,
                        region_idx,
                        value_type: vt,
                    });
                }
                (outcome.status, outcome.next_cursor)
            } else {
                let outcome = kpm::scan_kernel_with_values(
                    pid,
                    scan_type,
                    scan_op,
                    step as u8,
                    0,
                    criteria,
                    batch,
                    max_per_call,
                    resume_cursor,
                )?;
                all_matches.reserve(outcome.matches.len());
                for pair in outcome.matches {
                    let region_idx = find_region_idx(sorted_regions, pair.va);
                    all_matches.push(CompactMatch {
                        address: pair.va,
                        raw_value: pair.value,
                        region_idx,
                        value_type: vt,
                    });
                }
                (outcome.status, outcome.next_cursor)
            };

            if status == kpm::HMKPM_SCAN_STATUS_TRUNCATED
                && (next_cursor.range_idx as usize) < batch.len()
            {
                if let Some(prev) = resume_cursor
                    && (next_cursor.range_idx, next_cursor.va) <= (prev.range_idx, prev.va)
                {
                    break;
                }
                resume_cursor = Some(next_cursor);
            } else {
                break;
            }
        }
    }

    Ok(all_matches)
}

fn try_kernel_scan_op(
    pid: u32,
    regions: &[MemoryRegion],
    sorted_regions: &[(u64, u64, u32)],
    vt: ValueType,
    target_bytes: &[u8],
    op: ScanOperator,
) -> Result<Vec<CompactMatch>, String> {
    let Some((scan_type, scan_op, criteria)) = operator_to_kernel_criteria(vt, target_bytes, op)?
    else {
        return Ok(Vec::new());
    };
    let known_exact_raw = if op == ScanOperator::Equal {
        Some(bytes_to_raw_u64(target_bytes, vt))
    } else {
        None
    };
    run_kernel_scan_batches(
        pid,
        regions,
        sorted_regions,
        vt,
        scan_type,
        scan_op,
        criteria,
        known_exact_raw,
    )
}

#[allow(clippy::too_many_arguments)]
fn scan_regions_worker(
    pid: u32,
    tasks: &[ScanTask],
    next_task: &AtomicUsize,
    valid_targets: &[(ValueType, Vec<u8>)],
    min_step: usize,
    operator: ScanOperator,
    unknown_count: &AtomicUsize,
    unknown_cap_warned: &AtomicBool,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf: Vec<usize> = Vec::new();
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    loop {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }
        if operator == ScanOperator::Unknown
            && unknown_count.load(Ordering::Acquire) >= MAX_UNKNOWN_SCAN_MATCHES
        {
            break;
        }

        let task_idx = next_task.fetch_add(1, Ordering::Relaxed);
        let Some(&task) = tasks.get(task_idx) else {
            break;
        };

        let read_len = (task.read_end - task.read_start) as usize;
        if read_len < min_step {
            continue;
        }

        let present_ranges = v2p.get_present_ranges(
            task.read_start,
            task.read_end,
            crate::v2p::PAGE_FLAG_PRESENT,
        )?;

        for (start_addr, end_addr) in present_ranges {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }
            if start_addr >= task.emit_end {
                break;
            }
            let range_len = (end_addr - start_addr) as usize;
            if range_len < min_step {
                continue;
            }

            if let Ok(block_data) = kpm_buf.read(pid, start_addr, range_len) {
                for (vt, target_bytes) in valid_targets {
                    let step = vt.size();
                    if range_len < step {
                        continue;
                    }

                    if operator == ScanOperator::Unknown {
                        let emit_span = (task.emit_end.saturating_sub(start_addr) as usize)
                            .min(range_len.saturating_sub(step) + 1);
                        let candidate_count = emit_span.div_ceil(step);
                        let allowed = reserve_unknown_slots(
                            unknown_count,
                            candidate_count,
                            MAX_UNKNOWN_SCAN_MATCHES,
                        );
                        if allowed < candidate_count
                            && !unknown_cap_warned.swap(true, Ordering::AcqRel)
                        {
                            logger::warn(
                                "Scanner",
                                &format!(
                                    "Unknown scan reached safety cap of {MAX_UNKNOWN_SCAN_MATCHES} matches; truncating remaining regions to protect daemon memory"
                                ),
                            );
                        }
                        if allowed == 0 {
                            return Ok(matches);
                        }

                        matches.reserve(allowed);
                        let mut off = 0usize;
                        let mut emitted = 0usize;
                        while emitted < allowed && off + step <= range_len {
                            let match_addr = start_addr + off as u64;
                            if match_addr >= task.emit_end {
                                break;
                            }
                            let raw_val = bytes_to_raw_u64(&block_data[off..off + step], *vt);
                            matches.push(CompactMatch {
                                address: match_addr,
                                raw_value: raw_val,
                                region_idx: task.region_idx as u32,
                                value_type: *vt,
                            });
                            off += step;
                            emitted += 1;
                        }
                    } else {
                        offsets_buf.clear();
                        scan_buffer_to(block_data, target_bytes, *vt, operator, &mut offsets_buf);
                        for &off in &offsets_buf {
                            let match_addr = start_addr + off as u64;
                            if match_addr >= task.emit_end {
                                continue;
                            }
                            let raw_val = bytes_to_raw_u64(&block_data[off..off + step], *vt);
                            matches.push(CompactMatch {
                                address: match_addr,
                                raw_value: raw_val,
                                region_idx: task.region_idx as u32,
                                value_type: *vt,
                            });
                        }
                    }
                }
            }
        }
    }

    Ok(matches)
}

/// First scan: read regions and find matching values across multiple types, returning compact matches.
/// Distributes byte-balanced 4 MiB chunk tasks across available hardware CPU threads via work-stealing.
pub fn scan_regions(
    pid: u32,
    regions: &[MemoryRegion],
    target_value_str: &str,
    value_types: &[ValueType],
    operator: ScanOperator,
) -> Result<ScanSession, String> {
    reset_scan_cancel();

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

    let kernel_op_supported = matches!(
        operator,
        ScanOperator::Equal
            | ScanOperator::NotEqual
            | ScanOperator::Greater
            | ScanOperator::Less
            | ScanOperator::GreaterEqual
            | ScanOperator::LessEqual
    );

    let engine_mode = get_scan_engine_mode();
    if engine_mode == ScanEngineMode::Kernel as u8 {
        if !kernel_op_supported {
            return Err(format!(
                "Strict Kernel scan mode does not support operator '{operator:?}'; requires Auto or RustNeon mode"
            ));
        }

        if regions.is_empty() {
            return Ok(ScanSession {
                regions: Vec::new(),
                matches: Vec::new(),
                active_types: value_types.to_vec(),
            });
        }

        let sorted_regions = build_sorted_region_index(regions);
        let mut kernel_matches = Vec::new();
        for (vt, target_bytes) in &valid_targets {
            let partial =
                try_kernel_scan_op(pid, regions, &sorted_regions, *vt, target_bytes, operator)?;
            kernel_matches.extend(partial);
        }

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

    if engine_mode == ScanEngineMode::Auto as u8 && kernel_op_supported && !regions.is_empty() {
        let sorted_regions = build_sorted_region_index(regions);
        let mut kernel_matches = Vec::new();
        let mut kernel_success = true;

        for (vt, target_bytes) in &valid_targets {
            match try_kernel_scan_op(pid, regions, &sorted_regions, *vt, target_bytes, operator) {
                Ok(partial) => kernel_matches.extend(partial),
                Err(e) => {
                    if is_scan_cancelled() {
                        return Err("Scan cancelled".to_string());
                    }
                    logger::info(
                        "Scanner",
                        &format!("Auto mode falling back to NEON SIMD: {e}"),
                    );
                    kernel_success = false;
                    break;
                }
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

    let tasks = build_scan_tasks(regions, CHUNK_SIZE, max_step.saturating_sub(1));
    let worker_count = optimal_worker_count(tasks.len());
    let next_task = AtomicUsize::new(0);
    let unknown_count = AtomicUsize::new(0);
    let unknown_cap_warned = AtomicBool::new(false);

    let mut matches = if tasks.is_empty() {
        Vec::new()
    } else if worker_count <= 1 {
        scan_regions_worker(
            pid,
            &tasks,
            &next_task,
            &valid_targets,
            min_step,
            operator,
            &unknown_count,
            &unknown_cap_warned,
        )?
    } else {
        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(worker_count);
            for _ in 0..worker_count {
                let tasks_ref = &tasks;
                let next_task_ref = &next_task;
                let valid_targets_ref = &valid_targets;
                let unknown_count_ref = &unknown_count;
                let unknown_warn_ref = &unknown_cap_warned;
                let handle = s.spawn(move || {
                    scan_regions_worker(
                        pid,
                        tasks_ref,
                        next_task_ref,
                        valid_targets_ref,
                        min_step,
                        operator,
                        unknown_count_ref,
                        unknown_warn_ref,
                    )
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

    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; every vector load checks `offset + 16 <= len`
    // against `data`, and `vld1q_s8`/`vst1q_u8` support unaligned pointers.
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

    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; every vector load checks `offset + 16 <= len`
    // against `data`, and `vld1q_s16`/`vst1q_u16` support unaligned pointers.
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

    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; every vector load checks `offset + 16 <= len`
    // against `data`, and `vld1q_s32`/`vst1q_u32` support unaligned pointers.
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

    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; every vector load checks `offset + 16 <= len`
    // against `data`, and `vld1q_s64`/`vst1q_u64` support unaligned pointers.
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

    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; every vector load checks `offset + 16 <= len`
    // against `data`, and `vld1q_f32`/`vst1q_u32` support unaligned pointers.
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

    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; every vector load checks `offset + 16 <= len`
    // against `data`, and `vld1q_f64`/`vst1q_u64` support unaligned pointers.
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
// Obscured / XOR-Pair Scanning
// ============================================================

/// Scan buffer for 32-bit Obscured types (ObscuredInt / ObscuredFloat)
pub fn scan_buffer_obscured32(data: &[u8], target_bits: u32, results: &mut Vec<usize>) {
    let len = data.len();
    let mut offset = 0;
    const STEP: usize = 4;

    #[cfg(target_arch = "aarch64")]
    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; loop invariant `offset + 36 <= len` ensures
    // both `vld2q_u32(ptr)` (bytes 0..32) and `vld2q_u32(ptr.add(1))` (bytes 4..36) stay in bounds.
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
    // SAFETY: AArch64 NEON is mandatory on ARMv8-A; loop invariant `offset + 40 <= len` ensures
    // both `vld2q_u64(ptr)` (bytes 0..32) and `vld2q_u64(ptr.add(1))` (bytes 8..40) stay in bounds.
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

fn scan_obscured_worker(
    pid: u32,
    tasks: &[ScanTask],
    next_task: &AtomicUsize,
    target_bits32: Option<u32>,
    target_bits64: Option<u64>,
    target_vtype: ValueType,
    step: usize,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf = Vec::new();
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    loop {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }

        let task_idx = next_task.fetch_add(1, Ordering::Relaxed);
        let Some(&task) = tasks.get(task_idx) else {
            break;
        };

        let read_len = (task.read_end - task.read_start) as usize;
        if read_len < step {
            continue;
        }

        let present_ranges = v2p.get_present_ranges(
            task.read_start,
            task.read_end,
            crate::v2p::PAGE_FLAG_PRESENT,
        )?;

        for (start_addr, end_addr) in present_ranges {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }
            if start_addr >= task.emit_end {
                break;
            }
            let range_len = (end_addr - start_addr) as usize;
            if range_len < step {
                continue;
            }

            if let Ok(block_data) = kpm_buf.read(pid, start_addr, range_len) {
                offsets_buf.clear();
                if let Some(t32) = target_bits32 {
                    scan_buffer_obscured32(block_data, t32, &mut offsets_buf);
                } else if let Some(t64) = target_bits64 {
                    scan_buffer_obscured64(block_data, t64, &mut offsets_buf);
                }

                for &off in &offsets_buf {
                    let match_addr = start_addr + off as u64;
                    if match_addr >= task.emit_end {
                        continue;
                    }
                    let raw_val = if let Some(t32) = target_bits32 {
                        t32 as u64
                    } else {
                        target_bits64.unwrap_or(0)
                    };

                    matches.push(CompactMatch {
                        address: match_addr,
                        raw_value: raw_val,
                        region_idx: task.region_idx as u32,
                        value_type: target_vtype,
                    });
                }
            }
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
    reset_scan_cancel();

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

    let tasks = build_scan_tasks(regions, CHUNK_SIZE, step.saturating_sub(1));
    let worker_count = optimal_worker_count(tasks.len());
    let next_task = AtomicUsize::new(0);

    let mut matches = if tasks.is_empty() {
        Vec::new()
    } else if worker_count <= 1 {
        scan_obscured_worker(
            pid,
            &tasks,
            &next_task,
            target_bits32,
            target_bits64,
            target_vtype,
            step,
        )?
    } else {
        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(worker_count);
            for _ in 0..worker_count {
                let tasks_ref = &tasks;
                let next_task_ref = &next_task;
                let handle = s.spawn(move || {
                    scan_obscured_worker(
                        pid,
                        tasks_ref,
                        next_task_ref,
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

fn scan_big_double_worker(
    pid: u32,
    tasks: &[ScanTask],
    next_task: &AtomicUsize,
    target: &BigDouble,
    step: usize,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf = Vec::new();
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    loop {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }

        let task_idx = next_task.fetch_add(1, Ordering::Relaxed);
        let Some(&task) = tasks.get(task_idx) else {
            break;
        };

        let read_len = (task.read_end - task.read_start) as usize;
        if read_len < step {
            continue;
        }

        let present_ranges = v2p.get_present_ranges(
            task.read_start,
            task.read_end,
            crate::v2p::PAGE_FLAG_PRESENT,
        )?;

        for (start_addr, end_addr) in present_ranges {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }
            if start_addr >= task.emit_end {
                break;
            }
            let range_len = (end_addr - start_addr) as usize;
            if range_len < step {
                continue;
            }

            if let Ok(block_data) = kpm_buf.read(pid, start_addr, range_len) {
                offsets_buf.clear();
                scan_buffer_big_double_to(block_data, target, &mut offsets_buf);

                for &off in &offsets_buf {
                    let match_addr = start_addr + off as u64;
                    if match_addr >= task.emit_end {
                        continue;
                    }
                    let raw_val = target.mantissa.to_bits();

                    matches.push(CompactMatch {
                        address: match_addr,
                        raw_value: raw_val,
                        region_idx: task.region_idx as u32,
                        value_type: ValueType::Double,
                    });
                }
            }
        }
    }

    Ok(matches)
}

pub fn scan_big_double(
    pid: u32,
    regions: &[MemoryRegion],
    target_str: &str,
) -> Result<ScanSession, String> {
    reset_scan_cancel();

    let target = BigDouble::parse(target_str)?;
    let min_struct_size = 12;
    let max_struct_size = 16;

    let tasks = build_scan_tasks(regions, CHUNK_SIZE, max_struct_size - 1);
    let worker_count = optimal_worker_count(tasks.len());
    let next_task = AtomicUsize::new(0);

    let mut matches = if tasks.is_empty() {
        Vec::new()
    } else if worker_count <= 1 {
        scan_big_double_worker(pid, &tasks, &next_task, &target, min_struct_size)?
    } else {
        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(worker_count);
            for _ in 0..worker_count {
                let tasks_ref = &tasks;
                let next_task_ref = &next_task;
                let target_ref = &target;
                let handle = s.spawn(move || {
                    scan_big_double_worker(
                        pid,
                        tasks_ref,
                        next_task_ref,
                        target_ref,
                        min_struct_size,
                    )
                });
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

fn scan_range_worker(
    pid: u32,
    tasks: &[ScanTask],
    next_task: &AtomicUsize,
    valid_targets: &[(ValueType, Vec<u8>, Vec<u8>)],
    min_step: usize,
) -> Result<Vec<CompactMatch>, String> {
    let mut matches = Vec::new();
    let mut offsets_buf: Vec<usize> = Vec::new();
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    loop {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }

        let task_idx = next_task.fetch_add(1, Ordering::Relaxed);
        let Some(&task) = tasks.get(task_idx) else {
            break;
        };

        let read_len = (task.read_end - task.read_start) as usize;
        if read_len < min_step {
            continue;
        }

        let present_ranges = v2p.get_present_ranges(
            task.read_start,
            task.read_end,
            crate::v2p::PAGE_FLAG_PRESENT,
        )?;

        for (start_addr, end_addr) in present_ranges {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }
            if start_addr >= task.emit_end {
                break;
            }
            let range_len = (end_addr - start_addr) as usize;
            if range_len < min_step {
                continue;
            }

            if let Ok(block_data) = kpm_buf.read(pid, start_addr, range_len) {
                for (vt, min_bytes, max_bytes) in valid_targets {
                    let step = vt.size();
                    if range_len < step {
                        continue;
                    }

                    offsets_buf.clear();
                    scan_range_buffer_to(block_data, min_bytes, max_bytes, *vt, &mut offsets_buf);

                    for &off in &offsets_buf {
                        let match_addr = start_addr + off as u64;
                        if match_addr >= task.emit_end {
                            continue;
                        }
                        let raw_val = bytes_to_raw_u64(&block_data[off..off + step], *vt);
                        matches.push(CompactMatch {
                            address: match_addr,
                            raw_value: raw_val,
                            region_idx: task.region_idx as u32,
                            value_type: *vt,
                        });
                    }
                }
            }
        }
    }

    Ok(matches)
}

fn try_kernel_scan_range(
    pid: u32,
    regions: &[MemoryRegion],
    sorted_regions: &[(u64, u64, u32)],
    vt: ValueType,
    min_bytes: &[u8],
    max_bytes: &[u8],
) -> Result<Vec<CompactMatch>, String> {
    let Some((scan_type, criteria)) = range_bytes_to_kernel_criteria(vt, min_bytes, max_bytes)?
    else {
        return Ok(Vec::new());
    };
    run_kernel_scan_batches(
        pid,
        regions,
        sorted_regions,
        vt,
        scan_type,
        kpm::HMKPM_SCAN_OP_RANGE,
        criteria,
        None,
    )
}

pub fn scan_range(
    pid: u32,
    regions: &[MemoryRegion],
    min_value_str: &str,
    max_value_str: &str,
    value_types: &[ValueType],
) -> Result<ScanSession, String> {
    reset_scan_cancel();

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

    let engine_mode = get_scan_engine_mode();
    if engine_mode == ScanEngineMode::Kernel as u8 {
        if regions.is_empty() {
            return Ok(ScanSession {
                regions: Vec::new(),
                matches: Vec::new(),
                active_types: value_types.to_vec(),
            });
        }

        let sorted_regions = build_sorted_region_index(regions);
        let mut kernel_matches = Vec::new();
        for (vt, min_bytes, max_bytes) in &valid_targets {
            let partial =
                try_kernel_scan_range(pid, regions, &sorted_regions, *vt, min_bytes, max_bytes)?;
            kernel_matches.extend(partial);
        }

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

    if engine_mode == ScanEngineMode::Auto as u8 && !regions.is_empty() {
        let sorted_regions = build_sorted_region_index(regions);
        let mut kernel_matches = Vec::new();
        let mut kernel_success = true;

        for (vt, min_bytes, max_bytes) in &valid_targets {
            match try_kernel_scan_range(pid, regions, &sorted_regions, *vt, min_bytes, max_bytes) {
                Ok(partial) => kernel_matches.extend(partial),
                Err(e) => {
                    if is_scan_cancelled() {
                        return Err("Scan cancelled".to_string());
                    }
                    logger::info(
                        "Scanner",
                        &format!("Auto range mode falling back to NEON SIMD: {e}"),
                    );
                    kernel_success = false;
                    break;
                }
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

    let tasks = build_scan_tasks(regions, CHUNK_SIZE, max_step.saturating_sub(1));
    let worker_count = optimal_worker_count(tasks.len());
    let next_task = AtomicUsize::new(0);

    let mut matches = if tasks.is_empty() {
        Vec::new()
    } else if worker_count <= 1 {
        scan_range_worker(pid, &tasks, &next_task, &valid_targets, min_step)?
    } else {
        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(worker_count);
            for _ in 0..worker_count {
                let tasks_ref = &tasks;
                let next_task_ref = &next_task;
                let valid_targets_ref = &valid_targets;
                let handle = s.spawn(move || {
                    scan_range_worker(pid, tasks_ref, next_task_ref, valid_targets_ref, min_step)
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

fn scan_group_worker(
    pid: u32,
    tasks: &[ScanTask],
    next_task: &AtomicUsize,
    first_item: &GroupTargetItem,
    remaining_items: &[GroupTargetItem],
    distance: usize,
    remaining_size: usize,
) -> Result<Vec<CompactMatch>, String> {
    let first_step = first_item.value_type.size();
    let mut matches = Vec::new();
    let mut first_match_offsets = Vec::new();
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(CHUNK_SIZE);
    let mut v2p = crate::v2p::V2pReader::new(pid)
        .map_err(|e| format!("Failed to initialize V2P reader for PID {pid}: {e}"))?;

    loop {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }

        let task_idx = next_task.fetch_add(1, Ordering::Relaxed);
        let Some(&task) = tasks.get(task_idx) else {
            break;
        };

        let read_len = (task.read_end - task.read_start) as usize;
        if read_len < first_step {
            continue;
        }

        let present_ranges = v2p.get_present_ranges(
            task.read_start,
            task.read_end,
            crate::v2p::PAGE_FLAG_PRESENT,
        )?;

        for (start_addr, end_addr) in present_ranges {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }
            if start_addr >= task.emit_end {
                break;
            }
            let range_len = (end_addr - start_addr) as usize;
            if range_len < first_step {
                continue;
            }

            if let Ok(data) = kpm_buf.read(pid, start_addr, range_len) {
                let emit_limit = (task.emit_end.saturating_sub(start_addr) as usize)
                    .saturating_add(first_step.saturating_sub(1))
                    .min(data.len());

                first_match_offsets.clear();
                scan_buffer_to(
                    &data[..emit_limit],
                    &first_item.target_bytes,
                    first_item.value_type,
                    ScanOperator::Equal,
                    &mut first_match_offsets,
                );

                for &first_off in &first_match_offsets {
                    let match_addr = start_addr + first_off as u64;
                    if match_addr >= task.emit_end {
                        continue;
                    }

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
                        let raw_val = bytes_to_raw_u64(
                            &data[first_off..first_off + first_step],
                            first_item.value_type,
                        );
                        matches.push(CompactMatch {
                            address: match_addr,
                            raw_value: raw_val,
                            region_idx: task.region_idx as u32,
                            value_type: first_item.value_type,
                        });
                    }
                }
            }
        }
    }

    Ok(matches)
}

pub fn scan_group(
    pid: u32,
    regions: &[MemoryRegion],
    group_spec: &str,
    default_value_type: ValueType,
) -> Result<ScanSession, String> {
    reset_scan_cancel();

    let (target_items, distance) = parse_group_spec(group_spec, default_value_type)?;
    let first_item = &target_items[0];
    let remaining_items = &target_items[1..];
    let remaining_size: usize = remaining_items
        .iter()
        .map(|item| item.value_type.size())
        .sum();

    let overlap = distance.saturating_add(remaining_size);
    let tasks = build_scan_tasks(regions, CHUNK_SIZE, overlap);
    let worker_count = optimal_worker_count(tasks.len());
    let next_task = AtomicUsize::new(0);

    let mut matches = if tasks.is_empty() {
        Vec::new()
    } else if worker_count <= 1 {
        scan_group_worker(
            pid,
            &tasks,
            &next_task,
            first_item,
            remaining_items,
            distance,
            remaining_size,
        )?
    } else {
        std::thread::scope(|s| {
            let mut handles = Vec::with_capacity(worker_count);
            for _ in 0..worker_count {
                let tasks_ref = &tasks;
                let next_task_ref = &next_task;
                let handle = s.spawn(move || {
                    scan_group_worker(
                        pid,
                        tasks_ref,
                        next_task_ref,
                        first_item,
                        remaining_items,
                        distance,
                        remaining_size,
                    )
                });
                handles.push(handle);
            }

            let mut all_matches = Vec::new();
            for handle in handles {
                let partial = handle
                    .join()
                    .map_err(|_| "Worker thread panicked during group scan".to_string())??;
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
        active_types: vec![first_item.value_type],
    })
}

// ============================================================
// Next Scan / Refine Filtering
// ============================================================

fn scan_op_to_refine_op(scan_op: u8) -> Option<u8> {
    match scan_op {
        kpm::HMKPM_SCAN_OP_EXACT => Some(kpm::HMKPM_REFINE_OP_EXACT),
        kpm::HMKPM_SCAN_OP_NEQ => Some(kpm::HMKPM_REFINE_OP_NEQ),
        kpm::HMKPM_SCAN_OP_RANGE => Some(kpm::HMKPM_REFINE_OP_RANGE),
        kpm::HMKPM_SCAN_OP_NOT_RANGE => Some(kpm::HMKPM_REFINE_OP_NOT_RANGE),
        kpm::HMKPM_SCAN_OP_MASK => Some(kpm::HMKPM_REFINE_OP_MASK),
        _ => None,
    }
}

fn try_kernel_refine_matches(
    pid: u32,
    session: &ScanSession,
    target_value_str: &str,
    operator: ScanOperator,
) -> Result<ScanSession, String> {
    if !kpm::is_feature_supported(kpm::HMKPM_FEATURE_SCAN_REFINE) {
        return Err("HMKPM_FEATURE_SCAN_REFINE not supported".to_string());
    }

    let delta_refine_op = match operator {
        ScanOperator::Changed => Some(kpm::HMKPM_REFINE_OP_CHANGED),
        ScanOperator::Unchanged => Some(kpm::HMKPM_REFINE_OP_UNCHANGED),
        ScanOperator::Increased => Some(kpm::HMKPM_REFINE_OP_INCREASED),
        ScanOperator::Decreased => Some(kpm::HMKPM_REFINE_OP_DECREASED),
        ScanOperator::Equal
        | ScanOperator::NotEqual
        | ScanOperator::Greater
        | ScanOperator::Less
        | ScanOperator::GreaterEqual
        | ScanOperator::LessEqual => None,
        _ => {
            return Err(format!(
                "In-kernel refine does not support operator '{operator:?}'"
            ));
        }
    };

    if session.active_types.contains(&ValueType::Float16)
        || session
            .matches
            .iter()
            .any(|m| m.value_type == ValueType::Float16)
    {
        return Err("In-kernel refine does not support float16".to_string());
    }

    let mut grouped: std::collections::HashMap<ValueType, Vec<kpm::HmkpmScanValMatch>> =
        std::collections::HashMap::with_capacity(session.active_types.len());
    for m in &session.matches {
        grouped
            .entry(m.value_type)
            .or_default()
            .push(kpm::HmkpmScanValMatch {
                va: m.address,
                value: m.raw_value,
            });
    }

    let sorted_regions = build_sorted_region_index(&session.regions);
    let mut updated_matches = Vec::with_capacity(session.matches.len().min(65536));

    for (vt, candidates) in grouped {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }

        let (scan_type, refine_op, criteria) = if let Some(dop) = delta_refine_op {
            let st = value_type_to_kernel_scan_type(vt)?;
            (
                st,
                dop,
                kpm::HmkpmScanCriteria {
                    exact: kpm::HmkpmScanCriteriaExact { val: 0, mask: 0 },
                },
            )
        } else {
            let Ok(tb) = value_str_to_bytes(target_value_str, vt) else {
                continue;
            };
            let Some((st, scan_op, crit)) = operator_to_kernel_criteria(vt, &tb, operator)? else {
                continue;
            };
            let rop = scan_op_to_refine_op(scan_op)
                .ok_or_else(|| format!("Unsupported kernel refine scan_op {scan_op}"))?;
            (st, rop, crit)
        };

        for chunk in candidates.chunks(kpm::HMKPM_MAX_REFINE_COUNT) {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }
            let mut work = chunk.to_vec();
            let matched =
                kpm::scan_refine_values(pid, scan_type, refine_op, criteria, &mut work, 0)?;
            updated_matches.reserve(matched);
            for pair in work {
                let region_idx = find_region_idx(&sorted_regions, pair.va);
                updated_matches.push(CompactMatch {
                    address: pair.va,
                    raw_value: pair.value,
                    region_idx,
                    value_type: vt,
                });
            }
        }
    }

    updated_matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.cmp(&b.value_type))
    });

    Ok(ScanSession {
        regions: session.regions.clone(),
        matches: updated_matches,
        active_types: session.active_types.clone(),
    })
}

fn try_kernel_refine_range(
    pid: u32,
    session: &ScanSession,
    min_str: &str,
    max_str: &str,
) -> Result<ScanSession, String> {
    if !kpm::is_feature_supported(kpm::HMKPM_FEATURE_SCAN_REFINE) {
        return Err("HMKPM_FEATURE_SCAN_REFINE not supported".to_string());
    }

    if session.active_types.contains(&ValueType::Float16)
        || session
            .matches
            .iter()
            .any(|m| m.value_type == ValueType::Float16)
    {
        return Err("In-kernel range refine does not support float16".to_string());
    }

    let mut grouped: std::collections::HashMap<ValueType, Vec<kpm::HmkpmScanValMatch>> =
        std::collections::HashMap::with_capacity(session.active_types.len());
    for m in &session.matches {
        grouped
            .entry(m.value_type)
            .or_default()
            .push(kpm::HmkpmScanValMatch {
                va: m.address,
                value: m.raw_value,
            });
    }

    let sorted_regions = build_sorted_region_index(&session.regions);
    let mut updated_matches = Vec::with_capacity(session.matches.len().min(65536));

    for (vt, candidates) in grouped {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }

        let (Ok(min_b), Ok(max_b)) = (
            value_str_to_bytes(min_str, vt),
            value_str_to_bytes(max_str, vt),
        ) else {
            continue;
        };

        let Some((scan_type, criteria)) = range_bytes_to_kernel_criteria(vt, &min_b, &max_b)?
        else {
            continue;
        };

        for chunk in candidates.chunks(kpm::HMKPM_MAX_REFINE_COUNT) {
            if is_scan_cancelled() {
                return Err("Scan cancelled".to_string());
            }
            let mut work = chunk.to_vec();
            let matched = kpm::scan_refine_values(
                pid,
                scan_type,
                kpm::HMKPM_REFINE_OP_RANGE,
                criteria,
                &mut work,
                0,
            )?;
            updated_matches.reserve(matched);
            for pair in work {
                let region_idx = find_region_idx(&sorted_regions, pair.va);
                updated_matches.push(CompactMatch {
                    address: pair.va,
                    raw_value: pair.value,
                    region_idx,
                    value_type: vt,
                });
            }
        }
    }

    updated_matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.cmp(&b.value_type))
    });

    Ok(ScanSession {
        regions: session.regions.clone(),
        matches: updated_matches,
        active_types: session.active_types.clone(),
    })
}

pub fn filter_matches(
    pid: u32,
    session: &ScanSession,
    target_value_str: &str,
    operator: ScanOperator,
) -> Result<ScanSession, String> {
    reset_scan_cancel();

    let engine_mode = get_scan_engine_mode();
    if (engine_mode == ScanEngineMode::Kernel as u8 || engine_mode == ScanEngineMode::Auto as u8)
        && !session.matches.is_empty()
    {
        match try_kernel_refine_matches(pid, session, target_value_str, operator) {
            Ok(refined) => return Ok(refined),
            Err(e) => {
                if is_scan_cancelled() {
                    return Err("Scan cancelled".to_string());
                }
                logger::debug(
                    "Scanner",
                    &format!("Refine falling back to userspace KPM read loop: {e}"),
                );
            }
        }
    }

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
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(BATCH_WINDOW as usize + max_step);

    while i < items.len() {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }
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
        let block_slice = kpm_buf.read(pid, base, block_len).ok();

        for m in &items[i..j] {
            let step = m.value_type.size();
            let mut single_buf = [0u8; 8];
            let current_bytes = if let Some(block) = block_slice {
                let off = (m.address - base) as usize;
                if off + step <= block.len() {
                    &block[off..off + step]
                } else {
                    continue;
                }
            } else if kpm::read_memory(pid, m.address, &mut single_buf[..step]).is_ok() {
                &single_buf[..step]
            } else {
                continue;
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
    reset_scan_cancel();

    let engine_mode = get_scan_engine_mode();
    if (engine_mode == ScanEngineMode::Kernel as u8 || engine_mode == ScanEngineMode::Auto as u8)
        && !session.matches.is_empty()
    {
        match try_kernel_refine_range(pid, session, min_str, max_str) {
            Ok(refined) => return Ok(refined),
            Err(e) => {
                if is_scan_cancelled() {
                    return Err("Scan cancelled".to_string());
                }
                logger::debug(
                    "Scanner",
                    &format!("Range refine falling back to userspace KPM read loop: {e}"),
                );
            }
        }
    }

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
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(BATCH_WINDOW as usize + max_step);

    while i < items.len() {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }
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
        let block_slice = kpm_buf.read(pid, base, block_len).ok();

        for m in &items[i..j] {
            let step = m.value_type.size();
            let mut single_buf = [0u8; 8];
            let current_bytes = if let Some(block) = block_slice {
                let off = (m.address - base) as usize;
                if off + step <= block.len() {
                    &block[off..off + step]
                } else {
                    continue;
                }
            } else if kpm::read_memory(pid, m.address, &mut single_buf[..step]).is_ok() {
                &single_buf[..step]
            } else {
                continue;
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
    reset_scan_cancel();

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
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(BATCH_WINDOW as usize + step);

    while i < items.len() {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }
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
        let block_slice = kpm_buf.read(pid, base, block_len).ok();

        for m in &items[i..j] {
            let mut single_buf = [0u8; 16];
            let buf_slice: &[u8] = if let Some(block) = block_slice {
                let off = (m.address - base) as usize;
                if off + step <= block.len() {
                    &block[off..off + step]
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
    reset_scan_cancel();

    let target = BigDouble::parse(target_str)?;
    const BATCH_WINDOW: u64 = 65536;
    const MAX_STRUCT_SIZE: usize = 16;
    let items = &session.matches;
    let mut updated_matches = Vec::with_capacity(items.len());

    let mut i = 0;
    let mut kpm_buf = kpm::KpmReadBuffer::with_capacity(BATCH_WINDOW as usize + MAX_STRUCT_SIZE);

    while i < items.len() {
        if is_scan_cancelled() {
            return Err("Scan cancelled".to_string());
        }
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
        let block_slice = kpm_buf.read(pid, base, block_len).ok();

        for m in &items[i..j] {
            let mut single_buf = [0u8; 16];
            let buf_slice: &[u8] = if let Some(block) = block_slice {
                let off = (m.address - base) as usize;
                if off + MAX_STRUCT_SIZE <= block.len() {
                    &block[off..off + MAX_STRUCT_SIZE]
                } else if off + 12 <= block.len() {
                    &block[off..off + 12]
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
    fn test_operator_to_kernel_criteria_bounds_and_ops() {
        // Equal -> HMKPM_SCAN_OP_EXACT
        let (st, op, crit) =
            operator_to_kernel_criteria(ValueType::Int, &42i32.to_le_bytes(), ScanOperator::Equal)
                .unwrap()
                .unwrap();
        assert_eq!(st, kpm::HMKPM_SCAN_TYPE_I32);
        assert_eq!(op, kpm::HMKPM_SCAN_OP_EXACT);
        // SAFETY: `op == EXACT` populates `crit.exact`.
        assert_eq!(unsafe { crit.exact.val }, 42);

        // NotEqual -> HMKPM_SCAN_OP_NEQ
        let (st, op, _) = operator_to_kernel_criteria(
            ValueType::Int,
            &(-5i32).to_le_bytes(),
            ScanOperator::NotEqual,
        )
        .unwrap()
        .unwrap();
        assert_eq!(st, kpm::HMKPM_SCAN_TYPE_I32);
        assert_eq!(op, kpm::HMKPM_SCAN_OP_NEQ);

        // Greater on i32::MAX -> empty range Ok(None)
        assert!(
            operator_to_kernel_criteria(
                ValueType::Int,
                &i32::MAX.to_le_bytes(),
                ScanOperator::Greater
            )
            .unwrap()
            .is_none()
        );

        // Less on i8::MIN -> empty range Ok(None)
        assert!(
            operator_to_kernel_criteria(
                ValueType::Byte,
                &i8::MIN.to_le_bytes(),
                ScanOperator::Less
            )
            .unwrap()
            .is_none()
        );

        // Greater on Float 0.0 -> (next_up(0.0), +inf)
        let (st, op, crit) = operator_to_kernel_criteria(
            ValueType::Float,
            &0.0f32.to_le_bytes(),
            ScanOperator::Greater,
        )
        .unwrap()
        .unwrap();
        assert_eq!(st, kpm::HMKPM_SCAN_TYPE_F32);
        assert_eq!(op, kpm::HMKPM_SCAN_OP_RANGE);
        // SAFETY: `op == RANGE` on F32 populates `crit.range_u`.
        unsafe {
            assert_eq!(crit.range_u.min, 0.0f32.next_up().to_bits() as u64);
            assert_eq!(crit.range_u.max, f32::INFINITY.to_bits() as u64);
        }

        // NaN ordered comparison -> Ok(None)
        assert!(
            operator_to_kernel_criteria(
                ValueType::Float,
                &f32::NAN.to_le_bytes(),
                ScanOperator::Greater
            )
            .unwrap()
            .is_none()
        );

        // Float16 rejected
        assert!(
            operator_to_kernel_criteria(ValueType::Float16, &[0, 0], ScanOperator::Equal).is_err()
        );
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

    #[test]
    fn test_find_region_idx_binary_search() {
        let regions = vec![
            MemoryRegion {
                start: 0x5000,
                end: 0x7000,
                permissions: "rw-p".into(),
                offset: 0,
                path: "[heap]".into(),
                merged_count: 1,
            },
            MemoryRegion {
                start: 0x1000,
                end: 0x3000,
                permissions: "rw-p".into(),
                offset: 0,
                path: "[anon]".into(),
                merged_count: 1,
            },
            MemoryRegion {
                start: 0x9000,
                end: 0xA000,
                permissions: "rw-p".into(),
                offset: 0,
                path: "libgame.so".into(),
                merged_count: 1,
            },
        ];

        let sorted = build_sorted_region_index(&regions);
        assert_eq!(find_region_idx(&sorted, 0x1000), 1);
        assert_eq!(find_region_idx(&sorted, 0x2FFF), 1);
        assert_eq!(find_region_idx(&sorted, 0x5000), 0);
        assert_eq!(find_region_idx(&sorted, 0x6FFF), 0);
        assert_eq!(find_region_idx(&sorted, 0x9500), 2);
        // Out of range / gap defaults cleanly to 0
        assert_eq!(find_region_idx(&sorted, 0x3000), 0);
        assert_eq!(find_region_idx(&sorted, 0x0500), 0);
    }

    #[test]
    fn test_build_scan_tasks_coverage_alignment_and_overlap() {
        let region_size = 10 * 1024 * 1024u64; // 10 MiB single region
        let regions = vec![MemoryRegion {
            start: 0x1000_0000,
            end: 0x1000_0000 + region_size,
            permissions: "rw-p".into(),
            offset: 0,
            path: "[anon]".into(),
            merged_count: 1,
        }];

        let overlap = 15usize;
        let tasks = build_scan_tasks(&regions, CHUNK_SIZE, overlap);
        assert_eq!(tasks.len(), 3);

        // Verify contiguous non-overlapping emit ranges covering the full 10 MiB region
        let mut cursor = regions[0].start;
        for (i, t) in tasks.iter().enumerate() {
            assert_eq!(t.region_idx, 0);
            assert_eq!(t.emit_start, cursor);
            assert_eq!(t.read_start, t.emit_start);
            assert_eq!(
                t.read_start % 4096,
                0,
                "task start must remain 4 KiB-aligned"
            );
            assert!(t.emit_end > t.emit_start);
            assert!(
                (t.read_end - t.read_start) as usize <= CHUNK_SIZE,
                "read span must never exceed CHUNK_SIZE"
            );
            if i + 1 < tasks.len() {
                assert_eq!(t.read_end, t.emit_end + overlap as u64);
            } else {
                assert_eq!(t.read_end, regions[0].end);
            }
            cursor = t.emit_end;
        }
        assert_eq!(cursor, regions[0].end);
    }

    #[test]
    fn test_reserve_unknown_slots_respects_cap() {
        let counter = AtomicUsize::new(0);
        assert_eq!(reserve_unknown_slots(&counter, 60, 100), 60);
        assert_eq!(reserve_unknown_slots(&counter, 60, 100), 40);
        assert_eq!(reserve_unknown_slots(&counter, 10, 100), 0);
        assert_eq!(counter.load(Ordering::Acquire), 100);
    }

    #[test]
    fn test_scan_cancellation_flag() {
        reset_scan_cancel();
        assert!(!is_scan_cancelled());
        cancel_scan();
        assert!(is_scan_cancelled());
        reset_scan_cancel();
        assert!(!is_scan_cancelled());
    }

    #[test]
    fn test_strict_kernel_mode_rejects_unsupported_operator() {
        let prev = get_scan_engine_mode();
        set_scan_engine_mode(ScanEngineMode::Kernel as u8);
        let res = scan_regions(1234, &[], "100", &[ValueType::Int], ScanOperator::Unknown);
        set_scan_engine_mode(prev);
        assert!(res.is_err());
        assert!(res.unwrap_err().contains("Strict Kernel scan mode"));
    }
}
