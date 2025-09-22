--[[
HMemory Lua API Example Script

This script provides a comprehensive demonstration of the Lua API available in HMemory.
It covers the following modules:
1.  Utility Functions: General-purpose functions like logging, toasts, and getting device info.
2.  Dynamic Menu API: Functions to create and manage a dynamic, interactive UI menu.
3.  Canvas API: Functions for drawing shapes and text on the screen overlay.
4.  Memory API: Functions for searching, reading, and writing to a target process's memory.
5.  Freeze API: Functions to "freeze" a memory value, repeatedly writing it at a set interval.
6.  Thread Management API: Functions for creating and managing background threads.
7.  HTTP & Data API: Functions for making HTTP requests and storing temporary data.

Each section is clearly marked and contains explanations for each function.
--]]

-- =================================================================================
-- 1. UTILITY FUNCTIONS
-- These are general-purpose helper functions.
-- =================================================================================
log("info", "HuntMemory Lua API example script started.")
log("filename.txt", "info", "This is a log message written in an file.")) -- Log to a file on /data/local/tmp/huntmem
showToast("Welcome to the HuntMemory Lua API!")

-- sleep(milliseconds)
-- Pauses the script execution for a specified duration.
log("Pausing for 2 seconds...")
sleep(2000)
log("Resumed execution.")

-- getScreenSize() -> {width, height}
-- Returns a table containing the screen width and height in pixels.
local screenSize = getScreenSize()
local screenWidth = screenSize.width
local screenHeight = screenSize.height
log("Screen size: " .. screenWidth .. "x" .. screenHeight)
showToast("Screen: " .. screenWidth .. "x" .. screenHeight)


-- =================================================================================
-- 2. DYNAMIC MENU API (`DynamicMenuManager`)
-- Create and manage an interactive floating menu.
-- All `add_*` functions return an ID that can be used to modify the component later.
-- =================================================================================
log("--- 2. Dynamic Menu API ---")

-- clear_menu()
-- Removes all components from the dynamic menu.
clear_menu()
log("Menu cleared.")

-- add_label(text) -> id
-- Adds a non-interactive text label to the menu.
local titleLabelId = add_label("HuntMemory Lua API Demo")
log("Added title label with ID: " .. titleLabelId)

-- add_button(label, onClickCallback) -> id
-- Adds a button that executes a Lua function when clicked.
local function onButtonClick()
    log("Button was clicked!")
    showToast("You clicked the button!")
    -- update_text(id, newText)
    -- Updates the text of a component (Label, Button, Switch).
    update_text(titleLabelId, "Button clicked at: " .. os.date())
end
local buttonId = add_button("Click Me", onButtonClick)
log("Added button with ID: " .. buttonId)

-- add_switch(label, initialValue, onToggleCallback) -> id
-- Adds a toggle switch. The callback function receives the new state (true/false).
local isFeatureEnabled = false
local function onFeatureToggle(isChecked)
    isFeatureEnabled = isChecked
    if isFeatureEnabled then
        showToast("Feature Enabled")
        log("Feature switch is now ON")
    else
        showToast("Feature Disabled")
        log("Feature switch is now OFF")
    end
end
local switchId = add_switch("Enable Feature", isFeatureEnabled, onFeatureToggle)
log("Added switch with ID: " .. switchId)

-- add_slider(label, initialValue, min, max, steps, onValueChangeCallback) -> id
-- Adds a slider. The callback receives the new float value.
-- `steps` is optional (0 for continuous).
local sliderLabelId = add_label("Slider Value: 50.0")
local function onSliderChange(newValue)
    log("Slider value changed to: " .. newValue)
    -- Example of updating a label with the slider's value.
    update_text(sliderLabelId, string.format("Slider Value: %.1f", newValue))
    -- update_value(id, newValue)
    -- Programmatically sets a slider's value. This is just for demonstration.
    -- update_value(sliderId, newValue)
end
local sliderId = add_slider("Control Value", 50.0, 0.0, 100.0, 0, onSliderChange)
log("Added slider with ID: " .. sliderId)

