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

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.Keep
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.MemoryScanManager
import com.yervant.huntmem.backend.ShellProcessProvider
import com.yervant.huntmem.ui.CustomDialog
import com.yervant.huntmem.ui.DialogCallback
import com.yervant.huntmem.ui.keyboard.KeyboardType
import com.yervant.huntmem.ui.keyboard.VirtualTextField
import com.yervant.huntmem.ui.theme.MonospaceAddressStyle
import com.yervant.huntmem.ui.theme.MonospaceValueStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class TabState(
    val id: UUID = UUID.randomUUID(),
    var title: String,
    val matches: MutableList<MatchInfo> = mutableListOf(),
    val scanInputVal: MutableState<String> = mutableStateOf(""),
    val valueTypeSelectedOptionIdx: MutableState<Int> = mutableIntStateOf(0),
    val selectedTypes: MutableState<Set<String>> = mutableStateOf(setOf("int")),
    val operatorSelectedOptionIdx: MutableState<Int> = mutableIntStateOf(0),
    val initialScanDone: MutableState<Boolean> = mutableStateOf(false),
    val matchesStatusText: MutableState<String> = mutableStateOf(""),
    val totalMatchesCount: MutableState<Int> = mutableIntStateOf(0),
    val currentMatchesList: MutableState<List<MatchInfo>> = mutableStateOf(emptyList())
)

private val tabs = mutableStateListOf<TabState>()
private val selectedTabIndex = mutableIntStateOf(0)

private fun addTab(context: Context) {
    val newTabIndex = tabs.size
    val newTab = TabState(
        title = context.getString(R.string.memory_menu_default_tab_title, newTabIndex + 1),
        matchesStatusText = mutableStateOf(context.getString(R.string.memory_menu_status_no_matches))
    )
    tabs.add(newTab)
    selectedTabIndex.intValue = newTabIndex
}

private fun ensureInitialTab(context: Context) {
    if (tabs.isEmpty()) {
        addTab(context)
    }
}

private val isScanOnGoing: MutableState<Boolean> = mutableStateOf(false)
private val isRefreshOnGoing: MutableState<Boolean> = mutableStateOf(false)

private val initialOperatorOptions = listOf("=", "!=", ">", "<", ">=", "<=", "? Unknown")
private val refineOperatorOptions = listOf("=", "!=", ">", "<", ">=", "<=", "▲ Increased", "▼ Decreased", "~ Changed", "= Unchanged", "+ Increased by", "- Decreased by")
private val valueTypes: List<String> = listOf("byte", "short", "float16", "int", "long", "float", "double", "bigdouble", "obscured_int", "obscured_float")

@Keep
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
)

fun getCurrentScanOption(): ScanOptions {
    if (tabs.isEmpty()) return ScanOptions("", "int", "=")
    val currentTab = tabs[selectedTabIndex.intValue]
    val types = currentTab.selectedTypes.value
    val typesStr = if (types.size >= valueTypes.size) {
        "all"
    } else if (types.isEmpty()) {
        "int"
    } else {
        types.joinToString(",")
    }
    val ops = if (currentTab.initialScanDone.value) refineOperatorOptions else initialOperatorOptions
    val op = ops.getOrElse(currentTab.operatorSelectedOptionIdx.value) { "=" }
    return ScanOptions(
        inputVal = currentTab.scanInputVal.value,
        valueType = typesStr,
        operator = op
    )
}

@Composable
fun MemoryScanTab(
    context: Context?,
    dialogCallback: DialogCallback
) {
    ensureInitialTab(context!!)

    if (tabs.isNotEmpty()) {
        val currentTab = tabs[selectedTabIndex.intValue]
        LaunchedEffect(selectedTabIndex.intValue, currentTab.currentMatchesList.value.isNotEmpty()) {
            while (isActive) {
                val matchCount = synchronized(currentTab.matches) { currentTab.matches.size }
                if (matchCount in 1..99) {
                    refreshValues(context, dialogCallback, currentTab)
                }
                delay(5.seconds)
            }
        }
    }

    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        MemoryMenu(
            snackbarHostState = snackbarHostState,
            coroutineScope = coroutineScope,
            context = context,
            dialogCallback = dialogCallback,
            modifier = Modifier.padding(padding)
        )
    }
}

