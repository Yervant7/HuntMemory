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

import android.graphics.Paint
import androidx.annotation.Keep
import androidx.compose.ui.graphics.Color
import com.yervant.huntmem.ILuaUiCallback
import com.yervant.huntmem.ui.keyboard.KeyboardType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Data structures representing interactive UI components dynamically declared by Lua scripts.
 */
sealed interface LuaMenuItem {
    /**
     * Unique identifier for the menu item.
     */
    val id: String

    /**
     * Interactive clickable button element.
     *
     * @property id Unique identifier emitted on click events.
     * @property label Button display label text.
     * @property colorHex Optional hex color code or named color (e.g. "#4CAF50", "red").
     */
    data class Button(
        override val id: String,
        val label: String,
        val colorHex: String? = null
    ) : LuaMenuItem

    /**
     * Interactive toggle / switch element.
     *
     * @property id Unique identifier emitted on toggle events.
     * @property label Switch label text.
     * @property isChecked Current toggle state.
     */
    data class Toggle(
        override val id: String,
        val label: String,
        val isChecked: Boolean
    ) : LuaMenuItem

    /**
     * Interactive text input field element.
     *
     * @property id Unique identifier emitted on value changes.
     * @property label Input label text.
     * @property value Current text content.
     * @property placeholder Optional placeholder text when empty.
     */
    data class InputField(
        override val id: String,
        val label: String,
        val value: String,
        val placeholder: String = ""
    ) : LuaMenuItem

    /**
     * Static text label or section header.
     *
     * @property id Unique identifier.
     * @property text Text content displayed.
     * @property isHeader Whether this label is rendered with header typography.
     */
    data class Label(
        override val id: String,
        val text: String,
        val isHeader: Boolean = false
    ) : LuaMenuItem

    /**
     * Horizontal visual divider between menu sections.
     *
     * @property id Unique identifier.
     */
    data class Divider(
        override val id: String = ""
    ) : LuaMenuItem
}

/**
 * Container holding a complete dynamically declared menu hierarchy.
 *
 * @property title Top title displayed in the dynamic menu overlay.
 * @property items Ordered list of interactive [LuaMenuItem] components.
 */
data class LuaDynamicMenu(
    val title: String,
    val items: List<LuaMenuItem>
)

/**
 * Interactive synchronous modal dialogs dispatched from background Lua execution to the Compose UI.
 */
sealed interface LuaDialog {
    /**
     * Simple informational alert dialog with an OK confirmation.
     *
     * @property title Dialog title.
     * @property message Descriptive message body.
     * @property deferred Completable signal resolved when user confirms.
     */
    data class Alert(
        val title: String,
        val message: String,
        val deferred: CompletableDeferred<Unit>
    ) : LuaDialog

    /**
     * Single-line input prompt dialog with virtual keyboard integration.
     *
     * @property title Prompt title.
     * @property defaultValue Initial text in input field.
     * @property keyboardType Layout type for the virtual keyboard (QWERTY, Numeric, Hex).
     * @property deferred Completable signal returning user input string or null if dismissed.
     */
    data class Prompt(
        val title: String,
        val defaultValue: String,
        val keyboardType: KeyboardType,
        val deferred: CompletableDeferred<String?>
    ) : LuaDialog

    /**
     * Single-selection item list dialog.
     *
     * @property title Dialog title.
     * @property items List of candidate option strings.
     * @property deferred Completable signal returning selected index (1-based from Lua perspective) or null.
     */
    data class Choice(
        val title: String,
        val items: List<String>,
        val deferred: CompletableDeferred<Int?>
    ) : LuaDialog

    /**
     * Multiple-selection checklist dialog.
     *
     * @property title Dialog title.
     * @property items List of candidate option strings.
     * @property initialSelected Boolean selection state for each candidate.
     * @property deferred Completable signal returning boolean selection list or null if dismissed.
     */
    data class MultiChoice(
        val title: String,
        val items: List<String>,
        val initialSelected: List<Boolean>,
        val deferred: CompletableDeferred<List<Boolean>?>
    ) : LuaDialog
}

