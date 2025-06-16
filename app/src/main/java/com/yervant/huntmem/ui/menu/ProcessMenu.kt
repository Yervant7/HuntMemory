package com.yervant.huntmem.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.ProcessInfo

@Composable
fun ProcessScreen(
    modifier: Modifier = Modifier,
    viewModel: ProcessViewModel
) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filteredProcesses by viewModel.filteredProcesses.collectAsStateWithLifecycle(initialValue = emptyList())
    val attachedPid by viewModel.attachedProcessPid.collectAsStateWithLifecycle()

    val attachedProcess = remember(attachedPid, filteredProcesses) {
        filteredProcesses.find { it.pid.toInt() == attachedPid }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            AttachedProcessIndicator(attachedProcess)
            Spacer(modifier = Modifier.height(8.dp))
            SearchAndRefreshRow(
                searchQuery = searchQuery,
                onSearchQueryChanged = viewModel::onSearchQueryChanged,
                onRefreshClicked = viewModel::onRefresh
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (filteredProcesses.isEmpty()) {
                EmptyStateMessage()
            } else {
                ProcessList(
                    processes = filteredProcesses,
                    currentPid = attachedPid,
                    onAttach = { process ->
                        viewModel.onAttachRequest(process)
                    }
                )
            }
        }
    }
}

@Composable
private fun AttachedProcessIndicator(attachedProcess: ProcessInfo?) {
    val statusText = attachedProcess?.let { "${it.pid} - ${it.packageName}" } ?: "None"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("Attached Process", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SearchAndRefreshRow(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onRefreshClicked: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            label = { Text("Search processes") },
            leadingIcon = { Icon(Icons.Filled.Search, "Search") },
            singleLine = true
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onRefreshClicked) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ProcessList(
    processes: List<ProcessInfo>,
    currentPid: Int?,
    onAttach: (ProcessInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = processes,
            key = { it.pid }
        ) { process ->
            ProcessListItem(
                process = process,
                isAttached = currentPid == process.pid.toInt(),
                onAttach = { onAttach(process) }
            )
        }
    }
}

@Composable
private fun ProcessListItem(
    process: ProcessInfo,
    isAttached: Boolean,
    onAttach: () -> Unit,
) {
    val formattedMemory = remember(process.memory) {
        "%.2f MB".format(process.memory.toLongOrNull()?.div(1024.0) ?: 0.0)
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onAttach() },
        colors = CardDefaults.cardColors(
            containerColor = if (isAttached) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = process.icon,
                contentDescription = "${process.packageName} icon",
                placeholder = painterResource(id = R.drawable.ic_app),
                error = painterResource(id = R.drawable.ic_app),
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = process.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "PID: ${process.pid}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formattedMemory,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isAttached) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Attached",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp).padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No processes found",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}