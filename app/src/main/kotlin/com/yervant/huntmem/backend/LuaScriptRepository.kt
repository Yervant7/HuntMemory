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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Represents a Lua script item available in HuntMemory (either a built-in preset or a user-saved/imported file).
 */
data class LuaScriptItem(
    val id: String,
    val name: String,
    val content: String,
    val isPreset: Boolean = false,
    val lastModified: Long = 0L,
    val sizeBytes: Long = 0L,
    val description: String = ""
)

/**
 * Singleton repository responsible for persistent storage, discovery, creation,
 * import, and lifecycle management of Lua script files.
 */
object LuaScriptRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _userScripts = MutableStateFlow<List<LuaScriptItem>>(emptyList())
    val userScripts = _userScripts.asStateFlow()

    private val _activeScript = MutableStateFlow<LuaScriptItem?>(null)
    val activeScript = _activeScript.asStateFlow()

    fun getPresets(): List<LuaScriptItem> = emptyList()

    fun getScriptsDirectory(context: Context): File {
        val dir = File(context.filesDir, "scripts")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun loadAllScripts(context: Context) {
        repositoryScope.launch {
            val list = withContext(Dispatchers.IO) {
                val dir = getScriptsDirectory(context)
                val files = dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".lua", ignoreCase = true) || file.name.endsWith(".txt", ignoreCase = true))
                } ?: emptyArray()

                files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
                    runCatching {
                        val content = file.readText(StandardCharsets.UTF_8)
                        LuaScriptItem(
                            id = "file_${file.name}",
                            name = file.name,
                            content = content,
                            isPreset = false,
                            lastModified = file.lastModified(),
                            sizeBytes = file.length(),
                            description = "Imported / User Script (${formatFileSize(file.length())})"
                        )
                    }.getOrNull()
                }
            }
            _userScripts.value = list
        }
    }

    suspend fun saveScript(context: Context, name: String, content: String): Result<LuaScriptItem> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitizedName = sanitizeFilename(name)
            val dir = getScriptsDirectory(context)
            val targetFile = File(dir, sanitizedName)
            targetFile.writeText(content, StandardCharsets.UTF_8)

            val item = LuaScriptItem(
                id = "file_${targetFile.name}",
                name = targetFile.name,
                content = content,
                isPreset = false,
                lastModified = targetFile.lastModified(),
                sizeBytes = targetFile.length(),
                description = "Imported / User Script (${formatFileSize(targetFile.length())})"
            )

            loadAllScripts(context)
            _activeScript.value = item
            item
        }
    }

    suspend fun deleteScript(context: Context, scriptId: String): Boolean = withContext(Dispatchers.IO) {
        val fileName = scriptId.removePrefix("file_")
        val dir = getScriptsDirectory(context)
        val targetFile = File(dir, fileName)
        val deleted = if (targetFile.exists()) targetFile.delete() else false
        if (deleted) {
            loadAllScripts(context)
            if (_activeScript.value?.id == scriptId) {
                _activeScript.value = null
            }
        }
        deleted
    }

    suspend fun importFromUri(context: Context, uri: Uri): Result<LuaScriptItem> = withContext(Dispatchers.IO) {
        runCatching {
            var displayName = "imported_script.lua"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        displayName = name
                    }
                }
            }

            val sanitizedName = sanitizeFilename(displayName)
            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } ?: throw IllegalStateException("Could not read file from selected URI")

            val dir = getScriptsDirectory(context)
            val targetFile = File(dir, sanitizedName)
            targetFile.writeText(content, StandardCharsets.UTF_8)

            val item = LuaScriptItem(
                id = "file_${targetFile.name}",
                name = targetFile.name,
                content = content,
                isPreset = false,
                lastModified = targetFile.lastModified(),
                sizeBytes = targetFile.length(),
                description = "Imported Script (${formatFileSize(targetFile.length())})"
            )

            loadAllScripts(context)
            _activeScript.value = item
            item
        }
    }

    fun setActiveScript(script: LuaScriptItem?) {
        _activeScript.value = script
    }

    private fun sanitizeFilename(name: String): String {
        var clean = name.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (clean.isBlank()) {
            clean = "script"
        }
        if (!clean.endsWith(".lua", ignoreCase = true) && !clean.endsWith(".txt", ignoreCase = true)) {
            clean = "$clean.lua"
        }
        return clean
    }

    fun formatFileSize(bytes: Long): String {
        return if (bytes < 1024) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.1f KB", bytes / 1024f)
        }
    }
}
