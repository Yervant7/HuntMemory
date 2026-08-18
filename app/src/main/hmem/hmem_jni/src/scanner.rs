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
use crate::types::*;

const CHUNK_SIZE: usize = 4 * 1024 * 1024; // 4MB chunks per read

/// Helper for reading memory blocks with subdividing fallback
/// to ensure regions with sparse or non-resident pages are read properly.
#[inline]
fn read_region_chunk_robust(
    pid: u32,
    addr: u64,
    len: usize,
    buf: &mut Vec<u8>,
    valid_blocks: &mut Vec<(u64, usize, usize)>, // (block_addr, block_len, buf_offset)
) {
    valid_blocks.clear();
    if buf.len() < len {
        buf.resize(len, 0);
    }

    if kpm::read_memory(pid, addr, &mut buf[..len]).is_ok() {
        valid_blocks.push((addr, len, 0));
        return;
    }

    // If large block read fails (e.g. sparse pages in between),
    // retry with smaller chunks of 64 KiB
    const SUB_CHUNK: usize = 64 * 1024;
    let mut sub_addr = addr;
    let end_addr = addr + len as u64;

    while sub_addr < end_addr {
        let sub_len = ((end_addr - sub_addr) as usize).min(SUB_CHUNK);
        let off = (sub_addr - addr) as usize;
        if kpm::read_memory(pid, sub_addr, &mut buf[off..off + sub_len]).is_ok() {
            valid_blocks.push((sub_addr, sub_len, off));
        }
        sub_addr += sub_len as u64;
    }
}

