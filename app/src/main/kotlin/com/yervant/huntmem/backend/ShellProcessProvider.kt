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

package com.yervant.huntmem.backend

import android.graphics.drawable.Drawable
import com.topjohnwu.superuser.Shell

data class ProcessInfo(
    val pid: String,
    val uid: Int,
    val packageName: String,
    val memory: String,
    val icon: Drawable? = null
)

class ShellProcessProvider {

    fun getRunningProcesses(): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        val commandOutput = Shell.cmd("ps -e -o uid,pid,rss,cmdline").exec().out.drop(1)

        for (line in commandOutput) {
            val cleanedLine = line.replace(Regex("\\p{C}|\\s+"), " ").trim()
            val tokens = cleanedLine.split(" ", limit = 4)

            if (tokens.size >= 4) {
                val uid = tokens[0].toIntOrNull() ?: continue
                val pid = tokens[1]
                val memory = tokens[2]
                val packageName = tokens[3]

                if (uid >= 10000) {
                    processes.add(ProcessInfo(pid, uid, packageName, memory))
                }
            }
        }

        return processes
    }

    fun isProcessRunning(pid: String): Boolean {
        val pidInt = pid.toIntOrNull() ?: return false
        return Shell.cmd("test -d /proc/$pidInt").exec().isSuccess
    }
}


