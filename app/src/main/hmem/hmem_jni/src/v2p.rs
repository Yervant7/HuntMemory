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

//! Kernel V2P (Virtual to Physical) Translation & Page Residency Inspector
//!
//! Replaces legacy `/proc/[pid]/pagemap` parsing with direct MMU page table walks
//! via the HMKPM kernel module (`HMKPM_MAGIC_V2P_BATCH`).
//!
//! # Architecture & Security Advantages
//! 1. **Zero File Descriptors**: Operates without opening `/proc/<pid>/pagemap` or leaving
//!    open file descriptors in `/proc/self/fd`.
//! 2. **0% SELinux Audit Footprint**: Bypasses procfs VFS layers, preventing auditd/avc detections.
//! 3. **Batch Vectorized Execution**: Translates up to 4096 pages (16 MiB at 4 KiB/page) in a
//!    single kernel syscall invocation with hardware MMU walk.

use crate::kpm::{self, HMKPM_MAX_V2P_ENTRIES, HmkpmV2pEntry};
#[cfg(unix)]
use std::sync::OnceLock;

/// Bit flag indicating that the page is present in physical memory (RAM).
pub const PAGE_FLAG_PRESENT: u32 = kpm::HMKPM_PAGE_FLAG_PRESENT;
/// Bit flag indicating that the page is read-only.
#[allow(dead_code)]
pub const PAGE_FLAG_RO: u32 = kpm::HMKPM_PAGE_FLAG_RO;
/// Bit flag indicating that the page has been written to (dirty).
#[allow(dead_code)]
pub const PAGE_FLAG_DIRTY: u32 = kpm::HMKPM_PAGE_FLAG_DIRTY;
/// Bit flag indicating that the translation corresponds to a huge block mapping.
#[allow(dead_code)]
pub const PAGE_FLAG_BLOCK: u32 = kpm::HMKPM_PAGE_FLAG_BLOCK;

/// Default batch size for V2P translation queries (4096 entries = 16 MiB at 4 KiB/page).
const V2P_DEFAULT_BATCH_SIZE: usize = 4096;

/// Returns the system page size with a thread-safe static cache.
#[inline]
pub fn system_page_size() -> u64 {
    #[cfg(unix)]
    {
        static PAGE_SIZE: OnceLock<u64> = OnceLock::new();
        *PAGE_SIZE.get_or_init(|| {
            // SAFETY: sysconf(_SC_PAGESIZE) is POSIX standard and thread-safe.
            let sz = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
            if sz > 0 && (sz as u64).is_power_of_two() {
                sz as u64
            } else {
                4096
            }
        })
    }
    #[cfg(not(unix))]
    {
        4096
    }
}

/// High-performance V2P reader supporting batched kernel page table inspection.
pub struct V2pReader {
    pid: u32,
    page_size: u64,
    entries: Vec<HmkpmV2pEntry>,
}

impl V2pReader {
    /// Creates a new V2P reader for the specified process ID.
    pub fn new(pid: u32) -> Result<Self, String> {
        if pid == 0 {
            return Err("PID cannot be 0".to_string());
        }
        Ok(Self {
            pid,
            page_size: system_page_size(),
            entries: Vec::with_capacity(V2P_DEFAULT_BATCH_SIZE),
        })
    }

