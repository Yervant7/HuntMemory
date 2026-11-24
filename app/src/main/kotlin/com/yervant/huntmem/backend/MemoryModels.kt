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

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * Domain model representing an individual memory scan match.
 *
 * @property id Stable identifier string for Compose LazyColumn keys.
 * @property pid Target process PID.
 * @property address Virtual memory address.
 * @property prevValue Latest observed value formatted as string or numeric.
 * @property valueType Value type name (e.g. "int", "float", "byte").
 * @property size Size in bytes of the value type.
 * @property memoryRegion Memory map path or anonymous region name.
 * @property regionType Region category name (e.g. "ANONYMOUS", "ALLOC", "JAVA_HEAP").
 * @property regionStart Starting address of containing virtual memory region.
 * @property regionEnd Ending address of containing virtual memory region.
 * @property permissions Region protection string (e.g. "rw-p", "r-xp").
 * @property previousValue Prior value before the latest live refresh or refinement.
 */
@Keep
@Immutable
data class MatchInfo(
    val id: String,
    val pid: Int,
    val address: Long,
    val prevValue: Any,
    val valueType: String,
    val size: Int,
    val memoryRegion: String = "",
    val regionType: String = "",
    val regionStart: Long = 0,
    val regionEnd: Long = 0,
    val permissions: String = "",
    val previousValue: Any? = null
)

/**
 * Domain model representing a saved entry in the Address Table.
 */
@Immutable
data class AddressInfo(
    val id: String = UUID.randomUUID().toString(),
    val matchInfo: MatchInfo,
    val numType: String,
    val isFrozen: Boolean = false
)
