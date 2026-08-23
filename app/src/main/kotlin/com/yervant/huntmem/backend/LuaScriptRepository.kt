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

    const val PRESET_CHEAT_MENU = """-- HuntMemory Dynamic Overlay Menu
local pid = hmem.get_pid()
print("[Script] Attached to PID: " .. tostring(pid))

local choice = hmem.choice("Select Action", {
    "1. Infinite Health (Freeze)",
    "2. Add 50,000 Coins",
    "3. Set Custom Speed",
    "4. Create Persistent Overlay Panel"
})

if choice == 1 then
    local res = hmem.search("hp_scan", "100", "int")
    print("Found " .. res.count .. " matches. Freezing first address...")
    if res.count > 0 then
        local target = res.matches[1].address
        hmem.freeze(target, "9999", "int")
        hmem.toast("Health frozen to 9999!")
    end
elseif choice == 2 then
    local coin_addr_str = hmem.prompt("Enter Coins Address (Hex)", "0x7b000000", "hex")
    if coin_addr_str then
        local addr = tonumber(coin_addr_str)
        if addr then
            hmem.write_int(addr, 50000)
            hmem.toast("Added 50,000 coins!")
        end
    end
elseif choice == 3 then
    local speed = hmem.prompt("Enter Speed Multiplier", "2.5", "numeric")
    if speed then
        print("Set speed to: " .. speed)
        hmem.toast("Speed updated to " .. speed .. "x")
    end
elseif choice == 4 then
    -- Build dynamic overlay controls
    hmem.create_menu("Battle Cheats", {
        { type = "label", label = "Combat Modifiers", header = true },
        { type = "toggle", id = "god_mode", label = "God Mode (Invincible)", checked = true },
        { type = "toggle", id = "one_hit", label = "One-Hit Kill", checked = false },
        { type = "divider" },
        { type = "label", label = "Quick Actions", header = true },
        { type = "button", id = "heal_all", label = "Instant Full Heal", color = "#10B981" },
        { type = "button", id = "max_ammo", label = "Refill Max Ammo", color = "#3B82F6" }
    })
    hmem.toast("Dynamic Menu Created! Switched to Menu tab.")
end

return "Done"
"""

    const val PRESET_SEARCH_FREEZE = """-- Memory Search and Freeze Automation
local session = "auto_scan"
local target_val = hmem.prompt("Initial Value to Search", "100", "numeric")
if not target_val then return end

local maps = hmem.get_maps({ filter_types = "A,JH" })
print("Scanning " .. #maps .. " regions for " .. target_val)

local scan_res = hmem.search(session, target_val, "int", "equal", maps)
print("Initial matches: " .. scan_res.count)

if scan_res.count > 10 then
    local next_val = hmem.prompt("Value changed in game to:", "95", "numeric")
    if next_val then
        local refined = hmem.refine(session, next_val, "equal")
        print("Refined matches: " .. refined.count)
        if refined.count > 0 then
            hmem.freeze(refined.matches[1].address, "9999", "int")
            hmem.toast("Frozen address: 0x" .. string.format("%X", refined.matches[1].address))
        end
    end
end
"""

    const val PRESET_POINTER_RESOLVE = """-- Native Module Base & Multi-Level Pointer Resolver
local mod = "libil2cpp.so"
local base = hmem.get_module_base(mod)

if base then
    print(string.format("Found %s base address: 0x%X", mod, base))
    -- Resolve multi-level pointer chain: [libil2cpp.so + 0x1234] -> +0x20 -> +0x48
    local final_addr = hmem.resolve_pointer(base + 0x1234, { 0x20, 0x48 })
    if final_addr then
        local val = hmem.read_int(final_addr)
        print(string.format("Resolved address: 0x%X -> Value: %s", final_addr, tostring(val)))
        hmem.alert(string.format("Resolved: 0x%X\nValue: %s", final_addr, tostring(val)), "Pointer Resolved")
    else
        print("Failed to dereference pointer chain")
    end
else
    print("Module " .. mod .. " not found. Resolving from custom hex address:")
    local custom_base = tonumber("0x7b123400")
    if custom_base then
        local target = hmem.resolve_pointer(custom_base, { 0x48 })
        print("Target: 0x" .. string.format("%X", target or 0))
    end
end
"""

    const val PRESET_CANVAS_OVERLAY = """-- HuntMemory Real-Time Canvas Overlay / ESP Demo
local screen = canvas.get_screen_size()
local sw = screen.width or 1080
local sh = screen.height or 2400
local cx = sw / 2
local cy = sh / 2

print(string.format("[Canvas] Initialized overlay: %dx%d (Center: %.0f, %.0f)", sw, sh, cx, cy))
hmem.toast("Drawing Canvas Overlay...")

-- 1. Clear previous canvas drawings
canvas.clear()

-- 2. Draw tactical crosshair at screen center
canvas.draw_line(cx - 30, cy, cx + 30, cy, 3, "#00FF00")
canvas.draw_line(cx, cy - 30, cx, cy + 30, 3, "#00FF00")
canvas.draw_circle(cx, cy, 18, 2, "#00FF00", false)
canvas.draw_circle(cx, cy, 3, 2, "#FF0000", true)

-- 3. Draw Top HUD Banner
canvas.draw_rect(20, 80, sw - 40, 90, 2, "#AA000000", true)
canvas.draw_rect(20, 80, sw - 40, 90, 2, "#00E5FF", false)
canvas.draw_text("HuntMemory Real-Time Canvas Overlay", cx, 115, 16, "#00E5FF", "center")
canvas.draw_text(string.format("PID: %d | Res: %dx%d | Status: Active", hmem.get_pid(), sw, sh), cx, 145, 12, "#E2E8F0", "center")

-- 4. Draw simulated player bounding box & radar
local box_x = cx - 120
local box_y = cy - 200
local box_w = 240
local box_h = 320

-- Bounding box & snapline
canvas.draw_line(cx, sh - 100, cx, box_y + box_h, 1.5, "#FFFF00")
canvas.draw_rect(box_x, box_y, box_w, box_h, 2, "#FF0000", false)
canvas.draw_text("Target [Distance: 45m]", box_x, box_y - 10, 13, "#FF0000", "left")

-- Health Bar
canvas.draw_rect(box_x - 12, box_y, 6, box_h, 1, "#000000", true)
canvas.draw_rect(box_x - 12, box_y + (box_h * 0.25), 6, box_h * 0.75, 1, "#10B981", true)

-- 5. Draw Mini-Radar at bottom-right
local radar_x = sw - 140
local radar_y = sh - 300
local radar_r = 80
canvas.draw_circle(radar_x, radar_y, radar_r, 2, "#80000000", true)
canvas.draw_circle(radar_x, radar_y, radar_r, 2, "#00E5FF", false)
canvas.draw_circle(radar_x, radar_y, radar_r / 2, 1, "#4000E5FF", false)
canvas.draw_line(radar_x - radar_r, radar_y, radar_x + radar_r, radar_y, 1, "#4000E5FF")
canvas.draw_line(radar_x, radar_y - radar_r, radar_x, radar_y + radar_r, 1, "#4000E5FF")
-- Blips
canvas.draw_circle(radar_x, radar_y, 4, 1, "#00FF00", true)
canvas.draw_circle(radar_x + 30, radar_y - 25, 4, 1, "#FF0000", true)
canvas.draw_circle(radar_x - 40, radar_y + 35, 4, 1, "#FFFF00", true)

print("[Canvas] Overlay frame rendered successfully!")
return "Canvas Drawn"
"""

    fun getPresets(): List<LuaScriptItem> {
        return listOf(
            LuaScriptItem(
                id = "preset_cheat_menu",
                name = "Cheat Menu Template",
                content = PRESET_CHEAT_MENU,
                isPreset = true,
                description = "Demonstrates dynamic UI overlays, prompt/choice dialogs, and value freeze."
            ),
            LuaScriptItem(
                id = "preset_search_freeze",
                name = "Search & Freeze Loop",
                content = PRESET_SEARCH_FREEZE,
                isPreset = true,
                description = "Automated memory scanning with map filtering, value refinement, and freezing."
            ),
            LuaScriptItem(
                id = "preset_pointer_resolve",
                name = "Pointer Offset Reader",
                content = PRESET_POINTER_RESOLVE,
                isPreset = true,
                description = "Resolves module base addresses and dereferences multi-level pointer chains."
            ),
            LuaScriptItem(
                id = "preset_canvas_overlay",
                name = "Canvas Overlay / ESP HUD",
                content = PRESET_CANVAS_OVERLAY,
                isPreset = true,
                description = "Real-time overlay drawings with lines, bounding boxes, health bars, and radar."
            )
        )
    }

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
