package com.yervant.huntmem.ui.menu

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.Memory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import com.yervant.huntmem.backend.Memory.Companion.matches
import com.yervant.huntmem.backend.Process
import com.yervant.huntmem.ui.DialogCallback
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.isActive

private var defaultValueInitialized: Boolean = false

private var scanInputVal: MutableState<String> = mutableStateOf("")

private val valueTypeSelectedOptionIdx = mutableIntStateOf(0)

private val initialScanDone: MutableState<Boolean> = mutableStateOf(false)
private val isScanOnGoing: MutableState<Boolean> = mutableStateOf(false)
private val isRefreshOnGoing: MutableState<Boolean> = mutableStateOf(false)

private val valueTypeEnabled: MutableState<Boolean> = mutableStateOf(false)

private var currentMatchesList: MutableState<List<MatchInfo>> = mutableStateOf(emptyList())
private var matchesStatusText: MutableState<String> = mutableStateOf("0 matches")

private val operatorOptions = listOf("=", "!=", ">", "<", ">=", "<=")
private val operatorSelectedOptionIdx = mutableIntStateOf(0)

data class MatchInfo(
    val id: String,
    val pid: Int,
    val address: Long,
    val prevValue: Number,
    val valueType: String,
    val size: Int,
    val memoryRegion: String = "",
    val regionType: String = "",           // (HEAP, STACK, etc)
    val regionStart: Long = 0,
    val regionEnd: Long = 0,
    val permissions: String = "",          // (rwx)
)

fun getCurrentScanOption(): ScanOptions {
    return ScanOptions(
        inputVal = scanInputVal.value,
        valueType = valuestype[valueTypeSelectedOptionIdx.intValue],
        operator = operatorOptions[operatorSelectedOptionIdx.intValue]
    )
}

@Composable
fun InitialMemoryMenu(context: Context?, dialogCallback: DialogCallback) {

    LaunchedEffect(currentMatchesList.value.isNotEmpty()) {
        while (isActive) {
            if (currentMatchesList.value.size < 100) {
                refreshValues(context!!, dialogCallback)
            }
            delay(5.seconds)
        }
    }
    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, content = ({
        MemoryMenu(
            snackbarHostState = snackbarHostState,
            coroutineScope = coroutineScope,
            context!!,
            dialogCallback
        )
    }))
}

@OptIn(InternalCoroutinesApi::class)
suspend fun refreshValues(context: Context, dialogCallback: DialogCallback) {
    val pid = AttachedProcessRepository.getAttachedPid()

    if (pid == null) {
        dialogCallback.showInfoDialog(
            title = "Error",
            message = "No Process Attached",
            onConfirm = {},
            onDismiss = {}
        )
        return
    }

    if (!Process().processIsRunning(pid.toString())) {
        dialogCallback.showInfoDialog(
            title = "Error",
            message = "Process Not Exist Anymore",
            onConfirm = {},
            onDismiss = {}
        )

        resetMatches()
        initialScanDone.value = false
        return
    }

    if (isScanOnGoing.value) {
        return
    }

    if (matches.isNotEmpty() && matches.first().pid != pid) {
        dialogCallback.showInfoDialog(
            title = "Info",
            message = "Process changed cleaning...",
            onConfirm = {},
            onDismiss = {}
        )
        synchronized(matches) {
            matches.clear()
        }
        return
    }

    isRefreshOnGoing.value = true

    val newList = mutableListOf<MatchInfo>()
    matches.forEach { match ->
        val value = Memory().readMemory(pid, match.address, match.valueType, context)
        if (value != 0 && value != 0.0) {
            newList.add(match.copy(prevValue = value))
        }
    }
    synchronized(matches) {
        matches.clear()
        matches.addAll(newList)
    }
    updateMatches()
    isRefreshOnGoing.value = false
}

var valuestype: List<String> = listOf("int", "long", "float", "double")

