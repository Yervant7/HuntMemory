package com.yervant.huntmem.ui.menu

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.Process
import com.yervant.huntmem.backend.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ProcessViewModel(private val packageManager: PackageManager) : ViewModel() {

    private val processBackend = Process()
    private var refreshJob: Job? = null

    private val _allProcesses = MutableStateFlow<List<ProcessInfo>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val attachedProcessPid = AttachedProcessRepository.attachedProcessPid

    val filteredProcesses = combine(_allProcesses, _searchQuery) { processes, query ->
        if (query.isBlank()) {
            processes
        } else {
            processes.filter { it.packageName.contains(query, ignoreCase = true) }
        }
    }

    init {
        startAutoRefresh()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onAttachRequest(process: ProcessInfo) {
        if (!processBackend.processIsRunning(process.pid)) {
            return
        }

        AttachedProcessRepository.setAttachedProcess(process.pid.toInt())
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

            val processesWithIcons = rawProcesses.map { proc ->
                try {
                    val appInfo = packageManager.getApplicationInfo(proc.packageName, 0)
                    proc.copy(icon = packageManager.getApplicationIcon(appInfo))
                } catch (e: PackageManager.NameNotFoundException) {
                    proc
                }
            }
            _allProcesses.value = processesWithIcons
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }
}