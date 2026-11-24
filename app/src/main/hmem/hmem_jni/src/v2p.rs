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
//! 2. **Zero Procfs Overhead**: Avoids procfs VFS traversal and reduces SELinux/auditd log noise.
//! 3. **Batch Vectorized Execution**: Translates up to 4096 pages (16 MiB at 4 KiB/page) in a
//!    single kernel syscall invocation with hardware MMU walk.

use crate::kpm::{self, HMKPM_MAX_V2P_ENTRIES, HmkpmRegionEntry, HmkpmV2pEntry};
use std::collections::HashSet;
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

/// Coalesces monotonically ordered V2P batch entries into non-overlapping, clamped
/// resident ranges, properly handling aligned block/hugepage mappings (e.g., 2 MiB PMD blocks).
///
/// Returns the highest virtual address covered by any present entry in `entries`.
fn coalesce_v2p_entries(
    entries: &[HmkpmV2pEntry],
    flags_mask: u32,
    default_ps: u64,
    start: u64,
    end: u64,
    cur_range: &mut Option<(u64, u64)>,
    ranges: &mut Vec<(u64, u64)>,
) -> u64 {
    let mut highest_covered_end = 0u64;

    for entry in entries {
        let probe_va = entry.va;
        let raw_ps = entry.page_size as u64;
        let page_sz = if raw_ps >= default_ps && raw_ps.is_power_of_two() {
            raw_ps
        } else {
            default_ps
        };
        let is_present = (entry.flags & flags_mask) != 0;

        if is_present {
            let block_start = probe_va & !(page_sz - 1);
            let block_end = block_start.saturating_add(page_sz);
            highest_covered_end = highest_covered_end.max(block_end);

            if let Some((_, r_end)) = cur_range {
                if block_start <= *r_end {
                    *r_end = (*r_end).max(block_end);
                } else {
                    let (c_start, c_end) = cur_range.take().unwrap();
                    let r_start = c_start.max(start);
                    let clamped_end = c_end.min(end);
                    if r_start < clamped_end {
                        ranges.push((r_start, clamped_end));
                    }
                    *cur_range = Some((block_start, block_end));
                }
            } else {
                *cur_range = Some((block_start, block_end));
            }
        } else if let Some((_, r_end)) = *cur_range
            && probe_va >= r_end
        {
            let (c_start, c_end) = cur_range.take().unwrap();
            let r_start = c_start.max(start);
            let clamped_end = c_end.min(end);
            if r_start < clamped_end {
                ranges.push((r_start, clamped_end));
            }
        }
    }

    highest_covered_end
}

