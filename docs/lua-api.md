---
layout: default
title: Lua API Reference
---

# Lua API Reference

The Lua API in HuntMemory provides a powerful scripting interface for automating memory operations, creating UI elements, drawing on the screen, and managing background tasks. This document covers all available Lua functions grouped by functionality.

## Table of Contents

1. [Utility Functions](#utility-functions)
2. [Dynamic Menu API](#dynamic-menu-api)
3. [Canvas API](#canvas-api)
4. [Memory API](#memory-api)
5. [Freeze API](#freeze-api)
6. [Thread Management API](#thread-management-api)
7. [HTTP & Data API](#http--data-api)

## Utility Functions

### `log(level, message)`
Logs a message to the application's log output.
- `filename` : String - filename for log saved in /data/local/tmp/huntmem
- `level`: String - Log level ("debug", "info", "warn", "error")
- `message`: String - Message to log

### `showToast(message)`
Displays a toast message on the screen.
- `message`: String - Message to display

### `sleep(milliseconds)`
Pauses script execution for the specified duration.
- `milliseconds`: Number - Duration to sleep in milliseconds

### `getScreenSize()`
Returns the device's screen dimensions.
- Returns: Table with `width` and `height` keys

### `getAttachedPid()`
Returns the PID of the currently attached process.
- Returns: Number (PID) or nil if no process is attached

## Dynamic Menu API

The Dynamic Menu API allows you to create interactive UI components that float over the screen.

### `clear_menu()`
Removes all components from the dynamic menu.

### `add_label(text)`
Adds a non-interactive text label to the menu.
- `text`: String - Text to display
- Returns: String - Component ID

### `add_button(label, onClickCallback)`
Adds a button that executes a Lua function when clicked.
- `label`: String - Button text
- `onClickCallback`: Function - Function to call when clicked
- Returns: String - Component ID

### `add_switch(label, initialValue, onToggleCallback)`
Adds a toggle switch.
- `label`: String - Switch label
- `initialValue`: Boolean - Initial state
- `onToggleCallback`: Function - Called with new state when toggled
- Returns: String - Component ID

### `add_slider(label, initialValue, min, max, steps, onValueChangeCallback)`
Adds a slider control.
- `label`: String - Slider label
- `initialValue`: Number - Initial value
- `min`: Number - Minimum value
- `max`: Number - Maximum value
- `steps`: Number - Number of steps (0 for continuous)
- `onValueChangeCallback`: Function - Called with new value when changed
- Returns: String - Component ID

### `remove_item(id)`
Removes a specific component from the menu.
- `id`: String - Component ID

### `update_text(id, newText)`
Updates the text of a component.
- `id`: String - Component ID
- `newText`: String - New text

### `update_value(id, newValue)`
Updates the value of a slider component.
- `id`: String - Component ID
- `newValue`: Number - New value

## Canvas API

The Canvas API allows you to draw shapes and text on the screen overlay.

### `canvas.clear()`
Removes all drawings from the canvas.

### `canvas.drawLine(x1, y1, x2, y2, paint)`
Draws a line on the canvas.
- `x1`, `y1`: Number - Starting coordinates
- `x2`, `y2`: Number - Ending coordinates
- `paint`: Table - Styling properties
- Returns: String - Drawing ID

### `canvas.drawRect(left, top, right, bottom, paint)`
Draws a rectangle on the canvas.
- `left`, `top`: Number - Top-left coordinates
- `right`, `bottom`: Number - Bottom-right coordinates
- `paint`: Table - Styling properties
- Returns: String - Drawing ID

### `canvas.drawText(text, x, y, paint)`
Draws text on the canvas.
- `text`: String - Text to draw
- `x`, `y`: Number - Position coordinates
- `paint`: Table - Styling properties
- Returns: String - Drawing ID

### `canvas.drawCircle(cx, cy, radius, paint)`
Draws a circle on the canvas.
- `cx`, `cy`: Number - Center coordinates
- `radius`: Number - Circle radius
- `paint`: Table - Styling properties
- Returns: String - Drawing ID

### `canvas.remove(id)`
Removes a specific drawing from the canvas.
- `id`: String - Drawing ID

### `canvas.updatePosition(id, x, y)`
Updates the position of a drawing element.
- `id`: String - Drawing ID
- `x`, `y`: Number - New coordinates
- Returns: Boolean - Success status

### `canvas.updateColor(id, color)`
Updates the color of a drawing element.
- `id`: String - Drawing ID
- `color`: String - Hex color code
- Returns: Boolean - Success status

### `canvas.hasDrawing(id)`
Checks if a drawing with the given ID exists.
- `id`: String - Drawing ID
- Returns: Boolean

### `canvas.getDrawingsCount()`
Gets the total number of drawings on the canvas.
- Returns: Number

### Paint Table Format

The `paint` parameter for canvas functions is a table with the following optional keys:
- `color`: String - Hex color code (e.g., "#FFFF0000" for red)
- `strokeWidth`: Number - Line width for strokes
- `textSize`: Number - Text size for drawText
- `style`: String - Either "STROKE" or "FILL"

## Memory API

Functions for memory scanning, reading, and writing. Require a process to be attached.

### `searchMemory(value, valueType, operator)`
Searches memory for a value.
- `value`: String - Value to search for
- `valueType`: String - Data type ("int", "long", "float", "double")
- `operator`: String - Comparison operator ("=", "!=", ">", "<", ">=", "<=")

### `filterResults(value, valueType, operator)`
Alias for `searchMemory`.

### `getResults(limit)`
Retrieves results from the last search.
- `limit`: Number - Maximum number of results to return
- Returns: Table - Array of match objects

### `readMemory(address, valueType)`
Reads a value from a specific memory address.
- `address`: String - Hex address (e.g., "1234ABCD")
- `valueType`: String - Data type
- Returns: Value at the address

### `writeMemory(address, value, valueType)`
Writes a value to a specific memory address.
- `address`: String - Hex address
- `value`: String - Value to write
- `valueType`: String - Data type
- Returns: Boolean - Success status

### `gotoAddress(address)`
Jumps to a memory address.
- `address`: String - Address or pointer chain (e.g., "0x12345678+0x10")

### `dereferencePointer(address)`
Reads a pointer value and returns the address it points to.
- `address`: String - Hex address
- Returns: Table with pointer and value keys

### `getModuleBase(name)`
Gets the base address of a loaded library/module.
- `name`: String - Module name (e.g., "libgame.so")
- Returns: String - Base address or nil

### `clearResults()`
Clears the current list of search results.

## Freeze API

Functions to repeatedly write a value to a memory address.

### `startFreeze(address, value, valueType, interval)`
Starts a freeze operation.
- `address`: String - Hex address
- `value`: String - Value to freeze
- `valueType`: String - Data type
- `interval`: Number - Update interval in milliseconds (default: 100)
- Returns: String - Freeze operation ID

### `stopFreeze(freezeId)`
Stops a specific freeze operation.
- `freezeId`: String - Freeze operation ID
- Returns: Boolean - Success status

### `stopAllFreezes()`
Stops all freeze operations.

### `getActiveFreezes()`
Returns a list of all active freeze operations.
- Returns: Table - Array of freeze objects

## Thread Management API

Functions for creating and managing background threads.

### `createThread(name, function, interval, isRepeating, delay)`
Creates a new thread that executes the given Lua function.
- `name`: String - Thread name
- `function`: String - Lua code to execute
- `interval`: Number - Execution interval in milliseconds (default: 1000)
- `isRepeating`: Boolean - Whether to repeat execution (default: true)
- `delay`: Number - Initial delay in milliseconds (default: 0)
- Returns: String - Thread ID

### `pauseThread(threadId)`
Pauses a thread.
- `threadId`: String - Thread ID
- Returns: Boolean - Success status

### `resumeThread(threadId)`
Resumes a paused thread.
- `threadId`: String - Thread ID
- Returns: Boolean - Success status

### `stopThread(threadId)`
Stops a specific thread.
- `threadId`: String - Thread ID
- Returns: Boolean - Success status

### `stopAllThreads()`
Stops all threads.

### `getActiveThreads()`
Gets information about all active threads.
- Returns: Table - Array of thread objects

### `isThreadPaused(threadId)`
Checks if a thread is currently paused.
- `threadId`: String - Thread ID
- Returns: Boolean

### `getThreadExecutionCount(threadId)`
Gets the number of times a thread has been executed.
- `threadId`: String - Thread ID
- Returns: Number

### `getThreadStats()`
Gets detailed statistics about thread management.
- Returns: Table - Thread statistics

### `forceGarbageCollection()`
Forces garbage collection to free up memory.
- Returns: String - Status message

### `cleanupInactiveThreads()`
Cleans up inactive threads.
- Returns: String - Status message

## HTTP & Data API

Functions for web requests and simple key-value data storage.

### `setData(key, value)`
Store simple string data that persists for the script's session.
- `key`: String - Data key
- `value`: String - Data value

### `getData(key)`
Retrieve stored data.
- `key`: String - Data key
- Returns: String - Stored value or nil

### `clearData()`
Clears all stored data.
- Returns: String - Status message

### `httpGet(url)`
Performs an HTTP GET request.
- `url`: String - Request URL
- Returns: String - Response body or nil

### `httpPost(url, data, contentType)`
Performs an HTTP POST request.
- `url`: String - Request URL
- `data`: String - Request body
- `contentType`: String - Content type (default: "application/json; charset=utf-8")
- Returns: String - Response body or nil

### `downloadLuaFileAndExecute(url)`
Downloads a Lua script from a URL and executes it immediately.
- `url`: String - Script URL
- ⚠️ **Caution**: Only use trusted URLs