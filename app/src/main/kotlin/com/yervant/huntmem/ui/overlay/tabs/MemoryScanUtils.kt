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

package com.yervant.huntmem.ui.overlay.tabs

import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.MemoryScanManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanOptions(
    val inputVal: String,
    val valueType: String,
    val operator: String
)

private fun isRelativeOperator(op: String): Boolean {
    val clean = op.trim().lowercase()
    return clean.contains("increased") || clean.contains("decreased") ||
            clean.contains("changed") || clean.contains("unchanged") ||
            clean == "+prev" || clean == "-prev" || clean == "!=prev" || clean == "==prev" ||
            clean == "~"
}

private fun isValidSingleNumber(input: String, valueType: String): Boolean {
    val s = input.trim()
    if (s.isEmpty()) return false

    val isHex = s.startsWith("0x", ignoreCase = true) || s.startsWith("-0x", ignoreCase = true)
    return try {
        when (valueType.lowercase()) {
            "byte" -> {
                if (isHex) {
                    val clean = s.removePrefix("-").removePrefix("0x").removePrefix("0X")
                    clean.toLongOrNull(16)?.let { it in 0L..255L } == true
                } else {
                    s.toByteOrNull() != null || (s.toShortOrNull()?.let { it in 0..255 } == true)
                }
            }
            "short" -> {
                if (isHex) {
                    val clean = s.removePrefix("-").removePrefix("0x").removePrefix("0X")
                    clean.toLongOrNull(16)?.let { it in 0L..65535L } == true
                } else {
                    s.toShortOrNull() != null || (s.toIntOrNull()?.let { it in 0..65535 } == true)
                }
            }
            "float16" -> {
                if (isHex) {
                    val clean = s.removePrefix("0x").removePrefix("0X")
                    clean.toLongOrNull(16) != null
                } else {
                    s.toFloatOrNull() != null
                }
            }
            "int" -> {
                if (isHex) {
                    val clean = s.removePrefix("-").removePrefix("0x").removePrefix("0X")
                    clean.toLongOrNull(16)?.let { it in 0L..0xFFFFFFFFL } == true
                } else {
                    s.toIntOrNull() != null || s.toLongOrNull()?.let { it in 0L..0xFFFFFFFFL } == true
                }
            }
            "long" -> {
                if (isHex) {
                    val clean = s.removePrefix("-").removePrefix("0x").removePrefix("0X")
                    clean.toBigIntegerOrNull(16) != null
                } else {
                    s.toLongOrNull() != null || s.toBigIntegerOrNull() != null
                }
            }
            "float" -> {
                if (isHex) {
                    val clean = s.removePrefix("0x").removePrefix("0X")
                    clean.toLongOrNull(16) != null
                } else {
                    s.toFloatOrNull() != null
                }
            }
            "double" -> {
                if (isHex) {
                    val clean = s.removePrefix("0x").removePrefix("0X")
                    clean.toBigIntegerOrNull(16) != null
                } else {
                    s.toDoubleOrNull() != null
                }
            }
            "bigdouble" -> {
                s.toDoubleOrNull() != null || s.contains("e", ignoreCase = true) || s.contains(",") || s.contains(":")
            }
            "obscured_int" -> isValidSingleNumber(s, "int")
            "obscured_float" -> isValidSingleNumber(s, "float")
            "obscured_double" -> isValidSingleNumber(s, "double")
            "obscured_long" -> isValidSingleNumber(s, "long")
            else -> true
        }
    } catch (_: Exception) {
        false
    }
}

