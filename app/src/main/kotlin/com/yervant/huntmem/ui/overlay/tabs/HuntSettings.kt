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

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.MemoryEngine
import com.yervant.huntmem.backend.ShellProcessProvider
import com.yervant.huntmem.ui.keyboard.KeyboardType
import com.yervant.huntmem.ui.keyboard.VirtualTextField
import com.yervant.huntmem.ui.theme.MonospaceAddressStyle

private var regionsSelected: List<MemoryEngine.MemoryRegionType> = emptyList()
private var customRegionFilter: String? = null

fun getSelectedRegions(): List<MemoryEngine.MemoryRegionType> {
    return regionsSelected.ifEmpty {
        listOf(
            MemoryEngine.MemoryRegionType.ANONYMOUS, MemoryEngine.MemoryRegionType.JAVA_HEAP,
            MemoryEngine.MemoryRegionType.HEAP, MemoryEngine.MemoryRegionType.ALLOC
        )
    }
}
fun getCustomFilter(): String? = customRegionFilter
fun setRegions(regions: List<MemoryEngine.MemoryRegionType>, customFilter: String? = null) {
    regionsSelected = regions
    customRegionFilter = customFilter
}

@SuppressLint("DefaultLocale")
fun formatSize(sizeInBytes: Long): String {
    val sizeInMb = sizeInBytes / (1024f * 1024f)
    return String.format("%.2f MB", sizeInMb)
}