/**
 * High-performance data structures for real-time Canvas overlay drawings emitted by Lua scripts.
 * Pre-resolves [Color] and [Paint.Align] to guarantee zero heap allocations during Compose DrawScope phases.
 */
sealed interface CanvasDrawCommand {
    /**
     * Pre-resolved Compose Color for this drawing element.
     */
    val color: Color

    /**
     * Canvas text rendering command.
     *
     * @property text Text content to draw.
     * @property x Horizontal coordinate.
     * @property y Vertical coordinate.
     * @property size Font size in points.
     * @property color Text rendering color.
     * @property align Horizontal alignment relative to (x, y).
     */
    data class Text(
        val text: String,
        val x: Float,
        val y: Float,
        val size: Float = 14f,
        override val color: Color = Color.White,
        val align: Paint.Align = Paint.Align.LEFT
    ) : CanvasDrawCommand

    /**
     * 2D line rendering command.
     *
     * @property x1 Start X coordinate.
     * @property y1 Start Y coordinate.
     * @property x2 End X coordinate.
     * @property y2 End Y coordinate.
     * @property strokeWidth Stroke width in pixels.
     * @property color Line color.
     */
    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val strokeWidth: Float = 2f,
        override val color: Color = Color.White
    ) : CanvasDrawCommand

    /**
     * 2D rectangle rendering command.
     *
     * @property x Top-left X coordinate.
     * @property y Top-left Y coordinate.
     * @property width Rectangle width.
     * @property height Rectangle height.
     * @property strokeWidth Outline stroke width if not filled.
     * @property color Rectangle color.
     * @property filled Whether the rectangle is filled or stroked.
     */
    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val strokeWidth: Float = 2f,
        override val color: Color = Color.White,
        val filled: Boolean = false
    ) : CanvasDrawCommand

    /**
     * 2D circle rendering command.
     *
     * @property cx Center X coordinate.
     * @property cy Center Y coordinate.
     * @property radius Circle radius.
     * @property strokeWidth Outline stroke width if not filled.
     * @property color Circle color.
     * @property filled Whether the circle is filled or stroked.
     */
    data class Circle(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val strokeWidth: Float = 2f,
        override val color: Color = Color.White,
        val filled: Boolean = false
    ) : CanvasDrawCommand
}

/**
 * Reactive bridge connecting background Lua script execution with the Jetpack Compose Overlay UI.
 *
 * Manages dynamic menus, modal interactive dialogs, console log buffers, real-time Canvas overlay commands,
 * and IPC callbacks between root services and UI components.
 */
object LuaUiBridge {

    private val _dynamicMenu = MutableStateFlow<LuaDynamicMenu?>(null)
    val dynamicMenu = _dynamicMenu.asStateFlow()

    private val _activeDialog = MutableStateFlow<LuaDialog?>(null)
    val activeDialog = _activeDialog.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val consoleLogs = _consoleLogs.asStateFlow()

    private val _isScriptRunning = MutableStateFlow(false)
    val isScriptRunning = _isScriptRunning.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage = _toastMessage.asStateFlow()

    private val _canvasCommands = MutableStateFlow<List<CanvasDrawCommand>>(emptyList())
    val canvasCommands = _canvasCommands.asStateFlow()

    private val _isCanvasVisible = MutableStateFlow(true)
    val isCanvasVisible = _isCanvasVisible.asStateFlow()

    private val _screenWidth = MutableStateFlow(
        runCatching { android.content.res.Resources.getSystem().displayMetrics.widthPixels }.getOrNull()?.takeIf { it > 0 } ?: 1080
    )
    @Suppress("unused")
    val screenWidth = _screenWidth.asStateFlow()

    private val _screenHeight = MutableStateFlow(
        runCatching { android.content.res.Resources.getSystem().displayMetrics.heightPixels }.getOrNull()?.takeIf { it > 0 } ?: 2400
    )
    @Suppress("unused")
    val screenHeight = _screenHeight.asStateFlow()

    // Action listeners for dynamic menu items
    private val menuListeners = ConcurrentHashMap<String, (Any?) -> Unit>()

