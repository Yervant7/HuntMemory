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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.yervant.huntmem.IHMemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HMemServiceConnection : ServiceConnection {
    private const val TAG = "HMemServiceConnection"

    @Volatile
    var service: IHMemService? = null
        private set

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile
    private var binding = false

    fun bind(context: Context, force: Boolean = false) {
        if (!force && (service != null || binding)) return
        binding = true
        Log.i(TAG, "Binding to HMemService (force=$force)...")
        val intent = Intent(context, HMemService::class.java).apply {
            putExtra("NATIVE_LIB_DIR", context.applicationInfo.nativeLibraryDir)
            putExtra("PACKAGE_NAME", context.packageName)
        }
        try {
            RootService.bind(intent, this)
        } catch (e: Throwable) {
            Log.e(TAG, "RootService.bind failed: ${e.message}", e)
            binding = false
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = IHMemService.Stub.asInterface(binder)
        binding = false
        _isConnected.value = true
        Log.i(TAG, "Root HMemService connected successfully!")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        binding = false
        _isConnected.value = false
        Log.w(TAG, "Root HMemService disconnected.")
    }

    override fun onBindingDied(name: ComponentName?) {
        service = null
        binding = false
        _isConnected.value = false
        Log.w(TAG, "Root HMemService binding died.")
    }

    override fun onNullBinding(name: ComponentName?) {
        service = null
        binding = false
        _isConnected.value = false
        Log.w(TAG, "Root HMemService null binding.")
    }
}