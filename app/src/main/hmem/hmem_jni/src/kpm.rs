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

//! Userspace client for the HMKPM (KernelPatch Module) v3.0.0+.
//!
//! ## Channel
//! `getresuid` syscall using custom command magics:
//! - `HMKPM_MAGIC` (Probe / Liveness / Version, Telemetry & Feature Discovery)
//! - `HMKPM_MAGIC_READ` (Remote virtual address memory read)
//! - `HMKPM_MAGIC_WRITE` (Remote virtual address memory write)
//! - `HMKPM_MAGIC_READ_BATCH` (Batch read of multiple virtual addresses)
//! - `HMKPM_MAGIC_WRITE_BATCH` (Batch write of multiple virtual addresses)
//! - `HMKPM_MAGIC_V2P_BATCH` (Batch virtual-to-physical address & page-flag resolution)
//! - `HMKPM_MAGIC_SCAN_KERNEL` (In-kernel direct memory scan across page tables)
//! - `HMKPM_MAGIC_READ_CHAIN` (In-kernel batched pointer-chain resolution and optional leaf read)
//! - `HMKPM_MAGIC_SCAN_REFINE` (In-kernel candidate refinement and compaction)
//! - `HMKPM_MAGIC_ENUM_REGIONS` (In-kernel hardware page-table mapped-region enumeration)
//!
//! The kernel performs virtual address translation using the target process's `pgd` (`task->mm->pgd`),
//! eliminating userspace PFN translation overhead.

#![allow(dead_code)]

use crate::logger;
use std::sync::RwLock;

pub const HMKPM_VERSION_MAJOR: u16 = 3;
pub const HMKPM_VERSION_MINOR: u16 = 0;
pub const HMKPM_VERSION_PATCH: u16 = 0;
pub const HMKPM_VERSION_CODE: u32 = ((HMKPM_VERSION_MAJOR as u32) << 16)
    | ((HMKPM_VERSION_MINOR as u32) << 8)
    | (HMKPM_VERSION_PATCH as u32);
pub const HMKPM_VERSION_STR: &str = "3.0.0";

pub const HMKPM_MAGIC: u64 = 0x0048_4D4B_504D;
pub const HMKPM_MAGIC_READ: u64 = HMKPM_MAGIC + 1;
pub const HMKPM_MAGIC_WRITE: u64 = HMKPM_MAGIC + 2;
pub const HMKPM_MAGIC_READ_BATCH: u64 = HMKPM_MAGIC + 3;
pub const HMKPM_MAGIC_WRITE_BATCH: u64 = HMKPM_MAGIC + 4;
pub const HMKPM_MAGIC_V2P_BATCH: u64 = HMKPM_MAGIC + 5;
pub const HMKPM_MAGIC_SCAN_KERNEL: u64 = HMKPM_MAGIC + 6;
pub const HMKPM_MAGIC_READ_CHAIN: u64 = HMKPM_MAGIC + 7;
pub const HMKPM_MAGIC_SCAN_REFINE: u64 = HMKPM_MAGIC + 8;
pub const HMKPM_MAGIC_ENUM_REGIONS: u64 = HMKPM_MAGIC + 9;
pub const HMKPM_MAGIC_MAX: u64 = HMKPM_MAGIC_ENUM_REGIONS;

// Feature Flags
pub const HMKPM_FEATURE_READ: u64 = 1 << 0;
pub const HMKPM_FEATURE_WRITE: u64 = 1 << 1;
pub const HMKPM_FEATURE_READ_BATCH: u64 = 1 << 2;
pub const HMKPM_FEATURE_WRITE_BATCH: u64 = 1 << 3;
pub const HMKPM_FEATURE_V2P_BATCH: u64 = 1 << 4;
pub const HMKPM_FEATURE_SCAN_KERNEL: u64 = 1 << 5;
pub const HMKPM_FEATURE_LOCKLESS: u64 = 1 << 6;
pub const HMKPM_FEATURE_READ_CHAIN: u64 = 1 << 7;
pub const HMKPM_FEATURE_SCAN_REFINE: u64 = 1 << 8;
pub const HMKPM_FEATURE_ENUM_REGIONS: u64 = 1 << 9;
pub const HMKPM_FEATURE_SCAN_EXT: u64 = 1 << 10;
pub const HMKPM_FEATURE_FSNOTIFY_FILTER: u64 = 1 << 11;
pub const HMKPM_ALL_FEATURES: u64 = HMKPM_FEATURE_READ
    | HMKPM_FEATURE_WRITE
    | HMKPM_FEATURE_READ_BATCH
    | HMKPM_FEATURE_WRITE_BATCH
    | HMKPM_FEATURE_V2P_BATCH
    | HMKPM_FEATURE_SCAN_KERNEL
    | HMKPM_FEATURE_READ_CHAIN
    | HMKPM_FEATURE_SCAN_REFINE
    | HMKPM_FEATURE_ENUM_REGIONS
    | HMKPM_FEATURE_SCAN_EXT;

// Runtime Telemetry Enumerations (struct hmkpm_version_info)
pub const HMKPM_LOCK_SRC_LOCKLESS: u8 = 0;
pub const HMKPM_LOCK_SRC_BTF: u8 = 1;
pub const HMKPM_LOCK_SRC_GKI_VALIDATED: u8 = 2;
pub const HMKPM_LOCK_SRC_DISASM_LEGACY: u8 = 3;

pub const HMKPM_SW_PAN_OFF: u8 = 0;
pub const HMKPM_SW_PAN_KERNEL_UACCESS: u8 = 1;
pub const HMKPM_SW_PAN_MANUAL_TTBR0: u8 = 2;

pub const HMKPM_FSNOTIFY_OFF: u8 = 0;
pub const HMKPM_FSNOTIFY_GLOBAL: u8 = 1;
pub const HMKPM_FSNOTIFY_CALLER_PIDS: u8 = 2;

// Page Flags for V2P
pub const HMKPM_PAGE_FLAG_PRESENT: u32 = 1 << 0;
pub const HMKPM_PAGE_FLAG_RO: u32 = 1 << 1;
pub const HMKPM_PAGE_FLAG_DIRTY: u32 = 1 << 2;
pub const HMKPM_PAGE_FLAG_BLOCK: u32 = 1 << 3;

// Scan Types
pub const HMKPM_SCAN_TYPE_I8: u8 = 1;
pub const HMKPM_SCAN_TYPE_U8: u8 = 2;
pub const HMKPM_SCAN_TYPE_I16: u8 = 3;
pub const HMKPM_SCAN_TYPE_U16: u8 = 4;
pub const HMKPM_SCAN_TYPE_I32: u8 = 5;
pub const HMKPM_SCAN_TYPE_U32: u8 = 6;
pub const HMKPM_SCAN_TYPE_I64: u8 = 7;
pub const HMKPM_SCAN_TYPE_U64: u8 = 8;
pub const HMKPM_SCAN_TYPE_F32: u8 = 9;
pub const HMKPM_SCAN_TYPE_F64: u8 = 10;
pub const HMKPM_SCAN_TYPE_BYTES: u8 = 11;

// Scan Operations
pub const HMKPM_SCAN_OP_EXACT: u8 = 1;
pub const HMKPM_SCAN_OP_RANGE: u8 = 2;
pub const HMKPM_SCAN_OP_MASK: u8 = 3;
pub const HMKPM_SCAN_OP_NEQ: u8 = 4;
pub const HMKPM_SCAN_OP_NOT_RANGE: u8 = 5;

// Scan Header Flags (struct hmkpm_scan_hdr.flags)
/// Default (`0`): early-stop once `max_matches` entries are stored (when `max_matches > 0`).
/// Set `HMKPM_SCAN_FLAG_COUNT_ALL` to continue scanning all ranges after reaching `max_matches`
/// and compute the exact total match count in `matches_found`.
pub const HMKPM_SCAN_FLAG_COUNT_ALL: u8 = 1 << 0;
/// Output `HmkpmScanValMatch` `(va, value)` pairs instead of bare `u64` VAs (scalar types I8..F64).
pub const HMKPM_SCAN_FLAG_WITH_VALUES: u8 = 1 << 1;
/// Resume from input `(resume_range_idx, resume_va)` cursor.
pub const HMKPM_SCAN_FLAG_RESUME: u8 = 1 << 2;
pub const HMKPM_SCAN_FLAGS_MASK: u8 =
    HMKPM_SCAN_FLAG_COUNT_ALL | HMKPM_SCAN_FLAG_WITH_VALUES | HMKPM_SCAN_FLAG_RESUME;

pub const HMKPM_SCAN_STATUS_COMPLETE: u32 = 0;
pub const HMKPM_SCAN_STATUS_TRUNCATED: u32 = 1;

// Pointer Chain Resolution Flags, Status & Limits
pub const HMKPM_CHAIN_FLAG_DEREF_LAST: u16 = 1 << 0;
pub const HMKPM_CHAIN_FLAG_PTR32: u16 = 1 << 1;
pub const HMKPM_CHAIN_FLAGS_MASK: u16 = HMKPM_CHAIN_FLAG_DEREF_LAST | HMKPM_CHAIN_FLAG_PTR32;

pub const HMKPM_CHAIN_STATUS_OK: u32 = 0;
pub const HMKPM_CHAIN_STATUS_FAULT_HOP: u32 = 1;
pub const HMKPM_CHAIN_STATUS_FAULT_DATA: u32 = 2;
pub const HMKPM_CHAIN_STATUS_BAD_REQ: u32 = 3;

pub const HMKPM_MAX_CHAIN_COUNT: usize = 1024;
pub const HMKPM_MAX_CHAIN_DEPTH: usize = 32;
pub const HMKPM_MAX_CHAIN_TOTAL_OFFSETS: usize = 16384;
pub const HMKPM_MAX_CHAIN_READ_SIZE: usize = 4096;
pub const HMKPM_MAX_CHAIN_DATA_TOTAL: usize = 4 * 1024 * 1024;

// In-Kernel Scan Refinement Operations, Flags & Limits
pub const HMKPM_REFINE_OP_EXACT: u8 = 1;
pub const HMKPM_REFINE_OP_NEQ: u8 = 2;
pub const HMKPM_REFINE_OP_RANGE: u8 = 3;
pub const HMKPM_REFINE_OP_NOT_RANGE: u8 = 4;
pub const HMKPM_REFINE_OP_MASK: u8 = 5;
pub const HMKPM_REFINE_OP_CHANGED: u8 = 6;
pub const HMKPM_REFINE_OP_UNCHANGED: u8 = 7;
pub const HMKPM_REFINE_OP_INCREASED: u8 = 8;
pub const HMKPM_REFINE_OP_DECREASED: u8 = 9;

