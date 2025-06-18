-- ===================================================================
-- Example Script for the Memory API and FreezeService
-- ===================================================================

-- Main function of our script
function main()
    -- 1. Log the start of the script. The 'log' function prints to Android's Logcat.
    log("Starting example script...")

    -- 2. Check if we are attached to a process.
    local pid = getAttachedPid()
    if not pid then
        log("Error: No process attached. The script cannot continue.")
        return "Failure: No process attached."
    end
    log("Attached to process with PID: " .. pid)

    -- 3. Search for a specific value in memory.
    -- Let's search for an integer (int) value of 100.
    -- Imagine 100 is the player's current health.
    local value_to_find = "100"
    local value_type = "int"
    log("Searching for value '" .. value_to_find .. "' of type '" .. value_type .. "'...")

    local results = searchMemory(value_to_find, value_type)

    -- 4. Check if we found any results.
    if not results or #results == 0 then
        log("No addresses found with the value " .. value_to_find .. ".")
        return "Failure: Value not found."
    end

    log("Found " .. #results .. " addresses.")

    -- 5. Take the first result from the list to work with.
    local first_result = results[1]
    local target_address = first_result.address
    log("Using the first address found: 0x" .. target_address)

    -- 6. Modify the value at that address to 9999 (e.g., max health).
    local new_value = "9999"
    log("Attempting to write value '" .. new_value .. "' to address 0x" .. target_address)
    local success = writeMemory(target_address, new_value, value_type)

    if success then
        log("Value modified successfully!")
    else
        log("Failed to modify the value.")
        return "Failure: Could not write to memory."
    end

    -- 7. Now, let's freeze this value so it doesn't change.
    -- The freeze will rewrite 9999 every 200 milliseconds.
    local interval_ms = 200
    log("Starting to freeze the value at " .. new_value .. " at address 0x" .. target_address .. " every " .. interval_ms .. "ms.")

    -- The startFreeze function returns a freezeId that we can use to stop it later.
    local freeze_id = startFreeze(target_address, new_value, value_type, interval_ms)

    if string.find(freeze_id, "Error:") then
        log("Error starting freeze: " .. freeze_id)
        return "Failure: Error during freeze."
    else
        log("Freeze started successfully! ID: " .. freeze_id)
        -- We can save the ID for later use with the data API
        setData("myFreezeID", freeze_id)
    end

    -- 8. The freeze is now running in the background on the Kotlin side.
    -- The script can continue or finish. For demonstration, let's list the active freezes.
    local active_freezes = getActiveFreezes()
    log("Number of active freezes: " .. #active_freezes)
    for i, freeze in ipairs(active_freezes) do
        log("Active #" .. i .. ": ID=" .. freeze.id .. ", Address=" .. freeze.address)
    end

    -- 9. Demonstration of how to stop the freeze.
    -- Imagine the user clicked a "Deactivate Cheat" button.
    -- We'll retrieve the ID we saved.
    log("The script will now stop the freeze (simulating user action)...")
    local id_to_stop = getData("myFreezeID")

    if id_to_stop then
        local stopped_successfully = stopFreeze(id_to_stop)
        if stopped_successfully then
            log("Freeze " .. id_to_stop .. " stopped successfully.")
        else
            log("Failed to stop freeze " .. id_to_stop .. ". Maybe it already stopped.")
        end
    else
        log("Could not find a saved freeze ID to stop.")
    end

    -- Alternatively, you could stop ALL freezes at once:
    -- log("Stopping all active freezes...")
    -- stopAllFreezes()
    -- log("All freezes have been stopped.")

    log("Example script finished successfully.")
    return "Execution completed."
end

-- Call the main function to run the script.
-- The Kotlin API will capture the return value.
return main()