    // Event queue for Lua scripts polling dynamic menu events
    private val menuEventQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * AIDL callback instance bridging cross-process root service calls into this UI bridge.
     */
    val aidlCallback = object : ILuaUiCallback.Stub() {
        override fun showAlert(title: String?, message: String?) {
            LuaUiBridge.showAlert(title ?: "Alert", message ?: "")
        }

        override fun showToast(message: String?) {
            LuaUiBridge.showToast(message ?: "")
        }

        override fun showPrompt(title: String?, defaultValue: String?, keyboardType: String?): String {
            return LuaUiBridge.showPrompt(title ?: "", defaultValue ?: "", keyboardType ?: "qwerty") ?: ""
        }

        override fun showChoice(title: String?, itemsJson: String?): Int {
            return LuaUiBridge.showChoice(title ?: "Select", itemsJson ?: "[]")
        }

        override fun showMultiChoice(title: String?, itemsJson: String?, initialSelectedJson: String?): String {
            return LuaUiBridge.showMultiChoice(title ?: "Select", itemsJson ?: "[]", initialSelectedJson ?: "[]")
        }

        override fun setDynamicMenu(menuJson: String?) {
            if (menuJson != null) {
                LuaUiBridge.setDynamicMenu(menuJson)
            }
        }

        override fun clearDynamicMenu() {
            LuaUiBridge.clearDynamicMenu()
        }

        override fun postLog(line: String?) {
            if (line != null) {
                LuaUiBridge.postLog(line)
            }
        }

        override fun canvasDrawBinary(bytes: ByteArray?) {
            if (bytes != null) {
                LuaUiBridge.canvasDrawBinary(bytes)
            }
        }

        override fun canvasDraw(commandsJson: String?) {
            if (commandsJson != null) {
                LuaUiBridge.canvasDraw(commandsJson)
            }
        }

        override fun canvasClear() {
            LuaUiBridge.canvasClear()
        }

        override fun canvasSetVisible(visible: Boolean) {
            LuaUiBridge.canvasSetVisible(visible)
        }

        override fun getScreenDimensions(): Long {
            return LuaUiBridge.getScreenDimensions()
        }

        override fun pollMenuEvent(): String {
            return LuaUiBridge.pollMenuEvent() ?: ""
        }
    }

    /**
     * Exposes the [ILuaUiCallback] interface for AIDL registration.
     */
    fun asAidlCallback(): ILuaUiCallback = aidlCallback

    /**
     * Polls the next pending UI event queued for Lua scripts.
     */
    fun pollMenuEvent(): String? = menuEventQueue.poll()

    /**
     * Queues a user interaction event triggered from the dynamic menu.
     *
     * @param type Event type ('click', 'toggle', 'input').
     * @param id Menu item identifier.
     * @param value Optional payload value (e.g. boolean for toggle, string for input).
     */
    fun pushMenuEvent(type: String, id: String, value: Any? = null) {
        val obj = JSONObject().apply {
            put("type", type)
            put("id", id)
            if (value != null) {
                put("value", value)
            }
        }
        menuEventQueue.offer(obj.toString())
    }

    /**
     * Updates the active script running indicator.
     */
    fun setScriptRunning(running: Boolean) {
        _isScriptRunning.value = running
    }

    /**
     * Appends a log line to the reactive console buffer, capping maximum retained lines.
     */
    fun postLog(line: String) {
        _consoleLogs.update { current ->
            if (current.size > 2000) {
                current.drop(500) + line
            } else {
                current + line
            }
        }
    }

    /**
     * Clears all recorded console logs.
     */
    fun clearLogs() {
        _consoleLogs.value = emptyList()
    }

    /**
     * Dismisses the active toast notification.
     */
    fun dismissToast() {
        _toastMessage.value = null
    }

    // --- Dynamic Menu Management ---

