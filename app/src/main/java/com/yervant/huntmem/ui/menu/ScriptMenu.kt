package com.yervant.huntmem.ui.menu

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.LuaAPI
import com.yervant.huntmem.ui.DialogCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object ScriptManager {
    private const val SCRIPT_DIR_NAME = "scripts"
    private const val TAG = "ScriptManager"

    private fun getScriptsDir(context: Context): File {
        return File(context.filesDir, SCRIPT_DIR_NAME)
    }

    private fun ensureScriptDirExists(context: Context) {
        val scriptsDir = getScriptsDir(context)
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs()
        }
    }

    suspend fun listScripts(context: Context): List<File> = withContext(Dispatchers.IO) {
        ensureScriptDirExists(context)
        val scriptsDir = getScriptsDir(context)
        return@withContext scriptsDir.listFiles { _, name -> name.endsWith(".lua") }?.toList() ?: emptyList()
    }

    suspend fun getScriptContent(file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.e(TAG, "Falha ao ler o conteúdo do script: ${file.name}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteScript(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) {
                return@withContext file.delete()
            }
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Falha de segurança ao deletar o script: ${file.name}", e)
            false
        }
    }
}

@Composable
fun ScriptMenu(
    context: Context,
    dialogCallback: DialogCallback
) {
    val coroutineScope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<File>>(emptyList()) }

    fun refreshScripts() {
        coroutineScope.launch {
            scripts = ScriptManager.listScripts(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshScripts()
        LuaAPI.initialize(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.script_menu_no_scripts_found_message),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = scripts, key = { it.absolutePath }) { script ->
                    ScriptItem(
                        script = script,
                        onExecute = {
                            coroutineScope.launch {
                                val contentResult = ScriptManager.getScriptContent(script)
                                contentResult.onSuccess { content ->
                                    val result = LuaAPI.executeScript(content)
                                    val title = context.getString(R.string.script_menu_script_execution_dialog_title)
                                    dialogCallback.showInfoDialog(
                                        title = title,
                                        message = result,
                                        onConfirm = {},
                                        onDismiss = {}
                                    )
                                }.onFailure { exception ->
                                    val err = context.getString(R.string.script_menu_error_dialog_title)
                                    val msg = context.getString(R.string.script_menu_error_reading_script_message, exception.message)
                                    dialogCallback.showInfoDialog(
                                        title = err,
                                        message = msg,
                                        onConfirm = {},
                                        onDismiss = {}
                                    )
                                }
                            }
                        },
                        onDelete = {
                            val title = context.getString(R.string.script_menu_confirm_deletion_dialog_title)
                            val msg = context.getString(R.string.script_menu_confirm_deletion_message, script.name)
                            dialogCallback.showInfoDialog(
                                title = title,
                                message = msg,
                                onConfirm = {
                                    coroutineScope.launch {
                                        val deleted = ScriptManager.deleteScript(script)
                                        if (deleted) {
                                            refreshScripts()
                                            val titl = context.getString(R.string.script_menu_script_deletion_dialog_title)
                                            val ms = context.getString(R.string.script_menu_script_deleted_message, script.name)
                                            dialogCallback.showInfoDialog(
                                                title = titl,
                                                message = ms,
                                                onConfirm = {},
                                                onDismiss = {}
                                            )
                                        } else {
                                            val err = context.getString(R.string.script_menu_error_dialog_title)
                                            val ms = context.getString(R.string.script_menu_failed_to_delete_script_message)
                                            dialogCallback.showInfoDialog(
                                                title = err,
                                                message = ms,
                                                onConfirm = {},
                                                onDismiss = {}
                                            )
                                        }
                                    }
                                },
                                onDismiss = { }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptItem(
    script: File,
    onExecute: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = script.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Row {
                IconButton(onClick = onExecute) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.script_menu_execute_script_icon_description),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.script_menu_delete_script_icon_description),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}