---
layout: default
title: Usage Examples
---

# Usage Examples

This section provides practical examples of how to use HuntMemory's features for various memory analysis tasks.

## Basic Memory Scanning

### Finding a Static Value

1. Attach to your target process using the Process Menu
2. In the Memory Menu, enter a known value (e.g., player health: "100")
3. Select the appropriate value type (e.g., "int")
4. Click "New Scan"
5. Change the value in the target app (e.g., take damage to reduce health)
6. Enter the new value in HMemory and click "Refine"
7. Repeat until you have a small number of results
8. Save interesting addresses to the Address Table

### Using the Address Table

1. After finding an address of interest, tap it to add to the Address Table
2. In the Address Table, tap on a value to edit it
3. Toggle the freeze switch to lock a value
4. Use batch operations for multiple addresses:
   - "Edit All" to change multiple values at once
   - "Freeze All" to lock multiple values
   - "Unfreeze All" to unlock multiple values

## Lua Scripting Examples

### Creating a Simple UI

```lua
-- Clear any existing menu items
clear_menu()

-- Add a title label
add_label("My Debugging")

-- Add a button that shows a toast
function onButtonClick()
    showToast("Button clicked!")
end
add_button("Click Me", onButtonClick)

-- Add a switch to toggle a feature
function onFeatureToggle(isChecked)
    if isChecked then
        showToast("Feature enabled")
    else
        showToast("Feature disabled")
    end
end
add_switch("Enable Feature", false, onFeatureToggle)

-- Add a slider to control a value
function onSliderChange(newValue)
    showToast("Slider value: " .. newValue)
end
add_slider("APP Speed", 1.0, 0.1, 5.0, 0, onSliderChange)
```

### Drawing on Screen

```lua
-- Get screen dimensions
local screenSize = getScreenSize()
local centerX = screenSize.width / 2
local centerY = screenSize.height / 2

-- Clear any existing drawings
canvas.clear()

-- Draw a red circle in the center of the screen
local circlePaint = { color = "#FFFF0000", strokeWidth = 5, style = "STROKE" }
local circleId = canvas.drawCircle(centerX, centerY, 100, circlePaint)

-- Draw text above the circle
local textPaint = { color = "#FFFFFFFF", textSize = 40 }
local textId = canvas.drawText("Hacked!", centerX - 50, centerY - 120, textPaint)

-- Later, remove the drawings
-- canvas.remove(circleId)
-- canvas.remove(textId)
```

### Memory Manipulation Script

```lua
-- Check if a process is attached
local pid = getAttachedPid()
if not pid then
    showToast("No process attached!")
    return
end

-- Search for a value
searchMemory("100", "int", "=")

-- Get the results
local results = getResults(10)
log("info", "Found " .. #results .. " results")

-- Freeze the first result to 999
if #results > 0 then
    local address = results[1].address
    local freezeId = startFreeze(address, "999", "int", 100)
    log("info", "Freezing address " .. address)
    
    -- Stop the freeze after 10 seconds
    sleep(10000)
    stopFreeze(freezeId)
    log("info", "Stopped freezing")
end
```

### Background Thread Example

```lua
-- Create a function to run in the background
function backgroundTask()
    local pid = getAttachedPid()
    if pid then
        -- Read a value every second
        local value = readMemory("1234ABCD", "int")
        log("info", "Current value: " .. tostring(value))
    end
end

-- Create a thread that runs every 1000ms (1 second)
local threadId = createThread("ValueReader", "backgroundTask()", 1000, true, 0)
log("info", "Created thread: " .. threadId)

-- Stop the thread after 30 seconds
sleep(30000)
stopThread(threadId)
log("info", "Stopped thread")
```

### HTTP Request Example

```lua
-- Make an HTTP GET request
local response = httpGet("https://api.example.com/login")
if response then
    log("info", "Response: " .. response)
    
    -- Store the response data
    setData("apiResponse", response)
else
    log("error", "Failed to get data from API")
end

-- Make an HTTP POST request
local postData = '{"auth": "user123", "password": 1234}'
local response = httpPost("https://api.example.com/db", postData)
if response then
    log("info", "POST response: " .. response)
else
    log("error", "Failed to send POST request")
end
```

## Advanced Memory Techniques

### Pointer Scanning

1. Find a value using normal scanning techniques
2. With results in the list, use "Pointer Search" with appropriate offsets
3. Analyze the pointer results to find stable addresses
4. Use "Dereference Pointers" to follow pointers to their targets

### Module Base Address Usage

```lua
-- Get the base address of a game library
local baseAddress = getModuleBase("libgame.so")
if baseAddress then
    -- Calculate an offset from the base address
    local offsetAddress = "0x" .. string.format("%X", tonumber("0x" .. baseAddress) + 0x12345)
    
    -- Read a value at the calculated address
    local value = readMemory(offsetAddress, "int")
    log("info", "Value at offset: " .. tostring(value))
else
    log("error", "Could not find module base address")
end
```

## Best Practices

1. **Start Simple**: Begin with basic value scanning before moving to advanced techniques
2. **Use Appropriate Value Types**: Select the correct data type for your searches
3. **Narrow Results Efficiently**: Change values in the target app to quickly reduce result sets
4. **Save Important Addresses**: Use the Address Table to keep track of useful addresses
5. **Freeze Sparingly**: Only freeze values when necessary to avoid detection
6. **Clean Up**: Remove drawings and stop threads when finished
7. **Test Scripts**: Always test Lua scripts in a safe environment first
8. **Monitor Performance**: Be aware that some operations may impact device performance