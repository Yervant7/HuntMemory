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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val scanEngineMode = remember { mutableStateOf(MemoryEngine.globalScanEngineMode) }
    val showAdvancedOptions = remember { mutableStateOf(false) }
    val refreshTrigger = remember { mutableIntStateOf(0) }

    LaunchedEffect(key1 = pid, key2 = refreshTrigger.intValue) {
        if (pid != null) {
            val details = withContext(Dispatchers.IO) {
                if (!ShellProcessProvider().isProcessRunning(pid.toString())) {
                    return@withContext null
                }
                val allMemoryMaps = MemoryEngine.getMemoryMaps(pid, emptyList(), null)
                val mappedDetails = mutableMapOf<MemoryEngine.MemoryRegionType, List<MemoryEngine.MemoryMapEntry>>()
                allRegions.forEach { region ->
                    mappedDetails[region] = allMemoryMaps.filter { entry -> MemoryEngine.determineRegionType(entry.path) == region.name }
                }
                mappedDetails
            }
            if (details != null) {
                memoryDetailsMap.clear()
                memoryDetailsMap.putAll(details)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Top Header with Quick Select (ALL/RESET) and Advanced Options Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.hunt_settings_memory_regions_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Quick ALL / RESET toggle chip
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable {
                        if (selectedRegions.size == allRegions.size) {
                            selectedRegions.clear()
                        } else {
                            selectedRegions.clear()
                            selectedRegions.addAll(allRegions)
                        }
                    }
                ) {
                    Text(
                        text = if (selectedRegions.size == allRegions.size) "RESET" else "ALL",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 9.sp
                    )
                }

                // Advanced Options Toggle Chip
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (showAdvancedOptions.value) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(0.5.dp, if (showAdvancedOptions.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { showAdvancedOptions.value = !showAdvancedOptions.value }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (showAdvancedOptions.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.hunt_settings_scan_engine_title),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            fontSize = 9.5.sp,
                            color = if (showAdvancedOptions.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Compact Collapsible Advanced Options Panel
        AnimatedVisibility(
            visible = showAdvancedOptions.value,
            enter = expandVertically(animationSpec = tween(200)),
            exit = shrinkVertically(animationSpec = tween(200))
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Row 1: Scan Engine 3-way Segmented Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val engineOptions = listOf(
                            Triple(MemoryEngine.ScanEngineMode.RUST_NEON, "NEON SIMD", Icons.Default.Speed),
                            Triple(MemoryEngine.ScanEngineMode.KERNEL, "Kernel KPM", Icons.Default.Memory),
                            Triple(MemoryEngine.ScanEngineMode.AUTO, "Auto Engine", Icons.Default.Tune)
                        )
                        engineOptions.forEach { (mode, label, icon) ->
                            val isSelected = scanEngineMode.value == mode
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                                border = BorderStroke(
                                    if (isSelected) 1.dp else 0.5.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .clickable {
                                        scanEngineMode.value = mode
                                        MemoryEngine.updateScanEngineMode(mode)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                        fontSize = 9.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // Row 2: Permissions Checkboxes + Inline Min Size Field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Read Flag Chip
                        CompactFilterChip(
                            label = "Req (r)",
                            checked = requireRead.value,
                            onCheckedChange = { requireRead.value = it },
                            modifier = Modifier.weight(0.9f)
                        )

                        // Write Flag Chip
                        CompactFilterChip(
                            label = "Req (w)",
                            checked = requireWrite.value,
                            onCheckedChange = { requireWrite.value = it },
                            modifier = Modifier.weight(0.9f)
                        )

                        // Swapped Flag Chip
                        CompactFilterChip(
                            label = "Swapped",
                            checked = includeSwapped.value,
                            onCheckedChange = { includeSwapped.value = it },
                            modifier = Modifier.weight(1.0f)
                        )

                        // Min Size TextField
                        VirtualTextField(
                            value = minSize.value,
                            onValueChange = { minSize.value = it },
                            keyboardType = KeyboardType.NUMERIC,
                            placeholder = { Text(stringResource(R.string.hunt_settings_min_size_label), fontSize = 9.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1.1f),
                            shape = RoundedCornerShape(4.dp)
                        )
                    }
                }
            }
        }

        // Memory Regions List
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
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

        // Custom Filter + Save Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        ) {
            VirtualTextField(
                value = customRegion.value,
                onValueChange = { customRegion.value = it },
                keyboardType = KeyboardType.QWERTY,
                placeholder = { Text(stringResource(R.string.hunt_settings_custom_filter_label), fontSize = 10.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                singleLine = true
            )

            Button(
                onClick = {
                    MemoryEngine.globalMapOptions = MemoryEngine.MemoryMapOptions(
                        requireRead = requireRead.value,
                        requireWrite = requireWrite.value,
                        includeSwapped = includeSwapped.value,
                        minSize = minSize.value.toLongOrNull() ?: 0L
                    )
                    MemoryEngine.updateScanEngineMode(scanEngineMode.value)
                    refreshTrigger.intValue += 1

                    if (customRegion.value.isNotBlank()) {
                        setRegions(listOf(MemoryEngine.MemoryRegionType.CUSTOM), customRegion.value)
                    } else {
                        setRegions(selectedRegions.toList())
                    }
                },
                modifier = Modifier.height(38.dp),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
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
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.hunt_settings_save_button),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFilterChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
        border = BorderStroke(
            if (checked) 1.dp else 0.5.dp,
            if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        modifier = modifier
            .height(28.dp)
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.size(16.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
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
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    val border = if (isSelected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
    } else {
        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = border,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.size(18.dp),
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )

                Spacer(Modifier.width(6.dp))

                Text(
                    text = region.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 9.sp
                    )
                }

                Spacer(Modifier.width(3.dp))

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200))
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
                    modifier = Modifier.weight(1.4f)
                )
                Text(
                    text = stringResource(R.string.hunt_settings_region_details_perm_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(0.8f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = stringResource(R.string.hunt_settings_region_details_start_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1.1f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = stringResource(R.string.hunt_settings_region_details_end_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1.1f),
                    textAlign = TextAlign.End
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            details.take(10).forEach { entry ->
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1.4f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getFileName(entry.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (entry.mergedCount > 1) {
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                            ) {
                                Text(
                                    text = "${entry.mergedCount}x",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = entry.permissions,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.8f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = String.format("0x%X", entry.start),
                        style = MonospaceAddressStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1.1f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = String.format("0x%X", entry.end),
                        style = MonospaceAddressStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.1f),
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
