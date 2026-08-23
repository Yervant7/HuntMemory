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

class HMemService : RootService() {
    companion object {
        private const val TAG = "HMemService"
    }

    override fun onBind(intent: Intent): IBinder {
        val customLibDir = intent.getStringExtra("NATIVE_LIB_DIR")
        Log.i(TAG, "onBind in root daemon. NATIVE_LIB_DIR = $customLibDir")

        // Load the JNI library in the root process Context with explicit path fallback
        try {
            NativeBridge.loadLibrary(this, customLibDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load native library in HMemService: ${e.message}", e)
        }
        return object : IHMemService.Stub() {
            override fun nativeIsHmkpmAvailable(): Boolean {
                return try {
                    NativeBridge.nativeIsHmkpmAvailable()
                } catch (e: Throwable) {
                    Log.e(TAG, "nativeIsHmkpmAvailable error in root process: ${e.message}", e)
                    false
                }
            }

            override fun nativeReadMemory(
                pid: Int,
                address: Long,
                valueType: String
            ): String {
                return NativeBridge.nativeReadMemory(pid, address, valueType)
            }

            override fun nativeWriteMemory(
                pid: Int,
                address: Long,
                value: String,
                valueType: String
            ): Int {
                return NativeBridge.nativeWriteMemory(pid, address, value, valueType)
            }

            override fun nativeGetMemoryMaps(
                pid: Int,
                requireRead: Boolean,
                requireWrite: Boolean,
                includeSwapped: Boolean,
                minSize: Long,
                filterTypes: String,
                custom: String
            ): String {
                return NativeBridge.nativeGetMemoryMaps(pid, requireRead, requireWrite, includeSwapped, minSize, filterTypes, custom)
            }

            override fun nativeScanMemory(
                pid: Int,
                sessionId: String,
                value: String,
                valueType: String,
                regionsJson: String,
                operator: String
            ): String {
                return NativeBridge.nativeScanMemory(pid, sessionId, value, valueType, regionsJson, operator)
            }

            override fun nativeScanRange(
                pid: Int,
                sessionId: String,
                minValue: String,
                maxValue: String,
                valueType: String,
                regionsJson: String
            ): String {
                return NativeBridge.nativeScanRange(pid, sessionId, minValue, maxValue, valueType, regionsJson)
            }

            override fun nativeScanGroup(
                pid: Int,
                sessionId: String,
                groupSpec: String,
                valueType: String,
                regionsJson: String
            ): String {
                return NativeBridge.nativeScanGroup(pid, sessionId, groupSpec, valueType, regionsJson)
            }

            override fun nativeFilterMatches(
                pid: Int,
                sessionId: String,
                targetValue: String,
                operator: String
            ): String {
                return NativeBridge.nativeFilterMatches(pid, sessionId, targetValue, operator)
            }

            override fun nativeFilterRangeMatches(
                pid: Int,
                sessionId: String,
                minValue: String,
                maxValue: String
            ): String {
                return NativeBridge.nativeFilterRangeMatches(pid, sessionId, minValue, maxValue)
            }

            override fun nativeClearSession(sessionId: String) {
                NativeBridge.nativeClearSession(sessionId)
            }

            override fun nativeBatchWrite(
                pid: Int,
                writesJson: String
            ): Int {
                return NativeBridge.nativeBatchWrite(pid, writesJson)
            }

            override fun nativeFreezeAddress(
                pid: Int,
                address: Long,
                value: String,
                valueType: String
            ): Int {
                return NativeBridge.nativeFreezeAddress(pid, address, value, valueType)
            }

            override fun nativeUnfreezeAddress(address: Long): Boolean {
                return NativeBridge.nativeUnfreezeAddress(address)
            }

            override fun nativeUnfreezeAll(): Int {
                return NativeBridge.nativeUnfreezeAll()
            }

            override fun nativeScanObscured(
                pid: Int,
                sessionId: String,
                value: String,
                obscuredType: String,
                regionsJson: String
            ): String {
                return NativeBridge.nativeScanObscured(pid, sessionId, value, obscuredType, regionsJson)
            }

            override fun nativeScanBigDouble(
                pid: Int,
                sessionId: String,
                value: String,
                regionsJson: String
            ): String {
                return NativeBridge.nativeScanBigDouble(pid, sessionId, value, regionsJson)
            }

            override fun nativeFilterObscuredMatches(
                pid: Int,
                sessionId: String,
                targetValue: String,
                obscuredType: String
            ): String {
                return NativeBridge.nativeFilterObscuredMatches(pid, sessionId, targetValue, obscuredType)
            }

            override fun nativeFilterBigDoubleMatches(
                pid: Int,
                sessionId: String,
                targetValue: String
            ): String {
                return NativeBridge.nativeFilterBigDoubleMatches(pid, sessionId, targetValue)
            }

            override fun nativeWriteObscured(
                pid: Int,
                address: Long,
                value: String,
                obscuredType: String
            ): Int {
                return NativeBridge.nativeWriteObscured(pid, address, value, obscuredType)
            }

            override fun nativeWriteBigDouble(
                pid: Int,
                address: Long,
                value: String
            ): Int {
                return NativeBridge.nativeWriteBigDouble(pid, address, value)
            }

            override fun nativeIsAddressFrozen(address: Long): Boolean {
                return NativeBridge.nativeIsAddressFrozen(address)
            }

            override fun nativeRunLuaScript(pid: Int, script: String, callback: com.yervant.huntmem.ILuaUiCallback?): String {
                return NativeBridge.nativeRunLuaScript(pid, script, callback)
            }

            override fun nativeCancelLuaScript() {
                NativeBridge.nativeCancelLuaScript()
            }

            override fun nativeGetModuleBase(pid: Int, moduleName: String): Long {
                return NativeBridge.nativeGetModuleBase(pid, moduleName)
            }

            override fun nativeResolvePointerChain(pid: Int, baseExpr: String, offsetsJson: String): Long {
                return NativeBridge.nativeResolvePointerChain(pid, baseExpr, offsetsJson)
            }
        }
    }
}
