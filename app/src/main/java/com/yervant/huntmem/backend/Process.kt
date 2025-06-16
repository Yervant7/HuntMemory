package com.yervant.huntmem.backend

import com.topjohnwu.superuser.Shell
import android.graphics.drawable.Drawable

data class ProcessInfo(
    val pid: String,
    val packageName: String,
    val memory: String,
    val icon: Drawable? = null
)

class Process {

    fun getRunningProcesses(): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        val commandOutput = Shell.cmd("ps -e -o pid,rss,cmdline").exec().out.drop(2)

        commandOutput.forEach { line ->
            val cleanedLine = line.replace(Regex("\\p{C}|\\s+"), " ").trim()
            val tokens = cleanedLine.split(" ", limit = 3)

            if (tokens.size >= 3) {
                val pid = tokens[0]
                val memory = tokens[1]
                val packageName = tokens[2]

                if (!packageName.contains("/") && !packageName.contains("[")) {
                    processes.add(ProcessInfo(pid, packageName, memory))
                }
            }
        }

        return processes
    }

    fun processIsRunning(pid: String): Boolean {
        val commandOutput = Shell.cmd("ps -e -o pid,cmdline | grep $pid").exec().out
        if (commandOutput.size >= 2) return true
        else return false
    }
}