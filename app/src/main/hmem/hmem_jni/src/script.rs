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
//! dynamic Compose overlay menus, interactive dialogs, and 2D Canvas rendering.

use std::collections::VecDeque;
use std::fs::OpenOptions;
use std::io::{BufWriter, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use mlua::prelude::*;
use serde::{Deserialize, Serialize};

use crate::editor;
use crate::kpm;
use crate::logger;
use crate::maps;
use crate::scanner;
use crate::types::*;
use crate::{get_session, insert_session, remove_session};

/// Maximum heap memory a single Lua 5.4 VM instance may allocate (64 MiB).
const LUA_MEMORY_LIMIT_BYTES: usize = 64 * 1024 * 1024;
/// Maximum retained lines in the script execution result buffer returned over Binder.
const MAX_LOG_BUFFER_LINES: usize = 500;
/// Maximum retained UTF-8 bytes in the script execution result buffer returned over Binder.
const MAX_LOG_BUFFER_BYTES: usize = 64 * 1024;
/// Maximum bytes a single `hmem.dump_memory` invocation may dump (50 MiB).
const DUMP_MEMORY_MAX_BYTES: usize = 50 * 1024 * 1024;
/// Chunk size for `hmem.dump_memory` reads (4 MiB).
const DUMP_MEMORY_CHUNK_BYTES: usize = 4 * 1024 * 1024;
/// Maximum filename length permitted for sandboxed dump outputs.
const MAX_DUMP_FILENAME_LEN: usize = 128;

static SCRIPT_CANCELLED: AtomicBool = AtomicBool::new(false);
static SANDBOX_DUMP_DIR: RwLock<Option<PathBuf>> = RwLock::new(None);

/// Configures the dedicated sandbox directory where `hmem.dump_memory` may write files.
/// Passing `None` or an empty path disables `hmem.dump_memory` (fail-closed).
pub fn set_sandbox_dump_dir(dir: Option<String>) {
    let parsed = dir
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty() && !s.contains('\0'))
        .map(PathBuf::from);
    let mut guard = SANDBOX_DUMP_DIR.write().unwrap_or_else(|e| e.into_inner());
    *guard = parsed;
}

fn resolve_safe_dump_path(requested_name: &str) -> Result<PathBuf, String> {
    let base_dir = {
        let guard = SANDBOX_DUMP_DIR.read().unwrap_or_else(|e| e.into_inner());
        guard.clone().ok_or_else(|| {
            "dump_memory is disabled: sandbox dump directory is not configured".to_string()
        })?
    };

    let trimmed = requested_name.trim();
    if trimmed.is_empty()
        || trimmed.len() > MAX_DUMP_FILENAME_LEN
        || trimmed.contains('\0')
        || trimmed.contains('/')
        || trimmed.contains('\\')
        || trimmed.contains(':')
        || trimmed.starts_with('.')
        || trimmed.starts_with('-')
    {
        return Err(
            "dump_memory path rejected: only a plain filename without path separators or traversal is allowed"
                .to_string(),
        );
    }

    let mut components = Path::new(trimmed).components();
    match (components.next(), components.next()) {
        (Some(Component::Normal(_)), None) => {}
        _ => {
            return Err(
                "dump_memory path rejected: filename must be a single normal path component"
                    .to_string(),
            );
        }
    }

    std::fs::create_dir_all(&base_dir)
        .map_err(|e| format!("Failed to prepare sandbox dump directory: {e}"))?;
    let canonical_base = std::fs::canonicalize(&base_dir)
        .map_err(|e| format!("Failed to resolve sandbox dump directory: {e}"))?;

    let target = canonical_base.join(trimmed);
    if target.parent() != Some(canonical_base.as_path()) {
        return Err("dump_memory path rejected: resolved path escaped sandbox".to_string());
    }
    if let Ok(meta) = std::fs::symlink_metadata(&target)
        && meta.file_type().is_symlink()
    {
        return Err("dump_memory path rejected: target cannot be a symbolic link".to_string());
    }

    Ok(target)
}

fn open_safe_dump_file(path: &Path) -> std::io::Result<std::fs::File> {
    let mut opts = OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.custom_flags(libc::O_NOFOLLOW).mode(0o644);
    }
    opts.open(path)
}

/// Bounded FIFO buffer preventing unbounded memory growth and Android Binder
/// `TransactionTooLargeException` when returning script output JSON.
struct BoundedLogBuffer {
    lines: VecDeque<String>,
    total_bytes: usize,
    truncated: bool,
}

impl BoundedLogBuffer {
    fn new() -> Self {
        Self {
            lines: VecDeque::new(),
            total_bytes: 0,
            truncated: false,
        }
    }

    fn push(&mut self, mut line: String) {
        if line.len() > MAX_LOG_BUFFER_BYTES {
            let mut end = MAX_LOG_BUFFER_BYTES;
            while end > 0 && !line.is_char_boundary(end) {
                end -= 1;
            }
            line.truncate(end);
            self.truncated = true;
        }
        let line_bytes = line.len().saturating_add(1);
        while self.lines.len() >= MAX_LOG_BUFFER_LINES
            || self.total_bytes.saturating_add(line_bytes) > MAX_LOG_BUFFER_BYTES
        {
            if let Some(removed) = self.lines.pop_front() {
                self.total_bytes = self
                    .total_bytes
                    .saturating_sub(removed.len().saturating_add(1));
                self.truncated = true;
            } else {
                break;
            }
        }
        self.total_bytes = self.total_bytes.saturating_add(line_bytes);
        self.lines.push_back(line);
    }

    fn join(&self) -> String {
        let mut out = String::with_capacity(self.total_bytes.saturating_add(36));
        if self.truncated {
            out.push_str("[... earlier logs truncated ...]\n");
        }
        for (idx, line) in self.lines.iter().enumerate() {
            if idx > 0 {
                out.push('\n');
            }
            out.push_str(line);
        }
        out
    }
}

