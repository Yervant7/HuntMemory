package com.yervant.huntmem.ui.menu

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.MemoryScanner
import com.yervant.huntmem.backend.MemoryScanner.MemoryRegion
import com.yervant.huntmem.backend.Process
import com.yervant.huntmem.ui.MenuType

private var regionsSelected: List<MemoryRegion> = listOf()
private var customRegionFilter: String? = null

fun getSelectedRegions(): List<MemoryRegion> {
    return regionsSelected.ifEmpty {
        listOf(
            MemoryRegion.ALLOC, MemoryRegion.BSS, MemoryRegion.DATA, MemoryRegion.HEAP,
            MemoryRegion.JAVA_HEAP, MemoryRegion.ANONYMOUS, MemoryRegion.STACK, MemoryRegion.ASHMEM
        )
    }
}
fun getCustomFilter(): String? = customRegionFilter
fun setRegions(regions: List<MemoryRegion>, customFilter: String? = null) {
    regionsSelected = regions
    customRegionFilter = customFilter
}

typealias MemoryMapEntry = MemoryScanner.MemoryRegions

@SuppressLint("DefaultLocale")
fun formatSize(sizeInBytes: Long): String {
    val sizeInMb = sizeInBytes / (1024f * 1024f)
    return String.format("%.2f MB", sizeInMb)
}

@Composable
fun HuntSettings(
    activeMenu: MenuType,
    onSwitchMenu: (MenuType) -> Unit
) {
    val allRegions = remember {
        listOf(
            MemoryRegion.ALLOC, MemoryRegion.BSS, MemoryRegion.DATA, MemoryRegion.HEAP,
            MemoryRegion.JAVA_HEAP, MemoryRegion.ANONYMOUS, MemoryRegion.STACK,
            MemoryRegion.CODE_SYSTEM, MemoryRegion.ASHMEM, MemoryRegion.LIBS
        )
    }
    val selectedRegions = remember { mutableStateListOf<MemoryRegion>() }
    val customRegion = remember { mutableStateOf("") }
    val expandedRegion = remember { mutableStateOf<MemoryRegion?>(null) }
    val memoryDetailsMap = remember { mutableStateMapOf<MemoryRegion, List<MemoryMapEntry>>() }
    val pid = AttachedProcessRepository.getAttachedPid()

    LaunchedEffect(key1 = pid) {
        if (pid != null && Process().processIsRunning(pid.toString())) {
            val allMemoryMaps = MemoryScanner(pid).readMemoryMaps()
            val details = mutableMapOf<MemoryRegion, List<MemoryMapEntry>>()
            allRegions.forEach { region ->
                details[region] = allMemoryMaps.filter { entry -> region.matches(entry, null) }
            }
            memoryDetailsMap.clear()
            memoryDetailsMap.putAll(details)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Memory Regions",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allRegions) { region ->
                RegionItem(
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = customRegion.value,
                onValueChange = { customRegion.value = it },
                label = { Text("Custom Filter") },
                placeholder = { Text("libgame.so") },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Button(
                onClick = {
                    if (customRegion.value.isNotBlank()) {
                        setRegions(listOf(MemoryRegion.CUSTOM), customRegion.value)
                    } else {
                        setRegions(selectedRegions.toList())
                    }
                },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MenuType.entries.forEach { menuType ->
                if (menuType != activeMenu) {
                    Button(
                        onClick = { onSwitchMenu(menuType) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Switch to ${menuType.title}")
                    }
                }
            }
        }
    }
}

@Composable
fun RegionItem(
    region: MemoryRegion,
    isSelected: Boolean,
    isExpanded: Boolean,
    details: List<MemoryMapEntry>,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit
) {
    val totalSize = remember(details) { details.sumOf { it.end - it.start } }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
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
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )

                Text(
                    text = region.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formatSize(totalSize),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(90.dp),
                    textAlign = TextAlign.End
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(300))
            ) {
                RegionDetails(details)
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
fun RegionDetails(details: List<MemoryMapEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
    ) {
        if (details.isNotEmpty()) {
            Row(modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = "Perm",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = "Start",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = "End",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            HorizontalDivider()

            details.take(10).forEach { entry ->
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = getFileName(entry.path),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.permissions,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = String.format("0x%X", entry.start),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = String.format("0x%X", entry.end),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }
            if (details.size > 10) {
                Text(
                    text = "... and ${details.size - 10} more entries",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        } else {
            Text(
                text = "No memory entries found for this region.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
    }
}