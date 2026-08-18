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

//! Cross-platform logging module
//!
//! On Android, outputs to Android logcat via `__android_log_write` (linked with liblog.so);
//! on other platforms outputs to stderr.

/// ANDROID_LOG_DEBUG = 3
#[allow(dead_code)]
const ANDROID_LOG_DEBUG: libc::c_int = 3;

/// ANDROID_LOG_ERROR = 6
#[allow(dead_code)]
const ANDROID_LOG_ERROR: libc::c_int = 6;

#[cfg(target_os = "android")]
unsafe extern "C" {
    /// Writes a log message to the Android logcat.
    fn __android_log_write(
        prio: libc::c_int,
        tag: *const libc::c_char,
        text: *const libc::c_char,
    ) -> libc::c_int;
}

/// Logs a debug message.
pub fn debug(tag: &str, msg: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        if let (Ok(c_tag), Ok(c_msg)) = (CString::new(tag), CString::new(msg)) {
            // SAFETY: c_tag and c_msg are valid null-terminated C strings.
            unsafe {
                __android_log_write(ANDROID_LOG_DEBUG, c_tag.as_ptr(), c_msg.as_ptr());
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[DEBUG][{tag}] {msg}");
    }
}

/// Logs an error message.
pub fn error(tag: &str, msg: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        if let (Ok(c_tag), Ok(c_msg)) = (CString::new(tag), CString::new(msg)) {
            // SAFETY: c_tag and c_msg are valid null-terminated C strings.
            unsafe {
                __android_log_write(ANDROID_LOG_ERROR, c_tag.as_ptr(), c_msg.as_ptr());
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[ERROR][{tag}] {msg}");
    }
}
