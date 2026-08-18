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

import dalvik.annotation.optimization.FastNative

object NativeBridge {

    private const val LIB_NAME = "hmem_jni"
    init {
        try {
            System.loadLibrary(LIB_NAME)
        } catch (_: Throwable) {
            // Ignored, service will load it in its process
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
        return if (s != null) {
            try {
                s.nativeIsHmkpmAvailable()
            } catch (_: Exception) {
                false
            }
        } else {
            false
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
}
