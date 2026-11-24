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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.MemoryEngine
import com.yervant.huntmem.backend.MemoryScanManager
import com.yervant.huntmem.backend.NativeBridge
import com.yervant.huntmem.backend.ShellProcessProvider
import com.yervant.huntmem.ui.CustomDialog
import com.yervant.huntmem.ui.DialogCallback
import com.yervant.huntmem.ui.keyboard.KeyboardType
import com.yervant.huntmem.ui.keyboard.VirtualTextField
import com.yervant.huntmem.ui.theme.ComponentStyles
import com.yervant.huntmem.ui.theme.MonospaceAddressStyle
import com.yervant.huntmem.ui.theme.MonospaceValueStyle
import com.yervant.huntmem.ui.theme.SuccessEmerald
import com.yervant.huntmem.ui.theme.TertiaryAmber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Snapshot of UI control values saved alongside a native session history checkpoint.
 */
data class UiHistoryEntry(
    val inputVal: String,
    val operatorIdx: Int,
    val step: Int,
    val isSaved: Boolean
)

/**
 * State container for an independent memory scan tab session.
 *
 * @property id Unique UUID identifying this session in native core memory caches.
 * @property title Reactive tab display title.
 * @property matches Thread-safe list of active memory scan matches.
 * @property scanInputVal Reactive text input state for search expressions.
 * @property valueTypeSelectedOptionIdx Dropdown selection index for primary value type.
 * @property selectedTypes Selected set of memory value types.
 * @property operatorSelectedOptionIdx Dropdown selection index for comparison operator.
 * @property initialScanDone Whether the first scan pass has been completed for this tab.
 * @property matchesStatusText Status label reporting count of total and visible matches.
 * @property totalMatchesCount Total count of matches found across process memory.
 * @property currentMatchesList Immutable snapshot of matches rendered by Compose LazyColumn.
 */
data class TabState(
    val id: UUID = UUID.randomUUID(),
    val title: MutableState<String>,
    val matches: MutableList<MatchInfo> = mutableListOf(),
    val scanInputVal: MutableState<String> = mutableStateOf(""),
    val valueTypeSelectedOptionIdx: MutableState<Int> = mutableIntStateOf(0),
    val selectedTypes: MutableState<Set<String>> = mutableStateOf(setOf("int")),
    val operatorSelectedOptionIdx: MutableState<Int> = mutableIntStateOf(0),
    val initialScanDone: MutableState<Boolean> = mutableStateOf(false),
    val matchesStatusText: MutableState<String> = mutableStateOf(""),
    val totalMatchesCount: MutableState<Int> = mutableIntStateOf(0),
    val currentMatchesList: MutableState<List<MatchInfo>> = mutableStateOf(emptyList()),
    val undoCount: MutableState<Int> = mutableIntStateOf(0),
    val isCurrentSaved: MutableState<Boolean> = mutableStateOf(false),
    val savedCheckpointsCount: MutableState<Int> = mutableIntStateOf(0),
    val refinementStep: MutableState<Int> = mutableIntStateOf(0),
    val uiHistoryStack: MutableList<UiHistoryEntry> = mutableListOf(),
    val isSelectionMode: MutableState<Boolean> = mutableStateOf(false),
    val selectedAddresses: MutableState<Set<Long>> = mutableStateOf(emptySet())
)

/**
 * Reactive state holder for memory scan tabs and ongoing scan status scoped to the active overlay session.
 */
object MemoryScanRepository {
    val tabs = mutableStateListOf<TabState>()
    val selectedTabIndex = mutableIntStateOf(0)
    val isScanOnGoing: MutableState<Boolean> = mutableStateOf(false)
    val isRefreshOnGoing: MutableState<Boolean> = mutableStateOf(false)

    fun onOverlayDestroyed(scope: CoroutineScope) {
        MemoryEngine.cancelScan()
        val sessionIds = tabs.map { it.id.toString() }
        tabs.clear()
        selectedTabIndex.intValue = 0
        isScanOnGoing.value = false
        isRefreshOnGoing.value = false
        if (sessionIds.isNotEmpty()) {
            scope.launch(Dispatchers.IO + NonCancellable) {
                for (sid in sessionIds) {
                    runCatching { NativeBridge.clearSession(sid) }
                }
            }
        }
    }
}

private val tabs get() = MemoryScanRepository.tabs
private val selectedTabIndex get() = MemoryScanRepository.selectedTabIndex

private fun addTab(
    context: Context,
    customTitle: String? = null,
    selectedTypes: Set<String> = setOf("int"),
    valueTypeIdx: Int = 0,
    scanInput: String = ""
): TabState {
    val newTabIndex = tabs.size
    val newTab = TabState(
        title = mutableStateOf(
            customTitle ?: context.getString(R.string.memory_menu_default_tab_title, newTabIndex + 1)
        ),
        scanInputVal = mutableStateOf(scanInput),
        valueTypeSelectedOptionIdx = mutableIntStateOf(valueTypeIdx),
        selectedTypes = mutableStateOf(selectedTypes),
        matchesStatusText = mutableStateOf(context.getString(R.string.memory_menu_status_no_matches))
    )
    tabs.add(newTab)
    selectedTabIndex.intValue = newTabIndex
    return newTab
}

private fun ensureInitialTab(context: Context) {
    ensureSettingsLoaded(context)
    if (tabs.isEmpty()) {
        addTab(context)
    }
}

private val isScanOnGoing: MutableState<Boolean> get() = MemoryScanRepository.isScanOnGoing
private val isRefreshOnGoing: MutableState<Boolean> get() = MemoryScanRepository.isRefreshOnGoing