-- remove_item(id)
-- Removes a specific component from the menu using its ID.
local removableLabelId = add_label("This label will be removed")
sleep(3000) -- Wait 3 seconds
remove_item(removableLabelId)
log("Removed the temporary label.")


-- =================================================================================
-- 3. CANVAS API (`CanvasManager`)
-- Draw shapes and text on the screen overlay.
-- All `draw*` functions return an ID for later removal.
-- A `paint` table is used for styling: { color="#AARRGGBB", strokeWidth=float, textSize=float, style="STROKE"|"FILL" }
-- =================================================================================
log("--- 3. Canvas API ---")

-- canvas.clear()
-- Removes all drawings from the canvas.
canvas.clear()
log("Canvas cleared.")

-- Define some paint styles to reuse.
local paintRedStroke = { color = "#FFFF0000", strokeWidth = 3, style = "STROKE" }
local paintGreenFill = { color = "#FF00FF00", strokeWidth = 2, style = "FILL" }
local paintWhiteText = { color = "#FFFFFFFF", textSize = 40 }

-- canvas.drawLine(x1, y1, x2, y2, paint) -> id
local lineId = canvas.drawLine(0, 0, screenWidth, screenHeight, paintRedStroke)
log("Drew a diagonal line with ID: " .. lineId)

-- canvas.drawRect(left, top, right, bottom, paint) -> id
local rectId = canvas.drawRect(100, 100, 400, 300, paintGreenFill)
log("Drew a filled rectangle with ID: " .. rectId)

-- canvas.drawText(text, x, y, paint) -> id
local textId = canvas.drawText("HuntMemory Lua API", 100, 400, paintWhiteText)
log("Drew text with ID: " .. textId)

-- canvas.drawCircle(cx, cy, radius, paint) -> id
local circlePaint = { color = "#FF00BFFF", strokeWidth = 5, style = "STROKE" }
local circleId = canvas.drawCircle(screenWidth / 2, screenHeight / 2, 150, circlePaint)
log("Drew a circle with ID: " .. circleId)

-- canvas.updatePosition(id, x, y) -> success
-- Updates the position of a drawing element.
local positionUpdated = canvas.updatePosition(textId, 100, 500)
log("Text position updated: " .. tostring(positionUpdated))

-- canvas.updateColor(id, color) -> success
-- Updates the color of a drawing element.
local colorUpdated = canvas.updateColor(rectId, "#FFFF00FF") -- Purple
log("Rectangle color updated: " .. tostring(colorUpdated))

-- canvas.hasDrawing(id) -> boolean
-- Checks if a drawing with the given ID exists.
local hasLine = canvas.hasDrawing(lineId)
log("Line exists: " .. tostring(hasLine))

-- canvas.getDrawingsCount() -> number
-- Gets the total number of drawings on the canvas.
local drawingCount = canvas.getDrawingsCount()
log("Number of drawings: " .. drawingCount)

-- canvas.remove(id)
-- Removes a specific drawing from the canvas.
sleep(4000) -- Wait 4 seconds
canvas.remove(lineId)
log("Removed the diagonal line.")
canvas.remove(rectId)
log("Removed the rectangle.")

-- getCanvasStats() -> table
-- Gets performance statistics for the canvas.
local canvasStats = getCanvasStats()
log("Canvas stats retrieved")

-- clearCanvasStats() -> string
-- Clears canvas performance statistics.
local clearStatsResult = clearCanvasStats()
log(clearStatsResult)

-- cleanupCanvas() -> string
-- Cleans up all canvas resources.
local cleanupResult = cleanupCanvas()
log(cleanupResult)


-- =================================================================================
-- 4. MEMORY API
-- Functions for game hacking. Require a process to be attached.
-- =================================================================================
log("--- 4. Memory API ---")

-- getAttachedPid() -> number | nil
-- Returns the Process ID (PID) of the attached application, or nil if none.
local pid = getAttachedPid()