suspend fun refreshValues(context: Context, dialogCallback: DialogCallback, tabState: TabState) {
    val pid = AttachedProcessRepository.getAttachedPid()

    val err = context.getString(R.string.memory_menu_error_dialog_title)
    if (pid == null) {
        dialogCallback.showInfoDialog(err, context.getString(R.string.memory_menu_no_process_attached_error), {}, {})
        return
    }

    if (!ShellProcessProvider().isProcessRunning(pid.toString())) {
        dialogCallback.showInfoDialog(err, context.getString(R.string.memory_menu_process_not_exist_error), {}, {})
        resetMatches(context, tabState)
        tabState.initialScanDone.value = false
        return
    }

    if (isScanOnGoing.value) {
        return
    }

    val snapshot: List<MatchInfo>
    synchronized(tabState.matches) {
        if (tabState.matches.isNotEmpty() && tabState.matches.first().pid != pid) {
            dialogCallback.showInfoDialog(
                context.getString(R.string.memory_menu_info_dialog_title),
                context.getString(R.string.memory_menu_process_changed_info), {}, {}
            )
            tabState.matches.clear()
            tabState.currentMatchesList.value = emptyList()
            tabState.matchesStatusText.value = context.getString(R.string.memory_menu_status_no_matches)
            return
        }
        snapshot = tabState.matches.toList()
    }

    if (snapshot.isEmpty()) return

    isRefreshOnGoing.value = true

    val mem = MemoryScanManager()
    val updatedMatches = mem.updateMatchValues(tabState.id.toString())

    if (updatedMatches.isNotEmpty()) {
        synchronized(tabState.matches) {
            tabState.matches.clear()
            tabState.matches.addAll(updatedMatches)
        }
        updateMatches(context, tabState)
    }

    isRefreshOnGoing.value = false
}

