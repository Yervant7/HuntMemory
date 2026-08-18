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

//! HMem JNI Library — Rust 2024 Edition
//!
//! - Remote virtual process memory read/write via HMKPM
//! - High-performance memory scanning with ARM64 NEON vectorization
//! - Support for Float16, Mantissa/Exponent (BigDouble), and Obscured XOR keypairs
//! - Memory session management and background freeze engine

#![deny(unsafe_op_in_unsafe_fn)]
#![allow(clippy::missing_safety_doc)]

use jni::objects::{JClass, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jstring};
use jni::{AttachGuard, Env, EnvUnowned};
use std::panic::catch_unwind;

mod editor;
mod kpm;
mod logger;
mod maps;
mod pagemap;
mod scanner;
mod types;

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use types::*;

static SESSIONS: OnceLock<Mutex<HashMap<String, ScanSession>>> = OnceLock::new();

fn get_sessions() -> &'static Mutex<HashMap<String, ScanSession>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn store_and_format_results(session_id: &str, session: ScanSession) -> String {
    let count = session.matches.len();
    let limited_matches = session.to_scan_matches(100);

    if let Ok(mut map) = get_sessions().lock() {
        map.insert(session_id.to_string(), session);
    }

    let res = ScanResult {
        matches: limited_matches,
        count,
    };
    serde_json::to_string(&res).unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string())
}

/// Helper to safely extract a Rust String from a JString reference.
fn get_jni_string(env: &mut Env, jstr: &JString) -> Result<String, String> {
    if jstr.is_null() {
        return Ok(String::new());
    }
    jstr.mutf8_chars(env)
        .map(|chars| chars.to_string())
        .map_err(|e| format!("JNI string extraction error: {e}"))
}

/// Helper to safely extract an optional Rust String from a nullable JString.
fn get_optional_jstring(env: &mut Env, jstr: &JString) -> Option<String> {
    if jstr.is_null() {
        return None;
    }
    jstr.mutf8_chars(env).map(|chars| chars.to_string()).ok()
}

