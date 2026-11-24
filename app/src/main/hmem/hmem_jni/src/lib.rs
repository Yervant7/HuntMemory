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

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, OnceLock};
use types::*;

static SESSIONS: OnceLock<Mutex<HashMap<String, Arc<ScanSession>>>> = OnceLock::new();
static SESSION_HISTORIES: OnceLock<Mutex<HashMap<String, SessionHistory>>> = OnceLock::new();

#[derive(Clone)]
pub(crate) struct SessionHistoryEntry {
    pub session: Arc<ScanSession>,
    pub is_saved: bool,
}

#[derive(Default, Clone)]
pub(crate) struct SessionHistory {
    pub stack: Vec<SessionHistoryEntry>,
    pub current_is_saved: bool,
}

impl SessionHistory {
    pub fn clear(&mut self) {
        self.stack.clear();
        self.current_is_saved = false;
    }

    pub fn mark_current_saved(&mut self) {
        self.current_is_saved = true;
    }

    pub fn record_before_refinement(&mut self, previous: Arc<ScanSession>) {
        // Keep all explicitly saved checkpoints, dropping any prior unsaved auto-undo step.
        self.stack.retain(|entry| entry.is_saved);
        self.stack.push(SessionHistoryEntry {
            session: previous,
            is_saved: self.current_is_saved,
        });
        self.current_is_saved = false;
    }

