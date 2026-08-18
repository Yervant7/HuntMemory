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

package com.yervant.huntmem.backend

import android.util.Log
import com.yervant.huntmem.backend.MemoryEngine.getMemoryMaps
import com.yervant.huntmem.ui.overlay.tabs.MatchInfo
import java.util.UUID

class MemoryScanManager {

    suspend fun readMemory(pid: Int, addr: Long, datatype: String): Number? {
        return try {
            val res = MemoryEngine.readMem(pid, addr, datatype).getOrNull()
            if (res != null) {
                when (datatype.lowercase()) {
                    "byte" -> res.toByteOrNull()
                    "short" -> res.toShortOrNull()
                    "float16" -> res.toFloatOrNull()
                    "int" -> res.toIntOrNull()
                    "long" -> res.toLongOrNull()
                    "float" -> res.toFloatOrNull()
                    "double" -> res.toDoubleOrNull()
                    else -> res.toLongOrNull()
                }
            } else {
                Log.w(TAG, "Failed to read $datatype at 0x${addr.toString(16)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading memory at 0x${addr.toString(16)}: ${e.message}")
            null
        }
    }

    suspend fun createMatchFromOffset(address: String, dataType: String?, pid: Int): MatchInfo? {
        val cleanedaddr = address.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
        val type = (dataType ?: "int").lowercase()
        val value: Number = readMemory(pid, cleanedaddr, type) ?: 0

        val size = when (type) {
            "byte" -> 1
            "short", "float16" -> 2
            "int", "float" -> 4
            "long", "double" -> 8
            else -> return null
        }

        return MatchInfo(
            id = UUID.randomUUID().toString(),
            pid = pid,
            address = cleanedaddr,
            prevValue = value,
            valueType = type,
            size = size
        )
    }

    suspend fun createMatchFromAddressAndOffset(values: String, dataType: String?, pid: Int): MatchInfo? {
        val offs = values.split("+")
        if (offs.size < 2) return null

        val cleanedaddr = offs[0].removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
        val cleanedoffset = if (offs[1].startsWith("0x", ignoreCase = true)) {
            offs[1].removePrefix("0x").removePrefix("0X").toLongOrNull(16)
        } else {
            offs[1].toLongOrNull()
        } ?: return null

        val finalAddress = cleanedaddr + cleanedoffset
        val type = (dataType ?: "int").lowercase()
        val value: Number = readMemory(pid, finalAddress, type) ?: 0

        val size = when (type) {
            "byte" -> 1
            "short", "float16" -> 2
            "int", "float" -> 4
            "long", "double" -> 8
            else -> return null
        }

        return MatchInfo(
            id = UUID.randomUUID().toString(),
            pid = pid,
            address = finalAddress,
            prevValue = value,
            valueType = type,
            size = size
        )
    }

    suspend fun scanMemoryValues(
        sessionId: String,
        numValStr: String,
        valueType: String,
        operator: String,
        isNewScan: Boolean,
        selectedRegions: List<MemoryEngine.MemoryRegionType>,
        customFilter: String?
    ): Pair<Int, List<MatchInfo>> {
        val pid = AttachedProcessRepository.getAttachedPid() ?: throw Exception("pid is null")
        val regions = getMemoryMaps(pid, selectedRegions, customFilter)
        val s = numValStr.trim()
        val vTypeLower = valueType.lowercase()

        // 1. Obscured (XOR key-pair) scanning (e.g. ACTk ObscuredInt, ObscuredFloat)
        if (vTypeLower.startsWith("obscured") || s.startsWith("obscured:", ignoreCase = true) || s.startsWith("xor:", ignoreCase = true)) {
            val cleanedVal = s.removePrefix("obscured:").removePrefix("OBSCURED:")
                .removePrefix("xor:").removePrefix("XOR:").trim()
            val oType = if (vTypeLower.startsWith("obscured")) vTypeLower else "obscured_int"
            return if (isNewScan) {
                MemoryEngine.searchObscured(pid, sessionId, cleanedVal, oType, regions).getOrThrow()
            } else {
                MemoryEngine.filterObscuredAuto(pid, sessionId, "int", cleanedVal, oType).getOrThrow()
            }
        }

        // 2. Scientific / BigDouble struct scanning (e.g. 1.5e6, bigdouble:1.5,6)
        if (vTypeLower == "bigdouble" || s.startsWith("bigdouble:", ignoreCase = true) || s.startsWith("mantissa:", ignoreCase = true)) {
            val cleanedVal = s.removePrefix("bigdouble:").removePrefix("BIGDOUBLE:")
                .removePrefix("mantissa:").removePrefix("MANTISSA:").trim()
            return if (isNewScan) {
                MemoryEngine.searchBigDouble(pid, sessionId, cleanedVal, regions).getOrThrow()
            } else {
                MemoryEngine.filterBigDoubleAuto(pid, sessionId, cleanedVal).getOrThrow()
            }
        }

        // 3. Tolerance / Epsilon / Fuzzy floating point scanning (e.g. 100 +- 0.5 or 100 +- 2%)
        if (s.contains("+-") || s.contains("±")) {
            val delimiter = if (s.contains("+-")) "+-" else "±"
            val parts = s.split(delimiter)
            if (parts.size == 2) {
                val center = parts[0].trim().toDoubleOrNull() ?: 0.0
                val tolStr = parts[1].trim()
                val (minVal, maxVal) = if (tolStr.endsWith("%")) {
                    val pct = (tolStr.removeSuffix("%").trim().toDoubleOrNull() ?: 0.0) / 100.0
                    val delta = (center.coerceAtLeast(0.0) * pct).coerceAtLeast(0.0001)
                    Pair((center - delta).toString(), (center + delta).toString())
                } else {
                    val tol = tolStr.toDoubleOrNull() ?: 0.0
                    Pair((center - tol).toString(), (center + tol).toString())
                }

                val primaryType = if (vTypeLower.contains("double")) "double" else if (vTypeLower.contains("float16")) "float16" else "float"
                return if (isNewScan) {
                    MemoryEngine.searchRange(pid, sessionId, minVal, maxVal, primaryType, regions).getOrThrow()
                } else {
                    MemoryEngine.filterRangeAuto(pid, sessionId, primaryType, minVal, maxVal).getOrThrow()
                }
            }
        }

        // 4. Scaled / Multiplier fixed-point scanning (e.g. 10.5*1000 or scaled:10.5:1000)
        if (s.startsWith("scaled:", ignoreCase = true) || (s.contains("*") && !s.contains(";"))) {
            val cleaned = s.removePrefix("scaled:").removePrefix("SCALED:").trim()
            val parts = cleaned.split(if (cleaned.contains(":")) ":" else "*")
            if (parts.size == 2) {
                val base = parts[0].trim().toDoubleOrNull() ?: 0.0
                val mult = parts[1].trim().toDoubleOrNull() ?: 1.0
                val scaledInt = (base * mult).toLong().toString()
                return if (isNewScan) {
                    MemoryEngine.search(pid, sessionId, scaledInt, vTypeLower, regions, operator).getOrThrow()
                } else {
                    MemoryEngine.filterAddressesAuto(pid, sessionId, vTypeLower, scaledInt, operator).getOrThrow()
                }
            }
        }

        // 5. Group / Struct scanning (e.g. 100;200:512 or f64:1.5;i64:6:16)
        if (s.contains(";")) {
            if (isNewScan) {
                return MemoryEngine.searchGroup(pid, sessionId, s, vTypeLower, regions).getOrThrow()
            } else {
                val split = s.split(":")
                val values = split[0].split(";")
                return MemoryEngine.filterGroupAddressesAuto(pid, sessionId, vTypeLower, values, operator).getOrThrow()
            }
        }

        // 6. Range scanning (e.g. 10..20 or 10~20)
        if (s.contains("..") || s.contains("~")) {
            val delimiter = if (s.contains("..")) ".." else "~"
            val values = s.split(delimiter)
            if (values.size >= 2) {
                val minStr = values[0].trim()
                val maxStr = values[1].trim()
                return if (isNewScan) {
                    MemoryEngine.searchRange(pid, sessionId, minStr, maxStr, vTypeLower, regions).getOrThrow()
                } else {
                    MemoryEngine.filterRangeAuto(pid, sessionId, vTypeLower, minStr, maxStr).getOrThrow()
                }
            }
        }

        // 7. Standard typed / exact / relative scan
        return if (isNewScan) {
            MemoryEngine.search(pid, sessionId, s, vTypeLower, regions, operator).getOrThrow()
        } else {
            MemoryEngine.filterAddressesAuto(pid, sessionId, vTypeLower, s, operator).getOrThrow()
        }
    }

    suspend fun updateMatchValues(sessionId: String): List<MatchInfo> {
        val pid = AttachedProcessRepository.getAttachedPid() ?: return emptyList()

        return try {
            val results = MemoryEngine.filterAddressesAuto(pid, sessionId, "int", "0", "update").getOrThrow()
            results.second
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch update matches: ${e.message}")
            emptyList()
        }
    }

    companion object {
        const val TAG = "MemoryScanManager"
    }
}
