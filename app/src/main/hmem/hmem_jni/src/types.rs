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

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ValueType {
    Byte,
    Short,
    Int,
    Long,
    Float,
    Double,
    Float16,
}

impl ValueType {
    pub fn from_str(s: &str) -> Result<Self, String> {
        match s.trim().to_lowercase().as_str() {
            "byte" | "i8" | "u8" | "b" => Ok(ValueType::Byte),
            "short" | "i16" | "u16" | "w" | "word" => Ok(ValueType::Short),
            "int" | "i32" | "u32" | "d" | "dword" => Ok(ValueType::Int),
            "long" | "i64" | "u64" | "q" | "qword" => Ok(ValueType::Long),
            "float" | "f32" | "f" | "single" => Ok(ValueType::Float),
            "double" | "f64" | "d64" => Ok(ValueType::Double),
            "float16" | "f16" | "half" | "h" => Ok(ValueType::Float16),
            _ => Err(format!("Unsupported value type: {s}")),
        }
    }

    pub fn from_multi_str(s: &str) -> Result<Vec<Self>, String> {
        let clean = s.trim().to_lowercase();
        if clean == "all" || clean == "auto" || clean == "*" {
            return Ok(vec![
                ValueType::Byte,
                ValueType::Short,
                ValueType::Int,
                ValueType::Long,
                ValueType::Float,
                ValueType::Double,
                ValueType::Float16,
            ]);
        }
        let mut types = Vec::new();
        for part in clean.split([',', '|', ';']) {
            let p = part.trim();
            if !p.is_empty() {
                let vt = ValueType::from_str(p)?;
                if !types.contains(&vt) {
                    types.push(vt);
                }
            }
        }
        if types.is_empty() {
            Ok(vec![ValueType::Int])
        } else {
            Ok(types)
        }
    }

    #[inline(always)]
    pub fn size(&self) -> usize {
        match self {
            ValueType::Byte => 1,
            ValueType::Short | ValueType::Float16 => 2,
            ValueType::Int | ValueType::Float => 4,
            ValueType::Long | ValueType::Double => 8,
        }
    }

    #[inline]
    pub fn as_str(&self) -> &'static str {
        match self {
            ValueType::Byte => "byte",
            ValueType::Short => "short",
            ValueType::Int => "int",
            ValueType::Long => "long",
            ValueType::Float => "float",
            ValueType::Double => "double",
            ValueType::Float16 => "float16",
        }
    }
}

/// Software conversion between IEEE 754 half-precision (16-bit) and single-precision (32-bit) floats
#[inline]
pub fn f16_to_f32(h: u16) -> f32 {
    let sign = ((h >> 15) & 1) as u32;
    let exp = ((h >> 10) & 0x1F) as u32;
    let frac = (h & 0x3FF) as u32;

    if exp == 0 {
        if frac == 0 {
            // +/- 0
            f32::from_bits(sign << 31)
        } else {
            // Subnormal f16
            let mut f_exp = 127 - 14;
            let mut f_frac = frac << 13;
            while (f_frac & 0x0080_0000) == 0 {
                f_frac <<= 1;
                f_exp -= 1;
            }
            f_frac &= 0x007F_FFFF;
            f32::from_bits((sign << 31) | ((f_exp as u32) << 23) | f_frac)
        }
    } else if exp == 0x1F {
        if frac == 0 {
            // Infinity
            f32::from_bits((sign << 31) | (0xFF << 23))
        } else {
            // NaN
            f32::from_bits((sign << 31) | (0xFF << 23) | (frac << 13) | 1)
        }
    } else {
        // Normal number: new exp = exp - 15 + 127
        let f_exp = exp + (127 - 15);
        let f_frac = frac << 13;
        f32::from_bits((sign << 31) | (f_exp << 23) | f_frac)
    }
}

/// Software conversion from 32-bit single-precision float to IEEE 754 16-bit half-precision
#[inline]
pub fn f32_to_f16(f: f32) -> u16 {
    let bits = f.to_bits();
    let sign = (bits >> 31) & 1;
    let exp = ((bits >> 23) & 0xFF) as i32;
    let frac = bits & 0x007F_FFFF;

    if exp == 0xFF {
        // Infinity or NaN
        let h_frac = if frac != 0 { 0x200 } else { 0 };
        ((sign << 15) | (0x1F << 10) | h_frac) as u16
    } else {
        let new_exp = exp - 127 + 15;
        if new_exp >= 0x1F {
            // Overflow to infinity
            ((sign << 15) | (0x1F << 10)) as u16
        } else if new_exp <= 0 {
            if new_exp < -10 {
                // Underflow to zero
                (sign << 15) as u16
            } else {
                // Subnormal in f16
                let full_frac = frac | 0x0080_0000;
                let shift = (14 - new_exp) as u32;
                let h_frac = full_frac >> shift;
                ((sign << 15) | h_frac) as u16
            }
        } else {
            // Normal number: truncate/round
            let h_frac = frac >> 13;
            ((sign << 15) | ((new_exp as u32) << 10) | h_frac) as u16
        }
    }
}