pub const HMKPM_REFINE_FLAG_WITH_VALUES: u8 = 1 << 0;
pub const HMKPM_REFINE_FLAGS_MASK: u8 = HMKPM_REFINE_FLAG_WITH_VALUES;
pub const HMKPM_MAX_REFINE_COUNT: usize = 1048576;

// Mapped-Region Enumeration Flags & Limits
pub const HMKPM_REGION_FLAG_READ: u32 = 1 << 0;
pub const HMKPM_REGION_FLAG_WRITE: u32 = 1 << 1;
pub const HMKPM_REGION_FLAG_EXEC: u32 = 1 << 2;
pub const HMKPM_REGION_FLAG_HAS_BLOCK: u32 = 1 << 3;

pub const HMKPM_ENUM_FLAG_WRITABLE_ONLY: u32 = 1 << 0;
pub const HMKPM_ENUM_FLAG_EXEC_ONLY: u32 = 1 << 1;
pub const HMKPM_ENUM_FLAGS_MASK: u32 = HMKPM_ENUM_FLAG_WRITABLE_ONLY | HMKPM_ENUM_FLAG_EXEC_ONLY;
pub const HMKPM_MAX_ENUM_REGIONS: usize = 16384;

pub const HMKPM_MAX_SINGLE_SIZE: usize = 64 * 1024 * 1024;
pub const HMKPM_MAX_ENTRY_SIZE: usize = 64 * 1024 * 1024;
pub const HMKPM_MAX_BATCH_ENTRIES: usize = 65536;
pub const HMKPM_MAX_BATCH_TOTAL_SIZE: usize = 128 * 1024 * 1024;

pub const HMKPM_V2P_CHUNK_ENTRIES: usize = 64;
pub const HMKPM_MAX_V2P_ENTRIES: usize = 65536;

pub const HMKPM_MAX_SCAN_RANGES: usize = 4096;
pub const HMKPM_MAX_PATTERN_LEN: usize = 256;
pub const HMKPM_MAX_SCAN_MATCHES: usize = 1048576;
pub const HMKPM_SCAN_MATCH_CHUNK: usize = 16;

/// Version info size for `hmkpm_version_info` (80 bytes).
pub const HMKPM_VERSION_INFO_SIZE: usize = 80;
/// Header size for `hmkpm_req` (24 bytes).
pub const HMKPM_REQ_SIZE: usize = 24;
/// Header size for `hmkpm_batch_hdr` (24 bytes).
pub const HMKPM_BATCH_HDR_SIZE: usize = 24;
/// Entry size for `hmkpm_batch_entry` (16 bytes).
pub const HMKPM_BATCH_ENTRY_SIZE: usize = 16;
/// Header size for `hmkpm_v2p_hdr` (16 bytes).
pub const HMKPM_V2P_HDR_SIZE: usize = 16;
/// Entry size for `hmkpm_v2p_entry` (24 bytes).
pub const HMKPM_V2P_ENTRY_SIZE: usize = 24;
/// Entry size for `hmkpm_scan_range` (16 bytes).
pub const HMKPM_SCAN_RANGE_SIZE: usize = 16;
/// Entry size for `hmkpm_scan_val_match` (16 bytes).
pub const HMKPM_SCAN_VAL_MATCH_SIZE: usize = 16;
/// Header size for `hmkpm_scan_hdr` (64 bytes).
pub const HMKPM_SCAN_HDR_SIZE: usize = 64;
/// Header size for `hmkpm_chain_hdr` (24 bytes).
pub const HMKPM_CHAIN_HDR_SIZE: usize = 24;
/// Entry size for `hmkpm_chain_req` (32 bytes).
pub const HMKPM_CHAIN_REQ_SIZE: usize = 32;
/// Header size for `hmkpm_refine_hdr` (48 bytes).
pub const HMKPM_REFINE_HDR_SIZE: usize = 48;
/// Header size for `hmkpm_enum_hdr` (40 bytes).
pub const HMKPM_ENUM_HDR_SIZE: usize = 40;
/// Entry size for `hmkpm_region_entry` (24 bytes).
pub const HMKPM_REGION_ENTRY_SIZE: usize = 24;

#[allow(dead_code)]
const SYS_GETRESUID: libc::c_long = 148;

/// Version and runtime telemetry layout matching `struct hmkpm_version_info` in KPM v3.0.0 (80 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug, Default)]
pub struct HmkpmVersionInfo {
    pub magic: u64,
    pub struct_size: u32,
    pub version_code: u32,
    pub version_major: u16,
    pub version_minor: u16,
    pub version_patch: u16,
    pub is_lockless: u8,
    pub lock_source: u8,
    pub mmap_lock_offset: i32,
    pub page_size: u32,
    pub kver: u32,
    pub va_bits: u8,
    pub tbi0: u8,
    pub sw_pan_mode: u8,
    pub fsnotify_mode: u8,
    pub features: u64,
    pub reserved: [u64; 2],
    pub version_str: [u8; 16],
}

impl HmkpmVersionInfo {
    pub fn version_string(&self) -> String {
        let nul_pos = self
            .version_str
            .iter()
            .position(|&b| b == 0)
            .unwrap_or(self.version_str.len());
        String::from_utf8_lossy(&self.version_str[..nul_pos]).to_string()
    }

    pub fn is_lockless_mode(&self) -> bool {
        self.is_lockless != 0
            || (self.features & HMKPM_FEATURE_LOCKLESS) != 0
            || self.mmap_lock_offset < 0
    }

    pub fn lock_source_str(&self) -> &'static str {
        match self.lock_source {
            HMKPM_LOCK_SRC_BTF => "btf",
            HMKPM_LOCK_SRC_GKI_VALIDATED => "gki_validated",
            HMKPM_LOCK_SRC_DISASM_LEGACY => "disasm_legacy",
            _ => "lockless",
        }
    }

    pub fn sw_pan_mode_str(&self) -> &'static str {
        match self.sw_pan_mode {
            HMKPM_SW_PAN_KERNEL_UACCESS => "kernel_uaccess",
            HMKPM_SW_PAN_MANUAL_TTBR0 => "manual_ttbr0",
            _ => "off",
        }
    }

    pub fn fsnotify_mode_str(&self) -> &'static str {
        match self.fsnotify_mode {
            HMKPM_FSNOTIFY_GLOBAL => "global",
            HMKPM_FSNOTIFY_CALLER_PIDS => "pids",
            _ => "off",
        }
    }

    pub fn kver_string(&self) -> String {
        if self.kver == 0 {
            return String::new();
        }
        let major = (self.kver >> 16) & 0xFF;
        let minor = (self.kver >> 8) & 0xFF;
        let patch = self.kver & 0xFF;
        format!("{major}.{minor}.{patch}")
    }
}

/// Request header for single read/write operations — layout identical to `struct hmkpm_req` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmReq {
    pub pid: i32,
    pub _pad: u32,
    pub addr: u64,
    pub size: u64,
}

/// Request header for batch operations — layout identical to `struct hmkpm_batch_hdr` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmBatchHdr {
    pub pid: i32,
    pub _pad: u32,
    pub count: u64,
    pub data_total: u64,
}

/// Batch entry — layout identical to `struct hmkpm_batch_entry` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmBatchEntry {
    pub addr: u64,
    pub size: u64,
}

/// Header for V2P translation — layout identical to `struct hmkpm_v2p_hdr` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmV2pHdr {
    pub pid: i32,
    pub _pad: u32,
    pub count: u64,
}

/// Entry for V2P translation — layout identical to `struct hmkpm_v2p_entry` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug, Default)]
pub struct HmkpmV2pEntry {
    pub va: u64,
    pub pa: u64,
    pub flags: u32,
    pub page_size: u32,
}

/// Scan range descriptor — layout identical to `struct hmkpm_scan_range` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmScanRange {
    pub start_va: u64,
    pub size: u64,
}

/// Scan match pair `(va, raw_value)` — layout identical to `struct hmkpm_scan_val_match` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HmkpmScanValMatch {
    pub va: u64,
    pub value: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmScanCriteriaExact {
    pub val: u64,
    pub mask: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmScanCriteriaRangeU {
    pub min: u64,
    pub max: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmScanCriteriaRangeI {
    pub min: i64,
    pub max: i64,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union HmkpmScanCriteria {
    pub exact: HmkpmScanCriteriaExact,
    pub range_u: HmkpmScanCriteriaRangeU,
    pub range_i: HmkpmScanCriteriaRangeI,
}

/// Scan header — layout identical to `struct hmkpm_scan_hdr` in KPM v3.0.0 (64 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy)]
pub struct HmkpmScanHdr {
    pub pid: i32,
    pub scan_type: u8,
    pub op: u8,
    pub align: u8,
    pub flags: u8,
    pub range_count: u32,
    pub max_matches: u32,
    pub pattern_len: u64,
    pub criteria: HmkpmScanCriteria,
    pub matches_found: u64,
    pub resume_va: u64,
    pub resume_range_idx: u32,
    pub scan_status: u32,
}

/// Pointer chain header — layout identical to `struct hmkpm_chain_hdr` in KPM v3.0.0 (24 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmChainHdr {
    pub pid: i32,
    pub count: u32,
    pub total_offsets: u64,
    pub data_total: u64,
}

/// Pointer chain entry — layout identical to `struct hmkpm_chain_req` in KPM v3.0.0 (32 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug, Default)]
pub struct HmkpmChainReq {
    pub base_va: u64,
    pub resolved_va: u64,
    pub depth: u32,
    pub read_size: u32,
    pub flags: u16,
    pub hops_done: u16,
    pub status: u32,
}

/// Scan refinement header — layout identical to `struct hmkpm_refine_hdr` in KPM v3.0.0 (48 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy)]
pub struct HmkpmRefineHdr {
    pub pid: i32,
    pub scan_type: u8,
    pub op: u8,
    pub flags: u8,
    pub _pad: u8,
    pub count: u32,
    pub max_matches: u32,
    pub criteria: HmkpmScanCriteria,
    pub matches_found: u64,
    pub _reserved: u64,
}

/// Mapped-region enumeration header — layout identical to `struct hmkpm_enum_hdr` in KPM v3.0.0 (40 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug)]
pub struct HmkpmEnumHdr {
    pub pid: i32,
    pub flags: u32,
    pub start_va: u64,
    pub end_va: u64,
    pub max_regions: u32,
    pub regions_found: u32,
    pub next_va: u64,
}

/// Mapped-region entry — layout identical to `struct hmkpm_region_entry` in KPM v3.0.0 (24 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HmkpmRegionEntry {
    pub start_va: u64,
    pub size: u64,
    pub flags: u32,
    pub _pad: u32,
}

const _: () = assert!(std::mem::size_of::<HmkpmVersionInfo>() == HMKPM_VERSION_INFO_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmVersionInfo>() == 8);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, struct_size) == 8);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, version_code) == 12);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, lock_source) == 23);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, mmap_lock_offset) == 24);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, page_size) == 28);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, kver) == 32);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, va_bits) == 36);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, features) == 40);
const _: () = assert!(std::mem::offset_of!(HmkpmVersionInfo, version_str) == 64);
const _: () = assert!(std::mem::size_of::<HmkpmReq>() == HMKPM_REQ_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmReq>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmBatchHdr>() == HMKPM_BATCH_HDR_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmBatchHdr>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmBatchEntry>() == HMKPM_BATCH_ENTRY_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmBatchEntry>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmV2pHdr>() == HMKPM_V2P_HDR_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmV2pHdr>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmV2pEntry>() == HMKPM_V2P_ENTRY_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmV2pEntry>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmScanRange>() == HMKPM_SCAN_RANGE_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmScanRange>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmScanValMatch>() == HMKPM_SCAN_VAL_MATCH_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmScanValMatch>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmScanHdr>() == HMKPM_SCAN_HDR_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmScanHdr>() == 8);
const _: () = assert!(std::mem::offset_of!(HmkpmScanHdr, matches_found) == 40);
const _: () = assert!(std::mem::offset_of!(HmkpmScanHdr, resume_va) == 48);
const _: () = assert!(std::mem::offset_of!(HmkpmScanHdr, resume_range_idx) == 56);
const _: () = assert!(std::mem::offset_of!(HmkpmScanHdr, scan_status) == 60);
const _: () = assert!(std::mem::size_of::<HmkpmChainHdr>() == HMKPM_CHAIN_HDR_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmChainHdr>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmChainReq>() == HMKPM_CHAIN_REQ_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmChainReq>() == 8);
const _: () = assert!(std::mem::size_of::<HmkpmRefineHdr>() == HMKPM_REFINE_HDR_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmRefineHdr>() == 8);
const _: () = assert!(std::mem::offset_of!(HmkpmRefineHdr, matches_found) == 32);
const _: () = assert!(std::mem::size_of::<HmkpmEnumHdr>() == HMKPM_ENUM_HDR_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmEnumHdr>() == 8);
const _: () = assert!(std::mem::offset_of!(HmkpmEnumHdr, regions_found) == 28);
const _: () = assert!(std::mem::offset_of!(HmkpmEnumHdr, next_va) == 32);
const _: () = assert!(std::mem::size_of::<HmkpmRegionEntry>() == HMKPM_REGION_ENTRY_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmRegionEntry>() == 8);

