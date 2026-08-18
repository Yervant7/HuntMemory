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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.MemoryEditor
import com.yervant.huntmem.backend.MemoryEngine
import com.yervant.huntmem.backend.MemoryScanManager
import com.yervant.huntmem.backend.ShellProcessProvider
import com.yervant.huntmem.ui.DialogCallback
import com.yervant.huntmem.ui.keyboard.KeyboardType
import com.yervant.huntmem.ui.theme.FrozenIceCyan
import com.yervant.huntmem.ui.theme.MonospaceAddressStyle
import com.yervant.huntmem.ui.theme.MonospaceValueStyle
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

private val savedAddressList = mutableStateListOf<AddressInfo>()

fun addAddressToTable(matchInfo: MatchInfo) {
    savedAddressList.add(AddressInfo(matchInfo, matchInfo.valueType, false))
}

@Composable
fun AddressTableTab(context: Context?, dialogCallback: DialogCallback) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(savedAddressList.isNotEmpty()) {
        while (isActive) {
            MemoryEditor().syncFreezeState(savedAddressList)
            refreshValue(context!!, dialogCallback)
            delay(5.seconds)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Single-Line Control Toolbar
        SingleLineControlToolbar(
            dialogCallback = dialogCallback,
            coroutineScope = coroutineScope,
            context = context!!
        )

        // Main Address Table Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            elevation = CardDefaults.cardElevation(2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AddressTableHeader()
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (savedAddressList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = stringResource(R.string.address_table_empty_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        itemsIndexed(savedAddressList) { index, item ->
                            AddressTableRow(
                                item = item,
                                onDeleteClick = {
                                    val da = context.getString(R.string.address_table_delete_address_dialog_title)
                                    val dtafl = context.getString(R.string.address_table_delete_address_dialog_message)
                                    dialogCallback.showInfoDialog(
                                        title = da,
                                        message = dtafl,
                                        onConfirm = { savedAddressList.removeAt(index) },
                                        onDismiss = {}
                                    )
                                },
                                onValueClick = {
                                    val ev = context.getString(R.string.address_table_edit_value_dialog_title)
                                    val kbType = when (item.matchInfo.valueType.lowercase()) {
                                        "float", "double" -> KeyboardType.NUMERIC
                                        else -> KeyboardType.NUMERIC
                                    }
                                    dialogCallback.showInputDialog(
                                        title = ev,
                                        defaultValue = item.matchInfo.prevValue.toString(),
                                        keyboardType = kbType,
                                        onConfirm = { newValue ->
                                            coroutineScope.launch {
                                                val pid = AttachedProcessRepository.getAttachedPid()
                                                if (pid != null) {
                                                    MemoryEngine.writeMem(
                                                        pid,
                                                        item.matchInfo.address,
                                                        item.matchInfo.valueType,
                                                        newValue
                                                    )
                                                    refreshValue(context, dialogCallback)
                                                }
                                            }
                                        },
                                        onDismiss = {}
                                    )
                                },
                                coroutineScope = coroutineScope
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleLineControlToolbar(
    dialogCallback: DialogCallback,
    coroutineScope: CoroutineScope,
    context: Context
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ToolbarActionButton(
            icon = Icons.Filled.Delete,
            text = stringResource(R.string.address_table_delete_all_button),
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.error,
            onClick = {
                val daa = context.getString(R.string.address_table_delete_all_addresses_dialog_title)
                val awsa = context.getString(R.string.address_table_delete_all_warning_message)
                dialogCallback.showInfoDialog(
                    title = daa,
                    message = awsa,
                    onConfirm = { savedAddressList.clear() },
                    onDismiss = {}
                )
            },
            modifier = Modifier.weight(1f)
        )

        ToolbarActionButton(
            icon = Icons.Filled.Edit,
            text = stringResource(R.string.address_table_edit_all_button),
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.secondary,
            onClick = {
                val eav = context.getString(R.string.address_table_edit_all_values_dialog_title)
                dialogCallback.showInputDialog(
                    title = eav,
                    defaultValue = "999999",
                    keyboardType = KeyboardType.NUMERIC,
                    onConfirm = { input ->
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                MemoryEditor().writeAll(savedAddressList, input)
                            }
                        }
                    },
                    onDismiss = {}
                )
            },
            modifier = Modifier.weight(1f)
        )

        ToolbarActionButton(
            icon = Icons.Filled.AcUnit,
            text = stringResource(R.string.address_table_freeze_all_button),
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.primary,
            onClick = {
                val fav = context.getString(R.string.address_table_freeze_all_values_dialog_title)
                dialogCallback.showInputDialog(
                    title = fav,
                    defaultValue = "999999",
                    keyboardType = KeyboardType.NUMERIC,
                    onConfirm = { input ->
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                MemoryEditor().freezeAll(savedAddressList, input)
                            }
                        }
                    },
                    onDismiss = {}
                )
            },
            modifier = Modifier.weight(1f)
        )

        ToolbarActionButton(
            icon = Icons.Filled.PlayDisabled,
            text = stringResource(R.string.address_table_unfreeze_all_button),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = {
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        MemoryEditor().unfreezeAll(savedAddressList)
                        savedAddressList.forEach { addrInfo ->
                            addrInfo.isFrozen = false
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ToolbarActionButton(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier.height(30.dp),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        border = BorderStroke(0.5.dp, contentColor.copy(alpha = 0.3f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AddressTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TableCell(
            text = stringResource(R.string.address_table_header_address),
            weight = 0.34f,
            textStyle = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        )
        TableCell(
            text = stringResource(R.string.address_table_header_type),
            weight = 0.16f,
            textStyle = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        TableCell(
            text = stringResource(R.string.address_table_header_value),
            weight = 0.28f,
            textStyle = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        TableCell(
            text = stringResource(R.string.address_table_header_freeze),
            weight = 0.22f,
            alignment = Alignment.Center,
            textStyle = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun AddressTableRow(
    item: AddressInfo,
    onDeleteClick: () -> Unit,
    onValueClick: () -> Unit,
    coroutineScope: CoroutineScope
) {
    val isFrozen = item.isFrozen
    val freezeBorder = if (isFrozen) BorderStroke(1.dp, FrozenIceCyan.copy(alpha = 0.8f)) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    val containerColor = if (isFrozen) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDeleteClick() },
        elevation = CardDefaults.cardElevation(if (isFrozen) 2.dp else 1.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = freezeBorder,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Address
            TableCell(
                text = "0x${item.matchInfo.address.toString(16).uppercase(Locale.ROOT)}",
                weight = 0.34f,
                textStyle = MonospaceAddressStyle.copy(
                    color = if (isFrozen) FrozenIceCyan else MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp
                )
            )

            // Type
            TableCell(
                text = item.matchInfo.valueType.uppercase(),
                weight = 0.16f,
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            )

            // Value (Clickable to edit)
            Box(
                modifier = Modifier
                    .weight(0.28f)
                    .clickable { onValueClick() },
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (isFrozen) {
                        Icon(
                            imageVector = Icons.Filled.AcUnit,
                            contentDescription = "Frozen",
                            tint = FrozenIceCyan,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Text(
                        text = item.matchInfo.prevValue.toString(),
                        style = MonospaceValueStyle.copy(
                            color = if (isFrozen) FrozenIceCyan else MaterialTheme.colorScheme.tertiary,
                            fontWeight = if (isFrozen) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Freeze switch
            Box(
                modifier = Modifier.weight(0.22f),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = item.isFrozen,
                    onCheckedChange = { newValue ->
                        coroutineScope.launch {
                            if (newValue) {
                                MemoryEditor().freezeAddress(item)
                            } else {
                                MemoryEditor().unfreezeAddress(item)
                            }
                        }
                    },
                    modifier = Modifier.size(20.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = FrozenIceCyan,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    alignment: Alignment = Alignment.CenterStart,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = alignment
    ) {
        Text(
            text = text,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private suspend fun refreshValue(context: Context, dialogCallback: DialogCallback) {
    val mem = MemoryScanManager()
    val pid = AttachedProcessRepository.getAttachedPid()

    val err = context.getString(R.string.address_table_error_dialog_title)
    if (pid == null) {
        val npa = context.getString(R.string.address_table_no_process_attached_error)
        dialogCallback.showInfoDialog(
            title = err,
            message = npa,
            onConfirm = {},
            onDismiss = {}
        )
        return
    }

    if (!ShellProcessProvider().isProcessRunning(pid.toString())) {
        val pnr = context.getString(R.string.address_table_process_not_running_error)
        dialogCallback.showInfoDialog(
            title = err,
            message = pnr,
            onConfirm = {},
            onDismiss = {}
        )
        withContext(Dispatchers.Main) {
            savedAddressList.clear()
        }
        return
    }

    if (savedAddressList.isNotEmpty() && savedAddressList.first().matchInfo.pid != pid) {
        val info = context.getString(R.string.address_table_info_dialog_title)
        val processchanged = context.getString(R.string.address_table_process_changed_info)
        dialogCallback.showInfoDialog(
            title = info,
            message = processchanged,
            onConfirm = {},
            onDismiss = {}
        )
        withContext(Dispatchers.Main) {
            savedAddressList.clear()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val newList = mutableListOf<AddressInfo>()
        savedAddressList.forEach { addrInfo ->
            try {
                val currentValue = mem.readMemory(
                    pid,
                    addrInfo.matchInfo.address,
                    addrInfo.matchInfo.valueType
                )
                if (currentValue != null && currentValue != -1 && currentValue != -1.0) {
                    val newAddressInfo = addrInfo.copy(
                        matchInfo = addrInfo.matchInfo.copy(prevValue = currentValue)
                    )
                    newList.add(newAddressInfo)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    MemoryEditor().unfreezeAddress(addrInfo)
                    addrInfo.isFrozen = false
                }
            }
        }

        withContext(Dispatchers.Main) {
            savedAddressList.clear()
            savedAddressList.addAll(newList)
        }
    }
}

fun AddressInfo.copy(matchInfo: MatchInfo = this.matchInfo, isFrozen: Boolean = this.isFrozen): AddressInfo {
    return AddressInfo(matchInfo, this.numType, isFrozen)
}