    pub fn pop_undo(&mut self) -> Option<SessionHistoryEntry> {
        let entry = self.stack.pop()?;
        self.current_is_saved = entry.is_saved;
        Some(entry)
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn JNI_OnLoad(
    _vm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jint {
    logger::init();
    jni::sys::JNI_VERSION_1_6
}

pub(crate) fn get_sessions() -> &'static Mutex<HashMap<String, Arc<ScanSession>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn get_session_histories() -> &'static Mutex<HashMap<String, SessionHistory>> {
    SESSION_HISTORIES.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn get_session(session_id: &str) -> Option<Arc<ScanSession>> {
    let map = get_sessions().lock().unwrap_or_else(|e| e.into_inner());
    map.get(session_id).cloned()
}

pub(crate) fn insert_session(session_id: String, session: ScanSession) -> Arc<ScanSession> {
    insert_session_arc(session_id, Arc::new(session))
}

pub(crate) fn insert_session_arc(
    session_id: String,
    session: Arc<ScanSession>,
) -> Arc<ScanSession> {
    let mut map = get_sessions().lock().unwrap_or_else(|e| e.into_inner());
    map.insert(session_id, Arc::clone(&session));
    session
}

pub(crate) fn remove_session(session_id: &str) {
    {
        let mut map = get_sessions().lock().unwrap_or_else(|e| e.into_inner());
        map.remove(session_id);
    }
    let mut histories = get_session_histories()
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    histories.remove(session_id);
}

fn store_new_session(session_id: &str, session: ScanSession) -> usize {
    let count = session.matches.len();
    {
        let mut histories = get_session_histories()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        histories.entry(session_id.to_string()).or_default().clear();
    }
    let _ = insert_session(session_id.to_string(), session);
    count
}

fn store_filtered_session(
    session_id: &str,
    previous: Arc<ScanSession>,
    filtered: ScanSession,
    is_live_update: bool,
) -> usize {
    if !is_live_update {
        let mut histories = get_session_histories()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        histories
            .entry(session_id.to_string())
            .or_default()
            .record_before_refinement(previous);
    }
    let count = filtered.matches.len();
    let _ = insert_session(session_id.to_string(), filtered);
    count
}

fn to_jbyte_array(env: &mut Env, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

const MAP_FLAG_REQUIRE_READ: i32 = 1 << 0;
const MAP_FLAG_REQUIRE_WRITE: i32 = 1 << 1;
const MAP_FLAG_INCLUDE_SWAPPED: i32 = 1 << 2;

fn maps_options_from_flags(map_flags: i32, min_size: i64) -> maps::MapsOptions {
    maps::MapsOptions {
        require_read: (map_flags & MAP_FLAG_REQUIRE_READ) != 0,
        require_write: (map_flags & MAP_FLAG_REQUIRE_WRITE) != 0,
        include_swapped: (map_flags & MAP_FLAG_INCLUDE_SWAPPED) != 0,
        min_size: min_size.max(0) as u64,
        merge_adjacent: true,
    }
}

fn get_target_regions_with_options(
    pid: u32,
    filter_types_str: &str,
    custom: Option<String>,
    map_flags: i32,
    min_size: i64,
) -> Result<Vec<MemoryRegion>, String> {
    let maps_opt = maps_options_from_flags(map_flags, min_size);
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
    let res = catch_unwind(|| match kpm::probe() {
        Ok(_) => JNI_TRUE,
        Err(e) => {
            logger::debug("HMemJni", &format!("nativeIsHmkpmAvailable: {e}"));
            JNI_FALSE
        }
    });
    res.unwrap_or(JNI_FALSE)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetHmkpmVersionInfo(
    unowned_env: EnvUnowned,
    _class: JClass,
) -> jstring {
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let res = catch_unwind(std::panic::AssertUnwindSafe(
        || {
            match kpm::probe_version_fresh() {
            Ok(info) => serde_json::json!({
                "available": true,
                "struct_size": info.struct_size,
                "version": info.version_string(),
                "version_code": info.version_code,
                "version_major": info.version_major,
                "version_minor": info.version_minor,
                "version_patch": info.version_patch,
                "is_lockless": info.is_lockless_mode(),
                "lock_source": info.lock_source_str(),
                "mmap_lock_offset": info.mmap_lock_offset,
                "page_size": info.page_size,
                "kver": info.kver,
                "kver_str": info.kver_string(),
                "va_bits": info.va_bits,
                "tbi0": info.tbi0 != 0,
                "sw_pan_mode": info.sw_pan_mode_str(),
                "fsnotify_mode": info.fsnotify_mode_str(),
                "features": info.features,
                "supports_read": (info.features & kpm::HMKPM_FEATURE_READ) != 0,
                "supports_write": (info.features & kpm::HMKPM_FEATURE_WRITE) != 0,
                "supports_read_batch": (info.features & kpm::HMKPM_FEATURE_READ_BATCH) != 0,
                "supports_write_batch": (info.features & kpm::HMKPM_FEATURE_WRITE_BATCH) != 0,
                "supports_v2p_batch": (info.features & kpm::HMKPM_FEATURE_V2P_BATCH) != 0,
                "supports_kernel_scan": (info.features & kpm::HMKPM_FEATURE_SCAN_KERNEL) != 0,
                "supports_lockless": (info.features & kpm::HMKPM_FEATURE_LOCKLESS) != 0,
                "supports_read_chain": (info.features & kpm::HMKPM_FEATURE_READ_CHAIN) != 0,
                "supports_scan_refine": (info.features & kpm::HMKPM_FEATURE_SCAN_REFINE) != 0,
                "supports_enum_regions": (info.features & kpm::HMKPM_FEATURE_ENUM_REGIONS) != 0,
                "supports_scan_ext": (info.features & kpm::HMKPM_FEATURE_SCAN_EXT) != 0,
                "supports_fsnotify_filter": (info.features & kpm::HMKPM_FEATURE_FSNOTIFY_FILTER) != 0,
            })
            .to_string(),
            Err(e) => serde_json::json!({
                "available": false,
                "error": e,
            })
            .to_string(),
        }
        },
    ));

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
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeClearSession(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
) {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    if let Ok(sid_str) = get_jni_string(env, &session_id) {
        let _ = catch_unwind(move || {
            remove_session(&sid_str);
        });
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

impl JniAidlUiBridge {
    /// Executes a JNI callback closure on the current thread and clears any pending
    /// Java exception (e.g. `DeadObjectException` during overlay teardown) before returning
    /// so subsequent JNI calls do not trigger ART CheckJNI aborts.
    fn with_env<T>(&self, f: impl FnOnce(&mut Env) -> Result<T, jni::errors::Error>) -> Option<T> {
        let res: Result<T, jni::errors::Error> = self.vm.attach_current_thread(|env| {
            let out = f(env);
            if out.is_err() && env.exception_check() {
                env.exception_clear();
            }
            out
        });
        res.ok()
    }
}

impl script::ScriptUiCallback for JniAidlUiBridge {
    fn show_alert(&self, title: &str, message: &str) {
        let _ = self.with_env(|env| {
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
        let _ = self.with_env(|env| {
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
        self.with_env(|env| {
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
                // SAFETY: `j_obj` is a valid, non-null Java String local reference returned by
                // `showPrompt`; `into_raw()` transfers unique ownership to `JString` so
                // `DeleteLocalRef` is executed exactly once.
                let j_str = unsafe { JString::from_raw(env, j_obj.into_raw()) };
                if let Ok(res) = get_jni_string(env, &j_str) {
                    return Ok(Some(res));
                }
            }
            Ok(None)
        })
        .flatten()
    }

    fn show_choice(&self, title: &str, items: &[String]) -> Option<usize> {
        let items_json = serde_json::to_string(items).unwrap_or_else(|_| "[]".to_string());
        self.with_env(|env| {
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
        })
        .flatten()
    }

    fn show_multi_choice(
        &self,
        title: &str,
        items: &[String],
        initial: &[bool],
    ) -> Option<Vec<bool>> {
        let items_json = serde_json::to_string(items).unwrap_or_else(|_| "[]".to_string());
        let initial_json = serde_json::to_string(initial).unwrap_or_else(|_| "[]".to_string());
        self.with_env(|env| {
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
                // SAFETY: `j_obj` is a valid, non-null Java String local reference returned by
                // `showMultiChoice`; `into_raw()` transfers unique ownership to `JString` so
                // `DeleteLocalRef` is executed exactly once.
                let j_str = unsafe { JString::from_raw(env, j_obj.into_raw()) };
                if let Ok(json_str) = get_jni_string(env, &j_str)
                    && let Ok(res) = serde_json::from_str(&json_str)
                {
                    return Ok(Some(res));
                }
            }
            Ok(None)
        })
        .flatten()
    }

    fn set_dynamic_menu(&self, menu_json: &str) {
        let _ = self.with_env(|env| {
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
        let _ = self.with_env(|env| {
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
        let _ = self.with_env(|env| {
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

    fn canvas_draw_binary(&self, bytes: &[u8]) {
        let _ = self.with_env(|env| {
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
        let _ = self.with_env(|env| {
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
        let _ = self.with_env(|env| {
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
        self.with_env(|env| {
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
        })
        .unwrap_or((1080, 2400))
    }

    fn poll_menu_event(&self) -> Option<String> {
        self.with_env(|env| {
            let res_val = env.call_method(
                self.callback_ref.as_obj(),
                jni_str!("pollMenuEvent"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?;
            let j_obj = res_val.l()?;
            if !j_obj.is_null() {
                // SAFETY: `j_obj` is a valid, non-null Java String local reference returned by
                // `pollMenuEvent`; `into_raw()` transfers unique ownership to `JString` so
                // `DeleteLocalRef` is executed exactly once.
                let j_str = unsafe { JString::from_raw(env, j_obj.into_raw()) };
                if let Ok(s) = get_jni_string(env, &j_str)
                    && !s.is_empty()
                {
                    return Ok(Some(s));
                }
            }
            Ok(None)
        })
        .flatten()
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
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeSetSandboxDumpDir(
    unowned_env: EnvUnowned,
    _class: JClass,
    dir_path: JString,
) {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let path_opt = get_optional_jstring(env, &dir_path);
    let _ = catch_unwind(move || {
        script::set_sandbox_dump_dir(path_opt);
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
        get_session(&sid_str)
            .map(|session| session.matches.len() as jint)
            .unwrap_or(0)
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
        if let Some(session) = get_session(&sid_str) {
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
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeRefreshResultsPage(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    offset: jint,
    limit: jint,
) -> jbyteArray {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let off = (offset.max(0)) as usize;
    let lim = limit.clamp(1, 2000) as usize;
    let pid_u32 = pid.max(0) as u32;

    let res = catch_unwind(move || {
        if let Some(session) = get_session(&sid_str) {
            let total = session.matches.len();
            let page_matches = session.get_page_live(pid_u32, off, lim);
            return encode_matches_page_binary(total, off, &page_matches);
        }
        Vec::new()
    });

    let bytes = res.unwrap_or_default();
    to_jbyte_array(env, &bytes)
}

#[allow(clippy::too_many_arguments)]
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
    map_flags: jint,
    min_size: jlong,
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
        let regions = match get_target_regions_with_options(
            pid as u32,
            &filter_str,
            custom_str,
            map_flags,
            min_size,
        ) {
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
            Ok(session) => store_new_session(&sid_str, session) as jint,
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

#[allow(clippy::too_many_arguments)]
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
    map_flags: jint,
    min_size: jlong,
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
        let regions = match get_target_regions_with_options(
            pid as u32,
            &filter_str,
            custom_str,
            map_flags,
            min_size,
        ) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_range(pid as u32, &regions, &min_str, &max_str, &vtypes) {
            Ok(session) => store_new_session(&sid_str, session) as jint,
            Err(e) => {
                logger::error(
                    "HMemJni",
                    &format!("nativeScanRangeFast scan_range error: {e}"),
                );
                -1
            }
        }
    });

    res.unwrap_or(-1)
}

#[allow(clippy::too_many_arguments)]
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
    map_flags: jint,
    min_size: jlong,
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
        let regions = match get_target_regions_with_options(
            pid as u32,
            &filter_str,
            custom_str,
            map_flags,
            min_size,
        ) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_group(pid as u32, &regions, &gspec_str, vtype) {
            Ok(session) => store_new_session(&sid_str, session) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[allow(clippy::too_many_arguments)]
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
    map_flags: jint,
    min_size: jlong,
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
        let regions = match get_target_regions_with_options(
            pid as u32,
            &filter_str,
            custom_str,
            map_flags,
            min_size,
        ) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_obscured(pid as u32, &regions, &val_str, otype) {
            Ok(session) => store_new_session(&sid_str, session) as jint,
            Err(_) => -1,
        }
    });

    res.unwrap_or(-1)
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeScanBigDoubleFast(
    unowned_env: EnvUnowned,
    _class: JClass,
    pid: jint,
    session_id: JString,
    value: JString,
    filter_types: JString,
    custom: JString,
    map_flags: jint,
    min_size: jlong,
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
        let regions = match get_target_regions_with_options(
            pid as u32,
            &filter_str,
            custom_str,
            map_flags,
            min_size,
        ) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::scan_big_double(pid as u32, &regions, &val_str) {
            Ok(session) => store_new_session(&sid_str, session) as jint,
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
        let Some(session) = get_session(&sid_str) else {
            return 0;
        };
        if session.matches.is_empty() {
            return 0;
        }
        let op = ScanOperator::from_str(&op_str).unwrap_or(ScanOperator::Equal);
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::filter_matches(pid as u32, &session, &val_str, op) {
            Ok(filtered) => {
                store_filtered_session(&sid_str, session, filtered, op == ScanOperator::Update)
                    as jint
            }
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
        let Some(session) = get_session(&sid_str) else {
            return 0;
        };
        if session.matches.is_empty() {
            return 0;
        }
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::filter_range_matches(pid as u32, &session, &min_str, &max_str) {
            Ok(filtered) => store_filtered_session(&sid_str, session, filtered, false) as jint,
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
        let Some(session) = get_session(&sid_str) else {
            return 0;
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
            Ok(filtered) => store_filtered_session(&sid_str, session, filtered, false) as jint,
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
        let Some(session) = get_session(&sid_str) else {
            return 0;
        };
        if session.matches.is_empty() {
            return 0;
        }
        if ensure_kpm().is_err() {
            return -1;
        }
        match scanner::filter_big_double_matches(pid as u32, &session, &val_str) {
            Ok(filtered) => store_filtered_session(&sid_str, session, filtered, false) as jint,
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

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeCancelScan(
    _env: EnvUnowned,
    _class: JClass,
) {
    let _ = catch_unwind(|| {
        scanner::cancel_scan();
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeSaveSessionCheckpoint(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
) -> jboolean {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return JNI_FALSE,
    };

    let res = catch_unwind(move || -> jboolean {
        let Some(session) = get_session(&sid_str) else {
            return JNI_FALSE;
        };
        if session.matches.is_empty() {
            return JNI_FALSE;
        }
        let mut histories = get_session_histories()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        histories.entry(sid_str).or_default().mark_current_saved();
        JNI_TRUE
    });

    res.unwrap_or(JNI_FALSE)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeUndoSession(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
) -> jint {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let popped = {
            let mut histories = get_session_histories()
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            histories.get_mut(&sid_str).and_then(|h| h.pop_undo())
        };
        if let Some(entry) = popped {
            let count = entry.session.matches.len() as jint;
            insert_session_arc(sid_str, entry.session);
            count
        } else {
            -1
        }
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeGetSessionUndoCount(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
) -> jint {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return 0,
    };

    let res = catch_unwind(move || -> jint {
        let histories = get_session_histories()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        histories
            .get(&sid_str)
            .map(|h| h.stack.len() as jint)
            .unwrap_or(0)
    });

    res.unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeIsSessionSaved(
    unowned_env: EnvUnowned,
    _class: JClass,
    session_id: JString,
) -> jboolean {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let sid_str = match get_jni_string(env, &session_id) {
        Ok(s) => s,
        Err(_) => return JNI_FALSE,
    };

    let res = catch_unwind(move || -> jboolean {
        let histories = get_session_histories()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let saved = histories
            .get(&sid_str)
            .map(|h| h.current_is_saved)
            .unwrap_or(false);
        if saved { JNI_TRUE } else { JNI_FALSE }
    });

    res.unwrap_or(JNI_FALSE)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeCloneSession(
    unowned_env: EnvUnowned,
    _class: JClass,
    source_session_id: JString,
    target_session_id: JString,
) -> jint {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let src_id = match get_jni_string(env, &source_session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let dst_id = match get_jni_string(env, &target_session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = catch_unwind(move || -> jint {
        let Some(src_session) = get_session(&src_id) else {
            return -1;
        };
        let count = src_session.matches.len() as jint;
        if src_id != dst_id {
            {
                let mut histories = get_session_histories()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                histories.entry(dst_id.clone()).or_default().clear();
            }
            insert_session_arc(dst_id, Arc::clone(&src_session));
        }
        count
    });

    res.unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_yervant_huntmem_backend_NativeBridge_nativeCopyMatchesToSession(
    unowned_env: EnvUnowned,
    _class: JClass,
    source_session_id: JString,
    target_session_id: JString,
    addresses: jni::objects::JLongArray,
) -> jint {
    // SAFETY: `unowned_env` is a valid JNIEnv pointer provided by the JVM for this JNI call.
    let mut guard = unsafe { AttachGuard::from_unowned(unowned_env.as_raw()) };
    let env = guard.borrow_env_mut();

    let src_id = match get_jni_string(env, &source_session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let dst_id = match get_jni_string(env, &target_session_id) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let len = match addresses.len(env) {
        Ok(l) => l,
        Err(_) => return -1,
    };

    let mut raw_addrs = vec![0i64; len];
    if len > 0 && addresses.get_region(env, 0, &mut raw_addrs).is_err() {
        return -1;
    }

    let res = catch_unwind(move || -> jint {
        let Some(src_session) = get_session(&src_id) else {
            return -1;
        };
        if src_id == dst_id {
            return src_session.matches.len() as jint;
        }
        if raw_addrs.is_empty() {
            return get_session(&dst_id)
                .map(|s| s.matches.len() as jint)
                .unwrap_or(0);
        }

        let addr_set: HashSet<u64> = raw_addrs.into_iter().map(|a| a as u64).collect();
        let selected_matches: Vec<CompactMatch> = src_session
            .matches
            .iter()
            .filter(|m| addr_set.contains(&m.address))
            .copied()
            .collect();

        if selected_matches.is_empty() {
            return get_session(&dst_id)
                .map(|s| s.matches.len() as jint)
                .unwrap_or(0);
        }

        if let Some(dst_session) = get_session(&dst_id) {
            let mut merged_regions = dst_session.regions.clone();
            let mut region_map: HashMap<(u64, u64), u32> = merged_regions
                .iter()
                .enumerate()
                .map(|(idx, r)| ((r.start, r.end), idx as u32))
                .collect();

            let mut merged_matches = dst_session.matches.clone();
            let mut existing_keys: HashSet<(u64, ValueType)> = merged_matches
                .iter()
                .map(|m| (m.address, m.value_type))
                .collect();

            let mut merged_types = dst_session.active_types.clone();

            for m in selected_matches {
                if !existing_keys.insert((m.address, m.value_type)) {
                    continue;
                }
                let new_region_idx =
                    if let Some(src_reg) = src_session.regions.get(m.region_idx as usize) {
                        if let Some(&idx) = region_map.get(&(src_reg.start, src_reg.end)) {
                            idx
                        } else {
                            let new_idx = merged_regions.len() as u32;
                            region_map.insert((src_reg.start, src_reg.end), new_idx);
                            merged_regions.push(src_reg.clone());
                            new_idx
                        }
                    } else {
                        0
                    };
                if !merged_types.contains(&m.value_type) {
                    merged_types.push(m.value_type);
                }
                merged_matches.push(CompactMatch {
                    address: m.address,
                    raw_value: m.raw_value,
                    region_idx: new_region_idx,
                    value_type: m.value_type,
                });
            }

            let merged = ScanSession {
                regions: merged_regions,
                matches: merged_matches,
                active_types: merged_types,
            };
            store_filtered_session(&dst_id, dst_session, merged, false) as jint
        } else {
            let mut dedup_keys: HashSet<(u64, ValueType)> = HashSet::new();
            let mut dedup_matches = Vec::with_capacity(selected_matches.len());
            for m in selected_matches {
                if dedup_keys.insert((m.address, m.value_type)) {
                    dedup_matches.push(m);
                }
            }
            let new_session = ScanSession {
                regions: src_session.regions.clone(),
                matches: dedup_matches,
                active_types: src_session.active_types.clone(),
            };
            store_new_session(&dst_id, new_session) as jint
        }
    });

    res.unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dummy_session(addresses: &[u64]) -> Arc<ScanSession> {
        let matches = addresses
            .iter()
            .map(|&addr| CompactMatch {
                address: addr,
                raw_value: addr,
                region_idx: 0,
                value_type: ValueType::Int,
            })
            .collect();
        Arc::new(ScanSession {
            regions: Vec::new(),
            matches,
            active_types: vec![ValueType::Int],
        })
    }

    #[test]
    fn test_session_history_single_unsaved_and_multi_saved_checkpoints() {
        let mut history = SessionHistory::default();

        // Step 0: initial scan (3 matches)
        let s0 = dummy_session(&[0x1000, 0x2000, 0x3000]);
        assert!(!history.current_is_saved);

        // Refinement 1 (unsaved s0 -> s1)
        history.record_before_refinement(Arc::clone(&s0));
        assert_eq!(history.stack.len(), 1);
        assert!(!history.current_is_saved);

        // Refinement 2 (unsaved s1 -> s2): replaces unsaved s0 with unsaved s1 (1 auto-undo step)
        let s1 = dummy_session(&[0x1000, 0x2000]);
        history.record_before_refinement(Arc::clone(&s1));
        assert_eq!(history.stack.len(), 1);
        assert_eq!(history.stack[0].session.matches.len(), 2);

        // Save s2 as a checkpoint, then refine to s3
        history.mark_current_saved();
        assert!(history.current_is_saved);
        let s2 = dummy_session(&[0x1000, 0x2000]);
        history.record_before_refinement(Arc::clone(&s2));
        assert_eq!(history.stack.len(), 1);
        assert!(history.stack[0].is_saved);
        assert!(!history.current_is_saved);

        // Save s3 as another checkpoint, then refine to s4, then unsaved refine to s5
        history.mark_current_saved();
        let s3 = dummy_session(&[0x1000]);
        history.record_before_refinement(Arc::clone(&s3));
        let s4 = dummy_session(&[0x1000]);
        history.record_before_refinement(Arc::clone(&s4));
        // Stack has: saved s2, saved s3, unsaved s4 -> total 3 undo steps
        assert_eq!(history.stack.len(), 3);

        // Undo 1 -> restores unsaved s4
        let u1 = history.pop_undo().unwrap();
        assert!(!u1.is_saved);
        assert!(!history.current_is_saved);

        // Undo 2 -> restores saved checkpoint s3
        let u2 = history.pop_undo().unwrap();
        assert!(u2.is_saved);
        assert!(history.current_is_saved);

        // Undo 3 -> restores saved checkpoint s2
        let u3 = history.pop_undo().unwrap();
        assert!(u3.is_saved);
        assert!(history.current_is_saved);
        assert_eq!(u3.session.matches.len(), 2);

        // No more entries
        assert!(history.pop_undo().is_none());
    }
}
