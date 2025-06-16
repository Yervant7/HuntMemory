package com.yervant.huntmem.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AttachedProcessRepository {

    private val _attachedProcessPid = MutableStateFlow<Int?>(null)

    val attachedProcessPid = _attachedProcessPid.asStateFlow()

    fun setAttachedProcess(pid: Int?) {
        _attachedProcessPid.value = pid
    }

    fun getAttachedPid(): Int? {
        return _attachedProcessPid.value
    }
}