    /**
     * Parses a JSON string describing a dynamic menu and publishes it to the overlay UI.
     *
     * @param menuJson JSON payload with title and array of item objects.
     */
    @JvmStatic
    @Keep
    fun setDynamicMenu(menuJson: String) {
        try {
            val root = JSONObject(menuJson)
            val title = root.optString("title", "Script Menu")
            val itemsArr = root.optJSONArray("items") ?: JSONArray()
            val itemsList = mutableListOf<LuaMenuItem>()

            for (i in 0 until itemsArr.length()) {
                val itemObj = itemsArr.getJSONObject(i)
                val type = itemObj.optString("type", "button").lowercase()
                val id = itemObj.optString("id", "item_$i")
                val label = itemObj.optString("label", itemObj.optString("title", id))

                when (type) {
                    "button", "btn" -> {
                        val color = itemObj.optString("color").takeIf { it.isNotEmpty() }
                        itemsList.add(LuaMenuItem.Button(id, label, color))
                    }
                    "toggle", "switch", "checkbox" -> {
                        val isChecked = itemObj.optBoolean("checked", itemObj.optBoolean("value", false))
                        itemsList.add(LuaMenuItem.Toggle(id, label, isChecked))
                    }
                    "input", "text", "edit" -> {
                        val value = itemObj.optString("value", "")
                        val placeholder = itemObj.optString("placeholder", "")
                        itemsList.add(LuaMenuItem.InputField(id, label, value, placeholder))
                    }
                    "label", "text_label" -> {
                        val isHeader = itemObj.optBoolean("header", false)
                        itemsList.add(LuaMenuItem.Label(id, label, isHeader))
                    }
                    "divider", "separator" -> {
                        itemsList.add(LuaMenuItem.Divider(id))
                    }
                }
            }

            _dynamicMenu.value = LuaDynamicMenu(title, itemsList)
        } catch (e: Exception) {
            postLog("[Error] Failed to parse dynamic menu JSON: ${e.message}")
        }
    }

    /**
     * Clears the current active dynamic menu and resets registered listeners.
     */
    @JvmStatic
    @Keep
    fun clearDynamicMenu() {
        _dynamicMenu.value = null
        menuListeners.clear()
    }

    /**
     * Handler invoked when a dynamic menu button is tapped.
     */
    fun onMenuItemClicked(id: String) {
        postLog("[Menu Action] Clicked: $id")
        pushMenuEvent("click", id)
        menuListeners[id]?.invoke(true)
    }

    /**
     * Handler invoked when a dynamic menu toggle switch changes state.
     */
    fun onMenuItemToggled(id: String, isChecked: Boolean) {
        postLog("[Menu Action] Toggled: $id = $isChecked")
        pushMenuEvent("toggle", id, isChecked)
        _dynamicMenu.update { current ->
            current?.copy(
                items = current.items.map { item ->
                    if (item.id == id && item is LuaMenuItem.Toggle) {
                        item.copy(isChecked = isChecked)
                    } else {
                        item
                    }
                }
            )
        }
        menuListeners[id]?.invoke(isChecked)
    }

    /**
     * Handler invoked when a dynamic menu input field text changes.
     */
    fun onMenuItemInputChanged(id: String, newValue: String) {
        pushMenuEvent("input", id, newValue)
        _dynamicMenu.update { current ->
            current?.copy(
                items = current.items.map { item ->
                    if (item.id == id && item is LuaMenuItem.InputField) {
                        item.copy(value = newValue)
                    } else {
                        item
                    }
                }
            )
        }
        menuListeners[id]?.invoke(newValue)
    }

    // --- Interactive Synchronous Dialogs called from JNI/Lua ---
    // Note: These methods use runBlocking to wait for user interaction because Lua scripts execute
    // on a background worker thread / JNI service thread, matching standard GameGuardian/Lua paradigms.

    /**
     * Displays a synchronous alert modal and suspends caller thread until acknowledged.
     *
     * @param title Dialog title.
     * @param message Message content.
     */
    @JvmStatic
    @Keep
    fun showAlert(title: String, message: String) {
        val deferred = CompletableDeferred<Unit>()
        _activeDialog.value = LuaDialog.Alert(title, message, deferred)
        runBlocking {
            deferred.await()
        }
        _activeDialog.value = null
    }

