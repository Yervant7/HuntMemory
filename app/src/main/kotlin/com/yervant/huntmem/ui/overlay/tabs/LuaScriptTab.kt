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

package com.yervant.huntmem.ui.overlay.tabs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.LuaScriptItem
import com.yervant.huntmem.backend.LuaScriptRepository
import com.yervant.huntmem.backend.MemoryEngine
import com.yervant.huntmem.ui.CustomDialog
import com.yervant.huntmem.ui.LuaScriptImportActivity
import com.yervant.huntmem.ui.keyboard.KeyboardType
import com.yervant.huntmem.ui.keyboard.LocalKeyboardController
import com.yervant.huntmem.ui.keyboard.VirtualTextField
import com.yervant.huntmem.ui.theme.ComponentStyles
import com.yervant.huntmem.ui.theme.FrozenIceCyan
import com.yervant.huntmem.ui.theme.SuccessEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Overlay tab providing a full-featured Lua 5.4 scripting environment and GameGuardian compatibility engine.
 *
 * Features:
 * - Script editor with quick syntax insertion buttons.
 * - Dynamic Compose Menu generation driven by Lua scripts.
 * - Integrated interactive terminal console for print/log capture.
 * - User script preset management, importing, and persistence.
 *
 * @param context Android application/service Context.
 * @param modifier Optional layout modifier.
 */
