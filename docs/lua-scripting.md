# Lua Scripting Engine & API Reference

HuntMemory integrates a high-performance **Lua 5.4 scripting engine** (powered by `mlua` in Rust) allowing automation of memory scans, real-time memory inspection and editing, pointer resolution, dynamic Jetpack Compose overlay menus, and on-screen **2D Canvas telemetry rendering**.

---

## 🏛️ 1. Scripting Engine Architecture

```mermaid
graph TD
    subgraph Userspace_UI ["App Process (Overlay UI)"]
        UI_Tab["LuaScriptTab (Compose Code Editor & Console)"]
        Canvas_UI["LuaCanvasOverlay (Hardware Accelerated Compose Canvas)"]
        Bridge["LuaUiBridge (StateFlow / Channel Dispatcher)"]
    end

    subgraph Root_Service ["Root Process (libsu UID 0)"]
        AIDL["IHMemService AIDL IPC"]
        JNI["NativeBridge.runScript(...)"]
        subgraph Rust_Mlua ["Rust Engine (script.rs)"]
            MLUA["mlua (Lua 5.4 Runtime & Sandboxed Environment)"]
            HMEM_API["hmem.* Native API Registry"]
            CANVAS_API["canvas.* 2D Overlay API"]
            CB["ScriptUiCallback Trait Dispatcher"]
        end
    end

    UI_Tab -- "Execute Script / Input" --> AIDL
    AIDL --> JNI
    JNI --> MLUA
    MLUA --> HMEM_API
    MLUA --> CANVAS_API
    HMEM_API -- "Dialogs / Menus / Canvas Binary" --> CB
    CANVAS_API -- "Canvas Binary Packets" --> CB
    CB -- "IPC Return / Callbacks" --> Bridge
    Bridge --> Canvas_UI
    Bridge --> UI_Tab
```

### Execution Characteristics
- **Sandboxed Execution**: Scripts run in an isolated Lua 5.4 instance per execution request.
- **Console Capture**: `print(...)` statements are redirected to both the in-app interactive console and Android Logcat (`[LuaScript]`).
- **Asynchronous UI Callbacks**: Calls to `hmem.alert`, `hmem.prompt`, `hmem.choice`, and `hmem.create_menu` safely pause execution and wait for user response without blocking the Android UI thread.

---

## 🔍 2. `hmem.*` Native API Reference

### Process Control & Utilities

| Function | Parameters | Returns | Description |
| :--- | :--- | :--- | :--- |
| `hmem.get_pid()` | *none* | `number` | Returns current attached process PID. |
| `hmem.set_pid(pid)` | `pid: number` | `nil` | Changes target PID for subsequent operations. |
| `hmem.log(message)` | `msg: string` | `nil` | Outputs a string to the in-app console & logcat. |
| `hmem.sleep(millis)` | `ms: number` | `nil` | Suspends script execution for specified milliseconds. |

---

### Direct Primitive Memory Access

Read and write memory directly using HMKPM direct kernel page table access:

```lua
-- Primitive Read Functions
local byte_val   = hmem.read_byte(0x7F001234)    -- i8
local short_val  = hmem.read_short(0x7F001234)   -- i16
local int_val    = hmem.read_int(0x7F001234)     -- i32
local long_val   = hmem.read_long(0x7F001234)    -- i64
local float_val  = hmem.read_float(0x7F001234)   -- f32
local double_val = hmem.read_double(0x7F001234)  -- f64
local f16_val    = hmem.read_float16(0x7F001234) -- f16 (returns f32)
local bytes_tbl  = hmem.read_bytes(0x7F001234, 16) -- table of bytes

-- Primitive Write Functions
hmem.write_byte(0x7F001234, 255)
hmem.write_short(0x7F001234, 32000)
hmem.write_int(0x7F001234, 999999)
hmem.write_long(0x7F001234, 1234567890123)
hmem.write_float(0x7F001234, 100.5)
hmem.write_double(0x7F001234, 99999.999)
hmem.write_float16(0x7F001234, 12.5)
hmem.write_bytes(0x7F001234, { 0x90, 0x90, 0x00, 0x00 }) -- or hex string "90 90 00 00"

-- Generic Read/Write
local val_str = hmem.read(0x7F001234, "int")     -- Returns string or nil
hmem.write(0x7F001234, "500", "int")

-- Atomic Batch Write
local count = hmem.batch_write({
    { address = 0x7F001000, value = "100", type = "int" },
    { address = 0x7F002000, value = "99.5", type = "float" }
})
```

---

### SIMD-Accelerated Memory Scanning