/// Stack buffer with strict 8-byte alignment to prevent unaligned memory access penalties in the kernel.
#[repr(C, align(8))]
struct AlignedStackBuf<const N: usize> {
    data: [u8; N],
}

impl<const N: usize> AlignedStackBuf<N> {
    #[inline(always)]
    const fn new() -> Self {
        Self { data: [0u8; N] }
    }
}

/// Heap buffer with guaranteed 8-byte alignment to prevent unaligned access UB and trap instructions.
struct AlignedHeapBuf {
    data: Vec<u64>,
    len: usize,
}

impl AlignedHeapBuf {
    #[inline]
    fn new(size_bytes: usize) -> Self {
        let u64_count = size_bytes.div_ceil(8);
        Self {
            data: vec![0u64; u64_count],
            len: size_bytes,
        }
    }

    #[inline]
    fn as_mut_ptr(&mut self) -> *mut u8 {
        self.data.as_mut_ptr() as *mut u8
    }

    #[inline]
    fn as_ptr(&self) -> *const u8 {
        self.data.as_ptr() as *const u8
    }
}

impl std::ops::Deref for AlignedHeapBuf {
    type Target = [u8];
    #[inline]
    fn deref(&self) -> &Self::Target {
        // SAFETY: `data` is initialized and valid for at least `len` bytes.
        unsafe { std::slice::from_raw_parts(self.data.as_ptr() as *const u8, self.len) }
    }
}

impl std::ops::DerefMut for AlignedHeapBuf {
    #[inline]
    fn deref_mut(&mut self) -> &mut Self::Target {
        // SAFETY: `data` is initialized and valid for at least `len` bytes.
        unsafe { std::slice::from_raw_parts_mut(self.data.as_mut_ptr() as *mut u8, self.len) }
    }
}

/// Structured error returned by checked HMKPM write operations so callers such as
/// `FreezeEngine` and `batch_write` can react deterministically to Lockless Mode
/// or dead target processes without parsing error strings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum KpmError {
    /// Writes are disabled because HMKPM is operating in Lockless Mode (`EOPNOTSUPP`).
    Lockless(String),
    /// Target process no longer exists (`ESRCH`).
    ProcessDead { pid: u32, message: String },
    /// General validation, mapping, or kernel I/O error.
    Io(String),
}

impl std::fmt::Display for KpmError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Lockless(msg) | Self::Io(msg) => f.write_str(msg),
            Self::ProcessDead { message, .. } => f.write_str(message),
        }
    }
}

impl From<KpmError> for String {
    fn from(err: KpmError) -> Self {
        err.to_string()
    }
}

/// Classifies a write syscall errno into a structured `KpmError`.
#[inline]
pub(crate) fn classify_write_errno(pid: u32, raw_errno: i32, context: String) -> KpmError {
    if raw_errno == libc::EOPNOTSUPP || raw_errno == 95 {
        KpmError::Lockless(
            "HMKPM write disallowed: Kernel module is operating in Lockless Mode (mmap_lock unresolved)".to_string(),
        )
    } else if raw_errno == libc::ESRCH || raw_errno == 3 {
        KpmError::ProcessDead {
            pid,
            message: format!("Target process pid={pid} no longer exists ({context})"),
        }
    } else {
        KpmError::Io(context)
    }
}

/// Reusable 8-byte aligned read buffer for zero-copy HMKPM chunk reads.
///
/// Keeps the `HmkpmReq` header in `[0..HMKPM_REQ_SIZE]` and receives kernel data buffer
/// bytes directly in `[HMKPM_REQ_SIZE..HMKPM_REQ_SIZE + size]`, eliminating per-chunk
/// heap allocations, zero-initialization, and duplicate copies inside hot scan loops.
pub struct KpmReadBuffer {
    buf: AlignedHeapBuf,
    max_payload: usize,
}

impl KpmReadBuffer {
    /// Pre-allocates a reusable aligned buffer capable of reading up to `max_payload_bytes`.
    pub fn with_capacity(max_payload_bytes: usize) -> Self {
        let clamped = max_payload_bytes.min(HMKPM_MAX_SINGLE_SIZE);
        Self {
            buf: AlignedHeapBuf::new(HMKPM_REQ_SIZE + clamped),
            max_payload: clamped,
        }
    }

    /// Reads `size` bytes from `pid` at `addr` into the internal buffer and returns
    /// a zero-copy slice of the received data buffer.
    pub fn read(&mut self, pid: u32, addr: u64, size: usize) -> Result<&[u8], String> {
        if size == 0 {
            return Ok(&[]);
        }
        if pid == 0 {
            return Err("PID cannot be 0".to_string());
        }
        if size > HMKPM_MAX_SINGLE_SIZE {
            return Err(format!(
                "Read size {size} exceeds max single size {HMKPM_MAX_SINGLE_SIZE}"
            ));
        }

        let total_len = HMKPM_REQ_SIZE + size;
        if size > self.max_payload {
            self.buf = AlignedHeapBuf::new(total_len);
            self.max_payload = size;
        }

        let req_ptr = self.buf.as_mut_ptr() as *mut HmkpmReq;
        // SAFETY: `self.buf` is 8-byte aligned and has length >= `HMKPM_REQ_SIZE + size`.
        unsafe {
            std::ptr::write(
                req_ptr,
                HmkpmReq {
                    pid: pid as i32,
                    _pad: 0,
                    addr,
                    size: size as u64,
                },
            );
        }

        // SAFETY: `self.buf` is valid, 8-byte aligned, and at least `total_len` bytes long.
        let ret = unsafe { kpm_syscall(HMKPM_MAGIC_READ, self.buf.as_mut_ptr(), total_len as u64) };
        if ret != 0 {
            return Err(format!(
                "HMKPM read failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={}",
                std::io::Error::last_os_error()
            ));
        }

        Ok(&self.buf[HMKPM_REQ_SIZE..total_len])
    }
}

static CACHED_VERSION: RwLock<Option<HmkpmVersionInfo>> = RwLock::new(None);

/// Clears the cached HMKPM probe metadata so subsequent calls re-query the kernel module.
pub fn invalidate_version_cache() {
    let mut guard = CACHED_VERSION.write().unwrap_or_else(|e| e.into_inner());
    *guard = None;
}

/// Validates the magic response, ABI struct size, and pinned v3.0.0 version metadata.
pub(crate) fn validate_version_info(ret: i64, info: &HmkpmVersionInfo) -> Result<(), String> {
    let magic_low = (ret as u64) & 0x00FF_FFFF_FFFF;
    if ret < 0 || magic_low != HMKPM_MAGIC {
        return Err(format!(
            "HMKPM not available (syscall ret={ret}, errno={})",
            std::io::Error::last_os_error()
        ));
    }

    if info.magic != HMKPM_MAGIC || info.struct_size as usize != HMKPM_VERSION_INFO_SIZE {
        let ret_vcode = ((ret as u64) >> 40) as u32;
        let maj = (ret_vcode >> 16) & 0xFF;
        let min = (ret_vcode >> 8) & 0xFF;
        let pat = ret_vcode & 0xFF;
        return Err(format!(
            "Incompatible HMKPM ABI: pinned to v{HMKPM_VERSION_STR} (struct_size={HMKPM_VERSION_INFO_SIZE}), detected v{maj}.{min}.{pat} (magic=0x{:x}, struct_size={})",
            info.magic, info.struct_size
        ));
    }

    if info.version_code != HMKPM_VERSION_CODE
        || info.version_major != HMKPM_VERSION_MAJOR
        || info.version_minor != HMKPM_VERSION_MINOR
        || info.version_patch != HMKPM_VERSION_PATCH
    {
        return Err(format!(
            "Incompatible HMKPM version: pinned to v{HMKPM_VERSION_STR} (code=0x{HMKPM_VERSION_CODE:06x}), detected v{}.{}.{} (code=0x{:06x})",
            info.version_major, info.version_minor, info.version_patch, info.version_code
        ));
    }

    Ok(())
}

