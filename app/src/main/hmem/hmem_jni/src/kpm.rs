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

//! Userspace client for the HMKPM (KernelPatch Module) v2.7.0.
//!
//! ## Channel
//! `getresuid` syscall using custom command magics:
//! - `HMKPM_MAGIC` (Probe / Liveness / Version & Feature Discovery)
//! - `HMKPM_MAGIC_READ` (Remote virtual address memory read)
//! - `HMKPM_MAGIC_WRITE` (Remote virtual address memory write)
//! - `HMKPM_MAGIC_READ_BATCH` (Batch read of multiple virtual addresses)
//! - `HMKPM_MAGIC_WRITE_BATCH` (Batch write of multiple virtual addresses)
//! - `HMKPM_MAGIC_V2P_BATCH` (Batch virtual-to-physical address & page-flag resolution)
//! - `HMKPM_MAGIC_SCAN_KERNEL` (In-kernel direct memory scan across page tables)
//!
//! The kernel performs virtual address translation using the target process's `pgd` (`task->mm->pgd`),
//! eliminating userspace PFN translation overhead.

#![allow(dead_code)]

use crate::logger;
use std::sync::RwLock;

pub const HMKPM_VERSION_MAJOR: u16 = 2;
pub const HMKPM_VERSION_MINOR: u16 = 8;
pub const HMKPM_VERSION_PATCH: u16 = 0;
pub const HMKPM_VERSION_CODE: u32 = ((HMKPM_VERSION_MAJOR as u32) << 16)
    | ((HMKPM_VERSION_MINOR as u32) << 8)
    | (HMKPM_VERSION_PATCH as u32);
pub const HMKPM_VERSION_STR: &str = "2.8.0";

pub const HMKPM_MAGIC: u64 = 0x0048_4D4B_504D;
pub const HMKPM_MAGIC_READ: u64 = HMKPM_MAGIC + 1;
pub const HMKPM_MAGIC_WRITE: u64 = HMKPM_MAGIC + 2;
pub const HMKPM_MAGIC_READ_BATCH: u64 = HMKPM_MAGIC + 3;
pub const HMKPM_MAGIC_WRITE_BATCH: u64 = HMKPM_MAGIC + 4;
pub const HMKPM_MAGIC_V2P_BATCH: u64 = HMKPM_MAGIC + 5;
pub const HMKPM_MAGIC_SCAN_KERNEL: u64 = HMKPM_MAGIC + 6;
pub const HMKPM_MAGIC_MAX: u64 = HMKPM_MAGIC_SCAN_KERNEL;

// Feature Flags
pub const HMKPM_FEATURE_READ: u64 = 1 << 0;
pub const HMKPM_FEATURE_WRITE: u64 = 1 << 1;
pub const HMKPM_FEATURE_READ_BATCH: u64 = 1 << 2;
pub const HMKPM_FEATURE_WRITE_BATCH: u64 = 1 << 3;
pub const HMKPM_FEATURE_V2P_BATCH: u64 = 1 << 4;
pub const HMKPM_FEATURE_SCAN_KERNEL: u64 = 1 << 5;
pub const HMKPM_FEATURE_LOCKLESS: u64 = 1 << 6;
pub const HMKPM_ALL_FEATURES: u64 = HMKPM_FEATURE_READ
    | HMKPM_FEATURE_WRITE
    | HMKPM_FEATURE_READ_BATCH
    | HMKPM_FEATURE_WRITE_BATCH
    | HMKPM_FEATURE_V2P_BATCH
    | HMKPM_FEATURE_SCAN_KERNEL;

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

pub const HMKPM_MAX_SINGLE_SIZE: usize = 64 * 1024 * 1024;
pub const HMKPM_MAX_ENTRY_SIZE: usize = 64 * 1024 * 1024;
pub const HMKPM_MAX_BATCH_ENTRIES: usize = 65536;
pub const HMKPM_MAX_BATCH_TOTAL_SIZE: usize = 128 * 1024 * 1024;

