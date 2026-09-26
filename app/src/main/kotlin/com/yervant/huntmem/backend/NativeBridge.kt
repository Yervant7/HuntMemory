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
import android.content.Context
import android.util.Log
import dalvik.annotation.optimization.FastNative
import java.io.File

object NativeBridge {

    private const val LIB_NAME = "hmem_jni"
    private const val TAG = "NativeBridge"
    private var isLibraryLoaded = false

    /**
     * Loads the native library. Must be invoked ONLY in the root daemon process (HMemService).
     * Does NOT run or autoload in the normal app process.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibrary(context: Context? = null, customLibDir: String? = null) {
        if (isLibraryLoaded) return

        // 1. Try explicit directory passed from App process via Intent
        if (!customLibDir.isNullOrBlank()) {
            try {
                val libFile = File(customLibDir, "lib$LIB_NAME.so")
                if (libFile.exists()) {
                    System.load(libFile.absolutePath)
                    isLibraryLoaded = true
                    Log.i(TAG, "Native library loaded from customLibDir: ${libFile.absolutePath}")
                    return
                } else {
                    Log.w(TAG, "lib$LIB_NAME.so not found in customLibDir: $customLibDir")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed loading from customLibDir ($customLibDir): ${e.message}")
            }
        }

        // 2. Try context.applicationInfo.nativeLibraryDir
        if (context != null) {
            try {
                val libDir = context.applicationInfo.nativeLibraryDir
                if (!libDir.isNullOrBlank()) {
                    val libFile = File(libDir, "lib$LIB_NAME.so")
                    if (libFile.exists()) {
                        System.load(libFile.absolutePath)
                        isLibraryLoaded = true
                        Log.i(TAG, "Native library loaded from nativeLibraryDir: ${libFile.absolutePath}")
                        return
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed loading from context.applicationInfo.nativeLibraryDir: ${e.message}")
            }
        }

        // 3. Fallback to standard System.loadLibrary
        try {
            System.loadLibrary(LIB_NAME)
            isLibraryLoaded = true
            Log.i(TAG, "Native library '$LIB_NAME' loaded via System.loadLibrary")
            return
        } catch (e: Throwable) {
            Log.w(TAG, "System.loadLibrary($LIB_NAME) failed: ${e.message}")
        }
    }

    /** returns "error" in error */
    @FastNative
    external fun nativeReadMemory(
        pid: Int,
        address: Long,
        valueType: String
    ): String
    fun readMemory(
        pid: Int,
        address: Long,
        valueType: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeReadMemory(pid, address, valueType)
            } catch (_: Exception) {
                "error"
            }
        } else {
            "error"
        }
    }

    /** Write typed value. Returns 0 success, -1 error */
    @FastNative
    external fun nativeWriteMemory(
        pid: Int,
        address: Long,
        value: String,
        valueType: String
    ): Int
    fun writeMemory(
        pid: Int,
        address: Long,
        value: String,
        valueType: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeWriteMemory(pid, address, value, valueType)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    data class HmkpmVersion(
        val available: Boolean,
        val versionStr: String = "",
        val versionCode: Int = 0,
        val features: Long = 0L,
        val isLockless: Boolean = false,
        val mmapLockOffset: Int = -1,
        val supportsRead: Boolean = false,
        val supportsWrite: Boolean = false,
        val supportsReadBatch: Boolean = false,
        val supportsWriteBatch: Boolean = false,
        val supportsV2pBatch: Boolean = false,
        val supportsKernelScan: Boolean = false,
        val supportsLockless: Boolean = false,
        val error: String? = null
    )

    /** Checks if the HMKPM KernelPatch module is available and responsive. */
    @JvmStatic
    external fun nativeIsHmkpmAvailable(): Boolean
    fun isHmkpmAvailable(): Boolean {
        val s = HMemServiceConnection.service
        if (s != null) {
            try {
                return s.nativeIsHmkpmAvailable()
            } catch (e: Exception) {
                Log.w(TAG, "nativeIsHmkpmAvailable on root service failed: ${e.message}")
            }
        }
        return false
    }

    /** Retrieves detailed HMKPM version and supported features metadata. */
    @FastNative
    external fun nativeGetHmkpmVersionInfo(): String
    fun getHmkpmVersionInfo(): HmkpmVersion {
        val s = HMemServiceConnection.service
        val jsonStr = if (s != null) {
            try {
                s.nativeGetHmkpmVersionInfo()
            } catch (e: Exception) {
                Log.w(TAG, "nativeGetHmkpmVersionInfo on root service failed: ${e.message}")
                ""
            }
        } else {
            ""
        }

        if (jsonStr.isBlank()) {
            return HmkpmVersion(available = false, error = "Service disconnected")
        }

        return try {
            val obj = org.json.JSONObject(jsonStr)
            val available = obj.optBoolean("available", false)
            if (!available) {
                HmkpmVersion(available = false, error = obj.optString("error", "Not available"))
            } else {
                HmkpmVersion(
                    available = true,
                    versionStr = obj.optString("version", "2.8.0"),
                    versionCode = obj.optInt("version_code", 0),
                    features = obj.optLong("features", 0L),
                    isLockless = obj.optBoolean("is_lockless", false),
                    mmapLockOffset = obj.optInt("mmap_lock_offset", -1),
                    supportsRead = obj.optBoolean("supports_read", false),
                    supportsWrite = obj.optBoolean("supports_write", false),
                    supportsReadBatch = obj.optBoolean("supports_read_batch", false),
                    supportsWriteBatch = obj.optBoolean("supports_write_batch", false),
                    supportsV2pBatch = obj.optBoolean("supports_v2p_batch", false),
                    supportsKernelScan = obj.optBoolean("supports_kernel_scan", false),
                    supportsLockless = obj.optBoolean("supports_lockless", false)
                )
            }
        } catch (e: Exception) {
            HmkpmVersion(available = false, error = e.message)
        }
    }

    /** Translates virtual addresses to physical addresses in batch via kernel page table walk. */
    @FastNative
    external fun nativeTranslateV2P(pid: Int, addresses: LongArray): LongArray?
    fun translateV2P(pid: Int, addresses: LongArray): LongArray? {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeTranslateV2P(pid, addresses)
            } catch (e: Exception) {
                Log.w(TAG, "nativeTranslateV2P failed: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    @FastNative
    external fun nativeGetMemoryMaps(
        pid: Int,
        requireRead: Boolean,
        requireWrite: Boolean,
        includeSwapped: Boolean,
        minSize: Long,
        filterTypes: String,
        custom: String
    ): String
    fun getMemoryMaps(
        pid: Int,
        requireRead: Boolean = true,
        requireWrite: Boolean = false,
        includeSwapped: Boolean = true,
        minSize: Long = 0L,
        filterTypes: String = "",
        custom: String = ""
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeGetMemoryMaps(
                    pid, requireRead, requireWrite, includeSwapped,
                    minSize,
                    filterTypes, custom
                )
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** First scan. Returns JSON {"matches":[...],"count":N} */
    @FastNative
    external fun nativeScanMemory(
        pid: Int,
        sessionId: String,
        value: String,
        valueType: String,
        regionsJson: String,
        operator: String
    ): String
    fun scanMemory(
        pid: Int,
        sessionId: String,
        value: String,
        valueType: String,
        regionsJson: String,
        operator: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanMemory(pid, sessionId, value, valueType, regionsJson, operator)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** Range scan. Returns JSON {"matches":[...],"count":N} */
    @FastNative
    external fun nativeScanRange(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String,
        valueType: String,
        regionsJson: String
    ): String
    fun scanRange(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String,
        valueType: String,
        regionsJson: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanRange(pid, sessionId, minValue, maxValue, valueType, regionsJson)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** Group scan. Returns JSON {"matches":[...],"count":N} */
    @FastNative
    external fun nativeScanGroup(
        pid: Int,
        sessionId: String,
        groupSpec: String,
        valueType: String,
        regionsJson: String
    ): String
    fun scanGroup(
        pid: Int,
        sessionId: String,
        groupSpec: String,
        valueType: String,
        regionsJson: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanGroup(pid, sessionId, groupSpec, valueType, regionsJson)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** Filter/next scan. Returns JSON {"matches":[...],"count":N} */
    @FastNative
    external fun nativeFilterMatches(
        pid: Int,
        sessionId: String,
        targetValue: String,
        operator: String
    ): String
    fun filterMatches(
        pid: Int,
        sessionId: String,
        targetValue: String,
        operator: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterMatches(pid, sessionId, targetValue, operator)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** Filter by range. Returns JSON {"matches":[...],"count":N} */
    @FastNative
    external fun nativeFilterRangeMatches(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String
    ): String
    fun filterRangeMatches(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterRangeMatches(pid, sessionId, minValue, maxValue)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    @FastNative
    external fun nativeClearSession(sessionId: String)
    fun clearSession(sessionId: String) {
        val s = HMemServiceConnection.service
        if (s != null) {
            try {
                s.nativeClearSession(sessionId)
            } catch (_: Exception) {
            }
        }
    }

    /** Batch write. Returns count of successful writes */
    @FastNative
    external fun nativeBatchWrite(
        pid: Int,
        writesJson: String
    ): Int
    fun batchWrite(
        pid: Int,
        writesJson: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeBatchWrite(pid, writesJson)
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
    }

    /** Batch write binary. Returns count of successful writes */
    @FastNative
    external fun nativeBatchWriteBinary(
        pid: Int,
        payload: ByteArray
    ): Int
    fun batchWriteBinary(
        pid: Int,
        payload: ByteArray
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeBatchWriteBinary(pid, payload)
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
    }

    fun encodeBatchWrites(writes: List<Pair<Long, ByteArray>>): ByteArray {
        var totalSize = 4
        for ((_, data) in writes) {
            totalSize += 8 + 2 + data.size
        }
        val buffer = java.nio.ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(writes.size)
        for ((addr, data) in writes) {
            buffer.putLong(addr)
            buffer.putShort(data.size.toShort())
            buffer.put(data)
        }
        return buffer.array()
    }

    fun valueStringToBytes(value: String, valueType: String): ByteArray? {
        val s = value.trim()
        val vt = valueType.uppercase()
        return try {
            when {
                vt == "BYTE" || vt == "I8" -> {
                    val num = s.decodeIntOrHex()
                    byteArrayOf(num.toByte())
                }
                vt == "SHORT" || vt == "WORD" || vt == "I16" -> {
                    val num = s.decodeIntOrHex()
                    val buf = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.putShort(num.toShort())
                    buf.array()
                }
                vt == "INT" || vt == "DWORD" || vt == "I32" || vt == "AUTO" -> {
                    val num = s.decodeLongOrHex()
                    val buf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.putInt(num.toInt())
                    buf.array()
                }
                vt == "LONG" || vt == "QWORD" || vt == "I64" || vt == "XOR" -> {
                    val num = s.decodeLongOrHex()
                    val buf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.putLong(num)
                    buf.array()
                }
                vt == "FLOAT16" || vt == "F16" || vt == "HALF" || vt == "H" -> {
                    val halfBits: Short = if (s.startsWith("0x", ignoreCase = true) || s.startsWith("-0x", ignoreCase = true)) {
                        s.decodeIntOrHex().toShort()
                    } else {
                        android.util.Half.toHalf(s.toFloat())
                    }
                    val buf = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.putShort(halfBits)
                    buf.array()
                }
                vt == "FLOAT" || vt == "F32" -> {
                    val num = s.toFloat()
                    val buf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.putFloat(num)
                    buf.array()
                }
                vt == "DOUBLE" || vt == "F64" -> {
                    val num = s.toDouble()
                    val buf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.putDouble(num)
                    buf.array()
                }
                vt == "HEX" -> {
                    val clean = s.replace(" ", "").removePrefix("0x").removePrefix("0X")
                    if (clean.length % 2 != 0) null
                    else ByteArray(clean.length / 2) { i ->
                        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }
                }
                vt == "TEXT" || vt == "UTF8" -> {
                    s.toByteArray(Charsets.UTF_8)
                }
                else -> {
                    val num = s.decodeLongOrHex()
                    val buf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.putInt(num.toInt())
                    buf.array()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.decodeIntOrHex(): Int {
        val s = this.trim()
        return if (s.startsWith("0x", ignoreCase = true)) {
            s.substring(2).toLong(16).toInt()
        } else if (s.startsWith("-0x", ignoreCase = true)) {
            -s.substring(3).toLong(16).toInt()
        } else {
            s.toInt()
        }
    }

    private fun String.decodeLongOrHex(): Long {
        val s = this.trim()
        return if (s.startsWith("0x", ignoreCase = true)) {
            s.substring(2).toULong(16).toLong()
        } else if (s.startsWith("-0x", ignoreCase = true)) {
            -s.substring(3).toULong(16).toLong()
        } else {
            s.toLong()
        }
    }

    /** Freeze address to value */
    @FastNative
    external fun nativeFreezeAddress(
        pid: Int,
        address: Long,
        value: String,
        valueType: String
    ): Int
    fun freezeAddress(
        pid: Int,
        address: Long,
        value: String,
        valueType: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFreezeAddress(pid, address, value, valueType)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    /** Unfreeze address */
    @FastNative
    external fun nativeUnfreezeAddress(
        pid: Int,
        address: Long
    ): Boolean
    fun unfreezeAddress(
        pid: Int,
        address: Long
    ): Boolean {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeUnfreezeAddress(pid, address)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    /** Unfreeze all addresses */
    @FastNative
    external fun nativeUnfreezeAll(): Int
    fun unfreezeAll(): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeUnfreezeAll()
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
    }

    /** Check if address is frozen */
    @FastNative
    external fun nativeIsAddressFrozen(
        pid: Int,
        address: Long
    ): Boolean
    fun isAddressFrozen(
        pid: Int,
        address: Long
    ): Boolean {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeIsAddressFrozen(pid, address)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    /** Scan for Obscured (XOR key-pair) types. Returns JSON {"matches":[...],"count":N} */
    @FastNative
    external fun nativeScanObscured(
        pid: Int,
        sessionId: String,
        value: String,
        obscuredType: String,
        regionsJson: String
    ): String
    fun scanObscured(
        pid: Int,
        sessionId: String,
        value: String,
        obscuredType: String,
        regionsJson: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanObscured(pid, sessionId, value, obscuredType, regionsJson)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** Scan for BigDouble (mantissa/exponent scientific structs). Returns JSON {"matches":[...],"count":N} */
    @FastNative
    external fun nativeScanBigDouble(
        pid: Int,
        sessionId: String,
        value: String,
        regionsJson: String
    ): String
    fun scanBigDouble(
        pid: Int,
        sessionId: String,
        value: String,
        regionsJson: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanBigDouble(pid, sessionId, value, regionsJson)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** Filter Obscured scan matches */
    @FastNative
    external fun nativeFilterObscuredMatches(
        pid: Int,
        sessionId: String,
        targetValue: String,
        obscuredType: String
    ): String
    fun filterObscuredMatches(
        pid: Int,
        sessionId: String,
        targetValue: String,
        obscuredType: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterObscuredMatches(pid, sessionId, targetValue, obscuredType)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /** Filter BigDouble scan matches */
    @FastNative
    external fun nativeFilterBigDoubleMatches(
        pid: Int,
        sessionId: String,
        targetValue: String
    ): String
    fun filterBigDoubleMatches(
        pid: Int,
        sessionId: String,
        targetValue: String
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterBigDoubleMatches(pid, sessionId, targetValue)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    /**
     * Writes an obscured value (XOR keypair) into process memory at the specified address.
     *
     * JNI Contract:
     * - `pid`: Target process ID (> 0).
     * - `address`: 64-bit virtual memory address.
     * - `value`: Non-null string representation of the desired decrypted value (e.g., "100", "99.5").
     * - `obscuredType`: Obscured type name ("obscured_int", "obscured_float", "obscured_double", "obscured_long").
     * - Returns 0 on success, -1 on failure.
     */
    @FastNative
    external fun nativeWriteObscured(
        pid: Int,
        address: Long,
        value: String,
        obscuredType: String
    ): Int

    /**
     * High-level wrapper to write an obscured value via root IPC service.
     *
     * @param pid Target process ID.
     * @param address 64-bit virtual memory address.
     * @param value Target decrypted value as string.
     * @param obscuredType Type identifier ("obscured_int", "obscured_float", "obscured_double", "obscured_long").
     * @return 0 on success, -1 on failure or if root service is disconnected.
     */
    fun writeObscured(
        pid: Int,
        address: Long,
        value: String,
        obscuredType: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeWriteObscured(pid, address, value, obscuredType)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    /**
     * Writes a BigDouble scientific notation struct (mantissa & exponent) into process memory.
     *
     * JNI Contract:
     * - `pid`: Target process ID (> 0).
     * - `address`: 64-bit virtual memory address.
     * - `value`: Non-null scientific notation string (e.g. "1.5e10", "1500000").
     * - Returns 0 on success, -1 on failure.
     */
    @FastNative
    external fun nativeWriteBigDouble(
        pid: Int,
        address: Long,
        value: String
    ): Int

    /**
     * High-level wrapper to write a BigDouble struct via root IPC service.
     *
     * @param pid Target process ID.
     * @param address 64-bit virtual memory address.
     * @param value Scientific notation string (e.g. "1.5e10", "1500000").
     * @return 0 on success, -1 on failure or if root service is disconnected.
     */
    fun writeBigDouble(
        pid: Int,
        address: Long,
        value: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeWriteBigDouble(pid, address, value)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    /**
     * Executes a Lua script with full process memory access.
     *
     * JNI Contract:
     * - `pid`: Target process ID (> 0).
     * - `script`: Non-null string containing Lua script code.
     * - `callback`: Optional ILuaUiCallback instance for interactive UI/canvas/console bridging.
     * - Returns JSON string: `{"success":bool,"output":string,"error":string|null,"result":string|null}`.
     */
    @FastNative
    external fun nativeRunLuaScript(
        pid: Int,
        script: String,
        callback: com.yervant.huntmem.ILuaUiCallback?
    ): String

    /**
     * Cancels / interrupts currently running Lua script.
     */
    @FastNative
    external fun nativeCancelLuaScript()

    /**
     * High-level wrapper to run a Lua script via root IPC service.
     *
     * @param pid Target process ID.
     * @param script Lua 5.4 script code to execute.
     * @param callback Optional UI callback for dialogs, logs, canvas, and menus.
     * @return JSON response string with execution results.
     */
    fun runLuaScript(
        pid: Int,
        script: String,
        callback: com.yervant.huntmem.ILuaUiCallback? = null
    ): String {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeRunLuaScript(pid, script, callback)
            } catch (e: Exception) {
                "{\"success\":false,\"output\":\"\",\"error\":\"Service error: ${e.message}\",\"result\":null}"
            }
        } else {
            "{\"success\":false,\"output\":\"\",\"error\":\"Root service not connected\",\"result\":null}"
        }
    }

    /**
     * High-level wrapper to cancel running Lua script.
     */
    fun cancelLuaScript() {
        val s = HMemServiceConnection.service
        if (s != null) {
            try {
                s.nativeCancelLuaScript()
            } catch (_: Exception) {}
        }
        try {
            nativeCancelLuaScript()
        } catch (_: Exception) {}
    }

    /**
     * Retrieves the base virtual memory address of a loaded shared library or module.
     */
    @FastNative
    external fun nativeGetModuleBase(
        pid: Int,
        moduleName: String
    ): Long

    fun getModuleBase(
        pid: Int,
        moduleName: String
    ): Long {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeGetModuleBase(pid, moduleName)
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }
    }

    /**
     * Resolves a multi-level pointer chain in target process memory.
     */
    @FastNative
    external fun nativeResolvePointerChain(
        pid: Int,
        baseExpr: String,
        offsetsJson: String
    ): Long

    fun resolvePointerChain(
        pid: Int,
        baseExpr: String,
        offsetsJson: String
    ): Long {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeResolvePointerChain(pid, baseExpr, offsetsJson)
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }
    }

    // === FAST BINARY & PAGINATED IPC BINDINGS ===

    @FastNative
    external fun nativeGetMemoryMapsBinary(
        pid: Int,
        requireRead: Boolean,
        requireWrite: Boolean,
        includeSwapped: Boolean,
        minSize: Long,
        filterTypes: String,
        custom: String
    ): ByteArray?

    fun getMemoryMapsBinary(
        pid: Int,
        requireRead: Boolean = true,
        requireWrite: Boolean = false,
        includeSwapped: Boolean = true,
        minSize: Long = 0L,
        filterTypes: String = "",
        custom: String = ""
    ): ByteArray? {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeGetMemoryMapsBinary(pid, requireRead, requireWrite, includeSwapped, minSize, filterTypes, custom)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    @FastNative
    external fun nativeGetResultsCount(sessionId: String): Int

    fun getResultsCount(sessionId: String): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeGetResultsCount(sessionId)
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
    }

    @FastNative
    external fun nativeGetResultsPage(sessionId: String, offset: Int, limit: Int): ByteArray?

    fun getResultsPage(sessionId: String, offset: Int, limit: Int): ByteArray? {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeGetResultsPage(sessionId, offset, limit)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    @FastNative
    external fun nativeScanMemoryFast(
        pid: Int,
        sessionId: String,
        value: String,
        valueType: String,
        filterTypes: String,
        customFilter: String,
        operator: String
    ): Int

    fun scanMemoryFast(
        pid: Int,
        sessionId: String,
        value: String,
        valueType: String,
        filterTypes: String = "",
        customFilter: String = "",
        operator: String = "equal"
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanMemoryFast(pid, sessionId, value, valueType, filterTypes, customFilter, operator)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeScanRangeFast(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String,
        valueType: String,
        filterTypes: String,
        customFilter: String
    ): Int

    fun scanRangeFast(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String,
        valueType: String,
        filterTypes: String = "",
        customFilter: String = ""
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanRangeFast(pid, sessionId, minValue, maxValue, valueType, filterTypes, customFilter)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeScanGroupFast(
        pid: Int,
        sessionId: String,
        groupSpec: String,
        valueType: String,
        filterTypes: String,
        customFilter: String
    ): Int

    fun scanGroupFast(
        pid: Int,
        sessionId: String,
        groupSpec: String,
        valueType: String,
        filterTypes: String = "",
        customFilter: String = ""
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanGroupFast(pid, sessionId, groupSpec, valueType, filterTypes, customFilter)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeScanObscuredFast(
        pid: Int,
        sessionId: String,
        value: String,
        obscuredType: String,
        filterTypes: String,
        customFilter: String
    ): Int

    fun scanObscuredFast(
        pid: Int,
        sessionId: String,
        value: String,
        obscuredType: String,
        filterTypes: String = "",
        customFilter: String = ""
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanObscuredFast(pid, sessionId, value, obscuredType, filterTypes, customFilter)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeScanBigDoubleFast(
        pid: Int,
        sessionId: String,
        value: String,
        filterTypes: String,
        customFilter: String
    ): Int

    fun scanBigDoubleFast(
        pid: Int,
        sessionId: String,
        value: String,
        filterTypes: String = "",
        customFilter: String = ""
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeScanBigDoubleFast(pid, sessionId, value, filterTypes, customFilter)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeFilterMatchesFast(
        pid: Int,
        sessionId: String,
        targetValue: String,
        operator: String
    ): Int

    fun filterMatchesFast(
        pid: Int,
        sessionId: String,
        targetValue: String,
        operator: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterMatchesFast(pid, sessionId, targetValue, operator)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeFilterRangeMatchesFast(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String
    ): Int

    fun filterRangeMatchesFast(
        pid: Int,
        sessionId: String,
        minValue: String,
        maxValue: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterRangeMatchesFast(pid, sessionId, minValue, maxValue)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeFilterObscuredMatchesFast(
        pid: Int,
        sessionId: String,
        targetValue: String,
        obscuredType: String
    ): Int

    fun filterObscuredMatchesFast(
        pid: Int,
        sessionId: String,
        targetValue: String,
        obscuredType: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterObscuredMatchesFast(pid, sessionId, targetValue, obscuredType)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeFilterBigDoubleMatchesFast(
        pid: Int,
        sessionId: String,
        targetValue: String
    ): Int

    fun filterBigDoubleMatchesFast(
        pid: Int,
        sessionId: String,
        targetValue: String
    ): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeFilterBigDoubleMatchesFast(pid, sessionId, targetValue)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    @FastNative
    external fun nativeSetScanEngineMode(mode: Int)

    fun setScanEngineMode(mode: Int) {
        val s = HMemServiceConnection.service
        if (s != null) {
            try {
                s.nativeSetScanEngineMode(mode)
            } catch (_: Exception) {}
        }
    }

    @FastNative
    external fun nativeGetScanEngineMode(): Int

    fun getScanEngineMode(): Int {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeGetScanEngineMode()
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
    }

    // === HIGH-PERFORMANCE ZERO-JSON BYTEBUFFER DECODERS ===

    fun decodeMemoryRegions(bytes: ByteArray?): List<MemoryEngine.MemoryMapEntry> {
        if (bytes == null || bytes.size < 4) return emptyList()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        val list = ArrayList<MemoryEngine.MemoryMapEntry>(count.coerceAtLeast(0))
        for (i in 0 until count) {
            val start = buffer.long
            val end = buffer.long
            val offset = buffer.long
            val mergedCount = buffer.int
            val permBytes = ByteArray(4)
            buffer.get(permBytes)
            val permissions = String(permBytes, Charsets.US_ASCII).trim()
            val pathLen = buffer.short.toInt() and 0xFFFF
            val pathBytes = ByteArray(pathLen)
            buffer.get(pathBytes)
            val path = String(pathBytes, Charsets.UTF_8)
            list.add(
                MemoryEngine.MemoryMapEntry(
                    start = start,
                    end = end,
                    permissions = permissions,
                    offset = offset,
                    path = path,
                    mergedCount = mergedCount
                )
            )
        }
        return list
    }

    fun decodeMatchesPage(
        sessionId: String,
        bytes: ByteArray?,
        pid: Int
    ): Pair<Int, List<com.yervant.huntmem.ui.overlay.tabs.MatchInfo>> {
        if (bytes == null || bytes.size < 12) return Pair(0, emptyList())
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val totalCount = buffer.int
        val offset = buffer.int
        val pageCount = buffer.int
        val list = ArrayList<com.yervant.huntmem.ui.overlay.tabs.MatchInfo>(pageCount.coerceAtLeast(0))
        for (i in 0 until pageCount) {
            val address = buffer.long
            val regionStart = buffer.long
            val regionEnd = buffer.long
            val vtCode = buffer.get().toInt() and 0xFF
            val valueType = when (vtCode) {
                0 -> "byte"
                1 -> "short"
                2 -> "int"
                3 -> "long"
                4 -> "float"
                5 -> "double"
                6 -> "float16"
                else -> "int"
            }
            val permBytes = ByteArray(4)
            buffer.get(permBytes)
            val permissions = String(permBytes, Charsets.US_ASCII).trim()
            val valLen = buffer.get().toInt() and 0xFF
            val valBytes = ByteArray(valLen)
            buffer.get(valBytes)
            val valStr = String(valBytes, Charsets.UTF_8)
            val pathLen = buffer.short.toInt() and 0xFFFF
            val pathBytes = ByteArray(pathLen)
            buffer.get(pathBytes)
            val path = String(pathBytes, Charsets.UTF_8)

            val parsedValue: Any = when (valueType) {
                "byte" -> valStr.toByteOrNull() ?: 0.toByte()
                "short" -> valStr.toShortOrNull() ?: 0.toShort()
                "float16" -> valStr.toFloatOrNull() ?: 0.0f
                "int" -> valStr.toIntOrNull() ?: 0
                "long" -> valStr.toLongOrNull() ?: 0L
                "float" -> valStr.toFloatOrNull() ?: 0.0f
                "double" -> valStr.toDoubleOrNull() ?: 0.0
                else -> valStr
            }
            val size = when (valueType) {
                "byte" -> 1
                "short", "float16" -> 2
                "int", "float" -> 4
                "long", "double" -> 8
                else -> 4
            }

            list.add(
                com.yervant.huntmem.ui.overlay.tabs.MatchInfo(
                    id = "$sessionId-${offset + i}-$address",
                    pid = pid,
                    address = address,
                    prevValue = parsedValue,
                    valueType = valueType,
                    size = size,
                    memoryRegion = path,
                    regionType = MemoryEngine.determineRegionType(path),
                    regionStart = regionStart,
                    regionEnd = regionEnd,
                    permissions = permissions
                )
            )
        }
        return Pair(totalCount, list)
    }
}
