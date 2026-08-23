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

package com.yervant.huntmem.ui.overlay

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.yervant.huntmem.ui.overlay.tabs.CanvasDrawCommand
import com.yervant.huntmem.ui.overlay.tabs.LuaUiBridge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Ultra high-performance, transparent, hardware-accelerated Compose Canvas overlay.
 * Observing canvasCommands via collectAsState ensures that real-time canvas updates
 * (ESP HUD / overlays) immediately redraw cleanly at 60-120 FPS.
 */
@Composable
fun LuaCanvasOverlay(
    modifier: Modifier = Modifier
) {
    val isVisible by LuaUiBridge.isCanvasVisible.collectAsState()
    val commands by LuaUiBridge.canvasCommands.collectAsState()

    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            style = Paint.Style.FILL
        }
    }

    if (!isVisible || commands.isEmpty()) {
        return
    }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val currentDensity = density
                val nativeCanvas = drawContext.canvas.nativeCanvas

                for (cmd in commands) {
                    when (cmd) {
                        is CanvasDrawCommand.Text -> {
                            textPaint.color = cmd.color.toArgb()
                            textPaint.textSize = cmd.size * currentDensity
                            textPaint.textAlign = cmd.align
                            nativeCanvas.drawText(cmd.text, cmd.x, cmd.y, textPaint)
                        }
                        is CanvasDrawCommand.Line -> {
                            drawLine(
                                color = cmd.color,
                                start = Offset(cmd.x1, cmd.y1),
                                end = Offset(cmd.x2, cmd.y2),
                                strokeWidth = cmd.strokeWidth * currentDensity
                            )
                        }
                        is CanvasDrawCommand.Rect -> {
                            if (cmd.filled) {
                                drawRect(
                                    color = cmd.color,
                                    topLeft = Offset(cmd.x, cmd.y),
                                    size = Size(cmd.width, cmd.height),
                                    style = Fill
                                )
                            } else {
                                drawRect(
                                    color = cmd.color,
                                    topLeft = Offset(cmd.x, cmd.y),
                                    size = Size(cmd.width, cmd.height),
                                    style = Stroke(width = cmd.strokeWidth * currentDensity)
                                )
                            }
                        }
                        is CanvasDrawCommand.Circle -> {
                            if (cmd.filled) {
                                drawCircle(
                                    color = cmd.color,
                                    radius = cmd.radius * currentDensity,
                                    center = Offset(cmd.cx, cmd.cy),
                                    style = Fill
                                )
                            } else {
                                drawCircle(
                                    color = cmd.color,
                                    radius = cmd.radius * currentDensity,
                                    center = Offset(cmd.cx, cmd.cy),
                                    style = Stroke(width = cmd.strokeWidth * currentDensity)
                                )
                            }
                        }
                    }
                }
            }
    )
}