@Composable
fun LuaScriptTab(
    context: Context,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by remember { mutableIntStateOf(0) }
    var scriptCode by remember { mutableStateOf(LuaScriptRepository.PRESET_CHEAT_MENU) }
    var showScriptSelectionDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var scriptToDelete by remember { mutableStateOf<LuaScriptItem?>(null) }

    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()
    val isRunning by LuaUiBridge.isScriptRunning.collectAsState()
    val dynamicMenu by LuaUiBridge.dynamicMenu.collectAsState()
    val logs by LuaUiBridge.consoleLogs.collectAsState()

    val userScripts by LuaScriptRepository.userScripts.collectAsState()
    val activeScript by LuaScriptRepository.activeScript.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val isCompactWidth = windowWidthDp < 460.dp
    val isCompactHeight = windowHeightDp < 500.dp

    // Load scripts when tab initializes
    LaunchedEffect(Unit) {
        LuaScriptRepository.loadAllScripts(context)
    }

    // Automatically sync scriptCode when activeScript changes (e.g. from picker or selection)
    LaunchedEffect(activeScript) {
        activeScript?.let {
            scriptCode = it.content
        }
    }

    // Automatically switch to Dynamic Menu tab if a script creates one
    LaunchedEffect(dynamicMenu) {
        if (dynamicMenu != null) {
            selectedSubTab = 1
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (isCompactWidth) 4.dp else 6.dp, vertical = if (isCompactHeight) 3.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 6.dp)
    ) {
        // Top Toolbar: Status Chip, Script Selector Button, Import/Save, Run/Stop Controls
        if (isCompactWidth) {
            // Compact Mode (Portrait / Narrow Screen): 2 Responsive Rows
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Row 1: Status Chip + Script Selector Chip + Run/Stop Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status Indicator
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) SuccessEmerald else MaterialTheme.colorScheme.outline)
                            )
                            Text(
                                text = if (isRunning) stringResource(R.string.lua_script_status_running) else stringResource(R.string.lua_script_status_idle),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = 9.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Script Selection Button / Chip
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .height(28.dp)
                            .weight(1f)
                            .clickable { showScriptSelectionDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = activeScript?.name ?: stringResource(R.string.lua_scripts_manager_title),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // Run / Stop Button
                    Button(
                        onClick = {
                            val pid = attachedPid ?: 0
                            if (isRunning) {
                                LuaUiBridge.postLog("[Script] Stopping script execution...")
                                MemoryEngine.stopLuaScript()
                            } else if (pid > 0) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    LuaUiBridge.setScriptRunning(true)
                                    LuaUiBridge.postLog("[Script] Starting execution...")
                                    val result = MemoryEngine.runLuaScript(pid, scriptCode)
                                    result.onSuccess { res ->
                                        if (res.success) {
                                            LuaUiBridge.postLog("[Script] Completed successfully. Result: ${res.result ?: "nil"}")
                                        } else {
                                            LuaUiBridge.postLog("[Script Error] ${res.error}")
                                        }
                                    }.onFailure { e ->
                                        LuaUiBridge.postLog("[Execution Failed] ${e.message}")
                                    }
                                    LuaUiBridge.setScriptRunning(false)
                                }
                            } else {
                                LuaUiBridge.postLog("[Error] Please attach to a target process in the Processes tab first.")
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isRunning) stringResource(R.string.lua_stop_script_button) else stringResource(R.string.lua_run_script_button),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Row 2: Action Buttons Bar (New, Import, Save As, Manage)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // New Script Button
                    Button(
                        onClick = {
                            LuaScriptRepository.setActiveScript(null)
                            scriptCode = "-- New HuntMemory Lua Script\nlocal pid = hmem.get_pid()\nprint(\"PID: \" .. tostring(pid))\n\n"
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = stringResource(R.string.lua_new_script_button), fontSize = 9.5.sp)
                    }

                    // Import Script Button
                    Button(
                        onClick = { LuaScriptImportActivity.launch(context) },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = stringResource(R.string.lua_import_script_button), fontSize = 9.5.sp)
                    }

                    // Save As Button
                    Button(
                        onClick = { showSaveDialog = true },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = stringResource(R.string.lua_save_script_button), fontSize = 9.5.sp)
                    }

                    // Manage / Presets Button
                    Button(
                        onClick = { showScriptSelectionDialog = true },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = stringResource(R.string.lua_script_manage_button), fontSize = 9.5.sp)
                    }
                }
            }
        } else {
            // Wide Mode (Landscape / Tablet): Consolidated Single Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Group: Status Indicator & Active Script Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // Status Indicator
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) SuccessEmerald else MaterialTheme.colorScheme.outline)
                            )
                            Text(
                                text = if (isRunning) stringResource(R.string.lua_script_status_running) else stringResource(R.string.lua_script_status_idle),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Script Selection Button / Chip
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .height(28.dp)
                            .clickable { showScriptSelectionDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = activeScript?.name ?: stringResource(R.string.lua_scripts_manager_title),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Right Group: New, Import, Save As, Run/Stop
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // New Script Button
                    Button(
                        onClick = {
                            LuaScriptRepository.setActiveScript(null)
                            scriptCode = "-- New HuntMemory Lua Script\nlocal pid = hmem.get_pid()\nprint(\"PID: \" .. tostring(pid))\n\n"
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = stringResource(R.string.lua_new_script_button), fontSize = 10.sp)
                    }

                    // Import Script Button
                    Button(
                        onClick = { LuaScriptImportActivity.launch(context) },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = stringResource(R.string.lua_import_script_button), fontSize = 10.sp)
                    }

                    // Save As Button
                    Button(
                        onClick = { showSaveDialog = true },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = stringResource(R.string.lua_save_script_button), fontSize = 10.sp)
                    }

                    // Run / Stop Button
                    Button(
                        onClick = {
                            val pid = attachedPid ?: 0
                            if (isRunning) {
                                LuaUiBridge.postLog("[Script] Stopping script execution...")
                                MemoryEngine.stopLuaScript()
                            } else if (pid > 0) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    LuaUiBridge.setScriptRunning(true)
                                    LuaUiBridge.postLog("[Script] Starting execution...")
                                    val result = MemoryEngine.runLuaScript(pid, scriptCode)
                                    result.onSuccess { res ->
                                        if (res.success) {
                                            LuaUiBridge.postLog("[Script] Completed successfully. Result: ${res.result ?: "nil"}")
                                        } else {
                                            LuaUiBridge.postLog("[Script Error] ${res.error}")
                                        }
                                    }.onFailure { e ->
                                        LuaUiBridge.postLog("[Execution Failed] ${e.message}")
                                    }
                                    LuaUiBridge.setScriptRunning(false)
                                }
                            } else {
                                LuaUiBridge.postLog("[Error] Please attach to a target process in the Processes tab first.")
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isRunning) stringResource(R.string.lua_stop_script_button) else stringResource(R.string.lua_run_script_button),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Sub-navigation: Editor, Dynamic Menu, Console
        val tabRowHeight = if (isCompactHeight) 30.dp else 34.dp
        SecondaryTabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(tabRowHeight)
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                modifier = Modifier.height(tabRowHeight)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(13.dp))
                    Text(
                        text = stringResource(R.string.lua_tab_editor_subtab),
                        fontSize = if (isCompactWidth) 9.sp else 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                modifier = Modifier.height(tabRowHeight)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(13.dp))
                    Text(
                        text = stringResource(R.string.lua_tab_menu_subtab),
                        fontSize = if (isCompactWidth) 9.sp else 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (dynamicMenu != null) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(SuccessEmerald)
                        )
                    }
                }
            }
            Tab(
                selected = selectedSubTab == 2,
                onClick = { selectedSubTab = 2 },
                modifier = Modifier.height(tabRowHeight)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(13.dp))
                    Text(
                        text = stringResource(R.string.lua_tab_console_subtab),
                        fontSize = if (isCompactWidth) 9.sp else 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // Content Area
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedSubTab) {
                0 -> ScriptEditorView(
                    context = context,
                    script = scriptCode,
                    onScriptChange = { newCode ->
                        scriptCode = newCode
                    }
                )
                1 -> DynamicMenuView(menu = dynamicMenu)
                2 -> ConsoleOutputView(logs = logs, onClear = { LuaUiBridge.clearLogs() })
            }
        }
    }

    // --- Script Selection & Management Dialog ---
    if (showScriptSelectionDialog) {
        LuaScriptSelectionDialog(
            userScripts = userScripts,
            presets = LuaScriptRepository.getPresets(),
            activeScript = activeScript,
            onSelectScript = { selectedItem ->
                LuaScriptRepository.setActiveScript(selectedItem)
                scriptCode = selectedItem.content
                showScriptSelectionDialog = false
            },
            onImportRequest = {
                showScriptSelectionDialog = false
                LuaScriptImportActivity.launch(context)
            },
            onSaveRequest = {
                showScriptSelectionDialog = false
                showSaveDialog = true
            },
            onNewScriptRequest = {
                LuaScriptRepository.setActiveScript(null)
                scriptCode = "-- New HuntMemory Lua Script\nlocal pid = hmem.get_pid()\nprint(\"PID: \" .. tostring(pid))\n\n"
                showScriptSelectionDialog = false
            },
            onDeleteScript = { script ->
                scriptToDelete = script
            },
            onDismiss = { showScriptSelectionDialog = false }
        )
    }

    // --- Save Script Dialog ---
    if (showSaveDialog) {
        SaveScriptDialog(
            initialName = activeScript?.name ?: "custom_script.lua",
            onConfirm = { name ->
                coroutineScope.launch {
                    val result = LuaScriptRepository.saveScript(context, name, scriptCode)
                    result.onSuccess { item ->
                        val msg = context.getString(R.string.lua_script_save_success, item.name)
                        LuaUiBridge.postLog("[Script] $msg")
                        LuaUiBridge.showToast(msg)
                    }.onFailure { err ->
                        LuaUiBridge.postLog("[Script Error] Save failed: ${err.message}")
                    }
                }
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false }
        )
    }

    // --- Delete Confirmation Dialog ---
    scriptToDelete?.let { script ->
        CustomDialog(onDismissRequest = { scriptToDelete = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ComponentStyles.Dialog.contentPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.lua_delete_script_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.lua_delete_script_confirm, script.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { scriptToDelete = null }) {
                        Text(stringResource(R.string.overlay_ui_dialog_cancel_button))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                LuaScriptRepository.deleteScript(context, script.id)
                                val msg = context.getString(R.string.lua_script_delete_success, script.name)
                                LuaUiBridge.postLog("[Script] $msg")
                                LuaUiBridge.showToast(msg)
                            }
                            scriptToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.lua_delete_script_title), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Modal dialog for browsing, searching, filtering, importing, saving, and executing Lua script files.
 */
@Composable
private fun LuaScriptSelectionDialog(
    userScripts: List<LuaScriptItem>,
    presets: List<LuaScriptItem>,
    activeScript: LuaScriptItem?,
    onSelectScript: (LuaScriptItem) -> Unit,
    onImportRequest: () -> Unit,
    onSaveRequest: () -> Unit,
    onNewScriptRequest: () -> Unit,
    onDeleteScript: (LuaScriptItem) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterIndex by remember { mutableIntStateOf(0) } // 0: All, 1: Imported, 2: Templates

    val filteredList = remember(searchQuery, selectedFilterIndex, userScripts, presets) {
        val baseList = when (selectedFilterIndex) {
            1 -> userScripts
            2 -> presets
            else -> userScripts + presets
        }
        if (searchQuery.isBlank()) {
            baseList
        } else {
            baseList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val maxListHeight = (windowHeightDp * 0.52f).coerceAtLeast(160.dp)

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.lua_scripts_manager_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Search Bar
            VirtualTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                keyboardType = KeyboardType.QWERTY,
                placeholder = { Text(stringResource(R.string.lua_script_search_placeholder), fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Filter Chips (All, Imported, Templates)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedFilterIndex == 0,
                    onClick = { selectedFilterIndex = 0 },
                    label = { Text(stringResource(R.string.lua_scripts_tab_all), fontSize = 10.sp) },
                    modifier = Modifier.height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
                FilterChip(
                    selected = selectedFilterIndex == 1,
                    onClick = { selectedFilterIndex = 1 },
                    label = {
                        Text(
                            "${stringResource(R.string.lua_scripts_tab_imported)} (${userScripts.size})",
                            fontSize = 10.sp
                        )
                    },
                    modifier = Modifier.height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
                FilterChip(
                    selected = selectedFilterIndex == 2,
                    onClick = { selectedFilterIndex = 2 },
                    label = {
                        Text(
                            "${stringResource(R.string.lua_scripts_tab_presets)} (${presets.size})",
                            fontSize = 10.sp
                        )
                    },
                    modifier = Modifier.height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Scripts List
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.lua_script_no_imported_scripts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(filteredList, key = { it.id }) { item ->
                        val isCurrentActive = activeScript?.id == item.id

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectScript(item) },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrentActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                }
                            ),
                            border = BorderStroke(
                                if (isCurrentActive) 1.dp else 0.5.dp,
                                if (isCurrentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (item.isPreset) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else FrozenIceCyan.copy(alpha = 0.15f),
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (item.isPreset) Icons.Default.Description else Icons.Default.Code,
                                                contentDescription = null,
                                                tint = if (item.isPreset) MaterialTheme.colorScheme.primary else FrozenIceCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (isCurrentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = if (item.isPreset) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = if (item.isPreset) stringResource(R.string.lua_preset_badge) else stringResource(R.string.lua_script_custom_badge),
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (item.isPreset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = item.description.ifBlank { "Lua Script" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (!item.isPreset) {
                                    IconButton(
                                        onClick = { onDeleteScript(item) },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.lua_delete_script_title),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Action Buttons Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // New Script button
                TextButton(
                    onClick = onNewScriptRequest,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.lua_new_script_button), fontSize = 10.sp)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onImportRequest,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.lua_import_script_button), fontSize = 10.sp)
                    }

                    Button(
                        onClick = onSaveRequest,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.lua_save_script_button), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Modal dialog for naming and saving the current script content to internal storage.
 */
@Composable
private fun SaveScriptDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ComponentStyles.Dialog.contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.lua_script_save_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            VirtualTextField(
                value = name,
                onValueChange = { name = it },
                keyboardType = KeyboardType.QWERTY,
                label = { Text(stringResource(R.string.lua_script_save_name_label)) },
                placeholder = { Text(stringResource(R.string.lua_script_save_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.overlay_ui_dialog_cancel_button))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onConfirm(name.trim())
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.hunt_settings_save_button), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Interactive code editor view with dual-axis scrolling, line numbers gutter, and clipboard helper actions.
 */
@Composable
private fun ScriptEditorView(
    context: Context,
    script: String,
    onScriptChange: (String) -> Unit
) {
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val keyboardController = LocalKeyboardController.current
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    val lines = remember(script) { if (script.isEmpty()) listOf("") else script.lines() }
    val lineCount = lines.size
    val charCount = script.length
    val lineNumbersText = remember(lineCount) { (1..lineCount).joinToString("\n") }

    val quickSnippets = remember {
        listOf(
            "()", "[]", "{}", "\"", "=", "==", "~=", "..", ",", ":", ";",
            "~", "+", "-", "*", "/", "_", "\n", "    ",
            "local ", "function ", "end", "if ", "then", "else", "elseif ", "while ", "do ", "return ", "for ", "in ",
            "print(", "hmem.get_pid()", "hmem.read_memory(", "hmem.write_memory(", "hmem.create_menu(", "hmem.log(",
            "gg.searchNumber(", "gg.getResults(", "gg.editAll(", "canvas."
        )
    }

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 1. Editor Action & Metrics Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Metrics Badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = stringResource(R.string.lua_editor_lines_chars_format, lineCount, charCount),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Quick Actions: Copy, Paste, Clear, Focus/Edit
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Copy Button
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .height(24.dp)
                            .clickable {
                                if (script.isNotEmpty()) {
                                    val clip = ClipData.newPlainText("Lua Script", script)
                                    clipboardManager?.setPrimaryClip(clip)
                                    LuaUiBridge.showToast(context.getString(R.string.lua_editor_copied_toast))
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stringResource(R.string.lua_editor_copy_button),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Paste Button
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .height(24.dp)
                            .clickable {
                                val clipItem = clipboardManager?.primaryClip?.let { clip ->
                                    if (clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
                                }
                                if (!clipItem.isNullOrEmpty()) {
                                    val newText = if (script.isBlank()) clipItem else "$script\n$clipItem"
                                    onScriptChange(newText)
                                    if (keyboardController.isVisible.value) {
                                        keyboardController.currentText.value = newText
                                        keyboardController.setCursor(newText.length)
                                    }
                                    LuaUiBridge.showToast(context.getString(R.string.lua_editor_pasted_toast))
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stringResource(R.string.lua_editor_paste_button),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Clear Button
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .height(24.dp)
                            .clickable {
                                onScriptChange("")
                                if (keyboardController.isVisible.value) {
                                    keyboardController.onClear()
                                }
                                LuaUiBridge.showToast(context.getString(R.string.lua_editor_cleared_toast))
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stringResource(R.string.lua_editor_clear_button),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            )
                        }
                    }

                    // Edit Button
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .height(24.dp)
                            .clickable {
                                keyboardController.show(
                                    type = KeyboardType.QWERTY,
                                    initialText = script,
                                    onTextChange = onScriptChange
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            }

            // 2. Quick Lua Syntax & Snippet Toolbar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(quickSnippets) { snippet ->
                    val label = when (snippet) {
                        "\n" -> "⏎ Enter"
                        "    " -> "⇥ Tab"
                        else -> snippet.trim()
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .height(24.dp)
                            .clickable {
                                val newText = script + snippet
                                onScriptChange(newText)
                                if (keyboardController.isVisible.value) {
                                    keyboardController.currentText.value = newText
                                    keyboardController.setCursor(newText.length)
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (snippet.startsWith("hmem") || snippet.startsWith("gg") || snippet.startsWith("canvas")) {
                                    MaterialTheme.colorScheme.primary
                                } else if (snippet.contains("function") || snippet.contains("local") || snippet.contains("if")) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }

            // 3. Scrollable Code Editor Canvas with Line Numbers Gutter
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(6.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.60f)),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Line Numbers Gutter
                    val gutterWidth = (lineCount.toString().length * 8 + 18).dp.coerceIn(28.dp, 60.dp)
                    Surface(
                        modifier = Modifier
                            .width(gutterWidth)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                        border = BorderStroke(0.dp, Color.Transparent)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(verticalScrollState, enabled = false)
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = lineNumbersText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    // Vertical Separator
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )

                    // Code Text Display & Interactive Click-to-Edit Area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(verticalScrollState)
                            .horizontalScroll(horizontalScrollState)
                            .clickable {
                                keyboardController.show(
                                    type = KeyboardType.QWERTY,
                                    initialText = script,
                                    onTextChange = onScriptChange
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        if (script.isEmpty()) {
                            Text(
                                text = "-- Enter or paste Lua script here...\n-- Tap anywhere to edit",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                            )
                        } else {
                            Text(
                                text = script,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dynamically rendered Compose UI view generated on the fly from Lua `hmem.create_menu` declarations.
 */
@Composable
private fun DynamicMenuView(menu: LuaDynamicMenu?) {
    if (menu == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.lua_no_dynamic_menu_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text(
                text = menu.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        items(menu.items) { item ->
            when (item) {
                is LuaMenuItem.Button -> {
                    Button(
                        onClick = { LuaUiBridge.onMenuItemClicked(item.id) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = parseColor(item.colorHex) ?: MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                    ) {
                        Text(
                            text = item.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                is LuaMenuItem.Toggle -> {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Switch(
                                checked = item.isChecked,
                                onCheckedChange = { checked -> LuaUiBridge.onMenuItemToggled(item.id, checked) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SuccessEmerald,
                                    checkedTrackColor = SuccessEmerald.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
                is LuaMenuItem.InputField -> {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = item.label,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            VirtualTextField(
                                value = item.value,
                                onValueChange = { newValue ->
                                    LuaUiBridge.onMenuItemInputChanged(item.id, newValue)
                                },
                                keyboardType = KeyboardType.QWERTY,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
                is LuaMenuItem.Label -> {
                    Text(
                        text = item.text,
                        fontSize = if (item.isHeader) 12.sp else 11.sp,
                        fontWeight = if (item.isHeader) FontWeight.Bold else FontWeight.Normal,
                        color = if (item.isHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is LuaMenuItem.Divider -> {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Interactive console view rendering print and log outputs from executing Lua scripts.
 */
@Composable
private fun ConsoleOutputView(
    logs: List<String>,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lines: ${logs.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.lua_clear_console_button),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.65f)),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.lua_console_empty_placeholder),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                line.startsWith("[Error]") || line.startsWith("[Script Error]") -> Color(0xFFEF4444)
                                line.startsWith("[Menu Action]") -> SuccessEmerald
                                line.startsWith("[Script]") -> Color(0xFF38BDF8)
                                else -> Color(0xFFE2E8F0)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return LuaUiBridge.parseColorFast(hex)
}
