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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.ui.theme.MonospaceValueStyle

@Composable
fun VirtualKeyboard(controller: KeyboardController) {
    AnimatedVisibility(
        visible = controller.isVisible.value,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val type = controller.keyboardType.value

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top control & cursor preview bar
                KeyboardPreviewHeader(controller)

                HorizontalDivider(
                    modifier = Modifier.padding(bottom = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                when (type) {
                    KeyboardType.QWERTY -> QwertyLayout(controller)
                    KeyboardType.NUMERIC -> NumericLayout(controller)
                    KeyboardType.HEXADECIMAL -> HexadecimalLayout(controller)
                }
            }
        }
    }
}

@Composable
private fun KeyboardPreviewHeader(controller: KeyboardController) {
    val text = controller.currentText.value
    val pos = controller.cursorPosition.intValue.coerceIn(0, text.length)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Cursor movement controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (pos > 0) controller.setCursor(pos - 1) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Move Left",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = { if (pos < text.length) controller.setCursor(pos + 1) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Move Right",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Live text preview with simulated cursor
        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            val displayText = if (text.isEmpty()) {
                "|"
            } else {
                val before = text.substring(0, pos)
                val after = text.substring(pos)
                "$before|$after"
            }
            Text(
                text = displayText,
                style = MonospaceValueStyle.copy(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Close button
        IconButton(
            onClick = { controller.hide() },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.keyboard_hide_cd),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun NumericLayout(controller: KeyboardController) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey("7", Modifier.weight(1f)) { controller.onKeyPress("7") }
            DigitKey("8", Modifier.weight(1f)) { controller.onKeyPress("8") }
            DigitKey("9", Modifier.weight(1f)) { controller.onKeyPress("9") }
            ActionKey(
                text = "CLR",
                icon = Icons.Default.Clear,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            ) { controller.onClear() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey("4", Modifier.weight(1f)) { controller.onKeyPress("4") }
            DigitKey("5", Modifier.weight(1f)) { controller.onKeyPress("5") }
            DigitKey("6", Modifier.weight(1f)) { controller.onKeyPress("6") }
            ActionKey(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            ) { controller.onBackspace() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey("1", Modifier.weight(1f)) { controller.onKeyPress("1") }
            DigitKey("2", Modifier.weight(1f)) { controller.onKeyPress("2") }
            DigitKey("3", Modifier.weight(1f)) { controller.onKeyPress("3") }
            ActionKey(
                text = "+/-",
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            ) { controller.toggleSign() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey("0", Modifier.weight(1f)) { controller.onKeyPress("0") }
            DigitKey(".", Modifier.weight(1f)) { controller.onKeyPress(".") }
            DigitKey(":", Modifier.weight(1f)) { controller.onKeyPress(":") }
            ActionKey(
                icon = Icons.Default.Check,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1f)
            ) {
                controller.onDone?.invoke()
                controller.hide()
            }
        }
    }
}

@Composable
private fun HexadecimalLayout(controller: KeyboardController) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Hex Row 1: A B C + CLR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            HexKey("A", Modifier.weight(1f)) { controller.onKeyPress("A") }
            HexKey("B", Modifier.weight(1f)) { controller.onKeyPress("B") }
            HexKey("C", Modifier.weight(1f)) { controller.onKeyPress("C") }
            ActionKey(
                text = "CLR",
                icon = Icons.Default.Clear,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            ) { controller.onClear() }
        }

        // Hex Row 2: D E F + DEL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            HexKey("D", Modifier.weight(1f)) { controller.onKeyPress("D") }
            HexKey("E", Modifier.weight(1f)) { controller.onKeyPress("E") }
            HexKey("F", Modifier.weight(1f)) { controller.onKeyPress("F") }
            ActionKey(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            ) { controller.onBackspace() }
        }

        // Digits 7 8 9 + 0x prefix
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey("7", Modifier.weight(1f)) { controller.onKeyPress("7") }
            DigitKey("8", Modifier.weight(1f)) { controller.onKeyPress("8") }
            DigitKey("9", Modifier.weight(1f)) { controller.onKeyPress("9") }
            ActionKey(
                text = "0x",
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            ) { controller.toggleHexPrefix() }
        }

        // Digits 4 5 6 + "+" (offset separator)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey("4", Modifier.weight(1f)) { controller.onKeyPress("4") }
            DigitKey("5", Modifier.weight(1f)) { controller.onKeyPress("5") }
            DigitKey("6", Modifier.weight(1f)) { controller.onKeyPress("6") }
            DigitKey("+", Modifier.weight(1f)) { controller.onKeyPress("+") }
        }

        // Digits 1 2 3 0
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey("1", Modifier.weight(1f)) { controller.onKeyPress("1") }
            DigitKey("2", Modifier.weight(1f)) { controller.onKeyPress("2") }
            DigitKey("3", Modifier.weight(1f)) { controller.onKeyPress("3") }
            DigitKey("0", Modifier.weight(1f)) { controller.onKeyPress("0") }
        }

        // Delimiters & OK
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DigitKey(".", Modifier.weight(1f)) { controller.onKeyPress(".") }
            DigitKey(":", Modifier.weight(1f)) { controller.onKeyPress(":") }
            DigitKey("~", Modifier.weight(1f)) { controller.onKeyPress("~") }
            ActionKey(
                icon = Icons.Default.Check,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1f)
            ) {
                controller.onDone?.invoke()
                controller.hide()
            }
        }
    }
}

