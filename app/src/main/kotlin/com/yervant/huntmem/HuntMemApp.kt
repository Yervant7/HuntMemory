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

package com.yervant.huntmem

import android.app.Application
import android.content.SharedPreferences
import com.topjohnwu.superuser.Shell
import com.yervant.huntmem.backend.HMemServiceConnection

lateinit var huntmemApp: HuntMemApplication

class HuntMemApplication : Application() {

    companion object {
        const val SP_NAME = "hconfig"
        lateinit var sharedPreferences: SharedPreferences
    }

    override fun onCreate() {
        super.onCreate()

        Shell.enableVerboseLogging = BuildConfig.DEBUG

        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setContext(this)
                .setTimeout(10)
        )

        huntmemApp = this
        HMemServiceConnection.bind(this)

        sharedPreferences = getSharedPreferences(SP_NAME, MODE_PRIVATE)
    }
}