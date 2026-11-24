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

import com.yervant.huntmem.backend.MemoryEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * Lifecycle-scoped controller for executing and cancelling Lua 5.4 scripts.
 *
 * Owned by `OverlayService` (`lifecycleScope`) so minimizing the overlay menu or switching
 * tabs does not accidentally cancel long-running scripts (e.g. continuous 2D canvas loops) or leave
 * `LuaUiBridge.isScriptRunning` stuck in `true`.
 */
class LuaScriptRunner(
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var activeJob: Job? = null

    /**
     * Starts executing a Lua script against [pid] on `Dispatchers.IO`.
     * Ignored if another script is currently active.
     */
    fun start(pid: Int, scriptCode: String) {
        if (pid <= 0) {
            LuaUiBridge.postLog("[Error] Please attach to a target process in the Processes tab first.")
            return
        }

        synchronized(lock) {
            if (activeJob?.isActive == true) {
                return
            }

            LuaUiBridge.setDialogsEnabled(true)
            LuaUiBridge.setScriptRunning(true)
            LuaUiBridge.postLog("[Script] Starting execution...")

            activeJob = scope.launch(Dispatchers.IO) {
                try {
                    val result = MemoryEngine.runLuaScript(pid, scriptCode)
                    result.onSuccess { res ->
                        if (res.success) {
                            LuaUiBridge.postLog("[Script] Completed successfully. Result: ${res.result ?: "nil"}")
                        } else {
                            LuaUiBridge.postLog("[Script Error] ${res.error}")
                        }
                    }.onFailure { e ->
                        LuaUiBridge.postLog("[Execution Failed] ${e.message}")
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    LuaUiBridge.postLog("[Execution Failed] ${e.message}")
                } finally {
                    LuaUiBridge.cancelPendingDialog()
                    LuaUiBridge.setScriptRunning(false)
                    synchronized(lock) {
                        activeJob = null
                    }
                }
            }
        }
    }

    /**
     * Requests cooperative cancellation of the currently running Lua script and immediately
     * unblocks any pending synchronous UI dialog without touching the Main Thread for IPC.
     */
    fun stop() {
        LuaUiBridge.postLog("[Script] Stopping script execution...")
        LuaUiBridge.cancelPendingDialog()
        scope.launch(Dispatchers.IO + NonCancellable) {
            MemoryEngine.stopLuaScript()
        }
    }

    /**
     * Tears down any active script execution when `OverlayService` is destroyed.
     * Must be called before `super.onDestroy()` cancels `lifecycleScope`.
     */
    fun shutdown() {
        val hadActiveScript: Boolean
        synchronized(lock) {
            hadActiveScript = activeJob?.isActive == true || LuaUiBridge.isScriptRunning.value
        }
        LuaUiBridge.setDialogsEnabled(false)
        LuaUiBridge.cancelPendingDialog()
        if (hadActiveScript) {
            scope.launch(Dispatchers.IO + NonCancellable) {
                MemoryEngine.stopLuaScript()
            }
        }
        synchronized(lock) {
            activeJob?.cancel()
            activeJob = null
        }
        LuaUiBridge.setScriptRunning(false)
    }
}
