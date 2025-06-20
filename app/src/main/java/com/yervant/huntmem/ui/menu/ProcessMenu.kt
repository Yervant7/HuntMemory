package com.yervant.huntmem.ui.menu

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    val statusText = attachedProcess?.let { "${it.pid} - ${it.packageName}" } ?: stringResource(R.string.process_menu_attached_process_none)

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
                Text(stringResource(R.string.process_menu_attached_process_label), style = MaterialTheme.typography.labelMedium)
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
            label = { Text(stringResource(R.string.process_menu_search_processes_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, stringResource(R.string.process_menu_search_icon_description)) },
            singleLine = true
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onRefreshClicked) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.process_menu_refresh_icon_description),
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
                        contentDescription = stringResource(R.string.process_menu_item_attached_icon_description),
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
            text = stringResource(R.string.process_menu_no_processes_found_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}