if pid then
    log("Attached to process with PID: " .. pid)

    -- getModuleBase(name) -> string | nil
    -- Gets the base address of a loaded library/module in the process.
    -- Replace "libg.so" with a library from your target game.
    local moduleBase = getModuleBase("libg.so")
    if moduleBase then
        log("Found module 'libg.so' at address: 0x" .. moduleBase)
    else
        log("Module 'libg.so' not found.")
    end

    -- searchMemory(value, valueType, operator) -> table
    -- Searches memory for a value. Also aliased as `filterResults`.
    -- valueType: int, long, float, double, string, etc.
    -- operator: =, !=, >, <, >=, <=
    log("Searching for the integer value 100...")
    searchMemory("100", "int", "=")

    -- getResults(limit) -> table
    -- Retrieves results from the last search.
    local results = getResults(10) -- Get up to 10 results
    log("Found " .. #results .. " results for the value 100.")

    if #results > 0 then
        local firstResult = results[1]
        local address = firstResult.address -- Address is a string
        log("First result address: 0x" .. address)

        -- readMemory(address, valueType) -> value
        -- Reads a value from a specific memory address.
        local value = readMemory(address, "int")
        log("Value at 0x" .. address .. " is: " .. value)

        -- writeMemory(address, value, valueType) -> boolean
        -- Writes a value to a specific memory address.
        log("Writing 250 to 0x" .. address)
        local success = writeMemory(address, "250", "int")
        if success then
            log("Write successful.")
            local newValue = readMemory(address, "int")
            log("New value at 0x" .. address .. " is: " .. newValue)
        else
            log("Write failed.")
        end

        -- gotoAddress(address) -> table | nil
        -- Jumps to a memory address, useful for pointer chains.
        -- Example: "0x12345678+0x10"
        log("Using gotoAddress for the first result...")
        local gotoResult = gotoAddress(address)
        if gotoResult then
            log("gotoAddress result: address=0x" .. gotoResult.address .. ", value=" .. gotoResult.value)
        end

        -- dereferencePointer(address) -> table | nil
        -- Reads a pointer value from memory and returns the address it points to.
        -- Useful for following pointer chains in games.
        log("Dereferencing pointer at address: 0x" .. address)
        local derefResult = dereferencePointer(address)
        if derefResult then
            log("Pointer 0x" .. derefResult.pointer .. " points to address: 0x" .. derefResult.value)
        end
    end

    -- clearResults()
    -- Clears the current list of search results.
    clearResults()
    log("Memory results cleared.")

else
    log("No process attached. Skipping Memory API demonstration.")
end


-- =================================================================================
-- 5. FREEZE API (`FreezeService`)
-- Functions to repeatedly write a value to a memory address.
-- =================================================================================
log("--- 5. Freeze API ---")
if pid and #getResults(1) > 0 then
    local addressToFreeze = getResults(1)[1].address
    log("Using address 0x" .. addressToFreeze .. " for freeze demo.")

    -- startFreeze(address, value, valueType, interval_ms) -> freezeId
    -- Starts a freeze operation. Interval is optional (default 100ms).
    log("Freezing value at 0x" .. addressToFreeze .. " to 999 every 200ms.")
    local freezeId = startFreeze(addressToFreeze, "999", "int", 200)

    -- getActiveFreezes() -> table
    -- Returns a list of all active freeze operations.
    local activeFreezes = getActiveFreezes()
    log("Number of active freezes: " .. #activeFreezes)
    if #activeFreezes > 0 then
        log("Active freeze ID: " .. activeFreezes[1].id)
    end

    log("Freeze will run for 5 seconds...")
    sleep(5000)

    -- stopFreeze(freezeId) -> boolean
    -- Stops a specific freeze operation by its ID.
    local stopped = stopFreeze(freezeId)
    if stopped then
        log("Freeze operation " .. freezeId .. " stopped successfully.")
    else
        log("Failed to stop freeze " .. freezeId)
    end

    -- You can also start multiple freezes and stop them all at once.
    local freeze1 = startFreeze(addressToFreeze, "111", "int")
    local freeze2 = startFreeze(addressToFreeze, "222", "int")
    log("Started two more freezes. Will stop all in 2 seconds.")
    sleep(2000)
    -- stopAllFreezes()
    stopAllFreezes()
    log("All freeze operations have been stopped.")
else
    log("Skipping Freeze API demo (no process or address).")
end


-- =================================================================================
-- 6. THREAD MANAGEMENT API
-- Functions for creating and managing background threads.
-- =================================================================================
log("--- 6. Thread Management API ---")

-- createThread(name, function, interval, isRepeating, delay) -> threadId
-- Creates a new thread that executes the given Lua function.
local function myThreadFunction()
    log("Thread function executed at: " .. os.date())
end

local threadId = createThread("MyThread", "myThreadFunction()", 2000, true, 1000)
log("Created thread with ID: " .. threadId)

-- getActiveThreads() -> table
-- Gets information about all active threads.
local activeThreads = getActiveThreads()
log("Number of active threads: " .. #activeThreads)

-- getThreadExecutionCount(threadId) -> number
-- Gets the number of times a thread has been executed.
sleep(3000) -- Wait for thread to execute
local executionCount = getThreadExecutionCount(threadId)
log("Thread execution count: " .. executionCount)

-- pauseThread(threadId) -> boolean
-- Pauses a thread.
local paused = pauseThread(threadId)
log("Thread paused: " .. tostring(paused))

-- resumeThread(threadId) -> boolean
-- Resumes a paused thread.
sleep(2000)
local resumed = resumeThread(threadId)
log("Thread resumed: " .. tostring(resumed))

-- isThreadPaused(threadId) -> boolean
-- Checks if a thread is currently paused.
local isPaused = isThreadPaused(threadId)
log("Thread is paused: " .. tostring(isPaused))

-- getThreadStats() -> table
-- Gets detailed statistics about thread management.
local threadStats = getThreadStats()
log("Thread stats retrieved")

-- forceGarbageCollection() -> string
-- Forces garbage collection to free up memory.
local gcResult = forceGarbageCollection()
log(gcResult)

-- cleanupInactiveThreads() -> string
-- Cleans up inactive threads.
local cleanupResult = cleanupInactiveThreads()
log(cleanupResult)

-- stopThread(threadId) -> boolean
-- Stops a specific thread.
sleep(3000)
local stopped = stopThread(threadId)
log("Thread stopped: " .. tostring(stopped))

-- stopAllThreads()
-- Stops all threads.
stopAllThreads()
log("All threads stopped")


-- =================================================================================
-- 7. HTTP & DATA API
-- Functions for web requests and simple key-value data storage.
-- =================================================================================
log("--- 7. HTTP & Data API ---")

-- setData(key, value) & getData(key)
-- Store and retrieve simple string data that persists for the script's session.
setData("mySessionKey", "Hello from HuntMemory!")
local retrievedValue = getData("mySessionKey")
log("Retrieved data for 'mySessionKey': " .. retrievedValue)

-- httpGet(url) -> string | nil
-- Performs an HTTP GET request and returns the response body as a string.
log("Performing HTTP GET request...")
local responseGet = httpGet("https://jsonplaceholder.typicode.com/posts/1")
if responseGet then
    log("HTTP GET Response (first 50 chars): " .. string.sub(responseGet, 1, 50))
else
    log("HTTP GET request failed.")
end

-- httpPost(url, data, contentType) -> string | nil
-- Performs an HTTP POST request. contentType is optional.
log("Performing HTTP POST request...")
local postData = '{"title": "foo", "body": "bar", "userId": 1}'
local responsePost = httpPost("https://jsonplaceholder.typicode.com/posts", postData, "application/json; charset=utf-8")
if responsePost then
    log("HTTP POST Response: " .. responsePost)
else
    log("HTTP POST request failed.")
end

-- downloadLuaFileAndExecute(url)
-- Downloads a Lua script from a URL and executes it immediately.
-- Be cautious with this function and only use trusted URLs.
-- log("Demonstration for downloadLuaFileAndExecute is commented out for safety.")
-- downloadLuaFileAndExecute("https://example.com/myscript.lua")

-- clearData() -> string
-- Clears all stored data.
local clearDataResult = clearData()
log(clearDataResult)

log("Script finished.")
showToast("Example script has finished running.")
--- End of content ---