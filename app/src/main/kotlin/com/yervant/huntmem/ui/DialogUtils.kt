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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yervant.huntmem.ui.keyboard.KeyboardType
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.yervant.huntmem.ui.theme.ComponentStyles

interface DialogCallback {
    fun showInfoDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit)
    fun showInputDialog(title: String, defaultValue: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
        showInputDialog(title, defaultValue, KeyboardType.QWERTY, onConfirm, onDismiss)
    }
    fun showInputDialog(title: String, defaultValue: String, keyboardType: KeyboardType, onConfirm: (String) -> Unit, onDismiss: () -> Unit)
}

@Composable
fun CustomDialog(
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val screenHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val maxDialogHeight = screenHeightDp * 0.90f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = ComponentStyles.Dialog.SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismissRequest?.invoke()
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(min = ComponentStyles.Dialog.minWidth, max = ComponentStyles.Dialog.maxWidth)
                .heightIn(max = maxDialogHeight)
                .padding(
                    horizontal = ComponentStyles.Dialog.cardPaddingHorizontal,
                    vertical = ComponentStyles.Dialog.cardPaddingVertical
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* Consume clicks inside dialog card */ }
                .clip(ComponentStyles.Dialog.shape),
            color = MaterialTheme.colorScheme.surface.copy(alpha = ComponentStyles.Dialog.SURFACE_ALPHA),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = ComponentStyles.Dialog.tonalElevation,
            shadowElevation = ComponentStyles.Dialog.shadowElevation
        ) {
            content()
        }
    }
}