```lua
-- Search Exact Values
local res = hmem.search("session_1", "1000", "int", "=")
print("Found " .. res.count .. " matches")

-- Range Search
local res_range = hmem.search_range("session_1", "50", "100", "float")

-- Group Search (Homogeneous & Heterogeneous)
local res_group = hmem.search_group("session_1", "100;200;300:16", "int")

-- Array of Bytes (AoB) / Signature Search with Wildcards
local aob_res = hmem.search_pattern("session_1", "00 20 40 ? ? 90 00")
print("Found " .. aob_res.count .. " AoB matches")
for _, item in ipairs(aob_res.matches) do
    print(string.format("AoB Match: 0x%X", item.address))
end

-- XOR-Keypair Obscured Search
local res_obs = hmem.search_obscured("session_1", "999", "obscured_int")

-- BigDouble Scientific Search
local res_big = hmem.search_big_double("session_1", "1.5e12")

-- Refine Existing Results
local refined = hmem.refine("session_1", "1200", "=")
local refined_range = hmem.refine_range("session_1", "1100", "1300")

-- Fetch Results & Clear
local results = hmem.get_results("session_1", 50)
for i, item in ipairs(results.matches) do
    print(string.format("[%d] 0x%X = %s (%s)", i, item.address, item.value, item.value_type))
end

hmem.clear_session("session_1")
```

---

### Native Memory Freeze Engine Control

```lua
-- Add address to freeze loop
hmem.freeze_add(0x7F001234, "9999", "int", "Sample Counter")

-- Check active freeze status
local is_active = hmem.freeze_is_running()
local freeze_entries = hmem.freeze_list()
for _, entry in ipairs(freeze_entries) do
    print(string.format("Locked 0x%X to %s (%s)", entry.address, entry.value, entry.value_type))
end

-- Configure tick interval (in milliseconds)
hmem.freeze_set_interval(50) -- 50ms tick rate
local interval = hmem.freeze_get_interval()

-- Start / Stop / Remove / Clear
hmem.freeze_remove(0x7F001234)
hmem.freeze_clear()
hmem.freeze_stop()
hmem.freeze_start()
```

---

### Pointer Resolution & Multi-Level Offsets

```lua
-- Resolve module base address
local libtarget = hmem.get_module_base("libtarget.so")
print(string.format("libtarget.so Base: 0x%X", libtarget))

-- Resolve pointer chain: [[libtarget + 0x1A0] + 0x28] + 0x10
local target_addr = hmem.resolve_pointer(libtarget + 0x1A0, { 0x28, 0x10 })
if target_addr then
    print(string.format("Target Address: 0x%X", target_addr))
    local current_val = hmem.read_pointer(libtarget + 0x1A0, { 0x28, 0x10 }, "int")
    hmem.write_pointer(libtarget + 0x1A0, { 0x28, 0x10 }, "9999", "int")
end
```

---

### Obscured & XOR Keypair Helpers

```lua
-- Read XOR Value with Known Key
local val = hmem.read_xor(0x7F001000, 0x12345678, "int")
hmem.write_xor(0x7F001000, "5000", 0x12345678, "int")

-- Encode / Decode XOR-Keypair Obscured Types
local encoded = hmem.encode_obscured("12345", "obscured_int", 0xAABBCCDD)
print("Key: " .. encoded.key .. ", Hidden: " .. encoded.hidden)

local decoded = hmem.decode_obscured(encoded.key, encoded.hidden, "obscured_int")
print("Decoded: " .. decoded)

-- Hex and Base64 Conversion Utilities
local hex_str = hmem.hex_encode({ 0xDE, 0xAD, 0xBE, 0xEF }) -- "DEADBEEF"
local raw_bytes = hmem.hex_decode("DEADBEEF")
local b64_str = hmem.b64_encode({ 72, 101, 108, 108, 111 })
local b64_bytes = hmem.b64_decode(b64_str)
```

---

## 🎨 3. Dynamic Compose Overlay UI & Dialogs

Scripts can render interactive dialogs, toasts, prompt inputs, and dynamic menus directly within HuntMemory's Material 3 overlay:

### Interactive Dialogs

```lua
-- Toast Notification
hmem.toast("Scan completed successfully!")

-- Alert Dialog (Pauses execution until dismissed)
hmem.alert("Operation completed!", "Sample Alert")

-- Text / Numeric Prompt
local user_input = hmem.prompt("Enter new counter value:", "999999", "number")

-- Single Choice Dialog
local choice_idx = hmem.choice("Select Profile:", { "Profile A", "Profile B", "Profile C" })
if choice_idx then
    print("User selected index: " .. choice_idx)
end

-- Multi-Choice Dialog
local selected = hmem.multi_choice("Enable Options:", { "Option A", "Option B", "Option C" }, { true, false, true })
if selected then
    print("Option A: " .. tostring(selected[1]))
    print("Option B: " .. tostring(selected[2]))
    print("Option C: " .. tostring(selected[3]))
end
```

### Dynamic Floating Overlay Menus & Event Loop

Create fully customizable floating menus in the Compose overlay tab and respond to user clicks and toggle state changes:

