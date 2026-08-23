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

//! Lua 5.4 Scripting Engine for HuntMemory (powered by mlua)
//!
//! Provides a sandboxed, high-performance scripting runtime exposing
//! memory editing, vectorized scanning, address freezing, batch writes,
//! dynamic Compose overlay menus, interactive dialogs, and GameGuardian API compatibility.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use mlua::prelude::*;
use serde::{Deserialize, Serialize};

use crate::editor;
use crate::get_sessions;
use crate::kpm;
use crate::logger;
use crate::maps;
use crate::scanner;
use crate::types::*;

static SCRIPT_CANCELLED: AtomicBool = AtomicBool::new(false);

/// Signals the active Lua script execution to interrupt and terminate immediately.
pub fn cancel_script() {
    SCRIPT_CANCELLED.store(true, Ordering::SeqCst);
}

/// Returns whether script cancellation has been requested.
pub fn is_script_cancelled() -> bool {
    SCRIPT_CANCELLED.load(Ordering::Relaxed)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScriptExecutionResult {
    pub success: bool,
    pub output: String,
    pub error: Option<String>,
    pub result: Option<String>,
}

/// Interface for UI interactions (dialogs, toasts, dynamic menus) triggered by Lua scripts.
pub trait ScriptUiCallback: Send + Sync {
    fn show_alert(&self, title: &str, message: &str);
    fn show_toast(&self, message: &str);
    fn show_prompt(&self, title: &str, default_value: &str, keyboard_type: &str) -> Option<String>;
    fn show_choice(&self, title: &str, items: &[String]) -> Option<usize>;
    fn show_multi_choice(
        &self,
        title: &str,
        items: &[String],
        initial: &[bool],
    ) -> Option<Vec<bool>>;
    fn set_dynamic_menu(&self, menu_json: &str);
    fn clear_dynamic_menu(&self);
    fn post_log(&self, line: &str);
    #[allow(dead_code)]
    fn canvas_draw(&self, commands_json: &str);
    fn canvas_draw_binary(&self, bytes: &[u8]);
    fn canvas_clear(&self);
    fn canvas_set_visible(&self, visible: bool);
    fn get_screen_size(&self) -> (u32, u32);
    fn poll_menu_event(&self) -> Option<String>;
}

pub struct NoOpUiCallback;
impl ScriptUiCallback for NoOpUiCallback {
    fn show_alert(&self, _title: &str, _message: &str) {}
    fn show_toast(&self, _message: &str) {}
    fn show_prompt(
        &self,
        _title: &str,
        _default_value: &str,
        _keyboard_type: &str,
    ) -> Option<String> {
        None
    }
    fn show_choice(&self, _title: &str, _items: &[String]) -> Option<usize> {
        None
    }
    fn show_multi_choice(
        &self,
        _title: &str,
        _items: &[String],
        _initial: &[bool],
    ) -> Option<Vec<bool>> {
        None
    }
    fn set_dynamic_menu(&self, _menu_json: &str) {}
    fn clear_dynamic_menu(&self) {}
    fn post_log(&self, _line: &str) {}
    fn canvas_draw(&self, _commands_json: &str) {}
    fn canvas_draw_binary(&self, _bytes: &[u8]) {}
    fn canvas_clear(&self) {}
    fn canvas_set_visible(&self, _visible: bool) {}
    fn get_screen_size(&self) -> (u32, u32) {
        (1080, 2400)
    }
    fn poll_menu_event(&self) -> Option<String> {
        None
    }
}

pub const CANVAS_MODE_REPLACE: u8 = 0;
pub const CANVAS_MODE_APPEND: u8 = 1;

pub const CANVAS_CMD_TEXT: u8 = 1;
pub const CANVAS_CMD_LINE: u8 = 2;
pub const CANVAS_CMD_RECT: u8 = 3;
pub const CANVAS_CMD_CIRCLE: u8 = 4;

/// Helper to convert a Lua color representation (hex string, integer, or table) into a standardized hex string (`#AARRGGBB` or `#RRGGBB`).
#[allow(dead_code)]
pub fn parse_lua_color(val: mlua::Value) -> String {
    match val {
        mlua::Value::String(s) => s.to_string_lossy(),
        mlua::Value::Integer(i) => format!("#{:08X}", i as u32),
        mlua::Value::Number(n) => format!("#{:08X}", n as u64 as u32),
        mlua::Value::Table(t) => {
            if let (Ok(r), Ok(g), Ok(b)) = (t.get::<u32>("r"), t.get::<u32>("g"), t.get::<u32>("b"))
            {
                let a = t.get::<u32>("a").unwrap_or(255);
                format!("#{:02X}{:02X}{:02X}{:02X}", a, r, g, b)
            } else if let (Ok(r), Ok(g), Ok(b)) =
                (t.get::<u32>(1), t.get::<u32>(2), t.get::<u32>(3))
            {
                let a = t.get::<u32>(4).unwrap_or(255);
                format!("#{:02X}{:02X}{:02X}{:02X}", a, r, g, b)
            } else {
                "#FFFFFFFF".to_string()
            }
        }
        _ => "#FFFFFFFF".to_string(),
    }
}

/// Helper to convert a Lua color representation directly into a 32-bit ARGB integer (`0xAARRGGBB`).
pub fn parse_lua_color_to_argb(val: mlua::Value) -> u32 {
    match val {
        mlua::Value::Integer(i) => i as u32,
        mlua::Value::Number(n) => n as u64 as u32,
        mlua::Value::String(s) => {
            let s_lossy = s.to_string_lossy();
            parse_color_str_to_argb(&s_lossy)
        }
        mlua::Value::Table(t) => {
            if let (Ok(r), Ok(g), Ok(b)) = (t.get::<u32>("r"), t.get::<u32>("g"), t.get::<u32>("b"))
            {
                let a = t.get::<u32>("a").unwrap_or(255) & 0xFF;
                (a << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
            } else if let (Ok(r), Ok(g), Ok(b)) =
                (t.get::<u32>(1), t.get::<u32>(2), t.get::<u32>(3))
            {
                let a = t.get::<u32>(4).unwrap_or(255) & 0xFF;
                (a << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
            } else {
                0xFFFFFFFF
            }
        }
        _ => 0xFFFFFFFF,
    }
}

pub fn parse_color_str_to_argb(s: &str) -> u32 {
    let clean = s
        .trim()
        .trim_start_matches('#')
        .trim_start_matches("0x")
        .trim_start_matches("0X");
    match clean.to_ascii_lowercase().as_str() {
        "red" => 0xFFFF0000,
        "green" => 0xFF00FF00,
        "blue" => 0xFF0000FF,
        "yellow" => 0xFFFFFF00,
        "cyan" => 0xFF00FFFF,
        "magenta" => 0xFFFF00FF,
        "white" => 0xFFFFFFFF,
        "black" => 0xFF000000,
        "gray" | "grey" => 0xFF888888,
        "transparent" => 0x00000000,
        _ => {
            if let Ok(val) = u32::from_str_radix(clean, 16) {
                if clean.len() <= 6 {
                    val | 0xFF000000
                } else {
                    val
                }
            } else {
                0xFFFFFFFF
            }
        }
    }
}

pub fn parse_align_str_to_u8(align: &str) -> u8 {
    match align.to_ascii_lowercase().as_str() {
        "center" => 1,
        "right" => 2,
        _ => 0, // left
    }
}

#[inline]
pub fn encode_canvas_text(
    buf: &mut Vec<u8>,
    text: &str,
    x: f32,
    y: f32,
    size: f32,
    color_argb: u32,
    align: u8,
) {
    buf.push(CANVAS_CMD_TEXT);
    buf.extend_from_slice(&color_argb.to_le_bytes());
    buf.extend_from_slice(&x.to_le_bytes());
    buf.extend_from_slice(&y.to_le_bytes());
    buf.extend_from_slice(&size.to_le_bytes());
    buf.push(align);
    let text_bytes = text.as_bytes();
    let text_len = text_bytes.len().min(u16::MAX as usize) as u16;
    buf.extend_from_slice(&text_len.to_le_bytes());
    buf.extend_from_slice(&text_bytes[..text_len as usize]);
}

#[inline]
pub fn encode_canvas_line(
    buf: &mut Vec<u8>,
    x1: f32,
    y1: f32,
    x2: f32,
    y2: f32,
    stroke: f32,
    color_argb: u32,
) {
    buf.push(CANVAS_CMD_LINE);
    buf.extend_from_slice(&color_argb.to_le_bytes());
    buf.extend_from_slice(&x1.to_le_bytes());
    buf.extend_from_slice(&y1.to_le_bytes());
    buf.extend_from_slice(&x2.to_le_bytes());
    buf.extend_from_slice(&y2.to_le_bytes());
    buf.extend_from_slice(&stroke.to_le_bytes());
}

#[inline]
#[allow(clippy::too_many_arguments)]
pub fn encode_canvas_rect(
    buf: &mut Vec<u8>,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    stroke: f32,
    color_argb: u32,
    filled: bool,
) {
    buf.push(CANVAS_CMD_RECT);
    buf.extend_from_slice(&color_argb.to_le_bytes());
    buf.extend_from_slice(&x.to_le_bytes());
    buf.extend_from_slice(&y.to_le_bytes());
    buf.extend_from_slice(&width.to_le_bytes());
    buf.extend_from_slice(&height.to_le_bytes());
    buf.extend_from_slice(&stroke.to_le_bytes());
    buf.push(if filled { 1 } else { 0 });
}

#[inline]
pub fn encode_canvas_circle(
    buf: &mut Vec<u8>,
    cx: f32,
    cy: f32,
    radius: f32,
    stroke: f32,
    color_argb: u32,
    filled: bool,
) {
    buf.push(CANVAS_CMD_CIRCLE);
    buf.extend_from_slice(&color_argb.to_le_bytes());
    buf.extend_from_slice(&cx.to_le_bytes());
    buf.extend_from_slice(&cy.to_le_bytes());
    buf.extend_from_slice(&radius.to_le_bytes());
    buf.extend_from_slice(&stroke.to_le_bytes());
    buf.push(if filled { 1 } else { 0 });
}

/// Helper to convert a Lua region table to `Vec<MemoryRegion>`
fn lua_table_to_regions(table: &mlua::Table) -> mlua::Result<Vec<MemoryRegion>> {
    let mut regions = Vec::new();
    for pair in table.clone().sequence_values::<mlua::Table>() {
        let r_table = pair?;
        let start: u64 = r_table.get("start").unwrap_or(0);
        let end: u64 = r_table.get("end").unwrap_or(0);
        let permissions: String = r_table.get("permissions").unwrap_or_default();
        let offset: u64 = r_table.get("offset").unwrap_or(0);
        let path: String = r_table.get("path").unwrap_or_default();
        if start < end {
            regions.push(MemoryRegion {
                start,
                end,
                permissions,
                offset,
                path,
            });
        }
    }
    Ok(regions)
}

/// Helper to convert a `ValueType` string to GameGuardian numeric type flags.
pub fn value_type_to_gg_flag(vt: &str) -> u32 {
    match vt.to_ascii_lowercase().as_str() {
        "byte" | "i8" | "u8" => 1,
        "short" | "i16" | "u16" | "word" => 2,
        "int" | "i32" | "u32" | "dword" => 4,
        "float" | "f32" => 16,
        "long" | "i64" | "u64" | "qword" => 32,
        "double" | "f64" => 64,
        _ => 4,
    }
}

/// Helper to convert a `ScanSession` into a Lua table of matches
fn session_to_lua_table(
    lua: &Lua,
    session: &ScanSession,
    max_count: usize,
) -> mlua::Result<mlua::Table> {
    let result_table = lua.create_table()?;
    let matches_table = lua.create_table()?;
    let matches = session.to_scan_matches(max_count);

    for (idx, m) in matches.iter().enumerate() {
        let m_table = lua.create_table()?;
        m_table.set("address", m.address)?;
        m_table.set("value", m.value.clone())?;
        m_table.set("value_type", m.value_type.clone())?;
        m_table.set("type", m.value_type.clone())?;
        m_table.set("flags", value_type_to_gg_flag(&m.value_type))?;
        m_table.set("region_start", m.region_start)?;
        m_table.set("region_end", m.region_end)?;
        m_table.set("permissions", m.permissions.clone())?;
        m_table.set("path", m.path.clone())?;
        matches_table.set(idx + 1, m_table)?;
    }

    result_table.set("count", session.matches.len())?;
    result_table.set("matches", matches_table)?;
    Ok(result_table)
}

/// Converts a `serde_json::Value` dynamically into an equivalent `mlua::Value`.
pub fn json_to_lua_value(lua: &Lua, val: serde_json::Value) -> mlua::Result<mlua::Value> {
    match val {
        serde_json::Value::Null => Ok(mlua::Value::Nil),
        serde_json::Value::Bool(b) => Ok(mlua::Value::Boolean(b)),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Ok(mlua::Value::Integer(i))
            } else if let Some(f) = n.as_f64() {
                Ok(mlua::Value::Number(f))
            } else {
                Ok(mlua::Value::Nil)
            }
        }
        serde_json::Value::String(s) => lua.create_string(&s).map(mlua::Value::String),
        serde_json::Value::Array(arr) => {
            let table = lua.create_table()?;
            for (idx, item) in arr.into_iter().enumerate() {
                table.set(idx + 1, json_to_lua_value(lua, item)?)?;
            }
            Ok(mlua::Value::Table(table))
        }
        serde_json::Value::Object(map) => {
            let table = lua.create_table()?;
            for (k, v) in map {
                table.set(k, json_to_lua_value(lua, v)?)?;
            }
            Ok(mlua::Value::Table(table))
        }
    }
}

/// Parses an AoB (Array of Bytes) pattern string with wildcard support (`?`, `??`, `*`).
/// Returns a vector of `(byte, mask)` where mask is `0xFF` for exact matches and `0x00` for wildcards.
pub fn parse_aob_pattern(pattern_str: &str) -> Result<Vec<(u8, u8)>, String> {
    let mut pattern = Vec::new();
    for token in pattern_str.split_whitespace() {
        let clean = token.trim();
        if clean == "?" || clean == "??" || clean == "*" {
            pattern.push((0x00, 0x00));
        } else if let Some(hex) = clean.strip_prefix("0x").or_else(|| clean.strip_prefix("0X")) {
            let b = u8::from_str_radix(hex, 16)
                .map_err(|e| format!("Invalid hex byte '{clean}': {e}"))?;
            pattern.push((b, 0xFF));
        } else {
            let b = u8::from_str_radix(clean, 16)
                .map_err(|e| format!("Invalid hex byte '{clean}': {e}"))?;
            pattern.push((b, 0xFF));
        }
    }
    if pattern.is_empty() {
        return Err("Pattern cannot be empty".to_string());
    }
    Ok(pattern)
}

/// Performs an AoB signature scan across memory regions.
pub fn scan_aob_in_regions(
    pid: u32,
    regions: &[MemoryRegion],
    pattern: &[(u8, u8)],
    max_results: usize,
) -> Vec<u64> {
    let mut matches = Vec::new();
    let pat_len = pattern.len();
    if pat_len == 0 {
        return matches;
    }

    let first_byte = pattern[0].0;
    let first_mask = pattern[0].1;

    let mut chunk_buf = vec![0u8; 1024 * 1024]; // 1MB chunk
    for r in regions {
        let mut cur_addr = r.start;
        while cur_addr < r.end {
            if is_script_cancelled() {
                return matches;
            }
            let read_len = ((r.end - cur_addr) as usize).min(chunk_buf.len());
            if kpm::read_memory(pid, cur_addr, &mut chunk_buf[..read_len]).is_ok() {
                let slice = &chunk_buf[..read_len];
                if slice.len() >= pat_len {
                    for i in 0..=(slice.len() - pat_len) {
                        if (slice[i] & first_mask) == first_byte {
                            let mut matched = true;
                            for (j, &(expected, mask)) in pattern.iter().enumerate().skip(1) {
                                if (slice[i + j] & mask) != expected {
                                    matched = false;
                                    break;
                                }
                            }
                            if matched {
                                matches.push(cur_addr + i as u64);
                                if matches.len() >= max_results {
                                    return matches;
                                }
                            }
                        }
                    }
                }
            }
            // Overlap by pat_len - 1 so matches spanning chunk boundaries are not missed
            let advance = read_len.saturating_sub(pat_len.saturating_sub(1)).max(1);
            cur_addr += advance as u64;
        }
    }
    matches
}

fn b64_encode(data: &[u8]) -> String {
    const B64_CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64_CHARS[((triple >> 18) & 0x3F) as usize] as char);
        out.push(B64_CHARS[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            out.push(B64_CHARS[((triple >> 6) & 0x3F) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(B64_CHARS[(triple & 0x3F) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

fn b64_decode(input: &str) -> Option<Vec<u8>> {
    let clean: Vec<u8> = input.bytes().filter(|b| !b.is_ascii_whitespace()).collect();
    if !clean.len().is_multiple_of(4) {
        return None;
    }
    let val_of = |b: u8| -> Option<u8> {
        match b {
            b'A'..=b'Z' => Some(b - b'A'),
            b'a'..=b'z' => Some(b - b'a' + 26),
            b'0'..=b'9' => Some(b - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            b'=' => Some(0),
            _ => None,
        }
    };
    let mut out = Vec::with_capacity(clean.len() / 4 * 3);
    for chunk in clean.chunks(4) {
        let c0 = val_of(chunk[0])? as u32;
        let c1 = val_of(chunk[1])? as u32;
        let c2 = val_of(chunk[2])? as u32;
        let c3 = val_of(chunk[3])? as u32;
        let triple = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
        out.push(((triple >> 16) & 0xFF) as u8);
        if chunk[2] != b'=' {
            out.push(((triple >> 8) & 0xFF) as u8);
        }
        if chunk[3] != b'=' {
            out.push((triple & 0xFF) as u8);
        }
    }
    Some(out)
}

/// Executes a Lua script with a dedicated sandbox containing `hmem` and `gg` APIs.
pub fn run_script(
    pid: u32,
    script_code: &str,
    ui_callback: Arc<dyn ScriptUiCallback>,
) -> ScriptExecutionResult {
    SCRIPT_CANCELLED.store(false, Ordering::SeqCst);
    let lua = Lua::new();

    let log_buffer = Arc::new(Mutex::new(Vec::<String>::new()));
    let target_pid = Arc::new(Mutex::new(pid));

    if let Err(e) = setup_environment(&lua, log_buffer.clone(), target_pid, ui_callback) {
        return ScriptExecutionResult {
            success: false,
            output: String::new(),
            error: Some(format!("Failed to initialize Lua environment: {e}")),
            result: None,
        };
    }

    // Set cancellation check hook to interrupt runaway scripts
    let triggers = mlua::HookTriggers {
        every_line: true,
        every_nth_instruction: Some(100),
        ..Default::default()
    };
    let _ = lua.set_hook(triggers, |_lua, _debug| {
        if is_script_cancelled() {
            return Err(mlua::Error::runtime("Script execution stopped by user"));
        }
        Ok(mlua::VmState::Continue)
    });

    let eval_res = lua.load(script_code).eval::<mlua::Value>();

    let logs = log_buffer.lock().unwrap().join("\n");

    match eval_res {
        Ok(val) => {
            let res_str = match val {
                mlua::Value::Nil => None,
                mlua::Value::Boolean(b) => Some(b.to_string()),
                mlua::Value::Integer(i) => Some(i.to_string()),
                mlua::Value::Number(n) => Some(n.to_string()),
                mlua::Value::String(s) => Some(s.to_string_lossy()),
                mlua::Value::Table(t) => serde_json::to_string(&t)
                    .ok()
                    .or_else(|| Some("[table]".to_string())),
                other => Some(format!("{other:?}")),
            };

            ScriptExecutionResult {
                success: true,
                output: logs,
                error: None,
                result: res_str,
            }
        }
        Err(e) => ScriptExecutionResult {
            success: false,
            output: logs,
            error: Some(format!("{e}")),
            result: None,
        },
    }
}

fn setup_environment(
    lua: &Lua,
    logs: Arc<Mutex<Vec<String>>>,
    target_pid: Arc<Mutex<u32>>,
    ui: Arc<dyn ScriptUiCallback>,
) -> mlua::Result<()> {
    // 1. Override standard print(...) function
    let print_logs = logs.clone();
    let print_ui = ui.clone();
    let print_fn = lua.create_function(move |_lua, args: mlua::Variadic<mlua::Value>| {
        let mut parts = Vec::with_capacity(args.len());
        for arg in args {
            match arg {
                mlua::Value::Nil => parts.push("nil".to_string()),
                mlua::Value::Boolean(b) => parts.push(b.to_string()),
                mlua::Value::Integer(i) => parts.push(i.to_string()),
                mlua::Value::Number(n) => parts.push(n.to_string()),
                mlua::Value::String(s) => parts.push(s.to_string_lossy()),
                mlua::Value::Table(t) => {
                    parts.push(format!("[table: {:p}]", t.to_pointer()));
                }
                other => parts.push(format!("{other:?}")),
            }
        }
        let line = parts.join("\t");
        logger::debug("LuaScript", &line);
        print_ui.post_log(&line);
        if let Ok(mut l) = print_logs.lock() {
            l.push(line);
        }
        Ok(())
    })?;
    lua.globals().set("print", print_fn)?;

    // 2. Create and populate `hmem` table
    let hmem = lua.create_table()?;

    // Process ID
    let p1 = target_pid.clone();
    hmem.set(
        "get_pid",
        lua.create_function(move |_lua, ()| {
            let pid = *p1.lock().unwrap();
            Ok(pid)
        })?,
    )?;

    let p2 = target_pid.clone();
    hmem.set(
        "set_pid",
        lua.create_function(move |_lua, new_pid: u32| {
            *p2.lock().unwrap() = new_pid;
            Ok(())
        })?,
    )?;

    // Logging & Utility
    let hmem_logs = logs.clone();
    let hmem_ui = ui.clone();
    hmem.set(
        "log",
        lua.create_function(move |_lua, msg: String| {
            logger::debug("LuaScript", &msg);
            hmem_ui.post_log(&msg);
            if let Ok(mut l) = hmem_logs.lock() {
                l.push(msg);
            }
            Ok(())
        })?,
    )?;

    hmem.set(
        "sleep",
        lua.create_function(|_lua, millis: u64| {
            let chunk = 25;
            let mut elapsed = 0;
            while elapsed < millis {
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }
                let to_sleep = (millis - elapsed).min(chunk);
                std::thread::sleep(Duration::from_millis(to_sleep));
                elapsed += to_sleep;
            }
            Ok(())
        })?,
    )?;

    // --- Overlay UI & Dialogs ---

    let ui_alert = ui.clone();
    hmem.set(
        "alert",
        lua.create_function(
            move |_lua, (message, title_opt): (String, Option<String>)| {
                let title = title_opt.unwrap_or_else(|| "Alert".to_string());
                ui_alert.show_alert(&title, &message);
                Ok(())
            },
        )?,
    )?;

    let ui_toast = ui.clone();
    hmem.set(
        "toast",
        lua.create_function(move |_lua, message: String| {
            ui_toast.show_toast(&message);
            Ok(())
        })?,
    )?;

    let ui_prompt = ui.clone();
    hmem.set(
        "prompt",
        lua.create_function(
            move |_lua,
                  (title, default_opt, type_opt): (
                String,
                Option<String>,
                Option<String>,
            )| {
                let def = default_opt.unwrap_or_default();
                let kb = type_opt.unwrap_or_else(|| "qwerty".to_string());
                Ok(ui_prompt.show_prompt(&title, &def, &kb))
            },
        )?,
    )?;

    let ui_choice = ui.clone();
    hmem.set(
        "choice",
        lua.create_function(move |_lua, (title, items_table): (String, mlua::Table)| {
            let mut items = Vec::new();
            for val in items_table.sequence_values::<mlua::Value>() {
                let v = val?;
                match v {
                    mlua::Value::String(s) => items.push(s.to_string_lossy()),
                    mlua::Value::Integer(i) => items.push(i.to_string()),
                    mlua::Value::Number(n) => items.push(n.to_string()),
                    _ => items.push(format!("{v:?}")),
                }
            }
            Ok(ui_choice.show_choice(&title, &items))
        })?,
    )?;

    let ui_multi = ui.clone();
    hmem.set(
        "multi_choice",
        lua.create_function(
            move |lua_ctx,
                  (title, items_table, selected_opt): (
                String,
                mlua::Table,
                Option<mlua::Table>,
            )| {
                let mut items = Vec::new();
                for val in items_table.sequence_values::<mlua::Value>() {
                    let v = val?;
                    match v {
                        mlua::Value::String(s) => items.push(s.to_string_lossy()),
                        mlua::Value::Integer(i) => items.push(i.to_string()),
                        mlua::Value::Number(n) => items.push(n.to_string()),
                        _ => items.push(format!("{v:?}")),
                    }
                }

                let mut initial = Vec::new();
                if let Some(t) = selected_opt {
                    for val in t.sequence_values::<bool>().flatten() {
                        initial.push(val);
                    }
                }

                match ui_multi.show_multi_choice(&title, &items, &initial) {
                    Some(results) => {
                        let res_table = lua_ctx.create_table()?;
                        for (idx, b) in results.into_iter().enumerate() {
                            res_table.set(idx + 1, b)?;
                        }
                        Ok(Some(res_table))
                    }
                    None => Ok(None),
                }
            },
        )?,
    )?;

    let ui_menu = ui.clone();
    hmem.set(
        "create_menu",
        lua.create_function(move |_lua, (title, items_table): (String, mlua::Table)| {
            let mut root_map = serde_json::Map::new();
            root_map.insert("title".to_string(), serde_json::Value::String(title));

            let mut items_json = Vec::new();
            for item in items_table.sequence_values::<mlua::Table>() {
                let t = item?;
                let mut obj = serde_json::Map::new();
                if let Ok(typ) = t.get::<String>("type") {
                    obj.insert("type".to_string(), serde_json::Value::String(typ));
                }
                if let Ok(id) = t.get::<String>("id") {
                    obj.insert("id".to_string(), serde_json::Value::String(id));
                }
                if let Ok(label) = t.get::<String>("label") {
                    obj.insert("label".to_string(), serde_json::Value::String(label));
                }
                if let Ok(color) = t.get::<String>("color") {
                    obj.insert("color".to_string(), serde_json::Value::String(color));
                }
                if let Ok(chk) = t.get::<bool>("checked") {
                    obj.insert("checked".to_string(), serde_json::Value::Bool(chk));
                }
                if let Ok(val) = t.get::<String>("value") {
                    obj.insert("value".to_string(), serde_json::Value::String(val));
                }
                if let Ok(hdr) = t.get::<bool>("header") {
                    obj.insert("header".to_string(), serde_json::Value::Bool(hdr));
                }
                items_json.push(serde_json::Value::Object(obj));
            }
            root_map.insert("items".to_string(), serde_json::Value::Array(items_json));

            let json_str = serde_json::to_string(&root_map).unwrap_or_default();
            ui_menu.set_dynamic_menu(&json_str);
            Ok(())
        })?,
    )?;

    let ui_clr = ui.clone();
    hmem.set(
        "clear_menu",
        lua.create_function(move |_lua, ()| {
            ui_clr.clear_dynamic_menu();
            Ok(())
        })?,
    )?;

    let ui_event = ui.clone();
    hmem.set(
        "poll_event",
        lua.create_function(move |lua_ctx, ()| {
            if let Some(json_str) = ui_event.poll_menu_event()
                && let Ok(val) = serde_json::from_str::<serde_json::Value>(&json_str)
            {
                return json_to_lua_value(lua_ctx, val).map(Some);
            }
            Ok(None)
        })?,
    )?;

    let ui_wait_event = ui.clone();
    hmem.set(
        "wait_event",
        lua.create_function(move |lua_ctx, timeout_opt: Option<u64>| {
            let timeout_ms = timeout_opt.unwrap_or(0);
            let start = std::time::Instant::now();
            loop {
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }
                if let Some(json_str) = ui_wait_event.poll_menu_event()
                    && let Ok(val) = serde_json::from_str::<serde_json::Value>(&json_str)
                {
                    return json_to_lua_value(lua_ctx, val).map(Some);
                }
                if timeout_ms > 0 && start.elapsed().as_millis() >= timeout_ms as u128 {
                    return Ok(None);
                }
                std::thread::sleep(Duration::from_millis(25));
            }
        })?,
    )?;

    // --- Canvas Overlay Subsystem ---
    let canvas_table = lua.create_table()?;

    // canvas.clear() / hmem.canvas_clear()
    let ui_cc = ui.clone();
    let clear_fn = lua.create_function(move |_lua, ()| {
        ui_cc.canvas_clear();
        Ok(())
    })?;
    canvas_table.set("clear", clear_fn.clone())?;
    hmem.set("canvas_clear", clear_fn)?;

    // canvas.set_visible(bool) / canvas.show() / canvas.hide() / canvas.is_visible()
    let ui_vis = ui.clone();
    let visible_state = Arc::new(Mutex::new(true));
    let v1 = visible_state.clone();
    let set_vis_fn = lua.create_function(move |_lua, visible: bool| {
        if let Ok(mut v) = v1.lock() {
            *v = visible;
        }
        ui_vis.canvas_set_visible(visible);
        Ok(())
    })?;
    canvas_table.set("set_visible", set_vis_fn.clone())?;
    hmem.set("canvas_set_visible", set_vis_fn)?;

    let ui_show = ui.clone();
    let v2 = visible_state.clone();
    canvas_table.set(
        "show",
        lua.create_function(move |_lua, ()| {
            if let Ok(mut v) = v2.lock() {
                *v = true;
            }
            ui_show.canvas_set_visible(true);
            Ok(())
        })?,
    )?;

    let ui_hide = ui.clone();
    let v3 = visible_state.clone();
    canvas_table.set(
        "hide",
        lua.create_function(move |_lua, ()| {
            if let Ok(mut v) = v3.lock() {
                *v = false;
            }
            ui_hide.canvas_set_visible(false);
            Ok(())
        })?,
    )?;

    let v4 = visible_state.clone();
    canvas_table.set(
        "is_visible",
        lua.create_function(move |_lua, ()| {
            let vis = v4.lock().map(|v| *v).unwrap_or(true);
            Ok(vis)
        })?,
    )?;

    // canvas.get_screen_size() -> { width = w, height = h }
    let ui_screen = ui.clone();
    let get_screen_fn = lua.create_function(move |lua_ctx, ()| {
        let (w, h) = ui_screen.get_screen_size();
        let res = lua_ctx.create_table()?;
        res.set("width", w)?;
        res.set("height", h)?;
        Ok(res)
    })?;
    canvas_table.set("get_screen_size", get_screen_fn.clone())?;
    hmem.set("get_screen_size", get_screen_fn)?;

    let ui_sw = ui.clone();
    canvas_table.set(
        "get_width",
        lua.create_function(move |_lua, ()| {
            let (w, _) = ui_sw.get_screen_size();
            Ok(w)
        })?,
    )?;

    let ui_sh = ui.clone();
    canvas_table.set(
        "get_height",
        lua.create_function(move |_lua, ()| {
            let (_, h) = ui_sh.get_screen_size();
            Ok(h)
        })?,
    )?;

    // canvas.draw_text(text, x, y, size_opt, color_opt, align_opt)
    let ui_txt = ui.clone();
    let draw_text_fn = lua.create_function(
        move |_lua,
              (text, x, y, size_opt, color_opt, align_opt): (
            String,
            f32,
            f32,
            Option<f32>,
            Option<mlua::Value>,
            Option<String>,
        )| {
            let color = color_opt.map(parse_lua_color_to_argb).unwrap_or(0xFFFFFFFF);
            let size = size_opt.unwrap_or(14.0);
            let align = align_opt.as_deref().map(parse_align_str_to_u8).unwrap_or(0);

            let mut packet = Vec::with_capacity(32 + text.len());
            packet.push(CANVAS_MODE_APPEND);
            packet.extend_from_slice(&1u16.to_le_bytes());
            encode_canvas_text(&mut packet, &text, x, y, size, color, align);

            ui_txt.canvas_draw_binary(&packet);
            Ok(())
        },
    )?;
    canvas_table.set("draw_text", draw_text_fn.clone())?;
    hmem.set("canvas_draw_text", draw_text_fn)?;

    // canvas.draw_line(x1, y1, x2, y2, stroke_opt, color_opt)
    let ui_line = ui.clone();
    let draw_line_fn = lua.create_function(
        move |_lua,
              (x1, y1, x2, y2, stroke_opt, color_opt): (
            f32,
            f32,
            f32,
            f32,
            Option<f32>,
            Option<mlua::Value>,
        )| {
            let color = color_opt.map(parse_lua_color_to_argb).unwrap_or(0xFFFFFFFF);
            let stroke = stroke_opt.unwrap_or(2.0);

            let mut packet = Vec::with_capacity(32);
            packet.push(CANVAS_MODE_APPEND);
            packet.extend_from_slice(&1u16.to_le_bytes());
            encode_canvas_line(&mut packet, x1, y1, x2, y2, stroke, color);

            ui_line.canvas_draw_binary(&packet);
            Ok(())
        },
    )?;
    canvas_table.set("draw_line", draw_line_fn.clone())?;
    hmem.set("canvas_draw_line", draw_line_fn)?;

    // canvas.draw_rect(x, y, w, h, stroke_opt, color_opt, filled_opt)
    let ui_rect = ui.clone();
    let draw_rect_fn = lua.create_function(
        move |_lua,
              (x, y, width, height, stroke_opt, color_opt, filled_opt): (
            f32,
            f32,
            f32,
            f32,
            Option<f32>,
            Option<mlua::Value>,
            Option<bool>,
        )| {
            let color = color_opt.map(parse_lua_color_to_argb).unwrap_or(0xFFFFFFFF);
            let stroke = stroke_opt.unwrap_or(2.0);
            let filled = filled_opt.unwrap_or(false);

            let mut packet = Vec::with_capacity(32);
            packet.push(CANVAS_MODE_APPEND);
            packet.extend_from_slice(&1u16.to_le_bytes());
            encode_canvas_rect(&mut packet, x, y, width, height, stroke, color, filled);

            ui_rect.canvas_draw_binary(&packet);
            Ok(())
        },
    )?;
    canvas_table.set("draw_rect", draw_rect_fn.clone())?;
    hmem.set("canvas_draw_rect", draw_rect_fn)?;

    // canvas.draw_circle(cx, cy, radius, stroke_opt, color_opt, filled_opt)
    let ui_circ = ui.clone();
    let draw_circle_fn = lua.create_function(
        move |_lua,
              (cx, cy, radius, stroke_opt, color_opt, filled_opt): (
            f32,
            f32,
            f32,
            Option<f32>,
            Option<mlua::Value>,
            Option<bool>,
        )| {
            let color = color_opt.map(parse_lua_color_to_argb).unwrap_or(0xFFFFFFFF);
            let stroke = stroke_opt.unwrap_or(2.0);
            let filled = filled_opt.unwrap_or(false);

            let mut packet = Vec::with_capacity(32);
            packet.push(CANVAS_MODE_APPEND);
            packet.extend_from_slice(&1u16.to_le_bytes());
            encode_canvas_circle(&mut packet, cx, cy, radius, stroke, color, filled);

            ui_circ.canvas_draw_binary(&packet);
            Ok(())
        },
    )?;
    canvas_table.set("draw_circle", draw_circle_fn.clone())?;
    hmem.set("canvas_draw_circle", draw_circle_fn)?;

    // canvas.batch_draw(commands_table) / canvas.draw_batch(...)
    let ui_batch = ui.clone();
    let batch_draw_fn = lua.create_function(move |_lua, items_table: mlua::Table| {
        let count_hint = items_table.raw_len().min(u16::MAX as usize) as u16;
        let mut packet = Vec::with_capacity(3 + (count_hint as usize) * 32);
        packet.push(CANVAS_MODE_REPLACE);
        packet.extend_from_slice(&0u16.to_le_bytes());

        let mut actual_count: u16 = 0;
        for item in items_table.sequence_values::<mlua::Table>() {
            let t = item?;
            let typ: String = t.get("type").unwrap_or_else(|_| "text".to_string());

            match typ.to_ascii_lowercase().as_str() {
                "text" => {
                    let text: String = t.get("text").unwrap_or_default();
                    let x: f32 = t.get("x").unwrap_or(0.0);
                    let y: f32 = t.get("y").unwrap_or(0.0);
                    let size: f32 = t.get("size").unwrap_or(14.0);
                    let color: u32 = t
                        .get::<mlua::Value>("color")
                        .map(parse_lua_color_to_argb)
                        .unwrap_or(0xFFFFFFFF);
                    let align: u8 = t
                        .get::<String>("align")
                        .map(|s| parse_align_str_to_u8(&s))
                        .unwrap_or(0);
                    encode_canvas_text(&mut packet, &text, x, y, size, color, align);
                    actual_count = actual_count.saturating_add(1);
                }
                "line" => {
                    let x1: f32 = t.get("x1").unwrap_or(0.0);
                    let y1: f32 = t.get("y1").unwrap_or(0.0);
                    let x2: f32 = t.get("x2").unwrap_or(0.0);
                    let y2: f32 = t.get("y2").unwrap_or(0.0);
                    let stroke: f32 = t.get("stroke").or_else(|_| t.get("width")).unwrap_or(2.0);
                    let color: u32 = t
                        .get::<mlua::Value>("color")
                        .map(parse_lua_color_to_argb)
                        .unwrap_or(0xFFFFFFFF);
                    encode_canvas_line(&mut packet, x1, y1, x2, y2, stroke, color);
                    actual_count = actual_count.saturating_add(1);
                }
                "rect" | "box" => {
                    let x: f32 = t.get("x").unwrap_or(0.0);
                    let y: f32 = t.get("y").unwrap_or(0.0);
                    let width: f32 = t.get("width").or_else(|_| t.get("w")).unwrap_or(0.0);
                    let height: f32 = t.get("height").or_else(|_| t.get("h")).unwrap_or(0.0);
                    let stroke: f32 = t
                        .get("stroke")
                        .or_else(|_| t.get("stroke_width"))
                        .unwrap_or(2.0);
                    let color: u32 = t
                        .get::<mlua::Value>("color")
                        .map(parse_lua_color_to_argb)
                        .unwrap_or(0xFFFFFFFF);
                    let filled: bool = t.get("filled").unwrap_or(false);
                    encode_canvas_rect(&mut packet, x, y, width, height, stroke, color, filled);
                    actual_count = actual_count.saturating_add(1);
                }
                "circle" => {
                    let cx: f32 = t.get("cx").or_else(|_| t.get("x")).unwrap_or(0.0);
                    let cy: f32 = t.get("cy").or_else(|_| t.get("y")).unwrap_or(0.0);
                    let radius: f32 = t.get("radius").or_else(|_| t.get("r")).unwrap_or(10.0);
                    let stroke: f32 = t
                        .get("stroke")
                        .or_else(|_| t.get("stroke_width"))
                        .unwrap_or(2.0);
                    let color: u32 = t
                        .get::<mlua::Value>("color")
                        .map(parse_lua_color_to_argb)
                        .unwrap_or(0xFFFFFFFF);
                    let filled: bool = t.get("filled").unwrap_or(false);
                    encode_canvas_circle(&mut packet, cx, cy, radius, stroke, color, filled);
                    actual_count = actual_count.saturating_add(1);
                }
                _ => {}
            }
        }

        let count_bytes = actual_count.to_le_bytes();
        packet[1] = count_bytes[0];
        packet[2] = count_bytes[1];

        ui_batch.canvas_draw_binary(&packet);
        Ok(())
    })?;
    canvas_table.set("batch_draw", batch_draw_fn.clone())?;
    canvas_table.set("draw_batch", batch_draw_fn.clone())?;
    hmem.set("canvas_batch_draw", batch_draw_fn)?;

    hmem.set("canvas", canvas_table.clone())?;
    lua.globals().set("canvas", canvas_table.clone())?;

    // Direct Memory Reading
    let p_rb = target_pid.clone();
    hmem.set(
        "read_byte",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_rb.lock().unwrap();
            let mut buf = [0u8; 1];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => Ok(Some(buf[0] as i8)),
                Err(_) => Ok(None),
            }
        })?,
    )?;

    let p_rs = target_pid.clone();
    hmem.set(
        "read_short",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_rs.lock().unwrap();
            let mut buf = [0u8; 2];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => Ok(Some(i16::from_le_bytes(buf))),
                Err(_) => Ok(None),
            }
        })?,
    )?;

    let p_ri = target_pid.clone();
    hmem.set(
        "read_int",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_ri.lock().unwrap();
            let mut buf = [0u8; 4];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => Ok(Some(i32::from_le_bytes(buf))),
                Err(_) => Ok(None),
            }
        })?,
    )?;

    let p_rl = target_pid.clone();
    hmem.set(
        "read_long",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_rl.lock().unwrap();
            let mut buf = [0u8; 8];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => Ok(Some(i64::from_le_bytes(buf))),
                Err(_) => Ok(None),
            }
        })?,
    )?;

    let p_rf = target_pid.clone();
    hmem.set(
        "read_float",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_rf.lock().unwrap();
            let mut buf = [0u8; 4];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => Ok(Some(f32::from_le_bytes(buf))),
                Err(_) => Ok(None),
            }
        })?,
    )?;

    let p_rd = target_pid.clone();
    hmem.set(
        "read_double",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_rd.lock().unwrap();
            let mut buf = [0u8; 8];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => Ok(Some(f64::from_le_bytes(buf))),
                Err(_) => Ok(None),
            }
        })?,
    )?;

    let p_rf16 = target_pid.clone();
    hmem.set(
        "read_float16",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_rf16.lock().unwrap();
            let mut buf = [0u8; 2];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => {
                    let u = u16::from_le_bytes(buf);
                    Ok(Some(f16_to_f32(u)))
                }
                Err(_) => Ok(None),
            }
        })?,
    )?;

    let p_rbytes = target_pid.clone();
    hmem.set(
        "read_bytes",
        lua.create_function(move |lua_ctx, (addr, len): (u64, usize)| {
            let pid = *p_rbytes.lock().unwrap();
            let mut buf = vec![0u8; len];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => {
                    let s = lua_ctx.create_string(&buf)?;
                    Ok(Some(s))
                }
                Err(_) => Ok(None),
            }
        })?,
    )?;

    // Read UTF-8 / ASCII / Null-terminated string
    let p_rstr = target_pid.clone();
    hmem.set(
        "read_string",
        lua.create_function(move |_lua, (addr, max_len_opt): (u64, Option<usize>)| {
            let pid = *p_rstr.lock().unwrap();
            let max_len = max_len_opt.unwrap_or(256).min(65536);
            let mut buf = vec![0u8; max_len];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => {
                    let null_pos = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
                    let s = String::from_utf8_lossy(&buf[..null_pos]).to_string();
                    Ok(Some(s))
                }
                Err(_) => Ok(None),
            }
        })?,
    )?;

    // Read direct 64-bit pointer address
    let p_rptr64 = target_pid.clone();
    let read_ptr_fn = lua.create_function(move |_lua, addr: u64| {
        let pid = *p_rptr64.lock().unwrap();
        let mut buf = [0u8; 8];
        match kpm::read_memory(pid, addr, &mut buf) {
            Ok(()) => Ok(Some(u64::from_le_bytes(buf))),
            Err(_) => Ok(None),
        }
    })?;
    hmem.set("read_ptr", read_ptr_fn.clone())?;
    hmem.set("read_ptr64", read_ptr_fn)?;

    let p_read = target_pid.clone();
    hmem.set(
        "read",
        lua.create_function(move |_lua, (addr, type_str): (u64, String)| {
            let pid = *p_read.lock().unwrap();
            if let Ok(vtype) = ValueType::from_str(&type_str) {
                let mut buf = vec![0u8; vtype.size()];
                match kpm::read_memory(pid, addr, &mut buf) {
                    Ok(()) => Ok(Some(bytes_to_value_str(&buf, vtype))),
                    Err(_) => Ok(None),
                }
            } else if let Ok(otype) = ObscuredType::from_str(&type_str) {
                let mut buf = vec![0u8; otype.size()];
                match kpm::read_memory(pid, addr, &mut buf) {
                    Ok(()) => match otype {
                        ObscuredType::ObscuredInt => {
                            let k = u32::from_le_bytes(buf[..4].try_into().unwrap());
                            let v = u32::from_le_bytes(buf[4..8].try_into().unwrap());
                            Ok(Some(((k ^ v) as i32).to_string()))
                        }
                        ObscuredType::ObscuredFloat => {
                            let k = u32::from_le_bytes(buf[..4].try_into().unwrap());
                            let v = u32::from_le_bytes(buf[4..8].try_into().unwrap());
                            Ok(Some(f32::from_bits(k ^ v).to_string()))
                        }
                        ObscuredType::ObscuredDouble => {
                            let k = u64::from_le_bytes(buf[..8].try_into().unwrap());
                            let v = u64::from_le_bytes(buf[8..16].try_into().unwrap());
                            Ok(Some(f64::from_bits(k ^ v).to_string()))
                        }
                        ObscuredType::ObscuredLong => {
                            let k = u64::from_le_bytes(buf[..8].try_into().unwrap());
                            let v = u64::from_le_bytes(buf[8..16].try_into().unwrap());
                            Ok(Some(((k ^ v) as i64).to_string()))
                        }
                    },
                    Err(_) => Ok(None),
                }
            } else if type_str.eq_ignore_ascii_case("big_double")
                || type_str.eq_ignore_ascii_case("bigdouble")
            {
                let mut buf = [0u8; 16];
                match kpm::read_memory(pid, addr, &mut buf) {
                    Ok(()) => {
                        let m = f64::from_le_bytes(buf[..8].try_into().unwrap());
                        let exp64 = i64::from_le_bytes(buf[8..16].try_into().unwrap());
                        let exp32 = i32::from_le_bytes(buf[8..12].try_into().unwrap()) as i64;
                        if exp64 == 0 && exp32 != 0 {
                            Ok(Some(format!("{m}e{exp32}")))
                        } else {
                            Ok(Some(format!("{m}e{exp64}")))
                        }
                    }
                    Err(_) => Ok(None),
                }
            } else {
                Ok(None)
            }
        })?,
    )?;

    // Direct Memory Writing
    let p_wb = target_pid.clone();
    hmem.set(
        "write_byte",
        lua.create_function(move |_lua, (addr, val): (u64, i8)| {
            let pid = *p_wb.lock().unwrap();
            let bytes = [val as u8];
            Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_ws = target_pid.clone();
    hmem.set(
        "write_short",
        lua.create_function(move |_lua, (addr, val): (u64, i16)| {
            let pid = *p_ws.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wi = target_pid.clone();
    hmem.set(
        "write_int",
        lua.create_function(move |_lua, (addr, val): (u64, i32)| {
            let pid = *p_wi.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wl = target_pid.clone();
    hmem.set(
        "write_long",
        lua.create_function(move |_lua, (addr, val): (u64, i64)| {
            let pid = *p_wl.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wf = target_pid.clone();
    hmem.set(
        "write_float",
        lua.create_function(move |_lua, (addr, val): (u64, f32)| {
            let pid = *p_wf.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wd = target_pid.clone();
    hmem.set(
        "write_double",
        lua.create_function(move |_lua, (addr, val): (u64, f64)| {
            let pid = *p_wd.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wf16 = target_pid.clone();
    hmem.set(
        "write_float16",
        lua.create_function(move |_lua, (addr, val): (u64, f32)| {
            let pid = *p_wf16.lock().unwrap();
            let h = f32_to_f16(val);
            let bytes = h.to_le_bytes();
            Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wbytes = target_pid.clone();
    hmem.set(
        "write_bytes",
        lua.create_function(move |_lua, (addr, bytes_str): (u64, mlua::LuaString)| {
            let pid = *p_wbytes.lock().unwrap();
            let raw_bytes = bytes_str.as_bytes();
            Ok(kpm::write_memory(pid, addr, &raw_bytes).is_ok())
        })?,
    )?;

    // Write string
    let p_wstr = target_pid.clone();
    hmem.set(
        "write_string",
        lua.create_function(
            move |_lua, (addr, s, zero_term_opt): (u64, String, Option<bool>)| {
                let pid = *p_wstr.lock().unwrap();
                let mut bytes = s.into_bytes();
                if zero_term_opt.unwrap_or(true) {
                    bytes.push(0);
                }
                Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
            },
        )?,
    )?;

    // Write direct 64-bit pointer address
    let p_wptr64 = target_pid.clone();
    let write_ptr_fn = lua.create_function(move |_lua, (addr, target): (u64, u64)| {
        let pid = *p_wptr64.lock().unwrap();
        let bytes = target.to_le_bytes();
        Ok(kpm::write_memory(pid, addr, &bytes).is_ok())
    })?;
    hmem.set("write_ptr", write_ptr_fn.clone())?;
    hmem.set("write_ptr64", write_ptr_fn)?;

    let p_write = target_pid.clone();
    hmem.set(
        "write",
        lua.create_function(move |_lua, (addr, val, type_str): (u64, String, String)| {
            let pid = *p_write.lock().unwrap();
            if let Ok(vtype) = ValueType::from_str(&type_str) {
                Ok(editor::write_value(pid, addr, &val, vtype).is_ok())
            } else if let Ok(otype) = ObscuredType::from_str(&type_str) {
                Ok(editor::write_obscured(pid, addr, &val, otype).is_ok())
            } else if type_str.eq_ignore_ascii_case("big_double")
                || type_str.eq_ignore_ascii_case("bigdouble")
            {
                Ok(editor::write_big_double(pid, addr, &val).is_ok())
            } else {
                Ok(false)
            }
        })?,
    )?;

    // Obscured & BigDouble Writes
    let p_wobs = target_pid.clone();
    hmem.set(
        "write_obscured",
        lua.create_function(move |_lua, (addr, val, type_str): (u64, String, String)| {
            let pid = *p_wobs.lock().unwrap();
            let Ok(otype) = ObscuredType::from_str(&type_str) else {
                return Ok(false);
            };
            Ok(editor::write_obscured(pid, addr, &val, otype).is_ok())
        })?,
    )?;

    let p_wbig = target_pid.clone();
    hmem.set(
        "write_big_double",
        lua.create_function(move |_lua, (addr, val): (u64, String)| {
            let pid = *p_wbig.lock().unwrap();
            Ok(editor::write_big_double(pid, addr, &val).is_ok())
        })?,
    )?;

    // Batch Writes
    let p_bw = target_pid.clone();
    hmem.set(
        "batch_write",
        lua.create_function(move |_lua, writes_table: mlua::Table| {
            let pid = *p_bw.lock().unwrap();
            let mut writes = Vec::new();
            for item in writes_table.sequence_values::<mlua::Table>() {
                let item = item?;
                let addr: u64 = item.get("address").unwrap_or(0);
                let val: String = match item.get::<mlua::Value>("value")? {
                    mlua::Value::String(s) => s.to_string_lossy(),
                    mlua::Value::Integer(i) => i.to_string(),
                    mlua::Value::Number(n) => n.to_string(),
                    mlua::Value::Boolean(b) => b.to_string(),
                    _ => continue,
                };
                let vtype_str: String = item.get("type").unwrap_or_else(|_| "int".to_string());
                if let Ok(vtype) = ValueType::from_str(&vtype_str) {
                    writes.push((addr, val, vtype));
                }
            }
            match editor::batch_write(pid, &writes) {
                Ok(count) => Ok(count),
                Err(_) => Ok(0),
            }
        })?,
    )?;

    // Freeze Engine
    let p_fz = target_pid.clone();
    hmem.set(
        "freeze",
        lua.create_function(move |_lua, (addr, val, type_str): (u64, String, String)| {
            let pid = *p_fz.lock().unwrap();
            let Ok(vtype) = ValueType::from_str(&type_str) else {
                return Ok(false);
            };
            Ok(editor::get_freeze_engine()
                .freeze(pid, addr, &val, vtype)
                .is_ok())
        })?,
    )?;

    hmem.set(
        "unfreeze",
        lua.create_function(|_lua, addr: u64| Ok(editor::get_freeze_engine().unfreeze(addr)))?,
    )?;

    hmem.set(
        "unfreeze_all",
        lua.create_function(|_lua, ()| Ok(editor::get_freeze_engine().unfreeze_all()))?,
    )?;

    hmem.set(
        "is_frozen",
        lua.create_function(|_lua, addr: u64| Ok(editor::get_freeze_engine().is_frozen(addr)))?,
    )?;

    // Memory Maps
    let p_maps = target_pid.clone();
    hmem.set(
        "get_maps",
        lua.create_function(move |lua_ctx, opts_table: Option<mlua::Table>| {
            let pid = *p_maps.lock().unwrap();
            let mut opts = maps::MapsOptions::default();
            let mut filter_types: Vec<String> = Vec::new();
            let mut custom_filter: Option<String> = None;

            if let Some(t) = opts_table {
                if let Ok(r) = t.get("require_read") {
                    opts.require_read = r;
                }
                if let Ok(w) = t.get("require_write") {
                    opts.require_write = w;
                }
                if let Ok(s) = t.get("include_swapped") {
                    opts.include_swapped = s;
                }
                if let Ok(m) = t.get("min_size") {
                    opts.min_size = m;
                }
                if let Ok(ft) = t.get::<String>("filter_types") {
                    filter_types = ft.split(',').map(|s| s.trim().to_string()).collect();
                }
                if let Ok(c) = t.get::<String>("custom") {
                    custom_filter = Some(c);
                }
            }

            let parsed = maps::parse_maps(pid, &opts).map_err(mlua::Error::runtime)?;
            let filtered = maps::filter_regions(&parsed, &filter_types, custom_filter);

            let res_table = lua_ctx.create_table()?;
            for (idx, r) in filtered.iter().enumerate() {
                let r_table = lua_ctx.create_table()?;
                r_table.set("start", r.start)?;
                r_table.set("end", r.end)?;
                r_table.set("permissions", r.permissions.clone())?;
                r_table.set("offset", r.offset)?;
                r_table.set("path", r.path.clone())?;
                res_table.set(idx + 1, r_table)?;
            }

            Ok(res_table)
        })?,
    )?;

    // Module Base Resolution
    let p_mod = target_pid.clone();
    hmem.set(
        "get_module_base",
        lua.create_function(move |_lua, (mod_name, pid_opt): (String, Option<u32>)| {
            let pid = pid_opt.unwrap_or_else(|| *p_mod.lock().unwrap());
            match maps::get_module_base(pid, &mod_name) {
                Ok(base) if base > 0 => Ok(Some(base)),
                _ => Ok(None),
            }
        })?,
    )?;

    // Multi-Level Pointer Resolution Engine
    let p_ptr = target_pid.clone();
    hmem.set(
        "resolve_pointer",
        lua.create_function(
            move |_lua, (base_val, offsets_table): (mlua::Value, mlua::Table)| {
                let pid = *p_ptr.lock().unwrap();
                let base_addr: u64 = match base_val {
                    mlua::Value::Integer(i) => i as u64,
                    mlua::Value::Number(n) => n as u64,
                    mlua::Value::String(s) => {
                        let s_str = s.to_str()?;
                        if let Some(hex) = s_str
                            .strip_prefix("0x")
                            .or_else(|| s_str.strip_prefix("0X"))
                        {
                            u64::from_str_radix(hex, 16).map_err(mlua::Error::runtime)?
                        } else if s_str.chars().all(|c| c.is_ascii_digit()) {
                            s_str.parse::<u64>().map_err(mlua::Error::runtime)?
                        } else {
                            maps::get_module_base(pid, &s_str).map_err(mlua::Error::runtime)?
                        }
                    }
                    _ => return Err(mlua::Error::runtime("Invalid base address type")),
                };

                let mut offsets = Vec::new();
                for val in offsets_table.sequence_values::<mlua::Value>() {
                    let v = val?;
                    match v {
                        mlua::Value::Integer(i) => offsets.push(i),
                        mlua::Value::Number(n) => offsets.push(n as i64),
                        mlua::Value::String(s) => {
                            let s_str = s.to_str()?;
                            if let Some(hex) = s_str
                                .strip_prefix("0x")
                                .or_else(|| s_str.strip_prefix("0X"))
                            {
                                let u =
                                    i64::from_str_radix(hex, 16).map_err(mlua::Error::runtime)?;
                                offsets.push(u);
                            } else {
                                let i = s_str.parse::<i64>().map_err(mlua::Error::runtime)?;
                                offsets.push(i);
                            }
                        }
                        _ => {}
                    }
                }

                editor::resolve_pointer_chain(pid, base_addr, &offsets)
                    .map_err(mlua::Error::runtime)
            },
        )?,
    )?;

    let p_rptr = target_pid.clone();
    hmem.set(
        "read_pointer",
        lua.create_function(
            move |_lua, (base_val, offsets_table, type_str): (mlua::Value, mlua::Table, String)| {
                let pid = *p_rptr.lock().unwrap();
                let base_addr: u64 = match base_val {
                    mlua::Value::Integer(i) => i as u64,
                    mlua::Value::Number(n) => n as u64,
                    mlua::Value::String(s) => {
                        let s_str = s.to_str()?;
                        if let Some(hex) = s_str
                            .strip_prefix("0x")
                            .or_else(|| s_str.strip_prefix("0X"))
                        {
                            u64::from_str_radix(hex, 16).map_err(mlua::Error::runtime)?
                        } else if s_str.chars().all(|c| c.is_ascii_digit()) {
                            s_str.parse::<u64>().map_err(mlua::Error::runtime)?
                        } else {
                            maps::get_module_base(pid, &s_str).map_err(mlua::Error::runtime)?
                        }
                    }
                    _ => return Err(mlua::Error::runtime("Invalid base address type")),
                };

                let mut offsets = Vec::new();
                for val in offsets_table.sequence_values::<mlua::Value>() {
                    let v = val?;
                    match v {
                        mlua::Value::Integer(i) => offsets.push(i),
                        mlua::Value::Number(n) => offsets.push(n as i64),
                        mlua::Value::String(s) => {
                            let s_str = s.to_str()?;
                            if let Some(hex) = s_str
                                .strip_prefix("0x")
                                .or_else(|| s_str.strip_prefix("0X"))
                            {
                                let u =
                                    i64::from_str_radix(hex, 16).map_err(mlua::Error::runtime)?;
                                offsets.push(u);
                            } else {
                                let i = s_str.parse::<i64>().map_err(mlua::Error::runtime)?;
                                offsets.push(i);
                            }
                        }
                        _ => {}
                    }
                }

                let vtype = ValueType::from_str(&type_str).map_err(mlua::Error::runtime)?;
                editor::read_pointer_value(pid, base_addr, &offsets, vtype)
                    .map_err(mlua::Error::runtime)
            },
        )?,
    )?;

    let p_wptr = target_pid.clone();
    hmem.set(
        "write_pointer",
        lua.create_function(
            move |_lua,
                  (base_val, offsets_table, val_str, type_str): (
                mlua::Value,
                mlua::Table,
                String,
                String,
            )| {
                let pid = *p_wptr.lock().unwrap();
                let base_addr: u64 = match base_val {
                    mlua::Value::Integer(i) => i as u64,
                    mlua::Value::Number(n) => n as u64,
                    mlua::Value::String(s) => {
                        let s_str = s.to_str()?;
                        if let Some(hex) = s_str
                            .strip_prefix("0x")
                            .or_else(|| s_str.strip_prefix("0X"))
                        {
                            u64::from_str_radix(hex, 16).map_err(mlua::Error::runtime)?
                        } else if s_str.chars().all(|c| c.is_ascii_digit()) {
                            s_str.parse::<u64>().map_err(mlua::Error::runtime)?
                        } else {
                            maps::get_module_base(pid, &s_str).map_err(mlua::Error::runtime)?
                        }
                    }
                    _ => return Err(mlua::Error::runtime("Invalid base address type")),
                };

                let mut offsets = Vec::new();
                for val in offsets_table.sequence_values::<mlua::Value>() {
                    let v = val?;
                    match v {
                        mlua::Value::Integer(i) => offsets.push(i),
                        mlua::Value::Number(n) => offsets.push(n as i64),
                        mlua::Value::String(s) => {
                            let s_str = s.to_str()?;
                            if let Some(hex) = s_str
                                .strip_prefix("0x")
                                .or_else(|| s_str.strip_prefix("0X"))
                            {
                                let u =
                                    i64::from_str_radix(hex, 16).map_err(mlua::Error::runtime)?;
                                offsets.push(u);
                            } else {
                                let i = s_str.parse::<i64>().map_err(mlua::Error::runtime)?;
                                offsets.push(i);
                            }
                        }
                        _ => {}
                    }
                }

                let vtype = ValueType::from_str(&type_str).map_err(mlua::Error::runtime)?;
                editor::write_pointer_value(pid, base_addr, &offsets, &val_str, vtype)
                    .map_err(mlua::Error::runtime)?;
                Ok(true)
            },
        )?,
    )?;

    // Custom XOR Memory Helpers
    let p_rxor = target_pid.clone();
    hmem.set(
        "read_xor",
        lua.create_function(move |_lua, (addr, key, type_str): (u64, u64, String)| {
            let pid = *p_rxor.lock().unwrap();
            let vtype = ValueType::from_str(&type_str).map_err(mlua::Error::runtime)?;
            editor::read_xor_value(pid, addr, key, vtype).map_err(mlua::Error::runtime)
        })?,
    )?;

    let p_wxor = target_pid.clone();
    hmem.set(
        "write_xor",
        lua.create_function(
            move |_lua, (addr, val, key, type_str): (u64, String, u64, String)| {
                let pid = *p_wxor.lock().unwrap();
                let vtype = ValueType::from_str(&type_str).map_err(mlua::Error::runtime)?;
                editor::write_xor_value(pid, addr, &val, key, vtype)
                    .map_err(mlua::Error::runtime)?;
                Ok(true)
            },
        )?,
    )?;

    // Obscured Encode/Decode Helpers
    let p_robs_dir = target_pid.clone();
    hmem.set(
        "read_obscured",
        lua.create_function(move |_lua, (addr, type_str): (u64, String)| {
            let pid = *p_robs_dir.lock().unwrap();
            let otype = ObscuredType::from_str(&type_str).map_err(mlua::Error::runtime)?;
            let mut buf = vec![0u8; otype.size()];
            match kpm::read_memory(pid, addr, &mut buf) {
                Ok(()) => match otype {
                    ObscuredType::ObscuredInt => {
                        let k = u32::from_le_bytes(buf[..4].try_into().unwrap());
                        let v = u32::from_le_bytes(buf[4..8].try_into().unwrap());
                        Ok(Some(((k ^ v) as i32).to_string()))
                    }
                    ObscuredType::ObscuredFloat => {
                        let k = u32::from_le_bytes(buf[..4].try_into().unwrap());
                        let v = u32::from_le_bytes(buf[4..8].try_into().unwrap());
                        Ok(Some(f32::from_bits(k ^ v).to_string()))
                    }
                    ObscuredType::ObscuredDouble => {
                        let k = u64::from_le_bytes(buf[..8].try_into().unwrap());
                        let v = u64::from_le_bytes(buf[8..16].try_into().unwrap());
                        Ok(Some(f64::from_bits(k ^ v).to_string()))
                    }
                    ObscuredType::ObscuredLong => {
                        let k = u64::from_le_bytes(buf[..8].try_into().unwrap());
                        let v = u64::from_le_bytes(buf[8..16].try_into().unwrap());
                        Ok(Some(((k ^ v) as i64).to_string()))
                    }
                },
                Err(_) => Ok(None),
            }
        })?,
    )?;

    hmem.set(
        "decode_obscured",
        lua.create_function(|_lua, (key, hidden, type_str): (u64, u64, String)| {
            let otype = ObscuredType::from_str(&type_str).map_err(mlua::Error::runtime)?;
            match otype {
                ObscuredType::ObscuredInt => {
                    let k = key as u32;
                    let v = hidden as u32;
                    Ok(((k ^ v) as i32).to_string())
                }
                ObscuredType::ObscuredFloat => {
                    let k = key as u32;
                    let v = hidden as u32;
                    Ok(f32::from_bits(k ^ v).to_string())
                }
                ObscuredType::ObscuredDouble => Ok(f64::from_bits(key ^ hidden).to_string()),
                ObscuredType::ObscuredLong => Ok(((key ^ hidden) as i64).to_string()),
            }
        })?,
    )?;

    hmem.set(
        "encode_obscured",
        lua.create_function(
            |lua_ctx, (val_str, type_str, key_opt): (String, String, Option<u64>)| {
                let otype = ObscuredType::from_str(&type_str).map_err(mlua::Error::runtime)?;
                let res_table = lua_ctx.create_table()?;
                match otype {
                    ObscuredType::ObscuredInt => {
                        let target: i32 =
                            parse_int_flexible(&val_str).map_err(mlua::Error::runtime)?;
                        let key = key_opt.map(|k| k as u32).unwrap_or(0x12345678);
                        let hidden = (target as u32) ^ key;
                        res_table.set("key", key)?;
                        res_table.set("hidden", hidden)?;
                    }
                    ObscuredType::ObscuredFloat => {
                        let target: f32 = val_str
                            .parse()
                            .map_err(|e| mlua::Error::runtime(format!("{e}")))?;
                        let key = key_opt.map(|k| k as u32).unwrap_or(0x12345678);
                        let hidden = target.to_bits() ^ key;
                        res_table.set("key", key)?;
                        res_table.set("hidden", hidden)?;
                    }
                    ObscuredType::ObscuredDouble => {
                        let target: f64 = val_str
                            .parse()
                            .map_err(|e| mlua::Error::runtime(format!("{e}")))?;
                        let key = key_opt.unwrap_or(0x123456789ABCDEF0);
                        let hidden = target.to_bits() ^ key;
                        res_table.set("key", key)?;
                        res_table.set("hidden", hidden)?;
                    }
                    ObscuredType::ObscuredLong => {
                        let target: i64 =
                            parse_int_flexible(&val_str).map_err(mlua::Error::runtime)?;
                        let key = key_opt.unwrap_or(0x123456789ABCDEF0);
                        let hidden = (target as u64) ^ key;
                        res_table.set("key", key)?;
                        res_table.set("hidden", hidden)?;
                    }
                }
                Ok(res_table)
            },
        )?,
    )?;

    // Scanner / Search Functions
    let p_search = target_pid.clone();
    hmem.set(
        "search",
        lua.create_function(
            move |lua_ctx,
                  (session_id, val_str, type_str, op_str, regions_opt): (
                String,
                String,
                String,
                Option<String>,
                Option<mlua::Table>,
            )| {
                let pid = *p_search.lock().unwrap();
                let vtypes = ValueType::from_multi_str(&type_str).map_err(mlua::Error::runtime)?;
                let op = op_str
                    .as_deref()
                    .and_then(|s| ScanOperator::from_str(s).ok())
                    .unwrap_or(ScanOperator::Equal);

                let regions = match regions_opt {
                    Some(t) => lua_table_to_regions(&t)?,
                    None => {
                        let opts = maps::MapsOptions::default();
                        maps::parse_maps(pid, &opts).map_err(mlua::Error::runtime)?
                    }
                };

                let session = scanner::scan_regions(pid, &regions, &val_str, &vtypes, op)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, session.clone());
                }

                session_to_lua_table(lua_ctx, &session, 100)
            },
        )?,
    )?;

    let p_srange = target_pid.clone();
    hmem.set(
        "search_range",
        lua.create_function(
            move |lua_ctx,
                  (session_id, min_str, max_str, type_str, regions_opt): (
                String,
                String,
                String,
                String,
                Option<mlua::Table>,
            )| {
                let pid = *p_srange.lock().unwrap();
                let vtypes = ValueType::from_multi_str(&type_str).map_err(mlua::Error::runtime)?;
                let regions = match regions_opt {
                    Some(t) => lua_table_to_regions(&t)?,
                    None => {
                        let opts = maps::MapsOptions::default();
                        maps::parse_maps(pid, &opts).map_err(mlua::Error::runtime)?
                    }
                };

                let session = scanner::scan_range(pid, &regions, &min_str, &max_str, &vtypes)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, session.clone());
                }

                session_to_lua_table(lua_ctx, &session, 100)
            },
        )?,
    )?;

    let p_sgroup = target_pid.clone();
    hmem.set(
        "search_group",
        lua.create_function(
            move |lua_ctx,
                  (session_id, group_spec, type_str, regions_opt): (
                String,
                String,
                String,
                Option<mlua::Table>,
            )| {
                let pid = *p_sgroup.lock().unwrap();
                let vtype = ValueType::from_str(&type_str).unwrap_or(ValueType::Int);
                let regions = match regions_opt {
                    Some(t) => lua_table_to_regions(&t)?,
                    None => {
                        let opts = maps::MapsOptions::default();
                        maps::parse_maps(pid, &opts).map_err(mlua::Error::runtime)?
                    }
                };

                let session = scanner::scan_group(pid, &regions, &group_spec, vtype)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, session.clone());
                }

                session_to_lua_table(lua_ctx, &session, 100)
            },
        )?,
    )?;

    let p_sobs = target_pid.clone();
    hmem.set(
        "search_obscured",
        lua.create_function(
            move |lua_ctx,
                  (session_id, val_str, obscured_str, regions_opt): (
                String,
                String,
                String,
                Option<mlua::Table>,
            )| {
                let pid = *p_sobs.lock().unwrap();
                let otype = ObscuredType::from_str(&obscured_str).map_err(mlua::Error::runtime)?;
                let regions = match regions_opt {
                    Some(t) => lua_table_to_regions(&t)?,
                    None => {
                        let opts = maps::MapsOptions::default();
                        maps::parse_maps(pid, &opts).map_err(mlua::Error::runtime)?
                    }
                };

                let session = scanner::scan_obscured(pid, &regions, &val_str, otype)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, session.clone());
                }

                session_to_lua_table(lua_ctx, &session, 100)
            },
        )?,
    )?;

    let p_sbig = target_pid.clone();
    hmem.set(
        "search_big_double",
        lua.create_function(
            move |lua_ctx,
                  (session_id, val_str, regions_opt): (
                String,
                String,
                Option<mlua::Table>,
            )| {
                let pid = *p_sbig.lock().unwrap();
                let regions = match regions_opt {
                    Some(t) => lua_table_to_regions(&t)?,
                    None => {
                        let opts = maps::MapsOptions::default();
                        maps::parse_maps(pid, &opts).map_err(mlua::Error::runtime)?
                    }
                };

                let session = scanner::scan_big_double(pid, &regions, &val_str)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, session.clone());
                }

                session_to_lua_table(lua_ctx, &session, 100)
            },
        )?,
    )?;

    // Refine / Next Scans
    let p_refine = target_pid.clone();
    hmem.set(
        "refine",
        lua.create_function(
            move |lua_ctx, (session_id, val_str, op_str): (String, String, Option<String>)| {
                let pid = *p_refine.lock().unwrap();
                let session = match get_sessions().lock().unwrap().get(&session_id) {
                    Some(s) => s.clone(),
                    None => return Err(mlua::Error::runtime("Session not found")),
                };

                let op = op_str
                    .as_deref()
                    .and_then(|s| ScanOperator::from_str(s).ok())
                    .unwrap_or(ScanOperator::Equal);

                let filtered = scanner::filter_matches(pid, &session, &val_str, op)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, filtered.clone());
                }

                session_to_lua_table(lua_ctx, &filtered, 100)
            },
        )?,
    )?;

    let p_rrange = target_pid.clone();
    hmem.set(
        "refine_range",
        lua.create_function(
            move |lua_ctx, (session_id, min_str, max_str): (String, String, String)| {
                let pid = *p_rrange.lock().unwrap();
                let session = match get_sessions().lock().unwrap().get(&session_id) {
                    Some(s) => s.clone(),
                    None => return Err(mlua::Error::runtime("Session not found")),
                };

                let filtered = scanner::filter_range_matches(pid, &session, &min_str, &max_str)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, filtered.clone());
                }

                session_to_lua_table(lua_ctx, &filtered, 100)
            },
        )?,
    )?;

    let p_robs = target_pid.clone();
    hmem.set(
        "refine_obscured",
        lua.create_function(
            move |lua_ctx, (session_id, val_str, obscured_str): (String, String, String)| {
                let pid = *p_robs.lock().unwrap();
                let session = match get_sessions().lock().unwrap().get(&session_id) {
                    Some(s) => s.clone(),
                    None => return Err(mlua::Error::runtime("Session not found")),
                };

                let otype = ObscuredType::from_str(&obscured_str).map_err(mlua::Error::runtime)?;

                let filtered = scanner::filter_obscured_matches(pid, &session, &val_str, otype)
                    .map_err(mlua::Error::runtime)?;

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, filtered.clone());
                }

                session_to_lua_table(lua_ctx, &filtered, 100)
            },
        )?,
    )?;

    let p_rbig = target_pid.clone();
    hmem.set(
        "refine_big_double",
        lua.create_function(move |lua_ctx, (session_id, val_str): (String, String)| {
            let pid = *p_rbig.lock().unwrap();
            let session = match get_sessions().lock().unwrap().get(&session_id) {
                Some(s) => s.clone(),
                None => return Err(mlua::Error::runtime("Session not found")),
            };

            let filtered = scanner::filter_big_double_matches(pid, &session, &val_str)
                .map_err(mlua::Error::runtime)?;

            if let Ok(mut map) = get_sessions().lock() {
                map.insert(session_id, filtered.clone());
            }

            session_to_lua_table(lua_ctx, &filtered, 100)
        })?,
    )?;

    hmem.set(
        "get_results",
        lua.create_function(
            move |lua_ctx, (session_id, max_count): (String, Option<usize>)| {
                let session = match get_sessions().lock().unwrap().get(&session_id) {
                    Some(s) => s.clone(),
                    None => return Err(mlua::Error::runtime("Session not found")),
                };
                session_to_lua_table(lua_ctx, &session, max_count.unwrap_or(100))
            },
        )?,
    )?;

    hmem.set(
        "clear_session",
        lua.create_function(|_lua, session_id: String| {
            if let Ok(mut map) = get_sessions().lock() {
                map.remove(&session_id);
            }
            Ok(())
        })?,
    )?;

    // AoB Pattern / Signature Scanner
    let p_spat = target_pid.clone();
    hmem.set(
        "search_pattern",
        lua.create_function(
            move |lua_ctx,
                  (session_id, pattern_str, regions_opt): (
                String,
                String,
                Option<mlua::Table>,
            )| {
                let pid = *p_spat.lock().unwrap();
                let pattern = parse_aob_pattern(&pattern_str).map_err(mlua::Error::runtime)?;
                let regions = match regions_opt {
                    Some(t) => lua_table_to_regions(&t)?,
                    None => {
                        let opts = maps::MapsOptions::default();
                        maps::parse_maps(pid, &opts).map_err(mlua::Error::runtime)?
                    }
                };

                let matched_addrs = scan_aob_in_regions(pid, &regions, &pattern, 5000);

                let mut matches = Vec::with_capacity(matched_addrs.len());
                for addr in &matched_addrs {
                    matches.push(CompactMatch {
                        address: *addr,
                        raw_value: 0,
                        region_idx: 0,
                        value_type: ValueType::Byte,
                    });
                }

                let session = ScanSession {
                    regions,
                    matches,
                    active_types: vec![ValueType::Byte],
                };

                if let Ok(mut map) = get_sessions().lock() {
                    map.insert(session_id, session.clone());
                }

                session_to_lua_table(lua_ctx, &session, 100)
            },
        )?,
    )?;

    // JSON parse and serialize
    hmem.set(
        "parse_json",
        lua.create_function(|lua_ctx, json_str: String| {
            let v: serde_json::Value =
                serde_json::from_str(&json_str).map_err(mlua::Error::runtime)?;
            json_to_lua_value(lua_ctx, v)
        })?,
    )?;

    hmem.set(
        "to_json",
        lua.create_function(|_lua, val: mlua::Value| {
            let json_str = match val {
                mlua::Value::Table(t) => serde_json::to_string(&t).map_err(mlua::Error::runtime)?,
                mlua::Value::String(s) => s.to_string_lossy(),
                mlua::Value::Integer(i) => i.to_string(),
                mlua::Value::Number(n) => n.to_string(),
                mlua::Value::Boolean(b) => b.to_string(),
                mlua::Value::Nil => "null".to_string(),
                _ => "null".to_string(),
            };
            Ok(json_str)
        })?,
    )?;

    // Base64 encode and decode
    hmem.set(
        "base64_encode",
        lua.create_function(|_lua, s: String| {
            Ok(b64_encode(s.as_bytes()))
        })?,
    )?;

    hmem.set(
        "base64_decode",
        lua.create_function(|_lua, s: String| {
            match b64_decode(&s) {
                Some(bytes) => Ok(String::from_utf8_lossy(&bytes).to_string()),
                None => Err(mlua::Error::runtime("Invalid base64 string")),
            }
        })?,
    )?;

    // Float / Double Bitcast
    hmem.set(
        "ftd",
        lua.create_function(|_lua, val: f32| {
            Ok(val.to_bits())
        })?,
    )?;

    hmem.set(
        "etd",
        lua.create_function(|_lua, val: f64| {
            Ok((val.to_bits() >> 32) as u32)
        })?,
    )?;

    // Memory Copy & Dump
    let p_cpm = target_pid.clone();
    hmem.set(
        "copy_memory",
        lua.create_function(
            move |_lua, (from_addr, to_addr, size): (u64, u64, usize)| {
                let pid = *p_cpm.lock().unwrap();
                let max_size = size.min(10 * 1024 * 1024);
                let mut buf = vec![0u8; max_size];
                if kpm::read_memory(pid, from_addr, &mut buf).is_err() {
                    return Ok(false);
                }
                match kpm::write_memory(pid, to_addr, &buf) {
                    Ok(()) => Ok(true),
                    Err(_) => Ok(false),
                }
            },
        )?,
    )?;

    let p_dmp = target_pid.clone();
    hmem.set(
        "dump_memory",
        lua.create_function(
            move |_lua, (from_addr, to_addr, file_path): (u64, u64, String)| {
                let pid = *p_dmp.lock().unwrap();
                if to_addr <= from_addr {
                    return Ok(false);
                }
                let size = ((to_addr - from_addr) as usize).min(50 * 1024 * 1024);
                let mut buf = vec![0u8; size];
                if kpm::read_memory(pid, from_addr, &mut buf).is_err() {
                    return Ok(false);
                }
                match std::fs::write(&file_path, &buf) {
                    Ok(_) => Ok(true),
                    Err(_) => Ok(false),
                }
            },
        )?,
    )?;

    hmem.set(
        "is_vpn",
        lua.create_function(|_lua, ()| {
            let is_vpn = std::fs::read_to_string("/proc/net/dev")
                .map(|s| {
                    s.contains("tun") || s.contains("ppp") || s.contains("p2p") || s.contains("tap")
                })
                .unwrap_or(false);
            Ok(is_vpn)
        })?,
    )?;

    lua.globals().set("hmem", hmem.clone())?;

    // 3. Register `gg.*` GameGuardian API compatibility layer
    let gg_chunk = r##"
        gg = {}
        
        -- Type Constants
        gg.TYPE_BYTE = 1
        gg.TYPE_WORD = 2
        gg.TYPE_DWORD = 4
        gg.TYPE_QWORD = 32
        gg.TYPE_FLOAT = 16
        gg.TYPE_DOUBLE = 64
        gg.TYPE_AUTO = 127

        -- Region Constants
        gg.REGION_ALL = 0xFFFFFFFF
        gg.REGION_C_HEAP = 1
        gg.REGION_JAVA_HEAP = 2
        gg.REGION_C_ALLOC = 4
        gg.REGION_C_BSS = 8
        gg.REGION_C_DATA = 16
        gg.REGION_ANONYMOUS = 32
        gg.REGION_STACK = 64
        gg.REGION_ASHMEM = 524288
        gg.REGION_CODE_APP = 16384
        gg.REGION_CODE_SYS = 32768
        gg.REGION_BAD = 131072
        gg.REGION_OTHER = 0x80000000

        -- Sign Constants
        gg.SIGN_EQUAL = 0
        gg.SIGN_NOT_EQUAL = 1
        gg.SIGN_GREATER_OR_EQUAL = 2
        gg.SIGN_LESS_OR_EQUAL = 3
        gg.SIGN_GREATER = 4
        gg.SIGN_LESS = 5

        -- Freeze Constants
        gg.FREEZE_NORMAL = 0
        gg.FREEZE_MAY_INCREASE = 1
        gg.FREEZE_MAY_DECREASE = 2

        -- Prot Constants
        gg.PROT_READ = 1
        gg.PROT_WRITE = 2
        gg.PROT_EXEC = 4

        -- Build & Version Constants
        gg.BUILD = 16142
        gg.VERSION = "101.1"
        gg.VERSION_INT = 10101
        gg.PACKAGE = "com.yervant.huntmem"

        -- Active search configuration
        gg._active_ranges = nil
        gg._saved_list = {}

        gg._type_to_str = function(t)
            if t == gg.TYPE_BYTE or t == "byte" or t == "i8" or t == "u8" then return "byte"
            elseif t == gg.TYPE_WORD or t == "short" or t == "word" or t == "i16" or t == "u16" then return "short"
            elseif t == gg.TYPE_DWORD or t == "int" or t == "dword" or t == "i32" or t == "u32" then return "int"
            elseif t == gg.TYPE_QWORD or t == "long" or t == "qword" or t == "i64" or t == "u64" then return "long"
            elseif t == gg.TYPE_FLOAT or t == "float" or t == "f32" then return "float"
            elseif t == gg.TYPE_DOUBLE or t == "double" or t == "f64" then return "double"
            elseif t == "float16" or t == "f16" or t == "half" then return "float16"
            else return "auto" end
        end

        gg.alert = function(msg, ok)
            hmem.alert(tostring(msg), "Alert")
        end

        gg.toast = function(msg)
            hmem.toast(tostring(msg))
        end

        gg.prompt = function(prompts, defaults, types)
            local res = {}
            if type(prompts) == "table" then
                for i, p in ipairs(prompts) do
                    local def = (defaults and defaults[i]) or ""
                    local t = (types and types[i]) or "qwerty"
                    res[i] = hmem.prompt(tostring(p), tostring(def), tostring(t))
                end
            else
                local val = hmem.prompt(tostring(prompts), tostring(defaults or ""), tostring(types or "qwerty"))
                return { val }
            end
            return res
        end

        gg.choice = function(items, selected, title)
            return hmem.choice(title or "Select Option", items)
        end

        gg.multiChoice = function(items, selected, title)
            return hmem.multi_choice(title or "Select Options", items, selected)
        end

        gg.setRanges = function(ranges)
            gg._active_ranges = ranges
        end

        -- Table Utilities
        table.json = function(json_str)
            return hmem.parse_json(tostring(json_str))
        end
        table.to_json = function(tbl)
            return hmem.to_json(tbl)
        end
        table.dump = table.to_json

        -- String Utilities
        string.split = function(str, sep)
            if not sep or sep == "" then
                local t = {}
                for i = 1, #str do t[i] = str:sub(i, i) end
                return t
            end
            local t = {}
            local pattern = string.format("([^%s]+)", sep)
            for s in string.gmatch(str, pattern) do
                t[#t + 1] = s
            end
            return t
        end

        string.trim = function(str)
            return (tostring(str):gsub("^%s*(.-)%s*$", "%1"))
        end

        string.startsWith = function(str, prefix)
            local s = tostring(str)
            local p = tostring(prefix)
            return string.sub(s, 1, string.len(p)) == p
        end

        string.endsWith = function(str, suffix)
            local s = tostring(str)
            local sf = tostring(suffix)
            return sf == "" or string.sub(s, -string.len(sf)) == sf
        end

        string.replace = function(str, target, replacement)
            local s = tostring(str)
            local f = tostring(target):gsub("([%(%)%.%%%+%-%*%?%[%^%$])", "%%%1")
            local r = tostring(replacement):gsub("%%", "%%%%")
            return (s:gsub(f, r))
        end

        string.base64 = function(str, mode)
            if mode == "de" or mode == "decode" or mode == "dec" then
                return hmem.base64_decode(tostring(str))
            else
                return hmem.base64_encode(tostring(str))
            end
        end
        string.base64_encode = hmem.base64_encode
        string.base64_decode = hmem.base64_decode

        string.bytes = function(tbl, encoding)
            if type(tbl) ~= "table" then return "" end
            local chars = {}
            for _, b in ipairs(tbl) do
                if b and b > 0 then chars[#chars + 1] = string.char(b % 256) end
            end
            return table.concat(chars)
        end

        -- Type Bitcast Conversions
        gg.FTD = function(val)
            return hmem.ftd(tonumber(val) or 0.0)
        end

        gg.ETD = function(val)
            return hmem.etd(tonumber(val) or 0.0)
        end

        gg.WTD = function(addr, offset)
            local w1 = tonumber(hmem.read(addr, "word")) or 0
            local w2 = tonumber(hmem.read(addr + (offset or 2), "word")) or 0
            return ((w2 * 65536) + w1) & 0xFFFFFFFF
        end

        gg.XTD = function(addr, key)
            local d = tonumber(hmem.read(addr, "dword")) or 0
            return (d ~ (key or 0)) & 0xFFFFFFFF
        end

        -- Pointer Calculations
        gg.sumAddress = function(addr, offset, save, flags)
            local target = (tonumber(addr) or 0) + (tonumber(offset) or 0)
            local d_val = tonumber(hmem.read(target, "dword")) or 0
            local f_val = tonumber(hmem.read(target, "float")) or 0.0
            local q_val = tonumber(hmem.read(target, "qword")) or 0
            local res = {
                address = target,
                value = tostring(d_val),
                D = d_val,
                F = f_val,
                Q = q_val,
                flags = flags or gg.TYPE_DWORD
            }
            if save == true then
                gg.addListItems({ res })
            end
            return res
        end

        gg.sumAddressX = function(tbl, idx, offset, flags)
            if type(tbl) ~= "table" then return nil end
            local entry = tbl[idx] or tbl
            local addr = entry.address or entry[1] or 0
            return gg.sumAddress(addr, offset, false, flags)
        end

        gg.copyText = function(text)
            hmem.toast("Copied: " .. tostring(text))
        end

        gg.isVPN = function()
            return hmem.is_vpn()
        end

        -- Enhanced getRangesList supporting address lookup, string regex/substring, or table filters
        gg.getRangesList = function(filter)
            local all_maps = hmem.get_maps()
            if not filter then
                local list = {}
                for i, r in ipairs(all_maps) do
                    list[i] = {
                        start = r.start,
                        ['end'] = r['end'],
                        type = r.permissions,
                        state = "Ca",
                        name = r.path,
                        internalName = r.path
                    }
                end
                return list
            end

            local list = {}
            if type(filter) == "number" then
                for _, r in ipairs(all_maps) do
                    if filter >= r.start and filter < r['end'] then
                        list[#list + 1] = {
                            start = r.start,
                            ['end'] = r['end'],
                            type = r.permissions,
                            state = "Ca",
                            name = r.path,
                            internalName = r.path
                        }
                        break
                    end
                end
            elseif type(filter) == "string" then
                for _, r in ipairs(all_maps) do
                    if r.path and (r.path:find(filter) or filter == "") then
                        list[#list + 1] = {
                            start = r.start,
                            ['end'] = r['end'],
                            type = r.permissions,
                            state = "Ca",
                            name = r.path,
                            internalName = r.path
                        }
                    end
                end
            elseif type(filter) == "table" then
                for _, r in ipairs(all_maps) do
                    local match = true
                    if filter.internalName and (not r.path or not r.path:find(filter.internalName)) then match = false end
                    if filter.name and (not r.path or not r.path:find(filter.name)) then match = false end
                    if filter.type and (not r.permissions or not r.permissions:find(filter.type)) then match = false end
                    if match then
                        list[#list + 1] = {
                            start = r.start,
                            ['end'] = r['end'],
                            type = r.permissions,
                            state = filter.state or "Ca",
                            name = r.path,
                            internalName = r.path
                        }
                    end
                end
            end
            return list
        end

        -- DrawTool / ESP Compatibility Shims
        disDrawAcc = function() end
        closeMTP = function() end

        getWH = function()
            local w, h = hmem.canvas.get_width(), hmem.canvas.get_height()
            return { width = w, height = h, w = w, h = h, [1] = w, [2] = h }
        end

        newPaint = function()
            local paint = {
                _color = "#FFFFFFFF",
                _width = 2.0,
                _style = "stroke",
                _textSize = 14.0,
                _antiAlias = true
            }
            function paint:setColor(c)
                if type(c) == "number" then
                    self._color = string.format("#%08X", c & 0xFFFFFFFF)
                else
                    self._color = tostring(c)
                end
                return self
            end
            function paint:setWidth(w) self._width = tonumber(w) or 2.0; return self end
            function paint:setStyle(s) self._style = tostring(s); return self end
            function paint:setTextSize(sz) self._textSize = tonumber(sz) or 14.0; return self end
            function paint:setAntiAlias(b) self._antiAlias = (b == true); return self end
            return paint
        end

        newView = function()
            local view = {}
            function view:show(onDraw, fps)
                hmem.canvas.set_visible(true)
                if type(onDraw) == "function" then
                    local canvas_proxy = {}
                    function canvas_proxy:drawColor(c) hmem.canvas.clear() end
                    function canvas_proxy:drawLine(x1, y1, x2, y2, p)
                        local color = p and p._color or "#FFFFFFFF"
                        local w = p and p._width or 2.0
                        hmem.canvas.draw_line(x1, y1, x2, y2, color, w)
                    end
                    function canvas_proxy:drawLines(pts, p)
                        if type(pts) == "table" then
                            for i = 1, #pts - 3, 4 do
                                self:drawLine(pts[i], pts[i+1], pts[i+2], pts[i+3], p)
                            end
                        end
                    end
                    function canvas_proxy:drawRect(rect, p)
                        local x1, y1, x2, y2 = rect[1] or rect.x1 or 0, rect[2] or rect.y1 or 0, rect[3] or rect.x2 or 0, rect[4] or rect.y2 or 0
                        local color = p and p._color or "#FFFFFFFF"
                        local w = p and p._width or 2.0
                        local filled = p and (p._style == "填充" or p._style == "fill" or p._style == "描边并填充") or false
                        hmem.canvas.draw_rect(x1, y1, x2 - x1, y2 - y1, color, w, filled)
                    end
                    function canvas_proxy:drawCircle(cx, cy, r, p)
                        local color = p and p._color or "#FFFFFFFF"
                        local w = p and p._width or 2.0
                        local filled = p and (p._style == "填充" or p._style == "fill" or p._style == "描边并填充") or false
                        hmem.canvas.draw_circle(cx, cy, r, color, w, filled)
                    end
                    function canvas_proxy:drawText(text, x, y, p)
                        local color = p and p._color or "#FFFFFFFF"
                        local sz = p and p._textSize or 14.0
                        hmem.canvas.draw_text(text, x, y, color, sz, "left")
                    end
                    function canvas_proxy:save() end
                    function canvas_proxy:restore() end
                    function canvas_proxy:translate(dx, dy) end
                    function canvas_proxy:rotate(deg, px, py) end
                    function canvas_proxy:clipRect(rect) end

                    onDraw(canvas_proxy)
                    hmem.canvas.flush()
                end
            end
            function view:close() hmem.canvas.clear(); hmem.canvas.set_visible(false) end
            function view:invalidate() end
            function view:removeAllView() hmem.canvas.clear() end
            return view
        end

        -- Canvas Compatibility Aliases
        gg.canvas = hmem.canvas
        gg.getScreenSize = function() return hmem.canvas.get_screen_size() end
        gg.getScreenWidth = function() return hmem.canvas.get_width() end
        gg.getScreenHeight = function() return hmem.canvas.get_height() end
    "##;

    lua.load(gg_chunk).exec()?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lua_basic_eval() {
        let res = run_script(1234, "return 2 + 3", Arc::new(NoOpUiCallback));
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("5"));
    }

    #[test]
    fn test_lua_print_capture() {
        let res = run_script(
            1234,
            "print('Hello from Lua')\nreturn true",
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert!(res.output.contains("Hello from Lua"));
        assert_eq!(res.result.as_deref(), Some("true"));
    }

    #[test]
    fn test_lua_hmem_pid() {
        let res = run_script(
            1234,
            "local p = hmem.get_pid()\nhmem.set_pid(5678)\nreturn p .. '->' .. hmem.get_pid()",
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("1234->5678"));
    }

    #[test]
    fn test_lua_syntax_error() {
        let res = run_script(1234, "this is invalid syntax @@@", Arc::new(NoOpUiCallback));
        assert!(!res.success);
        assert!(res.error.is_some());
    }

    #[test]
    fn test_lua_gg_api() {
        let res = run_script(1234, "return gg.TYPE_DWORD", Arc::new(NoOpUiCallback));
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("4"));

        let res_ranges = run_script(
            1234,
            "gg.setRanges(gg.REGION_ANONYMOUS)\nreturn gg.getRanges()",
            Arc::new(NoOpUiCallback),
        );
        assert!(res_ranges.success);
        assert_eq!(res_ranges.result.as_deref(), Some("32"));

        let res_bytes = run_script(
            1234,
            "local t = gg.bytes('ABC')\nreturn t[1] .. ',' .. t[2] .. ',' .. t[3]",
            Arc::new(NoOpUiCallback),
        );
        assert!(res_bytes.success);
        assert_eq!(res_bytes.result.as_deref(), Some("65,66,67"));
    }

    #[test]
    fn test_lua_obscured_encode_decode() {
        let res = run_script(
            1234,
            r#"
            local enc = hmem.encode_obscured("12345", "obscured_int", 0xAABBCCDD)
            local dec = hmem.decode_obscured(enc.key, enc.hidden, "obscured_int")
            return dec
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("12345"));
    }

    #[test]
    fn test_lua_get_module_base_api_exists() {
        let res = run_script(
            1234,
            "return type(hmem.get_module_base) == 'function' and type(gg.getModuleBase) == 'function'",
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("true"));
    }

    #[test]
    fn test_lua_pointer_api_exists() {
        let res = run_script(
            1234,
            "return type(hmem.resolve_pointer) == 'function' and type(gg.resolvePointer) == 'function'",
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("true"));
    }

    #[test]
    fn test_lua_canvas_api_exists() {
        let res = run_script(
            1234,
            r#"
            local ok = type(hmem.canvas) == 'table'
                and type(hmem.canvas.draw_text) == 'function'
                and type(hmem.canvas.draw_line) == 'function'
                and type(hmem.canvas.draw_rect) == 'function'
                and type(hmem.canvas.draw_circle) == 'function'
                and type(hmem.canvas.batch_draw) == 'function'
                and type(hmem.canvas.clear) == 'function'
                and type(gg.canvas) == 'table'
                and type(canvas.draw_line) == 'function'
            return ok
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("true"));
    }

    #[test]
    fn test_lua_canvas_drawing_execution() {
        let res = run_script(
            1234,
            r##"
            canvas.clear()
            canvas.draw_text("Player 1", 100, 150, 16, "#FF0000", "center")
            canvas.draw_line(0, 0, 100, 100, 2, "green")
            canvas.draw_rect(50, 50, 200, 100, 3, { r = 255, g = 255, b = 0, a = 200 }, true)
            canvas.draw_circle(150, 150, 40, 2, 0xFF00FFFF, false)
            canvas.batch_draw({
                { type = "line", x1 = 10, y1 = 10, x2 = 20, y2 = 20, color = "blue", stroke = 2 },
                { type = "rect", x = 30, y = 30, width = 40, height = 40, color = "#FF00FF", filled = false },
                { type = "circle", cx = 50, cy = 50, radius = 15, color = "white", filled = true }
            })
            local sz = canvas.get_screen_size()
            return sz.width .. "x" .. sz.height
            "##,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("1080x2400"));
    }

    #[test]
    fn test_lua_table_json() {
        let res = run_script(
            1234,
            r#"
            local obj = table.json('{"name":"hunt","val":42,"arr":[1,2,3]}')
            local dump = table.to_json(obj)
            return obj.name .. "_" .. obj.val .. "_" .. #obj.arr .. "_" .. (dump:find("hunt") and "ok" or "fail")
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("hunt_42_3_ok"));
    }

    #[test]
    fn test_lua_string_utilities() {
        let res = run_script(
            1234,
            r#"
            local parts = string.split("a;b;c;d", ";")
            local trimmed = string.trim("  hello world  ")
            local sw = string.startsWith("libil2cpp.so", "lib")
            local ew = string.endsWith("libil2cpp.so", ".so")
            local rep = string.replace("123-456-789", "-", "_")
            local b64 = string.base64("HuntMemory", "en")
            local dec = string.base64(b64, "de")
            local bytes_str = string.bytes({72, 101, 108, 108, 111})

            return #parts .. "|" .. trimmed .. "|" .. tostring(sw) .. "|" .. tostring(ew) .. "|" .. rep .. "|" .. dec .. "|" .. bytes_str
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(
            res.result.as_deref(),
            Some("4|hello world|true|true|123_456_789|HuntMemory|Hello")
        );
    }

    #[test]
    fn test_lua_type_bitcasts_and_pointers() {
        let res = run_script(
            1234,
            r#"
            local ftd_val = gg.FTD(1.0)
            local etd_val = gg.ETD(1.0)
            local sum = gg.sumAddress(0x1000, 0x20, false, gg.TYPE_DWORD)
            return tostring(ftd_val) .. "|" .. tostring(etd_val) .. "|" .. tostring(sum.address)
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("1065353216|1072693248|4128"));
    }

    #[test]
    fn test_lua_drawtool_shims() {
        let res = run_script(
            1234,
            r##"
            disDrawAcc()
            local wh = getWH()
            local p = newPaint()
            p:setColor("#FF112233")
            p:setWidth(3.5)
            p:setStyle("填充")
            p:setTextSize(20)

            local v = newView()
            v:show(function(c)
                c:drawColor(0)
                c:drawLine(0, 0, 100, 100, p)
                c:drawRect({10, 20, 30, 40}, p)
                c:drawCircle(50, 50, 25, p)
                c:drawText("ESP", 50, 50, p)
            end, 60)
            v:close()
            closeMTP()

            return tostring(wh.width) .. "x" .. tostring(wh.height) .. "|" .. p._color .. "|" .. tostring(p._width)
            "##,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("1080x2400|#FF112233|3.5"));
    }
}
