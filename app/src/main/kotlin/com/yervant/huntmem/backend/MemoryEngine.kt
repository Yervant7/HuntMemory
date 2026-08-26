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

import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.Keep
import com.yervant.huntmem.ui.overlay.tabs.MatchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

object MemoryEngine {

    private const val TAG = "MemoryEngine"

    @Keep
    data class MemoryMapEntry(
        val start: Long,
        val end: Long,
        val permissions: String,
        val offset: Long = 0L,
        val device: String = "",
        val inode: Long = 0L,
        val path: String,
        val mergedCount: Int = 1
    )

    @Keep
    data class MemoryMapOptions(
        val requireRead: Boolean = true,
        val requireWrite: Boolean = false,
        val includeSwapped: Boolean = true,
        val minSize: Long = 0L
    )

    @Keep
    data class LuaExecutionResult(
        val success: Boolean,
        val output: String,
        val error: String? = null,
        val result: String? = null
    )

    var globalMapOptions = MemoryMapOptions()

    enum class MemoryRegionType(val code: String, val title: String) {
        ANONYMOUS("A", "Anonymous"),
        ALLOC("CA", "C++ Alloc (malloc, scudo...)"),
        BSS("CB", "C++ BSS"),
        DATA("CD", "C++ Data"),
        HEAP("CH", "C++ Heap"),
        JAVA_HEAP("JH", "Java Heap"),
        STACK("S", "Stack"),
        ASHMEM("AS", "Ashmem"),
        LIBS("XA", "Code App (Libraries)"),
        CUSTOM("CUSTOM", "Custom Filter")
    }