    /**
     * Dispatches a lightweight toast notification to the overlay.
     *
     * @param message Toast message text.
     */
    @JvmStatic
    @Keep
    fun showToast(message: String) {
        _toastMessage.value = message
    }

    /**
     * Displays a synchronous prompt dialog and suspends caller thread until user submits or dismisses.
     *
     * @param title Prompt title.
     * @param defaultValue Pre-filled text.
     * @param keyboardTypeStr Keyboard layout identifier ('numeric', 'hex', 'qwerty').
     * @return User-entered string, or null if dismissed.
     */
    @JvmStatic
    @Keep
    fun showPrompt(title: String, defaultValue: String, keyboardTypeStr: String): String? {
        val deferred = CompletableDeferred<String?>()
        val kbType = when (keyboardTypeStr.lowercase()) {
            "numeric", "number", "num" -> KeyboardType.NUMERIC
            "hex", "hexadecimal" -> KeyboardType.HEXADECIMAL
            else -> KeyboardType.QWERTY
        }
        _activeDialog.value = LuaDialog.Prompt(title, defaultValue, kbType, deferred)
        val result = runBlocking {
            deferred.await()
        }
        _activeDialog.value = null
        return result
    }

    /**
     * Displays a synchronous single-selection choice dialog and suspends caller thread.
     *
     * @param title Dialog title.
     * @param itemsJson JSON array string of option items.
     * @return 1-based index selected, or -1 if dismissed.
     */
    @JvmStatic
    @Keep
    fun showChoice(title: String, itemsJson: String): Int {
        val deferred = CompletableDeferred<Int?>()
        val items = mutableListOf<String>()
        try {
            val arr = JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                items.add(arr.getString(i))
            }
        } catch (_: Exception) {
            return -1
        }