fn unix_secs_to_utc_parts(secs: i64) -> (i64, u32, u32, u32, u32, u32, u32, u32) {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400) as u32;
    let hour = rem / 3600;
    let min = (rem % 3600) / 60;
    let sec = rem % 60;

    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = if m <= 2 { y + 1 } else { y };

    // Lua wday: Sunday = 1 .. Saturday = 7 (1970-01-01 was Thursday = 5).
    let wday = ((days + 4).rem_euclid(7) + 1) as u32;
    let is_leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    let month_offsets: [u32; 12] = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
    let yday = month_offsets[(m - 1) as usize] + d + u32::from(is_leap && m > 2);

    (year, m, d, hour, min, sec, wday, yday)
}

fn format_utc_date(fmt: &str, secs: i64) -> String {
    let (year, month, day, hour, min, sec, _wday, _yday) = unix_secs_to_utc_parts(secs);
    let mut out = String::with_capacity(fmt.len().saturating_add(16));
    let mut chars = fmt.chars();
    while let Some(c) = chars.next() {
        if c == '%' {
            match chars.next() {
                Some('Y') => out.push_str(&format!("{year:04}")),
                Some('m') => out.push_str(&format!("{month:02}")),
                Some('d') => out.push_str(&format!("{day:02}")),
                Some('H') => out.push_str(&format!("{hour:02}")),
                Some('M') => out.push_str(&format!("{min:02}")),
                Some('S') => out.push_str(&format!("{sec:02}")),
                Some('F') => out.push_str(&format!("{year:04}-{month:02}-{day:02}")),
                Some('T') => out.push_str(&format!("{hour:02}:{min:02}:{sec:02}")),
                Some('c') => out.push_str(&format!(
                    "{year:04}-{month:02}-{day:02} {hour:02}:{min:02}:{sec:02}"
                )),
                Some('%') => out.push('%'),
                Some(other) => {
                    out.push('%');
                    out.push(other);
                }
                None => out.push('%'),
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn create_sandboxed_lua() -> mlua::Result<Lua> {
    // Load strictly non-privileged standard libraries: exclude IO, OS, PACKAGE, DEBUG.
    let allowed_libs = mlua::StdLib::COROUTINE
        | mlua::StdLib::TABLE
        | mlua::StdLib::STRING
        | mlua::StdLib::UTF8
        | mlua::StdLib::MATH;
    let lua = Lua::new_with(allowed_libs, mlua::LuaOptions::default())?;
    let _ = lua.set_memory_limit(LUA_MEMORY_LIMIT_BYTES);

    let globals = lua.globals();
    // Strip filesystem chunk loaders from base library.
    globals.set("dofile", mlua::Value::Nil)?;
    globals.set("loadfile", mlua::Value::Nil)?;

    // Restrict `load()` to text mode ("t") to block crafted binary bytecode execution.
    lua.load(
        r#"
        do
            local _raw_load = load
            if type(_raw_load) == "function" then
                load = function(chunk, chunkname, _mode, ...)
                    if select('#', ...) > 0 then
                        return _raw_load(chunk, chunkname, "t", ...)
                    else
                        return _raw_load(chunk, chunkname, "t")
                    end
                end
            end
        end
        "#,
    )
    .exec()?;

    // Expose a minimal, read-only time-only `os` subset (no execute/remove/rename/getenv/exit).
    let start_instant = Instant::now();
    let safe_os = lua.create_table()?;
    safe_os.set(
        "clock",
        lua.create_function(move |_lua, ()| Ok(start_instant.elapsed().as_secs_f64()))?,
    )?;
    safe_os.set(
        "time",
        lua.create_function(|_lua, ()| {
            let secs = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as i64;
            Ok(secs)
        })?,
    )?;
    safe_os.set(
        "difftime",
        lua.create_function(|_lua, (t2, t1): (f64, f64)| Ok(t2 - t1))?,
    )?;
    safe_os.set(
        "date",
        lua.create_function(
            |lua_ctx, (fmt_opt, time_opt): (Option<String>, Option<i64>)| {
                let secs = time_opt.unwrap_or_else(|| {
                    SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs() as i64
                });
                let raw_fmt = fmt_opt.as_deref().unwrap_or("%Y-%m-%d %H:%M:%S");
                let fmt = raw_fmt.strip_prefix('!').unwrap_or(raw_fmt);
                if fmt == "*t" {
                    let (year, month, day, hour, min, sec, wday, yday) =
                        unix_secs_to_utc_parts(secs);
                    let tbl = lua_ctx.create_table()?;
                    tbl.set("year", year)?;
                    tbl.set("month", month)?;
                    tbl.set("day", day)?;
                    tbl.set("hour", hour)?;
                    tbl.set("min", min)?;
                    tbl.set("sec", sec)?;
                    tbl.set("wday", wday)?;
                    tbl.set("yday", yday)?;
                    tbl.set("isdst", false)?;
                    Ok(mlua::Value::Table(tbl))
                } else {
                    let formatted = format_utc_date(fmt, secs);
                    Ok(mlua::Value::String(lua_ctx.create_string(&formatted)?))
                }
            },
        )?,
    )?;
    globals.set("os", safe_os)?;

    Ok(lua)
}

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

struct CanvasFrameBuffer {
    in_frame: bool,
    packet: Vec<u8>,
    count: u16,
}

impl CanvasFrameBuffer {
    fn new() -> Self {
        Self {
            in_frame: false,
            packet: Vec::new(),
            count: 0,
        }
    }

    fn begin_frame(&mut self) {
        self.in_frame = true;
        self.reset_packet();
    }

    fn reset_packet(&mut self) {
        self.packet.clear();
        self.packet.push(CANVAS_MODE_REPLACE);
        self.packet.extend_from_slice(&0u16.to_le_bytes());
        self.count = 0;
    }

    fn take_flush_packet(&mut self) -> Option<Vec<u8>> {
        if !self.in_frame {
            return None;
        }
        self.in_frame = false;
        let count_bytes = self.count.to_le_bytes();
        if self.packet.len() >= 3 {
            self.packet[1] = count_bytes[0];
            self.packet[2] = count_bytes[1];
        }
        self.count = 0;
        Some(std::mem::take(&mut self.packet))
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
                merged_count: 1,
            });
        }
    }
    Ok(regions)
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
        } else if let Some(hex) = clean
            .strip_prefix("0x")
            .or_else(|| clean.strip_prefix("0X"))
        {
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
///
/// Uses `HMKPM_MAGIC_SCAN_KERNEL` (`HMKPM_SCAN_TYPE_BYTES`) when `ScanEngineMode` is
/// `Kernel` or `Auto`, pattern length `<= HMKPM_MAX_PATTERN_LEN`, and `HMKPM_FEATURE_SCAN_KERNEL`
/// is supported; falls back to userspace chunk scanning otherwise.
pub fn scan_aob_in_regions(
    pid: u32,
    regions: &[MemoryRegion],
    pattern: &[(u8, u8)],
    max_results: usize,
) -> Vec<u64> {
    let mut matches = Vec::new();
    let pat_len = pattern.len();
    if pat_len == 0 || max_results == 0 || regions.is_empty() {
        return matches;
    }

    let engine_mode = scanner::get_scan_engine_mode();
    if (engine_mode == scanner::ScanEngineMode::Kernel as u8
        || engine_mode == scanner::ScanEngineMode::Auto as u8)
        && pat_len <= kpm::HMKPM_MAX_PATTERN_LEN
        && kpm::is_feature_supported(kpm::HMKPM_FEATURE_SCAN_KERNEL)
    {
        let pat_bytes: Vec<u8> = pattern.iter().map(|&(b, _)| b).collect();
        let has_wildcards = pattern.iter().any(|&(_, m)| m != 0xFF);
        let mask_bytes: Option<Vec<u8>> = if has_wildcards {
            Some(pattern.iter().map(|&(_, m)| m).collect())
        } else {
            None
        };
        let op = if has_wildcards {
            kpm::HMKPM_SCAN_OP_MASK
        } else {
            kpm::HMKPM_SCAN_OP_EXACT
        };
        let criteria = kpm::HmkpmScanCriteria {
            exact: kpm::HmkpmScanCriteriaExact { val: 0, mask: 0 },
        };
        let prepared = scanner::prepare_scan_ranges(regions);
        let mut kernel_ok = true;

        'batch_loop: for batch in prepared.chunks(kpm::HMKPM_MAX_SCAN_RANGES) {
            let mut resume_cursor: Option<kpm::HmkpmScanCursor> = None;
            loop {
                if is_script_cancelled() || matches.len() >= max_results {
                    break 'batch_loop;
                }
                let remaining = max_results - matches.len();
                match kpm::scan_kernel_addrs(
                    pid,
                    kpm::HMKPM_SCAN_TYPE_BYTES,
                    op,
                    1,
                    0,
                    criteria,
                    Some(&pat_bytes),
                    mask_bytes.as_deref(),
                    batch,
                    remaining,
                    resume_cursor,
                ) {
                    Ok(outcome) => {
                        matches.extend(outcome.matches);
                        if matches.len() >= max_results {
                            matches.truncate(max_results);
                            break 'batch_loop;
                        }
                        if outcome.status == kpm::HMKPM_SCAN_STATUS_TRUNCATED
                            && (outcome.next_cursor.range_idx as usize) < batch.len()
                        {
                            if let Some(prev) = resume_cursor
                                && (outcome.next_cursor.range_idx, outcome.next_cursor.va)
                                    <= (prev.range_idx, prev.va)
                            {
                                break;
                            }
                            resume_cursor = Some(outcome.next_cursor);
                        } else {
                            break;
                        }
                    }
                    Err(_) => {
                        kernel_ok = false;
                        break 'batch_loop;
                    }
                }
            }
        }

        if kernel_ok {
            return matches;
        }
        matches.clear();
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

/// Executes a Lua script with a dedicated sandbox containing `hmem` and `canvas` APIs.
pub fn run_script(
    pid: u32,
    script_code: &str,
    ui_callback: Arc<dyn ScriptUiCallback>,
) -> ScriptExecutionResult {
    SCRIPT_CANCELLED.store(false, Ordering::SeqCst);
    let lua = match create_sandboxed_lua() {
        Ok(instance) => instance,
        Err(e) => {
            return ScriptExecutionResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to create sandboxed Lua VM: {e}")),
                result: None,
            };
        }
    };

    let log_buffer = Arc::new(Mutex::new(BoundedLogBuffer::new()));
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

    let eval_res = lua
        .load(script_code)
        .set_mode(mlua::chunk::ChunkMode::Text)
        .eval::<mlua::Value>();

    let logs = log_buffer.lock().unwrap_or_else(|e| e.into_inner()).join();

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
    logs: Arc<Mutex<BoundedLogBuffer>>,
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
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }
                let title = title_opt.unwrap_or_else(|| "Alert".to_string());
                ui_alert.show_alert(&title, &message);
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }
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
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }
                let def = default_opt.unwrap_or_default();
                let kb = type_opt.unwrap_or_else(|| "qwerty".to_string());
                let res = ui_prompt.show_prompt(&title, &def, &kb);
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }
                Ok(res)
            },
        )?,
    )?;

    let ui_choice = ui.clone();
    hmem.set(
        "choice",
        lua.create_function(move |_lua, (title, items_table): (String, mlua::Table)| {
            if is_script_cancelled() {
                return Err(mlua::Error::runtime("Script execution stopped by user"));
            }
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
            let res = ui_choice.show_choice(&title, &items);
            if is_script_cancelled() {
                return Err(mlua::Error::runtime("Script execution stopped by user"));
            }
            Ok(res)
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
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }
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

                let choice_res = ui_multi.show_multi_choice(&title, &items, &initial);
                if is_script_cancelled() {
                    return Err(mlua::Error::runtime("Script execution stopped by user"));
                }

                match choice_res {
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
    let canvas_frame_buf = Arc::new(Mutex::new(CanvasFrameBuffer::new()));

    // canvas.begin_frame() / hmem.canvas_begin_frame()
    let fb_begin = canvas_frame_buf.clone();
    let begin_frame_fn = lua.create_function(move |_lua, ()| {
        let mut fb = fb_begin.lock().unwrap_or_else(|e| e.into_inner());
        fb.begin_frame();
        Ok(())
    })?;
    canvas_table.set("begin_frame", begin_frame_fn.clone())?;
    hmem.set("canvas_begin_frame", begin_frame_fn)?;

    // canvas.clear() / hmem.canvas_clear()
    let ui_cc = ui.clone();
    let fb_clear = canvas_frame_buf.clone();
    let clear_fn = lua.create_function(move |_lua, ()| {
        let mut fb = fb_clear.lock().unwrap_or_else(|e| e.into_inner());
        if fb.in_frame {
            fb.reset_packet();
        } else {
            drop(fb);
            ui_cc.canvas_clear();
        }
        Ok(())
    })?;
    canvas_table.set("clear", clear_fn.clone())?;
    hmem.set("canvas_clear", clear_fn)?;

    // canvas.flush() / hmem.canvas_flush()
    let ui_flush = ui.clone();
    let fb_flush = canvas_frame_buf.clone();
    let flush_fn = lua.create_function(move |_lua, ()| {
        let packet_opt = {
            let mut fb = fb_flush.lock().unwrap_or_else(|e| e.into_inner());
            fb.take_flush_packet()
        };
        if let Some(packet) = packet_opt {
            ui_flush.canvas_draw_binary(&packet);
        }
        Ok(())
    })?;
    canvas_table.set("flush", flush_fn.clone())?;
    hmem.set("canvas_flush", flush_fn)?;

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
    let fb_txt = canvas_frame_buf.clone();
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

            let mut fb = fb_txt.lock().unwrap_or_else(|e| e.into_inner());
            if fb.in_frame {
                encode_canvas_text(&mut fb.packet, &text, x, y, size, color, align);
                fb.count = fb.count.saturating_add(1);
                return Ok(());
            }
            drop(fb);

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
    let fb_line = canvas_frame_buf.clone();
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

            let mut fb = fb_line.lock().unwrap_or_else(|e| e.into_inner());
            if fb.in_frame {
                encode_canvas_line(&mut fb.packet, x1, y1, x2, y2, stroke, color);
                fb.count = fb.count.saturating_add(1);
                return Ok(());
            }
            drop(fb);

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
    let fb_rect = canvas_frame_buf.clone();
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

            let mut fb = fb_rect.lock().unwrap_or_else(|e| e.into_inner());
            if fb.in_frame {
                encode_canvas_rect(&mut fb.packet, x, y, width, height, stroke, color, filled);
                fb.count = fb.count.saturating_add(1);
                return Ok(());
            }
            drop(fb);

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
    let fb_circ = canvas_frame_buf.clone();
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

            let mut fb = fb_circ.lock().unwrap_or_else(|e| e.into_inner());
            if fb.in_frame {
                encode_canvas_circle(&mut fb.packet, cx, cy, radius, stroke, color, filled);
                fb.count = fb.count.saturating_add(1);
                return Ok(());
            }
            drop(fb);

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
                        let exp32 = i32::from_le_bytes(buf[8..12].try_into().unwrap());
                        let upper = u32::from_le_bytes(buf[12..16].try_into().unwrap());
                        if upper == 0 && exp32 != 0 {
                            Ok(Some(format!("{m}e{exp32}")))
                        } else {
                            let exp64 = i64::from_le_bytes(buf[8..16].try_into().unwrap());
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
            Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_ws = target_pid.clone();
    hmem.set(
        "write_short",
        lua.create_function(move |_lua, (addr, val): (u64, i16)| {
            let pid = *p_ws.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wi = target_pid.clone();
    hmem.set(
        "write_int",
        lua.create_function(move |_lua, (addr, val): (u64, i32)| {
            let pid = *p_wi.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wl = target_pid.clone();
    hmem.set(
        "write_long",
        lua.create_function(move |_lua, (addr, val): (u64, i64)| {
            let pid = *p_wl.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wf = target_pid.clone();
    hmem.set(
        "write_float",
        lua.create_function(move |_lua, (addr, val): (u64, f32)| {
            let pid = *p_wf.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wd = target_pid.clone();
    hmem.set(
        "write_double",
        lua.create_function(move |_lua, (addr, val): (u64, f64)| {
            let pid = *p_wd.lock().unwrap();
            let bytes = val.to_le_bytes();
            Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wf16 = target_pid.clone();
    hmem.set(
        "write_float16",
        lua.create_function(move |_lua, (addr, val): (u64, f32)| {
            let pid = *p_wf16.lock().unwrap();
            let h = f32_to_f16(val);
            let bytes = h.to_le_bytes();
            Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
        })?,
    )?;

    let p_wbytes = target_pid.clone();
    hmem.set(
        "write_bytes",
        lua.create_function(move |_lua, (addr, bytes_str): (u64, mlua::LuaString)| {
            let pid = *p_wbytes.lock().unwrap();
            let raw_bytes = bytes_str.as_bytes();
            Ok(editor::write_raw_bytes(pid, addr, &raw_bytes).is_ok())
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
                Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
            },
        )?,
    )?;

    // Write direct 64-bit pointer address
    let p_wptr64 = target_pid.clone();
    let write_ptr_fn = lua.create_function(move |_lua, (addr, target): (u64, u64)| {
        let pid = *p_wptr64.lock().unwrap();
        let bytes = target.to_le_bytes();
        Ok(editor::write_raw_bytes(pid, addr, &bytes).is_ok())
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

    let p_unfz = target_pid.clone();
    hmem.set(
        "unfreeze",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_unfz.lock().unwrap();
            Ok(editor::get_freeze_engine().unfreeze(pid, addr))
        })?,
    )?;

    hmem.set(
        "unfreeze_all",
        lua.create_function(|_lua, ()| Ok(editor::get_freeze_engine().unfreeze_all()))?,
    )?;

    let p_isfz = target_pid.clone();
    hmem.set(
        "is_frozen",
        lua.create_function(move |_lua, addr: u64| {
            let pid = *p_isfz.lock().unwrap();
            Ok(editor::get_freeze_engine().is_frozen(pid, addr))
        })?,
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

                let session = insert_session(session_id, session);
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

                let session = insert_session(session_id, session);
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

                let session = insert_session(session_id, session);
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

                let session = insert_session(session_id, session);
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

                let session = insert_session(session_id, session);
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
                let session = get_session(&session_id)
                    .ok_or_else(|| mlua::Error::runtime("Session not found"))?;

                let op = op_str
                    .as_deref()
                    .and_then(|s| ScanOperator::from_str(s).ok())
                    .unwrap_or(ScanOperator::Equal);

                let filtered = scanner::filter_matches(pid, &session, &val_str, op)
                    .map_err(mlua::Error::runtime)?;

                let filtered = insert_session(session_id, filtered);
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
                let session = get_session(&session_id)
                    .ok_or_else(|| mlua::Error::runtime("Session not found"))?;

                let filtered = scanner::filter_range_matches(pid, &session, &min_str, &max_str)
                    .map_err(mlua::Error::runtime)?;

                let filtered = insert_session(session_id, filtered);
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
                let session = get_session(&session_id)
                    .ok_or_else(|| mlua::Error::runtime("Session not found"))?;

                let otype = ObscuredType::from_str(&obscured_str).map_err(mlua::Error::runtime)?;

                let filtered = scanner::filter_obscured_matches(pid, &session, &val_str, otype)
                    .map_err(mlua::Error::runtime)?;

                let filtered = insert_session(session_id, filtered);
                session_to_lua_table(lua_ctx, &filtered, 100)
            },
        )?,
    )?;

    let p_rbig = target_pid.clone();
    hmem.set(
        "refine_big_double",
        lua.create_function(move |lua_ctx, (session_id, val_str): (String, String)| {
            let pid = *p_rbig.lock().unwrap();
            let session = get_session(&session_id)
                .ok_or_else(|| mlua::Error::runtime("Session not found"))?;

            let filtered = scanner::filter_big_double_matches(pid, &session, &val_str)
                .map_err(mlua::Error::runtime)?;

            let filtered = insert_session(session_id, filtered);
            session_to_lua_table(lua_ctx, &filtered, 100)
        })?,
    )?;

    hmem.set(
        "get_results",
        lua.create_function(
            move |lua_ctx, (session_id, max_count): (String, Option<usize>)| {
                let session = get_session(&session_id)
                    .ok_or_else(|| mlua::Error::runtime("Session not found"))?;
                session_to_lua_table(lua_ctx, &session, max_count.unwrap_or(100))
            },
        )?,
    )?;

    hmem.set(
        "clear_session",
        lua.create_function(|_lua, session_id: String| {
            remove_session(&session_id);
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

                let session = insert_session(session_id, session);
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
        lua.create_function(|_lua, s: String| Ok(b64_encode(s.as_bytes())))?,
    )?;

    hmem.set(
        "base64_decode",
        lua.create_function(|_lua, s: String| match b64_decode(&s) {
            Some(bytes) => Ok(String::from_utf8_lossy(&bytes).to_string()),
            None => Err(mlua::Error::runtime("Invalid base64 string")),
        })?,
    )?;

    // Float / Double Bitcast
    hmem.set(
        "ftd",
        lua.create_function(|_lua, val: f32| Ok(val.to_bits()))?,
    )?;

    hmem.set(
        "etd",
        lua.create_function(|_lua, val: f64| Ok((val.to_bits() >> 32) as u32))?,
    )?;

    // Memory Copy & Dump
    let p_cpm = target_pid.clone();
    hmem.set(
        "copy_memory",
        lua.create_function(move |_lua, (from_addr, to_addr, size): (u64, u64, usize)| {
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
        })?,
    )?;

    let p_dmp = target_pid.clone();
    let ui_dmp = ui.clone();
    hmem.set(
        "dump_memory",
        lua.create_function(
            move |_lua, (from_addr, to_addr, file_name): (u64, u64, String)| {
                let target_path = match resolve_safe_dump_path(&file_name) {
                    Ok(p) => p,
                    Err(msg) => {
                        ui_dmp.post_log(&format!("[Sandbox] {msg}"));
                        return Err(mlua::Error::runtime(msg));
                    }
                };
                if to_addr <= from_addr {
                    return Ok(false);
                }
                let pid = *p_dmp.lock().unwrap_or_else(|e| e.into_inner());
                let total_size = ((to_addr - from_addr) as usize).min(DUMP_MEMORY_MAX_BYTES);
                let file = match open_safe_dump_file(&target_path) {
                    Ok(f) => f,
                    Err(e) => {
                        ui_dmp.post_log(&format!("[Sandbox] dump_memory open failed: {e}"));
                        return Ok(false);
                    }
                };
                let mut writer = BufWriter::new(file);
                let chunk_cap = total_size.min(DUMP_MEMORY_CHUNK_BYTES);
                let mut buf = vec![0u8; chunk_cap];
                let mut offset = 0usize;
                while offset < total_size {
                    if is_script_cancelled() {
                        return Err(mlua::Error::runtime("Script execution stopped by user"));
                    }
                    let read_len = (total_size - offset).min(buf.len());
                    let cur_addr = from_addr.saturating_add(offset as u64);
                    if kpm::read_memory(pid, cur_addr, &mut buf[..read_len]).is_err() {
                        return Ok(false);
                    }
                    if writer.write_all(&buf[..read_len]).is_err() {
                        return Ok(false);
                    }
                    offset += read_len;
                }
                Ok(writer.flush().is_ok())
            },
        )?,
    )?;

    // Hardware Page-Table Region Enumeration (HMKPM v3.0.0 ENUM_REGIONS)
    let p_enum = target_pid.clone();
    hmem.set(
        "enum_regions",
        lua.create_function(
            move |lua_ctx, (writable_only, exec_only): (Option<bool>, Option<bool>)| {
                let pid = *p_enum.lock().unwrap_or_else(|e| e.into_inner());
                let mut flags = 0u32;
                if writable_only.unwrap_or(false) {
                    flags |= kpm::HMKPM_ENUM_FLAG_WRITABLE_ONLY;
                }
                if exec_only.unwrap_or(false) {
                    flags |= kpm::HMKPM_ENUM_FLAG_EXEC_ONLY;
                }
                let entries = kpm::enum_regions(pid, flags, 0, 0).map_err(mlua::Error::runtime)?;
                let out = lua_ctx.create_table()?;
                for (idx, e) in entries.iter().enumerate() {
                    let t = lua_ctx.create_table()?;
                    let r = (e.flags & kpm::HMKPM_REGION_FLAG_READ) != 0;
                    let w = (e.flags & kpm::HMKPM_REGION_FLAG_WRITE) != 0;
                    let x = (e.flags & kpm::HMKPM_REGION_FLAG_EXEC) != 0;
                    let has_block = (e.flags & kpm::HMKPM_REGION_FLAG_HAS_BLOCK) != 0;
                    let perms = format!(
                        "{}{}{}p",
                        if r { 'r' } else { '-' },
                        if w { 'w' } else { '-' },
                        if x { 'x' } else { '-' }
                    );
                    t.set("start", e.start_va)?;
                    t.set("end", e.start_va.saturating_add(e.size))?;
                    t.set("size", e.size)?;
                    t.set("readable", r)?;
                    t.set("writable", w)?;
                    t.set("executable", x)?;
                    t.set("has_block", has_block)?;
                    t.set("permissions", perms)?;
                    out.set(idx + 1, t)?;
                }
                Ok(out)
            },
        )?,
    )?;

    // HMKPM v3.0.0 Runtime Telemetry & Feature Discovery
    hmem.set(
        "get_kpm_info",
        lua.create_function(|lua_ctx, ()| {
            let info = kpm::probe_version().map_err(mlua::Error::runtime)?;
            let t = lua_ctx.create_table()?;
            t.set("version", info.version_string())?;
            t.set("version_code", info.version_code)?;
            t.set("is_lockless", info.is_lockless_mode())?;
            t.set("lock_source", info.lock_source_str())?;
            t.set("mmap_lock_offset", info.mmap_lock_offset)?;
            t.set("page_size", info.page_size)?;
            t.set("kver", info.kver_string())?;
            t.set("va_bits", info.va_bits)?;
            t.set("tbi0", info.tbi0 != 0)?;
            t.set("sw_pan_mode", info.sw_pan_mode_str())?;
            t.set("fsnotify_mode", info.fsnotify_mode_str())?;
            t.set("features", info.features)?;
            Ok(t)
        })?,
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

    // 3. Register Lua table/string helpers and 2D Canvas compatibility shims
    let lua_helpers_chunk = r##"
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

        -- 2D Canvas Compatibility Shims
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
                        hmem.canvas.draw_line(x1, y1, x2, y2, w, color)
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
                        local style = p and string.lower(tostring(p._style or "")) or ""
                        local filled = (style == "fill" or style == "fill_and_stroke" or style == "both")
                        hmem.canvas.draw_rect(x1, y1, x2 - x1, y2 - y1, w, color, filled)
                    end
                    function canvas_proxy:drawCircle(cx, cy, r, p)
                        local color = p and p._color or "#FFFFFFFF"
                        local w = p and p._width or 2.0
                        local style = p and string.lower(tostring(p._style or "")) or ""
                        local filled = (style == "fill" or style == "fill_and_stroke" or style == "both")
                        hmem.canvas.draw_circle(cx, cy, r, w, color, filled)
                    end
                    function canvas_proxy:drawText(text, x, y, p)
                        local color = p and p._color or "#FFFFFFFF"
                        local sz = p and p._textSize or 14.0
                        hmem.canvas.draw_text(text, x, y, sz, color, "left")
                    end
                    function canvas_proxy:save() end
                    function canvas_proxy:restore() end
                    function canvas_proxy:translate(dx, dy) end
                    function canvas_proxy:rotate(deg, px, py) end
                    function canvas_proxy:clipRect(rect) end

                    hmem.canvas.begin_frame()
                    onDraw(canvas_proxy)
                    hmem.canvas.flush()
                end
            end
            function view:close() hmem.canvas.clear(); hmem.canvas.set_visible(false) end
            function view:invalidate() end
            function view:removeAllView() hmem.canvas.clear() end
            return view
        end
    "##;

    lua.load(lua_helpers_chunk).exec()?;

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
            "return type(hmem.get_module_base) == 'function'",
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("true"));
    }

    #[test]
    fn test_lua_pointer_api_exists() {
        let res = run_script(
            1234,
            "return type(hmem.resolve_pointer) == 'function'",
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
            canvas.draw_text("Node 1", 100, 150, 16, "#FF0000", "center")
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
            local sw = string.startsWith("libnative.so", "lib")
            local ew = string.endsWith("libnative.so", ".so")
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
    fn test_lua_type_bitcasts() {
        let res = run_script(
            1234,
            r#"
            local ftd_val = hmem.ftd(1.0)
            local etd_val = hmem.etd(1.0)
            return tostring(ftd_val) .. "|" .. tostring(etd_val)
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success);
        assert_eq!(res.result.as_deref(), Some("1065353216|1072693248"));
    }

    #[test]
    fn test_lua_canvas_shims() {
        let res = run_script(
            1234,
            r##"
            disDrawAcc()
            local wh = getWH()
            local p = newPaint()
            p:setColor("#FF112233")
            p:setWidth(3.5)
            p:setStyle("fill")
            p:setTextSize(20)

            local v = newView()
            v:show(function(c)
                c:drawColor(0)
                c:drawLine(0, 0, 100, 100, p)
                c:drawRect({10, 20, 30, 40}, p)
                c:drawCircle(50, 50, 25, p)
                c:drawText("Overlay", 50, 50, p)
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

    static TEST_GLOBAL_STATE_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn test_lua_sandbox_blocks_dangerous_stdlib() {
        let _guard = TEST_GLOBAL_STATE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let res = run_script(
            1234,
            r#"
            local blocked = {
                io = io,
                package = package,
                require = require,
                debug = debug,
                dofile = dofile,
                loadfile = loadfile,
                os_execute = os and os.execute,
                os_remove = os and os.remove,
                os_rename = os and os.rename,
                os_getenv = os and os.getenv,
                os_exit = os and os.exit,
                os_tmpname = os and os.tmpname,
                os_setlocale = os and os.setlocale,
            }
            for k, v in pairs(blocked) do
                if v ~= nil then
                    return "EXPOSED:" .. k
                end
            end
            return "SANDBOX_OK"
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success, "unexpected error: {:?}", res.error);
        assert_eq!(res.result.as_deref(), Some("SANDBOX_OK"));
    }

    #[test]
    fn test_lua_sandbox_allows_safe_os_subset() {
        let _guard = TEST_GLOBAL_STATE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let res = run_script(
            1234,
            r#"
            local c = os.clock()
            local t = os.time()
            local d = os.difftime(200, 150)
            local epoch = os.date("!%Y-%m-%d %H:%M:%S", 0)
            local known = os.date("!%F %T", 1700000000)
            local tbl = os.date("!*t", 0)
            assert(type(c) == "number" and c >= 0)
            assert(type(t) == "number" and t > 0)
            assert(d == 50)
            assert(tbl.year == 1970 and tbl.month == 1 and tbl.day == 1 and tbl.wday == 5 and tbl.yday == 1)
            return epoch .. "|" .. known
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success, "unexpected error: {:?}", res.error);
        assert_eq!(
            res.result.as_deref(),
            Some("1970-01-01 00:00:00|2023-11-14 22:13:20")
        );
    }

    #[test]
    fn test_lua_sandbox_blocks_binary_bytecode_load() {
        let _guard = TEST_GLOBAL_STATE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let res = run_script(
            1234,
            r#"
            local ok_fn = load("return 40 + 2")
            assert(ok_fn() == 42)
            local bad_fn, err = load("\27LuaT\0\0\0", "evil", "b")
            assert(bad_fn == nil)
            assert(type(err) == "string")
            return "TEXT_ONLY_OK"
            "#,
            Arc::new(NoOpUiCallback),
        );
        assert!(res.success, "unexpected error: {:?}", res.error);
        assert_eq!(res.result.as_deref(), Some("TEXT_ONLY_OK"));
    }

    #[test]
    fn test_lua_sandbox_dump_memory_path_validation() {
        let _guard = TEST_GLOBAL_STATE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());

        // Fail-closed when unconfigured
        set_sandbox_dump_dir(None);
        assert!(resolve_safe_dump_path("dump.bin").is_err());

        let temp_dir =
            std::env::temp_dir().join(format!("hmem_sandbox_dump_test_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&temp_dir);
        set_sandbox_dump_dir(Some(temp_dir.to_string_lossy().into_owned()));

        // Reject traversal, absolute paths, separators, hidden/dash names, NUL bytes
        assert!(resolve_safe_dump_path("").is_err());
        assert!(resolve_safe_dump_path("../escape.bin").is_err());
        assert!(resolve_safe_dump_path("sub/file.bin").is_err());
        assert!(resolve_safe_dump_path("sub\\file.bin").is_err());
        assert!(resolve_safe_dump_path("/data/local/tmp/evil.sh").is_err());
        assert!(resolve_safe_dump_path(".hidden").is_err());
        assert!(resolve_safe_dump_path("-flag").is_err());
        assert!(resolve_safe_dump_path("a\0b.bin").is_err());

        // Valid plain filename resolves strictly inside sandbox directory
        let resolved = resolve_safe_dump_path("region_dump.bin").expect("valid filename");
        let canonical_base = std::fs::canonicalize(&temp_dir).expect("canonical base");
        assert_eq!(resolved.parent(), Some(canonical_base.as_path()));
        assert_eq!(
            resolved.file_name().and_then(|s| s.to_str()),
            Some("region_dump.bin")
        );

        // Lua invocation with traversal path must fail with sandbox error
        let lua_res = run_script(
            1234,
            r#"return hmem.dump_memory(0x1000, 0x1010, "/data/local/tmp/evil.bin")"#,
            Arc::new(NoOpUiCallback),
        );
        assert!(!lua_res.success);
        assert!(
            lua_res
                .error
                .as_deref()
                .unwrap_or("")
                .contains("dump_memory path rejected")
        );

        set_sandbox_dump_dir(None);
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn test_lua_bounded_log_buffer() {
        let _guard = TEST_GLOBAL_STATE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let streamed_count = Arc::new(std::sync::atomic::AtomicUsize::new(0));

        struct CountingCallback(Arc<std::sync::atomic::AtomicUsize>);
        impl ScriptUiCallback for CountingCallback {
            fn show_alert(&self, _t: &str, _m: &str) {}
            fn show_toast(&self, _m: &str) {}
            fn show_prompt(&self, _t: &str, _d: &str, _k: &str) -> Option<String> {
                None
            }
            fn show_choice(&self, _t: &str, _i: &[String]) -> Option<usize> {
                None
            }
            fn show_multi_choice(
                &self,
                _t: &str,
                _i: &[String],
                _init: &[bool],
            ) -> Option<Vec<bool>> {
                None
            }
            fn set_dynamic_menu(&self, _j: &str) {}
            fn clear_dynamic_menu(&self) {}
            fn post_log(&self, _line: &str) {
                self.0.fetch_add(1, Ordering::Relaxed);
            }
            fn canvas_draw_binary(&self, _b: &[u8]) {}
            fn canvas_clear(&self) {}
            fn canvas_set_visible(&self, _v: bool) {}
            fn get_screen_size(&self) -> (u32, u32) {
                (1080, 2400)
            }
            fn poll_menu_event(&self) -> Option<String> {
                None
            }
        }

        let res = run_script(
            1234,
            r#"
            for i = 1, 1500 do
                print("log_line_" .. i .. "_" .. string.rep("A", 80))
            end
            return "DONE"
            "#,
            Arc::new(CountingCallback(streamed_count.clone())),
        );
        assert!(res.success, "unexpected error: {:?}", res.error);
        assert_eq!(streamed_count.load(Ordering::Relaxed), 1500);
        assert!(res.output.len() <= MAX_LOG_BUFFER_BYTES + 64);
        assert!(res.output.contains("[... earlier logs truncated ...]"));
        assert!(res.output.contains("log_line_1500_"));
    }

    #[test]
    fn test_lua_dialog_cancellation_aborts_immediately() {
        let _guard = TEST_GLOBAL_STATE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());

        struct CancellingDialogCallback;
        impl ScriptUiCallback for CancellingDialogCallback {
            fn show_alert(&self, _t: &str, _m: &str) {
                cancel_script();
            }
            fn show_toast(&self, _m: &str) {}
            fn show_prompt(&self, _t: &str, _d: &str, _k: &str) -> Option<String> {
                cancel_script();
                None
            }
            fn show_choice(&self, _t: &str, _i: &[String]) -> Option<usize> {
                cancel_script();
                None
            }
            fn show_multi_choice(
                &self,
                _t: &str,
                _i: &[String],
                _init: &[bool],
            ) -> Option<Vec<bool>> {
                cancel_script();
                None
            }
            fn set_dynamic_menu(&self, _j: &str) {}
            fn clear_dynamic_menu(&self) {}
            fn post_log(&self, _l: &str) {}
            fn canvas_draw_binary(&self, _b: &[u8]) {}
            fn canvas_clear(&self) {}
            fn canvas_set_visible(&self, _v: bool) {}
            fn get_screen_size(&self) -> (u32, u32) {
                (1080, 2400)
            }
            fn poll_menu_event(&self) -> Option<String> {
                None
            }
        }

        let res = run_script(
            1234,
            r#"
            local val = hmem.prompt("Enter value")
            return "SHOULD_NOT_REACH:" .. tostring(val)
            "#,
            Arc::new(CancellingDialogCallback),
        );
        assert!(!res.success);
        assert!(
            res.error
                .as_deref()
                .unwrap_or("")
                .contains("Script execution stopped by user")
        );
    }

    #[test]
    fn test_lua_canvas_frame_batching_and_newview() {
        let _guard = TEST_GLOBAL_STATE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());

        let packets: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));

        struct PacketCaptureCallback(Arc<Mutex<Vec<Vec<u8>>>>);
        impl ScriptUiCallback for PacketCaptureCallback {
            fn show_alert(&self, _t: &str, _m: &str) {}
            fn show_toast(&self, _m: &str) {}
            fn show_prompt(&self, _t: &str, _d: &str, _k: &str) -> Option<String> {
                None
            }
            fn show_choice(&self, _t: &str, _i: &[String]) -> Option<usize> {
                None
            }
            fn show_multi_choice(
                &self,
                _t: &str,
                _i: &[String],
                _init: &[bool],
            ) -> Option<Vec<bool>> {
                None
            }
            fn set_dynamic_menu(&self, _j: &str) {}
            fn clear_dynamic_menu(&self) {}
            fn post_log(&self, _l: &str) {}
            fn canvas_draw_binary(&self, b: &[u8]) {
                self.0
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push(b.to_vec());
            }
            fn canvas_clear(&self) {}
            fn canvas_set_visible(&self, _v: bool) {}
            fn get_screen_size(&self) -> (u32, u32) {
                (1080, 2400)
            }
            fn poll_menu_event(&self) -> Option<String> {
                None
            }
        }

        let res = run_script(
            1234,
            r##"
            canvas.begin_frame()
            canvas.draw_line(0, 0, 10, 10, 2, "#FF00FF00")
            canvas.draw_rect(10, 10, 50, 50, 2, "#FFFF0000", true)
            canvas.draw_circle(30, 30, 12, 2, "#FF0000FF", false)
            canvas.draw_text("Telemetry", 5, 15, 14, "#FFFFFFFF", "left")
            canvas.flush()
            return "BATCH_OK"
            "##,
            Arc::new(PacketCaptureCallback(packets.clone())),
        );
        assert!(res.success, "unexpected error: {:?}", res.error);
        let captured = packets.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(
            captured.len(),
            1,
            "expected a single batched binary packet for the entire frame"
        );
        assert_eq!(captured[0][0], CANVAS_MODE_REPLACE);
        let cmd_count = u16::from_le_bytes([captured[0][1], captured[0][2]]);
        assert_eq!(cmd_count, 4);
    }
}
