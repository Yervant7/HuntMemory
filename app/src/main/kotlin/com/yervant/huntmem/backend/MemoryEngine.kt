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
        val offset: Long,
        val device: String,
        val inode: Long,
        val path: String
    )

    @Keep
    data class MemoryMapOptions(
        val requireRead: Boolean = true,
        val requireWrite: Boolean = false,
        val includeSwapped: Boolean = true,
        val minSize: Long = 0L
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
                    path = obj.optString("path", "")
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
        withContext(Dispatchers.IO) {
            val normalized = datatype.lowercase().trim()
            val res = when {
                normalized.startsWith("obscured") || normalized.startsWith("actk") || normalized.startsWith("xor") -> {
                    NativeBridge.writeObscured(pid, address, value, datatype)
                }
                normalized == "big_double" || normalized == "bigdouble" -> {
                    NativeBridge.writeBigDouble(pid, address, value)
                }
                else -> {
                    NativeBridge.writeMemory(pid, address, value, datatype)
                }
            }
            if (res != 0) {
                throw IOException("Failed to write memory ($datatype) at 0x${address.toString(16)}")
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
}