    fun getMemoryMaps(
        pid: Int,
        selectedRegions: List<MemoryRegionType> = emptyList(),
        customFilter: String? = null,
        options: MemoryMapOptions = globalMapOptions
    ): List<MemoryMapEntry> {
        return try {
            val filterTypesStr = selectedRegions.joinToString(",") { it.code }
            val json = NativeBridge.getMemoryMaps(
                pid = pid,
                requireRead = options.requireRead,
                requireWrite = options.requireWrite,
                includeSwapped = options.includeSwapped,
                minSize = options.minSize,
                filterTypes = filterTypesStr,
                custom = customFilter ?: ""
            )
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MemoryMapEntry(
                    start = obj.getLong("start"),
                    end = obj.getLong("end"),
                    permissions = obj.optString("permissions", ""),
                    offset = obj.optLong("offset", 0L),
                    device = "",
                    inode = 0L,
                    path = obj.optString("path", ""),
                    mergedCount = obj.optInt("merged_count", 1)
                )
            }
        } catch (e: Exception) {
            Log.e("MemoryRegionScanner", "Error reading memory maps via JNI", e)
            emptyList()
        }
    }

    suspend fun readMem(
        pid: Int,
        addr: Long,
        valueType: String,
    ): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val res = NativeBridge.readMemory(pid, addr, valueType)
            if (res == "error") {
                throw IOException("Failed to read memory")
            }
            res
        }
    }

    suspend fun writeMem(
        pid: Int,
        address: Long,
        datatype: String,
        value: String,
    ): Result<Unit> = runCatching {
        val normalized = datatype.lowercase().trim()
        when {
            normalized.startsWith("obscured") || normalized.startsWith("actk") || normalized.startsWith("xor") -> {
                writeObscured(pid, address, value, datatype).getOrThrow()
            }
            normalized == "big_double" || normalized == "bigdouble" -> {
                writeBigDouble(pid, address, value).getOrThrow()
            }
            else -> {
                withContext(Dispatchers.IO) {
                    val res = NativeBridge.writeMemory(pid, address, value, datatype)
                    if (res != 0) {
                        throw IOException("Failed to write memory ($datatype) at 0x${address.toString(16)}")
                    }
                }
            }
        }
    }

    suspend fun writeObscured(
        pid: Int,
        address: Long,
        value: String,
        obscuredType: String,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val res = NativeBridge.writeObscured(pid, address, value, obscuredType)
            if (res != 0) {
                throw IOException("Failed to write obscured memory ($obscuredType) at 0x${address.toString(16)}")
            }
        }
    }

    suspend fun writeBigDouble(
        pid: Int,
        address: Long,
        value: String,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val res = NativeBridge.writeBigDouble(pid, address, value)
            if (res != 0) {
                throw IOException("Failed to write BigDouble memory at 0x${address.toString(16)}")
            }
        }
    }

    suspend fun search(
        pid: Int,
        sessionId: String,
        value: String,
        dataType: String,
        regions: List<MemoryMapEntry>,
        operator: String = "equal",
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val rJson = regionsToJson(regions)
            val resJson = NativeBridge.scanMemory(pid, sessionId, value, dataType, rJson, operator)
            parseMatchesFromJson(resJson, pid, dataType)
        }
    }

    suspend fun searchRange(
        pid: Int,
        sessionId: String,
        valueMin: String,
        valueMax: String,
        dataType: String,
        regions: List<MemoryMapEntry>,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val rJson = regionsToJson(regions)
            val resJson = NativeBridge.scanRange(pid, sessionId, valueMin, valueMax, dataType, rJson)
            parseMatchesFromJson(resJson, pid, dataType)
        }
    }

    suspend fun searchGroup(
        pid: Int,
        sessionId: String,
        value: String,
        dataType: String,
        regions: List<MemoryMapEntry>,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val rJson = regionsToJson(regions)
            val resJson = NativeBridge.scanGroup(pid, sessionId, value, dataType, rJson)
            parseMatchesFromJson(resJson, pid, dataType)
        }
    }

    suspend fun searchObscured(
        pid: Int,
        sessionId: String,
        value: String,
        obscuredType: String,
        regions: List<MemoryMapEntry>,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val rJson = regionsToJson(regions)
            val resJson = NativeBridge.scanObscured(pid, sessionId, value, obscuredType, rJson)
            val defaultType = when (obscuredType.lowercase()) {
                "obscured_float" -> "float"
                "obscured_double" -> "double"
                "obscured_long" -> "long"
                else -> "int"
            }
            parseMatchesFromJson(resJson, pid, defaultType)
        }
    }

    suspend fun searchBigDouble(
        pid: Int,
        sessionId: String,
        value: String,
        regions: List<MemoryMapEntry>,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val rJson = regionsToJson(regions)
            val resJson = NativeBridge.scanBigDouble(pid, sessionId, value, rJson)
            parseMatchesFromJson(resJson, pid, "double")
        }
    }

    suspend fun filterAddressesAuto(
        pid: Int,
        sessionId: String,
        fallbackType: String,
        targetValue: String,
        operator: String,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val resJson = NativeBridge.filterMatches(pid, sessionId, targetValue, operator)
            parseMatchesFromJson(resJson, pid, fallbackType)
        }
    }

    suspend fun filterObscuredAuto(
        pid: Int,
        sessionId: String,
        fallbackType: String,
        targetValue: String,
        obscuredType: String,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val resJson = NativeBridge.filterObscuredMatches(pid, sessionId, targetValue, obscuredType)
            parseMatchesFromJson(resJson, pid, fallbackType)
        }
    }

    suspend fun filterBigDoubleAuto(
        pid: Int,
        sessionId: String,
        targetValue: String,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val resJson = NativeBridge.filterBigDoubleMatches(pid, sessionId, targetValue)
            parseMatchesFromJson(resJson, pid, "double")
        }
    }

    suspend fun filterRangeAuto(
        pid: Int,
        sessionId: String,
        fallbackType: String,
        targetValueMin: String,
        targetValueMax: String,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val resJson = NativeBridge.filterRangeMatches(pid, sessionId, targetValueMin, targetValueMax)
            parseMatchesFromJson(resJson, pid, fallbackType)
        }
    }

    /**
     * Filters group scan matches against target values.
     */
    suspend fun filterGroupAddressesAuto(
        pid: Int,
        sessionId: String,
        fallbackType: String,
        targetValues: List<String>,
        operator: String,
    ): Result<Pair<Int, List<MatchInfo>>> = runCatching {
        withContext(Dispatchers.IO) {
            val targetVal = targetValues.firstOrNull() ?: ""
            filterAddressesAuto(pid, sessionId, fallbackType, targetVal, operator).getOrThrow()
        }
    }

    // === HELPER FUNCTIONS ===

    private fun regionsToJson(regions: List<MemoryMapEntry>): String {
        val arr = JSONArray()
        for (r in regions) {
            val obj = JSONObject()
            obj.put("start", r.start)
            obj.put("end", r.end)
            obj.put("permissions", r.permissions)
            obj.put("offset", r.offset)
            obj.put("path", r.path)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseMatchesFromJson(
        json: String,
        pid: Int,
        defaultType: String,
    ): Pair<Int, List<MatchInfo>> {
        val results = mutableListOf<MatchInfo>()
        var count = 0
        try {
            val root = JSONObject(json)
            if (root.has("error")) {
                Log.e(TAG, "Rust engine returned error: ${root.getString("error")}")
                return Pair(0, emptyList())
            }
            count = root.optInt("count", 0)
            if (!root.has("matches")) return Pair(count, emptyList())

            val arr = root.getJSONArray("matches")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val address = obj.getLong("address")
                val valueStr = obj.optString("value", "")
                val vtype = obj.optString("value_type", defaultType)
                val regionStart = obj.optLong("region_start", 0L)
                val regionEnd = obj.optLong("region_end", 0L)
                val perms = obj.optString("permissions", "")
                val path = obj.optString("path", "")

                val parsedValue: Any = when (vtype.lowercase()) {
                    "byte" -> valueStr.toByteOrNull() ?: 0.toByte()
                    "short" -> valueStr.toShortOrNull() ?: 0.toShort()
                    "float16" -> valueStr.toFloatOrNull() ?: 0.0f
                    "int" -> valueStr.toIntOrNull() ?: 0
                    "long" -> valueStr.toLongOrNull() ?: 0L
                    "float" -> valueStr.toFloatOrNull() ?: 0.0f
                    "double" -> valueStr.toDoubleOrNull() ?: 0.0
                    else -> valueStr
                }

                val size = when (vtype.lowercase()) {
                    "byte" -> 1
                    "short", "float16" -> 2
                    "int", "float" -> 4
                    "long", "double" -> 8
                    else -> 4
                }

                results.add(
                    MatchInfo(
                        id = UUID.randomUUID().toString(),
                        pid = pid,
                        address = address,
                        prevValue = parsedValue,
                        valueType = vtype,
                        size = size,
                        memoryRegion = path,
                        regionType = determineRegionType(path),
                        regionStart = regionStart,
                        regionEnd = regionEnd,
                        permissions = perms,
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Rust matches JSON: $e")
        }
        return Pair(count, results)
    }

    @SuppressLint("SdCardPath")
    fun determineRegionType(path: String): String {
        return when {
            path.isEmpty() || (path.startsWith("[anon:") &&
                    !path.contains("libc_malloc") && !path.contains("scudo") &&
                    !path.contains("jemalloc") && !path.contains("GWP-ASan") &&
                    !path.contains(".bss") && !path.contains("dalvik") &&
                    !path.contains("art_") && !path.contains("ashmem")) -> "ANONYMOUS"
            
            path.contains("[anon:libc_malloc]") || path.contains("[anon:scudo:") ||
                    path.contains("[anon:GWP-ASan]") || path.contains("[anon:jemalloc]") ||
                    path.contains("[anon:malloc]") -> "ALLOC"
            
            path.contains("[anon:.bss]") || path.contains("[anon:bss]") -> "BSS"
            
            (path.contains("/data/app/") || path.contains("/data/data/") ||
                    path.contains("/data/user/") || path.contains("/data/user_de/") ||
                    path.contains("/mnt/expand/")) &&
                    !path.endsWith(".so") && !path.endsWith(".apk") &&
                    !path.endsWith(".odex") && !path.endsWith(".oat") &&
                    !path.endsWith(".vdex") && !path.endsWith(".art") &&
                    !path.endsWith(".jar") -> "DATA"
            
            path.contains("[heap]") -> "HEAP"
            
            path.contains("/dev/ashmem/dalvik") || path.contains("[anon:dalvik-") ||
                    path.contains("[anon:art_") || path.contains("[anon:main space]") ||
                    path.contains("[anon:alloc space]") || path.contains("[anon:zygote") ||
                    path.contains("[anon:large object space]") || path.contains("[anon:non moving space]") -> "JAVA_HEAP"

            path.contains("[stack]") || path.contains("[stack:") -> "STACK"
            
            path.contains("/dev/ashmem") || path.contains("[anon:ashmem]") -> "ASHMEM"
            
            path.endsWith(".so") || path.endsWith(".apk") || path.endsWith(".odex") ||
                    path.endsWith(".oat") || path.endsWith(".vdex") || path.endsWith(".art") ||
                    path.endsWith(".jar") -> "LIBS"
            
            else -> "UNKNOWN"
        }
    }

    suspend fun resolvePointerChain(
        pid: Int,
        baseExpr: String,
        offsets: List<Long>
    ): Result<Long> = runCatching {
        withContext(Dispatchers.IO) {
            val offsetsJson = JSONArray(offsets).toString()
            val addr = NativeBridge.resolvePointerChain(pid, baseExpr, offsetsJson)
            if (addr <= 0L) {
                throw IOException("Failed to resolve pointer chain '$baseExpr' -> $offsets")
            }
            addr
        }
    }

    /**
     * Parses complex address expressions including:
     * - Direct Hex: "0x7b123400"
     * - Module Offset: "libil2cpp.so+0x1234"
     * - Pointer Dereference: "[0x7b123400]+0x20" or "[libil2cpp.so+0x1234]+0x20+0x48"
     * - Simple addition: "0x7b123400+0x48"
     */
    suspend fun parseAddressExpression(pid: Int, expr: String): Long? {
        val s = expr.trim()
        if (s.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            try {
                // 1. Pointer Dereference syntax: [Base]+off1+off2...
                if (s.startsWith("[") && s.contains("]")) {
                    val closeIdx = s.indexOf(']')
                    val baseInside = s.substring(1, closeIdx).trim()
                    val trailing = s.substring(closeIdx + 1).trim()

                    // Resolve baseInside (which could be direct hex or module+offset)
                    val baseAddr = parseAddressExpression(pid, baseInside) ?: return@withContext null

                    val offsets = mutableListOf<Long>()
                    if (trailing.isNotEmpty()) {
                        // Parse signed offsets based on + / -
                        var currentIdx = 0
                        while (currentIdx < trailing.length) {
                            val sign = if (trailing[currentIdx] == '-') -1L else 1L
                            val nextSignIdx = trailing.indexOfAny(charArrayOf('+', '-'), currentIdx + 1).let {
                                if (it == -1) trailing.length else it
                            }
                            val offStr = trailing.substring(currentIdx + 1, nextSignIdx).trim()
                            if (offStr.isNotEmpty()) {
                                val offVal = if (offStr.startsWith("0x", ignoreCase = true)) {
                                    offStr.substring(2).toLongOrNull(16) ?: 0L
                                } else {
                                    offStr.toLongOrNull() ?: 0L
                                }
                                offsets.add(sign * offVal)
                            }
                            currentIdx = nextSignIdx
                        }
                    }

                    if (offsets.isEmpty()) {
                        // Just single dereference [base]
                        offsets.add(0L)
                    }

                    resolvePointerChain(pid, "0x${baseAddr.toString(16)}", offsets).getOrNull()
                } else if (s.contains("+")) {
                    // 2. Module + offset or Address + offset
                    val parts = s.split("+")
                    if (parts.size == 2) {
                        val left = parts[0].trim()
                        val right = parts[1].trim()

                        val base: Long = (if (left.startsWith("0x", ignoreCase = true)) {
                            left.substring(2).toLongOrNull(16)
                        } else if (left.contains(".so") || left.contains(".apk") || left.contains(".art") || left.startsWith("lib", ignoreCase = true)) {
                            NativeBridge.getModuleBase(pid, left).takeIf { it > 0 }
                        } else {
                            left.toLongOrNull(16)
                        }) ?: return@withContext null

                        val offset: Long = (if (right.startsWith("0x", ignoreCase = true)) {
                            right.substring(2).toLongOrNull(16)
                        } else {
                            right.toLongOrNull()
                        }) ?: return@withContext null

                        base + offset
                    } else {
                        null
                    }
                } else if (s.contains(".so") || s.contains(".apk") || s.contains(".art") || s.startsWith("lib", ignoreCase = true)) {
                    // 3. Module base alone
                    NativeBridge.getModuleBase(pid, s).takeIf { it > 0 }
                } else {
                    // 4. Direct address
                    if (s.startsWith("0x", ignoreCase = true)) {
                        s.substring(2).toLongOrNull(16)
                    } else {
                        s.toLongOrNull(16)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating address expression '$expr': ${e.message}")
                null
            }
        }
    }

    suspend fun runLuaScript(
        pid: Int,
        script: String
    ): Result<LuaExecutionResult> = runCatching {
        withContext(Dispatchers.IO) {
            val callback = com.yervant.huntmem.ui.overlay.tabs.LuaUiBridge.asAidlCallback()
            val json = NativeBridge.runLuaScript(pid, script, callback)
            val obj = JSONObject(json)
            val success = obj.optBoolean("success", false)
            val output = obj.optString("output", "")
            val error = if (!obj.isNull("error") && obj.has("error")) obj.optString("error").takeIf { it.isNotEmpty() } else null
            val result = if (!obj.isNull("result") && obj.has("result")) obj.optString("result").takeIf { it.isNotEmpty() } else null
            LuaExecutionResult(
                success = success,
                output = output,
                error = error,
                result = result
            )
        }
    }

    fun stopLuaScript() {
        NativeBridge.cancelLuaScript()
    }
}
