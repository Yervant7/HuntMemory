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
        address: Long
    ): Boolean
    fun unfreezeAddress(
        address: Long
    ): Boolean {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeUnfreezeAddress(address)
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
        address: Long
    ): Boolean
    fun isAddressFrozen(
        address: Long
    ): Boolean {
        val s = HMemServiceConnection.service
        return if (s != null) {
            try {
                s.nativeIsAddressFrozen(address)
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
}
