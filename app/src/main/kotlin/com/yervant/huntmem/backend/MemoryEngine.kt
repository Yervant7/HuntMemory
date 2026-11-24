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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
    ) {
        fun toMapFlags(): Int {
            var flags = 0
            if (requireRead) flags = flags or NativeBridge.MAP_FLAG_REQUIRE_READ
            if (requireWrite) flags = flags or NativeBridge.MAP_FLAG_REQUIRE_WRITE
            if (includeSwapped) flags = flags or NativeBridge.MAP_FLAG_INCLUDE_SWAPPED
            return flags
        }
    }

    @Keep
    data class LuaExecutionResult(
        val success: Boolean,
        val output: String,
        val error: String? = null,
        val result: String? = null
    )

    var globalMapOptions = MemoryMapOptions()

    enum class ScanEngineMode(val code: Int) {
        RUST_NEON(0),
        KERNEL(1),
        AUTO(2);

        companion object {
            fun fromCode(code: Int): ScanEngineMode = when (code) {
                1 -> KERNEL
                2 -> AUTO
                else -> RUST_NEON
            }
        }
    }

    var globalScanEngineMode: ScanEngineMode = ScanEngineMode.RUST_NEON
        set(value) {
            field = value
            NativeBridge.setScanEngineMode(value.code)
        }

    fun updateScanEngineMode(mode: ScanEngineMode) {
        globalScanEngineMode = mode
    }

    enum class MemoryRegionType(val code: String, val title: String) {
        ANONYMOUS("A", "Anonymous"),
        ALLOC("CA", "C++ Alloc (malloc, scudo...)"),
        BSS("CB", "C++ BSS"),
        DATA("CD", "C++ Data"),
        HEAP("CH", "C++ Heap"),
        JAVA_HEAP("JH", "Java Heap"),
        STACK("S", "Stack"),
        ASHMEM("AS", "Ashmem"),
        NATIVE_LIBS("NL", "Native Libraries"),
        FILES("F", "Files"),
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
            val custom = customFilter ?: ""
            val binaryBytes = NativeBridge.getMemoryMapsBinary(
                pid = pid,
                requireRead = options.requireRead,
                requireWrite = options.requireWrite,
                includeSwapped = options.includeSwapped,
                minSize = options.minSize,
                filterTypes = filterTypesStr,
                custom = custom
            )
            if (binaryBytes != null && binaryBytes.isNotEmpty()) {
                NativeBridge.decodeMemoryRegions(binaryBytes)
            } else {
                emptyList()
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
    ): Result<String> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val res = NativeBridge.readMemory(pid, addr, valueType)
            if (res == "error" || res.isEmpty()) {
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
    ): Result<Unit> {
        val normalized = datatype.lowercase().trim()
        return when {
            normalized.startsWith("obscured") || normalized.startsWith("actk") || normalized.startsWith("xor") ->
                writeObscured(pid, address, value, datatype)
            normalized == "big_double" || normalized == "bigdouble" ->
                writeBigDouble(pid, address, value)
            else -> runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    if (NativeBridge.getHmkpmVersionInfo().isLockless) {
                        throw IOException("Memory write blocked: HMKPM Lockless Mode is active (kernel mmap_lock unavailable)")
                    }
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
    ): Result<Unit> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            if (NativeBridge.getHmkpmVersionInfo().isLockless) {
                throw IOException("Memory write blocked: HMKPM Lockless Mode is active (kernel mmap_lock unavailable)")
            }
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
    ): Result<Unit> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            if (NativeBridge.getHmkpmVersionInfo().isLockless) {
                throw IOException("Memory write blocked: HMKPM Lockless Mode is active (kernel mmap_lock unavailable)")
            }
            val res = NativeBridge.writeBigDouble(pid, address, value)
            if (res != 0) {
                throw IOException("Failed to write BigDouble memory at 0x${address.toString(16)}")
            }
        }
    }

    // === FAST BINARY & ZERO-JSON PAGINATED ENGINE APIS ===

    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

    fun cancelScan() {
        NativeBridge.cancelScan()
    }

    suspend fun searchFast(
        pid: Int,
        sessionId: String,
        value: String,
        dataType: String,
        filterTypes: String = "",
        customFilter: String = "",
        operator: String = "equal",
        limit: Int = 100,
        options: MemoryMapOptions = globalMapOptions
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.scanMemoryFast(
                pid,
                sessionId,
                value,
                dataType,
                filterTypes,
                customFilter,
                operator,
                options.toMapFlags(),
                options.minSize
            )
            if (count < 0) throw IOException("Fast memory scan failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun searchRangeFast(
        pid: Int,
        sessionId: String,
        valueMin: String,
        valueMax: String,
        dataType: String,
        filterTypes: String = "",
        customFilter: String = "",
        limit: Int = 100,
        options: MemoryMapOptions = globalMapOptions
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.scanRangeFast(
                pid,
                sessionId,
                valueMin,
                valueMax,
                dataType,
                filterTypes,
                customFilter,
                options.toMapFlags(),
                options.minSize
            )
            if (count < 0) throw IOException("Fast range scan failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun searchGroupFast(
        pid: Int,
        sessionId: String,
        value: String,
        dataType: String,
        filterTypes: String = "",
        customFilter: String = "",
        limit: Int = 100,
        options: MemoryMapOptions = globalMapOptions
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.scanGroupFast(
                pid,
                sessionId,
                value,
                dataType,
                filterTypes,
                customFilter,
                options.toMapFlags(),
                options.minSize
            )
            if (count < 0) throw IOException("Fast group scan failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun searchObscuredFast(
        pid: Int,
        sessionId: String,
        value: String,
        obscuredType: String,
        filterTypes: String = "",
        customFilter: String = "",
        limit: Int = 100,
        options: MemoryMapOptions = globalMapOptions
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.scanObscuredFast(
                pid,
                sessionId,
                value,
                obscuredType,
                filterTypes,
                customFilter,
                options.toMapFlags(),
                options.minSize
            )
            if (count < 0) throw IOException("Fast obscured scan failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun searchBigDoubleFast(
        pid: Int,
        sessionId: String,
        value: String,
        filterTypes: String = "",
        customFilter: String = "",
        limit: Int = 100,
        options: MemoryMapOptions = globalMapOptions
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.scanBigDoubleFast(
                pid,
                sessionId,
                value,
                filterTypes,
                customFilter,
                options.toMapFlags(),
                options.minSize
            )
            if (count < 0) throw IOException("Fast BigDouble scan failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun filterAddressesFast(
        pid: Int,
        sessionId: String,
        targetValue: String,
        operator: String = "equal",
        limit: Int = 100
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.filterMatchesFast(pid, sessionId, targetValue, operator)
            if (count < 0) throw IOException("Fast filter matches failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun filterRangeFast(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String,
        limit: Int = 100
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.filterRangeMatchesFast(pid, sessionId, minValue, maxValue)
            if (count < 0) throw IOException("Fast range filter failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun filterObscuredFast(
        pid: Int,
        sessionId: String,
        targetValue: String,
        obscuredType: String,
        limit: Int = 100
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.filterObscuredMatchesFast(pid, sessionId, targetValue, obscuredType)
            if (count < 0) throw IOException("Fast obscured filter failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun filterBigDoubleFast(
        pid: Int,
        sessionId: String,
        targetValue: String,
        limit: Int = 100
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.filterBigDoubleMatchesFast(pid, sessionId, targetValue)
            if (count < 0) throw IOException("Fast BigDouble filter failed")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun getResultsPage(
        pid: Int,
        sessionId: String,
        offset: Int,
        limit: Int
    ): Pair<Int, List<MatchInfo>> = withContext(Dispatchers.IO) {
        val bytes = NativeBridge.getResultsPage(sessionId, offset, limit)
        NativeBridge.decodeMatchesPage(sessionId, bytes, pid)
    }

    suspend fun refreshResultsPage(
        pid: Int,
        sessionId: String,
        offset: Int,
        limit: Int
    ): Pair<Int, List<MatchInfo>> = withContext(Dispatchers.IO) {
        val bytes = NativeBridge.refreshResultsPage(pid, sessionId, offset, limit)
        NativeBridge.decodeMatchesPage(sessionId, bytes, pid)
    }

    suspend fun saveSessionCheckpoint(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        NativeBridge.saveSessionCheckpoint(sessionId)
    }

    suspend fun getSessionUndoCount(sessionId: String): Int = withContext(Dispatchers.IO) {
        NativeBridge.getSessionUndoCount(sessionId)
    }

    suspend fun isSessionSaved(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        NativeBridge.isSessionSaved(sessionId)
    }

    suspend fun undoSessionFast(
        pid: Int,
        sessionId: String,
        limit: Int = 100
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.undoSession(sessionId)
            if (count < 0) throw IOException("No refinement checkpoint available to undo")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(sessionId, 0, limit)
            NativeBridge.decodeMatchesPage(sessionId, pageBytes, pid)
        }
    }

    suspend fun cloneSessionFast(
        pid: Int,
        sourceSessionId: String,
        targetSessionId: String,
        limit: Int = 100
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.cloneSession(sourceSessionId, targetSessionId)
            if (count < 0) throw IOException("Failed to clone search session")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(targetSessionId, 0, limit)
            NativeBridge.decodeMatchesPage(targetSessionId, pageBytes, pid)
        }
    }

    suspend fun copyMatchesToSessionFast(
        pid: Int,
        sourceSessionId: String,
        targetSessionId: String,
        addresses: LongArray,
        limit: Int = 100
    ): Result<Pair<Int, List<MatchInfo>>> = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val count = NativeBridge.copyMatchesToSession(sourceSessionId, targetSessionId, addresses)
            if (count < 0) throw IOException("Failed to copy selected matches to target tab")
            if (count == 0) return@withContext Pair(0, emptyList())
            val pageBytes = NativeBridge.getResultsPage(targetSessionId, 0, limit)
            NativeBridge.decodeMatchesPage(targetSessionId, pageBytes, pid)
        }
    }

    // === HELPER FUNCTIONS ===

    @SuppressLint("SdCardPath")
    private fun isDataRegion(path: String): Boolean {
        return (path.startsWith("/data/app/") ||
                path.startsWith("/data/data/") ||
                path.startsWith("/data/user/") ||
                path.startsWith("/data/user_de/") ||
                path.startsWith("/mnt/expand/")) &&
                !path.endsWith(".so")
    }

    private fun isFileBackedRegion(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty() || p.startsWith("[")) return false
        if (p.startsWith("/dev/") || p.startsWith("/memfd:")) return false
        return p.startsWith("/")
    }

    /**
     * Checks whether a memory map path belongs to the given [MemoryRegionType],
     * mirroring `maps.rs::match_filter_type_upper` in the native engine.
     */
    fun matchesRegionType(path: String, region: MemoryRegionType): Boolean {
        return when (region) {
            MemoryRegionType.ALLOC ->
                path.contains("[anon:libc_malloc]") || path.contains("[anon:scudo:") ||
                        path.contains("[anon:GWP-ASan]") || path.contains("[anon:jemalloc]") ||
                        path.contains("[anon:malloc]")

            MemoryRegionType.BSS ->
                path.contains("[anon:.bss]") || path.contains("[anon:bss]")

            MemoryRegionType.DATA ->
                isDataRegion(path)

            MemoryRegionType.HEAP ->
                path == "[heap]" || path.startsWith("[heap")

            MemoryRegionType.JAVA_HEAP ->
                path.contains("/dev/ashmem/dalvik") || path.contains("[anon:dalvik-") ||
                        path.contains("[anon:art_") || path.contains("[anon:main space]") ||
                        path.contains("[anon:alloc space]") || path.contains("[anon:region space]") ||
                        path.contains("[anon:zygote") || path.contains("[anon:large object space]") ||
                        path.contains("[anon:non moving space]")

            MemoryRegionType.ANONYMOUS ->
                path.isEmpty() || (path.startsWith("[anon:") &&
                        !path.contains("libc_malloc") && !path.contains("scudo") &&
                        !path.contains("jemalloc") && !path.contains("GWP-ASan") &&
                        !path.contains(".bss") && !path.contains("dalvik") &&
                        !path.contains("art_") && !path.contains("ashmem"))

            MemoryRegionType.STACK ->
                path.contains("[stack]") || path.contains("[stack:")

            MemoryRegionType.ASHMEM ->
                path.contains("/dev/ashmem") || path.contains("[anon:ashmem]")

            MemoryRegionType.NATIVE_LIBS ->
                path.endsWith(".so")

            MemoryRegionType.FILES ->
                isFileBackedRegion(path)

            MemoryRegionType.CUSTOM ->
                false
        }
    }

    fun determineRegionType(path: String): String {
        return when {
            matchesRegionType(path, MemoryRegionType.ANONYMOUS) -> MemoryRegionType.ANONYMOUS.name
            matchesRegionType(path, MemoryRegionType.ALLOC) -> MemoryRegionType.ALLOC.name
            matchesRegionType(path, MemoryRegionType.BSS) -> MemoryRegionType.BSS.name
            matchesRegionType(path, MemoryRegionType.HEAP) -> MemoryRegionType.HEAP.name
            matchesRegionType(path, MemoryRegionType.JAVA_HEAP) -> MemoryRegionType.JAVA_HEAP.name
            matchesRegionType(path, MemoryRegionType.STACK) -> MemoryRegionType.STACK.name
            matchesRegionType(path, MemoryRegionType.ASHMEM) -> MemoryRegionType.ASHMEM.name
            matchesRegionType(path, MemoryRegionType.NATIVE_LIBS) -> MemoryRegionType.NATIVE_LIBS.name
            matchesRegionType(path, MemoryRegionType.DATA) -> MemoryRegionType.DATA.name
            matchesRegionType(path, MemoryRegionType.FILES) -> MemoryRegionType.FILES.name
            else -> "UNKNOWN"
        }
    }

    suspend fun resolvePointerChain(
        pid: Int,
        baseExpr: String,
        offsets: List<Long>
    ): Result<Long> = runCatchingCancellable {
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
     * - Direct Hex: "0x7b123400" or "@0x7b123400"
     * - Module Offset: "libnative.so+0x1234"
     * - Pointer Dereference: "[0x7b123400]+0x20" or "[libnative.so+0x1234]+0x20+0x48"
     * - Simple addition: "0x7b123400+0x48"
     */
    suspend fun parseAddressExpression(pid: Int, expr: String): Long? {
        val s = expr.trim().removePrefix("@").trim()
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
    ): Result<LuaExecutionResult> = try {
        val executionResult = withContext(Dispatchers.IO) {
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
        Result.success(executionResult)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun stopLuaScript() {
        com.yervant.huntmem.ui.overlay.tabs.LuaUiBridge.cancelPendingDialog()
        NativeBridge.cancelLuaScript()
    }
}