/// Internal uncached HMKPM v3.0.0 syscall probe.
fn probe_version_uncached() -> Result<HmkpmVersionInfo, String> {
    let mut info = HmkpmVersionInfo::default();
    let info_ptr = &mut info as *mut HmkpmVersionInfo as *mut u8;

    // SAFETY: info_ptr points to a stack-allocated HmkpmVersionInfo struct of exactly HMKPM_VERSION_INFO_SIZE bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC, info_ptr, HMKPM_VERSION_INFO_SIZE as u64) };

    validate_version_info(ret, &info)?;

    logger::info(
        "HMKPM",
        &format!(
            "Probe OK: HMKPM v{} (code=0x{:06x}, kver={}, page={}, va_bits={}, tbi0={}, lockless={}, lock_src={}, mmap_offset={}, sw_pan={}, fsnotify={}, features=0x{:x})",
            info.version_string(),
            info.version_code,
            info.kver_string(),
            info.page_size,
            info.va_bits,
            info.tbi0,
            info.is_lockless_mode(),
            info.lock_source_str(),
            info.mmap_lock_offset,
            info.sw_pan_mode_str(),
            info.fsnotify_mode_str(),
            info.features
        ),
    );

    Ok(info)
}

/// # Safety
///
/// `ptr` must point to a valid, readable/writable memory buffer of at least `len` bytes.
#[cfg(unix)]
#[inline]
unsafe fn kpm_syscall(magic: u64, ptr: *mut u8, len: u64) -> i64 {
    // SAFETY: The caller guarantees `ptr` and `len` describe a valid buffer.
    unsafe { libc::syscall(SYS_GETRESUID, magic, ptr, len) as i64 }
}

/// # Safety
///
/// Fallback for non-unix targets.
#[cfg(not(unix))]
#[inline]
unsafe fn kpm_syscall(_magic: u64, _ptr: *mut u8, _len: u64) -> i64 {
    -1
}

/// PROBE & VERSION (fresh): skips `CACHED_VERSION`, re-queries the live HMKPM module,
/// and updates or clears the cache according to the live result.
pub fn probe_version_fresh() -> Result<HmkpmVersionInfo, String> {
    match probe_version_uncached() {
        Ok(info) => {
            let mut guard = CACHED_VERSION.write().unwrap_or_else(|e| e.into_inner());
            *guard = Some(info);
            Ok(info)
        }
        Err(err) => {
            invalidate_version_cache();
            Err(err)
        }
    }
}

/// PROBE & VERSION: returns cached HMKPM metadata if available, otherwise probes once.
pub fn probe_version() -> Result<HmkpmVersionInfo, String> {
    {
        let guard = CACHED_VERSION.read().unwrap_or_else(|e| e.into_inner());
        if let Some(info) = *guard {
            return Ok(info);
        }
    }

    let info = probe_version_uncached()?;
    let mut guard = CACHED_VERSION.write().unwrap_or_else(|e| e.into_inner());
    *guard = Some(info);
    Ok(info)
}

/// PROBE: checks if the HMKPM module is loaded and responding to the syscall.
pub fn probe() -> Result<(), String> {
    probe_version().map(|_| ())
}

/// Returns true if the active HMKPM module supports the given feature flag.
pub fn is_feature_supported(feature: u64) -> bool {
    match probe_version() {
        Ok(info) => (info.features & feature) == feature,
        Err(_) => false,
    }
}

/// Reads `buf.len()` bytes of virtual memory from process `pid` starting at virtual address `addr`.
pub fn read_memory(pid: u32, addr: u64, buf: &mut [u8]) -> Result<(), String> {
    let size = buf.len();
    if size == 0 {
        return Ok(());
    }

    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }

    if size > HMKPM_MAX_SINGLE_SIZE {
        return Err(format!(
            "Read size {size} exceeds max single size {HMKPM_MAX_SINGLE_SIZE}"
        ));
    }

    let total_len = HMKPM_REQ_SIZE + size;

    // Small reads optimization (<= 256 bytes): uses 8-byte aligned stack buffer without extra allocations
    if size <= 256 {
        let mut stack_buf: AlignedStackBuf<{ HMKPM_REQ_SIZE + 256 }> = AlignedStackBuf::new();
        let req_ptr = stack_buf.data.as_mut_ptr() as *mut HmkpmReq;

        // SAFETY: req_ptr is valid and 8-byte aligned within stack_buf.
        unsafe {
            std::ptr::write(
                req_ptr,
                HmkpmReq {
                    pid: pid as i32,
                    _pad: 0,
                    addr,
                    size: size as u64,
                },
            );
        }

        // SAFETY: stack_buf is a valid aligned buffer of total_len bytes.
        let ret = unsafe {
            kpm_syscall(
                HMKPM_MAGIC_READ,
                stack_buf.data.as_mut_ptr(),
                total_len as u64,
            )
        };

        if ret != 0 {
            return Err(format!(
                "HMKPM read failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={}",
                std::io::Error::last_os_error()
            ));
        }

        buf.copy_from_slice(&stack_buf.data[HMKPM_REQ_SIZE..total_len]);
        return Ok(());
    }

    let mut req_buf = AlignedHeapBuf::new(total_len);
    let req_ptr = req_buf.as_mut_ptr() as *mut HmkpmReq;

    // SAFETY: req_buf is allocated with total_len >= HMKPM_REQ_SIZE and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            req_ptr,
            HmkpmReq {
                pid: pid as i32,
                _pad: 0,
                addr,
                size: size as u64,
            },
        );
    }

    // SAFETY: req_buf is a valid aligned heap buffer of total_len bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_READ, req_buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM read failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    buf.copy_from_slice(&req_buf[HMKPM_REQ_SIZE..total_len]);
    Ok(())
}

/// Writes `data` into the virtual memory of process `pid` at virtual address `addr`,
/// returning a structured `KpmError` to distinguish Lockless Mode and dead PIDs.
pub fn write_memory_checked(pid: u32, addr: u64, data: &[u8]) -> Result<(), KpmError> {
    let size = data.len();
    if size == 0 {
        return Ok(());
    }

    if pid == 0 {
        return Err(KpmError::Io("PID cannot be 0".to_string()));
    }

    if size > HMKPM_MAX_SINGLE_SIZE {
        return Err(KpmError::Io(format!(
            "Write size {size} exceeds max single size {HMKPM_MAX_SINGLE_SIZE}"
        )));
    }

    let total_len = HMKPM_REQ_SIZE + size;

    // Small writes optimization (<= 256 bytes): uses 8-byte aligned stack buffer without heap allocation
    if size <= 256 {
        let mut stack_buf: AlignedStackBuf<{ HMKPM_REQ_SIZE + 256 }> = AlignedStackBuf::new();
        let req_ptr = stack_buf.data.as_mut_ptr() as *mut HmkpmReq;

        // SAFETY: req_ptr is valid and 8-byte aligned within stack_buf.
        unsafe {
            std::ptr::write(
                req_ptr,
                HmkpmReq {
                    pid: pid as i32,
                    _pad: 0,
                    addr,
                    size: size as u64,
                },
            );
        }
        stack_buf.data[HMKPM_REQ_SIZE..total_len].copy_from_slice(data);

        // SAFETY: stack_buf is a valid aligned buffer of total_len bytes.
        let ret = unsafe {
            kpm_syscall(
                HMKPM_MAGIC_WRITE,
                stack_buf.data.as_mut_ptr(),
                total_len as u64,
            )
        };

        if ret != 0 {
            let err = std::io::Error::last_os_error();
            let raw_errno = err.raw_os_error().unwrap_or(0);
            return Err(classify_write_errno(
                pid,
                raw_errno,
                format!(
                    "HMKPM write failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={err}"
                ),
            ));
        }

        return Ok(());
    }

    let mut req_buf = AlignedHeapBuf::new(total_len);
    let req_ptr = req_buf.as_mut_ptr() as *mut HmkpmReq;

    // SAFETY: req_buf is allocated with total_len >= HMKPM_REQ_SIZE and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            req_ptr,
            HmkpmReq {
                pid: pid as i32,
                _pad: 0,
                addr,
                size: size as u64,
            },
        );
    }
    req_buf[HMKPM_REQ_SIZE..total_len].copy_from_slice(data);

    // SAFETY: req_buf is a valid aligned heap buffer of total_len bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_WRITE, req_buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        let err = std::io::Error::last_os_error();
        let raw_errno = err.raw_os_error().unwrap_or(0);
        return Err(classify_write_errno(
            pid,
            raw_errno,
            format!(
                "HMKPM write failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={err}"
            ),
        ));
    }

    Ok(())
}

/// Writes `data` into the virtual memory of process `pid` at virtual address `addr`.
pub fn write_memory(pid: u32, addr: u64, data: &[u8]) -> Result<(), String> {
    write_memory_checked(pid, addr, data).map_err(String::from)
}

/// Executes batch reads across multiple addresses in a single kernel syscall.
/// Returns a vector with the read buffers.
#[allow(dead_code)]
pub fn read_batch(pid: u32, requests: &[(u64, usize)]) -> Result<Vec<Vec<u8>>, String> {
    if requests.is_empty() {
        return Ok(Vec::new());
    }

    if requests.len() > HMKPM_MAX_BATCH_ENTRIES {
        return Err(format!(
            "Batch count {} exceeds max entries {}",
            requests.len(),
            HMKPM_MAX_BATCH_ENTRIES
        ));
    }

    let mut data_total = 0usize;
    for &(_, sz) in requests {
        if sz > HMKPM_MAX_ENTRY_SIZE {
            return Err(format!(
                "Batch entry size {sz} exceeds max entry size {HMKPM_MAX_ENTRY_SIZE}"
            ));
        }
        data_total = data_total
            .checked_add(sz)
            .ok_or_else(|| "Batch data total size overflow".to_string())?;
    }

    if data_total > HMKPM_MAX_BATCH_TOTAL_SIZE {
        return Err(format!(
            "Batch total data size {data_total} exceeds max {}",
            HMKPM_MAX_BATCH_TOTAL_SIZE
        ));
    }

    let count = requests.len();
    let entries_bytes = count * HMKPM_BATCH_ENTRY_SIZE;
    let total_len = HMKPM_BATCH_HDR_SIZE + entries_bytes + data_total;

    let mut buf = AlignedHeapBuf::new(total_len);

    // Header
    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmBatchHdr;
    // SAFETY: buf is allocated with total_len >= HMKPM_BATCH_HDR_SIZE and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmBatchHdr {
                pid: pid as i32,
                _pad: 0,
                count: count as u64,
                data_total: data_total as u64,
            },
        );
    }

    // Entries
    // SAFETY: `HMKPM_BATCH_HDR_SIZE` (24) is 8-byte aligned and within `buf` (length `total_len`).
    let entries_ptr = unsafe { buf.as_mut_ptr().add(HMKPM_BATCH_HDR_SIZE) as *mut HmkpmBatchEntry };
    for (i, &(addr, sz)) in requests.iter().enumerate() {
        // SAFETY: i < count, entries_ptr is 8-byte aligned, and the memory buffer was allocated with sufficient capacity.
        unsafe {
            std::ptr::write(
                entries_ptr.add(i),
                HmkpmBatchEntry {
                    addr,
                    size: sz as u64,
                },
            );
        }
    }

    // SAFETY: buf is allocated with total_len bytes and properly initialized.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_READ_BATCH, buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM batch read failed: pid={pid} count={count} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    // Extract results
    let data_start = HMKPM_BATCH_HDR_SIZE + entries_bytes;
    let mut results = Vec::with_capacity(count);
    let mut current_data_off = data_start;
    // SAFETY: `HMKPM_BATCH_HDR_SIZE` (24) is 8-byte aligned and within `buf`.
    let entries_read_ptr =
        unsafe { buf.as_ptr().add(HMKPM_BATCH_HDR_SIZE) as *const HmkpmBatchEntry };

    for (i, req) in requests.iter().enumerate().take(count) {
        // SAFETY: `i < count` and every entry slot in `buf` was initialized and populated by the kernel.
        let actual_size = unsafe { (*entries_read_ptr.add(i)).size as usize };
        let req_size = req.1;

        let entry_data = if actual_size > 0 && actual_size <= req_size {
            buf[current_data_off..current_data_off + req_size].to_vec()
        } else {
            vec![0u8; req_size]
        };

        results.push(entry_data);
        current_data_off += req_size;
    }

    Ok(results)
}