private val initialOperatorOptions = listOf("=", "!=", ">", "<", ">=", "<=", "? Unknown")
private val refineOperatorOptions = listOf("=", "!=", ">", "<", ">=", "<=", "▲ Increased", "▼ Decreased", "~ Changed", "= Unchanged", "+ Increased by", "- Decreased by")
private val valueTypes: List<String> = listOf("byte", "short", "float16", "int", "long", "float", "double", "bigdouble", "obscured_int", "obscured_float")

typealias MatchInfo = com.yervant.huntmem.backend.MatchInfo

private sealed class CopyToTabRequest {
    abstract val sourceTab: TabState

    data class CloneAll(override val sourceTab: TabState) : CopyToTabRequest()
    data class CopyItems(override val sourceTab: TabState, val addresses: List<Long>) : CopyToTabRequest()
}

/**
 * Shared guided banner shown when no target process is currently attached.
 */
@Composable
internal fun NoProcessAttachedBanner(
    onSelectProcessClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = TertiaryAmber.copy(alpha = 0.14f)
        ),
        border = BorderStroke(1.dp, TertiaryAmber.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = TertiaryAmber,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.no_process_banner_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TertiaryAmber,
                    fontSize = 10.5.sp
                )
                Text(
                    text = stringResource(R.string.no_process_banner_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp
                )
            }
            Button(
                onClick = onSelectProcessClick,
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TertiaryAmber,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = stringResource(R.string.no_process_banner_action),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

private suspend fun syncTabHistoryState(tabState: TabState) {
    val sessionId = tabState.id.toString()
    val (undoCnt, savedNow) = withContext(Dispatchers.IO) {
        Pair(
            MemoryEngine.getSessionUndoCount(sessionId),
            MemoryEngine.isSessionSaved(sessionId)
        )
    }
    tabState.undoCount.value = undoCnt
    tabState.isCurrentSaved.value = savedNow
    tabState.savedCheckpointsCount.value =
        tabState.uiHistoryStack.count { it.isSaved } + (if (savedNow) 1 else 0)
}

/**
 * Resolves current scan parameters (input, selected types, operator) for the active tab.
 */
fun getCurrentScanOption(): ScanOptions {
    if (tabs.isEmpty()) return ScanOptions("", "int", "=")
    val currentTab = tabs.getOrNull(selectedTabIndex.intValue) ?: return ScanOptions("", "int", "=")
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

/**
 * Main Composable tab hosting the Process Memory Scanner and Editor.
 *
 * Supports multi-tab memory sessions, exact/group/range/relative scans, live value updating,
 * and seamless transfer of resolved addresses to the Saved Address Table.
 *
 * @param context Android application/service Context.
 * @param dialogCallback Callback handler for interactive modal dialogs.
 * @param modifier Optional layout modifier.
 */
@Composable
fun MemoryScanTab(
    context: Context,
    dialogCallback: DialogCallback,
    modifier: Modifier = Modifier,
    onNavigateToProcesses: () -> Unit = {}
) {
    ensureInitialTab(context)

    val currentTab = tabs.getOrNull(selectedTabIndex.intValue)
    if (currentTab != null) {
        LaunchedEffect(currentTab.id, currentTab.currentMatchesList.value.isNotEmpty()) {
            while (isActive) {
                val matchCount = synchronized(currentTab.matches) { currentTab.matches.size }
                if (matchCount in 1..200) {
                    refreshValues(context, dialogCallback, currentTab, userInitiated = false)
                }
                delay(1500L.milliseconds)
            }
        }
    }

    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        MemoryMenu(
            snackbarHostState = snackbarHostState,
            coroutineScope = coroutineScope,
            context = context,
            dialogCallback = dialogCallback,
            onNavigateToProcesses = onNavigateToProcesses,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * Refreshes live memory values for all active matches in the current tab session.
 *
 * @param context Android application/service Context.
 * @param dialogCallback Callback handler for interactive modal dialogs.
 * @param tabState Active tab whose match values should be refreshed.
 * @param userInitiated Whether this refresh was triggered by explicit user action.
 */
suspend fun refreshValues(
    context: Context,
    dialogCallback: DialogCallback,
    tabState: TabState,
    userInitiated: Boolean = false
) {
    val pid = AttachedProcessRepository.getAttachedPid()

    val err = context.getString(R.string.memory_menu_error_dialog_title)
    if (pid == null) {
        if (userInitiated) {
            dialogCallback.showInfoDialog(err, context.getString(R.string.memory_menu_no_process_attached_error), {}, {})
        }
        return
    }

    val isRunning = withContext(Dispatchers.IO) {
        ShellProcessProvider().isProcessRunning(pid.toString())
    }
    if (!isRunning) {
        if (userInitiated) {
            dialogCallback.showInfoDialog(err, context.getString(R.string.memory_menu_process_not_exist_error), {}, {})
        }
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
            resetMatches(context, tabState)
            tabState.initialScanDone.value = false
            return
        }
        snapshot = tabState.matches.toList()
    }

    if (snapshot.isEmpty()) return

    isRefreshOnGoing.value = true
    try {
        val updatedMatches = withContext(Dispatchers.IO) {
            val mem = MemoryScanManager()
            mem.updateMatchValues(tabState.id.toString(), limit = snapshot.size.coerceAtLeast(100))
        }

        if (updatedMatches.isNotEmpty()) {
            val merged = mergePreviousValues(snapshot, updatedMatches)
            synchronized(tabState.matches) {
                tabState.matches.clear()
                tabState.matches.addAll(merged)
            }
            updateMatches(context, tabState)
        }
    } finally {
        isRefreshOnGoing.value = false
    }
}

/**
 * Main UI layout containing the tab bar, results list, input controls, and action triggers.
 */
@Composable
fun MemoryMenu(
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    context: Context,
    dialogCallback: DialogCallback,
    onNavigateToProcesses: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()
    val isAttached = attachedPid != null
    val showErrorDialog = remember { mutableStateOf(false) }
    val errorDialogMsg = remember { mutableStateOf("") }
    val showValueTypeDialog = remember { mutableStateOf(false) }
    val showSyntaxHelpDialog = remember { mutableStateOf(false) }
    var copyToTabRequest by remember { mutableStateOf<CopyToTabRequest?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(showErrorDialog.value) {
        if (showErrorDialog.value) {
            dialogCallback.showInfoDialog(
                title = context.getString(R.string.memory_menu_error_dialog_title),
                message = errorDialogMsg.value,
                onConfirm = { showErrorDialog.value = false },
                onDismiss = { showErrorDialog.value = false }
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isAttached) {
                NoProcessAttachedBanner(
                    onSelectProcessClick = onNavigateToProcesses,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }

            // Tab Selector Bar
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex.intValue.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                edgePadding = 4.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = selectedTabIndex.intValue == index
                    val tabLabel = if (tab.initialScanDone.value) {
                        "${tab.title.value} (${tab.totalMatchesCount.value})"
                    } else {
                        tab.title.value
                    }
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTabIndex.intValue = index },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = tabLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                                if (tabs.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val tabToRemove = tabs[index]
                                            val sessionIdToClear = tabToRemove.id.toString()
                                            tabs.remove(tabToRemove)
                                            if (selectedTabIndex.intValue >= tabs.size && selectedTabIndex.intValue > 0) {
                                                selectedTabIndex.intValue = tabs.size - 1
                                            } else if (selectedTabIndex.intValue > index) {
                                                selectedTabIndex.intValue--
                                            }
                                            coroutineScope.launch(Dispatchers.IO) {
                                                NativeBridge.clearSession(sessionIdToClear)
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

            val currentTab = tabs.getOrNull(selectedTabIndex.intValue) ?: return@Column

            val content: @Composable (matchesTableModifier: Modifier, matchesSettingModifier: Modifier) -> Unit =
                { matchesTableModifier, matchesSettingModifier ->
                    MatchesTable(
                        modifier = matchesTableModifier,
                        matches = currentTab.currentMatchesList.value,
                        totalMatchesCount = currentTab.totalMatchesCount.value,
                        matchesStatusText = currentTab.matchesStatusText.value,
                        refinementStep = currentTab.refinementStep.value,
                        savedCheckpointsCount = currentTab.savedCheckpointsCount.value,
                        isSelectionMode = currentTab.isSelectionMode.value,
                        selectedAddresses = currentTab.selectedAddresses.value,
                        isLoadingMore = isLoadingMore,
                        onMatchClicked = { matchInfo: MatchInfo ->
                            addAddressToTable(matchInfo = matchInfo)
                            coroutineScope.launch {
                                val added = context.getString(R.string.memory_menu_added_to_address_table_snackbar, matchInfo.address.toString(16).uppercase())
                                snackbarHostState.showSnackbar(message = added, duration = SnackbarDuration.Short)
                            }
                        },
                        onCopyAllMatchesToAddressTable = {
                            for (matchInfo in currentTab.currentMatchesList.value) {
                                addAddressToTable(matchInfo = matchInfo)
                            }
                            coroutineScope.launch {
                                val amat = context.getString(R.string.memory_menu_added_all_to_address_table_snackbar)
                                snackbarHostState.showSnackbar(message = amat, duration = SnackbarDuration.Short)
                            }
                        },
                        onToggleSelectionMode = {
                            val next = !currentTab.isSelectionMode.value
                            currentTab.isSelectionMode.value = next
                            if (!next) {
                                currentTab.selectedAddresses.value = emptySet()
                            }
                        },
                        onToggleItemSelection = { match ->
                            val current = currentTab.selectedAddresses.value.toMutableSet()
                            if (!current.add(match.address)) {
                                current.remove(match.address)
                            }
                            currentTab.selectedAddresses.value = current
                        },
                        onSelectAllVisible = {
                            val visibleAddrs = currentTab.currentMatchesList.value.map { it.address }.toSet()
                            currentTab.selectedAddresses.value =
                                if (currentTab.selectedAddresses.value.containsAll(visibleAddrs) && visibleAddrs.isNotEmpty()) {
                                    emptySet()
                                } else {
                                    visibleAddrs
                                }
                        },
                        onSendSelectedToEditor = {
                            val selectedSet = currentTab.selectedAddresses.value
                            val toSend = currentTab.currentMatchesList.value.filter { it.address in selectedSet }
                            if (toSend.isNotEmpty()) {
                                toSend.forEach { addAddressToTable(it) }
                                currentTab.isSelectionMode.value = false
                                currentTab.selectedAddresses.value = emptySet()
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(
                                            R.string.memory_menu_added_selected_to_address_table_snackbar,
                                            toSend.size
                                        ),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        },
                        onCopyMatchToTab = { match ->
                            copyToTabRequest = CopyToTabRequest.CopyItems(currentTab, listOf(match.address))
                        },
                        onCopyToTabClicked = {
                            val selected = currentTab.selectedAddresses.value
                            copyToTabRequest = if (currentTab.isSelectionMode.value && selected.isNotEmpty()) {
                                CopyToTabRequest.CopyItems(currentTab, selected.toList())
                            } else {
                                CopyToTabRequest.CloneAll(currentTab)
                            }
                        },
                        onLoadMore = {
                            val pid = attachedPid
                            if (pid != null && !isLoadingMore) {
                                isLoadingMore = true
                                coroutineScope.launch {
                                    try {
                                        val offset = synchronized(currentTab.matches) { currentTab.matches.size }
                                        val (total, pageMatches) = MemoryEngine.refreshResultsPage(
                                            pid = pid,
                                            sessionId = currentTab.id.toString(),
                                            offset = offset,
                                            limit = 100
                                        )
                                        if (pageMatches.isNotEmpty()) {
                                            synchronized(currentTab.matches) {
                                                val existingIds = currentTab.matches.map { it.id }.toHashSet()
                                                for (m in pageMatches) {
                                                    if (existingIds.add(m.id)) {
                                                        currentTab.matches.add(m)
                                                    }
                                                }
                                            }
                                        }
                                        currentTab.totalMatchesCount.value = total
                                        updateMatches(context, currentTab)
                                    } finally {
                                        isLoadingMore = false
                                    }
                                }
                            }
                        }
                    )
                    MatchesSetting(
                        modifier = matchesSettingModifier,
                        scanInputVal = currentTab.scanInputVal,
                        selectedTypes = currentTab.selectedTypes,
                        onOpenTypeSelector = { showValueTypeDialog.value = true },
                        onOpenSyntaxHelp = { showSyntaxHelpDialog.value = true },
                        operatorSelectedOptionIdx = currentTab.operatorSelectedOptionIdx,
                        initialScanDone = currentTab.initialScanDone.value,
                        totalMatchesCount = currentTab.totalMatchesCount.value,
                        undoCount = currentTab.undoCount.value,
                        isCurrentSaved = currentTab.isCurrentSaved.value,
                        nextScanEnabled = isAttached && !isScanOnGoing.value && !isRefreshOnGoing.value,
                        nextScanClicked = {
                            coroutineScope.launch {
                                val wasInitial = !currentTab.initialScanDone.value
                                onNextScanClicked(
                                    scanOptions = getCurrentScanOption(),
                                    onBeforeScanStart = { isScanOnGoing.value = true },
                                    onScanDone = {
                                        isScanOnGoing.value = false
                                        if (wasInitial) {
                                            currentTab.title.value = currentTab.scanInputVal.value.ifBlank {
                                                "? (Unknown)"
                                            }
                                        }
                                        currentTab.initialScanDone.value = true
                                        updateMatches(context, currentTab)
                                    },
                                    onScanError = { e: Exception ->
                                        isScanOnGoing.value = false
                                        errorDialogMsg.value = formatFriendlyScanError(
                                            context = context,
                                            error = e,
                                            input = currentTab.scanInputVal.value,
                                            valueType = getCurrentScanOption().valueType
                                        )
                                        showErrorDialog.value = true
                                    },
                                    tabState = currentTab
                                )
                            }
                        },
                        newScanEnabled = isAttached && currentTab.initialScanDone.value && !isScanOnGoing.value,
                        newScanClicked = {
                            val sessionIdToClear = currentTab.id.toString()
                            resetMatches(context, currentTab)
                            updateMatches(context, currentTab)
                            currentTab.initialScanDone.value = false
                            currentTab.operatorSelectedOptionIdx.value = 0
                            coroutineScope.launch(Dispatchers.IO) {
                                NativeBridge.clearSession(sessionIdToClear)
                            }
                        },
                        gotoClicked = {
                            val inputValue = currentTab.scanInputVal.value.trim()
                            coroutineScope.launch {
                                val pid = attachedPid
                                if (pid == null) {
                                    errorDialogMsg.value = context.getString(R.string.memory_menu_friendly_error_no_process)
                                    showErrorDialog.value = true
                                    return@launch
                                }
                                val primaryType = getCurrentScanOption().valueType
                                    .split(",", "|", ";")
                                    .firstOrNull { it.isNotBlank() && it != "all" } ?: "int"
                                val result = withContext(Dispatchers.IO) {
                                    MemoryScanManager().createMatchFromOffset(inputValue, primaryType, pid)
                                }
                                if (result != null) {
                                    synchronized(currentTab.matches) {
                                        currentTab.matches.clear()
                                        currentTab.matches.add(result)
                                    }
                                    currentTab.totalMatchesCount.value = 1
                                    currentTab.refinementStep.value = 1
                                    currentTab.initialScanDone.value = true
                                    currentTab.title.value = inputValue
                                    updateMatches(context, currentTab)
                                } else {
                                    errorDialogMsg.value = context.getString(
                                        R.string.memory_menu_friendly_error_goto_failed,
                                        inputValue
                                    )
                                    showErrorDialog.value = true
                                }
                            }
                        },
                        onSaveCheckpointClicked = {
                            coroutineScope.launch {
                                val saved = MemoryEngine.saveSessionCheckpoint(currentTab.id.toString())
                                if (saved) {
                                    currentTab.isCurrentSaved.value = true
                                    currentTab.savedCheckpointsCount.value =
                                        currentTab.uiHistoryStack.count { it.isSaved } + 1
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.memory_menu_checkpoint_saved_snackbar),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        },
                        onUndoClicked = {
                            val pid = attachedPid ?: return@MatchesSetting
                            coroutineScope.launch {
                                val result = MemoryEngine.undoSessionFast(
                                    pid = pid,
                                    sessionId = currentTab.id.toString(),
                                    limit = 100
                                )
                                result.onSuccess { (count, restoredMatches) ->
                                    synchronized(currentTab.matches) {
                                        currentTab.matches.clear()
                                        currentTab.matches.addAll(restoredMatches)
                                    }
                                    currentTab.totalMatchesCount.value = count
                                    currentTab.isSelectionMode.value = false
                                    currentTab.selectedAddresses.value = emptySet()

                                    val poppedUi = currentTab.uiHistoryStack.removeLastOrNull()
                                    if (poppedUi != null) {
                                        currentTab.scanInputVal.value = poppedUi.inputVal
                                        currentTab.operatorSelectedOptionIdx.value = poppedUi.operatorIdx
                                        currentTab.refinementStep.value = poppedUi.step
                                    } else if (currentTab.refinementStep.value > 1) {
                                        currentTab.refinementStep.value--
                                    }
                                    syncTabHistoryState(currentTab)
                                    updateMatches(context, currentTab)
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.memory_menu_undo_restored_snackbar, count),
                                        duration = SnackbarDuration.Short
                                    )
                                }.onFailure { err ->
                                    errorDialogMsg.value = formatFriendlyScanError(
                                        context = context,
                                        error = err,
                                        input = currentTab.scanInputVal.value,
                                        valueType = getCurrentScanOption().valueType
                                    )
                                    showErrorDialog.value = true
                                }
                            }
                        }
                    )
                }

            // Results Table : Controls layout split
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
                Column(
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content(
                        Modifier.weight(0.66f).padding(horizontal = 6.dp, vertical = 2.dp),
                        Modifier.weight(0.34f).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content(
                        Modifier.weight(0.58f).padding(horizontal = 4.dp, vertical = 2.dp),
                        Modifier.weight(0.42f).fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (showValueTypeDialog.value && tabs.isNotEmpty()) {
            val currentTab = tabs.getOrNull(selectedTabIndex.intValue)
            if (currentTab != null) {
                ValueTypeSelectionDialog(
                    selectedTypes = currentTab.selectedTypes,
                    onDismiss = { showValueTypeDialog.value = false }
                )
            }
        }

        if (showSyntaxHelpDialog.value) {
            val currentTab = tabs.getOrNull(selectedTabIndex.intValue)
            SearchHelpDialog(
                onSelectExample = { example ->
                    currentTab?.scanInputVal?.value = example
                    showSyntaxHelpDialog.value = false
                },
                onDismiss = { showSyntaxHelpDialog.value = false }
            )
        }

        copyToTabRequest?.let { req ->
            val pid = attachedPid
            val existingTabs = tabs.filter { it.id != req.sourceTab.id }
            CopyToSearchTabDialog(
                request = req,
                existingTabs = existingTabs,
                onSelectNewTab = {
                    val src = req.sourceTab
                    val suffixTitle = if (req is CopyToTabRequest.CloneAll) {
                        "${src.title.value} (2)"
                    } else {
                        null
                    }
                    val newTab = addTab(
                        context = context,
                        customTitle = suffixTitle,
                        selectedTypes = src.selectedTypes.value,
                        valueTypeIdx = src.valueTypeSelectedOptionIdx.value,
                        scanInput = if (req is CopyToTabRequest.CloneAll) src.scanInputVal.value else ""
                    )
                    copyToTabRequest = null
                    coroutineScope.launch {
                        executeCopyToTab(
                            context = context,
                            pid = pid,
                            request = req,
                            targetTab = newTab,
                            snackbarHostState = snackbarHostState
                        )
                    }
                },
                onSelectExistingTab = { targetTab ->
                    copyToTabRequest = null
                    val targetIdx = tabs.indexOf(targetTab)
                    if (targetIdx != -1) {
                        selectedTabIndex.intValue = targetIdx
                    }
                    coroutineScope.launch {
                        executeCopyToTab(
                            context = context,
                            pid = pid,
                            request = req,
                            targetTab = targetTab,
                            snackbarHostState = snackbarHostState
                        )
                    }
                },
                onDismiss = { copyToTabRequest = null }
            )
        }
    }
}

private suspend fun executeCopyToTab(
    context: Context,
    pid: Int?,
    request: CopyToTabRequest,
    targetTab: TabState,
    snackbarHostState: SnackbarHostState
) {
    val srcTab = request.sourceTab
    val effectivePid = pid ?: AttachedProcessRepository.getAttachedPid()
    if (effectivePid == null) {
        snackbarHostState.showSnackbar(
            message = context.getString(R.string.memory_menu_no_process_attached_error),
            duration = SnackbarDuration.Short
        )
        return
    }

    val nativeResult = when (request) {
        is CopyToTabRequest.CloneAll ->
            MemoryEngine.cloneSessionFast(
                pid = effectivePid,
                sourceSessionId = srcTab.id.toString(),
                targetSessionId = targetTab.id.toString(),
                limit = 100
            )
        is CopyToTabRequest.CopyItems ->
            MemoryEngine.copyMatchesToSessionFast(
                pid = effectivePid,
                sourceSessionId = srcTab.id.toString(),
                targetSessionId = targetTab.id.toString(),
                addresses = request.addresses.toLongArray(),
                limit = 100
            )
    }

    nativeResult.onSuccess { (count, copiedMatches) ->
        synchronized(targetTab.matches) {
            targetTab.matches.clear()
            targetTab.matches.addAll(copiedMatches)
        }
        targetTab.totalMatchesCount.value = count
        targetTab.initialScanDone.value = true
        if (request is CopyToTabRequest.CloneAll) {
            targetTab.refinementStep.value = srcTab.refinementStep.value.coerceAtLeast(1)
            targetTab.uiHistoryStack.clear()
        } else if (targetTab.refinementStep.value == 0) {
            targetTab.refinementStep.value = 1
        }
        targetTab.selectedTypes.value = srcTab.selectedTypes.value
        syncTabHistoryState(targetTab)
        updateMatches(context, targetTab)
    }.onFailure {
        // Fallback when source tab only contains local direct-address matches without a native session
        val fallbackItems = synchronized(srcTab.matches) {
            when (request) {
                is CopyToTabRequest.CloneAll -> srcTab.matches.toList()
                is CopyToTabRequest.CopyItems -> {
                    val addrSet = request.addresses.toHashSet()
                    srcTab.matches.filter { it.address in addrSet }
                }
            }
        }
        synchronized(targetTab.matches) {
            val existingKeys = targetTab.matches.map { it.address to it.valueType }.toHashSet()
            for (item in fallbackItems) {
                if (existingKeys.add(item.address to item.valueType)) {
                    targetTab.matches.add(item.copy(id = "${targetTab.id}-${item.address}"))
                }
            }
            targetTab.totalMatchesCount.value = targetTab.matches.size
        }
        targetTab.initialScanDone.value = targetTab.totalMatchesCount.value > 0
        if (targetTab.refinementStep.value == 0 && targetTab.initialScanDone.value) {
            targetTab.refinementStep.value = 1
        }
        updateMatches(context, targetTab)
    }

    srcTab.isSelectionMode.value = false
    srcTab.selectedAddresses.value = emptySet()

    snackbarHostState.showSnackbar(
        message = context.getString(R.string.memory_menu_copied_to_tab_snackbar, targetTab.title.value),
        duration = SnackbarDuration.Short
    )
}

/**
 * Clears recorded matches and resets total count for the specified tab.
 */
fun resetMatches(context: Context, tabState: TabState) {
    synchronized(tabState.matches) {
        tabState.matches.clear()
    }
    tabState.totalMatchesCount.value = 0
    tabState.currentMatchesList.value = emptyList()
    tabState.matchesStatusText.value = context.getString(R.string.memory_menu_status_no_matches)
    tabState.undoCount.value = 0
    tabState.isCurrentSaved.value = false
    tabState.savedCheckpointsCount.value = 0
    tabState.refinementStep.value = 0
    tabState.uiHistoryStack.clear()
    tabState.isSelectionMode.value = false
    tabState.selectedAddresses.value = emptySet()
}

/**
 * Refreshes snapshot list and status text based on current matches.
 */
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

/**
 * Scrollable card listing memory match results with address, type, and live value.
 */
@Composable
private fun MatchesTable(
    modifier: Modifier = Modifier,
    matches: List<MatchInfo>,
    totalMatchesCount: Int,
    matchesStatusText: String,
    refinementStep: Int,
    savedCheckpointsCount: Int,
    isSelectionMode: Boolean,
    selectedAddresses: Set<Long>,
    isLoadingMore: Boolean,
    onMatchClicked: (MatchInfo) -> Unit,
    onCopyAllMatchesToAddressTable: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onToggleItemSelection: (MatchInfo) -> Unit,
    onSelectAllVisible: () -> Unit,
    onSendSelectedToEditor: () -> Unit,
    onCopyMatchToTab: (MatchInfo) -> Unit,
    onCopyToTabClicked: () -> Unit,
    onLoadMore: () -> Unit
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = matchesStatusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (refinementStep > 0) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        ) {
                            Text(
                                text = stringResource(R.string.memory_menu_step_badge, refinementStep),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (savedCheckpointsCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = SuccessEmerald.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = stringResource(R.string.memory_menu_checkpoints_badge, savedCheckpointsCount),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessEmerald,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (matches.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        OutlinedButton(
                            onClick = onToggleSelectionMode,
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(
                                0.5.dp,
                                if (isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Icon(
                                imageVector = if (isSelectionMode) Icons.Default.Check else Icons.Default.Checklist,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = if (isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = stringResource(
                                    if (isSelectionMode) R.string.memory_menu_select_mode_cancel
                                    else R.string.memory_menu_select_mode_button
                                ),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!isSelectionMode) {
                            Button(
                                onClick = onCopyToTabClicked,
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(stringResource(R.string.memory_menu_copy_to_tab_button), fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                            }

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
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(stringResource(R.string.memory_menu_copy_all_button), fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (isSelectionMode && matches.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = onSelectAllVisible,
                                modifier = Modifier.height(24.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Icon(imageVector = Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(stringResource(R.string.memory_menu_select_all_visible), fontSize = 9.sp)
                            }
                            Text(
                                text = stringResource(R.string.memory_menu_selected_count_label, selectedAddresses.size),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 9.5.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = onCopyToTabClicked,
                                enabled = selectedAddresses.isNotEmpty(),
                                modifier = Modifier.height(24.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(stringResource(R.string.memory_menu_copy_to_tab_button), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onSendSelectedToEditor,
                                enabled = selectedAddresses.isNotEmpty(),
                                modifier = Modifier.height(24.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(stringResource(R.string.memory_menu_send_selected_to_editor), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
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
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items = matches, key = { match -> match.id }) { match ->
                        MatchItem(
                            match = match,
                            isSelectionMode = isSelectionMode,
                            isSelected = match.address in selectedAddresses,
                            onClick = {
                                if (isSelectionMode) {
                                    onToggleItemSelection(match)
                                } else {
                                    onMatchClicked(match)
                                }
                            },
                            onCopyToTab = { onCopyMatchToTab(match) }
                        )
                    }
                    if (matches.size < totalMatchesCount) {
                        item(key = "load_more_footer") {
                            val nextBatch = (totalMatchesCount - matches.size).coerceAtMost(100)
                            OutlinedButton(
                                onClick = onLoadMore,
                                enabled = !isLoadingMore,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .height(32.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else {
                                    Text(
                                        text = stringResource(
                                            R.string.memory_menu_load_more_button,
                                            nextBatch,
                                            matches.size,
                                            totalMatchesCount
                                        ),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual match card row displaying address, region tag, value, live delta badge, and copy action.
 */
@Composable
private fun MatchItem(
    match: MatchInfo,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: (MatchInfo) -> Unit = {},
    onCopyToTab: (MatchInfo) -> Unit = {}
) {
    val displayedRegion = match.memoryRegion.substringAfterLast('/', match.memoryRegion)
    val prevVal = match.previousValue
    val hasDelta = prevVal != null && prevVal.toString() != match.prevValue.toString()

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(match) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = BorderStroke(
            width = if (isSelected || hasDelta) 1.dp else 0.5.dp,
            color = when {
                isSelected -> MaterialTheme.colorScheme.primary
                hasDelta -> SuccessEmerald.copy(alpha = 0.65f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick(match) },
                    modifier = Modifier.size(18.dp).padding(end = 6.dp)
                )
            }

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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val valueText = stringResource(R.string.memory_menu_match_item_value_label, match.prevValue.toString())
                    Text(
                        text = valueText,
                        style = MonospaceValueStyle.copy(
                            fontSize = 11.sp,
                            fontWeight = if (hasDelta) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (hasDelta) SuccessEmerald else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasDelta) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = SuccessEmerald.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = stringResource(R.string.memory_menu_previous_value_badge, prevVal.toString()),
                                style = MaterialTheme.typography.labelSmall,
                                color = SuccessEmerald,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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

                if (!isSelectionMode) {
                    IconButton(
                        onClick = { onCopyToTab(match) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.memory_menu_copy_item_to_tab_cd),
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Interactive control panel providing scan input text fields, operator selectors, type pickers,
 * checkpoint controls, undo action, and scan triggers.
 */
@Composable
private fun MatchesSetting(
    modifier: Modifier = Modifier,
    scanInputVal: MutableState<String>,
    selectedTypes: MutableState<Set<String>>,
    onOpenTypeSelector: () -> Unit,
    onOpenSyntaxHelp: () -> Unit,
    operatorSelectedOptionIdx: MutableState<Int>,
    initialScanDone: Boolean,
    totalMatchesCount: Int,
    undoCount: Int,
    isCurrentSaved: Boolean,
    nextScanEnabled: Boolean,
    nextScanClicked: () -> Unit,
    newScanEnabled: Boolean,
    newScanClicked: () -> Unit,
    gotoClicked: () -> Unit,
    onSaveCheckpointClicked: () -> Unit,
    onUndoClicked: () -> Unit
) {
    val currentOps = if (initialScanDone) refineOperatorOptions else initialOperatorOptions
    val safeOpIdx = operatorSelectedOptionIdx.value.coerceIn(0, (currentOps.size - 1).coerceAtLeast(0))
    val selectedOp = currentOps.getOrElse(safeOpIdx) { "=" }
    val requiresValue = doesOperatorRequireValue(selectedOp, !initialScanDone)

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
                    value = if (requiresValue) scanInputVal.value else "",
                    onValueChange = { scanInputVal.value = it },
                    enabled = requiresValue,
                    keyboardType = KeyboardType.HEXADECIMAL,
                    label = { Text(stringResource(R.string.memory_menu_scan_value_label), fontSize = 10.sp) },
                    placeholder = {
                        Text(
                            text = stringResource(
                                if (requiresValue) R.string.memory_menu_scan_value_placeholder
                                else R.string.memory_menu_value_not_required_placeholder
                            ),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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

                CustomDropdown(
                    options = currentOps,
                    selectedIndex = safeOpIdx,
                    onOptionSelected = { operatorSelectedOptionIdx.value = it },
                    modifier = Modifier.widthIn(min = 72.dp, max = 96.dp)
                )

                val isGotoExpression = requiresValue && (
                    scanInputVal.value.startsWith("0x", ignoreCase = true) ||
                        scanInputVal.value.startsWith("[") ||
                        scanInputVal.value.contains(".so") ||
                        scanInputVal.value.startsWith("lib", ignoreCase = true)
                    )

                if (isGotoExpression && scanInputVal.value.isNotBlank()) {
                    Button(
                        onClick = gotoClicked,
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

            // Operator explanation hint bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = stringResource(getOperatorHintResId(selectedOp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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

            // Row 3: Save Checkpoint & Undo Controls (available once a scan session exists)
            if (initialScanDone) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val saveEnabled = nextScanEnabled && totalMatchesCount > 0 && !isCurrentSaved
                    OutlinedButton(
                        onClick = onSaveCheckpointClicked,
                        enabled = saveEnabled,
                        modifier = Modifier.weight(1f).height(30.dp),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        border = BorderStroke(
                            0.5.dp,
                            if (isCurrentSaved) SuccessEmerald.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isCurrentSaved) SuccessEmerald.copy(alpha = 0.14f) else Color.Transparent,
                            contentColor = if (isCurrentSaved) SuccessEmerald else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isCurrentSaved) Icons.Default.Check else Icons.Default.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (isCurrentSaved) R.string.memory_menu_checkpoint_saved_button
                                else R.string.memory_menu_save_checkpoint_button
                            ),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = onUndoClicked,
                        enabled = nextScanEnabled && undoCount > 0,
                        modifier = Modifier.weight(1f).height(30.dp),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        border = BorderStroke(
                            0.5.dp,
                            if (undoCount > 0) TertiaryAmber.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (undoCount > 0) TertiaryAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (undoCount > 0) {
                                stringResource(R.string.memory_menu_undo_button_with_count, undoCount)
                            } else {
                                stringResource(R.string.memory_menu_undo_button)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dropdown menu for selecting comparison operators.
 */
@Composable
private fun CustomDropdown(
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

/**
 * Action button styled for scan triggers with loading indicator support.
 */
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

/**
 * Surface button displaying the currently active value types and triggering the selection dialog on click.
 */
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
    TypeOptionItem("obscured_int", "8 Bytes", "XOR-Pair Int"),
    TypeOptionItem("obscured_float", "8 Bytes", "XOR-Pair Float"),
)

private data class SearchHelpCategory(
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val accentColor: Color,
    val examples: List<String>
)

/**
 * Modern informational dialog explaining advanced search techniques, syntax rules, and interactive examples.
 */
@Composable
private fun SearchHelpDialog(
    onSelectExample: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val categories = remember {
        listOf(
            SearchHelpCategory(
                titleRes = R.string.search_help_cat_basic_title,
                descRes = R.string.search_help_cat_basic_desc,
                icon = Icons.Default.Tune,
                accentColor = Color(0xFF38BDF8),
                examples = listOf("100", "100.0 +- 0.5", "500 +- 5%")
            ),
            SearchHelpCategory(
                titleRes = R.string.search_help_cat_obscured_title,
                descRes = R.string.search_help_cat_obscured_desc,
                icon = Icons.Default.Lock,
                accentColor = Color(0xFFA855F7),
                examples = listOf("xor:1000", "obscured:250.0")
            ),
            SearchHelpCategory(
                titleRes = R.string.search_help_cat_bigdouble_title,
                descRes = R.string.search_help_cat_bigdouble_desc,
                icon = Icons.Default.Calculate,
                accentColor = Color(0xFFEC4899),
                examples = listOf("1.5e6", "1.5,6", "bigdouble:2.5,12")
            ),
            SearchHelpCategory(
                titleRes = R.string.search_help_cat_scaled_title,
                descRes = R.string.search_help_cat_scaled_desc,
                icon = Icons.Default.Speed,
                accentColor = Color(0xFFF59E0B),
                examples = listOf("10.5 * 1000", "scaled:10.5:1000")
            ),
            SearchHelpCategory(
                titleRes = R.string.search_help_cat_group_title,
                descRes = R.string.search_help_cat_group_desc,
                icon = Icons.Default.Layers,
                accentColor = Color(0xFF10B981),
                examples = listOf("f64:1.5; i64:6 : 16", "f32:100.0; i32:50; byte:1 : 32")
            ),
            SearchHelpCategory(
                titleRes = R.string.search_help_cat_goto_title,
                descRes = R.string.search_help_cat_goto_desc,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                accentColor = Color(0xFF6366F1),
                examples = listOf("0x7FFF12345678", "libnative.so + 0x12345")
            )
        )
    }

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(ComponentStyles.Dialog.contentPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
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
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.search_help_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
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

            // Tip Banner
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.search_help_try_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Category Cards
            categories.forEach { category ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        // Title + Icon Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = category.accentColor.copy(alpha = 0.2f),
                                modifier = Modifier.size(22.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = category.icon,
                                        contentDescription = null,
                                        tint = category.accentColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(category.titleRes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = category.accentColor,
                                fontSize = 11.5.sp
                            )
                        }

                        // Description
                        Text(
                            text = stringResource(category.descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )

                        // Interactive Example Chips
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            category.examples.forEach { example ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                    border = BorderStroke(0.5.dp, category.accentColor.copy(alpha = 0.4f)),
                                    modifier = Modifier.clickable { onSelectExample(example) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = example,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 10.sp
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = category.accentColor,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(stringResource(R.string.ok), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

/**
 * Multi-type selection dialog allowing users to pick combinations of value types or quick presets.
 */
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
                .padding(ComponentStyles.Dialog.contentPadding)
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
                    label = { Text(stringResource(R.string.memory_menu_preset_all), fontSize = 8.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == integerKeys,
                    onClick = { tempSelected = integerKeys },
                    label = { Text(stringResource(R.string.memory_menu_preset_ints), fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == floatKeys,
                    onClick = { tempSelected = floatKeys },
                    label = { Text(stringResource(R.string.memory_menu_preset_floats), fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == specialKeys,
                    onClick = { tempSelected = specialKeys },
                    label = { Text(stringResource(R.string.memory_menu_preset_spec), fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = tempSelected == setOf("int"),
                    onClick = { tempSelected = setOf("int") },
                    label = { Text(stringResource(R.string.memory_menu_preset_reset), fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
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

/**
 * Option card with checkbox representing a single selectable memory type.
 */
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

/**
 * Dialog enabling the user to clone the active search session or copy selected matches
 * to a new or existing search tab.
 */
@Composable
private fun CopyToSearchTabDialog(
    request: CopyToTabRequest,
    existingTabs: List<TabState>,
    onSelectNewTab: () -> Unit,
    onSelectExistingTab: (TabState) -> Unit,
    onDismiss: () -> Unit
) {
    val description = when (request) {
        is CopyToTabRequest.CloneAll -> stringResource(
            R.string.memory_menu_copy_to_tab_dialog_desc_all,
            request.sourceTab.totalMatchesCount.value
        )
        is CopyToTabRequest.CopyItems -> stringResource(
            R.string.memory_menu_copy_to_tab_dialog_desc_items,
            request.addresses.size
        )
    }

    CustomDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(ComponentStyles.Dialog.contentPadding)
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
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.memory_menu_copy_to_tab_dialog_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.overlay_ui_close_menu_icon_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Option 1: New Search Tab
            Surface(
                onClick = onSelectNewTab,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.memory_menu_copy_to_new_tab_option),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.5.sp
                        )
                        Text(
                            text = stringResource(R.string.memory_menu_copy_to_new_tab_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.5.sp
                        )
                    }
                }
            }

            // Existing Search Tabs
            existingTabs.forEach { targetTab ->
                Surface(
                    onClick = { onSelectExistingTab(targetTab) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.memory_menu_copy_to_existing_tab_option,
                                    targetTab.title.value,
                                    targetTab.totalMatchesCount.value
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp
                            )
                            Text(
                                text = stringResource(R.string.memory_menu_copy_to_existing_tab_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.overlay_ui_dialog_cancel_button), fontSize = 11.sp)
                }
            }
        }
    }
}