/// First scan: read regions and find matching values across multiple types, returning compact matches
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

    let min_step = valid_targets
        .iter()
        .map(|(vt, _)| vt.size())
        .min()
        .unwrap_or(4);
    let estimated = regions
        .iter()
        .map(|r| r.end.saturating_sub(r.start))
        .sum::<u64>() as usize
        / min_step
        / 1000;
    let mut matches = Vec::with_capacity(estimated.min(131_072));
    let mut offsets_buf: Vec<usize> = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut valid_blocks: Vec<(u64, usize, usize)> = Vec::new();

    for (region_idx, region) in regions.iter().enumerate() {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < min_step {
                break;
            }

            read_region_chunk_robust(pid, addr, chunk_len, &mut chunk_buf, &mut valid_blocks);

            for &(block_addr, block_len, buf_off) in &valid_blocks {
                let block_data = &chunk_buf[buf_off..buf_off + block_len];

                for (vt, target_bytes) in &valid_targets {
                    let step = vt.size();
                    if block_len < step {
                        continue;
                    }

                    if operator == ScanOperator::Unknown {
                        let mut off = 0;
                        while off + step <= block_len {
                            let match_addr = block_addr + off as u64;
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
                        scan_buffer_to(block_data, target_bytes, *vt, operator, &mut offsets_buf);
                        for &off in &offsets_buf {
                            let match_addr = block_addr + off as u64;
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

            addr += chunk_len as u64;
        }
    }

    matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.size().cmp(&b.value_type.size()))
    });

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
                if op == ScanOperator::Equal {
                    let v = f64::from_le_bytes(target[..8].try_into().unwrap());
                    scan_buffer_f64_eq_neon(data, v, results);
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
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const f32;
            let chunk = vld1q_f32(ptr);
            let mask = match op {
                NeonOp::Eq => vceqq_f32(chunk, target_vec),
                NeonOp::Ne => vmvnq_u32(vceqq_f32(chunk, target_vec)),
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
fn scan_buffer_f64_eq_neon(data: &[u8], target_val: f64, results: &mut Vec<usize>) {
    use std::arch::aarch64::*;
    const STEP: usize = 8;
    let len = data.len();
    let mut offset = 0;

    unsafe {
        let target_vec = vdupq_n_f64(target_val);
        while offset + 16 <= len {
            let ptr = data.as_ptr().add(offset) as *const f64;
            let chunk = vld1q_f64(ptr);
            let mask = vceqq_f64(chunk, target_vec);
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
            if (val - target_val).abs() < 1e-9 {
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
        while offset + 32 <= len {
            let ptr = data.as_ptr().add(offset) as *const u32;
            let pairs = vld2q_u32(ptr);
            let xored = veorq_u32(pairs.0, pairs.1);
            let mask = vceqq_u32(xored, target_vec);

            if vmaxvq_u32(mask) != 0 {
                let mut mask_arr = [0u32; 4];
                vst1q_u32(mask_arr.as_mut_ptr(), mask);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 8);
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
        while offset + 32 <= len {
            let ptr = data.as_ptr().add(offset) as *const u64;
            let pairs = vld2q_u64(ptr);
            let xored = veorq_u64(pairs.0, pairs.1);
            let mask = vceqq_u64(xored, target_vec);

            if vmaxvq_u32(vreinterpretq_u32_u64(mask)) != 0 {
                let mut mask_arr = [0u64; 2];
                vst1q_u64(mask_arr.as_mut_ptr(), mask);
                for (i, &m) in mask_arr.iter().enumerate() {
                    if m != 0 {
                        results.push(offset + i * 16);
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
    let estimated = regions
        .iter()
        .map(|r| r.end.saturating_sub(r.start))
        .sum::<u64>() as usize
        / step
        / 1000;
    let mut matches = Vec::with_capacity(estimated.min(131_072));
    let mut offsets_buf = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut valid_blocks: Vec<(u64, usize, usize)> = Vec::new();

    let target_vtype = match obscured_type {
        ObscuredType::ObscuredInt => ValueType::Int,
        ObscuredType::ObscuredFloat => ValueType::Float,
        ObscuredType::ObscuredDouble => ValueType::Double,
        ObscuredType::ObscuredLong => ValueType::Long,
    };

    for (region_idx, region) in regions.iter().enumerate() {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < step {
                break;
            }

            read_region_chunk_robust(pid, addr, chunk_len, &mut chunk_buf, &mut valid_blocks);

            for &(block_addr, block_len, buf_off) in &valid_blocks {
                let block_data = &chunk_buf[buf_off..buf_off + block_len];
                if block_len < step {
                    continue;
                }

                offsets_buf.clear();
                if let Some(t32) = target_bits32 {
                    scan_buffer_obscured32(block_data, t32, &mut offsets_buf);
                } else if let Some(t64) = target_bits64 {
                    scan_buffer_obscured64(block_data, t64, &mut offsets_buf);
                }

                for &off in &offsets_buf {
                    let match_addr = block_addr + off as u64;
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

            addr += chunk_len as u64;
        }
    }

    matches.sort_by_key(|m| m.address);

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

pub fn scan_big_double(
    pid: u32,
    regions: &[MemoryRegion],
    target_str: &str,
) -> Result<ScanSession, String> {
    let target = BigDouble::parse(target_str)?;
    let step = 12;
    let estimated = regions
        .iter()
        .map(|r| r.end.saturating_sub(r.start))
        .sum::<u64>() as usize
        / step
        / 1000;
    let mut matches = Vec::with_capacity(estimated.min(131_072));
    let mut offsets_buf = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut valid_blocks: Vec<(u64, usize, usize)> = Vec::new();

    for (region_idx, region) in regions.iter().enumerate() {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < step {
                break;
            }

            read_region_chunk_robust(pid, addr, chunk_len, &mut chunk_buf, &mut valid_blocks);

            for &(block_addr, block_len, buf_off) in &valid_blocks {
                let block_data = &chunk_buf[buf_off..buf_off + block_len];
                if block_len < step {
                    continue;
                }

                offsets_buf.clear();
                scan_buffer_big_double_to(block_data, &target, &mut offsets_buf);

                for &off in &offsets_buf {
                    let match_addr = block_addr + off as u64;
                    let raw_val = target.mantissa.to_bits();

                    matches.push(CompactMatch {
                        address: match_addr,
                        raw_value: raw_val,
                        region_idx: region_idx as u32,
                        value_type: ValueType::Double,
                    });
                }
            }

            addr += chunk_len as u64;
        }
    }

    matches.sort_by_key(|m| m.address);

    Ok(ScanSession {
        regions: regions.to_vec(),
        matches,
        active_types: vec![ValueType::Double],
    })
}

// ============================================================
// Range Scan
// ============================================================

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

    let min_step = valid_targets
        .iter()
        .map(|(vt, _, _)| vt.size())
        .min()
        .unwrap_or(4);
    let estimated = regions
        .iter()
        .map(|r| r.end.saturating_sub(r.start))
        .sum::<u64>() as usize
        / min_step
        / 1000;
    let mut matches = Vec::with_capacity(estimated.min(131_072));
    let mut offsets_buf: Vec<usize> = Vec::new();
    let mut chunk_buf: Vec<u8> = Vec::with_capacity(CHUNK_SIZE);
    let mut valid_blocks: Vec<(u64, usize, usize)> = Vec::new();

    for (region_idx, region) in regions.iter().enumerate() {
        let mut addr = region.start;
        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < min_step {
                break;
            }

            read_region_chunk_robust(pid, addr, chunk_len, &mut chunk_buf, &mut valid_blocks);

            for &(block_addr, block_len, buf_off) in &valid_blocks {
                let block_data = &chunk_buf[buf_off..buf_off + block_len];

                for (vt, min_bytes, max_bytes) in &valid_targets {
                    let step = vt.size();
                    if block_len < step {
                        continue;
                    }

                    offsets_buf.clear();
                    scan_range_buffer_to(block_data, min_bytes, max_bytes, *vt, &mut offsets_buf);

                    for &off in &offsets_buf {
                        let match_addr = block_addr + off as u64;
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

            addr += chunk_len as u64;
        }
    }

    matches.sort_by(|a, b| {
        a.address
            .cmp(&b.address)
            .then_with(|| a.value_type.size().cmp(&b.value_type.size()))
    });

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
    let mut valid_blocks: Vec<(u64, usize, usize)> = Vec::new();

    for (region_idx, region) in regions.iter().enumerate() {
        let mut addr = region.start;
        let mut overlap_buffer: Vec<u8> = Vec::new();

        while addr < region.end {
            let chunk_len = ((region.end - addr) as usize).min(CHUNK_SIZE);
            if chunk_len < first_step && overlap_buffer.is_empty() {
                break;
            }

            read_region_chunk_robust(pid, addr, chunk_len, &mut chunk_buf, &mut valid_blocks);

            for &(block_addr, block_len, buf_off) in &valid_blocks {
                let mut data = chunk_buf[buf_off..buf_off + block_len].to_vec();

                let overlap_len = overlap_buffer.len();
                if !overlap_buffer.is_empty() {
                    let mut full_data = overlap_buffer.clone();
                    full_data.extend_from_slice(&data);
                    data = full_data;
                }

                let effective_start_addr = block_addr.saturating_sub(overlap_len as u64);
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
                            scan_off += rem_step;
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

                if block_addr + (block_len as u64) < region.end {
                    let overlap_start = data.len().saturating_sub(distance + remaining_size);
                    overlap_buffer = data[overlap_start..].to_vec();
                } else {
                    overlap_buffer.clear();
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
    let mut updated_matches = Vec::new();
    const BATCH_WINDOW: u64 = 65536;

    let mut sorted = session.matches.clone();
    if !sorted.windows(2).all(|w| w[0].address <= w[1].address) {
        sorted.sort_by_key(|m| m.address);
    }

    let max_step = session
        .active_types
        .iter()
        .map(|t| t.size())
        .max()
        .unwrap_or(8);

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + max_step);

    while i < sorted.len() {
        let base = sorted[i].address;
        let region_idx = sorted[i].region_idx;
        let mut j = i;

        while j < sorted.len()
            && sorted[j].address < base + BATCH_WINDOW
            && sorted[j].address >= base
            && sorted[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (sorted[j - 1].address - base) as usize + max_step;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &sorted[i..j] {
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

            let target_bytes = if (operator.is_relative() && target_value_str.trim().is_empty())
                || operator == ScanOperator::Update
            {
                Vec::new()
            } else {
                match value_str_to_bytes(target_value_str, m.value_type) {
                    Ok(b) => b,
                    Err(_) => continue,
                }
            };

            let matches_cond = if operator == ScanOperator::Update {
                true
            } else if operator.is_relative() {
                compare_relative_values(
                    current_bytes,
                    m.raw_value,
                    &target_bytes,
                    m.value_type,
                    operator,
                )
            } else {
                compare_values(current_bytes, &target_bytes, m.value_type, operator)
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
    let mut updated_matches = Vec::new();
    const BATCH_WINDOW: u64 = 65536;

    let mut sorted = session.matches.clone();
    if !sorted.windows(2).all(|w| w[0].address <= w[1].address) {
        sorted.sort_by_key(|m| m.address);
    }

    let max_step = session
        .active_types
        .iter()
        .map(|t| t.size())
        .max()
        .unwrap_or(8);

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + max_step);

    while i < sorted.len() {
        let base = sorted[i].address;
        let region_idx = sorted[i].region_idx;
        let mut j = i;

        while j < sorted.len()
            && sorted[j].address < base + BATCH_WINDOW
            && sorted[j].address >= base
            && sorted[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (sorted[j - 1].address - base) as usize + max_step;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &sorted[i..j] {
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

            let min_bytes = match value_str_to_bytes(min_str, m.value_type) {
                Ok(b) => b,
                Err(_) => continue,
            };
            let max_bytes = match value_str_to_bytes(max_str, m.value_type) {
                Ok(b) => b,
                Err(_) => continue,
            };

            if value_in_range(current_bytes, &min_bytes, &max_bytes, m.value_type) {
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
    let mut updated_matches = Vec::new();
    const BATCH_WINDOW: u64 = 65536;

    let mut sorted = session.matches.clone();
    if !sorted.windows(2).all(|w| w[0].address <= w[1].address) {
        sorted.sort_by_key(|m| m.address);
    }

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + step);

    while i < sorted.len() {
        let base = sorted[i].address;
        let region_idx = sorted[i].region_idx;
        let mut j = i;

        while j < sorted.len()
            && sorted[j].address < base + BATCH_WINDOW
            && sorted[j].address >= base
            && sorted[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (sorted[j - 1].address - base) as usize + step;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &sorted[i..j] {
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
    let mut updated_matches = Vec::new();
    const BATCH_WINDOW: u64 = 65536;
    const MAX_STRUCT_SIZE: usize = 16;

    let mut sorted = session.matches.clone();
    if !sorted.windows(2).all(|w| w[0].address <= w[1].address) {
        sorted.sort_by_key(|m| m.address);
    }

    let mut i = 0;
    let mut block_buf = Vec::with_capacity(BATCH_WINDOW as usize + MAX_STRUCT_SIZE);

    while i < sorted.len() {
        let base = sorted[i].address;
        let region_idx = sorted[i].region_idx;
        let mut j = i;

        while j < sorted.len()
            && sorted[j].address < base + BATCH_WINDOW
            && sorted[j].address >= base
            && sorted[j].region_idx == region_idx
        {
            j += 1;
        }

        let block_len = (sorted[j - 1].address - base) as usize + MAX_STRUCT_SIZE;
        if block_buf.len() < block_len {
            block_buf.resize(block_len, 0);
        }

        let read_ok = kpm::read_memory(pid, base, &mut block_buf[..block_len]).is_ok();

        for m in &sorted[i..j] {
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
    fn test_scan_group_heterogeneous() {
        let mut data = [0u8; 128];
        // Insert f64 1.5 at 16, i64 6 at 24
        data[16..24].copy_from_slice(&1.5f64.to_le_bytes());
        data[24..32].copy_from_slice(&6i64.to_le_bytes());

        let (items, dist) = parse_group_spec("f64:1.5; i64:6 : 16", ValueType::Int).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(dist, 16);
    }
}
