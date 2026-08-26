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

//! Reader module for `/proc/pid/pagemap` used to filter memory regions in `maps.rs` and `scanner.rs`.
//!
//! # Linux `/proc/[pid]/pagemap` Specification
//! The `/proc/pid/pagemap` virtual file contains a 64-bit (8 bytes) entry per virtual page:
//! - Bits 0-54   : Page Frame Number (PFN) if present
//! - Bit  55     : Page soft-dirty
//! - Bit  56     : Page exclusively mapped
//! - Bit  62     : Page swapped (PM_SWAP)
//! - Bit  63     : Page present (PM_PRESENT - 1 = present in physical RAM)
//!
//! # Zero-Page & Uncommitted Memory Semantics
//! Uncommitted anonymous virtual memory (regions allocated via `mmap` or `malloc` but never written)
//! does not have a physical RAM page allocated by the Linux kernel and has neither `PM_PRESENT` nor `PM_SWAP`.
//! Reading such pages would return zeroes (mapped to the kernel zero page). Filtering by `PM_PRESENT` skips
//! uncommitted pages, which provides a massive performance boost (10x-50x) for memory scanning.
//!
//! # Strict Non-Fallback Verification Policy
//! To ensure deterministic behavior and memory safety, pagemap reading never blindly assumes pages are present
//! when an I/O or permission error occurs. All errors are propagated as typed `Result<T, String>` errors,
//! with transient system call interruptions (`EINTR`, `EAGAIN`) retried automatically.

use std::fs::File;
use std::io::{self, ErrorKind};
#[cfg(unix)]
use std::os::unix::fs::FileExt;
#[cfg(unix)]
use std::sync::OnceLock;

#[cfg(not(unix))]
trait FileExt {
    fn read_at(&self, buf: &mut [u8], offset: u64) -> io::Result<usize>;
}

#[cfg(windows)]
use std::os::windows::fs::FileExt as WinFileExt;

#[cfg(not(unix))]
impl FileExt for File {
    fn read_at(&self, buf: &mut [u8], offset: u64) -> io::Result<usize> {
        #[cfg(windows)]
        {
            self.seek_read(buf, offset)
        }
        #[cfg(not(windows))]
        {
            let _ = (buf, offset);
            Ok(0)
        }
    }
}

/// Bit indicating that the page is present in physical memory (RAM).
pub const PM_PRESENT: u64 = 1u64 << 63;
/// Bit indicating that the page is in swap (zram on Android).
pub const PM_SWAP: u64 = 1u64 << 62;

/// Maximum retry attempts for transient POSIX `EINTR` (interrupted system calls).
const MAX_EINTR_RETRIES: usize = 5;
/// Maximum retry attempts for transient `EAGAIN` / `WouldBlock`.
const MAX_WOULDBLOCK_RETRIES: usize = 3;

