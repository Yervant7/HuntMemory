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

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::refs::Global;
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jstring};
use jni::{AttachGuard, Env, EnvUnowned};
use jni::{jni_sig, jni_str};
use std::panic::catch_unwind;

mod editor;
mod kpm;
mod logger;
mod maps;
mod scanner;
mod script;
mod types;
mod v2p;

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use types::*;

static SESSIONS: OnceLock<Mutex<HashMap<String, ScanSession>>> = OnceLock::new();

#[unsafe(no_mangle)]
pub unsafe extern "C" fn JNI_OnLoad(
    _vm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jint {
    logger::init();
    jni::sys::JNI_VERSION_1_6
}

pub(crate) fn get_sessions() -> &'static Mutex<HashMap<String, ScanSession>> {
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

fn store_session(session_id: &str, session: ScanSession) -> usize {
    let count = session.matches.len();
    if let Ok(mut map) = get_sessions().lock() {
        map.insert(session_id.to_string(), session);
    }
    count
}

fn to_jbyte_array(env: &mut Env, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn get_target_regions(
    pid: u32,
    filter_types_str: &str,
    custom: Option<String>,
) -> Result<Vec<MemoryRegion>, String> {
    let maps_opt = maps::MapsOptions::default();
    let maps = maps::parse_maps(pid, &maps_opt)?;
    let types_vec: Vec<String> = if filter_types_str.is_empty() {
        Vec::new()
    } else {
        filter_types_str.split(',').map(|s| s.to_string()).collect()
    };
    Ok(maps::filter_regions(&maps, &types_vec, custom))
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

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetHmkpmVersionInfo(
    unowned_env: EnvUnowned,
    _class: JClass,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let res = catch_unwind(std::panic::AssertUnwindSafe(|| {
        match kpm::probe_version() {
            Ok(info) => serde_json::json!({
                "available": true,
                "version": info.version_string(),
                "version_code": info.version_code,
                "version_major": info.version_major,
                "version_minor": info.version_minor,
                "version_patch": info.version_patch,
                "is_lockless": info.is_lockless_mode(),
                "mmap_lock_offset": info.mmap_lock_offset,
                "features": info.features,
                "supports_read": (info.features & kpm::HMKPM_FEATURE_READ) != 0,
                "supports_write": (info.features & kpm::HMKPM_FEATURE_WRITE) != 0,
                "supports_read_batch": (info.features & kpm::HMKPM_FEATURE_READ_BATCH) != 0,
                "supports_write_batch": (info.features & kpm::HMKPM_FEATURE_WRITE_BATCH) != 0,
                "supports_v2p_batch": (info.features & kpm::HMKPM_FEATURE_V2P_BATCH) != 0,
                "supports_kernel_scan": (info.features & kpm::HMKPM_FEATURE_SCAN_KERNEL) != 0,
                "supports_lockless": (info.features & kpm::HMKPM_FEATURE_LOCKLESS) != 0,
            })
            .to_string(),
            Err(e) => serde_json::json!({
                "available": false,
                "error": e,
            })
            .to_string(),
        }
    }));

    let json_str = res.unwrap_or_else(|_| "{\"available\":false,\"error\":\"panic\"}".to_string());
    to_jstring(env, &json_str)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeTranslateV2P(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    addresses: jni::objects::JLongArray,
) -> jni::sys::jlongArray {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let len = match addresses.len(env) {
        Ok(l) => l,
        Err(_) => return std::ptr::null_mut(),
    };

    if len == 0 || pid <= 0 {
        return match env.new_long_array(0) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }

    let mut vas = vec![0i64; len];
    if addresses.get_region(env, 0, &mut vas).is_err() {
        return std::ptr::null_mut();
    }

    let res = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut entries: Vec<kpm::HmkpmV2pEntry> = vas
            .iter()
            .map(|&va| kpm::HmkpmV2pEntry {
                va: va as u64,
                pa: 0,
                flags: 0,
                page_size: 0,
            })
            .collect();

        if kpm::v2p_batch(pid as u32, &mut entries).is_ok() {
            let pas: Vec<i64> = entries.iter().map(|e| e.pa as i64).collect();
            Some(pas)
        } else {
            None
        }
    }));

    if let Ok(Some(pas)) = res
        && let Ok(jarr) = env.new_long_array(pas.len())
        && jarr.set_region(env, 0, &pas).is_ok()
    {
        return jarr.into_raw();
    }

    std::ptr::null_mut()
}

// ============================================================
// JNI Exports (Rust Memory Scanner & Editor Engine)
// ============================================================

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetModuleBase(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    module_name: JString,
) -> jlong {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let mod_str = match get_jni_string(env, &module_name) {
        Ok(s) => s,
        Err(_) => return 0,
    };

    let res = catch_unwind(move || match maps::get_module_base(pid as u32, &mod_str) {
        Ok(base) => base as jlong,
        Err(e) => {
            logger::error("HMemJni", &format!("nativeGetModuleBase error: {e}"));
            0
        }
    });

    res.unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeResolvePointerChain(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    base_expr: JString,
    offsets_json: JString,
) -> jlong {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let base_str = match get_jni_string(env, &base_expr) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    let offsets_str = match get_jni_string(env, &offsets_json) {
        Ok(s) => s,
        Err(_) => "[]".to_string(),
    };

    let res = catch_unwind(move || -> jlong {
        let pid_u32 = pid as u32;
        let base_trimmed = base_str.trim();

        let base_addr: u64 = if let Some(hex) = base_trimmed
            .strip_prefix("0x")
            .or_else(|| base_trimmed.strip_prefix("0X"))
        {
            u64::from_str_radix(hex, 16).unwrap_or(0)
        } else if base_trimmed.chars().all(|c| c.is_ascii_digit()) && !base_trimmed.is_empty() {
            base_trimmed.parse::<u64>().unwrap_or(0)
        } else {
            maps::get_module_base(pid_u32, base_trimmed).unwrap_or(0)
        };

        if base_addr == 0 {
            return 0;
        }

        let raw_offsets: Vec<i64> = serde_json::from_str(&offsets_str).unwrap_or_default();
        match editor::resolve_pointer_chain(pid_u32, base_addr, &raw_offsets) {
            Ok(resolved) => resolved as jlong,
            Err(e) => {
                logger::error("HMemJni", &format!("nativeResolvePointerChain error: {e}"));
                0
            }
        }
    });

    res.unwrap_or(0)
}

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
        merge_adjacent: true,
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
                    let exp32 = i32::from_le_bytes(buf[8..12].try_into().unwrap());
                    let upper = u32::from_le_bytes(buf[12..16].try_into().unwrap());
                    if upper == 0 && exp32 != 0 {
                        format!("{m}e{exp32}")
                    } else {
                        let exp64 = i64::from_le_bytes(buf[8..16].try_into().unwrap());
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
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeBatchWriteBinary(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    payload: JByteArray,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    if payload.is_null() {
        return 0;
    }

    let byte_vec = match env.convert_byte_array(&payload) {
        Ok(v) => v,
        Err(e) => {
            logger::error(
                "HMemJni",
                &format!("nativeBatchWriteBinary array error: {e}"),
            );
            return -1;
        }
    };

    let res = catch_unwind(move || -> jint {
        match editor::batch_write_binary(pid as u32, &byte_vec) {
            Ok(count) => count as jint,
            Err(e) => {
                logger::error("HMemJni", &format!("nativeBatchWriteBinary error: {e}"));
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
    pid: jint,
    address: jlong,
) -> jboolean {
    let res =
        catch_unwind(move || editor::get_freeze_engine().unfreeze(pid as u32, address as u64));
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
    pid: jint,
    address: jlong,
) -> jboolean {
    let res =
        catch_unwind(move || editor::get_freeze_engine().is_frozen(pid as u32, address as u64));
    if res.unwrap_or(false) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

struct JniAidlUiBridge {
    vm: jni::JavaVM,
    callback_ref: Global<JObject<'static>>,
}

impl script::ScriptUiCallback for JniAidlUiBridge {
    fn show_alert(&self, title: &str, message: &str) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let j_title = env.new_string(title)?;
            let j_msg = env.new_string(message)?;
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("showAlert"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)V"),
                &[(&j_title).into(), (&j_msg).into()],
            )?;
            Ok(())
        });
    }

    fn show_toast(&self, message: &str) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let j_msg = env.new_string(message)?;
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("showToast"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[(&j_msg).into()],
            )?;
            Ok(())
        });
    }

    fn show_prompt(&self, title: &str, default_value: &str, keyboard_type: &str) -> Option<String> {
        let res: Result<Option<String>, jni::errors::Error> =
            self.vm.attach_current_thread(|env| {
                let j_title = env.new_string(title)?;
                let j_def = env.new_string(default_value)?;
                let j_kb = env.new_string(keyboard_type)?;
                let res_val = env.call_method(
                    self.callback_ref.as_obj(),
                    jni_str!("showPrompt"),
                    jni_sig!(
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                    ),
                    &[(&j_title).into(), (&j_def).into(), (&j_kb).into()],
                )?;
                let j_obj = res_val.l()?;
                if !j_obj.is_null() {
                    // SAFETY: `j_obj` is a valid, non-null Java String reference returned by `showPrompt`.
                    let j_str = unsafe { JString::from_raw(env, j_obj.as_raw()) };
                    if let Ok(res) = get_jni_string(env, &j_str) {
                        return Ok(Some(res));
                    }
                }
                Ok(None)
            });
        res.unwrap_or(None)
    }

    fn show_choice(&self, title: &str, items: &[String]) -> Option<usize> {
        let items_json = serde_json::to_string(items).unwrap_or_else(|_| "[]".to_string());
        let res: Result<Option<usize>, jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let j_title = env.new_string(title)?;
            let j_items = env.new_string(&items_json)?;
            let res_val = env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("showChoice"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)I"),
                &[(&j_title).into(), (&j_items).into()],
            )?;
            let idx = res_val.i()?;
            if idx > 0 {
                Ok(Some(idx as usize))
            } else {
                Ok(None)
            }
        });
        res.unwrap_or(None)
    }

    fn show_multi_choice(
        &self,
        title: &str,
        items: &[String],
        initial: &[bool],
    ) -> Option<Vec<bool>> {
        let items_json = serde_json::to_string(items).unwrap_or_else(|_| "[]".to_string());
        let initial_json = serde_json::to_string(initial).unwrap_or_else(|_| "[]".to_string());
        let res: Result<Option<Vec<bool>>, jni::errors::Error> =
            self.vm.attach_current_thread(|env| {
                let j_title = env.new_string(title)?;
                let j_items = env.new_string(&items_json)?;
                let j_sel = env.new_string(&initial_json)?;
                let res_val = env.call_method(
                    self.callback_ref.as_obj(),
                    jni_str!("showMultiChoice"),
                    jni_sig!(
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                    ),
                    &[(&j_title).into(), (&j_items).into(), (&j_sel).into()],
                )?;
                let j_obj = res_val.l()?;
                if !j_obj.is_null() {
                    // SAFETY: `j_obj` is a valid, non-null Java String reference returned by `showMultiChoice`.
                    let j_str = unsafe { JString::from_raw(env, j_obj.as_raw()) };
                    if let Ok(json_str) = get_jni_string(env, &j_str)
                        && let Ok(res) = serde_json::from_str(&json_str)
                    {
                        return Ok(Some(res));
                    }
                }
                Ok(None)
            });
        res.unwrap_or(None)
    }

    fn set_dynamic_menu(&self, menu_json: &str) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let j_json = env.new_string(menu_json)?;
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("setDynamicMenu"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[(&j_json).into()],
            )?;
            Ok(())
        });
    }

    fn clear_dynamic_menu(&self) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("clearDynamicMenu"),
                jni_sig!("()V"),
                &[],
            )?;
            Ok(())
        });
    }

    fn post_log(&self, line: &str) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let j_line = env.new_string(line)?;
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("postLog"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[(&j_line).into()],
            )?;
            Ok(())
        });
    }

    fn canvas_draw(&self, commands_json: &str) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let j_json = env.new_string(commands_json)?;
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("canvasDraw"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[(&j_json).into()],
            )?;
            Ok(())
        });
    }

    fn canvas_draw_binary(&self, bytes: &[u8]) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let j_bytes = env.byte_array_from_slice(bytes)?;
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("canvasDrawBinary"),
                jni_sig!("([B)V"),
                &[(&j_bytes).into()],
            )?;
            Ok(())
        });
    }

    fn canvas_clear(&self) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("canvasClear"),
                jni_sig!("()V"),
                &[],
            )?;
            Ok(())
        });
    }

    fn canvas_set_visible(&self, visible: bool) {
        let _: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("canvasSetVisible"),
                jni_sig!("(Z)V"),
                &[visible.into()],
            )?;
            Ok(())
        });
    }

    fn get_screen_size(&self) -> (u32, u32) {
        let res: Result<(u32, u32), jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let res_val = env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("getScreenDimensions"),
                jni_sig!("()J"),
                &[],
            )?;
            let packed = res_val.j()?;
            let w = ((packed >> 32) & 0xFFFFFFFF) as u32;
            let h = (packed & 0xFFFFFFFF) as u32;
            if w > 0 && h > 0 {
                Ok((w, h))
            } else {
                Ok((1080, 2400))
            }
        });
        res.unwrap_or((1080, 2400))
    }

    fn poll_menu_event(&self) -> Option<String> {
        let res: Result<Option<String>, jni::errors::Error> =
            self.vm.attach_current_thread(|env| {
                let res_val = env.call_method(
                    self.callback_ref.as_obj(),
                    jni_str!("pollMenuEvent"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?;
                let j_obj = res_val.l()?;
                if !j_obj.is_null() {
                    // SAFETY: `j_obj` is a valid, non-null Java String reference returned by `pollMenuEvent`.
                    let j_str = unsafe { JString::from_raw(env, j_obj.as_raw()) };
                    if let Ok(s) = get_jni_string(env, &j_str)
                        && !s.is_empty()
                    {
                        return Ok(Some(s));
                    }
                }
                Ok(None)
            });
        res.unwrap_or(None)
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeRunLuaScript(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    script_str: JString,
    callback_obj: JObject,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let vm_opt = env.get_java_vm().ok();
    let global_callback = if !callback_obj.is_null() {
        env.new_global_ref(&callback_obj).ok()
    } else {
        None
    };

    let code = match get_jni_string(env, &script_str) {
        Ok(s) => s,
        Err(e) => {
            let err_res = script::ScriptExecutionResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to decode script string: {e}")),
                result: None,
            };
            let json = serde_json::to_string(&err_res).unwrap_or_default();
            return to_jstring(env, &json);
        }
    };

    let res = catch_unwind(move || -> String {
        let ui_callback: std::sync::Arc<dyn script::ScriptUiCallback> =
            match (vm_opt, global_callback) {
                (Some(vm), Some(cb_ref)) => std::sync::Arc::new(JniAidlUiBridge {
                    vm,
                    callback_ref: cb_ref,
                }),
                _ => std::sync::Arc::new(script::NoOpUiCallback),
            };

        let execution = script::run_script(pid as u32, &code, ui_callback);
        serde_json::to_string(&execution).unwrap_or_else(|e| {
            format!("{{\"success\":false,\"output\":\"\",\"error\":\"JSON serialization error: {e}\",\"result\":null}}")
        })
    });

    let json = res.unwrap_or_else(|_| {
        "{\"success\":false,\"output\":\"\",\"error\":\"Panic while executing script\",\"result\":null}".to_string()
    });

    to_jstring(env, &json)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeCancelLuaScript(
    _unowned_env: EnvUnowned,
    _class: JClass,
) {
    let _ = catch_unwind(|| {
        script::cancel_script();
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetMemoryMapsBinary(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    require_read: jboolean,
    require_write: jboolean,
    include_swapped: jboolean,
    min_size: jlong,
    filter_types: JString,
    custom: JString,
) -> jbyteArray {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let filter_types_str = match get_jni_string(env, &filter_types) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let custom_str = get_optional_jstring(env, &custom);

    let maps_opt = maps::MapsOptions {
        require_read,
        require_write,
        include_swapped,
        min_size: min_size as u64,
        merge_adjacent: true,
    };

    let res = catch_unwind(move || match maps::parse_maps(pid as u32, &maps_opt) {
        Ok(maps) => {
            let types_vec: Vec<String> = if filter_types_str.is_empty() {
                Vec::new()
            } else {
                filter_types_str.split(',').map(|s| s.to_string()).collect()
            };
            let regions = maps::filter_regions(&maps, &types_vec, custom_str);
            encode_regions_binary(&regions)
        }
        Err(e) => {
            logger::error("HMemJni", &format!("nativeGetMemoryMapsBinary error: {e}"));
            Vec::new()
        }
    });

    let bytes = res.unwrap_or_default();
    to_jbyte_array(env, &bytes)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetResultsCount(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return 0,
    };

    let res = catch_unwind(move || {
        if let Ok(map) = get_sessions().lock()
            && let Some(session) = map.get(&sid_str)
        {
            return session.matches.len() as jint;
        }
        0
    });

    res.unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetResultsPage(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
    offset: jint,
    limit: jint,
) -> jbyteArray {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let off = (offset.max(0)) as usize;
    let lim = limit.clamp(1, 2000) as usize;

    let res = catch_unwind(move || {
        if let Ok(map) = get_sessions().lock()
            && let Some(session) = map.get(&sid_str)
        {
            let total = session.matches.len();
            let page_matches = session.get_page(off, lim);
            return encode_matches_page_binary(total, off, &page_matches);
        }
        Vec::new()
    });

    let bytes = res.unwrap_or_default();
    to_jbyte_array(env, &bytes)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanMemoryFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    value: JString,
    value_type: JString,
    filter_types: JString,
    custom: JString,
    operator: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let filter_str = get_jni_string(env, &filter_types).unwrap_or_default();
    let custom_str = get_optional_jstring(env, &custom);
    let op_str = match get_jni_string(env, &operator) {
        Ok(s) => s,
        Err(_) => "equal".to_string(),
    };

    let res = catch_unwind(move || -> jint {
        let vtypes = match ValueType::from_multi_str(&vtype_str) {
            Ok(v) => v,
            Err(e) => {
                logger::error(
                    "HMemJni",
                    &format!("nativeScanMemoryFast invalid value type: {e}"),
                );
                return -1;
            }
        };
        let op = ScanOperator::from_str(&op_str).unwrap_or(ScanOperator::Equal);
        let regions = match get_target_regions(pid as u32, &filter_str, custom_str) {
            Ok(r) => r,
            Err(e) => {
                logger::error(
                    "HMemJni",
                    &format!("nativeScanMemoryFast get_target_regions error: {e}"),
                );
                return -1;
            }
        };
        if let Err(e) = ensure_kpm() {
            logger::error(
                "HMemJni",
                &format!("nativeScanMemoryFast ensure_kpm error: {e}"),
            );
            return -1;
        }
        match scanner::scan_regions(pid as u32, &regions, &val_str, &vtypes, op) {
            Ok(session) => store_session(&sid_str, session) as jint,
            Err(e) => {
                logger::error(
                    "HMemJni",
                    &format!("nativeScanMemoryFast scan_regions error: {e}"),
                );
                -1
            }
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanRangeFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    min_value: JString,
    max_value: JString,
    value_type: JString,
    filter_types: JString,
    custom: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let min_str = match get_jni_string(env, &min_value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let max_str = match get_jni_string(env, &max_value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let filter_str = get_jni_string(env, &filter_types).unwrap_or_default();
    let custom_str = get_optional_jstring(env, &custom);

    let res = catch_unwind(move || -> jint {
        let vtypes = match ValueType::from_multi_str(&vtype_str) {
            Ok(v) => v,
            Err(_) => return -1,
        };
        let regions = match get_target_regions(pid as u32, &filter_str, custom_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_range(pid as u32, &regions, &min_str, &max_str, &vtypes) {
            Ok(session) => store_session(&sid_str, session) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanGroupFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    group_spec: JString,
    value_type: JString,
    filter_types: JString,
    custom: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let gspec_str = match get_jni_string(env, &group_spec) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let vtype_str = match get_jni_string(env, &value_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let filter_str = get_jni_string(env, &filter_types).unwrap_or_default();
    let custom_str = get_optional_jstring(env, &custom);

    let res = catch_unwind(move || -> jint {
        let vtype = match ValueType::from_str(&vtype_str) {
            Ok(v) => v,
            Err(_) => return -1,
        };
        let regions = match get_target_regions(pid as u32, &filter_str, custom_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_group(pid as u32, &regions, &gspec_str, vtype) {
            Ok(session) => store_session(&sid_str, session) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanObscuredFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    value: JString,
    obscured_type: JString,
    filter_types: JString,
    custom: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let otype_str = match get_jni_string(env, &obscured_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let filter_str = get_jni_string(env, &filter_types).unwrap_or_default();
    let custom_str = get_optional_jstring(env, &custom);

    let res = catch_unwind(move || -> jint {
        let otype = match ObscuredType::from_str(&otype_str) {
            Ok(o) => o,
            Err(_) => return -1,
        };
        let regions = match get_target_regions(pid as u32, &filter_str, custom_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_obscured(pid as u32, &regions, &val_str, otype) {
            Ok(session) => store_session(&sid_str, session) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanBigDoubleFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    value: JString,
    filter_types: JString,
    custom: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let val_str = match get_jni_string(env, &value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let filter_str = get_jni_string(env, &filter_types).unwrap_or_default();
    let custom_str = get_optional_jstring(env, &custom);

    let res = catch_unwind(move || -> jint {
        let regions = match get_target_regions(pid as u32, &filter_str, custom_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_big_double(pid as u32, &regions, &val_str) {
            Ok(session) => store_session(&sid_str, session) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterMatchesFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    target_value: JString,
    operator: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let val_str = match get_jni_string(env, &target_value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let op_str = match get_jni_string(env, &operator) {
        Ok(s) => s,
        Err(_) => "equal".to_string(),
    };

    let res = catch_unwind(move || -> jint {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => return 0,
        };
        if session.matches.is_empty() {
            return 0;
        }
        let op = ScanOperator::from_str(&op_str).unwrap_or(ScanOperator::Equal);
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::filter_matches(pid as u32, &session, &val_str, op) {
            Ok(filtered) => store_session(&sid_str, filtered) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterRangeMatchesFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    min_value: JString,
    max_value: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let min_str = match get_jni_string(env, &min_value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let max_str = match get_jni_string(env, &max_value) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => return 0,
        };
        if session.matches.is_empty() {
            return 0;
        }
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::filter_range_matches(pid as u32, &session, &min_str, &max_str) {
            Ok(filtered) => store_session(&sid_str, filtered) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterObscuredMatchesFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    target_value: JString,
    obscured_type: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let val_str = match get_jni_string(env, &target_value) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let otype_str = match get_jni_string(env, &obscured_type) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => return 0,
        };
        if session.matches.is_empty() {
            return 0;
        }
        let otype = match ObscuredType::from_str(&otype_str) {
            Ok(o) => o,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::filter_obscured_matches(pid as u32, &session, &val_str, otype) {
            Ok(filtered) => store_session(&sid_str, filtered) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeFilterBigDoubleMatchesFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    target_value: JString,
) -> jint {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let val_str = match get_jni_string(env, &target_value) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let session = match get_sessions().lock().unwrap().get(&sid_str) {
            Some(s) => s.clone(),
            None => return 0,
        };
        if session.matches.is_empty() {
            return 0;
        }
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::filter_big_double_matches(pid as u32, &session, &val_str) {
            Ok(filtered) => store_session(&sid_str, filtered) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeSetScanEngineMode(
    _env: EnvUnowned,
    _class: JClass,
    mode: jint,
) {
    let res = catch_unwind(move || {
        scanner::set_scan_engine_mode(mode as u8);
    });
    let _ = res;
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetScanEngineMode(
    _env: EnvUnowned,
    _class: JClass,
) -> jint {
    let res = catch_unwind(move || -> jint { scanner::get_scan_engine_mode() as jint });
    res.unwrap_or(0)
}
