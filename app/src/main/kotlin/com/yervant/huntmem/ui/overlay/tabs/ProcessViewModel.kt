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

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.ProcessInfo
import com.yervant.huntmem.backend.ShellProcessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

enum class ProcessFilterType {
    ALL, USER, SYSTEM
}

data class ProcessItemData(
    val info: ProcessInfo,
    val appLabel: String = "",
    val isSystemApp: Boolean = false
)

class ProcessViewModel(private val packageManager: PackageManager) : ViewModel() {

    private val processBackend = ShellProcessProvider()
    private var refreshJob: Job? = null

    private val _allProcesses = MutableStateFlow<List<ProcessItemData>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _filterType = MutableStateFlow(ProcessFilterType.ALL)
    val filterType = _filterType.asStateFlow()

    val attachedProcessPid = AttachedProcessRepository.attachedProcessPid

    val processCounts = _allProcesses.map { list ->
        val user = list.count { !it.isSystemApp }
        val system = list.count { it.isSystemApp }
        Triple(list.size, user, system)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Triple(0, 0, 0)
    )

    val filteredProcesses = combine(_allProcesses, _searchQuery, _filterType) { processes, query, filter ->
        val typeFiltered = when (filter) {
            ProcessFilterType.ALL -> processes
            ProcessFilterType.USER -> processes.filter { !it.isSystemApp }
            ProcessFilterType.SYSTEM -> processes.filter { it.isSystemApp }
        }

        if (query.isBlank()) {
            typeFiltered
        } else {
            typeFiltered.filter { item ->
                item.appLabel.contains(query, ignoreCase = true) ||
                item.info.packageName.contains(query, ignoreCase = true) ||
                item.info.pid.contains(query) ||
                item.info.uid.toString().contains(query)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        startAutoRefresh()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onFilterTypeChanged(filter: ProcessFilterType) {
        _filterType.value = filter
    }

    fun onAttachRequest(process: ProcessInfo) {
        if (!processBackend.isProcessRunning(process.pid)) {
            return
        }
        AttachedProcessRepository.setAttachedProcess(process.pid.toInt())
    }

    fun onDetachRequest() {
        AttachedProcessRepository.setAttachedProcess(null)
    }

    fun onRefresh() {
        refreshProcessList()
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                refreshProcessList()
                delay(30.seconds)
            }
        }
    }

    private fun refreshProcessList() {
        viewModelScope.launch(Dispatchers.IO) {
            val rawProcesses = processBackend.getRunningProcesses()

            val items = rawProcesses.map { proc ->
                try {
                    val appInfo = packageManager.getApplicationInfo(proc.packageName, 0)
                    val label = packageManager.getApplicationLabel(appInfo).toString()
                    val icon = packageManager.getApplicationIcon(appInfo)
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    ProcessItemData(
                        info = proc.copy(icon = icon),
                        appLabel = label,
                        isSystemApp = isSystem
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    ProcessItemData(
                        info = proc,
                        appLabel = proc.packageName.substringAfterLast('.'),
                        isSystemApp = false
                    )
                }
            }
            _allProcesses.value = items
        }
    }

    override fun onCleared() {
        refreshJob?.cancel()
    }
}