/// Clamps and coalesces `HmkpmRegionEntry` spans returned by `HMKPM_MAGIC_ENUM_REGIONS`
/// into contiguous `(start, end)` resident ranges within `[start, end)`.
pub(crate) fn coalesce_enum_regions(
    entries: &[HmkpmRegionEntry],
    start: u64,
    end: u64,
) -> Vec<(u64, u64)> {
    let mut ranges: Vec<(u64, u64)> = Vec::with_capacity(entries.len());
    for entry in entries {
        if entry.size == 0 {
            continue;
        }
        let r_start = entry.start_va.max(start);
        let r_end = entry.start_va.saturating_add(entry.size).min(end);
        if r_start >= r_end {
            continue;
        }
        if let Some((_, last_end)) = ranges.last_mut()
            && r_start <= *last_end
        {
            *last_end = (*last_end).max(r_end);
            continue;
        }
        ranges.push((r_start, r_end));
    }
    ranges
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
    /// Uses O(1) empty-subtree-skipping `HMKPM_MAGIC_ENUM_REGIONS` when checking presence
    /// on HMKPM v3.0.0+, falling back to `V2P_BATCH` for custom flag masks or older modules.
    pub fn has_candidate_pages(
        &mut self,
        start: u64,
        end: u64,
        flags_mask: u32,
    ) -> Result<bool, String> {
        if end <= start || flags_mask == 0 {
            return Ok(false);
        }

        if flags_mask == PAGE_FLAG_PRESENT
            && kpm::is_feature_supported(kpm::HMKPM_FEATURE_ENUM_REGIONS)
            && let Ok((entries, _)) = kpm::enum_regions_page(self.pid, 0, start, end, 1)
        {
            return Ok(!entries.is_empty());
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

    /// Iterates over page table entries in `[start, end)` via HMKPM page-table walk
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

        if flags_mask == PAGE_FLAG_PRESENT
            && kpm::is_feature_supported(kpm::HMKPM_FEATURE_ENUM_REGIONS)
            && let Ok(entries) = kpm::enum_regions(self.pid, 0, start, end)
        {
            return Ok(coalesce_enum_regions(&entries, start, end));
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

            let covered_end = coalesce_v2p_entries(
                &self.entries,
                flags_mask,
                ps,
                start,
                end,
                &mut cur_range,
                &mut ranges,
            );

            // Advance past any hugepage block already covered so subsequent batches
            // never re-probe the interior of the same 2 MiB / 1 GiB mapping.
            cur = probe_va.max(covered_end);
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

/// Queries residency for all unique virtual pages covered by `spans` (`(addr, len)`)
/// in deduplicated kernel V2P batches, including end pages for page-crossing writes.
pub fn query_resident_pages_batch(
    pid: u32,
    spans: &[(u64, usize)],
    flags_mask: u32,
) -> Result<HashSet<u64>, String> {
    if pid == 0 {
        return Err("PID cannot be 0".to_string());
    }
    if spans.is_empty() || flags_mask == 0 {
        return Ok(HashSet::new());
    }

    let ps = system_page_size();
    let page_mask = !(ps - 1);

    let mut unique_pages: Vec<u64> = Vec::with_capacity(spans.len());
    for &(addr, len) in spans {
        if len == 0 {
            continue;
        }
        let first_page = addr & page_mask;
        let last_page = addr.saturating_add((len - 1) as u64) & page_mask;
        let mut p = first_page;
        loop {
            unique_pages.push(p);
            if p >= last_page {
                break;
            }
            let next = p.saturating_add(ps);
            if next == p {
                break;
            }
            p = next;
        }
    }

    if unique_pages.is_empty() {
        return Ok(HashSet::new());
    }

    unique_pages.sort_unstable();
    unique_pages.dedup();

    let batch_cap = V2P_DEFAULT_BATCH_SIZE.min(HMKPM_MAX_V2P_ENTRIES);
    let mut resident = HashSet::with_capacity(unique_pages.len());
    let mut entries: Vec<HmkpmV2pEntry> = Vec::with_capacity(unique_pages.len().min(batch_cap));

    for chunk in unique_pages.chunks(batch_cap) {
        entries.clear();
        for &page_va in chunk {
            entries.push(HmkpmV2pEntry {
                va: page_va,
                pa: 0,
                flags: 0,
                page_size: 0,
            });
        }
        kpm::v2p_batch(pid, &mut entries)?;
        for entry in &entries {
            if (entry.flags & flags_mask) != 0 {
                resident.insert(entry.va);
            }
        }
    }

    Ok(resident)
}

/// Returns true iff every page covered by `[addr, addr + len)` is present in `resident_pages`.
#[inline]
pub fn is_span_resident(resident_pages: &HashSet<u64>, addr: u64, len: usize) -> bool {
    if len == 0 {
        return true;
    }
    let ps = system_page_size();
    let page_mask = !(ps - 1);
    let first_page = addr & page_mask;
    let last_page = addr.saturating_add((len - 1) as u64) & page_mask;
    let mut p = first_page;
    loop {
        if !resident_pages.contains(&p) {
            return false;
        }
        if p >= last_page {
            return true;
        }
        let next = p.saturating_add(ps);
        if next == p {
            return true;
        }
        p = next;
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

#[cfg(test)]
mod tests {
    use super::*;

    fn mk_entry(va: u64, flags: u32, page_size: u32) -> HmkpmV2pEntry {
        HmkpmV2pEntry {
            va,
            pa: 0,
            flags,
            page_size,
        }
    }

    #[test]
    fn test_coalesce_4k_pages_with_gap() {
        let ps = 4096u64;
        let entries = vec![
            mk_entry(0x10000, PAGE_FLAG_PRESENT, 4096),
            mk_entry(0x11000, PAGE_FLAG_PRESENT, 4096),
            mk_entry(0x12000, 0, 4096),
            mk_entry(0x13000, PAGE_FLAG_PRESENT, 4096),
        ];
        let mut cur = None;
        let mut out = Vec::new();
        coalesce_v2p_entries(
            &entries,
            PAGE_FLAG_PRESENT,
            ps,
            0x10000,
            0x14000,
            &mut cur,
            &mut out,
        );
        if let Some((s, e)) = cur {
            out.push((s.max(0x10000), e.min(0x14000)));
        }
        assert_eq!(out, vec![(0x10000, 0x12000), (0x13000, 0x14000)]);
    }

    #[test]
    fn test_coalesce_2mb_hugepage_no_overlap() {
        let ps = 4096u64;
        let huge = 2 * 1024 * 1024u32; // 2 MiB
        let base = 0x20_0000u64;
        // Simulate 512 sequential 4 KiB probes inside the same 2 MiB hugepage
        let entries: Vec<HmkpmV2pEntry> = (0..512u64)
            .map(|i| mk_entry(base + i * ps, PAGE_FLAG_PRESENT | PAGE_FLAG_BLOCK, huge))
            .collect();

        let mut cur = None;
        let mut out = Vec::new();
        let covered_end = coalesce_v2p_entries(
            &entries,
            PAGE_FLAG_PRESENT,
            ps,
            base,
            base + huge as u64,
            &mut cur,
            &mut out,
        );
        if let Some((s, e)) = cur {
            out.push((s.max(base), e.min(base + huge as u64)));
        }

        assert_eq!(covered_end, base + huge as u64);
        assert_eq!(out, vec![(base, base + huge as u64)]);
    }

    #[test]
    fn test_coalesce_partial_hugepage_and_adjacent_4k() {
        let ps = 4096u64;
        let huge = 2 * 1024 * 1024u32;
        let start = 0x20_1000u64; // starts 4 KiB inside a 2 MiB hugepage at 0x20_0000
        let end = 0x40_2000u64; // extends 8 KiB past hugepage end (0x40_0000)

        let entries = vec![
            mk_entry(0x20_1000, PAGE_FLAG_PRESENT | PAGE_FLAG_BLOCK, huge),
            mk_entry(0x20_2000, PAGE_FLAG_PRESENT | PAGE_FLAG_BLOCK, huge),
            mk_entry(0x40_0000, PAGE_FLAG_PRESENT, 4096),
            mk_entry(0x40_1000, 0, 4096),
        ];

        let mut cur = None;
        let mut out = Vec::new();
        let covered_end = coalesce_v2p_entries(
            &entries,
            PAGE_FLAG_PRESENT,
            ps,
            start,
            end,
            &mut cur,
            &mut out,
        );
        if let Some((s, e)) = cur {
            let rs = s.max(start);
            let re = e.min(end);
            if rs < re {
                out.push((rs, re));
            }
        }

        assert_eq!(covered_end, 0x40_1000);
        assert_eq!(out, vec![(0x20_1000, 0x40_1000)]);
    }

    #[test]
    fn test_coalesce_ignores_stale_absent_probe_inside_hugepage() {
        let ps = 4096u64;
        let huge = 2 * 1024 * 1024u32;
        let base = 0x20_0000u64;
        let entries = vec![
            mk_entry(base, PAGE_FLAG_PRESENT | PAGE_FLAG_BLOCK, huge),
            mk_entry(base + ps, 0, 4096), // probe_va < r_end (base + 2 MiB)
        ];

        let mut cur = None;
        let mut out = Vec::new();
        coalesce_v2p_entries(
            &entries,
            PAGE_FLAG_PRESENT,
            ps,
            base,
            base + huge as u64,
            &mut cur,
            &mut out,
        );
        if let Some((s, e)) = cur {
            out.push((s.max(base), e.min(base + huge as u64)));
        }

        assert_eq!(out, vec![(base, base + huge as u64)]);
    }

    #[test]
    fn test_is_span_resident_cross_page() {
        let ps = system_page_size();
        let mut resident = HashSet::new();
        resident.insert(0x10000);
        assert!(is_span_resident(&resident, 0x10000 + ps - 4, 4));
        // Crosses into next page which is NOT resident
        assert!(!is_span_resident(&resident, 0x10000 + ps - 2, 4));
        resident.insert(0x10000 + ps);
        assert!(is_span_resident(&resident, 0x10000 + ps - 2, 4));
    }

    #[test]
    fn test_coalesce_enum_regions_clamping_and_merging() {
        let entries = vec![
            HmkpmRegionEntry {
                start_va: 0x0F000,
                size: 0x3000, // 0x0F000..0x12000 (clamped to 0x10000..0x12000)
                flags: kpm::HMKPM_REGION_FLAG_READ,
                _pad: 0,
            },
            HmkpmRegionEntry {
                start_va: 0x12000,
                size: 0x2000, // adjacent with different perms -> merged into 0x10000..0x14000
                flags: kpm::HMKPM_REGION_FLAG_READ | kpm::HMKPM_REGION_FLAG_WRITE,
                _pad: 0,
            },
            HmkpmRegionEntry {
                start_va: 0x16000,
                size: 0x5000, // 0x16000..0x1B000 (clamped to 0x16000..0x18000)
                flags: kpm::HMKPM_REGION_FLAG_READ,
                _pad: 0,
            },
        ];

        let ranges = coalesce_enum_regions(&entries, 0x10000, 0x18000);
        assert_eq!(ranges, vec![(0x10000, 0x14000), (0x16000, 0x18000)]);
    }
}
