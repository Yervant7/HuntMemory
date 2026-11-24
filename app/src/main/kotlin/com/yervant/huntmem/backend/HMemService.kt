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

import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.yervant.huntmem.IHMemService
import com.yervant.huntmem.ILuaUiCallback
import org.json.JSONObject

class HMemService : RootService() {
    companion object {
        private const val TAG = "HMemService"

        private inline fun <T> safeNativeCall(methodName: String, fallback: T, block: () -> T): T {
            if (!NativeBridge.isLoaded) {
                Log.e(TAG, "$methodName skipped: native library is not loaded")
                return fallback
            }
            return try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "$methodName error in root process: ${e.message}", e)
                fallback
            }
        }

        private inline fun safeNativeVoidCall(methodName: String, block: () -> Unit) {
            if (!NativeBridge.isLoaded) {
                Log.e(TAG, "$methodName skipped: native library is not loaded")
                return
            }
            try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "$methodName error in root process: ${e.message}", e)
            }
        }

        private fun hmkpmErrorJson(message: String): String =
            JSONObject().put("available", false).put("error", message).toString()

        private fun luaErrorJson(message: String): String =
            JSONObject()
                .put("success", false)
                .put("output", "")
                .put("error", message)
                .put("result", JSONObject.NULL)
                .toString()
    }

    override fun onBind(intent: Intent): IBinder {
        val customLibDir = intent.getStringExtra("NATIVE_LIB_DIR")
        val sandboxDumpsDir = intent.getStringExtra("SANDBOX_DUMPS_DIR")
        Log.i(TAG, "onBind in root daemon. NATIVE_LIB_DIR = $customLibDir")

        // Load the JNI library in the root process Context with explicit path fallback
        try {
            NativeBridge.loadLibrary(this, customLibDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load native library in HMemService: ${e.message}", e)
        }

        if (NativeBridge.isLoaded && !sandboxDumpsDir.isNullOrBlank()) {
            safeNativeVoidCall("nativeSetSandboxDumpDir") {
                NativeBridge.nativeSetSandboxDumpDir(sandboxDumpsDir)
            }
        }

        return object : IHMemService.Stub() {
            override fun nativeIsHmkpmAvailable(): Boolean =
                safeNativeCall("nativeIsHmkpmAvailable", false) {
                    NativeBridge.nativeIsHmkpmAvailable()
                }

            override fun nativeGetHmkpmVersionInfo(): String {
                if (!NativeBridge.isLoaded) {
                    Log.e(TAG, "nativeGetHmkpmVersionInfo skipped: native library is not loaded")
                    return hmkpmErrorJson("Native library not loaded")
                }
                return try {
                    NativeBridge.nativeGetHmkpmVersionInfo()
                } catch (e: Throwable) {
                    Log.e(TAG, "nativeGetHmkpmVersionInfo error in root process: ${e.message}", e)
                    hmkpmErrorJson(e.message ?: "Native error")
                }
            }

            override fun nativeTranslateV2P(pid: Int, addresses: LongArray): LongArray? =
                safeNativeCall("nativeTranslateV2P", null) {
                    NativeBridge.nativeTranslateV2P(pid, addresses)
                }

            override fun nativeReadMemory(
                pid: Int,
                address: Long,
                valueType: String
            ): String = safeNativeCall("nativeReadMemory", "error") {
                NativeBridge.nativeReadMemory(pid, address, valueType)
            }

            override fun nativeWriteMemory(
                pid: Int,
                address: Long,
                value: String,
                valueType: String
            ): Int = safeNativeCall("nativeWriteMemory", -1) {
                NativeBridge.nativeWriteMemory(pid, address, value, valueType)
            }

            override fun nativeClearSession(sessionId: String) {
                safeNativeVoidCall("nativeClearSession") {
                    NativeBridge.nativeClearSession(sessionId)
                }
            }

            override fun nativeBatchWriteBinary(
                pid: Int,
                payload: ByteArray
            ): Int = safeNativeCall("nativeBatchWriteBinary", 0) {
                NativeBridge.nativeBatchWriteBinary(pid, payload)
            }

            override fun nativeFreezeAddress(
                pid: Int,
                address: Long,
                value: String,
                valueType: String
            ): Int = safeNativeCall("nativeFreezeAddress", -1) {
                NativeBridge.nativeFreezeAddress(pid, address, value, valueType)
            }

            override fun nativeUnfreezeAddress(pid: Int, address: Long): Boolean =
                safeNativeCall("nativeUnfreezeAddress", false) {
                    NativeBridge.nativeUnfreezeAddress(pid, address)
                }

            override fun nativeUnfreezeAll(): Int =
                safeNativeCall("nativeUnfreezeAll", 0) {
                    NativeBridge.nativeUnfreezeAll()
                }

            override fun nativeWriteObscured(
                pid: Int,
                address: Long,
                value: String,
                obscuredType: String
            ): Int = safeNativeCall("nativeWriteObscured", -1) {
                NativeBridge.nativeWriteObscured(pid, address, value, obscuredType)
            }

            override fun nativeWriteBigDouble(
                pid: Int,
                address: Long,
                value: String
            ): Int = safeNativeCall("nativeWriteBigDouble", -1) {
                NativeBridge.nativeWriteBigDouble(pid, address, value)
            }

            override fun nativeIsAddressFrozen(pid: Int, address: Long): Boolean =
                safeNativeCall("nativeIsAddressFrozen", false) {
                    NativeBridge.nativeIsAddressFrozen(pid, address)
                }

            override fun nativeRunLuaScript(pid: Int, script: String, callback: ILuaUiCallback?): String {
                if (!NativeBridge.isLoaded) {
                    Log.e(TAG, "nativeRunLuaScript skipped: native library is not loaded")
                    return luaErrorJson("Native library not loaded in root service")
                }
                return try {
                    NativeBridge.nativeRunLuaScript(pid, script, callback)
                } catch (e: Throwable) {
                    Log.e(TAG, "nativeRunLuaScript error in root process: ${e.message}", e)
                    luaErrorJson("Root service native error: ${e.message ?: "unknown"}")
                }
            }

            override fun nativeCancelLuaScript() {
                safeNativeVoidCall("nativeCancelLuaScript") {
                    NativeBridge.nativeCancelLuaScript()
                }
            }

            override fun nativeGetModuleBase(pid: Int, moduleName: String): Long =
                safeNativeCall("nativeGetModuleBase", 0L) {
                    NativeBridge.nativeGetModuleBase(pid, moduleName)
                }

            override fun nativeResolvePointerChain(pid: Int, baseExpr: String, offsetsJson: String): Long =
                safeNativeCall("nativeResolvePointerChain", 0L) {
                    NativeBridge.nativeResolvePointerChain(pid, baseExpr, offsetsJson)
                }

            override fun nativeGetMemoryMapsBinary(
                pid: Int,
                require_read: Boolean,
                require_write: Boolean,
                include_swapped: Boolean,
                min_size: Long,
                filter_types: String,
                custom: String
            ): ByteArray? = safeNativeCall("nativeGetMemoryMapsBinary", null) {
                NativeBridge.nativeGetMemoryMapsBinary(pid, require_read, require_write, include_swapped, min_size, filter_types, custom)
            }

            override fun nativeGetResultsCount(sessionId: String): Int =
                safeNativeCall("nativeGetResultsCount", 0) {
                    NativeBridge.nativeGetResultsCount(sessionId)
                }

            override fun nativeGetResultsPage(sessionId: String, offset: Int, limit: Int): ByteArray? =
                safeNativeCall("nativeGetResultsPage", null) {
                    NativeBridge.nativeGetResultsPage(sessionId, offset, limit)
                }

            override fun nativeRefreshResultsPage(pid: Int, sessionId: String, offset: Int, limit: Int): ByteArray? =
                safeNativeCall("nativeRefreshResultsPage", null) {
                    NativeBridge.nativeRefreshResultsPage(pid, sessionId, offset, limit)
                }

            override fun nativeScanMemoryFast(
                pid: Int,
                sessionId: String,
                value: String,
                valueType: String,
                filterTypes: String,
                customFilter: String,
                operator: String,
                mapFlags: Int,
                minSize: Long
            ): Int = safeNativeCall("nativeScanMemoryFast", -1) {
                NativeBridge.nativeScanMemoryFast(
                    pid, sessionId, value, valueType, filterTypes, customFilter, operator, mapFlags, minSize
                )
            }

            override fun nativeScanRangeFast(
                pid: Int,
                sessionId: String,
                minValue: String,
                maxValue: String,
                valueType: String,
                filterTypes: String,
                customFilter: String,
                mapFlags: Int,
                minSize: Long
            ): Int = safeNativeCall("nativeScanRangeFast", -1) {
                NativeBridge.nativeScanRangeFast(
                    pid, sessionId, minValue, maxValue, valueType, filterTypes, customFilter, mapFlags, minSize
                )
            }

            override fun nativeScanGroupFast(
                pid: Int,
                sessionId: String,
                groupSpec: String,
                valueType: String,
                filterTypes: String,
                customFilter: String,
                mapFlags: Int,
                minSize: Long
            ): Int = safeNativeCall("nativeScanGroupFast", -1) {
                NativeBridge.nativeScanGroupFast(
                    pid, sessionId, groupSpec, valueType, filterTypes, customFilter, mapFlags, minSize
                )
            }

            override fun nativeScanObscuredFast(
                pid: Int,
                sessionId: String,
                value: String,
                obscuredType: String,
                filterTypes: String,
                customFilter: String,
                mapFlags: Int,
                minSize: Long
            ): Int = safeNativeCall("nativeScanObscuredFast", -1) {
                NativeBridge.nativeScanObscuredFast(
                    pid, sessionId, value, obscuredType, filterTypes, customFilter, mapFlags, minSize
                )
            }

            override fun nativeScanBigDoubleFast(
                pid: Int,
                sessionId: String,
                value: String,
                filterTypes: String,
                customFilter: String,
                mapFlags: Int,
                minSize: Long
            ): Int = safeNativeCall("nativeScanBigDoubleFast", -1) {
                NativeBridge.nativeScanBigDoubleFast(
                    pid, sessionId, value, filterTypes, customFilter, mapFlags, minSize
                )
            }

            override fun nativeFilterMatchesFast(
                pid: Int,
                sessionId: String,
                targetValue: String,
                operator: String
            ): Int = safeNativeCall("nativeFilterMatchesFast", -1) {
                NativeBridge.nativeFilterMatchesFast(pid, sessionId, targetValue, operator)
            }

            override fun nativeFilterRangeMatchesFast(
                pid: Int,
                sessionId: String,
                minValue: String,
                maxValue: String
            ): Int = safeNativeCall("nativeFilterRangeMatchesFast", -1) {
                NativeBridge.nativeFilterRangeMatchesFast(pid, sessionId, minValue, maxValue)
            }

            override fun nativeFilterObscuredMatchesFast(
                pid: Int,
                sessionId: String,
                targetValue: String,
                obscuredType: String
            ): Int = safeNativeCall("nativeFilterObscuredMatchesFast", -1) {
                NativeBridge.nativeFilterObscuredMatchesFast(pid, sessionId, targetValue, obscuredType)
            }

            override fun nativeFilterBigDoubleMatchesFast(
                pid: Int,
                sessionId: String,
                targetValue: String
            ): Int = safeNativeCall("nativeFilterBigDoubleMatchesFast", -1) {
                NativeBridge.nativeFilterBigDoubleMatchesFast(pid, sessionId, targetValue)
            }

            override fun nativeSetScanEngineMode(mode: Int) {
                safeNativeVoidCall("nativeSetScanEngineMode") {
                    NativeBridge.nativeSetScanEngineMode(mode)
                }
            }

            override fun nativeGetScanEngineMode(): Int =
                safeNativeCall("nativeGetScanEngineMode", 0) {
                    NativeBridge.nativeGetScanEngineMode()
                }

            override fun nativeCancelScan() {
                safeNativeVoidCall("nativeCancelScan") {
                    NativeBridge.nativeCancelScan()
                }
            }

            override fun nativeSaveSessionCheckpoint(sessionId: String): Boolean =
                safeNativeCall("nativeSaveSessionCheckpoint", false) {
                    NativeBridge.nativeSaveSessionCheckpoint(sessionId)
                }

            override fun nativeUndoSession(sessionId: String): Int =
                safeNativeCall("nativeUndoSession", -1) {
                    NativeBridge.nativeUndoSession(sessionId)
                }

            override fun nativeGetSessionUndoCount(sessionId: String): Int =
                safeNativeCall("nativeGetSessionUndoCount", 0) {
                    NativeBridge.nativeGetSessionUndoCount(sessionId)
                }

            override fun nativeIsSessionSaved(sessionId: String): Boolean =
                safeNativeCall("nativeIsSessionSaved", false) {
                    NativeBridge.nativeIsSessionSaved(sessionId)
                }

            override fun nativeCloneSession(sourceSessionId: String, targetSessionId: String): Int =
                safeNativeCall("nativeCloneSession", -1) {
                    NativeBridge.nativeCloneSession(sourceSessionId, targetSessionId)
                }

            override fun nativeCopyMatchesToSession(
                sourceSessionId: String,
                targetSessionId: String,
                addresses: LongArray
            ): Int = safeNativeCall("nativeCopyMatchesToSession", -1) {
                NativeBridge.nativeCopyMatchesToSession(sourceSessionId, targetSessionId, addresses)
            }
        }
    }
}