@Composable
fun HuntSettings(context: Context) {
    val allRegions = remember {
        MemoryEngine.MemoryRegionType.entries.filter { it != MemoryEngine.MemoryRegionType.CUSTOM }
    }
    val selectedRegions = remember { regionsSelected.toMutableStateList() }
    val customRegion = remember { mutableStateOf(customRegionFilter ?: "") }
    val expandedRegion = remember { mutableStateOf<MemoryEngine.MemoryRegionType?>(null) }
    val memoryDetailsMap = remember { mutableStateMapOf<MemoryEngine.MemoryRegionType, List<MemoryEngine.MemoryMapEntry>>() }
    val pid = AttachedProcessRepository.getAttachedPid()

    val requireRead = remember { mutableStateOf(MemoryEngine.globalMapOptions.requireRead) }
    val requireWrite = remember { mutableStateOf(MemoryEngine.globalMapOptions.requireWrite) }
    val includeSwapped = remember { mutableStateOf(MemoryEngine.globalMapOptions.includeSwapped) }
    val minSize = remember { mutableStateOf(MemoryEngine.globalMapOptions.minSize.toString()) }
    val showAdvancedOptions = remember { mutableStateOf(false) }
    val refreshTrigger = remember { mutableIntStateOf(0) }

    LaunchedEffect(key1 = pid, key2 = refreshTrigger.intValue) {
        if (pid != null && ShellProcessProvider().isProcessRunning(pid.toString())) {
            val allMemoryMaps = MemoryEngine.getMemoryMaps(pid, emptyList(), null)
            val details = mutableMapOf<MemoryEngine.MemoryRegionType, List<MemoryEngine.MemoryMapEntry>>()
            allRegions.forEach { region ->
                details[region] = allMemoryMaps.filter { entry -> MemoryEngine.determineRegionType(entry.path) == region.name }
            }
            memoryDetailsMap.clear()
            memoryDetailsMap.putAll(details)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.hunt_settings_memory_regions_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        val visibleRegions = remember {
            derivedStateOf {
                allRegions.filter { region ->
                    val details = memoryDetailsMap[region] ?: emptyList()
                    details.sumOf { it.end - it.start } > 0
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(visibleRegions.value) { region ->
                RegionItem(
                    context = context,
                    region = region,
                    isSelected = selectedRegions.contains(region),
                    isExpanded = expandedRegion.value == region,
                    details = memoryDetailsMap[region] ?: emptyList(),
                    onToggleSelection = {
                        if (selectedRegions.contains(region)) {
                            selectedRegions.remove(region)
                        } else {
                            selectedRegions.add(region)
                        }
                    },
                    onClick = {
                        expandedRegion.value = if (expandedRegion.value == region) null else region
                    }
                )
            }
        }

        OutlinedButton(
            onClick = { showAdvancedOptions.value = !showAdvancedOptions.value },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (showAdvancedOptions.value)
                        stringResource(R.string.hunt_settings_hide_advanced_options)
                    else
                        stringResource(R.string.hunt_settings_show_advanced_options),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (showAdvancedOptions.value) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(visible = showAdvancedOptions.value) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { requireRead.value = !requireRead.value },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = requireRead.value,
                                onCheckedChange = { requireRead.value = it },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.hunt_settings_req_read),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { requireWrite.value = !requireWrite.value },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = requireWrite.value,
                                onCheckedChange = { requireWrite.value = it },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.hunt_settings_req_write),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeSwapped.value = !includeSwapped.value },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeSwapped.value,
                            onCheckedChange = { includeSwapped.value = it },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.hunt_settings_inc_swapped),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    VirtualTextField(
                        value = minSize.value,
                        onValueChange = { minSize.value = it },
                        keyboardType = KeyboardType.NUMERIC,
                        label = { Text(stringResource(R.string.hunt_settings_min_size_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Custom Filter + Save Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VirtualTextField(
                value = customRegion.value,
                onValueChange = { customRegion.value = it },
                keyboardType = KeyboardType.QWERTY,
                label = { Text(stringResource(R.string.hunt_settings_custom_filter_label)) },
                placeholder = { Text("libgame.so") },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    MemoryEngine.globalMapOptions = MemoryEngine.MemoryMapOptions(
                        requireRead = requireRead.value,
                        requireWrite = requireWrite.value,
                        includeSwapped = includeSwapped.value,
                        minSize = minSize.value.toLongOrNull() ?: 0L
                    )
                    refreshTrigger.intValue += 1

                    if (customRegion.value.isNotBlank()) {
                        setRegions(listOf(MemoryEngine.MemoryRegionType.CUSTOM), customRegion.value)
                    } else {
                        setRegions(selectedRegions.toList())
                    }
                },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(stringResource(R.string.hunt_settings_save_button), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun RegionItem(
    context: Context,
    region: MemoryEngine.MemoryRegionType,
    isSelected: Boolean,
    isExpanded: Boolean,
    details: List<MemoryEngine.MemoryMapEntry>,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit
) {
    val totalSize = remember(details) { details.sumOf { it.end - it.start } }

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    val border = if (isSelected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
    } else {
        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = border,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.size(20.dp),
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = region.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = formatSize(totalSize),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(Modifier.width(4.dp))

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(250)),
                exit = shrinkVertically(animationSpec = tween(250))
            ) {
                RegionDetails(details, context)
            }
        }
    }
}

private fun getFileName(path: String): String {
    if (path.isBlank() || !path.contains("/")) {
        return path
    }
    return path.substringAfterLast('/')
}

@Composable
fun RegionDetails(details: List<MemoryEngine.MemoryMapEntry>, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)
    ) {
        if (details.isNotEmpty()) {
            Row(modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.hunt_settings_region_details_name_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = stringResource(R.string.hunt_settings_region_details_perm_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = stringResource(R.string.hunt_settings_region_details_start_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = stringResource(R.string.hunt_settings_region_details_end_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            details.take(10).forEach { entry ->
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = getFileName(entry.path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.permissions,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = String.format("0x%X", entry.start),
                        style = MonospaceAddressStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = String.format("0x%X", entry.end),
                        style = MonospaceAddressStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }
            if (details.size > 10) {
                val remainingEntries = details.size - 10
                val label = context.resources.getQuantityString(
                    R.plurals.hunt_settings_and_more_entries_label,
                    remainingEntries,
                    remainingEntries
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
        } else {
            Text(
                text = stringResource(R.string.hunt_settings_no_memory_entries_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
    }
}
