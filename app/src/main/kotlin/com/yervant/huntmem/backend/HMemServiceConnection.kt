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

object HMemServiceConnection : ServiceConnection {
    private const val TAG = "HMemServiceConnection"

    @Volatile
    var service: IHMemService? = null
        private set

    private var binding = false

    fun bind(context: Context) {
        if (service != null || binding) return
        binding = true
        Log.i(TAG, "Binding to HMemService...")
        val intent = Intent(context, HMemService::class.java)
        RootService.bind(intent, this)
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = IHMemService.Stub.asInterface(binder)
        binding = false
        Log.i(TAG, "Root HMemService connected successfully!")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        binding = false
        Log.w(TAG, "Root HMemService disconnected.")
    }
}