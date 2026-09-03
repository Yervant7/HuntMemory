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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.yervant.huntmem.ui.overlay.tabs.LuaDialog
import com.yervant.huntmem.ui.overlay.tabs.LuaScriptTab
import com.yervant.huntmem.ui.overlay.tabs.LuaUiBridge
import com.yervant.huntmem.ui.overlay.tabs.MemoryScanTab
import com.yervant.huntmem.ui.overlay.tabs.ProcessScreen
import com.yervant.huntmem.ui.overlay.tabs.ProcessViewModel
import com.yervant.huntmem.ui.theme.ComponentStyles
import com.yervant.huntmem.ui.theme.HuntMemTheme
import com.yervant.huntmem.ui.theme.SuccessEmerald
import kotlin.time.Duration.Companion.milliseconds

val LocalOverlayOpacity = androidx.compose.runtime.compositionLocalOf { 0.92f }

@Composable
fun FloatingIcon(
    modifier: Modifier = Modifier
) {
    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()

    Box(
        modifier = modifier
            .size(54.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.Transparent)
    ) {
        Image(
            painter = painterResource(id = R.drawable.overlay_icon),
            contentDescription = stringResource(id = R.string.overlay_ui_open_menu_icon_description),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
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
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val isWideScreen = windowWidthDp >= 600.dp

    HuntMemTheme(darkTheme = true) {
        CompositionLocalProvider(LocalKeyboardController provides keyboardController) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (isWideScreen) Alignment.Center else Alignment.TopStart
            ) {
                // Dimmed touch scrim on wide displays/tablets
                if (isWideScreen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.40f))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { onToggleMenu() }
                    )
                }

                Box(
                    modifier = if (isWideScreen) {
                        Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxHeight(0.92f)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(16.dp))
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    MenuContent(
                        selectedTab = uiState.selectedTab,
                        onTabSelected = onTabSelected,
                        viewModel = viewModel,
                        context = context,
                        dialogCallback = dialogCallback,
                        onClose = onToggleMenu,
                    )
                }

                DialogManager(dialogState = uiState.dialogState)
                LuaDialogManager()
                LuaToastOverlay()

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

@Immutable
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
    val tabs = remember {
        listOf(
            TabItemData(R.string.overlay_ui_processes_tab, Icons.Default.Apps),
            TabItemData(R.string.overlay_ui_memory_tab, Icons.Default.Memory),
            TabItemData(R.string.overlay_ui_editor_tab, Icons.Default.EditNote),
            TabItemData(R.string.overlay_ui_scripts_tab, Icons.Default.Terminal),
            TabItemData(R.string.overlay_ui_settings_tab_and_title, Icons.Default.Settings)
        )
    }

    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()
    var currentOpacity by remember { mutableFloatStateOf(0.92f) }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val isCompactHeight = windowHeightDp < 500.dp

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
                        .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 2.dp else 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Process status chip
                    Surface(
                        shape = ComponentStyles.StatusChip.shape,
                        color = if (attachedPid != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(ComponentStyles.StatusChip.borderWidth, if (attachedPid != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.clickable { onTabSelected(0) }
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = ComponentStyles.StatusChip.horizontalPadding,
                                vertical = ComponentStyles.StatusChip.verticalPadding
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ComponentStyles.StatusChip.spacing)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(ComponentStyles.StatusChip.indicatorSize)
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
                    modifier = Modifier.height(if (isCompactHeight) 33.dp else 38.dp)
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
                3 -> LuaScriptTab(
                    context = context
                )
                4 -> HuntSettings(
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
fun LuaDialogManager() {
    val activeDialog by LuaUiBridge.activeDialog.collectAsState()

    when (val dialog = activeDialog) {
        is LuaDialog.Alert -> {
            InfoDialog(
                title = dialog.title,
                message = dialog.message,
                onConfirm = { dialog.deferred.complete(Unit) },
                onDismiss = { dialog.deferred.complete(Unit) }
            )
        }
        is LuaDialog.Prompt -> {
            InputDialog(
                title = dialog.title,
                defaultValue = dialog.defaultValue,
                keyboardType = dialog.keyboardType,
                onConfirm = { dialog.deferred.complete(it) },
                onDismiss = { dialog.deferred.complete(null) }
            )
        }
        is LuaDialog.Choice -> {
            LuaChoiceDialog(
                title = dialog.title,
                items = dialog.items,
                onSelect = { idx -> dialog.deferred.complete(idx) },
                onDismiss = { dialog.deferred.complete(null) }
            )
        }
        is LuaDialog.MultiChoice -> {
            LuaMultiChoiceDialog(
                title = dialog.title,
                items = dialog.items,
                initialSelected = dialog.initialSelected,
                onConfirm = { selections -> dialog.deferred.complete(selections) },
                onDismiss = { dialog.deferred.complete(null) }
            )
        }
        null -> {}
    }
}

@Composable
fun LuaToastOverlay() {
    val toastMessage by LuaUiBridge.toastMessage.collectAsState()

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2500L.milliseconds)
            LuaUiBridge.dismissToast()
        }
    }

    AnimatedVisibility(
        visible = toastMessage != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
                shadowElevation = 8.dp,
                modifier = Modifier.clickable { LuaUiBridge.dismissToast() }
            ) {
                Text(
                    text = toastMessage ?: "",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun LuaChoiceDialog(
    title: String,
    items: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val maxListHeight = (windowHeightDp * 0.45f).coerceAtLeast(130.dp)

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(ComponentStyles.Dialog.contentPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(items) { index, itemText ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index + 1) } // 1-based index for Lua
                    ) {
                        Text(
                            text = itemText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.overlay_ui_dialog_cancel_button))
                }
            }
        }
    }
}

@Composable
fun LuaMultiChoiceDialog(
    title: String,
    items: List<String>,
    initialSelected: List<Boolean>,
    onConfirm: (List<Boolean>) -> Unit,
    onDismiss: () -> Unit
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val maxListHeight = (windowHeightDp * 0.45f).coerceAtLeast(130.dp)

    val selections = remember { mutableStateListOf<Boolean>().apply { addAll(initialSelected) } }
    while (selections.size < items.size) {
        selections.add(false)
    }

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(ComponentStyles.Dialog.contentPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(items) { index, itemText ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selections[index] = !selections[index]
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = itemText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Checkbox(
                                checked = selections[index],
                                onCheckedChange = { checked -> selections[index] = checked }
                            )
                        }
                    }
                }
            }

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
                    onClick = { onConfirm(selections.toList()) },
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
fun InfoDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(ComponentStyles.Dialog.contentPadding),
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
            modifier = Modifier.padding(ComponentStyles.Dialog.contentPadding),
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