    /// Returns `Ok(true)` if at least one page in `[start, end)` matches `flags_mask`.
    ///
    /// If no matching pages are resident, returns `Ok(false)`.
    /// If an unrecoverable kernel translation error occurs, returns `Err(String)`.
    pub fn has_candidate_pages(
        &mut self,
        start: u64,
        end: u64,
        flags_mask: u32,
    ) -> Result<bool, String> {
        if end <= start || flags_mask == 0 {
            return Ok(false);
        }

        let ps = self.page_size;
        let mut cur = start & !(ps - 1);
        let batch_cap = self.entries.capacity().min(HMKPM_MAX_V2P_ENTRIES);

        while cur < end {
            self.entries.clear();
            let mut probe_va = cur;

            while probe_va < end && self.entries.len() < batch_cap {
                self.entries.push(HmkpmV2pEntry {
                    va: probe_va,
                    pa: 0,
                    flags: 0,
                    page_size: 0,
                });
                probe_va = probe_va.saturating_add(ps);
            }

            if self.entries.is_empty() {
                break;
            }

            kpm::v2p_batch(self.pid, &mut self.entries)?;

            for entry in &self.entries {
                if (entry.flags & flags_mask) != 0 {
                    return Ok(true);
                }
            }

            cur = probe_va;
        }

        Ok(false)
    }

    /// Iterates over page table entries in `[start, end)` via HMKPM V2P batch translation
    /// and groups contiguous pages matching `flags_mask` into `(start, end)` ranges
    /// clamped strictly to `[start, end)`.
    pub fn get_present_ranges(
        &mut self,
        start: u64,
        end: u64,
        flags_mask: u32,
    ) -> Result<Vec<(u64, u64)>, String> {
        if end <= start || flags_mask == 0 {
            return Ok(Vec::new());
        }

        let ps = self.page_size;
        let mut ranges: Vec<(u64, u64)> = Vec::new();
        let mut cur_range: Option<(u64, u64)> = None;

        let mut cur = start & !(ps - 1);
        let batch_cap = self.entries.capacity().min(HMKPM_MAX_V2P_ENTRIES);

        while cur < end {
            self.entries.clear();
            let mut probe_va = cur;

            while probe_va < end && self.entries.len() < batch_cap {
                self.entries.push(HmkpmV2pEntry {
                    va: probe_va,
                    pa: 0,
                    flags: 0,
                    page_size: 0,
                });
                probe_va = probe_va.saturating_add(ps);
            }

            if self.entries.is_empty() {
                break;
            }

            kpm::v2p_batch(self.pid, &mut self.entries)?;

            for entry in &self.entries {
                let page_va = entry.va;
                let page_sz =
                    if entry.page_size as u64 >= ps && (entry.page_size as u64).is_power_of_two() {
                        entry.page_size as u64
                    } else {
                        ps
                    };
                let page_end = page_va.saturating_add(page_sz);
                let is_present = (entry.flags & flags_mask) != 0;

                if is_present {
                    if let Some((_, ref mut r_end)) = cur_range {
                        if *r_end == page_va {
                            *r_end = page_end;
                        } else {
                            let (c_start, c_end) = cur_range.take().unwrap();
                            let r_start = c_start.max(start);
                            let r_end = c_end.min(end);
                            if r_start < r_end {
                                ranges.push((r_start, r_end));
                            }
                            cur_range = Some((page_va, page_end));
                        }
                    } else {
                        cur_range = Some((page_va, page_end));
                    }
                } else if let Some((c_start, c_end)) = cur_range.take() {
                    let r_start = c_start.max(start);
                    let r_end = c_end.min(end);
                    if r_start < r_end {
                        ranges.push((r_start, r_end));
                    }
                }
            }

            cur = probe_va;
        }

        if let Some((c_start, c_end)) = cur_range {
            let r_start = c_start.max(start);
            let r_end = c_end.min(end);
            if r_start < r_end {
                ranges.push((r_start, r_end));
            }
        }

        Ok(ranges)
    }
}

/// Standalone check to determine if a single virtual page at `addr` matches `flags_mask`.
pub fn is_page_present(pid: u32, addr: u64, flags_mask: u32) -> Result<bool, String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }
    let ps = system_page_size();
    let page_base = addr & !(ps - 1);
    let mut entry = [HmkpmV2pEntry {
        va: page_base,
        pa: 0,
        flags: 0,
        page_size: 0,
    }];
    kpm::v2p_batch(pid, &mut entry)?;
    Ok((entry[0].flags & flags_mask) != 0)
}