@Composable
private fun QwertyLayout(controller: KeyboardController) {
    val isShift = controller.isShiftActive.value

    val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2 = if (isShift) {
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
    } else {
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    }
    val row3 = if (isShift) {
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
    } else {
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    }
    val row4 = if (isShift) {
        listOf("Z", "X", "C", "V", "B", "N", "M")
    } else {
        listOf("z", "x", "c", "v", "b", "n", "m")
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.5.dp)) {
        KeyboardRow(row1) { controller.onKeyPress(it) }
        KeyboardRow(row2) { controller.onKeyPress(it) }
        KeyboardRow(row3) { controller.onKeyPress(it) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.5.dp)
        ) {
            ActionKey(
                icon = Icons.Default.KeyboardCapslock,
                containerColor = if (isShift) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isShift) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.2f)
            ) { controller.toggleShift() }

            row4.forEach { key ->
                DigitKey(key, Modifier.weight(1f)) { controller.onKeyPress(key) }
            }

            ActionKey(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.2f)
            ) { controller.onBackspace() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.5.dp)
        ) {
            ActionKey(
                text = "0x",
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            ) { controller.toggleHexPrefix() }

            ActionKey(
                text = "CLR",
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            ) { controller.onClear() }

            KeyButton(
                text = stringResource(R.string.keyboard_space),
                modifier = Modifier.weight(3.5f),
                onClick = { controller.onKeyPress(" ") }
            )

            DigitKey(":", Modifier.weight(0.8f)) { controller.onKeyPress(":") }
            DigitKey(";", Modifier.weight(0.8f)) { controller.onKeyPress(";") }
            DigitKey(".", Modifier.weight(0.8f)) { controller.onKeyPress(".") }

            ActionKey(
                icon = Icons.Default.Check,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1.2f)
            ) {
                controller.onDone?.invoke()
                controller.hide()
            }
        }
    }
}

@Composable
private fun KeyboardRow(keys: List<String>, onKey: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        keys.forEach { key ->
            DigitKey(
                text = key,
                modifier = Modifier.weight(1f),
                onClick = { onKey(key) }
            )
        }
    }
}

@Composable
private fun DigitKey(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    KeyButton(
        text = text,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick
    )
}

@Composable
private fun HexKey(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    KeyButton(
        text = text,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        contentColor = MaterialTheme.colorScheme.primary,
        onClick = onClick
    )
}

@Composable
private fun KeyButton(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(5.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ActionKey(
    text: String? = null,
    icon: ImageVector? = null,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(5.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(16.dp)
            )
        } else if (text != null) {
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
