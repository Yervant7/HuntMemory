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

package com.yervant.huntmem.ui.keyboard

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

enum class KeyboardType {
    QWERTY, NUMERIC, HEXADECIMAL
}

class KeyboardController {
    val isVisible = mutableStateOf(false)
    val currentText = mutableStateOf("")
    val cursorPosition = mutableIntStateOf(0)
    val keyboardType = mutableStateOf(KeyboardType.QWERTY)
    val isShiftActive = mutableStateOf(false)

    var onTextChange: ((String) -> Unit)? = null
    var onDone: (() -> Unit)? = null

    fun show(
        type: KeyboardType,
        initialText: String,
        onTextChange: (String) -> Unit,
        onDone: (() -> Unit)? = null
    ) {
        this.keyboardType.value = type
        this.currentText.value = initialText
        this.cursorPosition.intValue = initialText.length
        this.onTextChange = onTextChange
        this.onDone = onDone
        this.isVisible.value = true
    }

    fun hide() {
        this.isVisible.value = false
        this.onTextChange = null
        this.onDone = null
    }

    fun setCursor(pos: Int) {
        cursorPosition.intValue = pos.coerceIn(0, currentText.value.length)
    }

    fun onKeyPress(key: String) {
        val text = currentText.value
        val pos = cursorPosition.intValue.coerceIn(0, text.length)
        val newText = text.substring(0, pos) + key + text.substring(pos)
        currentText.value = newText
        cursorPosition.intValue = pos + key.length
        onTextChange?.invoke(newText)
    }

    fun onBackspace() {
        val text = currentText.value
        val pos = cursorPosition.intValue.coerceIn(0, text.length)
        if (pos > 0) {
            val newText = text.substring(0, pos - 1) + text.substring(pos)
            currentText.value = newText
            cursorPosition.intValue = pos - 1
            onTextChange?.invoke(newText)
        }
    }

    fun onClear() {
        currentText.value = ""
        cursorPosition.intValue = 0
        onTextChange?.invoke("")
    }

    fun toggleSign() {
        val text = currentText.value
        val newText = if (text.startsWith("-")) {
            text.substring(1)
        } else {
            "-$text"
        }
        currentText.value = newText
        cursorPosition.intValue = currentText.value.length
        onTextChange?.invoke(newText)
    }

    fun toggleHexPrefix() {
        val text = currentText.value
        val newText = if (text.startsWith("0x", ignoreCase = true)) {
            text.substring(2)
        } else {
            "0x$text"
        }
        currentText.value = newText
        cursorPosition.intValue = currentText.value.length
        onTextChange?.invoke(newText)
    }

    fun toggleShift() {
        isShiftActive.value = !isShiftActive.value
    }
}

val LocalKeyboardController = compositionLocalOf { KeyboardController() }