```lua
hmem.create_menu("Diagnostic Control Hub", {
    { type = "header", label = "RUNTIME CONTROLS" },
    { type = "button", id = "btn_reset", label = "⚡ Reset Counter (100%)", color = "#4CAF50" },
    { type = "toggle", id = "tog_freeze", label = "❄️ Freeze Value", checked = true },
    { type = "button", id = "btn_preset", label = "🔧 Set Preset Value", color = "#FFC107" },
    { type = "divider" },
    { type = "button", id = "btn_exit", label = "❌ Close Menu", color = "#F44336" }
})

-- Interactive Event Listener Loop
while true do
    -- Wait for UI event (or timeout after 5000ms)
    local evt = hmem.wait_event(5000)
    if evt then
        if evt.id == "btn_reset" then
            hmem.toast("Counter reset!")
        elseif evt.id == "tog_freeze" then
            hmem.toast("Freeze toggle state: " .. tostring(evt.checked))
        elseif evt.id == "btn_exit" then
            hmem.clear_menu()
            break
        end
    end
end
```

---

## 🖥️ 4. Real-Time 2D Canvas Overlay

HuntMemory provides a high-performance hardware-accelerated **Canvas Overlay** subsystem (`hmem.canvas` and global `canvas`) that renders directly over running foreground applications without window flickering.

```mermaid
graph LR
    LUA["Lua Script (canvas.batch_draw)"] --> BIN["Compact Binary Buffer"]
    BIN --> JNI["JNI (canvasDrawBinary)"]
    JNI --> BRIDGE["LuaUiBridge"]
    BRIDGE --> DRAW["Compose Draw Phase (drawBehind)"]
    DRAW --> GPU["SurfaceFlinger / Hardware Canvas"]
```

### Canvas API Reference

| Function | Parameters | Description |
| :--- | :--- | :--- |
| `canvas.clear()` | *none* | Clears all active canvas drawing commands. |
| `canvas.set_visible(bool)` | `visible: boolean` | Toggles canvas visibility. |
| `canvas.show()` / `canvas.hide()` | *none* | Shows or hides the canvas overlay. |
| `canvas.is_visible()` | *none* | Returns `true` if canvas is currently visible. |
| `canvas.get_screen_size()` | *none* | Returns `{ width = w, height = h }` in pixels. |
| `canvas.get_width()` | *none* | Returns screen width in pixels. |
| `canvas.get_height()` | *none* | Returns screen height in pixels. |
| `canvas.draw_text(...)` | `text, x, y, [size], [color], [align]` | Draws text on screen. `align` can be `"left"`, `"center"`, or `"right"`. |
| `canvas.draw_line(...)` | `x1, y1, x2, y2, [stroke], [color]` | Draws a 2D line. |
| `canvas.draw_rect(...)` | `x, y, w, h, [stroke], [color], [filled]` | Draws an outlined or filled bounding rectangle. |
| `canvas.draw_circle(...)` | `cx, cy, radius, [stroke], [color], [filled]` | Draws an outlined or filled circle. |
| `canvas.batch_draw(...)` | `commands_table` | Sends multiple drawing commands in a single atomic frame. |

### Color Formats
Colors accept:
- Hex strings: `"#FF0000"`, `"#80FF0000"` (with alpha channel)
- Integers: `0xFFFF0000`
- Tables: `{ r = 255, g = 0, b = 0, a = 255 }` or `{ 255, 0, 0, 255 }`

---

## 💡 5. Practical Script Examples

### Example 1: Full Telemetry / 2D Canvas Overlay Loop

```lua
local screen = canvas.get_screen_size()
local cx = screen.width / 2
local cy = screen.height / 2

canvas.show()
canvas.clear()

-- Draw alignment crosshair and radial telemetry grid
canvas.batch_draw({
    -- Center Crosshair
    { type = "line", x1 = cx - 20, y1 = cy, x2 = cx + 20, y2 = cy, stroke = 2.0, color = "#00FF00" },
    { type = "line", x1 = cx, y1 = cy - 20, x2 = cx, y2 = cy + 20, stroke = 2.0, color = "#00FF00" },
    
    -- Radial Grid Circle
    { type = "circle", cx = 150, cy = 150, radius = 100, stroke = 2.0, color = "#40FFFFFF", filled = false },
    { type = "circle", cx = 150, cy = 150, radius = 4, stroke = 0, color = "#00FF00", filled = true },
    
    -- Simulated Bounding Box Example
    { type = "rect", x = cx - 50, y = cy - 100, width = 100, height = 200, stroke = 3.0, color = "#FF0000", filled = false },
    { type = "text", text = "Object [100m]", x = cx, y = cy - 110, size = 16.0, color = "#FFFF00", align = "center" }
})
```

### Example 2: Multi-Level Pointer Resolution & Value Update

```lua
local base = hmem.get_module_base("libnative.so")
if not base or base == 0 then
    hmem.alert("libnative.so not found in process maps!", "Error")
    return
end

local counter_addr = hmem.resolve_pointer(base + 0x4D2F00, { 0x58, 0x10, 0x24 })
if counter_addr and counter_addr ~= 0 then
    hmem.toast(string.format("Address resolved at: 0x%X", counter_addr))
    
    -- Update target counter
    hmem.write_int(counter_addr, 9999)
    hmem.alert("Counter set to 9999!", "Success")
else
    hmem.toast("Failed to resolve pointer chain.")
end
```