/// Returns the system page size with a thread-safe static cache.
#[inline]
pub fn system_page_size() -> u64 {
    #[cfg(unix)]
    {
        static PAGE_SIZE: OnceLock<u64> = OnceLock::new();
        *PAGE_SIZE.get_or_init(|| {
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

/// High-performance reader for `/proc/[pid]/pagemap` supporting batched `pread`
/// and file descriptor reuse for region filtering in `maps.rs` and `scanner.rs`.
pub struct PagemapReader {
    file: File,
    page_size: u64,
    buf: Vec<u8>,
}

impl PagemapReader {
    /// Opens `/proc/{pid}/pagemap` for reading. Returns an error if the file cannot be opened.
    pub fn new(pid: u32) -> io::Result<Self> {
        let path = format!("/proc/{pid}/pagemap");
        let file = File::open(&path)?;
        Ok(Self {
            file,
            page_size: system_page_size(),
            // 32 KiB buffer = 4096 pagemap entries per pread batch
            buf: vec![0u8; 4096 * 8],
        })
    }



    /// Returns `Ok(true)` if at least one page in `[start, end)` matches `mask`.
    /// With `PM_PRESENT | PM_SWAP`, this keeps regions that may become
    /// readable after faulting swapped pages back in.
    ///
    /// If no matching pages are found or EOF is reached without matches, returns `Ok(false)`.
    /// If an unrecoverable I/O or permission error occurs, returns `Err(String)`.
    pub fn has_candidate_pages(&mut self, start: u64, end: u64, mask: u64) -> Result<bool, String> {
        if end <= start || mask == 0 {
            return Ok(false);
        }

        let ps = self.page_size;
        let first_page_idx = start / ps;
        let last_page_idx = (end - 1) / ps;
        let num_pages = (last_page_idx - first_page_idx) + 1;

        let Some(mut offset) = first_page_idx.checked_mul(8) else {
            return Err(format!("Pagemap start address 0x{start:X} overflowed offset calculation"));
        };

        let mut remaining = num_pages;
        let max_batch_entries = (self.buf.len() / 8) as u64;

        while remaining > 0 {
            let batch_entries = remaining.min(max_batch_entries) as usize;
            let bytes_to_read = batch_entries * 8;

            let read_bytes = pread_exact_or_eof(&self.file, &mut self.buf[..bytes_to_read], offset)
                .map_err(|e| format!("Cannot read pagemap at offset 0x{offset:X}: {e}"))?;

            if read_bytes == 0 {
                // Reached true EOF without finding candidate pages
                break;
            }

            for chunk in self.buf[..read_bytes].as_chunks::<8>().0 {
                let entry = u64::from_ne_bytes(*chunk);
                if entry & mask != 0 {
                    return Ok(true);
                }
            }

            if read_bytes < bytes_to_read {
                // Partial read reaching EOF without finding candidate pages
                break;
            }

            remaining -= batch_entries as u64;
            let Some(next) = offset.checked_add(bytes_to_read as u64) else {
                return Err(format!("Pagemap offset overflow advancing from 0x{offset:X}"));
            };
            offset = next;
        }

        Ok(false)
    }

    /// Iterates over pagemap entries in `[start, end)` and groups contiguous
    /// pages matching `mask` into `(start, end)` ranges clamped strictly to `[start, end)`.
    ///
    /// If an unrecoverable I/O error occurs, returns `Err(String)`.
    pub fn get_present_ranges(&mut self, start: u64, end: u64, mask: u64) -> Result<Vec<(u64, u64)>, String> {
        if end <= start || mask == 0 {
            return Ok(Vec::new());
        }

        let ps = self.page_size;
        let first_page_idx = start / ps;
        let last_page_idx = (end - 1) / ps;
        let num_pages = (last_page_idx - first_page_idx) + 1;

        let Some(mut offset) = first_page_idx.checked_mul(8) else {
            return Err(format!("Pagemap offset calculation overflow for 0x{start:X}"));
        };

        let mut ranges: Vec<(u64, u64)> = Vec::new();
        let mut cur_range: Option<(u64, u64)> = None;

        let mut page_idx = first_page_idx;
        let mut remaining = num_pages;
        let max_batch_entries = (self.buf.len() / 8) as u64;

        while remaining > 0 {
            let batch_entries = remaining.min(max_batch_entries) as usize;
            let bytes_to_read = batch_entries * 8;

            let read_bytes = pread_exact_or_eof(&self.file, &mut self.buf[..bytes_to_read], offset)
                .map_err(|e| format!("Cannot read pagemap at offset 0x{offset:X}: {e}"))?;

            if read_bytes == 0 {
                break;
            }

            for chunk in self.buf[..read_bytes].as_chunks::<8>().0 {
                let entry = u64::from_ne_bytes(*chunk);
                let is_present = (entry & mask) != 0;

                if is_present {
                    if let Some((_, ref mut c_end)) = cur_range {
                        *c_end = page_idx + 1;
                    } else {
                        cur_range = Some((page_idx, page_idx + 1));
                    }
                } else if let Some((c_start, c_end)) = cur_range.take() {
                    let r_start = (c_start.saturating_mul(ps)).max(start);
                    let r_end = (c_end.saturating_mul(ps)).min(end);
                    if r_start < r_end {
                        ranges.push((r_start, r_end));
                    }
                }

                page_idx += 1;
            }

            if read_bytes < bytes_to_read {
                break;
            }

            remaining -= batch_entries as u64;
            let Some(next) = offset.checked_add(bytes_to_read as u64) else {
                return Err(format!("Pagemap offset overflow advancing from 0x{offset:X}"));
            };
            offset = next;
        }

        if let Some((c_start, c_end)) = cur_range {
            let r_start = (c_start.saturating_mul(ps)).max(start);
            let r_end = (c_end.saturating_mul(ps)).min(end);
            if r_start < r_end {
                ranges.push((r_start, r_end));
            }
        }

        Ok(ranges)
    }
}

/// Standalone check to determine if a single virtual page at `addr` matches `mask`.
///
/// Opens `/proc/{pid}/pagemap`, calculates the offset for `addr` (`(addr / page_size) * 8`),
/// reads the 8-byte entry using `pread`, and checks whether `(entry & mask) != 0`.
/// Returns `Err(String)` if the pagemap file cannot be opened or read.
pub fn is_page_present(pid: u32, addr: u64, mask: u64) -> Result<bool, String> {
    let path = format!("/proc/{pid}/pagemap");
    let file = File::open(&path).map_err(|e| format!("Cannot open {path}: {e}"))?;

    let page_size = system_page_size();
    let page_idx = addr / page_size;
    let Some(offset) = page_idx.checked_mul(8) else {
        return Err(format!("Address 0x{addr:X} offset overflow"));
    };

    let mut buf = [0u8; 8];
    let read_bytes = pread_exact_or_eof(&file, &mut buf, offset)
        .map_err(|e| format!("Cannot read pagemap for 0x{addr:X}: {e}"))?;

    if read_bytes != 8 {
        return Err(format!("Pagemap read truncated ({read_bytes}/8 bytes) for 0x{addr:X}"));
    }

    let entry = u64::from_ne_bytes(buf);
    Ok((entry & mask) != 0)
}

/// Helper for reading with `pread` until the buffer is filled or EOF is reached,
/// with automatic retries for transient signals (`EINTR`) and resource contention (`EAGAIN`).
fn pread_exact_or_eof(file: &File, buf: &mut [u8], mut offset: u64) -> io::Result<usize> {
    let mut total = 0;
    let mut eintr_retries = 0;
    let mut wouldblock_retries = 0;

    while total < buf.len() {
        match file.read_at(&mut buf[total..], offset) {
            Ok(0) => break,
            Ok(n) => {
                total += n;
                offset = offset
                    .checked_add(n as u64)
                    .ok_or_else(|| io::Error::new(ErrorKind::InvalidInput, "offset overflow"))?;
                eintr_retries = 0;
                wouldblock_retries = 0;
            }
            Err(e) if e.kind() == ErrorKind::Interrupted => {
                eintr_retries += 1;
                if eintr_retries > MAX_EINTR_RETRIES {
                    return Err(e);
                }
                continue;
            }
            Err(e) if e.kind() == ErrorKind::WouldBlock => {
                wouldblock_retries += 1;
                if wouldblock_retries > MAX_WOULDBLOCK_RETRIES {
                    return Err(e);
                }
                std::thread::yield_now();
                continue;
            }
            Err(e) => return Err(e),
        }
    }

    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_is_page_present_nonexistent_pid_returns_err() {
        // Non-existent pid must return Err, NEVER true
        let res = is_page_present(999_999, 0x1000, PM_PRESENT);
        assert!(res.is_err());
    }

    #[test]
    fn test_has_candidate_pages_with_mock_file() {
        let temp_dir = std::env::temp_dir();
        let file_path = temp_dir.join("test_pagemap_candidate_mock.bin");

        // Write mock entries for 4 pages:
        // Page 0: 0 (not present)
        // Page 1: 0 (not present)
        // Page 2: PM_PRESENT
        // Page 3: 0 (not present)
        {
            let mut f = File::create(&file_path).unwrap();
            let entries = [
                0u64.to_ne_bytes(),
                0u64.to_ne_bytes(),
                PM_PRESENT.to_ne_bytes(),
                0u64.to_ne_bytes(),
            ];
            for entry in entries {
                f.write_all(&entry).unwrap();
            }
            f.flush().unwrap();
        }

        let file = File::open(&file_path).unwrap();
        let mut reader = PagemapReader {
            file,
            page_size: 4096,
            buf: vec![0u8; 16],
        };

        // Range [0, 8192) -> Pages 0..1: No present pages -> Ok(false)
        assert_eq!(reader.has_candidate_pages(0, 8192, PM_PRESENT), Ok(false));

        // Range [0, 16384) -> Pages 0..3: Page 2 is present -> Ok(true)
        assert_eq!(reader.has_candidate_pages(0, 16384, PM_PRESENT), Ok(true));

        // Range [8192, 12288) -> Page 2: Page 2 is present -> Ok(true)
        assert_eq!(reader.has_candidate_pages(8192, 12288, PM_PRESENT), Ok(true));

        // Range [12288, 16384) -> Page 3: No present pages -> Ok(false)
        assert_eq!(reader.has_candidate_pages(12288, 16384, PM_PRESENT), Ok(false));

        // Clean up
        let _ = std::fs::remove_file(file_path);
    }

    #[test]
    fn test_get_present_ranges_with_mock_file() {
        let temp_dir = std::env::temp_dir();
        let file_path = temp_dir.join("test_pagemap_mock.bin");

        // Write mock entries for 5 pages (pages 0..4):
        // Page 0: PM_PRESENT
        // Page 1: 0 (not present)
        // Page 2: PM_PRESENT
        // Page 3: PM_PRESENT
        // Page 4: 0 (not present)
        {
            let mut f = File::create(&file_path).unwrap();
            let entries = [
                PM_PRESENT.to_ne_bytes(),
                0u64.to_ne_bytes(),
                PM_PRESENT.to_ne_bytes(),
                PM_PRESENT.to_ne_bytes(),
                0u64.to_ne_bytes(),
            ];
            for entry in entries {
                f.write_all(&entry).unwrap();
            }
            f.flush().unwrap();
        }

        let file = File::open(&file_path).unwrap();
        let mut reader = PagemapReader {
            file,
            page_size: 4096,
            // Small buffer (16 bytes = 2 entries) to test multi-batching
            buf: vec![0u8; 16],
        };

        // Query range [2000, 15000)
        // Page 0: [0, 4096) -> clamped to [2000, 4096)
        // Page 1: [4096, 8192) -> not present
        // Pages 2..3: [8192, 16384) -> clamped to [8192, 15000)
        let ranges = reader.get_present_ranges(2000, 15000, PM_PRESENT).unwrap();
        assert_eq!(ranges, vec![(2000, 4096), (8192, 15000)]);

        // Inverted or empty range: returns empty
        assert_eq!(reader.get_present_ranges(15000, 2000, PM_PRESENT).unwrap(), vec![]);
        assert_eq!(reader.get_present_ranges(2000, 2000, PM_PRESENT).unwrap(), vec![]);

        // Zero mask: returns empty
        assert_eq!(reader.get_present_ranges(2000, 15000, 0).unwrap(), vec![]);

        // Clean up
        let _ = std::fs::remove_file(file_path);
    }
}
