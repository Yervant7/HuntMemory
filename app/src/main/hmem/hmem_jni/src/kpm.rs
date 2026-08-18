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

//! Userspace client for the HMKPM (KernelPatch Module).
//!
//! ## Channel
//! `getresuid` syscall using custom command magics:
//! - `HMKPM_MAGIC` (Probe / Liveness)
//! - `HMKPM_MAGIC_READ` (Remote virtual address memory read)
//! - `HMKPM_MAGIC_WRITE` (Remote virtual address memory write)
//! - `HMKPM_MAGIC_READ_BATCH` (Batch read of multiple virtual addresses)
//! - `HMKPM_MAGIC_WRITE_BATCH` (Batch write of multiple virtual addresses)
//!
//! The kernel performs virtual address translation using the target process's `pgd` (`task->mm->pgd`),
//! eliminating userspace PFN translation overhead.

use crate::logger;

pub const HMKPM_MAGIC: u64 = 0x0048_4D4B_504D;
pub const HMKPM_MAGIC_READ: u64 = HMKPM_MAGIC + 1;
pub const HMKPM_MAGIC_WRITE: u64 = HMKPM_MAGIC + 2;
#[allow(dead_code)]
pub const HMKPM_MAGIC_READ_BATCH: u64 = HMKPM_MAGIC + 3;
pub const HMKPM_MAGIC_WRITE_BATCH: u64 = HMKPM_MAGIC + 4;

pub const HMKPM_MAX_SINGLE_SIZE: usize = 64 * 1024 * 1024;
pub const HMKPM_MAX_ENTRY_SIZE: usize = 64 * 1024 * 1024;
pub const HMKPM_MAX_BATCH_ENTRIES: usize = 65536;
pub const HMKPM_MAX_BATCH_TOTAL_SIZE: usize = 128 * 1024 * 1024;

/// Header size for `hmkpm_req` (24 bytes).
pub const HMKPM_REQ_SIZE: usize = 24;
/// Header size for `hmkpm_batch_hdr` (24 bytes).
pub const HMKPM_BATCH_HDR_SIZE: usize = 24;
/// Entry size for `hmkpm_batch_entry` (16 bytes).
pub const HMKPM_BATCH_ENTRY_SIZE: usize = 16;

#[allow(dead_code)]
const SYS_GETRESUID: libc::c_long = 148;

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

const _: () = assert!(std::mem::size_of::<HmkpmReq>() == HMKPM_REQ_SIZE);
const _: () = assert!(std::mem::size_of::<HmkpmBatchHdr>() == HMKPM_BATCH_HDR_SIZE);
const _: () = assert!(std::mem::size_of::<HmkpmBatchEntry>() == HMKPM_BATCH_ENTRY_SIZE);
const _: () = assert!(std::mem::align_of::<HmkpmReq>() == 8);
const _: () = assert!(std::mem::align_of::<HmkpmBatchHdr>() == 8);
const _: () = assert!(std::mem::align_of::<HmkpmBatchEntry>() == 8);

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

#[cfg(unix)]
#[inline]
unsafe fn kpm_syscall(magic: u64, ptr: *mut u8, len: u64) -> i64 {
    unsafe { libc::syscall(SYS_GETRESUID, magic, ptr, len) as i64 }
}

#[cfg(unix)]
#[inline]
unsafe fn kpm_probe_syscall() -> i64 {
    unsafe { libc::syscall(SYS_GETRESUID, HMKPM_MAGIC, 0usize, 0u64) as i64 }
}

#[cfg(not(unix))]
#[inline]
unsafe fn kpm_syscall(_magic: u64, _ptr: *mut u8, _len: u64) -> i64 {
    -1
}

#[cfg(not(unix))]
#[inline]
unsafe fn kpm_probe_syscall() -> i64 {
    -1
}

/// PROBE: checks if the HMKPM module is loaded and responding to the syscall.
pub fn probe() -> Result<(), String> {
    let ret = unsafe { kpm_probe_syscall() };

    if ret < 0 || (ret as u64) != HMKPM_MAGIC {
        return Err(format!(
            "HMKPM not available (syscall ret={ret}, errno={})",
            std::io::Error::last_os_error()
        ));
    }

    logger::debug("HMKPM", "probe ok: module responsive");
    Ok(())
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

    let mut req_buf = vec![0u8; total_len];
    let req_ptr = req_buf.as_mut_ptr() as *mut HmkpmReq;

    // SAFETY: req_buf is allocated with total_len >= HMKPM_REQ_SIZE.
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

        let ret = unsafe {
            kpm_syscall(
                HMKPM_MAGIC_WRITE,
                stack_buf.data.as_mut_ptr(),
                total_len as u64,
            )
        };

        if ret != 0 {
            return Err(format!(
                "HMKPM write failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={}",
                std::io::Error::last_os_error()
            ));
        }

        return Ok(());
    }

    let mut req_buf = vec![0u8; total_len];
    let req_ptr = req_buf.as_mut_ptr() as *mut HmkpmReq;

    // SAFETY: req_buf is allocated with total_len >= HMKPM_REQ_SIZE.
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

    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_WRITE, req_buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM write failed: pid={pid} addr=0x{addr:x} size={size} ret={ret} errno={}",
            std::io::Error::last_os_error()
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

    let mut buf = vec![0u8; total_len];

    // Header
    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmBatchHdr;
    // SAFETY: buf is allocated with total_len >= HMKPM_BATCH_HDR_SIZE.
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
        // SAFETY: i < count and the memory buffer was allocated with sufficient capacity.
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

    let mut buf = vec![0u8; total_len];

    // Header
    let hdr_ptr = buf.as_mut_ptr() as *mut HmkpmBatchHdr;
    // SAFETY: buf is allocated with total_len >= HMKPM_BATCH_HDR_SIZE.
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

    let ret = unsafe { kpm_syscall(HMKPM_MAGIC_WRITE_BATCH, buf.as_mut_ptr(), total_len as u64) };

    if ret != 0 {
        return Err(format!(
            "HMKPM batch write failed: pid={pid} count={count} ret={ret} errno={}",
            std::io::Error::last_os_error()
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
