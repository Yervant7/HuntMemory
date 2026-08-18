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

use crate::pagemap::{PM_PRESENT, PM_SWAP, PagemapReader};
use crate::types::MemoryRegion;
use std::fs::File;
use std::io::{BufRead, BufReader};

#[derive(Debug, Clone)]
pub struct MapsOptions {
    pub require_read: bool,

    /// Requires write permission ('w')
    pub require_write: bool,

    /// If true, considers swapped pages as valid for supported types (DATA/CD and LIBS/XA).
    pub include_swapped: bool,

    /// Minimum region size in bytes.
    pub min_size: u64,
}

impl Default for MapsOptions {
    fn default() -> Self {
        Self {
            require_read: true,
            require_write: false,
            include_swapped: true,
            min_size: 0,
        }
    }
}

/// Configurable streaming parser for `/proc/<pid>/maps`.
/// Implemented with minimal heap allocations per line.
pub fn parse_maps(pid: u32, opts: &MapsOptions) -> Result<Vec<MemoryRegion>, String> {
    let maps_path = format!("/proc/{pid}/maps");
    let file = File::open(&maps_path).map_err(|e| format!("Cannot read {maps_path}: {e}"))?;
    let reader = BufReader::with_capacity(64 * 1024, file);

    let mut regions = Vec::with_capacity(1024);

    // Open pagemap once and reuse buffer and descriptor
    let mut scanner = PagemapReader::new(pid);

    for line_result in reader.lines() {
        let line = match line_result {
            Ok(l) => l,
            Err(_) => continue,
        };

        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        // Format: address perms offset dev inode [path]
        let mut tokens = trimmed.split_ascii_whitespace();

        let Some(addr_range) = tokens.next() else {
            continue;
        };
        let Some((start_str, end_str)) = addr_range.split_once('-') else {
            continue;
        };

        let Ok(start) = u64::from_str_radix(start_str, 16) else {
            continue;
        };
        let Ok(end) = u64::from_str_radix(end_str, 16) else {
            continue;
        };

        if end <= start {
            continue;
        }

        let Some(permissions) = tokens.next() else {
            continue;
        };

        if opts.require_read && !permissions.contains('r') {
            continue;
        }

        if opts.require_write && !permissions.contains('w') {
            continue;
        }

        let offset = tokens
            .next()
            .and_then(|s| u64::from_str_radix(s, 16).ok())
            .unwrap_or(0);

        let _dev = tokens.next();
        let _inode = tokens.next();

        // Extract path as a direct slice from the original line
        let path_slice = extract_maps_path(trimmed);
        let path = normalize_path(path_slice);

        if is_excluded_region(&path) {
            continue;
        }

        let size = end - start;

        if opts.min_size > 0 && size < opts.min_size {
            continue;
        }

        // Swap rule: only "DATA" | "CD" and "LIBS" | "XA" support swap.
        // All other regions strictly require PM_PRESENT (in RAM).
        let pagemap_mask = if opts.include_swapped && region_supports_swap(&path) {
            PM_PRESENT | PM_SWAP
        } else {
            PM_PRESENT
        };

        // Pagemap check: apply to all regions except file-backed regions.
        let should_check = pagemap_mask != 0 && !is_file_backed(&path);

        if should_check && !scanner.has_resident_pages(start, end, pagemap_mask) {
            continue;
        }

        regions.push(MemoryRegion {
            start,
            end,
            permissions: permissions.to_string(),
            offset,
            path,
        });
    }

    Ok(regions)
}

/// Extracts the path slice from the maps line without heap allocations.
fn extract_maps_path(line: &str) -> &str {
    let mut spaces = 0;
    let mut in_whitespace = false;

    for (idx, b) in line.bytes().enumerate() {
        if b == b' ' || b == b'\t' {
            if !in_whitespace {
                spaces += 1;
                in_whitespace = true;
                if spaces == 5 {
                    return line[idx..].trim();
                }
            }
        } else {
            in_whitespace = false;
        }
    }

    ""
}

/// Checks whether a region supports swap.
/// Only "DATA" | "CD" and "LIBS" | "XA" support swapped pages.
pub fn region_supports_swap(path: &str) -> bool {
    is_data_region(path) || is_libs_region(path)
}

#[inline]
fn is_data_region(path: &str) -> bool {
    (path.contains("/data/app/")
        || path.contains("/data/data/")
        || path.contains("/data/user/")
        || path.contains("/data/user_de/")
        || path.contains("/mnt/expand/"))
        && !is_libs_region(path)
}

