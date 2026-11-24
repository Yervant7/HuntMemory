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

import android.content.Context
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.MemoryEngine
import com.yervant.huntmem.backend.MemoryScanManager
import com.yervant.huntmem.backend.ShellProcessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanOptions(
    val inputVal: String,
    val valueType: String,
    val operator: String
)

sealed class ScanInputException(message: String) : Exception(message) {
    data object NoProcess : ScanInputException("No process attached")
    data object ProcessNotRunning : ScanInputException("Target process is not running")
    data object EmptyInput : ScanInputException("Input value cannot be empty for this operator")
    class InvalidValue(val input: String, val valueType: String) :
        ScanInputException("Input value '$input' is not valid for selected type(s) '$valueType'")
    class GotoResolutionFailed(val expression: String) :
        ScanInputException("Failed to resolve address expression '$expression'")
}

fun doesOperatorRequireValue(operator: String, isNewScan: Boolean): Boolean {
    val clean = operator.trim().lowercase()
    if (clean.contains("unknown") || clean == "?") return false
    if (!isNewScan) {
        if (clean.contains("increased by") || clean.contains("decreased by") ||
            clean == "+val" || clean == "+x" || clean == "-val" || clean == "-x"
        ) {
            return true
        }
        if (clean.contains("increased") || clean.contains("decreased") ||
            clean.contains("changed") || clean.contains("unchanged") ||
            clean == "+prev" || clean == "-prev" || clean == "!=prev" || clean == "==prev" ||
            clean == "~"
        ) {
            return false
        }
    }
    return true
}

fun getOperatorHintResId(operator: String): Int {
    val clean = operator.trim().lowercase()
    return when {
        clean.contains("unknown") || clean == "?" -> R.string.memory_menu_op_hint_unknown
        clean.contains("increased by") || clean == "+val" || clean == "+x" -> R.string.memory_menu_op_hint_increased_by
        clean.contains("decreased by") || clean == "-val" || clean == "-x" -> R.string.memory_menu_op_hint_decreased_by
        clean.contains("increased") || clean == "+prev" -> R.string.memory_menu_op_hint_increased
        clean.contains("decreased") || clean == "-prev" -> R.string.memory_menu_op_hint_decreased
        clean.contains("unchanged") || clean == "==prev" -> R.string.memory_menu_op_hint_unchanged
        clean.contains("changed") || clean == "!=prev" || clean == "~" -> R.string.memory_menu_op_hint_changed
        clean.contains("!=") -> R.string.memory_menu_op_hint_not_equal
        clean.contains(">=") -> R.string.memory_menu_op_hint_greater_equal
        clean.contains("<=") -> R.string.memory_menu_op_hint_less_equal
        clean.contains(">") -> R.string.memory_menu_op_hint_greater
        clean.contains("<") -> R.string.memory_menu_op_hint_less
        else -> R.string.memory_menu_op_hint_equal
    }
}

fun formatFriendlyScanError(
    context: Context,
    error: Throwable,
    input: String,
    valueType: String
): String {
    return when (error) {
        is ScanInputException.NoProcess ->
            context.getString(R.string.memory_menu_friendly_error_no_process)
        is ScanInputException.ProcessNotRunning ->
            context.getString(R.string.memory_menu_friendly_error_process_stopped)
        is ScanInputException.EmptyInput ->
            context.getString(R.string.memory_menu_friendly_error_empty_input)
        is ScanInputException.InvalidValue ->
            context.getString(
                R.string.memory_menu_friendly_error_invalid_number,
                error.input.ifBlank { input },
                error.valueType.ifBlank { valueType }.uppercase()
            )
        is ScanInputException.GotoResolutionFailed ->
            context.getString(R.string.memory_menu_friendly_error_goto_failed, error.expression)
        else -> {
            val msg = error.message.orEmpty().lowercase()
            when {
                msg.contains("hmkpm") || msg.contains("probe") ->
                    context.getString(R.string.memory_menu_friendly_error_hmkpm_unavailable)
                msg.contains("no process") ->
                    context.getString(R.string.memory_menu_friendly_error_no_process)
                else ->
                    context.getString(R.string.memory_menu_friendly_error_scan_failed)
            }
        }
    }
}