/// Executes batch writes across multiple addresses in a single kernel syscall,
/// returning a structured `KpmError` on Lockless Mode, dead PID, or kernel I/O error.
pub fn write_batch_checked(pid: u32, writes: &[(u64, &[u8])]) -> Result<usize, KpmError> {
    if writes.is_empty() {
        return Ok(0);
    }

    if pid == 0 {
        return Err(KpmError::Io("PID cannot be 0".to_string()));
    }

    if writes.len() > HMKPM_MAX_BATCH_ENTRIES {
        return Err(KpmError::Io(format!(
            "Batch count {} exceeds max entries {}",
            writes.len(),
            HMKPM_MAX_BATCH_ENTRIES
        )));
    }

    let mut data_total = 0usize;
    for &(_, data) in writes {
        let sz = data.len();
        if sz > HMKPM_MAX_ENTRY_SIZE {
            return Err(KpmError::Io(format!(
                "Batch entry size {sz} exceeds max entry size {HMKPM_MAX_ENTRY_SIZE}"
            )));
        }
        data_total = data_total
            .checked_add(sz)
            .ok_or_else(|| KpmError::Io("Batch data total size overflow".to_string()))?;
    }

    if data_total > HMKPM_MAX_BATCH_TOTAL_SIZE {
        return Err(KpmError::Io(format!(
            "Batch total data size {data_total} exceeds max {}",
            HMKPM_MAX_BATCH_TOTAL_SIZE
        )));
    }

    let count = writes.len();
    let entries_bytes = count * HMKPM_BATCH_ENTRY_SIZE;
    let total_len = HMKPM_BATCH_HDR_SIZE + entries_bytes + data_total;

    let mut buf = AlignedHeapBuf::new(total_len);

    // Header
    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmBatchHdr;
    // SAFETY: buf is allocated with total_len >= HMKPM_BATCH_HDR_SIZE and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmBatchHdr {
                pid: pid as i32,
                _pad: 0,
                count: count as u64,
                data_total: data_total as u64,
            },
        );
    }

    // Entries and Data Buffer
    // SAFETY: `HMKPM_BATCH_HDR_SIZE` (24) is 8-byte aligned and within `buf`.
    let entries_ptr = unsafe { buf.as_mut_ptr().add(HMKPM_BATCH_HDR_SIZE) as *mut HmkpmBatchEntry };
    let data_start = HMKPM_BATCH_HDR_SIZE + entries_bytes;
    let mut data_curr = data_start;

    for (i, &(addr, data)) in writes.iter().enumerate() {
        let sz = data.len() as u64;
        // SAFETY: i < count, corresponding memory was allocated inside buf.
        unsafe {
            std::ptr::write(entries_ptr.add(i), HmkpmBatchEntry { addr, size: sz });
        }

        if !data.is_empty() {
            buf[data_curr..data_curr + data.len()].copy_from_slice(data);
            data_curr += data.len();
        }
    }

    // SAFETY: buf is allocated with total_len bytes and populated.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_WRITE_BATCH, buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        let err = std::io::Error::last_os_error();
        let raw_errno = err.raw_os_error().unwrap_or(0);
        return Err(classify_write_errno(
            pid,
            raw_errno,
            format!("HMKPM batch write failed: pid={pid} count={count} ret={ret} errno={err}"),
        ));
    }

    // Count entries written completely
    let mut success_count = 0usize;
    // SAFETY: `HMKPM_BATCH_HDR_SIZE` (24) is 8-byte aligned and within `buf`.
    let entries_read_ptr =
        unsafe { buf.as_ptr().add(HMKPM_BATCH_HDR_SIZE) as *const HmkpmBatchEntry };
    for (i, write_item) in writes.iter().enumerate().take(count) {
        // SAFETY: `i < count` and `entries_read_ptr` points to kernel-updated `HmkpmBatchEntry` array.
        let actual_size = unsafe { (*entries_read_ptr.add(i)).size as usize };
        if actual_size == write_item.1.len() {
            success_count += 1;
        }
    }

    Ok(success_count)
}

/// Executes batch writes across multiple addresses in a single kernel syscall.
/// Returns the number of successfully written entries.
pub fn write_batch(pid: u32, writes: &[(u64, &[u8])]) -> Result<usize, String> {
    write_batch_checked(pid, writes).map_err(String::from)
}

/// Translates a batch of virtual addresses to physical addresses and inspects page table flags.
pub fn v2p_batch(pid: u32, entries: &mut [HmkpmV2pEntry]) -> Result<(), String> {
    if entries.is_empty() {
        return Ok(());
    }

    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }

    let count = entries.len();
    if count > HMKPM_MAX_V2P_ENTRIES {
        return Err(format!(
            "V2P batch count {count} exceeds max entries {HMKPM_MAX_V2P_ENTRIES}"
        ));
    }

    let entries_bytes = count * HMKPM_V2P_ENTRY_SIZE;
    let total_len = HMKPM_V2P_HDR_SIZE + entries_bytes;

    let mut buf = AlignedHeapBuf::new(total_len);

    // Header
    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmV2pHdr;
    // SAFETY: buf is allocated with total_len >= HMKPM_V2P_HDR_SIZE and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmV2pHdr {
                pid: pid as i32,
                _pad: 0,
                count: count as u64,
            },
        );
    }

    // Entries
    // SAFETY: `HMKPM_V2P_HDR_SIZE` (16) is 8-byte aligned and within `buf` (length `total_len`).
    let entries_ptr = unsafe { buf.as_mut_ptr().add(HMKPM_V2P_HDR_SIZE) as *mut HmkpmV2pEntry };
    for (i, entry) in entries.iter().enumerate() {
        // SAFETY: i < count, entries_ptr is 8-byte aligned, and entry memory is valid.
        unsafe {
            std::ptr::write(entries_ptr.add(i), *entry);
        }
    }

    // SAFETY: buf is a valid aligned heap buffer of total_len bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_V2P_BATCH, buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM V2P batch failed: pid={pid} count={count} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    // Copy back results
    for (i, entry) in entries.iter_mut().enumerate() {
        // SAFETY: i < count and entries_ptr has been updated by the kernel.
        *entry = unsafe { *entries_ptr.add(i) };
    }

    Ok(())
}

/// Cursor position for resuming truncated in-kernel scans (`HMKPM_SCAN_FLAG_RESUME`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HmkpmScanCursor {
    pub range_idx: u32,
    pub va: u64,
}

/// Outcome returned by v3.0.0 in-kernel scans including truncation status and resume cursor.
#[derive(Clone, Debug)]
pub struct HmkpmScanOutcome<T> {
    pub matches: Vec<T>,
    pub total_found: u64,
    pub status: u32,
    pub next_cursor: HmkpmScanCursor,
}

