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

package com.yervant.huntmem;

/**
 * Cross-process callback interface for UI interactions invoked by Lua scripts.
 * Enables the Root Daemon (HMemService) to communicate with the application's Compose Overlay.
 */
interface ILuaUiCallback {

    /** Synchronous alert dialog */
    void showAlert(String title, String message);

    /** Asynchronous toast notification */
    oneway void showToast(String message);

    /** Synchronous text prompt dialog */
    String showPrompt(String title, String defaultValue, String keyboardType);

    /** Synchronous single choice selection dialog */
    int showChoice(String title, String itemsJson);

    /** Synchronous multiple choice selection dialog */
    String showMultiChoice(String title, String itemsJson, String initialSelectedJson);

    /** Asynchronously updates dynamic Compose overlay menu */
    oneway void setDynamicMenu(String menuJson);

    /** Asynchronously clears dynamic Compose overlay menu */
    oneway void clearDynamicMenu();

    /** Asynchronously streams a console log line */
    oneway void postLog(String line);

    /** Asynchronously renders binary draw commands on the real-time canvas overlay */
    oneway void canvasDrawBinary(in byte[] bytes);

    /** Asynchronously renders JSON draw commands on the real-time canvas overlay */
    oneway void canvasDraw(String commandsJson);

    /** Asynchronously clears all real-time canvas overlay drawings */
    oneway void canvasClear();

    /** Asynchronously toggles real-time canvas visibility */
    oneway void canvasSetVisible(boolean visible);

    /** Synchronously queries screen dimensions in pixels (packed as (width << 32) | height) */
    long getScreenDimensions();

    /** Synchronously checks if a dynamic menu event is available (JSON string or empty) */
    String pollMenuEvent();
}