@Composable
fun MemoryMenu(
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    context: Context,
    dialogCallback: DialogCallback,
) {

    if (!defaultValueInitialized) {
        valueTypeSelectedOptionIdx.intValue = 0
        defaultValueInitialized = true
    }
    val isAttached: Boolean = (AttachedProcessRepository.getAttachedPid() != null)
    valueTypeEnabled.value = isAttached && !initialScanDone.value

    val showErrorDialog = remember { mutableStateOf(false) }
    val errorDialogMsg = remember { mutableStateOf("") }
    if (showErrorDialog.value) {
        dialogCallback.showInfoDialog(
            title = "Error",
            message = errorDialogMsg.value,
            onConfirm = { showErrorDialog.value = false },
            onDismiss = {}
        )
    }

    val content: @Composable (matchesTableModifier: Modifier, matchesSettingModifier: Modifier) -> Unit =
        { matchesTableModifier, matchesSettingModifier ->

            MatchesTable(
                modifier = matchesTableModifier,
                matches = currentMatchesList.value,
                matchesStatusText = matchesStatusText.value,
                onMatchClicked = { matchInfo: MatchInfo ->
                    AddressTableAddAddress(matchInfo = matchInfo)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Added ${matchInfo.address} to Address Table",
                            duration = SnackbarDuration.Short,
                            actionLabel = "Ok"
                        )
                    }
                },
                onMatchLongPress = { match: MatchInfo -> },
                dialogCallback = dialogCallback,
                onCopyAllMatchesToAddressTable = {
                    for (matchInfo in currentMatchesList.value)
                        AddressTableAddAddress(matchInfo = matchInfo)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Added all matches to Address Table",
                            duration = SnackbarDuration.Short,
                            actionLabel = "Ok"
                        )
                    }
                },
            )
            MatchesSetting(
                modifier = matchesSettingModifier,
                scanInputVal = scanInputVal,
                nextScanEnabled = isAttached && !isScanOnGoing.value && !isRefreshOnGoing.value,
                nextScanClicked = {
                    coroutineScope.launch {
                        onNextScanClicked(
                            scanOptions = getCurrentScanOption(),
                            onBeforeScanStart = {
                                isScanOnGoing.value = true
                            },
                            onScanDone = {
                                isScanOnGoing.value = false
                                initialScanDone.value = true
                                updateMatches()
                            },
                            onScanError = { e: Exception ->
                                showErrorDialog.value = true
                                errorDialogMsg.value = e.stackTraceToString()
                            },
                            context = context
                        )
                    }
                },
                newScanEnabled = isAttached && initialScanDone.value && !isScanOnGoing.value,
                newScanClicked = {
                    coroutineScope.launch {
                        resetMatches()
                        updateMatches()
                        initialScanDone.value = false
                    }
                },
            )
        }

    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize(),
        ) {
            content(
                Modifier
                    .weight(0.6f)
                    .padding(16.dp),
                Modifier
                    .weight(0.4f)
                    .padding(10.dp),
            )
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize(),
        ) {
            content(
                Modifier
                    .weight(0.6f)
                    .padding(16.dp),
                Modifier
                    .weight(0.4f)
                    .fillMaxSize()
            )
        }
    }
}

@OptIn(InternalCoroutinesApi::class)
fun resetMatches() {
    synchronized(matches) {
        matches.clear()
    }
    currentMatchesList.value = emptyList()
    matchesStatusText.value = "0 matches"
}

@Composable
private fun MatchesTable(
    modifier: Modifier = Modifier,
    matches: List<MatchInfo>,
    matchesStatusText: String,
    onMatchClicked: (MatchInfo) -> Unit,
    onMatchLongPress: (MatchInfo) -> Unit,
    dialogCallback: DialogCallback,
    onCopyAllMatchesToAddressTable: () -> Unit
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = matchesStatusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onCopyAllMatchesToAddressTable,
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy All", fontSize = 14.sp)
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = matches,
                    key = { match -> match.id }
                ) { match ->
                    MatchItem(match, onMatchClicked, onMatchLongPress, dialogCallback)
                }
            }
        }
    }
}

