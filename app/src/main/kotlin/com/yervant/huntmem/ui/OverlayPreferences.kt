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

package com.yervant.huntmem.ui

import android.content.Context
import androidx.core.content.edit
import com.yervant.huntmem.HuntMemApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OverlayPreferences {
    const val KEY_OVERLAY_OPACITY = "overlay_opacity"
    const val DEFAULT_OVERLAY_OPACITY = 0.92f

    private val _opacity = MutableStateFlow(DEFAULT_OVERLAY_OPACITY)
    val opacity = _opacity.asStateFlow()

    init {
        runCatching {
            val prefs = HuntMemApplication.sharedPreferences
            _opacity.value = prefs.getFloat(KEY_OVERLAY_OPACITY, DEFAULT_OVERLAY_OPACITY)
        }
    }

    fun load(context: Context): Float {
        val prefs = runCatching { HuntMemApplication.sharedPreferences }
            .getOrElse { context.getSharedPreferences(HuntMemApplication.SP_NAME, Context.MODE_PRIVATE) }
        val loadedOpacity = prefs.getFloat(KEY_OVERLAY_OPACITY, DEFAULT_OVERLAY_OPACITY)
        _opacity.value = loadedOpacity
        return loadedOpacity
    }

    fun getOpacity(context: Context? = null): Float {
        if (context != null && _opacity.value == DEFAULT_OVERLAY_OPACITY) {
            load(context)
        }
        return _opacity.value
    }

    fun setOpacity(context: Context, newOpacity: Float) {
        val clamped = newOpacity.coerceIn(0.1f, 1.0f)
        _opacity.value = clamped
        val prefs = runCatching { HuntMemApplication.sharedPreferences }
            .getOrElse { context.getSharedPreferences(HuntMemApplication.SP_NAME, Context.MODE_PRIVATE) }
        prefs.edit(commit = true) { putFloat(KEY_OVERLAY_OPACITY, clamped) }
    }
}
