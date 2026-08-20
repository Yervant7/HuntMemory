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

//! Reader module for `/proc/pid/pagemap` used to filter memory regions in `maps.rs`.
//!
//! The `/proc/pid/pagemap` file contains a 64-bit (8 bytes) entry per virtual page.
//! Bits 0-54   : Page Frame Number (PFN) if present
//! Bit  62     : Page swapped (PM_SWAP)
//! Bit  63     : Page present (PM_PRESENT - 1 = present in physical RAM)

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

#[cfg(not(unix))]
impl FileExt for File {
    fn read_at(&self, _buf: &mut [u8], _offset: u64) -> io::Result<usize> {
        Ok(0)
    }
}

/// Bit indicating that the page is present in physical memory (RAM).
pub const PM_PRESENT: u64 = 1u64 << 63;
/// Bit indicating that the page is in swap (zram on Android).
pub const PM_SWAP: u64 = 1u64 << 62;

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
/// and file descriptor reuse for region filtering in `maps.rs`.
pub struct PagemapReader {
    file: Option<File>,
    page_size: u64,
    buf: Vec<u8>,
}

impl PagemapReader {
    pub fn new(pid: u32) -> Self {
        let path = format!("/proc/{pid}/pagemap");
        Self {
            file: File::open(path).ok(),
            page_size: system_page_size(),
            // 32 KiB buffer = 4096 pagemap entries
            buf: vec![0u8; 4096 * 8],
        }
    }

    /// Returns true if:
    /// - Pagemap inspection is unavailable (safe fallback), or
    /// - At least one page matching `mask` exists in the range.
    pub fn has_resident_pages(&mut self, start: u64, end: u64, mask: u64) -> bool {
        if end <= start || mask == 0 {
            return false;
        }

        let Some(file) = self.file.as_ref() else {
            // No pagemap available: safe fallback
            return true;
        };

        let ps = self.page_size;
        let first_page_idx = start / ps;
        let last_page_idx = (end - 1) / ps;
        let num_pages = (last_page_idx - first_page_idx) + 1;

        let Some(mut offset) = first_page_idx.checked_mul(8) else {
            return true;
        };

        let mut remaining = num_pages;
        let max_batch_entries = (self.buf.len() / 8) as u64;

        while remaining > 0 {
            let batch_entries = remaining.min(max_batch_entries) as usize;
            let bytes_to_read = batch_entries * 8;

            match pread_exact_or_eof(file, &mut self.buf[..bytes_to_read], offset) {
                Ok(read_bytes) => {
                    if read_bytes == 0 {
                        break;
                    }

                    let (chunks, _) = self.buf[..read_bytes].as_chunks::<8>();
                    for &chunk in chunks {
                        let entry = u64::from_le_bytes(chunk);
                        if entry & mask != 0 {
                            return true;
                        }
                    }

                    if read_bytes < bytes_to_read {
                        break;
                    }

                    remaining -= batch_entries as u64;
                    let Some(next) = offset.checked_add(bytes_to_read as u64) else {
                        return true;
                    };
                    offset = next;
                }
                Err(_) => {
                    // I/O error: safe fallback
                    return true;
                }
            }
        }

        false
    }
}

/// Helper for reading with `pread` until the buffer is filled or EOF is reached.
fn pread_exact_or_eof(file: &File, buf: &mut [u8], mut offset: u64) -> io::Result<usize> {
    let mut total = 0;

    while total < buf.len() {
        match file.read_at(&mut buf[total..], offset) {
            Ok(0) => break,
            Ok(n) => {
                total += n;
                offset = offset
                    .checked_add(n as u64)
                    .ok_or_else(|| io::Error::new(ErrorKind::InvalidInput, "offset overflow"))?;
            }
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }

    Ok(total)
}