@Composable
private fun MatchItem(
    match: MatchInfo,
    onClick: (MatchInfo) -> Unit = {},
    onLongClick: (MatchInfo) -> Unit = {},
    dialogCallback: DialogCallback
) {
    val coroutineScope = rememberCoroutineScope()
    val isLongPressHandled = remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(match.id) {
                detectTapGestures(
                    onLongPress = {
                        coroutineScope.launch {
                            delay(2000)
                            if (!isLongPressHandled.value) {
                                isLongPressHandled.value = true
                                dialogCallback.showInfoDialog(
                                    title = "HW Watchpoint",
                                    message = """
                                        Address: 0x${match.address.toString(16).uppercase()}
                                        Value: ${match.prevValue}
                                        Size: ${match.size}
                                        Type: ${match.valueType}
                                        Region: ${match.regionType} (${match.memoryRegion})
                                        Range: 0x${match.regionStart.toString(16)} - 0x${
                                        match.regionEnd.toString(
                                            16
                                        )
                                    }
                                        Permissions: ${match.permissions}
                                    """.trimIndent(),
                                    onConfirm = {
                                        onLongClick(match)
                                        isLongPressHandled.value = false
                                    },
                                    onDismiss = {
                                        isLongPressHandled.value = false
                                    }
                                )
                            }
                        }
                    },
                    onTap = {
                        onClick(match)
                        isLongPressHandled.value = false
                    }
                )
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "0x${match.address.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Value: ${match.prevValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = match.valueType.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(InternalCoroutinesApi::class)
fun updateMatches() {
    val mem = Memory()
    val matchesCount: Int
    val shownMatchesCount: Int

    synchronized(matches) {
        matchesCount = matches.size
        shownMatchesCount = min(matchesCount, 1000)

        if (matchesCount > 0) {
            currentMatchesList.value =
                mem.listMatches(shownMatchesCount)
                    .toMutableList()
        } else {
            currentMatchesList.value = emptyList()
        }
    }

    matchesStatusText.value = "$matchesCount matches" +
            if (matchesCount > 0) " (showing $shownMatchesCount)" else ""
}

@Composable
private fun MatchesSetting(
    modifier: Modifier = Modifier,
    scanInputVal: MutableState<String>,
    nextScanEnabled: Boolean,
    nextScanClicked: () -> Unit,
    newScanEnabled: Boolean,
    newScanClicked: () -> Unit,
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Scan Settings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = scanInputVal.value,
                onValueChange = { scanInputVal.value = it },
                label = { Text("Scan Value") },
                placeholder = { Text("Enter target value...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            if (initialScanDone.value) {
                CustomDropdown(
                    label = "Operator",
                    options = operatorOptions,
                    selectedIndex = operatorSelectedOptionIdx.intValue,
                    onOptionSelected = { operatorSelectedOptionIdx.intValue = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            CustomDropdown(
                label = "Value Type",
                options = valuestype,
                selectedIndex = valueTypeSelectedOptionIdx.intValue,
                onOptionSelected = { valueTypeSelectedOptionIdx.intValue = it },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ScanButton(
                    text = "New Scan",
                    enabled = newScanEnabled,
                    isLoading = isScanOnGoing.value,
                    onClick = newScanClicked,
                    modifier = Modifier.weight(1f)
                )
                ScanButton(
                    text = "Next Scan",
                    enabled = nextScanEnabled,
                    isLoading = isScanOnGoing.value,
                    onClick = nextScanClicked,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun CustomDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val expanded = remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded.value = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Text(
                text = "$label: ${options[selectedIndex]}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, false)
            )
            Icon(
                imageVector = if (expanded.value) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }

        DropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onOptionSelected(index)
                        expanded.value = false
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp))
        } else {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}