private fun isValidForAnyType(input: String, typesStr: String): Boolean {
    val clean = input.trim()
    if (clean.contains(";") || clean.contains("+-") || clean.contains("±") ||
        clean.startsWith("scaled:", ignoreCase = true) || clean.contains("*") ||
        clean.startsWith("obscured:", ignoreCase = true) || clean.startsWith("xor:", ignoreCase = true) ||
        clean.startsWith("bigdouble:", ignoreCase = true) || clean.startsWith("mantissa:", ignoreCase = true)) {
        return true
    }

    val types = if (typesStr.lowercase() == "all" || typesStr.lowercase() == "auto") {
        listOf("byte", "short", "float16", "int", "long", "float", "double")
    } else {
        typesStr.split(",", "|", ";").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
    if (types.isEmpty()) return true
    return types.any { isValidSingleNumber(clean, it) }
}

suspend fun onNextScanClicked(
    scanOptions: ScanOptions,
    onBeforeScanStart: () -> Unit,
    onScanDone: () -> Unit,
    onScanError: (e: Exception) -> Unit,
    tabState: TabState
) {
    onBeforeScanStart()
    try {
        withContext(Dispatchers.IO) {
            val mem = MemoryScanManager()
            val pid = AttachedProcessRepository.getAttachedPid() ?: throw Exception("No process attached")
            val selectedRegions = getSelectedRegions()
            val customFilter = getCustomFilter()
            val input = scanOptions.inputVal.trim()
            val vType = scanOptions.valueType.lowercase()
            val op = scanOptions.operator.lowercase()
            val isNewScan = !tabState.initialScanDone.value

            val isGroup = input.contains(";")
            val isTolerance = input.contains("+-") || input.contains("±")
            val isScaled = input.startsWith("scaled:", ignoreCase = true) || (input.contains("*") && !input.contains(";"))
            val isObscured = vType.startsWith("obscured") || input.startsWith("obscured:", ignoreCase = true) || input.startsWith("xor:", ignoreCase = true)
            val isBigDouble = vType == "bigdouble" || input.startsWith("bigdouble:", ignoreCase = true) || input.startsWith("mantissa:", ignoreCase = true)
            val isRange = input.contains("..") || input.contains("~")
            val isDirectAddress = input.startsWith("0x", ignoreCase = true) && (input.contains("+") || isNewScan)
            val isRelative = isRelativeOperator(op)
            val isUnknown = op.contains("unknown") || op == "?" || (isNewScan && (input == "?" || input.isBlank()))

            val effectiveOp = if (isUnknown && isNewScan) "unknown" else op
            val effectiveInput = if (isUnknown && isNewScan) "" else input

            if (!isGroup && !isRange && !isDirectAddress && !isTolerance && !isScaled && !isObscured && !isBigDouble) {
                if (input.isBlank() && !isRelative && !isUnknown) {
                    throw Exception("Input value cannot be empty for this operator")
                }
                if (input.isNotBlank() && !isValidForAnyType(input, vType) && !isUnknown && input != "?") {
                    throw Exception("Input value '$input' is not valid for selected type(s)")
                }
            }

            if (isDirectAddress && !isObscured && !isBigDouble) {
                val primaryType = vType.split(",", "|", ";").firstOrNull { it.isNotBlank() } ?: "int"
                if (input.contains("+")) {
                    val result = mem.createMatchFromAddressAndOffset(input, primaryType, pid)
                    if (result != null) {
                        synchronized(tabState.matches) {
                            tabState.matches.clear()
                            tabState.matches.add(result)
                        }
                        tabState.totalMatchesCount.value = 1
                    }
                } else {
                    val result = mem.createMatchFromOffset(input, primaryType, pid)
                    if (result != null) {
                        synchronized(tabState.matches) {
                            tabState.matches.clear()
                            tabState.matches.add(result)
                        }
                        tabState.totalMatchesCount.value = 1
                    }
                }
            } else {
                val (count, newMatches) = mem.scanMemoryValues(
                    sessionId = tabState.id.toString(),
                    numValStr = effectiveInput,
                    valueType = vType,
                    operator = effectiveOp,
                    isNewScan = isNewScan,
                    selectedRegions = selectedRegions,
                    customFilter = customFilter
                )
                synchronized(tabState.matches) {
                    tabState.matches.clear()
                    tabState.matches.addAll(newMatches)
                }
                tabState.totalMatchesCount.value = count
            }
        }
        onScanDone()
    } catch (e: Exception) {
        onScanError(e)
    }
}
