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

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destination hierarchy for HuntMemory application screens.
 */
@Immutable
@Serializable
sealed interface AppRoute {

    /**
     * Main dashboard displaying system status, root/HMKPM diagnostics, and service controls.
     */
    @Immutable
    @Serializable
    data object Main : AppRoute

    /**
     * Open source credits, license viewer, and library attribution screen.
     */
    @Immutable
    @Serializable
    data object Credits : AppRoute
}
