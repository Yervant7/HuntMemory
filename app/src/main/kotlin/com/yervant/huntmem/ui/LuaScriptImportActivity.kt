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

package com.yervant.huntmem.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.LuaScriptRepository
import com.yervant.huntmem.ui.overlay.tabs.LuaUiBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent bridge Activity enabling SAF document selection for Lua scripts
 * initiated from the overlay background service or main UI.
 */
class LuaScriptImportActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, LuaScriptImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var documentLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        documentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        LuaScriptRepository.importFromUri(applicationContext, uri)
                    }

                    result.onSuccess { item ->
                        val successMsg = getString(R.string.lua_script_imported_success, item.name)
                        LuaUiBridge.postLog("[Script] $successMsg")
                        LuaUiBridge.showToast(successMsg)
                    }.onFailure { err ->
                        val errMsg = "Failed to import script: ${err.message}"
                        LuaUiBridge.postLog("[Script Error] $errMsg")
                        LuaUiBridge.showToast(errMsg)
                    }

                    finish()
                }
            } else {
                finish()
            }
        }

        try {
            documentLauncher.launch(
                arrayOf(
                    "*/*",
                    "text/*",
                    "text/plain",
                    "application/x-lua",
                    "text/x-lua",
                    "application/octet-stream"
                )
            )
        } catch (_: Exception) {
            finish()
        }
    }
}