#[allow(clippy::enum_variant_names)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ObscuredType {
    ObscuredInt,    // 4 bytes cryptoKey ^ 4 bytes hiddenValue = i32
    ObscuredFloat,  // 4 bytes cryptoKey ^ 4 bytes hiddenValue = f32 bits
    ObscuredDouble, // 8 bytes cryptoKey ^ 8 bytes hiddenValue = f64 bits
    ObscuredLong,   // 8 bytes cryptoKey ^ 8 bytes hiddenValue = i64
}

impl ObscuredType {
    pub fn from_str(s: &str) -> Result<Self, String> {
        match s.trim().to_lowercase().as_str() {
            "obscured_int" | "obscuredint" | "actk_int" | "xor_int" | "xor_i32" | "xori32"
            | "obscured" => Ok(ObscuredType::ObscuredInt),
            "obscured_float" | "obscuredfloat" | "actk_float" | "xor_float" | "xor_f32"
            | "xorf32" => Ok(ObscuredType::ObscuredFloat),
            "obscured_double" | "obscureddouble" | "actk_double" | "xor_double" | "xor_f64"
            | "xorf64" => Ok(ObscuredType::ObscuredDouble),
            "obscured_long" | "obscuredlong" | "actk_long" | "xor_long" | "xor_i64" | "xori64" => {
                Ok(ObscuredType::ObscuredLong)
            }
            _ => Err(format!("Unsupported obscured type: {s}")),
        }
    }

    #[inline(always)]
    pub fn size(&self) -> usize {
        match self {
            ObscuredType::ObscuredInt | ObscuredType::ObscuredFloat => 8,
            ObscuredType::ObscuredDouble | ObscuredType::ObscuredLong => 16,
        }
    }

    #[inline]
    #[allow(dead_code)]
    pub fn as_str(&self) -> &'static str {
        match self {
            ObscuredType::ObscuredInt => "obscured_int",
            ObscuredType::ObscuredFloat => "obscured_float",
            ObscuredType::ObscuredDouble => "obscured_double",
            ObscuredType::ObscuredLong => "obscured_long",
        }
    }
}

/// Representation of numbers stored as scientific notation structs:
/// - BreakInfinity / Decimal structs: `{ mantissa: f64, exponent: i64 }` (16 bytes)
/// - Alternates: `{ mantissa: f64, exponent: i32 }` (12 or 16 bytes)
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct BigDouble {
    pub mantissa: f64,
    pub exponent: i64,
}

impl BigDouble {
    pub fn parse(s: &str) -> Result<Self, String> {
        let clean = s.trim();
        let clean = clean.strip_prefix("bigdouble:").unwrap_or(clean).trim();
        let clean = clean.strip_prefix("mantissa:").unwrap_or(clean).trim();

        if clean.contains(',') || (clean.contains(':') && !clean.starts_with("0x")) {
            let parts: Vec<&str> = clean.split([',', ':']).collect();
            if parts.len() == 2 {
                let m = parts[0]
                    .trim()
                    .parse::<f64>()
                    .map_err(|e| format!("Invalid mantissa: {e}"))?;
                let exp = parts[1]
                    .trim()
                    .parse::<i64>()
                    .map_err(|e| format!("Invalid exponent: {e}"))?;
                return Ok(BigDouble {
                    mantissa: m,
                    exponent: exp,
                });
            }
        }

        if let Ok(v) = clean.parse::<f64>() {
            if v == 0.0 {
                return Ok(BigDouble {
                    mantissa: 0.0,
                    exponent: 0,
                });
            }
            let exp = v.abs().log10().floor() as i64;
            let mantissa = v / 10f64.powi(exp as i32);
            return Ok(BigDouble {
                mantissa,
                exponent: exp,
            });
        }

        Err(format!(
            "Invalid scientific / BigDouble format '{s}'. Use '1.5e6' or '1.5,6'"
        ))
    }
}

/// Target item for heterogeneous struct/group scanning
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GroupTargetItem {
    pub value_type: ValueType,
    pub target_bytes: Vec<u8>,
}

