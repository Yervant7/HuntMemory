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

package com.yervant.huntmem.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design system tokens and centralized component styles for HuntMemory.
 * Consolidates shapes, metrics, elevations, and semantic colors across the dashboard and overlay.
 */
@Immutable
object ComponentStyles {

    /**
     * Metrics and styling for Compact Status Cards (Dashboard 2x2 grid).
     */
    @Immutable
    object StatusCard {
        val height: Dp = 76.dp
        val cornerRadius: Dp = 10.dp
        val shape: Shape = RoundedCornerShape(cornerRadius)
        val iconContainerSize: Dp = 32.dp
        val iconContainerRadius: Dp = 6.dp
        val iconContainerShape: Shape = RoundedCornerShape(iconContainerRadius)
        val iconSize: Dp = 18.dp
        val borderWidth: Dp = 1.dp
        val horizontalPadding: Dp = 10.dp
        val verticalPadding: Dp = 8.dp
    }

    /**
     * Metrics and styling for primary action buttons (Start/Stop Hunting).
     */
    @Immutable
    object ActionButton {
        val height: Dp = 52.dp
        val cornerRadius: Dp = 12.dp
        val shape: Shape = RoundedCornerShape(cornerRadius)
        val elevation: Dp = 6.dp
        val iconSize: Dp = 24.dp
    }

    /**
     * Metrics and styling for status chips and process tags.
     */
    @Immutable
    object StatusChip {
        val cornerRadius: Dp = 6.dp
        val shape: Shape = RoundedCornerShape(cornerRadius)
        val indicatorSize: Dp = 7.dp
        val horizontalPadding: Dp = 6.dp
        val verticalPadding: Dp = 3.dp
        val spacing: Dp = 4.dp
        val borderWidth: Dp = 0.5.dp
    }

    /**
     * Metrics for dialog cards.
     */
    @Immutable
    object Dialog {
        val cornerRadius: Dp = 14.dp
        val shape: Shape = RoundedCornerShape(cornerRadius)
        val contentPadding: Dp = 14.dp
        val cardPaddingHorizontal: Dp = 8.dp
        val cardPaddingVertical: Dp = 8.dp
        val minWidth: Dp = 280.dp
        val maxWidth: Dp = 460.dp
        val tonalElevation: Dp = 8.dp
        val shadowElevation: Dp = 16.dp
        const val SCRIM_ALPHA: Float = 0.72f
        const val SURFACE_ALPHA: Float = 0.94f
    }
}
