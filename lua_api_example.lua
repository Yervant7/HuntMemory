-- This script demonstrates the various API functions available for creating dynamic menus,
-- drawing on the screen, and interacting with memory.

-- Log a message to the device's logcat for debugging.
log("HMemory Lua API example script started.")

-- Show a toast message on the screen.
showToast("Welcome to the Lua API Example!")

-- =================================================================================
-- Utility Functions
-- =================================================================================

-- Get the screen dimensions.
local screenSize = getScreenSize()
local screenWidth = screenSize.width
local screenHeight = screenSize.height
log("Screen size: " .. screenWidth .. "x" .. screenHeight)

-- =_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=
-- Dynamic Menu API (`DynamicMenuManager`)
--
-- Create interactive UI elements like buttons, switches, and sliders.
-- =_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=_=

-- Clear any existing menu items to start fresh.
clear_menu()
log("Menu cleared.")

-- Add a descriptive label to the menu.
add_label("--- Canvas Controls ---")

-- Variable to hold the ID of a drawing we want to control.
local circleId = nil

-- Function to be called when the switch is toggled.
local function onToggleCircle(isChecked)
    if isChecked then
        showToast("Circle is now visible.")
        log("Drawing circle.")
        -- Define the paint properties for the circle.
        local paint = {
            color = "#FF5733", -- Orange color
            strokeWidth = 5,
            style = "STROKE" -- Can be "STROKE" or "FILL"
        }
        -- Draw the circle at the center of the screen and store its ID.
        circleId = canvas.drawCircle(screenWidth / 2, screenHeight / 2, 100, paint)
    else
        showToast("Circle is now hidden.")
        log("Removing circle.")
        -- If the circle has been drawn (ID exists), remove it.
        if circleId then
            canvas.remove(circleId)
            circleId = nil -- Reset the ID
        end
    end
end

-- Add a switch to control the visibility of a circle.
-- add_switch(label, initialValue, onToggleCallback)
local switchId = add_switch("Show Circle", false, onToggleCircle)
log("Added switch with ID: " .. switchId)


-- Add a slider to control the text size of a label.
add_label("--- Other Controls ---")
local labelId = add_label("This is a dynamic label")

local function onSliderChange(newValue)
    -- The paint object for text requires 'textSize'.
    local paint = {
        color = "#FFFFFF",
        textSize = newValue
    }
    -- We can't directly update a label's text size, but we can demonstrate
    -- the slider's value by updating a text drawing on the canvas.
    -- First, remove the old text to avoid clutter.
    if textId then canvas.remove(textId) end
    -- Draw new text with the updated size.
    textId = canvas.drawText("Size: " .. string.format("%.0f", newValue), 100, screenHeight - 100, paint)
end

-- add_slider(label, initialValue, minValue, maxValue, steps, onValueChangeCallback)
local sliderId = add_slider("Text Size", 32, 16, 128, 0, onSliderChange)
log("Added slider with ID: " .. sliderId)


-- Add a button that shows a toast when clicked.
local function onButtonClick()
    showToast("Button clicked!")
    -- We can also interact with other menu items, e.g., update the label's text.
    update_text(labelId, "Button was clicked at: " .. os.date())
end

-- add_button(label, onClickCallback)
local buttonId = add_button("Click Me", onButtonClick)
log("Added button with ID: " .. buttonId)


-- =================================================================================
-- Canvas API (`CanvasManager`)
--
-- Draw shapes and text directly on the screen overlay.
-- Each drawing function returns an ID that can be used to remove the drawing later.
-- =================================================================================

-- Clear the canvas to ensure no old drawings are present.
canvas.clear()
log("Canvas cleared.")

-- Define a reusable 'paint' object for styling drawings.
local paintRed = { color = "#FF0000", strokeWidth = 3 }
local paintGreen = { color = "#00FF00", strokeWidth = 2, style = "FILL" }
local paintWhiteText = { color = "#FFFFFF", textSize = 40 }

-- Draw a line from top-left to bottom-right.
canvas.drawLine(0, 0, screenWidth, screenHeight, paintRed)
log("Drew a diagonal line.")

-- Draw a filled rectangle.
canvas.drawRect(100, 100, 400, 300, paintGreen)
log("Drew a filled rectangle.")

-- Draw some text.
canvas.drawText("HMemory Lua API", 100, 400, paintWhiteText)
log("Drew text.")

-- This textId is used by the slider callback to update the text size.
textId = canvas.drawText("Size: 32", 100, screenHeight - 100, { color = "#FFFFFF", textSize = 32 })


-- =================================================================================
-- Memory & Process API (`LuaAPI`)
--
-- Functions for game hacking: searching, reading, and writing memory.
-- NOTE: These functions require a game process to be attached first.
-- =================================================================================

log("--- Memory Functions ---")
local pid = getAttachedPid()

if pid then
    log("Attached to process with PID: " .. pid)

    -- Example: Search for the integer value 100 in memory.
    -- searchMemory(value, valueType, operator)
    -- valueType can be: int, long, float, double, string, etc.
    -- operator can be: =, !=, >, <, >=, <=
    log("Searching for the value 100 as an integer...")
    searchMemory("100", "int", "=")

    -- Get the results from the last search.
    -- getResults(limit)
    local results = getResults(10) -- Get the first 10 results

    if #results > 0 then
        log("Found " .. #results .. " results.")
        local firstAddress = results[1].address
        log("First result address: 0x" .. firstAddress)

        -- Read the value from the found address.
        -- readMemory(address, valueType)
        local value = readMemory(firstAddress, "int")
        log("Value at 0x" .. firstAddress .. " is: " .. value)

        -- Write a new value to the address.
        -- writeMemory(address, value, valueType)
        log("Writing 250 to 0x" .. firstAddress)
        writeMemory(firstAddress, "250", "int")

        -- Verify the new value.
        value = readMemory(firstAddress, "int")
        log("New value at 0x" .. firstAddress .. " is: " .. value)

        -- Start freezing the value at 250.
        -- startFreeze(address, value, valueType, interval_ms)
        log("Freezing value at 250.")
        local freezeId = startFreeze(firstAddress, "250", "int", 200)

        -- Wait for 5 seconds.
        sleep(5000)

        -- Stop the freeze.
        stopFreeze(freezeId)
        log("Freeze stopped.")

    else
        log("Value not found in memory.")
    end

    -- Clear results for the next search.
    clearResults()
else
    log("No process attached. Skipping memory operations.")
end


-- =================================================================================
-- HTTP & Data API (`LuaAPI`)
-- =================================================================================

log("--- HTTP and Data Functions ---")

-- Store a simple key-value pair.
setData("myKey", "myValue")
local retrievedValue = getData("myKey")
log("Retrieved data from key 'myKey': " .. retrievedValue)

-- Perform an HTTP GET request to a public API.
log("Performing HTTP GET request...")
local response = httpGet("https://jsonplaceholder.typicode.com/posts/1")
if response then
    log("HTTP GET Response received (first 50 chars): " .. string.sub(response, 1, 50))
else
    log("HTTP GET request failed.")
end

log("Script finished.")