#[inline]
fn is_libs_region(path: &str) -> bool {
    path.ends_with(".so")
        || path.ends_with(".apk")
        || path.ends_with(".odex")
        || path.ends_with(".oat")
        || path.ends_with(".vdex")
        || path.ends_with(".art")
        || path.ends_with(".jar")
}

/// Removes " (deleted)" suffix and extra whitespace.
fn normalize_path(raw: &str) -> String {
    let p = raw.trim();
    let p = p.strip_suffix(" (deleted)").unwrap_or(p);
    p.trim().to_string()
}

/// Excludes regions that should not be directly read or written.
fn is_excluded_region(path: &str) -> bool {
    let p = path.trim();

    if p.is_empty() {
        return false;
    }

    // Special kernel pages.
    if matches!(
        p,
        "[vvar]" | "[vdso]" | "[vectors]" | "[vsyscall]" | "[uprobes]"
    ) {
        return true;
    }

    // Special anonymous regions that are typically not relevant.
    if p.starts_with("[anon:tracefs") || p.starts_with("[anon:inotify") {
        return true;
    }

    // Pseudo filesystems.
    if p.starts_with("/proc/")
        || p.starts_with("/sys/")
        || p.starts_with("/debug/")
        || p.starts_with("/trace/")
    {
        return true;
    }

    // Devices: most should not be read directly. Keep ashmem/memfd/dma_heap.
    if p.starts_with("/dev/") {
        if p.starts_with("/dev/ashmem") || p.contains("dma_heap") {
            return false;
        }
        return true;
    }

    false
}

/// Checks whether a mapped region corresponds to a filesystem file (file-backed).
///
/// File-backed regions (e.g., `.apk`, `.so`, `.dex`, `.odex`, `.art`, regular filesystem files)
/// are excluded from pagemap resident page checks by default, whereas non-file regions
/// (anonymous memory, heap, stack, ashmem, memfd) are checked against pagemap.
pub fn is_file_backed(path: &str) -> bool {
    let p = path.trim();
    if p.is_empty() {
        return false;
    }
    if p.starts_with('[') {
        return false;
    }
    if p.starts_with("/dev/ashmem") || p.starts_with("/memfd:") {
        return false;
    }
    p.starts_with('/')
}

/// Returns the base address of a module or 0 if not found.
#[allow(dead_code)]
pub fn get_module_base(pid: u32, module_name: &str) -> Result<u64, String> {
    Ok(get_module_base_opt(pid, module_name)?.unwrap_or(0))
}

/// Searches for a module base address in streaming mode.
#[allow(dead_code)]
pub fn get_module_base_opt(pid: u32, module_name: &str) -> Result<Option<u64>, String> {
    let module_name = module_name.trim();
    if module_name.is_empty() {
        return Ok(None);
    }

    let maps_path = format!("/proc/{pid}/maps");
    let file = File::open(&maps_path).map_err(|e| format!("Cannot read {maps_path}: {e}"))?;
    let reader = BufReader::with_capacity(32 * 1024, file);

    let mut fallback_base: Option<u64> = None;

    for line_result in reader.lines() {
        let line = match line_result {
            Ok(l) => l,
            Err(_) => continue,
        };

        let trimmed = line.trim();
        let mut tokens = trimmed.split_ascii_whitespace();

        let Some(addr_range) = tokens.next() else {
            continue;
        };
        let Some((start_str, _)) = addr_range.split_once('-') else {
            continue;
        };
        let Ok(start) = u64::from_str_radix(start_str, 16) else {
            continue;
        };

        let _perms = tokens.next();
        let offset = tokens
            .next()
            .and_then(|s| u64::from_str_radix(s, 16).ok())
            .unwrap_or(0);

        let _dev = tokens.next();
        let _inode = tokens.next();

        let raw_path = extract_maps_path(trimmed);
        if path_matches_module(raw_path, module_name) {
            if offset == 0 {
                return Ok(Some(start));
            } else if fallback_base.is_none() {
                fallback_base = Some(start);
            }
        }
    }

    Ok(fallback_base)
}

fn path_matches_module(path: &str, module_name: &str) -> bool {
    let module_name = module_name.trim();
    if module_name.is_empty() {
        return false;
    }

    let norm = normalize_path(path);
    let p = norm.as_str();

    // Complete or partial path match
    if module_name.contains('/') {
        return p.ends_with(module_name);
    }

    // Basename comparison
    let basename = p.rsplit('/').next().unwrap_or("");
    let basename = basename.strip_prefix("memfd:").unwrap_or(basename);

    basename == module_name
}