pub const HMKPM_V2P_CHUNK_ENTRIES: usize = 64;
pub const HMKPM_MAX_V2P_ENTRIES: usize = 65536;

pub const HMKPM_MAX_SCAN_RANGES: usize = 4096;
pub const HMKPM_MAX_PATTERN_LEN: usize = 256;
pub const HMKPM_MAX_SCAN_MATCHES: usize = 1048576;
pub const HMKPM_SCAN_MATCH_CHUNK: usize = 64;

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
/// Header size for `hmkpm_scan_hdr` (48 bytes).
pub const HMKPM_SCAN_HDR_SIZE: usize = 48;
/// Entry size for `hmkpm_scan_range` (16 bytes).
pub const HMKPM_SCAN_RANGE_SIZE: usize = 16;
/// Version info size for `hmkpm_version_info` (48 bytes).
pub const HMKPM_VERSION_INFO_SIZE: usize = 48;

#[allow(dead_code)]
const SYS_GETRESUID: libc::c_long = 148;

/// Version and feature info layout matching `struct hmkpm_version_info` in KPM (48 bytes).
#[repr(C, align(8))]
#[derive(Clone, Copy, Debug, Default)]
pub struct HmkpmVersionInfo {
    pub magic: u64,
    pub version_code: u32,
    pub version_major: u16,
    pub version_minor: u16,
    pub version_patch: u16,
    pub is_lockless: u8,
    pub _pad: u8,
    pub mmap_lock_offset: i32,
    pub features: u64,
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

/// Scan header — layout identical to `struct hmkpm_scan_hdr` in KPM.
#[repr(C, align(8))]
#[derive(Clone, Copy)]
pub struct HmkpmScanHdr {
    pub pid: i32,
    pub scan_type: u8,
    pub op: u8,
    pub align: u8,
    pub _pad: u8,
    pub range_count: u32,
    pub max_matches: u32,
    pub pattern_len: u64,
    pub criteria: HmkpmScanCriteria,
    pub matches_found: u64,
}

const _: () = assert!(std::mem::size_of::<HmkpmVersionInfo>() == HMKPM_VERSION_INFO_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmVersionInfo>() == 8);
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
const _: () = assert!(std::mem::size_of::<HmkpmScanHdr>() == HMKPM_SCAN_HDR_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmScanHdr>() == 8);

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

static CACHED_VERSION: RwLock<Option<HmkpmVersionInfo>> = RwLock::new(None);

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

/// PROBE & VERSION: queries the HMKPM module and populates version and supported feature bitmasks.
pub fn probe_version() -> Result<HmkpmVersionInfo, String> {
    if let Ok(guard) = CACHED_VERSION.read()
        && let Some(info) = *guard
    {
        return Ok(info);
    }

    let mut info = HmkpmVersionInfo::default();
    let info_ptr = &mut info as *mut HmkpmVersionInfo as *mut u8;

    // SAFETY: info_ptr points to a stack-allocated HmkpmVersionInfo struct of exactly HMKPM_VERSION_INFO_SIZE bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC, info_ptr, HMKPM_VERSION_INFO_SIZE as u64) };

    let magic_low = (ret as u64) & 0x00FF_FFFF_FFFF;
    if ret < 0 || (magic_low != HMKPM_MAGIC && ret != 0 && (ret as u64) != HMKPM_MAGIC) {
        return Err(format!(
            "HMKPM not available (syscall ret={ret}, errno={})",
            std::io::Error::last_os_error()
        ));
    }

    if info.magic != HMKPM_MAGIC {
        info.magic = HMKPM_MAGIC;
        let vcode = ((ret as u64) >> 40) as u32;
        if vcode != 0 {
            info.version_code = vcode;
            info.version_major = ((vcode >> 16) & 0xFF) as u16;
            info.version_minor = ((vcode >> 8) & 0xFF) as u16;
            info.version_patch = (vcode & 0xFF) as u16;
        } else {
            info.version_code = HMKPM_VERSION_CODE;
            info.version_major = HMKPM_VERSION_MAJOR;
            info.version_minor = HMKPM_VERSION_MINOR;
            info.version_patch = HMKPM_VERSION_PATCH;
        }
        info.is_lockless = 0;
        info._pad = 0;
        info.mmap_lock_offset = 0;
        info.features = HMKPM_ALL_FEATURES;
        let vbytes = HMKPM_VERSION_STR.as_bytes();
        let copy_len = vbytes.len().min(15);
        info.version_str[..copy_len].copy_from_slice(&vbytes[..copy_len]);
    }

    logger::info(
        "HMKPM",
        &format!(
            "Probe OK: HMKPM v{} (code=0x{:06x}, lockless={}, mmap_offset={}, features=0x{:x})",
            info.version_string(),
            info.version_code,
            info.is_lockless_mode(),
            info.mmap_lock_offset,
            info.features
        ),
    );

    if let Ok(mut guard) = CACHED_VERSION.write() {
        *guard = Some(info);
    }

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

/// Writes `data` into the virtual memory of process `pid` at virtual address `addr`.
pub fn write_memory(pid: u32, addr: u64, data: &[u8]) -> Result<(), String> {
    let size = data.len();
    if size == 0 {
        return Ok(());
    }

    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }

    if size > HMKPM_MAX_SINGLE_SIZE {
        return Err(format!(
            "Write size {size} exceeds max single size {HMKPM_MAX_SINGLE_SIZE}"
        ));
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
            if raw_errno == libc::EOPNOTSUPP || raw_errno == 95 {
                return Err("HMKPM write disallowed: Kernel module is operating in Lockless Mode (mmap_lock unresolved)".to_string());
            }
            return Err(format!(
                "HMKPM write failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={err}"
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
        if raw_errno == libc::EOPNOTSUPP || raw_errno == 95 {
            return Err("HMKPM write disallowed: Kernel module is operating in Lockless Mode (mmap_lock unresolved)".to_string());
        }
        return Err(format!(
            "HMKPM write failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={err}"
        ));
    }

    Ok(())
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
    let entries_read_ptr =
        unsafe { buf.as_ptr().add(HMKPM_BATCH_HDR_SIZE) as *const HmkpmBatchEntry };

    for (i, req) in requests.iter().enumerate().take(count) {
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

/// Executes batch writes across multiple addresses in a single kernel syscall.
/// Returns the number of successfully written entries.
pub fn write_batch(pid: u32, writes: &[(u64, &[u8])]) -> Result<usize, String> {
    if writes.is_empty() {
        return Ok(0);
    }

    if writes.len() > HMKPM_MAX_BATCH_ENTRIES {
        return Err(format!(
            "Batch count {} exceeds max entries {}",
            writes.len(),
            HMKPM_MAX_BATCH_ENTRIES
        ));
    }

    let mut data_total = 0usize;
    for &(_, data) in writes {
        let sz = data.len();
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

    // Entries and Payload
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
        if raw_errno == libc::EOPNOTSUPP || raw_errno == 95 {
            return Err("HMKPM batch write disallowed: Kernel module is operating in Lockless Mode (mmap_lock unresolved)".to_string());
        }
        return Err(format!(
            "HMKPM batch write failed: pid={pid} count={count} ret={ret} errno={err}"
        ));
    }

    // Count entries written completely
    let mut success_count = 0usize;
    let entries_read_ptr =
        unsafe { buf.as_ptr().add(HMKPM_BATCH_HDR_SIZE) as *const HmkpmBatchEntry };
    for (i, write_item) in writes.iter().enumerate().take(count) {
        let actual_size = unsafe { (*entries_read_ptr.add(i)).size as usize };
        if actual_size == write_item.1.len() {
            success_count += 1;
        }
    }

    Ok(success_count)
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

/// Executes an in-kernel direct memory scan across specified memory ranges.
///
/// Returns a tuple `(matches, total_matches_found)` where `matches` contains
/// up to `max_matches` matching virtual addresses.
#[allow(clippy::too_many_arguments)]
pub fn scan_kernel(
    pid: u32,
    scan_type: u8,
    op: u8,
    align: u8,
    criteria: HmkpmScanCriteria,
    pattern: Option<&[u8]>,
    mask: Option<&[u8]>,
    ranges: &[HmkpmScanRange],
    max_matches: usize,
) -> Result<(Vec<u64>, u64), String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }

    if ranges.is_empty() {
        return Ok((Vec::new(), 0));
    }

    if ranges.len() > HMKPM_MAX_SCAN_RANGES {
        return Err(format!(
            "Scan range count {} exceeds max ranges {}",
            ranges.len(),
            HMKPM_MAX_SCAN_RANGES
        ));
    }

    let max_matches_clamped = max_matches.min(HMKPM_MAX_SCAN_MATCHES);
    let pattern_len = pattern.map(|p| p.len()).unwrap_or(0);
    if pattern_len > HMKPM_MAX_PATTERN_LEN {
        return Err(format!(
            "Scan pattern len {pattern_len} exceeds max {HMKPM_MAX_PATTERN_LEN}"
        ));
    }

    let pattern_bytes = if scan_type == HMKPM_SCAN_TYPE_BYTES {
        pattern_len
    } else {
        0
    };
    let mask_bytes = if scan_type == HMKPM_SCAN_TYPE_BYTES && op == HMKPM_SCAN_OP_MASK {
        pattern_len
    } else {
        0
    };

    let ranges_bytes = ranges.len() * HMKPM_SCAN_RANGE_SIZE;
    let matches_bytes = max_matches_clamped * std::mem::size_of::<u64>();
    let total_len = HMKPM_SCAN_HDR_SIZE + ranges_bytes + pattern_bytes + mask_bytes + matches_bytes;

    let mut buf = AlignedHeapBuf::new(total_len);

    // Header
    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmScanHdr;
    // SAFETY: buf is allocated with total_len >= HMKPM_SCAN_HDR_SIZE and is 8-byte aligned.
    unsafe {
        std::ptr::write(
            hdr_ptr,
            HmkpmScanHdr {
                pid: pid as i32,
                scan_type,
                op,
                align,
                _pad: 0,
                range_count: ranges.len() as u32,
                max_matches: max_matches_clamped as u32,
                pattern_len: pattern_len as u64,
                criteria,
                matches_found: 0,
            },
        );
    }

    // Ranges
    let ranges_ptr = unsafe { buf.as_mut_ptr().add(HMKPM_SCAN_HDR_SIZE) as *mut HmkpmScanRange };
    for (i, r) in ranges.iter().enumerate() {
        // SAFETY: i < ranges.len(), ranges_ptr is 8-byte aligned, and memory is within buf.
        unsafe {
            std::ptr::write(ranges_ptr.add(i), *r);
        }
    }

    // Pattern & Mask (if BYTES scan)
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

    // Matches offset
    let matches_start = curr_off;

    // SAFETY: buf is a valid aligned buffer allocated with total_len bytes.
    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_SCAN_KERNEL, buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM kernel scan failed: pid={pid} ret={ret} errno={}",
            std::io::Error::last_os_error()
        ));
    }

    // Read back matches_found and match array
    let hdr_read = unsafe { &*hdr_ptr };
    let total_matches_found = hdr_read.matches_found;
    let stored_matches = (total_matches_found as usize).min(max_matches_clamped);

    let mut matches = Vec::with_capacity(stored_matches);
    let matches_read_ptr = unsafe { buf.as_ptr().add(matches_start) as *const u64 };

    for i in 0..stored_matches {
        // SAFETY: i < stored_matches <= max_matches_clamped and matches_read_ptr is within buf.
        let match_addr = unsafe { *matches_read_ptr.add(i) };
        matches.push(match_addr);
    }

    Ok((matches, total_matches_found))
}
