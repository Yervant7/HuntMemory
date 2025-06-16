package com.yervant.huntmem.ui.menu

import android.content.Context
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayDisabled
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.Editor
import com.yervant.huntmem.backend.HuntMem
import com.yervant.huntmem.backend.Memory
import com.yervant.huntmem.backend.Process
import com.yervant.huntmem.ui.DialogCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class AddressInfo(
    val matchInfo: MatchInfo,
    val numType: String,
    var isFrozen: Boolean = false,
)

private val savedAddresList = mutableStateListOf<AddressInfo>()

fun AddressTableAddAddress(matchInfo: MatchInfo) {
    savedAddresList.add(AddressInfo(matchInfo, matchInfo.valueType, false))
}

@Composable
fun AddressTableMenu(context: Context?, dialogCallback: DialogCallback) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(savedAddresList.isNotEmpty()) {
        while (isActive) {
            Editor().syncFreezeState(savedAddresList)
            refreshValue(context!!, dialogCallback)
            delay(5.seconds)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.background)
    ) {

        ControlButtonsRow(
            dialogCallback = dialogCallback, // Passe o callback
            coroutineScope = coroutineScope,
            context = context!!
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column {
                AddressTableHeader()
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    itemsIndexed(savedAddresList) { index, item ->
                        AddressTableRow(
                            item = item,
                            onAddressClick = {
                                dialogCallback.showInfoDialog(
                                    title = "Delete Address",
                                    message = "Delete this address from the list?",
                                    onConfirm = { savedAddresList.removeAt(index) },
                                    onDismiss = {}
                                )
                            },
                            onValueClick = {
                                dialogCallback.showInputDialog(
                                    title = "Edit Value",
                                    defaultValue = item.matchInfo.prevValue.toString(),
                                    onConfirm = { newValue ->
                                        val huntmem = HuntMem()
                                        context.let {
                                            coroutineScope.launch {
                                                val pid = AttachedProcessRepository.getAttachedPid()
                                                if (pid != null) {
                                                    huntmem.writeMem(
                                                        pid,
                                                        item.matchInfo.address,
                                                        item.matchInfo.valueType,
                                                        newValue,
                                                        context
                                                    )
                                                    refreshValue(context, dialogCallback)
                                                }
                                            }
                                        }
                                    },
                                    onDismiss = {}
                                )
                            },
                            coroutineScope = coroutineScope,
                            context = context
                        )
                        if (index < savedAddresList.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                thickness = 1.dp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun ControlButtonsRow(
    dialogCallback: DialogCallback,
    coroutineScope: CoroutineScope,
    context: Context
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.screenHeightDp > configuration.screenWidthDp

    if (isPortrait) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton(
                    icon = Icons.Filled.Delete,
                    text = "Delete All",
                    containerColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        dialogCallback.showInfoDialog(
                            title = "Delete All Addresses",
                            message = "Are you sure you want to delete all saved addresses?",
                            onConfirm = { savedAddresList.clear() },
                            onDismiss = {}
                        )
                    },
                    modifier = Modifier.weight(1f)
                )

                ControlButton(
                    icon = Icons.Filled.Edit,
                    text = "Edit All",
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    onClick = {
                        dialogCallback.showInputDialog(
                            title = "Edit All Values",
                            defaultValue = "999999999",
                            onConfirm = { input ->
                                context.let {
                                    coroutineScope.launch {
                                        Editor().writeall(savedAddresList, input, context)
                                    }
                                }
                            },
                            onDismiss = {}
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton(
                    icon = Icons.Filled.CheckCircle,
                    text = "Freeze All",
                    containerColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        dialogCallback.showInputDialog(
                            title = "Freeze All Values",
                            defaultValue = "999999999",
                            onConfirm = { input ->
                                context.let {
                                    coroutineScope.launch {
                                        Editor().freezeall(savedAddresList, input, context)
                                    }
                                }
                            },
                            onDismiss = {}
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                ControlButton(
                    icon = Icons.Filled.PlayDisabled,
                    text = "Unfreeze",
                    containerColor = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        coroutineScope.launch {
                            Editor().unfreezeall(savedAddresList)
                            savedAddresList.forEach { addrInfo ->
                                addrInfo.isFrozen = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ControlButton(
                icon = Icons.Filled.Delete,
                text = "Delete All",
                containerColor = MaterialTheme.colorScheme.error,
                onClick = {
                    dialogCallback.showInfoDialog(
                        title = "Delete All Addresses",
                        message = "Are you sure you want to delete all saved addresses?",
                        onConfirm = { savedAddresList.clear() },
                        onDismiss = {}
                    )
                },
                modifier = Modifier.weight(1f)
            )

            ControlButton(
                icon = Icons.Filled.Edit,
                text = "Edit All",
                containerColor = MaterialTheme.colorScheme.tertiary,
                onClick = {
                    dialogCallback.showInputDialog(
                        title = "Edit All Values",
                        defaultValue = "999999999",
                        onConfirm = { input ->
                            context.let {
                                coroutineScope.launch {
                                    Editor().writeall(savedAddresList, input, context)
                                }
                            }
                        },
                        onDismiss = {}
                    )
                },
                modifier = Modifier.weight(1f)
            )
            ControlButton(
                icon = Icons.Filled.CheckCircle,
                text = "Freeze All",
                containerColor = MaterialTheme.colorScheme.primary,
                onClick = {
                    dialogCallback.showInputDialog(
                        title = "Freeze All Values",
                        defaultValue = "999999999",
                        onConfirm = { input ->
                            context.let {
                                coroutineScope.launch {
                                    Editor().freezeall(savedAddresList, input, context)
                                }
                            }
                        },
                        onDismiss = {}
                    )
                },
                modifier = Modifier.weight(1f)
            )
            ControlButton(
                icon = Icons.Filled.PlayDisabled,
                text = "Unfreeze",
                containerColor = MaterialTheme.colorScheme.secondary,
                onClick = {
                    coroutineScope.launch {
                        Editor().unfreezeall(savedAddresList)
                        savedAddresList.forEach { addrInfo ->
                            addrInfo.isFrozen = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedButton(
        onClick = onClick,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = containerColor,
            contentColor = Color.White
        ),
        modifier = modifier.height(40.dp),
        elevation = ButtonDefaults.elevatedButtonElevation(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AddressTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TableCell(
            text = "Address",
            weight = 0.3f,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        TableCell(
            text = "Type",
            weight = 0.2f,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        TableCell(
            text = "Value",
            weight = 0.3f,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        TableCell(
            text = "Freeze",
            weight = 0.2f,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    }
}

@Composable
private fun AddressTableRow(
    item: AddressInfo,
    onAddressClick: () -> Unit,
    onValueClick: () -> Unit,
    coroutineScope: CoroutineScope,
    context: Context
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onAddressClick() },
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TableCell(
                text = "0x${item.matchInfo.address.toString(16).uppercase(Locale.ROOT)}",
                weight = 0.3f,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            )
            TableCell(
                text = item.matchInfo.valueType,
                weight = 0.2f,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            TableCell(
                text = item.matchInfo.prevValue.toString(),
                weight = 0.3f,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium
                ),
                onClick = { onValueClick() }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.2f)
                    .wrapContentSize(Alignment.Center)
            ) {
                Switch(
                    checked = item.isFrozen,
                    onCheckedChange = { newValue ->
                        coroutineScope.launch {
                            if (newValue) {
                                Editor().freezeAddress(item, context)
                            } else {
                                Editor().unfreezeAddress(item)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    weight: Float,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(weight)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DialogHandling(
    context: Context?,
    dialogCallback: DialogCallback,
    coroutineScope: CoroutineScope,
    showDeleteDialog: Boolean,
    showEditDialog: Boolean,
    showFreezeDialog: Boolean,
    selectedAddressIndex: Int?,
    selectedAddressInfo: AddressInfo?,
    onDismissDelete: () -> Unit,
    onDismissEdit: () -> Unit,
    onDismissFreeze: () -> Unit,
    onDismissAddress: () -> Unit,
    onDismissValue: () -> Unit
) {
    if (showDeleteDialog) {
        dialogCallback.showInfoDialog(
            title = "Delete All Addresses",
            message = "Are you sure you want to delete all saved addresses?",
            onConfirm = {
                savedAddresList.clear()
            },
            onDismiss = onDismissDelete
        )
    }

    if (showEditDialog) {
        dialogCallback.showInputDialog(
            title = "Edit All Values",
            defaultValue = "999999999",
            onConfirm = { input ->
                context?.let {
                    coroutineScope.launch {
                        Editor().writeall(savedAddresList, input, context)
                    }
                }
            },
            onDismiss = onDismissEdit
        )
    }

    if (showFreezeDialog) {
        dialogCallback.showInputDialog(
            title = "Freeze All Values",
            defaultValue = "999999999",
            onConfirm = { input ->
                context?.let {
                    coroutineScope.launch {
                        Editor().freezeall(savedAddresList, input, context)
                    }
                }
            },
            onDismiss = onDismissFreeze
        )
    }

    selectedAddressIndex?.let { index ->
        dialogCallback.showInfoDialog(
            title = "Delete Address",
            message = "Delete this address from the list?",
            onConfirm = {
                savedAddresList.removeAt(index)
            },
            onDismiss = onDismissAddress
        )
    }

    selectedAddressInfo?.let { info ->
        val value = info.matchInfo.prevValue.toString()

        dialogCallback.showInputDialog(
            title = "Edit Value",
            defaultValue = value,
            onConfirm = { newValue ->
                val huntmem = HuntMem()
                context?.let {
                    coroutineScope.launch {
                        val pid = AttachedProcessRepository.getAttachedPid()
                        if (pid != null) {
                            huntmem.writeMem(
                                pid,
                                info.matchInfo.address,
                                info.matchInfo.valueType,
                                newValue,
                                context
                            )
                            refreshValue(context, dialogCallback)
                        }
                    }
                }
            },
            onDismiss = onDismissValue
        )
    }
}

private suspend fun refreshValue(context: Context, dialogCallback: DialogCallback) {
    val mem = Memory()
    val pid = AttachedProcessRepository.getAttachedPid()

    if (pid == null) {
        dialogCallback.showInfoDialog(
            title = "Error",
            message = "No process attached",
            onConfirm = {},
            onDismiss = {}
        )
        return
    }

    if (!Process().processIsRunning(pid.toString())) {
        dialogCallback.showInfoDialog(
            title = "Error",
            message = "Process not running",
            onConfirm = {},
            onDismiss = {}
        )
        withContext(Dispatchers.Main) {
            savedAddresList.clear()
        }
        return
    }

    if (savedAddresList.isNotEmpty() && savedAddresList.first().matchInfo.pid != pid) {
        dialogCallback.showInfoDialog(
            title = "Info",
            message = "Process changed cleaning...",
            onConfirm = {},
            onDismiss = {}
        )
        withContext(Dispatchers.Main) {
            savedAddresList.clear()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val newList = mutableListOf<AddressInfo>()
        savedAddresList.forEach { addrInfo ->
            try {
                val currentValue = mem.readMemory(
                    pid,
                    addrInfo.matchInfo.address,
                    addrInfo.matchInfo.valueType,
                    context
                )
                if (currentValue != -1 && currentValue != -1.0) {
                    val newAddressInfo = addrInfo.copy(
                        matchInfo = addrInfo.matchInfo.copy(prevValue = currentValue)
                    )
                    newList.add(newAddressInfo)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Editor().unfreezeAddress(addrInfo)
                    addrInfo.isFrozen = false
                }
            }
        }

        withContext(Dispatchers.Main) {
            savedAddresList.clear()
            savedAddresList.addAll(newList)
        }
    }
}

fun AddressInfo.copy(matchInfo: MatchInfo = this.matchInfo, isFrozen: Boolean = this.isFrozen): AddressInfo {
    return AddressInfo(matchInfo, this.numType, isFrozen)
}