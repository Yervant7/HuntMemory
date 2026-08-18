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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.ui.keyboard.KeyboardController
import com.yervant.huntmem.ui.keyboard.KeyboardType
import com.yervant.huntmem.ui.keyboard.LocalKeyboardController
import com.yervant.huntmem.ui.keyboard.VirtualKeyboard
import com.yervant.huntmem.ui.keyboard.VirtualTextField
import com.yervant.huntmem.ui.overlay.tabs.AddressTableTab
import com.yervant.huntmem.ui.overlay.tabs.HuntSettings
import com.yervant.huntmem.ui.overlay.tabs.MemoryScanTab
import com.yervant.huntmem.ui.overlay.tabs.ProcessScreen
import com.yervant.huntmem.ui.overlay.tabs.ProcessViewModel
import com.yervant.huntmem.ui.theme.HuntMemTheme
import com.yervant.huntmem.ui.theme.SuccessEmerald
import kotlin.math.roundToInt

val LocalOverlayOpacity = androidx.compose.runtime.compositionLocalOf { 0.92f }

@Composable
fun FloatingIcon(
    onToggleMenu: () -> Unit,
    onUpdatePosition: (IntOffset) -> Unit,
    onDragEnd: () -> Unit = {}
) {
    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()

    Box(
        modifier = Modifier
            .size(54.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onUpdatePosition(
                            IntOffset(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        )
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleMenu() })
            }
    ) {
        Image(
            painter = painterResource(id = R.drawable.overlay_icon),
            contentDescription = stringResource(id = R.string.overlay_ui_open_menu_icon_description),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Status badge indicator for attached process
        if (attachedPid != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(SuccessEmerald)
                    .shadow(3.dp, CircleShape)
            )
        }
    }
}

@Composable
fun MenuOverlayContent(
    uiState: OverlayUiState,
    viewModel: ProcessViewModel,
    context: Context,
    dialogCallback: DialogCallback,
    onToggleMenu: () -> Unit,
    onTabSelected: (Int) -> Unit
) {
    val keyboardController = remember { KeyboardController() }

    HuntMemTheme(darkTheme = true) {
        CompositionLocalProvider(LocalKeyboardController provides keyboardController) {
            Box(modifier = Modifier.fillMaxSize()) {
                MenuContent(
                    selectedTab = uiState.selectedTab,
                    onTabSelected = onTabSelected,
                    viewModel = viewModel,
                    context = context,
                    dialogCallback = dialogCallback,
                    onClose = onToggleMenu,
                )
                DialogManager(dialogState = uiState.dialogState)

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    VirtualKeyboard(controller = keyboardController)
                }
            }
        }
    }
}

private data class TabItemData(
    val titleRes: Int,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuContent(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    viewModel: ProcessViewModel,
    context: Context,
    dialogCallback: DialogCallback,
    onClose: () -> Unit
) {
    val tabs = listOf(
        TabItemData(R.string.overlay_ui_processes_tab, Icons.Default.Apps),
        TabItemData(R.string.overlay_ui_memory_tab, Icons.Default.Memory),
        TabItemData(R.string.overlay_ui_editor_tab, Icons.Default.EditNote),
        TabItemData(R.string.overlay_ui_settings_tab_and_title, Icons.Default.Settings)
    )

    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()
    var currentOpacity by remember { mutableFloatStateOf(0.92f) }

    CompositionLocalProvider(LocalOverlayOpacity provides currentOpacity) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = currentOpacity),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = (currentOpacity + 0.05f).coerceAtMost(1f)))
                ) {
                // Top Header Row with Attached Process Chip, Opacity Switcher, and Window Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Process status chip
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (attachedPid != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, if (attachedPid != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.clickable { onTabSelected(0) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (attachedPid != null) SuccessEmerald else MaterialTheme.colorScheme.outline)
                            )
                            Text(
                                text = if (attachedPid != null) stringResource(R.string.overlay_ui_target_pid, attachedPid!!) else stringResource(R.string.overlay_ui_select_target),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (attachedPid != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Quick Opacity Selector Chip
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.clickable {
                                currentOpacity = when (currentOpacity) {
                                    0.92f -> 0.75f
                                    0.75f -> 0.55f
                                    else -> 0.92f
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Opacity,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${(currentOpacity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Window Close button
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(id = R.string.overlay_ui_close_menu_icon_description),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Tab Selector Row
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.height(38.dp)
                ) {
                    tabs.forEachIndexed { index, tabItem ->
                        val isSelected = selectedTab == index
                        Tab(
                            selected = isSelected,
                            onClick = { onTabSelected(index) },
                            icon = {
                                Icon(
                                    imageVector = tabItem.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            text = {
                                Text(
                                    text = stringResource(id = tabItem.titleRes),
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ProcessScreen(viewModel = viewModel)
                1 -> MemoryScanTab(
                    context = context,
                    dialogCallback = dialogCallback
                )
                2 -> AddressTableTab(
                    context = context,
                    dialogCallback = dialogCallback
                )
                3 -> HuntSettings(
                    context = context
                )
            }
        }
    }
}
}

@Composable
fun DialogManager(dialogState: DialogState) {
    when (dialogState) {
        is DialogState.Info -> {
            InfoDialog(
                title = dialogState.title,
                message = dialogState.message,
                onConfirm = dialogState.onConfirm,
                onDismiss = dialogState.onDismiss
            )
        }
        is DialogState.Input -> {
            InputDialog(
                title = dialogState.title,
                defaultValue = dialogState.defaultValue,
                keyboardType = dialogState.keyboardType,
                onConfirm = dialogState.onConfirm,
                onDismiss = dialogState.onDismiss
            )
        }
        is DialogState.Hidden -> {}
    }
}

@Composable
fun InfoDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(id = R.string.ok), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun InputDialog(
    title: String,
    defaultValue: String,
    keyboardType: KeyboardType = KeyboardType.NUMERIC,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(defaultValue) }

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            VirtualTextField(
                value = text,
                onValueChange = { text = it },
                keyboardType = keyboardType,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.overlay_ui_dialog_cancel_button))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = { onConfirm(text) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(id = R.string.ok), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