        _activeDialog.value = LuaDialog.Choice(title, items, deferred)
        val selectedIdx = runBlocking {
            deferred.await()
        }
        _activeDialog.value = null
        return selectedIdx ?: -1
    }

    /**
     * Displays a synchronous multiple-selection dialog and suspends caller thread.
     *
     * @param title Dialog title.
     * @param itemsJson JSON array string of option items.
     * @param preselectedJson JSON array string of initial boolean selections.
     * @return JSON array string of booleans, or "[]" if dismissed.
     */
    @JvmStatic
    @Keep
    fun showMultiChoice(title: String, itemsJson: String, preselectedJson: String): String {
        val deferred = CompletableDeferred<List<Boolean>?>()
        val items = mutableListOf<String>()
        val initialSelected = mutableListOf<Boolean>()
        try {
            val arr = JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                items.add(arr.getString(i))
            }
            val selArr = JSONArray(preselectedJson)
            for (i in items.indices) {
                initialSelected.add(selArr.optBoolean(i, false))
            }
        } catch (_: Exception) {
            return "[]"
        }

        _activeDialog.value = LuaDialog.MultiChoice(title, items, initialSelected, deferred)
        val resultList = runBlocking {
            deferred.await()
        }
        _activeDialog.value = null

        return if (resultList != null) {
            val out = JSONArray()
            for (b in resultList) {
                out.put(b)
            }
            out.toString()
        } else {
            "[]"
        }
    }

    // --- Real-Time Canvas Overlay Management ---

    const val CANVAS_MODE_REPLACE: Byte = 0
    const val CANVAS_MODE_APPEND: Byte = 1

    const val CANVAS_CMD_TEXT: Byte = 1
    const val CANVAS_CMD_LINE: Byte = 2
    const val CANVAS_CMD_RECT: Byte = 3
    const val CANVAS_CMD_CIRCLE: Byte = 4

    /**
     * Decodes compact Little-Endian binary drawing commands directly emitted by the Rust Canvas engine.
     *
     * @param data Binary payload conforming to the canvas binary protocol.
     */
    @JvmStatic
    @Keep
    fun canvasDrawBinary(data: ByteArray) {
        if (data.size < 3) return
        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val mode = buffer.get()
            val count = buffer.short.toInt() and 0xFFFF
            val list = ArrayList<CanvasDrawCommand>(count.coerceAtLeast(1))

            repeat(count) {
                if (!buffer.hasRemaining()) return@repeat
                when (buffer.get()) {
                    CANVAS_CMD_TEXT -> {
                        val colorArgb = buffer.int
                        val x = buffer.float
                        val y = buffer.float
                        val size = buffer.float
                        val alignByte = buffer.get().toInt()
                        val align = when (alignByte) {
                            1 -> Paint.Align.CENTER
                            2 -> Paint.Align.RIGHT
                            else -> Paint.Align.LEFT
                        }
                        val textLen = buffer.short.toInt() and 0xFFFF
                        val text = if (textLen > 0 && buffer.remaining() >= textLen) {
                            val textBytes = ByteArray(textLen)
                            buffer.get(textBytes)
                            String(textBytes, StandardCharsets.UTF_8)
                        } else {
                            ""
                        }
                        list.add(
                            CanvasDrawCommand.Text(
                                text = text,
                                x = x,
                                y = y,
                                size = size,
                                color = Color(colorArgb),
                                align = align
                            )
                        )
                    }
                    CANVAS_CMD_LINE -> {
                        val colorArgb = buffer.int
                        val x1 = buffer.float
                        val y1 = buffer.float
                        val x2 = buffer.float
                        val y2 = buffer.float
                        val stroke = buffer.float
                        list.add(
                            CanvasDrawCommand.Line(
                                x1 = x1,
                                y1 = y1,
                                x2 = x2,
                                y2 = y2,
                                strokeWidth = stroke,
                                color = Color(colorArgb)
                            )
                        )
                    }
                    CANVAS_CMD_RECT -> {
                        val colorArgb = buffer.int
                        val x = buffer.float
                        val y = buffer.float
                        val width = buffer.float
                        val height = buffer.float
                        val stroke = buffer.float
                        val filled = buffer.get() != 0.toByte()
                        list.add(
                            CanvasDrawCommand.Rect(
                                x = x,
                                y = y,
                                width = width,
                                height = height,
                                strokeWidth = stroke,
                                color = Color(colorArgb),
                                filled = filled
                            )
                        )
                    }
                    CANVAS_CMD_CIRCLE -> {
                        val colorArgb = buffer.int
                        val cx = buffer.float
                        val cy = buffer.float
                        val radius = buffer.float
                        val stroke = buffer.float
                        val filled = buffer.get() != 0.toByte()
                        list.add(
                            CanvasDrawCommand.Circle(
                                cx = cx,
                                cy = cy,
                                radius = radius,
                                strokeWidth = stroke,
                                color = Color(colorArgb),
                                filled = filled
                            )
                        )
                    }
                }
            }

            when (mode) {
                CANVAS_MODE_APPEND -> _canvasCommands.update { current -> current + list }
                CANVAS_MODE_REPLACE -> _canvasCommands.value = list
                else -> _canvasCommands.value = list
            }
        } catch (e: Exception) {
            postLog("[Canvas Binary Error] Failed to parse draw commands: ${e.message}")
        }
    }

    /**
     * Parses a JSON array of drawing commands and updates the overlay Canvas state.
     *
     * @param commandsJson JSON array string of command objects.
     */
    @JvmStatic
    @Keep
    fun canvasDraw(commandsJson: String) {
        try {
            val arr = JSONArray(commandsJson)
            val list = ArrayList<CanvasDrawCommand>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val color = parseColorFast(obj.optString("color", "#FFFFFFFF"))
                when (obj.optString("type").lowercase()) {
                    "text" -> {
                        val align = when (obj.optString("align", "left").lowercase()) {
                            "center" -> Paint.Align.CENTER
                            "right" -> Paint.Align.RIGHT
                            else -> Paint.Align.LEFT
                        }
                        list.add(
                            CanvasDrawCommand.Text(
                                text = obj.optString("text", ""),
                                x = obj.optDouble("x", 0.0).toFloat(),
                                y = obj.optDouble("y", 0.0).toFloat(),
                                size = obj.optDouble("size", 14.0).toFloat(),
                                color = color,
                                align = align
                            )
                        )
                    }
                    "line" -> {
                        list.add(
                            CanvasDrawCommand.Line(
                                x1 = obj.optDouble("x1", 0.0).toFloat(),
                                y1 = obj.optDouble("y1", 0.0).toFloat(),
                                x2 = obj.optDouble("x2", 0.0).toFloat(),
                                y2 = obj.optDouble("y2", 0.0).toFloat(),
                                strokeWidth = obj.optDouble("stroke", 2.0).toFloat(),
                                color = color
                            )
                        )
                    }
                    "rect", "box" -> {
                        list.add(
                            CanvasDrawCommand.Rect(
                                x = obj.optDouble("x", 0.0).toFloat(),
                                y = obj.optDouble("y", 0.0).toFloat(),
                                width = obj.optDouble("width", 0.0).toFloat(),
                                height = obj.optDouble("height", 0.0).toFloat(),
                                strokeWidth = obj.optDouble("stroke", 2.0).toFloat(),
                                color = color,
                                filled = obj.optBoolean("filled", false)
                            )
                        )
                    }
                    "circle" -> {
                        list.add(
                            CanvasDrawCommand.Circle(
                                cx = obj.optDouble("cx", 0.0).toFloat(),
                                cy = obj.optDouble("cy", 0.0).toFloat(),
                                radius = obj.optDouble("radius", 10.0).toFloat(),
                                strokeWidth = obj.optDouble("stroke", 2.0).toFloat(),
                                color = color,
                                filled = obj.optBoolean("filled", false)
                            )
                        )
                    }
                }
            }
            _canvasCommands.value = list
        } catch (e: Exception) {
            postLog("[Canvas Error] Failed to parse draw commands: ${e.message}")
        }
    }

    /**
     * Clears all active Canvas drawing commands.
     */
    @JvmStatic
    @Keep
    fun canvasClear() {
        _canvasCommands.value = emptyList()
    }

    /**
     * Toggles whether the hardware-accelerated Canvas overlay is rendered.
     */
    @JvmStatic
    @Keep
    fun canvasSetVisible(visible: Boolean) {
        _isCanvasVisible.value = visible
    }

    /**
     * Returns screen width and height packed into a single 64-bit long: (width << 32) | height.
     */
    @JvmStatic
    @Keep
    fun getScreenDimensions(): Long {
        val w = _screenWidth.value.toLong() and 0xFFFFFFFFL
        val h = _screenHeight.value.toLong() and 0xFFFFFFFFL
        return (w shl 32) or h
    }

    /**
     * Updates recorded screen dimensions when window or display configuration changes.
     */
    fun updateScreenDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            _screenWidth.value = width
            _screenHeight.value = height
        }
    }

    /**
     * Fast parser converting named color strings or hex codes (#RRGGBB, #AARRGGBB, 0xRRGGBB)
     * into properly structured 32-bit ARGB Jetpack Compose [Color] instances.
     *
     * @param hex Color string representation.
     * @return Jetpack Compose [Color] instance.
     */
    fun parseColorFast(hex: String): Color {
        val clean = hex.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        return try {
            when (clean.lowercase()) {
                "red" -> Color(0xFFFF0000.toInt())
                "green" -> Color(0xFF00FF00.toInt())
                "blue" -> Color(0xFF0000FF.toInt())
                "yellow" -> Color(0xFFFFFF00.toInt())
                "cyan" -> Color(0xFF00FFFF.toInt())
                "magenta" -> Color(0xFFFF00FF.toInt())
                "white" -> Color(0xFFFFFFFF.toInt())
                "black" -> Color(0xFF000000.toInt())
                "gray", "grey" -> Color(0xFF888888.toInt())
                "transparent" -> Color(0x00000000)
                else -> {
                    val colorInt = clean.toLong(16).toInt()
                    if (clean.length <= 6) {
                        Color(colorInt or 0xFF000000.toInt())
                    } else {
                        Color(colorInt)
                    }
                }
            }
        } catch (_: Exception) {
            Color.White
        }
    }
}