@Composable
fun MemoryMenu(
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    context: Context,
    dialogCallback: DialogCallback,
    modifier: Modifier = Modifier
) {
    val isAttached: Boolean = (AttachedProcessRepository.getAttachedPid() != null)
    val showErrorDialog = remember { mutableStateOf(false) }
    val errorDialogMsg = remember { mutableStateOf("") }
    val showValueTypeDialog = remember { mutableStateOf(false) }
    val showSyntaxHelpDialog = remember { mutableStateOf(false) }

    if (showErrorDialog.value) {
        dialogCallback.showInfoDialog(
            title = stringResource(R.string.memory_menu_error_dialog_title),
            message = errorDialogMsg.value,
            onConfirm = { showErrorDialog.value = false },
            onDismiss = {}
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab Selector Bar
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex.intValue,
                edgePadding = 4.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = selectedTabIndex.intValue == index
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTabIndex.intValue = index },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                                if (tabs.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val tabToRemove = tabs[index]
                                            tabs.remove(tabToRemove)
                                            com.yervant.huntmem.backend.NativeBridge.clearSession(tabToRemove.id.toString())
                                            if (selectedTabIndex.intValue >= index && selectedTabIndex.intValue > 0) {
                                                selectedTabIndex.intValue--
                                            }
                                        },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.memory_menu_close_tab_cd),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
                IconButton(
                    onClick = { addTab(context) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.memory_menu_add_tab_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (tabs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.memory_menu_no_tabs_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            val currentTab = tabs[selectedTabIndex.intValue]

            val content: @Composable (matchesTableModifier: Modifier, matchesSettingModifier: Modifier) -> Unit =
                { matchesTableModifier, matchesSettingModifier ->
                    MatchesTable(
                        modifier = matchesTableModifier,
                        matches = currentTab.currentMatchesList.value,
                        matchesStatusText = currentTab.matchesStatusText.value,
                        onMatchClicked = { matchInfo: MatchInfo ->
                            addAddressToTable(matchInfo = matchInfo)
                            coroutineScope.launch {
                                val added = context.getString(R.string.memory_menu_added_to_address_table_snackbar, matchInfo.address.toString(16).uppercase())
                                snackbarHostState.showSnackbar(message = added, duration = SnackbarDuration.Short)
                            }
                        },
                        onCopyAllMatchesToAddressTable = {
                            for (matchInfo in currentTab.currentMatchesList.value)
                                addAddressToTable(matchInfo = matchInfo)
                            coroutineScope.launch {
                                val amat = context.getString(R.string.memory_menu_added_all_to_address_table_snackbar)
                                snackbarHostState.showSnackbar(message = amat, duration = SnackbarDuration.Short)
                            }
                        }
                    )
                    MatchesSetting(
                        context = context,
                        coroutineScope = coroutineScope,
                        modifier = matchesSettingModifier,
                        scanInputVal = currentTab.scanInputVal,
                        selectedTypes = currentTab.selectedTypes,
                        onOpenTypeSelector = { showValueTypeDialog.value = true },
                        onOpenSyntaxHelp = { showSyntaxHelpDialog.value = true },
                        operatorSelectedOptionIdx = currentTab.operatorSelectedOptionIdx,
                        initialScanDone = currentTab.initialScanDone.value,
                        nextScanEnabled = isAttached && !isScanOnGoing.value && !isRefreshOnGoing.value,
                        nextScanClicked = {
                            coroutineScope.launch {
                                if (!currentTab.initialScanDone.value) {
                                    currentTab.title = if (currentTab.scanInputVal.value.isNotBlank()) {
                                        currentTab.scanInputVal.value
                                    } else {
                                        "? (Unknown)"
                                    }
                                }
                                onNextScanClicked(
                                    scanOptions = getCurrentScanOption(),
                                    onBeforeScanStart = { isScanOnGoing.value = true },
                                    onScanDone = {
                                        isScanOnGoing.value = false
                                        currentTab.initialScanDone.value = true
                                        updateMatches(context, currentTab)
                                    },
                                    onScanError = { e: Exception ->
                                        isScanOnGoing.value = false
                                        showErrorDialog.value = true
                                        errorDialogMsg.value = e.stackTraceToString()
                                    },
                                    tabState = currentTab
                                )
                            }
                        },
                        newScanEnabled = isAttached && currentTab.initialScanDone.value && !isScanOnGoing.value,
                        newScanClicked = {
                            coroutineScope.launch {
                                com.yervant.huntmem.backend.NativeBridge.clearSession(currentTab.id.toString())
                                resetMatches(context, currentTab)
                                updateMatches(context, currentTab)
                                currentTab.initialScanDone.value = false
                                currentTab.operatorSelectedOptionIdx.value = 0
                            }
                        },
                    )
                }

            // 70% Results Table : 30% Controls for maximum real-estate
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
                Column(
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content(
                        Modifier.weight(0.70f).padding(horizontal = 6.dp, vertical = 4.dp),
                        Modifier.weight(0.30f).padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content(
                        Modifier.weight(0.60f).padding(horizontal = 6.dp, vertical = 4.dp),
                        Modifier.weight(0.40f).fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (showValueTypeDialog.value && tabs.isNotEmpty()) {
            val currentTab = tabs[selectedTabIndex.intValue]
            ValueTypeSelectionDialog(
                selectedTypes = currentTab.selectedTypes,
                onDismiss = { showValueTypeDialog.value = false }
            )
        }

        if (showSyntaxHelpDialog.value) {
            SearchHelpDialog(
                onDismiss = { showSyntaxHelpDialog.value = false }
            )
        }
    }
}

fun resetMatches(context: Context, tabState: TabState) {
    synchronized(tabState.matches) {
        tabState.matches.clear()
    }
    tabState.totalMatchesCount.value = 0
    tabState.currentMatchesList.value = emptyList()
    tabState.matchesStatusText.value = context.getString(R.string.memory_menu_status_no_matches)
}

fun updateMatches(context: Context, tabState: TabState) {
    val matchesCount = tabState.totalMatchesCount.value
    val shownMatchesCount: Int

    synchronized(tabState.matches) {
        shownMatchesCount = tabState.matches.size

        if (shownMatchesCount > 0) {
            tabState.currentMatchesList.value = tabState.matches.toList()
        } else {
            tabState.currentMatchesList.value = emptyList()
        }
    }

    tabState.matchesStatusText.value = if (matchesCount > 0) {
        context.resources.getQuantityString(R.plurals.memory_menu_status_full_with_showing, matchesCount, matchesCount, shownMatchesCount)
    } else {
        context.resources.getQuantityString(R.plurals.memory_menu_status_full, matchesCount, matchesCount)
    }
}


@Composable
private fun MatchesTable(
    modifier: Modifier = Modifier,
    matches: List<MatchInfo>,
    matchesStatusText: String,
    onMatchClicked: (MatchInfo) -> Unit,
    onCopyAllMatchesToAddressTable: () -> Unit
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = matchesStatusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (matches.isNotEmpty()) {
                    Button(
                        onClick = onCopyAllMatchesToAddressTable,
                        modifier = Modifier.height(26.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(stringResource(R.string.memory_menu_copy_all_button), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            if (matches.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.memory_menu_no_matches_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items = matches, key = { match -> match.id }) { match ->
                        MatchItem(match, onMatchClicked)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchItem(
    match: MatchInfo,
    onClick: (MatchInfo) -> Unit = {}
) {
    val displayedRegion = match.memoryRegion.substringAfterLast('/', match.memoryRegion)

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(match) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "0x${match.address.toString(16).uppercase()}",
                        style = MonospaceAddressStyle.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (displayedRegion.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = displayedRegion,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val valueText = stringResource(R.string.memory_menu_match_item_value_label, match.prevValue.toString())
                Text(
                    text = valueText,
                    style = MonospaceValueStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = RoundedCornerShape(3.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            ) {
                Text(
                    text = match.valueType.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun MatchesSetting(
    context: Context,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
    scanInputVal: MutableState<String>,
    selectedTypes: MutableState<Set<String>>,
    onOpenTypeSelector: () -> Unit,
    onOpenSyntaxHelp: () -> Unit,
    operatorSelectedOptionIdx: MutableState<Int>,
    initialScanDone: Boolean,
    nextScanEnabled: Boolean,
    nextScanClicked: () -> Unit,
    newScanEnabled: Boolean,
    newScanClicked: () -> Unit,
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(6.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1: Scan input + Operator dropdown + Optional Goto
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                VirtualTextField(
                    value = scanInputVal.value,
                    onValueChange = { scanInputVal.value = it },
                    keyboardType = KeyboardType.HEXADECIMAL,
                    label = { Text(stringResource(R.string.memory_menu_scan_value_label), fontSize = 10.sp) },
                    placeholder = { Text("Value / 1.5e6 / xor:100...", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp)
                )

                IconButton(
                    onClick = onOpenSyntaxHelp,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.memory_menu_syntax_help_button_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                val currentOps = if (initialScanDone) refineOperatorOptions else initialOperatorOptions
                CustomDropdown(
                    label = "Op",
                    options = currentOps,
                    selectedIndex = operatorSelectedOptionIdx.value.coerceIn(0, (currentOps.size - 1).coerceAtLeast(0)),
                    onOptionSelected = { operatorSelectedOptionIdx.value = it },
                    modifier = Modifier.width(100.dp)
                )

                if (scanInputVal.value.startsWith("0x", ignoreCase = true)) {
                    Button(
                        onClick = {
                            val currentTab = tabs[selectedTabIndex.intValue]
                            val inputValue = currentTab.scanInputVal.value

                            coroutineScope.launch {
                                val pid = AttachedProcessRepository.getAttachedPid()
                                if (pid != null) {
                                    if (inputValue.contains("+")) {
                                        val result = MemoryScanManager().createMatchFromAddressAndOffset(inputValue, null, pid)
                                        if (result != null) {
                                            synchronized(currentTab.matches) {
                                                currentTab.matches.clear()
                                                currentTab.matches.add(result)
                                            }
                                            currentTab.totalMatchesCount.value = 1
                                        }
                                    } else {
                                        val result = MemoryScanManager().createMatchFromOffset(inputValue, null, pid)
                                        if (result != null) {
                                            synchronized(currentTab.matches) {
                                                currentTab.matches.clear()
                                                currentTab.matches.add(result)
                                            }
                                            currentTab.totalMatchesCount.value = 1
                                        }
                                    }
                                }

                                currentTab.initialScanDone.value = true
                                currentTab.title = inputValue
                                updateMatches(context, currentTab)
                            }
                        },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(stringResource(id = R.string.memory_menu_goto_button), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Row 2: Type Selector Pill + Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ValueTypeSelectorTrigger(
                    selectedTypes = selectedTypes,
                    enabled = !initialScanDone,
                    onClick = onOpenTypeSelector,
                    modifier = Modifier.weight(0.9f)
                )

                ScanButton(
                    text = stringResource(R.string.memory_menu_new_scan_button),
                    enabled = newScanEnabled,
                    isLoading = false,
                    isSecondary = true,
                    onClick = newScanClicked,
                    modifier = Modifier.weight(0.8f)
                )

                ScanButton(
                    text = if (!initialScanDone) stringResource(id = R.string.memory_menu_new_search_button_initial) else stringResource(id = R.string.memory_menu_refine_button),
                    enabled = nextScanEnabled,
                    isLoading = isScanOnGoing.value,
                    isSecondary = false,
                    onClick = nextScanClicked,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CustomDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(38.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            contentPadding = PaddingValues(horizontal = 6.dp)
        ) {
            Text(
                text = options.getOrElse(selectedIndex) { "=" },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, false),
                fontSize = 11.sp
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(180.dp)
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(text = option, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onOptionSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ScanButton(
    text: String,
    enabled: Boolean,
    isLoading: Boolean,
    isSecondary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonColors = if (isSecondary) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    }

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = buttonColors
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ValueTypeSelectorTrigger(
    selectedTypes: MutableState<Set<String>>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = selectedTypes.value
    val allTypes = listOf("byte", "short", "int", "long", "float", "double")
    val isAllSelected = selected.size >= allTypes.size

    val allText = stringResource(R.string.memory_menu_value_types_all)
    val noneText = stringResource(R.string.memory_menu_value_types_none)

    val displayText = when {
        isAllSelected -> allText
        selected.isEmpty() -> noneText
        selected.size == 1 -> selected.first().uppercase()
        else -> selected.joinToString(",") { it.uppercase() }
    }

    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        modifier = modifier.height(38.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.memory_menu_type_label_prefix, displayText),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp
            )
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = stringResource(R.string.memory_menu_select_types_cd),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private data class TypeOptionItem(
    val key: String,
    val sizeText: String,
    val detailText: String
)

private val allTypeOptionItems = listOf(
    TypeOptionItem("byte", "1 Byte", "8-bit int"),
    TypeOptionItem("short", "2 Bytes", "16-bit int"),
    TypeOptionItem("float16", "2 Bytes", "Half float"),
    TypeOptionItem("int", "4 Bytes", "32-bit int"),
    TypeOptionItem("long", "8 Bytes", "64-bit int"),
    TypeOptionItem("float", "4 Bytes", "Single float"),
    TypeOptionItem("double", "8 Bytes", "Double float"),
    TypeOptionItem("bigdouble", "16 Bytes", "Mantissa/Exp"),
    TypeOptionItem("obscured_int", "8 Bytes", "ACTk XOR Int"),
    TypeOptionItem("obscured_float", "8 Bytes", "ACTk XOR Float"),
)

@Composable
private fun SearchHelpDialog(
    onDismiss: () -> Unit
) {
    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.memory_menu_syntax_help_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.overlay_ui_close_menu_icon_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = stringResource(R.string.memory_menu_syntax_help_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
                fontSize = 11.sp
            )

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(R.string.ok), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ValueTypeSelectionDialog(
    selectedTypes: MutableState<Set<String>>,
    onDismiss: () -> Unit
) {
    var tempSelected by remember { mutableStateOf(selectedTypes.value) }
    val allKeys = remember { allTypeOptionItems.map { it.key }.toSet() }
    val integerKeys = remember { setOf("byte", "short", "int", "long") }
    val floatKeys = remember { setOf("float16", "float", "double", "bigdouble") }
    val specialKeys = remember { setOf("bigdouble", "obscured_int", "obscured_float") }

    val isAllSelected = tempSelected.size >= allKeys.size

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
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
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.memory_menu_select_types_dialog_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.overlay_ui_close_menu_icon_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Quick Preset Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                FilterChip(
                    selected = isAllSelected,
                    onClick = {
                        tempSelected = if (isAllSelected) setOf("int") else allKeys
                    },
                    label = { Text("ALL", fontSize = 8.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == integerKeys,
                    onClick = { tempSelected = integerKeys },
                    label = { Text("INTs", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == floatKeys,
                    onClick = { tempSelected = floatKeys },
                    label = { Text("FLOATs", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == specialKeys,
                    onClick = { tempSelected = specialKeys },
                    label = { Text("SPEC", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == setOf("int"),
                    onClick = { tempSelected = setOf("int") },
                    label = { Text("RESET", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            // Grid of Types
            val chunked = allTypeOptionItems.chunked(2)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                chunked.forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rowItems.forEach { item ->
                            val isChecked = tempSelected.contains(item.key)
                            TypeOptionCard(
                                item = item,
                                isChecked = isChecked,
                                onClick = {
                                    val current = tempSelected.toMutableSet()
                                    if (isChecked) {
                                        if (current.size > 1) {
                                            current.remove(item.key)
                                        }
                                    } else {
                                        current.add(item.key)
                                    }
                                    tempSelected = current
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Footer Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.memory_menu_types_selected_count, tempSelected.size, allTypeOptionItems.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.height(32.dp)) {
                        Text(stringResource(R.string.overlay_ui_dialog_cancel_button), fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            if (tempSelected.isNotEmpty()) {
                                selectedTypes.value = tempSelected
                            }
                            onDismiss()
                        },
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(stringResource(R.string.ok), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeOptionCard(
    item: TypeOptionItem,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isChecked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    val containerColor = if (isChecked) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(if (isChecked) 1.dp else 0.5.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(16.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.key.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Medium,
                    color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${item.sizeText} • ${item.detailText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
