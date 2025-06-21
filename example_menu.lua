-- ===================================================================
-- Example Script for the Dynamic Menu API
--
-- This script demonstrates how to use the functions provided by the
-- DynamicMenuManager to build an interactive user interface
-- at runtime.
-- ===================================================================

-- Main function of our script
function main()
    -- 1. Log the script's start and clear any previous menu.
    -- It's good practice to call clear_menu() at the beginning to ensure
    -- you are starting with a clean slate.
    log("Starting dynamic menu script...")
    clear_menu()
    log("Previous menu cleared.")

    -- 2. Define the callback functions that will be used by the UI components.
    -- These functions are called by the system (Kotlin) when the user interacts
    -- with the components.

    -- Callback for the greeting button's click
    function handleGreetingClick()
        log("The greeting button was clicked! Hello from Lua!")
    end

    -- Callback for the 'God Mode' switch
    -- The function receives the new state of the switch (true for on, false for off)
    function handleGodModeToggle(isChecked)
        if isChecked then
            log("GOD MODE ACTIVATED. (Here you would enable the cheat)")
            -- Example: writeMemory(god_mode_address, "1", "byte")
        else
            log("GOD MODE DEACTIVATED. (Here you would disable the cheat)")
            -- Example: writeMemory(god_mode_address, "0", "byte")
        end
    end

    -- Callback for the speed slider's value change
    -- The function receives the new numeric value from the slider.
    function handleSpeedChange(newValue)
        -- We use string.format to display the value nicely.
        log("Game speed changed to: " .. string.format("%.2f", newValue))
        -- Example: writeMemory(game_speed_address, tostring(newValue), "float")
    end


    -- 3. Add components to the menu and store their IDs.
    -- The component creation functions return a unique ID (string).
    -- It's important to save these IDs if you plan to modify the components later.
    log("Creating menu components...")

    local titleId = add_label("Lua Cheat Menu")
    local greetingButtonId = add_button("Say Hello", handleGreetingClick)

    add_switch("Enable God Mode", false, handleGodModeToggle)

    local speedSliderId = add_slider("Game Speed", 1.0, 0.5, 3.0, 0, handleSpeedChange)
    -- Arguments for add_slider:
    -- 1. "Game Speed"    - Label
    -- 2. 1.0             - Initial value
    -- 3. 0.5             - Minimum value
    -- 4. 3.0             - Maximum value
    -- 5. 0               - Steps. 0 means continuous.
    -- 6. handleSpeedChange - Callback function

    log("Initial components created. IDs saved for later use.")


    -- 4. Demonstrate dynamic UI modification.
    -- Let's add a button that, when clicked, modifies other components.

    function handleDynamicUpdate()
        log("Update button clicked. Modifying the UI...")

        -- Change the title's text using its saved ID
        update_text(titleId, "Menu Updated Dynamically!")
        log("Title text has been changed.")

        -- Change the speed slider's value programmatically
        update_value(speedSliderId, 2.5)
        log("Speed slider value has been set to 2.5.")

        -- Remove an item from the menu, like the greeting button
        remove_item(greetingButtonId)
        log("The 'Say Hello' button has been removed.")

        -- Add a new item
        add_label("The menu has been modified!")
    end

    -- Add the button that triggers the update function above
    add_button("Update Menu & Items", handleDynamicUpdate)


    -- 5. End of menu setup.
    log("Menu creation script finished successfully.")
    return "Dynamic menu has been created and is ready for interaction."
end

-- Call the main function to run the script.
-- The Kotlin API will capture the return value.
return main()