/// Helper to allocate and return a raw JNI jstring pointer.
fn to_jstring(env: &mut Env, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn ensure_kpm() -> Result<(), String> {
    kpm::probe().map_err(|e| format!("HMKPM probe failed: {e}"))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeIsHmkpmAvailable(
    _unowned_env: EnvUnowned,
    _class: JClass,
) -> jboolean {
    match kpm::probe() {
        Ok(_) => JNI_TRUE,
        Err(e) => {
            logger::debug("HMemJni", &format!("nativeIsHmkpmAvailable: {e}"));
            JNI_FALSE
        }
    }
}

// ============================================================
// JNI Exports (Rust Memory Scanner & Editor Engine)
// ============================================================

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetMemoryMaps(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    require_read: jboolean,
    require_write: jboolean,
    include_swapped: jboolean,
    min_size: jlong,
    filter_types: JString,
    custom: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let filter_types_str = match get_jni_string(env, &filter_types) {
        Ok(s) => s,
        Err(_) => {
            logger::error(
                "HMemJni",
                "nativeGetMemoryMapsWithOptions error: get_jni_string filter_types failed",
            );
            return to_jstring(env, "");
        }
    };
    let custom_str = get_optional_jstring(env, &custom);

    let maps_opt = maps::MapsOptions {
        require_read,
        require_write,
        include_swapped,
        min_size: min_size as u64,
    };

    let res = catch_unwind(move || match maps::parse_maps(pid as u32, &maps_opt) {
        Ok(maps) => {
            let types_vec: Vec<String> = if filter_types_str.is_empty() {
                Vec::new()
            } else {
                filter_types_str.split(',').map(|s| s.to_string()).collect()
            };
            let regions = maps::filter_regions(&maps, &types_vec, custom_str);
            serde_json::to_string(&regions).unwrap_or_else(|_| "".to_string())
        }
        Err(e) => {
            logger::error(
                "HMemJni",
                &format!("nativeGetMemoryMapsWithOptions error: {e}"),
            );
            "".to_string()
        }
    });

    let json = res.unwrap_or_default();
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanMemory(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    value: JString,
    value_type: JString,
    regions_json: JString,
    operator: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let rjson_str = match get_jni_string(env, &regions_json) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let op_str = match get_jni_string(env, &operator) {
        Ok(s) => s,
        Err(_) => "equal".to_string(),
    };

    let res = catch_unwind(move || -> String {
        let vtypes = match ValueType::from_multi_str(&vtype_str) {
            Ok(v) => v,
            Err(e) => return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        };
        let op = ScanOperator::from_str(&op_str).unwrap_or(ScanOperator::Equal);
        let regions: Vec<MemoryRegion> = match serde_json::from_str(&rjson_str) {
            Ok(r) => r,
            Err(e) => {
                return format!(
                    "{{\"error\":\"Invalid regions JSON: {e}\",\"matches\":[],\"count\":0}}"
                );
            }
        };

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::scan_regions(pid as u32, &regions, &val_str, &vtypes, op) {
            Ok(session) => store_and_format_results(&sid_str, session),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanRange(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    min_value: JString,
    max_value: JString,
    value_type: JString,
    regions_json: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let min_str = match get_jni_string(env, &min_value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let max_str = match get_jni_string(env, &max_value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let rjson_str = match get_jni_string(env, &regions_json) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };

    let res = catch_unwind(move || -> String {
        let vtypes = match ValueType::from_multi_str(&vtype_str) {
            Ok(v) => v,
            Err(e) => return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        };
        let regions: Vec<MemoryRegion> = match serde_json::from_str(&rjson_str) {
            Ok(r) => r,
            Err(e) => {
                return format!(
                    "{{\"error\":\"Invalid regions JSON: {e}\",\"matches\":[],\"count\":0}}"
                );
            }
        };

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::scan_range(pid as u32, &regions, &min_str, &max_str, &vtypes) {
            Ok(session) => store_and_format_results(&sid_str, session),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanGroup(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    group_spec: JString,
    value_type: JString,
    regions_json: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let gspec = match get_jni_string(env, &group_spec) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let rjson_str = match get_jni_string(env, &regions_json) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };

    let res = catch_unwind(move || -> String {
        let vtype = match ValueType::from_str(&vtype_str) {
            Ok(v) => v,
            Err(_) => ValueType::Int,
        };
        let regions: Vec<MemoryRegion> = match serde_json::from_str(&rjson_str) {
            Ok(r) => r,
            Err(e) => {
                return format!(
                    "{{\"error\":\"Invalid regions JSON: {e}\",\"matches\":[],\"count\":0}}"
                );
            }
        };

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::scan_group(pid as u32, &regions, &gspec, vtype) {
            Ok(session) => store_and_format_results(&sid_str, session),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanObscured(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    value: JString,
    obscured_type: JString,
    regions_json: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let otype_str = match get_jni_string(env, &obscured_type) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let rjson_str = match get_jni_string(env, &regions_json) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };

    let res = catch_unwind(move || -> String {
        let otype = match ObscuredType::from_str(&otype_str) {
            Ok(o) => o,
            Err(e) => return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        };
        let regions: Vec<MemoryRegion> = match serde_json::from_str(&rjson_str) {
            Ok(r) => r,
            Err(e) => {
                return format!(
                    "{{\"error\":\"Invalid regions JSON: {e}\",\"matches\":[],\"count\":0}}"
                );
            }
        };

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::scan_obscured(pid as u32, &regions, &val_str, otype) {
            Ok(session) => store_and_format_results(&sid_str, session),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanBigDouble(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    value: JString,
    regions_json: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let rjson_str = match get_jni_string(env, &regions_json) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };

    let res = catch_unwind(move || -> String {
        let regions: Vec<MemoryRegion> = match serde_json::from_str(&rjson_str) {
            Ok(r) => r,
            Err(e) => {
                return format!(
                    "{{\"error\":\"Invalid regions JSON: {e}\",\"matches\":[],\"count\":0}}"
                );
            }
        };

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::scan_big_double(pid as u32, &regions, &val_str) {
            Ok(session) => store_and_format_results(&sid_str, session),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterMatches(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    target_value: JString,
    operator: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let val_str = match get_jni_string(env, &target_value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let op_str = match get_jni_string(env, &operator) {
        Ok(s) => s,
        Err(_) => "equal".to_string(),
    };

    let res = catch_unwind(move || -> String {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => {
                return "{\"error\":\"Session not found\",\"matches\":[],\"count\":0}".to_string();
            }
        };

        if session.matches.is_empty() {
            return "{\"matches\":[],\"count\":0}".to_string();
        }

        let op = ScanOperator::from_str(&op_str).unwrap_or(ScanOperator::Equal);
        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::filter_matches(pid as u32, &session, &val_str, op) {
            Ok(filtered) => store_and_format_results(&sid_str, filtered),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterRangeMatches(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    min_value: JString,
    max_value: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let min_str = match get_jni_string(env, &min_value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let max_str = match get_jni_string(env, &max_value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };

    let res = catch_unwind(move || -> String {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => {
                return "{\"error\":\"Session not found\",\"matches\":[],\"count\":0}".to_string();
            }
        };

        if session.matches.is_empty() {
            return "{\"matches\":[],\"count\":0}".to_string();
        }

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::filter_range_matches(pid as u32, &session, &min_str, &max_str) {
            Ok(filtered) => store_and_format_results(&sid_str, filtered),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterObscuredMatches(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    target_value: JString,
    obscured_type: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let val_str = match get_jni_string(env, &target_value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let otype_str = match get_jni_string(env, &obscured_type) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };

    let res = catch_unwind(move || -> String {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => {
                return "{\"error\":\"Session not found\",\"matches\":[],\"count\":0}".to_string();
            }
        };

        if session.matches.is_empty() {
            return "{\"matches\":[],\"count\":0}".to_string();
        }

        let otype = match ObscuredType::from_str(&otype_str) {
            Ok(o) => o,
            Err(e) => return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        };

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::filter_obscured_matches(pid as u32, &session, &val_str, otype) {
            Ok(filtered) => store_and_format_results(&sid_str, filtered),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterBigDoubleMatches(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    target_value: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };
    let val_str = match get_jni_string(env, &target_value) {
        Ok(s) => s,
        Err(e) => {
            return to_jstring(
                env,
                &format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
            );
        }
    };

    let res = catch_unwind(move || -> String {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => {
                return "{\"error\":\"Session not found\",\"matches\":[],\"count\":0}".to_string();
            }
        };

        if session.matches.is_empty() {
            return "{\"matches\":[],\"count\":0}".to_string();
        }

        if let Err(e) = ensure_kpm() {
            return format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}");
        }

        match scanner::filter_big_double_matches(pid as u32, &session, &val_str) {
            Ok(filtered) => store_and_format_results(&sid_str, filtered),
            Err(e) => format!("{{\"error\":\"{e}\",\"matches\":[],\"count\":0}}"),
        }
    });

    let json = res.unwrap_or_else(|_| "{\"matches\":[],\"count\":0}".to_string());
    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeClearSession(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
) {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    if let Ok(sid_str) = get_jni_string(env, &session_id)
        && let Ok(mut map) = get_sessions().lock()
    {
        map.remove(&sid_str);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeReadMemory(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    address: jlong,
    value_type: JString,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(_) => return to_jstring(env, ""),
    };

    let res = catch_unwind(move || -> String {
        if let Ok(vtype) = ValueType::from_str(&vtype_str) {
            let size = vtype.size();
            let mut buf = vec![0u8; size];

            match kpm::read_memory(pid as u32, address as u64, &mut buf) {
                Ok(()) => bytes_to_value_str(&buf, vtype),
                Err(e) => {
                    logger::error(
                        "HMemJni",
                        &format!("KPM read failed for 0x{address:x}: {e}"),
                    );
                    "".to_string()
                }
            }
        } else if let Ok(otype) = ObscuredType::from_str(&vtype_str) {
            let size = otype.size();
            let mut buf = vec![0u8; size];

            match kpm::read_memory(pid as u32, address as u64, &mut buf) {
                Ok(()) => match otype {
                    ObscuredType::ObscuredInt => {
                        let k = u32::from_le_bytes(buf[..4].try_into().unwrap());
                        let v = u32::from_le_bytes(buf[4..8].try_into().unwrap());
                        ((k ^ v) as i32).to_string()
                    }
                    ObscuredType::ObscuredFloat => {
                        let k = u32::from_le_bytes(buf[..4].try_into().unwrap());
                        let v = u32::from_le_bytes(buf[4..8].try_into().unwrap());
                        f32::from_bits(k ^ v).to_string()
                    }
                    ObscuredType::ObscuredDouble => {
                        let k = u64::from_le_bytes(buf[..8].try_into().unwrap());
                        let v = u64::from_le_bytes(buf[8..16].try_into().unwrap());
                        f64::from_bits(k ^ v).to_string()
                    }
                    ObscuredType::ObscuredLong => {
                        let k = u64::from_le_bytes(buf[..8].try_into().unwrap());
                        let v = u64::from_le_bytes(buf[8..16].try_into().unwrap());
                        ((k ^ v) as i64).to_string()
                    }
                },
                Err(e) => {
                    logger::error(
                        "HMemJni",
                        &format!("KPM read obscured failed for 0x{address:x}: {e}"),
                    );
                    "".to_string()
                }
            }
        } else if vtype_str.eq_ignore_ascii_case("big_double")
            || vtype_str.eq_ignore_ascii_case("bigdouble")
        {
            let mut buf = [0u8; 16];
            match kpm::read_memory(pid as u32, address as u64, &mut buf) {
                Ok(()) => {
                    let m = f64::from_le_bytes(buf[..8].try_into().unwrap());
                    let exp64 = i64::from_le_bytes(buf[8..16].try_into().unwrap());
                    let exp32 = i32::from_le_bytes(buf[8..12].try_into().unwrap()) as i64;
                    if exp64 == 0 && exp32 != 0 {
                        format!("{m}e{exp32}")
                    } else {
                        format!("{m}e{exp64}")
                    }
                }
                Err(e) => {
                    logger::error(
                        "HMemJni",
                        &format!("KPM read BigDouble failed for 0x{address:x}: {e}"),
                    );
                    "".to_string()
                }
            }
        } else {
            "".to_string()
        }
    });

    let s = res.unwrap_or_default();
    to_jstring(env, &s)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeWriteMemory(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    address: jlong,
    value: JString,
    value_type: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        if let Ok(vtype) = ValueType::from_str(&vtype_str) {
            match editor::write_value(pid as u32, address as u64, &val_str, vtype) {
                Ok(()) => 0,
                Err(e) => {
                    logger::error("HMemJni", &format!("nativeWriteValue error: {e}"));
                    -1
                }
            }
        } else if let Ok(otype) = ObscuredType::from_str(&vtype_str) {
            match editor::write_obscured(pid as u32, address as u64, &val_str, otype) {
                Ok(()) => 0,
                Err(e) => {
                    logger::error("HMemJni", &format!("nativeWriteMemory obscured error: {e}"));
                    -1
                }
            }
        } else if vtype_str.eq_ignore_ascii_case("big_double")
            || vtype_str.eq_ignore_ascii_case("bigdouble")
        {
            match editor::write_big_double(pid as u32, address as u64, &val_str) {
                Ok(()) => 0,
                Err(e) => {
                    logger::error(
                        "HMemJni",
                        &format!("nativeWriteMemory big_double error: {e}"),
                    );
                    -1
                }
            }
        } else {
            -1
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeWriteObscured(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    address: jlong,
    value: JString,
    obscured_type: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let otype_str = match get_jni_string(env, &obscured_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let otype = match ObscuredType::from_str(&otype_str) {
            Ok(o) => o,
            Err(_) => return -1,
        };

        match editor::write_obscured(pid as u32, address as u64, &val_str, otype) {
            Ok(()) => 0,
            Err(e) => {
                logger::error("HMemJni", &format!("nativeWriteObscured error: {e}"));
                -1
            }
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeWriteBigDouble(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    address: jlong,
    value: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        match editor::write_big_double(pid as u32, address as u64, &val_str) {
            Ok(()) => 0,
            Err(e) => {
                logger::error("HMemJni", &format!("nativeWriteBigDouble error: {e}"));
                -1
            }
        }
    });

    res.unwrap_or(-1)
}

#[derive(serde::Deserialize)]
struct WriteItem {
    address: u64,
    value: String,
    #[serde(default = "default_vtype")]
    value_type: String,
}

fn default_vtype() -> String {
    "int".to_string()
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeBatchWrite(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    writes_json: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let wjson_str = match get_jni_string(env, &writes_json) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let raw_writes: Vec<WriteItem> = match serde_json::from_str(&wjson_str) {
            Ok(w) => w,
            Err(_) => return -1,
        };

        let mut parsed_writes = Vec::new();
        for item in raw_writes {
            if let Ok(vtype) = ValueType::from_str(&item.value_type) {
                parsed_writes.push((item.address, item.value, vtype));
            }
        }

        match editor::batch_write(pid as u32, &parsed_writes) {
            Ok(count) => count as jint,
            Err(e) => {
                logger::error("HMemJni", &format!("nativeBatchWrite error: {e}"));
                -1
            }
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFreezeAddress(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    address: jlong,
    value: JString,
    value_type: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let vtype = match ValueType::from_str(&vtype_str) {
            Ok(v) => v,
            Err(_) => return -1,
        };

        match editor::get_freeze_engine().freeze(pid as u32, address as u64, &val_str, vtype) {
            Ok(()) => 0,
            Err(e) => {
                logger::error("HMemJni", &format!("nativeFreezeAddress error: {e}"));
                -1
            }
        }
    });
    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeUnfreezeAddress(
    _unowned_env: EnvUnowned,
    _class: JClass,
    address: jlong,
) -> jboolean {
    let res = catch_unwind(move || editor::get_freeze_engine().unfreeze(address as u64));
    if res.unwrap_or(false) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeUnfreezeAll(
    _unowned_env: EnvUnowned,
    _class: JClass,
) -> jint {
    let res = catch_unwind(move || editor::get_freeze_engine().unfreeze_all() as jint);
    res.unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeIsAddressFrozen(
    _unowned_env: EnvUnowned,
    _class: JClass,
    address: jlong,
) -> jboolean {
    let res = catch_unwind(move || editor::get_freeze_engine().is_frozen(address as u64));
    if res.unwrap_or(false) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