fun mergePreviousValues(
    previousMatches: List<MatchInfo>,
    newMatches: List<MatchInfo>
): List<MatchInfo> {
    if (previousMatches.isEmpty() || newMatches.isEmpty()) return newMatches
    val prevMap = HashMap<String, MatchInfo>(previousMatches.size)
    for (m in previousMatches) {
        prevMap["${m.address}-${m.valueType}"] = m
    }
    return newMatches.map { updated ->
        val old = prevMap["${updated.address}-${updated.valueType}"]
        if (old == null) {
            updated
        } else {
            val oldVal = old.prevValue.toString()
            val newVal = updated.prevValue.toString()
            val prevToKeep = if (oldVal != newVal) old.prevValue else old.previousValue
            updated.copy(previousValue = prevToKeep)
        }
    }
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

private data class ScanExecutionOutcome(
    val count: Int,
    val matches: List<MatchInfo>,
    val isNewScan: Boolean,
    val undoCount: Int,
    val isSaved: Boolean
)

/**
 * Determines whether an input string in the Scan field is an explicit address or pointer
 * expression (`@0x...`, `[base]+off`, `lib.so+0x...`, or `0x...+0x...`).
 * Plain hexadecimal literals like `0x100` or `-0x100` return `false` so they are scanned as hex values.
 */
fun isExplicitAddressExpression(input: String, isNewScan: Boolean): Boolean {
    val s = input.trim()
    if (s.isEmpty()) return false
    if (s.startsWith("@")) return true
    if (s.startsWith("[") && s.contains("]")) return true
    val hasModuleToken = s.contains(".so", ignoreCase = true) ||
            s.contains(".apk", ignoreCase = true) ||
            s.contains(".art", ignoreCase = true) ||
            s.startsWith("lib", ignoreCase = true)
    if (hasModuleToken && (s.contains("+") || isNewScan)) return true
    if (s.startsWith("0x", ignoreCase = true) && s.contains("+")) return true
    return false
}

suspend fun onNextScanClicked(
    scanOptions: ScanOptions,
    onBeforeScanStart: () -> Unit,
    onScanDone: () -> Unit,
    onScanError: (e: Exception) -> Unit,
    tabState: TabState
) {
    val previousInputVal = tabState.scanInputVal.value
    val previousOperatorIdx = tabState.operatorSelectedOptionIdx.value
    val previousStep = tabState.refinementStep.value
    val previousIsSaved = tabState.isCurrentSaved.value
    val previousMatchesSnapshot = synchronized(tabState.matches) { tabState.matches.toList() }

    onBeforeScanStart()
    try {
        val outcome = withContext(Dispatchers.IO) {
            val mem = MemoryScanManager()
            val pid = AttachedProcessRepository.getAttachedPid() ?: throw ScanInputException.NoProcess
            if (!ShellProcessProvider().isProcessRunning(pid.toString())) {
                throw ScanInputException.ProcessNotRunning
            }
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
            val isDirectAddress = isExplicitAddressExpression(input, isNewScan)
            val isUnknown = op.contains("unknown") || op == "?" || (isNewScan && input == "?")
            val requiresValue = doesOperatorRequireValue(op, isNewScan) && !isUnknown

            val effectiveOp = if (isUnknown && isNewScan) "unknown" else op
            val effectiveInput = if (isUnknown && isNewScan) "" else input

            if (!isGroup && !isRange && !isDirectAddress && !isTolerance && !isScaled && !isObscured && !isBigDouble) {
                if (input.isBlank() && requiresValue) {
                    throw ScanInputException.EmptyInput
                }
                if (input.isNotBlank() && requiresValue && !isValidForAnyType(input, vType)) {
                    throw ScanInputException.InvalidValue(input, vType)
                }
            }

            if (isDirectAddress && !isObscured && !isBigDouble) {
                val primaryType = vType.split(",", "|", ";").firstOrNull { it.isNotBlank() } ?: "int"
                val result = mem.createMatchFromOffset(input, primaryType, pid)
                    ?: throw ScanInputException.GotoResolutionFailed(input)
                ScanExecutionOutcome(
                    count = 1,
                    matches = listOf(result),
                    isNewScan = isNewScan,
                    undoCount = 0,
                    isSaved = false
                )
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
                val mergedMatches = if (isNewScan) {
                    newMatches
                } else {
                    mergePreviousValues(previousMatchesSnapshot, newMatches)
                }
                val sessionId = tabState.id.toString()
                val undoCount = MemoryEngine.getSessionUndoCount(sessionId)
                val isSaved = MemoryEngine.isSessionSaved(sessionId)
                ScanExecutionOutcome(
                    count = count,
                    matches = mergedMatches,
                    isNewScan = isNewScan,
                    undoCount = undoCount,
                    isSaved = isSaved
                )
            }
        }

        synchronized(tabState.matches) {
            tabState.matches.clear()
            tabState.matches.addAll(outcome.matches)
        }
        tabState.totalMatchesCount.value = outcome.count
        tabState.selectedAddresses.value = emptySet()
        tabState.isSelectionMode.value = false

        if (outcome.isNewScan) {
            tabState.uiHistoryStack.clear()
            tabState.refinementStep.value = 1
            tabState.isCurrentSaved.value = outcome.isSaved
            tabState.undoCount.value = outcome.undoCount
            tabState.savedCheckpointsCount.value = if (outcome.isSaved) 1 else 0
        } else {
            tabState.uiHistoryStack.retainAll { it.isSaved }
            tabState.uiHistoryStack.add(
                UiHistoryEntry(
                    inputVal = previousInputVal,
                    operatorIdx = previousOperatorIdx,
                    step = previousStep,
                    isSaved = previousIsSaved
                )
            )
            tabState.refinementStep.value = previousStep + 1
            tabState.isCurrentSaved.value = outcome.isSaved
            tabState.undoCount.value = outcome.undoCount
            tabState.savedCheckpointsCount.value =
                tabState.uiHistoryStack.count { it.isSaved } + (if (outcome.isSaved) 1 else 0)
        }

        onScanDone()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        onScanError(e)
    }
}