/// Filters memory regions by known types and/or custom filters.
pub fn filter_regions(
    regions: &[MemoryRegion],
    filter_types: &[String],
    custom_filter: Option<String>,
) -> Vec<MemoryRegion> {
    let custom = custom_filter
        .as_deref()
        .map(|s| s.trim())
        .filter(|s| !s.is_empty());

    if filter_types.is_empty() {
        return match custom {
            None => regions.to_vec(),
            Some(cf) => regions
                .iter()
                .filter(|r| r.path.contains(cf))
                .cloned()
                .collect(),
        };
    }

    let types: Vec<&str> = filter_types.iter().map(|s| s.trim()).collect();

    let has_custom_type = types.iter().any(|t| t.eq_ignore_ascii_case("CUSTOM"));

    regions
        .iter()
        .filter(|r| {
            let path = r.path.as_str();

            let type_ok = types.iter().any(|t| match_filter_type(t, path, custom));

            let custom_ok = match custom {
                Some(cf) if !has_custom_type => path.contains(cf),
                _ => true,
            };

            type_ok && custom_ok
        })
        .cloned()
        .collect()
}

fn match_filter_type(ft: &str, path: &str, custom: Option<&str>) -> bool {
    let ft_upper = ft.to_ascii_uppercase();
    match ft_upper.as_str() {
        "ALLOC" | "CA" => {
            path.contains("[anon:libc_malloc]")
                || path.contains("[anon:scudo:")
                || path.contains("[anon:GWP-ASan]")
                || path.contains("[anon:jemalloc]")
                || path.contains("[anon:malloc]")
        }

        "BSS" | "CB" => path.contains("[anon:.bss]") || path.contains("[anon:bss]"),

        "DATA" | "CD" => is_data_region(path),

        "HEAP" | "CH" => path == "[heap]" || path.starts_with("[heap"),

        "JAVA_HEAP" | "JH" => {
            path.contains("/dev/ashmem/dalvik")
                || path.contains("[anon:dalvik-")
                || path.contains("[anon:art_")
                || path.contains("[anon:main space]")
                || path.contains("[anon:alloc space]")
                || path.contains("[anon:zygote")
                || path.contains("[anon:large object space]")
                || path.contains("[anon:non moving space]")
        }

        "ANONYMOUS" | "A" => {
            path.is_empty()
                || (path.starts_with("[anon:")
                    && !path.contains("libc_malloc")
                    && !path.contains("scudo")
                    && !path.contains("jemalloc")
                    && !path.contains("GWP-ASan")
                    && !path.contains(".bss")
                    && !path.contains("dalvik")
                    && !path.contains("art_")
                    && !path.contains("ashmem"))
        }

        "STACK" | "S" => path.contains("[stack]") || path.contains("[stack:"),

        "ASHMEM" | "AS" => path.contains("/dev/ashmem") || path.contains("[anon:ashmem]"),

        "LIBS" | "XA" => is_libs_region(path),

        "CUSTOM" => custom.is_some_and(|cf| path.contains(cf)),

        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_file_backed() {
        // Non-file regions: must return false
        assert!(!is_file_backed(""));
        assert!(!is_file_backed("   "));
        assert!(!is_file_backed("[heap]"));
        assert!(!is_file_backed("[stack]"));
        assert!(!is_file_backed("[stack:1234]"));
        assert!(!is_file_backed("[anon:libc_malloc]"));
        assert!(!is_file_backed("[anon:scudo:primary]"));
        assert!(!is_file_backed("[anon:dalvik-main space]"));
        assert!(!is_file_backed("[anon:.bss]"));
        assert!(!is_file_backed("/dev/ashmem"));
        assert!(!is_file_backed("/dev/ashmem/dalvik-LinearAlloc"));
        assert!(!is_file_backed("/memfd:jit-cache"));

        // File-backed regions: must return true
        assert!(is_file_backed("/data/app/~~xyz==/base.apk"));
        assert!(is_file_backed("/data/data/com.example/databases/game.db"));
        assert!(is_file_backed("/system/lib64/libc.so"));
        assert!(is_file_backed(
            "/apex/com.android.runtime/lib64/bionic/libc.so"
        ));
        assert!(is_file_backed("/vendor/lib64/egl/libGLES.so"));
        assert!(is_file_backed("/storage/emulated/0/file.txt"));
    }

    #[test]
    fn test_maps_options_default() {
        let opts = MapsOptions::default();
        assert!(opts.require_read);
        assert!(!opts.require_write);
        assert!(opts.include_swapped);
        assert_eq!(opts.min_size, 0);
    }
}