/// Parse a group specification string which can contain uniform values (`100;200:512`)
/// or heterogeneous typed values (`f64:1.5; i64:6 : 16`).
pub fn parse_group_spec(
    spec: &str,
    default_vtype: ValueType,
) -> Result<(Vec<GroupTargetItem>, usize), String> {
    let parts: Vec<&str> = spec.split(':').collect();
    if parts.is_empty() {
        return Err("Invalid group specification".into());
    }

    let mut distance: usize = 512;
    let vals_str = if parts.len() > 1 {
        if let Ok(dist) = parts[parts.len() - 1].trim().parse::<usize>() {
            distance = dist;
            parts[..parts.len() - 1].join(":")
        } else {
            parts.join(":")
        }
    } else {
        parts[0].to_string()
    };

    let raw_items: Vec<&str> = vals_str.split(';').collect();
    if raw_items.len() < 2 {
        return Err("Group spec requires at least two values separated by ';'".into());
    }

    let mut target_items = Vec::with_capacity(raw_items.len());
    for item in raw_items {
        let item = item.trim();
        if item.is_empty() {
            continue;
        }

        if item.contains(':') {
            let item_parts: Vec<&str> = item.splitn(2, ':').collect();
            let vt = ValueType::from_str(item_parts[0])?;
            let val_str = item_parts[1].trim();
            let b = value_str_to_bytes(val_str, vt)?;
            target_items.push(GroupTargetItem {
                value_type: vt,
                target_bytes: b,
            });
        } else {
            let b = value_str_to_bytes(item, default_vtype)?;
            target_items.push(GroupTargetItem {
                value_type: default_vtype,
                target_bytes: b,
            });
        }
    }

    if target_items.len() < 2 {
        return Err("Group spec requires at least two valid target elements".into());
    }

    Ok((target_items, distance))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScanOperator {
    Equal,
    NotEqual,
    Greater,
    Less,
    GreaterEqual,
    LessEqual,
    Update,
    Increased,
    Decreased,
    Changed,
    Unchanged,
    IncreasedBy,
    DecreasedBy,
    Unknown,
}

impl ScanOperator {
    pub fn from_str(s: &str) -> Result<Self, String> {
        let clean = s.trim().to_lowercase();
        if clean == "?" || clean == "unknown" || clean.contains("unknown") {
            return Ok(ScanOperator::Unknown);
        }
        if clean.contains("increased by")
            || clean == "+val"
            || clean == "+x"
            || clean == "increasedby"
        {
            return Ok(ScanOperator::IncreasedBy);
        }
        if clean.contains("decreased by")
            || clean == "-val"
            || clean == "-x"
            || clean == "decreasedby"
        {
            return Ok(ScanOperator::DecreasedBy);
        }
        if clean.contains("increased")
            || clean == "+prev"
            || clean == ">prev"
            || clean == "▲ increased"
        {
            return Ok(ScanOperator::Increased);
        }
        if clean.contains("decreased")
            || clean == "-prev"
            || clean == "<prev"
            || clean == "▼ decreased"
        {
            return Ok(ScanOperator::Decreased);
        }
        if clean.contains("changed") || clean == "!=prev" || clean == "~ changed" || clean == "~" {
            return Ok(ScanOperator::Changed);
        }
        if clean.contains("unchanged")
            || clean == "==prev"
            || clean == "=疏 unchanged"
            || clean == "= unchanged"
        {
            return Ok(ScanOperator::Unchanged);
        }

        match clean.as_str() {
            "equal" | "=" | "==" => Ok(ScanOperator::Equal),
            "notequal" | "!=" => Ok(ScanOperator::NotEqual),
            "greater" | ">" => Ok(ScanOperator::Greater),
            "less" | "<" => Ok(ScanOperator::Less),
            "greaterequal" | ">=" => Ok(ScanOperator::GreaterEqual),
            "lessequal" | "<=" => Ok(ScanOperator::LessEqual),
            "update" => Ok(ScanOperator::Update),
            _ => Ok(ScanOperator::Equal),
        }
    }

    #[inline(always)]
    pub fn is_relative(&self) -> bool {
        matches!(
            self,
            ScanOperator::Increased
                | ScanOperator::Decreased
                | ScanOperator::Changed
                | ScanOperator::Unchanged
                | ScanOperator::IncreasedBy
                | ScanOperator::DecreasedBy
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemoryRegion {
    pub start: u64,
    pub end: u64,
    pub permissions: String,
    #[serde(default)]
    pub offset: u64,
    #[serde(default)]
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanMatch {
    pub address: u64,
    pub value: String,
    pub region_start: u64,
    pub region_end: u64,
    pub permissions: String,
    pub path: String,
    #[serde(default)]
    pub value_type: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct CompactMatch {
    pub address: u64,
    pub raw_value: u64,
    pub region_idx: u32,
    pub value_type: ValueType,
}

#[derive(Debug, Clone)]
pub struct ScanSession {
    pub regions: Vec<MemoryRegion>,
    pub matches: Vec<CompactMatch>,
    pub active_types: Vec<ValueType>,
}

impl ScanSession {
    pub fn to_scan_matches(&self, max_count: usize) -> Vec<ScanMatch> {
        let count = self.matches.len().min(max_count);
        let mut result = Vec::with_capacity(count);

        for m in self.matches.iter().take(count) {
            let region = self.regions.get(m.region_idx as usize);
            let val_bytes = m.raw_value.to_le_bytes();
            let vtype = m.value_type;
            let val_str = bytes_to_value_str(&val_bytes[..vtype.size()], vtype);

            result.push(ScanMatch {
                address: m.address,
                value: val_str,
                region_start: region.map(|r| r.start).unwrap_or(0),
                region_end: region.map(|r| r.end).unwrap_or(0),
                permissions: region.map(|r| r.permissions.clone()).unwrap_or_default(),
                path: region.map(|r| r.path.clone()).unwrap_or_default(),
                value_type: vtype.as_str().to_string(),
            });
        }

        result
    }
}

#[derive(Serialize, Deserialize)]
pub struct ScanResult {
    pub matches: Vec<ScanMatch>,
    pub count: usize,
}

pub fn parse_int_flexible<T>(value: &str) -> Result<T, String>
where
    T: TryFrom<i64> + TryFrom<u64>,
    <T as TryFrom<i64>>::Error: std::fmt::Display,
    <T as TryFrom<u64>>::Error: std::fmt::Display,
{
    let s = value.trim();
    if s.is_empty() {
        return Err("Empty input string".into());
    }

    if let Some(hex_str) = s.strip_prefix("0x").or_else(|| s.strip_prefix("0X")) {
        let unsigned =
            u64::from_str_radix(hex_str, 16).map_err(|e| format!("Invalid hex '{s}': {e}"))?;
        T::try_from(unsigned)
            .or_else(|_| T::try_from(unsigned as i64))
            .map_err(|e| format!("Hex '{s}' out of range: {e}"))
    } else if let Some(hex_str) = s.strip_prefix("-0x").or_else(|| s.strip_prefix("-0X")) {
        let unsigned =
            i64::from_str_radix(hex_str, 16).map_err(|e| format!("Invalid hex '{s}': {e}"))?;
        T::try_from(-unsigned).map_err(|e| format!("Hex '{s}' out of range: {e}"))
    } else {
        match s.parse::<i64>() {
            Ok(signed) => T::try_from(signed).map_err(|e| format!("Value '{s}' out of range: {e}")),
            Err(_) => match s.parse::<u64>() {
                Ok(unsigned) => {
                    T::try_from(unsigned).map_err(|e| format!("Value '{s}' out of range: {e}"))
                }
                Err(e) => Err(format!("Invalid integer '{s}': {e}")),
            },
        }
    }
}

pub fn value_str_to_bytes(value: &str, vtype: ValueType) -> Result<Vec<u8>, String> {
    let s = value.trim();
    match vtype {
        ValueType::Byte => {
            if s.starts_with("0x")
                || s.starts_with("0X")
                || s.starts_with("-0x")
                || s.starts_with("-0X")
            {
                let u: u8 = parse_int_flexible(s)?;
                Ok(vec![u])
            } else {
                let v: i8 = parse_int_flexible(s)?;
                Ok(v.to_le_bytes().to_vec())
            }
        }
        ValueType::Short => {
            if s.starts_with("0x")
                || s.starts_with("0X")
                || s.starts_with("-0x")
                || s.starts_with("-0X")
            {
                let u: u16 = parse_int_flexible(s)?;
                Ok(u.to_le_bytes().to_vec())
            } else {
                let v: i16 = parse_int_flexible(s)?;
                Ok(v.to_le_bytes().to_vec())
            }
        }
        ValueType::Int => {
            if s.starts_with("0x")
                || s.starts_with("0X")
                || s.starts_with("-0x")
                || s.starts_with("-0X")
            {
                let u: u32 = parse_int_flexible(s)?;
                Ok(u.to_le_bytes().to_vec())
            } else {
                let v: i32 = parse_int_flexible(s)?;
                Ok(v.to_le_bytes().to_vec())
            }
        }
        ValueType::Long => {
            if s.starts_with("0x")
                || s.starts_with("0X")
                || s.starts_with("-0x")
                || s.starts_with("-0X")
            {
                let u: u64 = parse_int_flexible(s)?;
                Ok(u.to_le_bytes().to_vec())
            } else {
                let v: i64 = parse_int_flexible(s)?;
                Ok(v.to_le_bytes().to_vec())
            }
        }
        ValueType::Float16 => {
            if s.starts_with("0x") || s.starts_with("0X") {
                let u: u16 = parse_int_flexible(s)?;
                Ok(u.to_le_bytes().to_vec())
            } else {
                let v = s
                    .parse::<f32>()
                    .map_err(|e| format!("Invalid float16 '{s}': {e}"))?;
                let h = f32_to_f16(v);
                Ok(h.to_le_bytes().to_vec())
            }
        }
        ValueType::Float => {
            if s.starts_with("0x") || s.starts_with("0X") {
                let u: u32 = parse_int_flexible(s)?;
                Ok(u.to_le_bytes().to_vec())
            } else {
                let v = s
                    .parse::<f32>()
                    .map_err(|e| format!("Invalid float '{s}': {e}"))?;
                Ok(v.to_le_bytes().to_vec())
            }
        }
        ValueType::Double => {
            if s.starts_with("0x") || s.starts_with("0X") {
                let u: u64 = parse_int_flexible(s)?;
                Ok(u.to_le_bytes().to_vec())
            } else {
                let v = s
                    .parse::<f64>()
                    .map_err(|e| format!("Invalid double '{s}': {e}"))?;
                Ok(v.to_le_bytes().to_vec())
            }
        }
    }
}

#[inline]
pub fn bytes_to_value_str(bytes: &[u8], vtype: ValueType) -> String {
    if bytes.len() < vtype.size() {
        return "0".to_string();
    }
    match vtype {
        ValueType::Byte => {
            let v = bytes[0] as i8;
            v.to_string()
        }
        ValueType::Short => {
            let v = i16::from_le_bytes(bytes[..2].try_into().unwrap());
            v.to_string()
        }
        ValueType::Int => {
            let v = i32::from_le_bytes(bytes[..4].try_into().unwrap());
            v.to_string()
        }
        ValueType::Long => {
            let v = i64::from_le_bytes(bytes[..8].try_into().unwrap());
            v.to_string()
        }
        ValueType::Float16 => {
            let u = u16::from_le_bytes(bytes[..2].try_into().unwrap());
            let v = f16_to_f32(u);
            v.to_string()
        }
        ValueType::Float => {
            let v = f32::from_le_bytes(bytes[..4].try_into().unwrap());
            v.to_string()
        }
        ValueType::Double => {
            let v = f64::from_le_bytes(bytes[..8].try_into().unwrap());
            v.to_string()
        }
    }
}

#[inline(always)]
pub fn bytes_to_raw_u64(bytes: &[u8], vtype: ValueType) -> u64 {
    match vtype {
        ValueType::Byte => {
            if !bytes.is_empty() {
                bytes[0] as u64
            } else {
                0
            }
        }
        ValueType::Short | ValueType::Float16 => {
            if bytes.len() >= 2 {
                u16::from_le_bytes(bytes[..2].try_into().unwrap()) as u64
            } else {
                0
            }
        }
        ValueType::Int | ValueType::Float => {
            if bytes.len() >= 4 {
                u32::from_le_bytes(bytes[..4].try_into().unwrap()) as u64
            } else {
                0
            }
        }
        ValueType::Long | ValueType::Double => {
            if bytes.len() >= 8 {
                u64::from_le_bytes(bytes[..8].try_into().unwrap())
            } else {
                0
            }
        }
    }
}

#[inline(always)]
pub fn compare_values(current: &[u8], target: &[u8], vtype: ValueType, op: ScanOperator) -> bool {
    if current.len() < vtype.size() || (op != ScanOperator::Update && target.len() < vtype.size()) {
        return false;
    }
    match vtype {
        ValueType::Byte => {
            let c = current[0] as i8;
            let t = if op == ScanOperator::Update {
                0
            } else {
                target[0] as i8
            };
            match op {
                ScanOperator::Equal => c == t,
                ScanOperator::NotEqual => c != t,
                ScanOperator::Greater => c > t,
                ScanOperator::Less => c < t,
                ScanOperator::GreaterEqual => c >= t,
                ScanOperator::LessEqual => c <= t,
                ScanOperator::Update => true,
                _ => false,
            }
        }
        ValueType::Short => {
            let c = i16::from_le_bytes(current[..2].try_into().unwrap());
            let t = if op == ScanOperator::Update {
                0
            } else {
                i16::from_le_bytes(target[..2].try_into().unwrap())
            };
            match op {
                ScanOperator::Equal => c == t,
                ScanOperator::NotEqual => c != t,
                ScanOperator::Greater => c > t,
                ScanOperator::Less => c < t,
                ScanOperator::GreaterEqual => c >= t,
                ScanOperator::LessEqual => c <= t,
                ScanOperator::Update => true,
                _ => false,
            }
        }
        ValueType::Int => {
            let c = i32::from_le_bytes(current[..4].try_into().unwrap());
            let t = if op == ScanOperator::Update {
                0
            } else {
                i32::from_le_bytes(target[..4].try_into().unwrap())
            };
            match op {
                ScanOperator::Equal => c == t,
                ScanOperator::NotEqual => c != t,
                ScanOperator::Greater => c > t,
                ScanOperator::Less => c < t,
                ScanOperator::GreaterEqual => c >= t,
                ScanOperator::LessEqual => c <= t,
                ScanOperator::Update => true,
                _ => false,
            }
        }
        ValueType::Long => {
            let c = i64::from_le_bytes(current[..8].try_into().unwrap());
            let t = if op == ScanOperator::Update {
                0
            } else {
                i64::from_le_bytes(target[..8].try_into().unwrap())
            };
            match op {
                ScanOperator::Equal => c == t,
                ScanOperator::NotEqual => c != t,
                ScanOperator::Greater => c > t,
                ScanOperator::Less => c < t,
                ScanOperator::GreaterEqual => c >= t,
                ScanOperator::LessEqual => c <= t,
                ScanOperator::Update => true,
                _ => false,
            }
        }
        ValueType::Float16 => {
            let cu = u16::from_le_bytes(current[..2].try_into().unwrap());
            let c = f16_to_f32(cu);
            let tu = if op == ScanOperator::Update {
                0
            } else {
                u16::from_le_bytes(target[..2].try_into().unwrap())
            };
            let t = f16_to_f32(tu);
            match op {
                ScanOperator::Equal => cu == tu || (c - t).abs() < 1e-4,
                ScanOperator::NotEqual => cu != tu && (c - t).abs() >= 1e-4,
                ScanOperator::Greater => c > t,
                ScanOperator::Less => c < t,
                ScanOperator::GreaterEqual => c >= t,
                ScanOperator::LessEqual => c <= t,
                ScanOperator::Update => true,
                _ => false,
            }
        }
        ValueType::Float => {
            let c = f32::from_le_bytes(current[..4].try_into().unwrap());
            let t = if op == ScanOperator::Update {
                0.0
            } else {
                f32::from_le_bytes(target[..4].try_into().unwrap())
            };
            match op {
                ScanOperator::Equal => c.to_bits() == t.to_bits() || (c - t).abs() < 1e-5,
                ScanOperator::NotEqual => c.to_bits() != t.to_bits() && (c - t).abs() >= 1e-5,
                ScanOperator::Greater => c > t,
                ScanOperator::Less => c < t,
                ScanOperator::GreaterEqual => c >= t,
                ScanOperator::LessEqual => c <= t,
                ScanOperator::Update => true,
                _ => false,
            }
        }
        ValueType::Double => {
            let c = f64::from_le_bytes(current[..8].try_into().unwrap());
            let t = if op == ScanOperator::Update {
                0.0
            } else {
                f64::from_le_bytes(target[..8].try_into().unwrap())
            };
            match op {
                ScanOperator::Equal => c.to_bits() == t.to_bits() || (c - t).abs() < 1e-9,
                ScanOperator::NotEqual => c.to_bits() != t.to_bits() && (c - t).abs() >= 1e-9,
                ScanOperator::Greater => c > t,
                ScanOperator::Less => c < t,
                ScanOperator::GreaterEqual => c >= t,
                ScanOperator::LessEqual => c <= t,
                ScanOperator::Update => true,
                _ => false,
            }
        }
    }
}

#[inline(always)]
pub fn compare_relative_values(
    current: &[u8],
    prev_raw: u64,
    target: &[u8],
    vtype: ValueType,
    op: ScanOperator,
) -> bool {
    if current.len() < vtype.size() {
        return false;
    }
    let prev_bytes = prev_raw.to_le_bytes();

    match vtype {
        ValueType::Byte => {
            let c = current[0] as i8;
            let p = prev_bytes[0] as i8;
            let delta = if target.is_empty() {
                0
            } else {
                target[0] as i8
            };
            match op {
                ScanOperator::Increased => c > p,
                ScanOperator::Decreased => c < p,
                ScanOperator::Changed => c != p,
                ScanOperator::Unchanged => c == p,
                ScanOperator::IncreasedBy => c == p.wrapping_add(delta),
                ScanOperator::DecreasedBy => c == p.wrapping_sub(delta),
                _ => compare_values(current, target, vtype, op),
            }
        }
        ValueType::Short => {
            let c = i16::from_le_bytes(current[..2].try_into().unwrap());
            let p = i16::from_le_bytes(prev_bytes[..2].try_into().unwrap());
            let delta = if target.len() < 2 {
                0
            } else {
                i16::from_le_bytes(target[..2].try_into().unwrap())
            };
            match op {
                ScanOperator::Increased => c > p,
                ScanOperator::Decreased => c < p,
                ScanOperator::Changed => c != p,
                ScanOperator::Unchanged => c == p,
                ScanOperator::IncreasedBy => c == p.wrapping_add(delta),
                ScanOperator::DecreasedBy => c == p.wrapping_sub(delta),
                _ => compare_values(current, target, vtype, op),
            }
        }
        ValueType::Int => {
            let c = i32::from_le_bytes(current[..4].try_into().unwrap());
            let p = i32::from_le_bytes(prev_bytes[..4].try_into().unwrap());
            let delta = if target.len() < 4 {
                0
            } else {
                i32::from_le_bytes(target[..4].try_into().unwrap())
            };
            match op {
                ScanOperator::Increased => c > p,
                ScanOperator::Decreased => c < p,
                ScanOperator::Changed => c != p,
                ScanOperator::Unchanged => c == p,
                ScanOperator::IncreasedBy => c == p.wrapping_add(delta),
                ScanOperator::DecreasedBy => c == p.wrapping_sub(delta),
                _ => compare_values(current, target, vtype, op),
            }
        }
        ValueType::Long => {
            let c = i64::from_le_bytes(current[..8].try_into().unwrap());
            let p = i64::from_le_bytes(prev_bytes[..8].try_into().unwrap());
            let delta = if target.len() < 8 {
                0
            } else {
                i64::from_le_bytes(target[..8].try_into().unwrap())
            };
            match op {
                ScanOperator::Increased => c > p,
                ScanOperator::Decreased => c < p,
                ScanOperator::Changed => c != p,
                ScanOperator::Unchanged => c == p,
                ScanOperator::IncreasedBy => c == p.wrapping_add(delta),
                ScanOperator::DecreasedBy => c == p.wrapping_sub(delta),
                _ => compare_values(current, target, vtype, op),
            }
        }
        ValueType::Float16 => {
            let cu = u16::from_le_bytes(current[..2].try_into().unwrap());
            let pu = u16::from_le_bytes(prev_bytes[..2].try_into().unwrap());
            let c = f16_to_f32(cu);
            let p = f16_to_f32(pu);
            let delta = if target.len() < 2 {
                0.0
            } else {
                f16_to_f32(u16::from_le_bytes(target[..2].try_into().unwrap()))
            };
            match op {
                ScanOperator::Increased => c > p,
                ScanOperator::Decreased => c < p,
                ScanOperator::Changed => cu != pu,
                ScanOperator::Unchanged => cu == pu,
                ScanOperator::IncreasedBy => (c - (p + delta)).abs() < 1e-3,
                ScanOperator::DecreasedBy => (c - (p - delta)).abs() < 1e-3,
                _ => compare_values(current, target, vtype, op),
            }
        }
        ValueType::Float => {
            let c = f32::from_le_bytes(current[..4].try_into().unwrap());
            let p = f32::from_le_bytes(prev_bytes[..4].try_into().unwrap());
            let delta = if target.len() < 4 {
                0.0
            } else {
                f32::from_le_bytes(target[..4].try_into().unwrap())
            };
            match op {
                ScanOperator::Increased => c > p,
                ScanOperator::Decreased => c < p,
                ScanOperator::Changed => c.to_bits() != p.to_bits(),
                ScanOperator::Unchanged => c.to_bits() == p.to_bits(),
                ScanOperator::IncreasedBy => (c - (p + delta)).abs() < 1e-5,
                ScanOperator::DecreasedBy => (c - (p - delta)).abs() < 1e-5,
                _ => compare_values(current, target, vtype, op),
            }
        }
        ValueType::Double => {
            let c = f64::from_le_bytes(current[..8].try_into().unwrap());
            let p = f64::from_le_bytes(prev_bytes[..8].try_into().unwrap());
            let delta = if target.len() < 8 {
                0.0
            } else {
                f64::from_le_bytes(target[..8].try_into().unwrap())
            };
            match op {
                ScanOperator::Increased => c > p,
                ScanOperator::Decreased => c < p,
                ScanOperator::Changed => c.to_bits() != p.to_bits(),
                ScanOperator::Unchanged => c.to_bits() == p.to_bits(),
                ScanOperator::IncreasedBy => (c - (p + delta)).abs() < 1e-9,
                ScanOperator::DecreasedBy => (c - (p - delta)).abs() < 1e-9,
                _ => compare_values(current, target, vtype, op),
            }
        }
    }
}

#[inline(always)]
pub fn value_in_range(current: &[u8], min: &[u8], max: &[u8], vtype: ValueType) -> bool {
    let size = vtype.size();
    if current.len() < size || min.len() < size || max.len() < size {
        return false;
    }
    match vtype {
        ValueType::Byte => {
            let c = current[0] as i8;
            let mn = min[0] as i8;
            let mx = max[0] as i8;
            c >= mn && c <= mx
        }
        ValueType::Short => {
            let c = i16::from_le_bytes(current[..2].try_into().unwrap());
            let mn = i16::from_le_bytes(min[..2].try_into().unwrap());
            let mx = i16::from_le_bytes(max[..2].try_into().unwrap());
            c >= mn && c <= mx
        }
        ValueType::Int => {
            let c = i32::from_le_bytes(current[..4].try_into().unwrap());
            let mn = i32::from_le_bytes(min[..4].try_into().unwrap());
            let mx = i32::from_le_bytes(max[..4].try_into().unwrap());
            c >= mn && c <= mx
        }
        ValueType::Long => {
            let c = i64::from_le_bytes(current[..8].try_into().unwrap());
            let mn = i64::from_le_bytes(min[..8].try_into().unwrap());
            let mx = i64::from_le_bytes(max[..8].try_into().unwrap());
            c >= mn && c <= mx
        }
        ValueType::Float16 => {
            let c = f16_to_f32(u16::from_le_bytes(current[..2].try_into().unwrap()));
            let mn = f16_to_f32(u16::from_le_bytes(min[..2].try_into().unwrap()));
            let mx = f16_to_f32(u16::from_le_bytes(max[..2].try_into().unwrap()));
            c >= mn && c <= mx
        }
        ValueType::Float => {
            let c = f32::from_le_bytes(current[..4].try_into().unwrap());
            let mn = f32::from_le_bytes(min[..4].try_into().unwrap());
            let mx = f32::from_le_bytes(max[..4].try_into().unwrap());
            c >= mn && c <= mx
        }
        ValueType::Double => {
            let c = f64::from_le_bytes(current[..8].try_into().unwrap());
            let mn = f64::from_le_bytes(min[..8].try_into().unwrap());
            let mx = f64::from_le_bytes(max[..8].try_into().unwrap());
            c >= mn && c <= mx
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_value_types_parsing_and_sizes() {
        assert_eq!(ValueType::from_str("byte").unwrap(), ValueType::Byte);
        assert_eq!(ValueType::from_str("short").unwrap(), ValueType::Short);
        assert_eq!(ValueType::from_str("int").unwrap(), ValueType::Int);
        assert_eq!(ValueType::from_str("long").unwrap(), ValueType::Long);
        assert_eq!(ValueType::from_str("float").unwrap(), ValueType::Float);
        assert_eq!(ValueType::from_str("double").unwrap(), ValueType::Double);
        assert_eq!(ValueType::from_str("float16").unwrap(), ValueType::Float16);
        assert_eq!(ValueType::from_str("f16").unwrap(), ValueType::Float16);
        assert_eq!(ValueType::from_str("half").unwrap(), ValueType::Float16);

        assert_eq!(ValueType::Byte.size(), 1);
        assert_eq!(ValueType::Short.size(), 2);
        assert_eq!(ValueType::Float16.size(), 2);
        assert_eq!(ValueType::Int.size(), 4);
        assert_eq!(ValueType::Float.size(), 4);
        assert_eq!(ValueType::Long.size(), 8);
        assert_eq!(ValueType::Double.size(), 8);
    }

    #[test]
    fn test_float16_conversions() {
        // 0.0
        let h0 = f32_to_f16(0.0);
        assert_eq!(f16_to_f32(h0), 0.0);

        // 1.0
        let h1 = f32_to_f16(1.0);
        assert_eq!(f16_to_f32(h1), 1.0);

        // -1.0
        let hm1 = f32_to_f16(-1.0);
        assert_eq!(f16_to_f32(hm1), -1.0);

        // 0.5
        let hhalf = f32_to_f16(0.5);
        assert_eq!(f16_to_f32(hhalf), 0.5);

        // 123.5
        let h123 = f32_to_f16(123.5);
        assert!((f16_to_f32(h123) - 123.5).abs() < 0.1);
    }

    #[test]
    fn test_obscured_types_parsing() {
        assert_eq!(
            ObscuredType::from_str("obscured_int").unwrap(),
            ObscuredType::ObscuredInt
        );
        assert_eq!(
            ObscuredType::from_str("obscured_float").unwrap(),
            ObscuredType::ObscuredFloat
        );
        assert_eq!(
            ObscuredType::from_str("obscured_double").unwrap(),
            ObscuredType::ObscuredDouble
        );
        assert_eq!(
            ObscuredType::from_str("obscured_long").unwrap(),
            ObscuredType::ObscuredLong
        );

        assert_eq!(ObscuredType::ObscuredInt.size(), 8);
        assert_eq!(ObscuredType::ObscuredFloat.size(), 8);
        assert_eq!(ObscuredType::ObscuredDouble.size(), 16);
        assert_eq!(ObscuredType::ObscuredLong.size(), 16);
    }

    #[test]
    fn test_big_double_parsing() {
        let bd1 = BigDouble::parse("1.5e6").unwrap();
        assert_eq!(bd1.exponent, 6);
        assert!((bd1.mantissa - 1.5).abs() < 1e-5);

        let bd2 = BigDouble::parse("1.5,12").unwrap();
        assert_eq!(bd2.exponent, 12);
        assert!((bd2.mantissa - 1.5).abs() < 1e-5);

        let bd3 = BigDouble::parse("bigdouble:2.75:20").unwrap();
        assert_eq!(bd3.exponent, 20);
        assert!((bd3.mantissa - 2.75).abs() < 1e-5);
    }

    #[test]
    fn test_group_spec_parsing() {
        let (items, dist) = parse_group_spec("f64:1.5; i64:6 : 16", ValueType::Int).unwrap();
        assert_eq!(dist, 16);
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].value_type, ValueType::Double);
        assert_eq!(items[1].value_type, ValueType::Long);

        let (items_simple, dist_simple) =
            parse_group_spec("100; 200; 300", ValueType::Int).unwrap();
        assert_eq!(dist_simple, 512);
        assert_eq!(items_simple.len(), 3);
        assert_eq!(items_simple[0].value_type, ValueType::Int);
    }

    #[test]
    fn test_hex_value_parsing() {
        let b = value_str_to_bytes("0xFF", ValueType::Byte).unwrap();
        assert_eq!(b, vec![255]);

        let s = value_str_to_bytes("0x1234", ValueType::Short).unwrap();
        assert_eq!(s, 0x1234i16.to_le_bytes().to_vec());

        let i = value_str_to_bytes("0xDEADBEEF", ValueType::Int).unwrap();
        assert_eq!(i, 0xDEADBEEFu32.to_le_bytes().to_vec());
    }

    #[test]
    fn test_relative_comparisons() {
        let prev = 100i32 as u64;
        let current_increased = 105i32.to_le_bytes();
        let current_decreased = 95i32.to_le_bytes();
        let current_same = 100i32.to_le_bytes();

        assert!(compare_relative_values(
            &current_increased,
            prev,
            &[],
            ValueType::Int,
            ScanOperator::Increased
        ));
        assert!(!compare_relative_values(
            &current_decreased,
            prev,
            &[],
            ValueType::Int,
            ScanOperator::Increased
        ));

        assert!(compare_relative_values(
            &current_decreased,
            prev,
            &[],
            ValueType::Int,
            ScanOperator::Decreased
        ));
        assert!(compare_relative_values(
            &current_same,
            prev,
            &[],
            ValueType::Int,
            ScanOperator::Unchanged
        ));
        assert!(compare_relative_values(
            &current_increased,
            prev,
            &[],
            ValueType::Int,
            ScanOperator::Changed
        ));

        let delta = 5i32.to_le_bytes();
        assert!(compare_relative_values(
            &current_increased,
            prev,
            &delta,
            ValueType::Int,
            ScanOperator::IncreasedBy
        ));
        assert!(compare_relative_values(
            &current_decreased,
            prev,
            &delta,
            ValueType::Int,
            ScanOperator::DecreasedBy
        ));
    }

    #[test]
    fn test_range_comparisons() {
        let min = 10i32.to_le_bytes();
        let max = 20i32.to_le_bytes();
        assert!(value_in_range(
            &15i32.to_le_bytes(),
            &min,
            &max,
            ValueType::Int
        ));
        assert!(value_in_range(
            &10i32.to_le_bytes(),
            &min,
            &max,
            ValueType::Int
        ));
        assert!(value_in_range(
            &20i32.to_le_bytes(),
            &min,
            &max,
            ValueType::Int
        ));
        assert!(!value_in_range(
            &9i32.to_le_bytes(),
            &min,
            &max,
            ValueType::Int
        ));
        assert!(!value_in_range(
            &21i32.to_le_bytes(),
            &min,
            &max,
            ValueType::Int
        ));
    }
}