/// Executes an in-kernel direct memory scan returning bare virtual addresses and resume cursor state.
#[allow(clippy::too_many_arguments)]
pub fn scan_kernel_addrs(
    pid: u32,
    scan_type: u8,
    op: u8,
    align: u8,
    flags: u8,
    criteria: HmkpmScanCriteria,
    pattern: Option<&[u8]>,
    mask: Option<&[u8]>,
    ranges: &[HmkpmScanRange],
    max_matches: usize,
    resume_cursor: Option<HmkpmScanCursor>,
) -> Result<HmkpmScanOutcome<u64>, String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }

    if (flags & !HMKPM_SCAN_FLAGS_MASK) != 0 {
        return Err(format!("Invalid scan flags: 0x{flags:02x}"));
    }
    if (flags & HMKPM_SCAN_FLAG_WITH_VALUES) != 0 {
        return Err("Use scan_kernel_with_values for HMKPM_SCAN_FLAG_WITH_VALUES".to_string());
    }

    if ranges.is_empty() {
        return Ok(HmkpmScanOutcome {
            matches: Vec::new(),
            total_found: 0,
            status: HMKPM_SCAN_STATUS_COMPLETE,
            next_cursor: HmkpmScanCursor::default(),
        });
    }

    if ranges.len() > HMKPM_MAX_SCAN_RANGES {
        return Err(format!(
            "Scan range count {} exceeds max ranges {}",
            ranges.len(),
            HMKPM_MAX_SCAN_RANGES
        ));
    }

    let max_matches_clamped = max_matches.min(HMKPM_MAX_SCAN_MATCHES);
    let (pattern_bytes, mask_bytes) = if scan_type == HMKPM_SCAN_TYPE_BYTES {
        let pat = pattern.ok_or_else(|| "BYTES scan requires a pattern".to_string())?;
        if pat.is_empty() {
            return Err("BYTES scan pattern cannot be empty".to_string());
        }
        if pat.len() > HMKPM_MAX_PATTERN_LEN {
            return Err(format!(
                "Scan pattern len {} exceeds max {HMKPM_MAX_PATTERN_LEN}",
                pat.len()
            ));
        }
        let mlen = if op == HMKPM_SCAN_OP_MASK {
            let m = mask.ok_or_else(|| "BYTES mask scan requires a mask".to_string())?;
            if m.len() != pat.len() {
                return Err(format!(
                    "BYTES scan mask len {} does not match pattern len {}",
                    m.len(),
                    pat.len()
                ));
            }
            m.len()
        } else {
            0
        };
        (pat.len(), mlen)
    } else {
        (0, 0)
    };

    let (eff_flags, resume_va, resume_range_idx) = match resume_cursor {
        Some(cursor) => {
            if cursor.range_idx as usize > ranges.len() {
                return Err(format!(
                    "Resume range index {} exceeds range count {}",
                    cursor.range_idx,
                    ranges.len()
                ));
            }
            (flags | HMKPM_SCAN_FLAG_RESUME, cursor.va, cursor.range_idx)
        }
        None => (flags & !HMKPM_SCAN_FLAG_RESUME, 0, 0),
    };

    let ranges_bytes = ranges.len() * HMKPM_SCAN_RANGE_SIZE;
    let matches_bytes = max_matches_clamped * std::mem::size_of::<u64>();
    let total_len = HMKPM_SCAN_HDR_SIZE + ranges_bytes + pattern_bytes + mask_bytes + matches_bytes;

    let mut buf = AlignedHeapBuf::new(total_len);

    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmScanHdr;
    // SAFETY: `buf` is allocated with `total_len >= HMKPM_SCAN_HDR_SIZE` (64) and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmScanHdr {
                pid: pid as i32,
                scan_type,
                op,
                align,
                flags: eff_flags,
                range_count: ranges.len() as u32,
                max_matches: max_matches_clamped as u32,
                pattern_len: pattern_bytes as u64,
                criteria,
                matches_found: 0,
                resume_va,
                resume_range_idx,
                scan_status: 0,
            },
        );
    }

    // SAFETY: `HMKPM_SCAN_HDR_SIZE` (64) is 8-byte aligned and within `buf` (length `total_len`).
    let ranges_ptr = unsafe { buf.as_mut_ptr().add(HMKPM_SCAN_HDR_SIZE) as *mut HmkpmScanRange };
    for (i, r) in ranges.iter().enumerate() {
        // SAFETY: `i < ranges.len()`, `ranges_ptr` is 8-byte aligned, and memory is within `buf`.
        unsafe {
            std::ptr::write(ranges_ptr.add(i), *r);
        }
    }

    let mut curr_off = HMKPM_SCAN_HDR_SIZE + ranges_bytes;
    if pattern_bytes > 0
        && let Some(pat) = pattern
    {
        buf[curr_off..curr_off + pattern_bytes].copy_from_slice(&pat[..pattern_bytes]);
        curr_off += pattern_bytes;
    }
    if mask_bytes > 0
        && let Some(m) = mask
    {
        buf[curr_off..curr_off + mask_bytes].copy_from_slice(&m[..mask_bytes]);
        curr_off += mask_bytes;
    }

    let matches_start = curr_off;

    // SAFETY: `buf` is a valid 8-byte aligned buffer allocated with `total_len` bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_SCAN_KERNEL, buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM kernel scan failed: pid={pid} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    // SAFETY: `hdr_ptr` points to the 8-byte aligned `HmkpmScanHdr` at the start of `buf`.
    let hdr_read = unsafe { &*hdr_ptr };
    let total_found = hdr_read.matches_found;
    let status = hdr_read.scan_status;
    let next_cursor = HmkpmScanCursor {
        range_idx: hdr_read.resume_range_idx,
        va: hdr_read.resume_va,
    };
    let stored_matches = (total_found as usize).min(max_matches_clamped);

    let mut matches = Vec::with_capacity(stored_matches);
    // SAFETY: `matches_start` is within `buf` with space for `max_matches_clamped * size_of::<u64>()` bytes.
    let matches_read_ptr = unsafe { buf.as_ptr().add(matches_start) as *const u64 };

    for i in 0..stored_matches {
        // SAFETY: `i < stored_matches <= max_matches_clamped`, within `buf`; `read_unaligned`
        // handles non-8-byte `pattern_bytes + mask_bytes` offsets without alignment UB.
        let match_addr = unsafe { std::ptr::read_unaligned(matches_read_ptr.add(i)) };
        matches.push(match_addr);
    }

    Ok(HmkpmScanOutcome {
        matches,
        total_found,
        status,
        next_cursor,
    })
}

/// Executes an in-kernel direct memory scan returning `(va, value)` pairs (`HMKPM_SCAN_FLAG_WITH_VALUES`).
#[allow(clippy::too_many_arguments)]
pub fn scan_kernel_with_values(
    pid: u32,
    scan_type: u8,
    op: u8,
    align: u8,
    flags: u8,
    criteria: HmkpmScanCriteria,
    ranges: &[HmkpmScanRange],
    max_matches: usize,
    resume_cursor: Option<HmkpmScanCursor>,
) -> Result<HmkpmScanOutcome<HmkpmScanValMatch>, String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }
    if !(HMKPM_SCAN_TYPE_I8..=HMKPM_SCAN_TYPE_F64).contains(&scan_type) {
        return Err(format!(
            "WITH_VALUES scan requires scalar type I8..F64, got {scan_type}"
        ));
    }
    if (flags & !HMKPM_SCAN_FLAGS_MASK) != 0 {
        return Err(format!("Invalid scan flags: 0x{flags:02x}"));
    }

    if ranges.is_empty() {
        return Ok(HmkpmScanOutcome {
            matches: Vec::new(),
            total_found: 0,
            status: HMKPM_SCAN_STATUS_COMPLETE,
            next_cursor: HmkpmScanCursor::default(),
        });
    }

    if ranges.len() > HMKPM_MAX_SCAN_RANGES {
        return Err(format!(
            "Scan range count {} exceeds max ranges {}",
            ranges.len(),
            HMKPM_MAX_SCAN_RANGES
        ));
    }

    let (eff_flags, resume_va, resume_range_idx) = match resume_cursor {
        Some(cursor) => {
            if cursor.range_idx as usize > ranges.len() {
                return Err(format!(
                    "Resume range index {} exceeds range count {}",
                    cursor.range_idx,
                    ranges.len()
                ));
            }
            (
                flags | HMKPM_SCAN_FLAG_WITH_VALUES | HMKPM_SCAN_FLAG_RESUME,
                cursor.va,
                cursor.range_idx,
            )
        }
        None => (
            (flags & !HMKPM_SCAN_FLAG_RESUME) | HMKPM_SCAN_FLAG_WITH_VALUES,
            0,
            0,
        ),
    };

    let max_matches_clamped = max_matches.min(HMKPM_MAX_SCAN_MATCHES);
    let ranges_bytes = ranges.len() * HMKPM_SCAN_RANGE_SIZE;
    let matches_start = HMKPM_SCAN_HDR_SIZE + ranges_bytes;
    let matches_bytes = max_matches_clamped * HMKPM_SCAN_VAL_MATCH_SIZE;
    let total_len = matches_start + matches_bytes;

    let mut buf = AlignedHeapBuf::new(total_len);

    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmScanHdr;
    // SAFETY: `buf` is allocated with `total_len >= HMKPM_SCAN_HDR_SIZE` (64) and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmScanHdr {
                pid: pid as i32,
                scan_type,
                op,
                align,
                flags: eff_flags,
                range_count: ranges.len() as u32,
                max_matches: max_matches_clamped as u32,
                pattern_len: 0,
                criteria,
                matches_found: 0,
                resume_va,
                resume_range_idx,
                scan_status: 0,
            },
        );
    }

    // SAFETY: `HMKPM_SCAN_HDR_SIZE` (64) is 8-byte aligned and within `buf`.
    let ranges_ptr = unsafe { buf.as_mut_ptr().add(HMKPM_SCAN_HDR_SIZE) as *mut HmkpmScanRange };
    for (i, r) in ranges.iter().enumerate() {
        // SAFETY: `i < ranges.len()` and `ranges_ptr` is 8-byte aligned within `buf`.
        unsafe {
            std::ptr::write(ranges_ptr.add(i), *r);
        }
    }

    // SAFETY: `buf` is a valid 8-byte aligned buffer allocated with `total_len` bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_SCAN_KERNEL, buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM kernel value scan failed: pid={pid} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    // SAFETY: `hdr_ptr` points to the 8-byte aligned `HmkpmScanHdr` at the start of `buf`.
    let hdr_read = unsafe { &*hdr_ptr };
    let total_found = hdr_read.matches_found;
    let status = hdr_read.scan_status;
    let next_cursor = HmkpmScanCursor {
        range_idx: hdr_read.resume_range_idx,
        va: hdr_read.resume_va,
    };
    let stored_matches = (total_found as usize).min(max_matches_clamped);

    let mut matches = Vec::with_capacity(stored_matches);
    // SAFETY: `matches_start` (64 + ranges*16) is 8-byte aligned and within `buf`.
    let matches_read_ptr = unsafe { buf.as_ptr().add(matches_start) as *const HmkpmScanValMatch };

    for i in 0..stored_matches {
        // SAFETY: `i < stored_matches <= max_matches_clamped`, within `buf`.
        let pair = unsafe { std::ptr::read_unaligned(matches_read_ptr.add(i)) };
        matches.push(pair);
    }

    Ok(HmkpmScanOutcome {
        matches,
        total_found,
        status,
        next_cursor,
    })
}

/// Convenience wrapper for single-shot bare-address kernel scans without a resume cursor.
#[allow(clippy::too_many_arguments)]
pub fn scan_kernel(
    pid: u32,
    scan_type: u8,
    op: u8,
    align: u8,
    flags: u8,
    criteria: HmkpmScanCriteria,
    pattern: Option<&[u8]>,
    mask: Option<&[u8]>,
    ranges: &[HmkpmScanRange],
    max_matches: usize,
) -> Result<(Vec<u64>, u64), String> {
    let outcome = scan_kernel_addrs(
        pid,
        scan_type,
        op,
        align,
        flags,
        criteria,
        pattern,
        mask,
        ranges,
        max_matches,
        None,
    )?;
    Ok((outcome.matches, outcome.total_found))
}

/// Input descriptor for a single pointer chain inside [`read_chain`].
#[derive(Clone, Debug)]
pub struct ChainRequest<'a> {
    pub base_va: u64,
    pub offsets: &'a [i64],
    pub read_size: u32,
    pub flags: u16,
}

/// Per-chain output returned by [`read_chain`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChainResult {
    pub resolved_va: u64,
    pub hops_done: u16,
    pub status: u32,
    pub data: Vec<u8>,
}

