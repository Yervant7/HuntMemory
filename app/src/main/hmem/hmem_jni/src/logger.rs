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

//! Cross-platform logging module with Android Logcat integration and panic diagnostic hooks.
//!
//! On Android, outputs to Android Logcat via `__android_log_write` (liblog);
//! on other platforms outputs to stderr/stdout.

use std::sync::Once;

#[allow(dead_code)]
const ANDROID_LOG_VERBOSE: libc::c_int = 2;
#[allow(dead_code)]
const ANDROID_LOG_DEBUG: libc::c_int = 3;
#[allow(dead_code)]
const ANDROID_LOG_INFO: libc::c_int = 4;
#[allow(dead_code)]
const ANDROID_LOG_WARN: libc::c_int = 5;
#[allow(dead_code)]
const ANDROID_LOG_ERROR: libc::c_int = 6;

static INIT_ONCE: Once = Once::new();

#[cfg(target_os = "android")]
unsafe extern "C" {
    /// Writes a log message to the Android logcat.
    fn __android_log_write(
        prio: libc::c_int,
        tag: *const libc::c_char,
        text: *const libc::c_char,
    ) -> libc::c_int;
}

#[inline]
fn log_native(_prio: libc::c_int, _tag: &str, _msg: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        if let (Ok(c_tag), Ok(c_msg)) = (CString::new(_tag), CString::new(_msg)) {
            // SAFETY: c_tag and c_msg are valid null-terminated C strings.
            unsafe {
                __android_log_write(_prio, c_tag.as_ptr(), c_msg.as_ptr());
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let level = match _prio {
            ANDROID_LOG_VERBOSE => "VERBOSE",
            ANDROID_LOG_DEBUG => "DEBUG",
            ANDROID_LOG_INFO => "INFO",
            ANDROID_LOG_WARN => "WARN",
            _ => "ERROR",
        };
        eprintln!("[{level}][{_tag}] {_msg}");
    }
}

/// Logs a verbose diagnostic trace (only active in debug builds).
#[inline]
#[allow(dead_code)]
pub fn trace(tag: &str, msg: &str) {
    #[cfg(debug_assertions)]
    {
        log_native(ANDROID_LOG_VERBOSE, tag, msg);
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = (tag, msg);
    }
}

/// Logs a debug message.
#[inline]
pub fn debug(tag: &str, msg: &str) {
    #[cfg(debug_assertions)]
    {
        log_native(ANDROID_LOG_DEBUG, tag, msg);
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = (tag, msg);
    }
}

/// Logs an informational message.
#[inline]
#[allow(dead_code)]
pub fn info(tag: &str, msg: &str) {
    log_native(ANDROID_LOG_INFO, tag, msg);
}

/// Logs a warning message.
#[inline]
#[allow(dead_code)]
pub fn warn(tag: &str, msg: &str) {
    log_native(ANDROID_LOG_WARN, tag, msg);
}

/// Logs an error message.
#[inline]
pub fn error(tag: &str, msg: &str) {
    log_native(ANDROID_LOG_ERROR, tag, msg);
}

/// Initializes the logging system and installs a custom panic hook to route
/// Rust panics directly to Android Logcat before unwinding.
pub fn init() {
    INIT_ONCE.call_once(|| {
        std::panic::set_hook(Box::new(|info| {
            let payload = if let Some(s) = info.payload().downcast_ref::<&str>() {
                *s
            } else if let Some(s) = info.payload().downcast_ref::<String>() {
                s.as_str()
            } else {
                "Unknown panic payload"
            };

            let location = if let Some(loc) = info.location() {
                format!("{}:{}:{}", loc.file(), loc.line(), loc.column())
            } else {
                "unknown location".to_string()
            };

            let err_msg = format!("RUST PANIC at {location}: {payload}");
            error("HMemPanic", &err_msg);
        }));

        debug("HMemJni", "Logger and panic diagnostics hook initialized");
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_logging_calls() {
        init();
        trace("TestTag", "Trace message");
        debug("TestTag", "Debug message");
        info("TestTag", "Info message");
        warn("TestTag", "Warn message");
        error("TestTag", "Error message");
    }
}
