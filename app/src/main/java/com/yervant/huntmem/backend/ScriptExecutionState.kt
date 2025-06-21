package com.yervant.huntmem.backend

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File

object ScriptExecutionState {
    private val _runningScript = mutableStateOf<File?>(null)

    val runningScript: State<File?> = _runningScript

    fun updateRunningScript(script: File?) {
        _runningScript.value = script
    }
}