/// Resolves one or more pointer chains (with optional terminal reads) in a single
/// `HMKPM_MAGIC_READ_CHAIN` kernel syscall.
pub fn read_chain(pid: u32, chains: &[ChainRequest<'_>]) -> Result<Vec<ChainResult>, String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }
    if chains.is_empty() {
        return Ok(Vec::new());
    }
    if chains.len() > HMKPM_MAX_CHAIN_COUNT {
        return Err(format!(
            "Chain count {} exceeds max {}",
            chains.len(),
            HMKPM_MAX_CHAIN_COUNT
        ));
    }

    let mut total_offsets = 0usize;
    let mut data_total = 0usize;
    for (idx, c) in chains.iter().enumerate() {
        if (c.flags & !HMKPM_CHAIN_FLAGS_MASK) != 0 {
            return Err(format!("Chain[{idx}] invalid flags: 0x{:04x}", c.flags));
        }
        if c.offsets.len() > HMKPM_MAX_CHAIN_DEPTH {
            return Err(format!(
                "Chain[{idx}] depth {} exceeds max {HMKPM_MAX_CHAIN_DEPTH}",
                c.offsets.len()
            ));
        }
        if c.read_size as usize > HMKPM_MAX_CHAIN_READ_SIZE {
            return Err(format!(
                "Chain[{idx}] read_size {} exceeds max {HMKPM_MAX_CHAIN_READ_SIZE}",
                c.read_size
            ));
        }
        total_offsets = total_offsets
            .checked_add(c.offsets.len())
            .ok_or_else(|| "Chain total_offsets overflow".to_string())?;
        data_total = data_total
            .checked_add(c.read_size as usize)
            .ok_or_else(|| "Chain data_total overflow".to_string())?;
    }

    if total_offsets > HMKPM_MAX_CHAIN_TOTAL_OFFSETS {
        return Err(format!(
            "Chain total_offsets {total_offsets} exceeds max {HMKPM_MAX_CHAIN_TOTAL_OFFSETS}"
        ));
    }
    if data_total > HMKPM_MAX_CHAIN_DATA_TOTAL {
        return Err(format!(
            "Chain data_total {data_total} exceeds max {HMKPM_MAX_CHAIN_DATA_TOTAL}"
        ));
    }

    let count = chains.len();
    let reqs_bytes = count * HMKPM_CHAIN_REQ_SIZE;
    let offsets_start = HMKPM_CHAIN_HDR_SIZE + reqs_bytes;
    let offsets_bytes = total_offsets * std::mem::size_of::<i64>();
    let data_start = offsets_start + offsets_bytes;
    let total_len = data_start + data_total;

    let mut buf = AlignedHeapBuf::new(total_len);

    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmChainHdr;
    // SAFETY: `buf` is 8-byte aligned and has length `total_len >= HMKPM_CHAIN_HDR_SIZE` (24).
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmChainHdr {
                pid: pid as i32,
                count: count as u32,
                total_offsets: total_offsets as u64,
                data_total: data_total as u64,
            },
        );
    }

    // SAFETY: `HMKPM_CHAIN_HDR_SIZE` (24) is 8-byte aligned and within `buf`.
    let reqs_ptr = unsafe { buf.as_mut_ptr().add(HMKPM_CHAIN_HDR_SIZE) as *mut HmkpmChainReq };
    // SAFETY: `offsets_start` (24 + count * 32) is 8-byte aligned and within `buf`.
    let offsets_ptr = unsafe { buf.as_mut_ptr().add(offsets_start) as *mut i64 };
    let mut off_cursor = 0usize;

    for (i, c) in chains.iter().enumerate() {
        // SAFETY: `i < count` and `reqs_ptr` is 8-byte aligned within `buf`.
        unsafe {
            std::ptr::write(
                reqs_ptr.add(i),
                HmkpmChainReq {
                    base_va: c.base_va,
                    resolved_va: 0,
                    depth: c.offsets.len() as u32,
                    read_size: c.read_size,
                    flags: c.flags,
                    hops_done: 0,
                    status: 0,
                },
            );
        }
        for &off in c.offsets {
            // SAFETY: `off_cursor < total_offsets` and `offsets_ptr` is 8-byte aligned within `buf`.
            unsafe {
                std::ptr::write(offsets_ptr.add(off_cursor), off);
            }
            off_cursor += 1;
        }
    }

    // SAFETY: `buf` is a valid 8-byte aligned buffer allocated with `total_len` bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_READ_CHAIN, buf.as_mut_ptr(), total_len as u64) };
    if ret != 0 {
        return Err(format!(
            "HMKPM read_chain failed: pid={pid} count={count} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    let mut results = Vec::with_capacity(count);
    let mut data_cursor = data_start;
    // SAFETY: `HMKPM_CHAIN_HDR_SIZE` (24) is 8-byte aligned and within `buf`.
    let reqs_read_ptr = unsafe { buf.as_ptr().add(HMKPM_CHAIN_HDR_SIZE) as *const HmkpmChainReq };

    for (i, c) in chains.iter().enumerate() {
        // SAFETY: `i < count` and `reqs_read_ptr` points to kernel-updated `HmkpmChainReq` slots.
        let out_req = unsafe { *reqs_read_ptr.add(i) };
        let req_read_sz = c.read_size as usize;
        let actual_read_sz = (out_req.read_size as usize).min(req_read_sz);
        let data = if actual_read_sz > 0 {
            buf[data_cursor..data_cursor + actual_read_sz].to_vec()
        } else {
            Vec::new()
        };
        data_cursor += req_read_sz;

        results.push(ChainResult {
            resolved_va: out_req.resolved_va,
            hops_done: out_req.hops_done,
            status: out_req.status,
            data,
        });
    }

    Ok(results)
}

/// Refines and compacts `(va, value)` candidates in-kernel via `HMKPM_MAGIC_SCAN_REFINE`
/// using `HMKPM_REFINE_FLAG_WITH_VALUES`.
///
/// Surviving entries (with updated live values) are compacted into `items[..matched]`
/// and `items` is truncated to `matched`.
pub fn scan_refine_values(
    pid: u32,
    scan_type: u8,
    op: u8,
    criteria: HmkpmScanCriteria,
    items: &mut Vec<HmkpmScanValMatch>,
    max_matches: usize,
) -> Result<usize, String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }
    if !(HMKPM_SCAN_TYPE_I8..=HMKPM_SCAN_TYPE_F64).contains(&scan_type) {
        return Err(format!("Invalid refine scan_type: {scan_type}"));
    }
    if !(HMKPM_REFINE_OP_EXACT..=HMKPM_REFINE_OP_DECREASED).contains(&op) {
        return Err(format!("Invalid refine op: {op}"));
    }
    if items.is_empty() {
        return Ok(0);
    }
    if items.len() > HMKPM_MAX_REFINE_COUNT {
        return Err(format!(
            "Refine candidate count {} exceeds max {HMKPM_MAX_REFINE_COUNT}",
            items.len()
        ));
    }

    let count = items.len();
    let cap_u32 = if max_matches == 0 || max_matches >= count {
        0u32
    } else {
        max_matches as u32
    };
    let items_bytes = count * HMKPM_SCAN_VAL_MATCH_SIZE;
    let total_len = HMKPM_REFINE_HDR_SIZE + items_bytes;

    let mut buf = AlignedHeapBuf::new(total_len);

    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmRefineHdr;
    // SAFETY: `buf` is 8-byte aligned and has length `total_len >= HMKPM_REFINE_HDR_SIZE` (48).
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmRefineHdr {
                pid: pid as i32,
                scan_type,
                op,
                flags: HMKPM_REFINE_FLAG_WITH_VALUES,
                _pad: 0,
                count: count as u32,
                max_matches: cap_u32,
                criteria,
                matches_found: 0,
                _reserved: 0,
            },
        );
    }

    // SAFETY: `HMKPM_REFINE_HDR_SIZE` (48) is 8-byte aligned and within `buf`.
    let items_ptr =
        unsafe { buf.as_mut_ptr().add(HMKPM_REFINE_HDR_SIZE) as *mut HmkpmScanValMatch };
    for (i, &item) in items.iter().enumerate() {
        // SAFETY: `i < count` and `items_ptr` is 8-byte aligned within `buf`.
        unsafe {
            std::ptr::write(items_ptr.add(i), item);
        }
    }

    // SAFETY: `buf` is a valid 8-byte aligned buffer allocated with `total_len` bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_SCAN_REFINE, buf.as_mut_ptr(), total_len as u64) };
    if ret != 0 {
        return Err(format!(
            "HMKPM scan_refine failed: pid={pid} count={count} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    // SAFETY: `hdr_ptr` points to the 8-byte aligned `HmkpmRefineHdr` at the start of `buf`.
    let hdr_read = unsafe { &*hdr_ptr };
    let matched = (hdr_read.matches_found as usize).min(count);

    // SAFETY: `HMKPM_REFINE_HDR_SIZE` (48) is 8-byte aligned and within `buf`.
    let items_read_ptr =
        unsafe { buf.as_ptr().add(HMKPM_REFINE_HDR_SIZE) as *const HmkpmScanValMatch };
    for (i, slot) in items.iter_mut().enumerate().take(matched) {
        // SAFETY: `i < matched <= count` and `items_read_ptr` holds kernel-compacted entries.
        *slot = unsafe { *items_read_ptr.add(i) };
    }
    items.truncate(matched);

    Ok(matched)
}

/// Enumerates a single page of mapped user regions via `HMKPM_MAGIC_ENUM_REGIONS`.
///
/// Returns `(entries, next_va)` where `next_va == 0` indicates enumeration is complete.
pub fn enum_regions_page(
    pid: u32,
    flags: u32,
    start_va: u64,
    end_va: u64,
    max_regions: usize,
) -> Result<(Vec<HmkpmRegionEntry>, u64), String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }
    if (flags & !HMKPM_ENUM_FLAGS_MASK) != 0 {
        return Err(format!("Invalid enum_regions flags: 0x{flags:x}"));
    }
    if max_regions == 0 || max_regions > HMKPM_MAX_ENUM_REGIONS {
        return Err(format!(
            "Invalid enum_regions max_regions {max_regions} (must be 1..={HMKPM_MAX_ENUM_REGIONS})"
        ));
    }

    let entries_bytes = max_regions * HMKPM_REGION_ENTRY_SIZE;
    let total_len = HMKPM_ENUM_HDR_SIZE + entries_bytes;
    let mut buf = AlignedHeapBuf::new(total_len);

    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmEnumHdr;
    // SAFETY: `buf` is 8-byte aligned and has length `total_len >= HMKPM_ENUM_HDR_SIZE` (40).
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmEnumHdr {
                pid: pid as i32,
                flags,
                start_va,
                end_va,
                max_regions: max_regions as u32,
                regions_found: 0,
                next_va: 0,
            },
        );
    }

    // SAFETY: `buf` is a valid 8-byte aligned buffer allocated with `total_len` bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_ENUM_REGIONS, buf.as_mut_ptr(), total_len as u64) };
    if ret != 0 {
        return Err(format!(
            "HMKPM enum_regions failed: pid={pid} start=0x{start_va:x} end=0x{end_va:x} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    // SAFETY: `hdr_ptr` points to the 8-byte aligned `HmkpmEnumHdr` at the start of `buf`.
    let hdr_read = unsafe { &*hdr_ptr };
    let found = (hdr_read.regions_found as usize).min(max_regions);
    let next_va = hdr_read.next_va;

    let mut entries = Vec::with_capacity(found);
    // SAFETY: `HMKPM_ENUM_HDR_SIZE` (40) is 8-byte aligned and within `buf`.
    let entries_read_ptr =
        unsafe { buf.as_ptr().add(HMKPM_ENUM_HDR_SIZE) as *const HmkpmRegionEntry };
    for i in 0..found {
        // SAFETY: `i < found <= max_regions` and `entries_read_ptr` holds kernel-written entries.
        entries.push(unsafe { *entries_read_ptr.add(i) });
    }

    Ok((entries, next_va))
}

/// Enumerates all hardware-mapped resident regions in `[start_va, end_va)` via paginated
/// `HMKPM_MAGIC_ENUM_REGIONS` calls (`end_va == 0` walks the entire user VA space).
pub fn enum_regions(
    pid: u32,
    flags: u32,
    start_va: u64,
    end_va: u64,
) -> Result<Vec<HmkpmRegionEntry>, String> {
    const PAGE_CAP: usize = 4096;
    let mut all = Vec::new();
    let mut cur_start = start_va;

    loop {
        let (page, next_va) = enum_regions_page(pid, flags, cur_start, end_va, PAGE_CAP)?;
        all.extend(page);

        if next_va == 0 || (end_va != 0 && next_va >= end_va) || next_va <= cur_start {
            break;
        }
        cur_start = next_va;
    }

    Ok(all)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_classify_write_errno_lockless() {
        let err = classify_write_errno(1234, 95, "write failed".to_string());
        assert!(matches!(err, KpmError::Lockless(_)));
        assert!(err.to_string().contains("Lockless"));
    }

    #[test]
    fn test_classify_write_errno_process_dead() {
        let err = classify_write_errno(4321, 3, "ESRCH".to_string());
        assert!(matches!(err, KpmError::ProcessDead { pid: 4321, .. }));
        assert!(err.to_string().contains("4321"));
    }

    #[test]
    fn test_classify_write_errno_generic_io() {
        let err = classify_write_errno(100, libc::EFAULT, "bad address".to_string());
        assert!(matches!(err, KpmError::Io(ref msg) if msg == "bad address"));
    }

    #[test]
    fn test_kpm_read_buffer_validation() {
        let mut buf = KpmReadBuffer::with_capacity(4096);
        assert_eq!(buf.read(123, 0x1000, 0).unwrap(), &[] as &[u8]);
        assert!(buf.read(0, 0x1000, 4).is_err());
        assert!(buf.read(123, 0x1000, HMKPM_MAX_SINGLE_SIZE + 1).is_err());
    }

    #[test]
    fn test_v3_uapi_abi_offsets_and_sizes() {
        assert_eq!(std::mem::size_of::<HmkpmVersionInfo>(), 80);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, magic), 0);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, struct_size), 8);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, version_code), 12);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, version_major), 16);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, version_minor), 18);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, version_patch), 20);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, is_lockless), 22);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, lock_source), 23);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, mmap_lock_offset), 24);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, page_size), 28);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, kver), 32);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, va_bits), 36);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, tbi0), 37);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, sw_pan_mode), 38);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, fsnotify_mode), 39);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, features), 40);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, reserved), 48);
        assert_eq!(std::mem::offset_of!(HmkpmVersionInfo, version_str), 64);

        assert_eq!(std::mem::size_of::<HmkpmScanHdr>(), 64);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, flags), 7);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, range_count), 8);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, max_matches), 12);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, pattern_len), 16);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, criteria), 24);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, matches_found), 40);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, resume_va), 48);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, resume_range_idx), 56);
        assert_eq!(std::mem::offset_of!(HmkpmScanHdr, scan_status), 60);

        assert_eq!(std::mem::size_of::<HmkpmScanValMatch>(), 16);
        assert_eq!(std::mem::size_of::<HmkpmChainHdr>(), 24);
        assert_eq!(std::mem::size_of::<HmkpmChainReq>(), 32);
        assert_eq!(std::mem::size_of::<HmkpmRefineHdr>(), 48);
        assert_eq!(std::mem::size_of::<HmkpmEnumHdr>(), 40);
        assert_eq!(std::mem::size_of::<HmkpmRegionEntry>(), 24);
    }

    #[test]
    fn test_version_info_telemetry_formatters() {
        let info = HmkpmVersionInfo {
            lock_source: HMKPM_LOCK_SRC_BTF,
            sw_pan_mode: HMKPM_SW_PAN_KERNEL_UACCESS,
            fsnotify_mode: HMKPM_FSNOTIFY_CALLER_PIDS,
            kver: (6 << 16) | (1 << 8) | 75,
            ..Default::default()
        };
        assert_eq!(info.lock_source_str(), "btf");
        assert_eq!(info.sw_pan_mode_str(), "kernel_uaccess");
        assert_eq!(info.fsnotify_mode_str(), "pids");
        assert_eq!(info.kver_string(), "6.1.75");
    }

    #[test]
    fn test_validate_version_info_strict_pin() {
        let valid_ret = (HMKPM_MAGIC | ((HMKPM_VERSION_CODE as u64) << 40)) as i64;
        let valid_info = HmkpmVersionInfo {
            magic: HMKPM_MAGIC,
            struct_size: HMKPM_VERSION_INFO_SIZE as u32,
            version_code: HMKPM_VERSION_CODE,
            version_major: HMKPM_VERSION_MAJOR,
            version_minor: HMKPM_VERSION_MINOR,
            version_patch: HMKPM_VERSION_PATCH,
            ..Default::default()
        };
        assert!(validate_version_info(valid_ret, &valid_info).is_ok());

        // Reject older or newer version_code even when struct_size matches
        let older_info = HmkpmVersionInfo {
            version_code: (2 << 16) | (8 << 8),
            version_major: 2,
            version_minor: 8,
            version_patch: 0,
            ..valid_info
        };
        let err_old = validate_version_info(valid_ret, &older_info).unwrap_err();
        assert!(err_old.contains("pinned to v3.0.0"));
        assert!(err_old.contains("v2.8.0"));

        let patch_mismatch_info = HmkpmVersionInfo {
            version_code: (3 << 16) | 1,
            version_patch: 1,
            ..valid_info
        };
        assert!(validate_version_info(valid_ret, &patch_mismatch_info).is_err());

        let minor_mismatch_info = HmkpmVersionInfo {
            version_code: (3 << 16) | (1 << 8),
            version_minor: 1,
            ..valid_info
        };
        assert!(validate_version_info(valid_ret, &minor_mismatch_info).is_err());

        // Reject invalid struct_size or magic
        let bad_size = HmkpmVersionInfo {
            struct_size: 40,
            ..valid_info
        };
        assert!(validate_version_info(valid_ret, &bad_size).is_err());
        assert!(validate_version_info(-1, &valid_info).is_err());
    }

    #[test]
    fn test_scan_kernel_validation() {
        let criteria = HmkpmScanCriteria {
            exact: HmkpmScanCriteriaExact { val: 42, mask: 0 },
        };
        let ranges = [HmkpmScanRange {
            start_va: 0x1000,
            size: 0x1000,
        }];

        // Unknown flags bit rejected before syscall
        assert!(
            scan_kernel(
                1234,
                HMKPM_SCAN_TYPE_U32,
                HMKPM_SCAN_OP_EXACT,
                4,
                0x80,
                criteria,
                None,
                None,
                &ranges,
                16,
            )
            .is_err()
        );

        // WITH_VALUES flag rejected on bare-address scan_kernel
        assert!(
            scan_kernel(
                1234,
                HMKPM_SCAN_TYPE_U32,
                HMKPM_SCAN_OP_EXACT,
                4,
                HMKPM_SCAN_FLAG_WITH_VALUES,
                criteria,
                None,
                None,
                &ranges,
                16,
            )
            .is_err()
        );

        // BYTES scan rejected on scan_kernel_with_values
        assert!(
            scan_kernel_with_values(
                1234,
                HMKPM_SCAN_TYPE_BYTES,
                HMKPM_SCAN_OP_EXACT,
                1,
                0,
                criteria,
                &ranges,
                16,
                None,
            )
            .is_err()
        );

        // BYTES scan rejects missing or empty pattern
        assert!(
            scan_kernel(
                1234,
                HMKPM_SCAN_TYPE_BYTES,
                HMKPM_SCAN_OP_EXACT,
                1,
                0,
                criteria,
                None,
                None,
                &ranges,
                16,
            )
            .is_err()
        );
        assert!(
            scan_kernel(
                1234,
                HMKPM_SCAN_TYPE_BYTES,
                HMKPM_SCAN_OP_EXACT,
                1,
                0,
                criteria,
                Some(&[]),
                None,
                &ranges,
                16,
            )
            .is_err()
        );

        // BYTES mask scan rejects missing or mismatched mask
        assert!(
            scan_kernel(
                1234,
                HMKPM_SCAN_TYPE_BYTES,
                HMKPM_SCAN_OP_MASK,
                1,
                0,
                criteria,
                Some(&[0xAA, 0xBB]),
                None,
                &ranges,
                16,
            )
            .is_err()
        );
        assert!(
            scan_kernel(
                1234,
                HMKPM_SCAN_TYPE_BYTES,
                HMKPM_SCAN_OP_MASK,
                1,
                0,
                criteria,
                Some(&[0xAA, 0xBB]),
                Some(&[0xFF]),
                &ranges,
                16,
            )
            .is_err()
        );
    }

    #[test]
    fn test_v3_new_opcodes_validation() {
        // read_chain validation
        assert!(read_chain(0, &[]).is_err());
        assert_eq!(read_chain(1234, &[]).unwrap().len(), 0);
        let bad_flag_chain = [ChainRequest {
            base_va: 0x1000,
            offsets: &[0x10],
            read_size: 4,
            flags: 0x8000,
        }];
        assert!(read_chain(1234, &bad_flag_chain).is_err());
        let deep_offsets = [0i64; HMKPM_MAX_CHAIN_DEPTH + 1];
        let deep_chain = [ChainRequest {
            base_va: 0x1000,
            offsets: &deep_offsets,
            read_size: 4,
            flags: 0,
        }];
        assert!(read_chain(1234, &deep_chain).is_err());

        // scan_refine_values validation
        let criteria = HmkpmScanCriteria {
            exact: HmkpmScanCriteriaExact { val: 1, mask: 0 },
        };
        let mut items = Vec::new();
        assert_eq!(
            scan_refine_values(
                1234,
                HMKPM_SCAN_TYPE_I32,
                HMKPM_REFINE_OP_EXACT,
                criteria,
                &mut items,
                0
            )
            .unwrap(),
            0
        );
        assert!(
            scan_refine_values(
                1234,
                HMKPM_SCAN_TYPE_BYTES,
                HMKPM_REFINE_OP_EXACT,
                criteria,
                &mut items,
                0
            )
            .is_err()
        );

        // enum_regions_page validation
        assert!(enum_regions_page(0, 0, 0, 0, 16).is_err());
        assert!(enum_regions_page(1234, 0, 0, 0, 0).is_err());
        assert!(enum_regions_page(1234, 0x80, 0, 0, 16).is_err());
        assert!(enum_regions_page(1234, 0, 0, 0, HMKPM_MAX_ENUM_REGIONS + 1).is_err());
    }
}
