-- === Enemy-Only ESP with Dynamic FOV + Toggles + Full Player Count ===
local paintWhiteText = { color = "#FFFF0000", textSize = 40 }
local textId = canvas.drawText("Discord:@TraagicJunior", 40, 100, paintWhiteText)
local textId = canvas.drawText("Free beta for customer paid = scam", 40, 300, paintWhiteText)
local offsets = {
  GWorld             = 0x1234567,
  GameState          = 0x1324,
  GameInstance       = 0x1356,
  LocalPlayers       = 0x38,
  PlayerController   = 0x30,
  PlayerCameraMgr    = 0x3,
  CameraRot          = 0x1,
  CameraPosPtr       = 0x1,
  CameraFOVOffset    = 0x1,
  PlayerStateArray   = 0x2,
  PlayerStateCount   = 0x1,
  PlayerStateToPawn  = 0x3,
  PawnRootComponent  = 0x1,
  RootComponentToPos = 0x2
}

local CAPSULE_HALF_HEIGHT = 90.0 --find your own games via sdk or can do manual tests this helps determine box height without calculating headpos but i have implimented head pos logic already
local RENDER_SLEEP_MS = 5
local BATCH_PER_FRAME = 100 -- max players to calculate each frame can reduce if the esp is laggy

-- Menu Toggles
clear_menu()
local SHOW_BOX, SHOW_LINE, SHOW_DIST, SHOW_COUNT = true, true, true, true
add_switch("ESP: Show Boxes", SHOW_BOX, function(v) SHOW_BOX = v end)
add_switch("ESP: Show Lines", SHOW_LINE, function(v) SHOW_LINE = v end)
add_switch("ESP: Show Distance", SHOW_DIST, function(v) SHOW_DIST = v end)
add_switch("ESP: Show Player Count", SHOW_COUNT, function(v) SHOW_COUNT = v end)

local scr = getScreenSize()
local SW, SH = scr.width, scr.height
local CX, CY = SW * 0.5, SH * 0.5

local function to_hex(n) return string.format("0x%X", n) end
local function deref_long_at(a)
  local r = gotoAddress(to_hex(a), "long")
  return r and tonumber(r.value)
end
local function read_num_at(a, t)
  local r = gotoAddress(to_hex(a), t)
  return r and tonumber(r.value)
end
local function readFVector(addr) --type is double in ue5 and the struct is x+0x8 = y and x+0x16 =z 
  local x = read_num_at(addr, "float") 
  local y = read_num_at(addr + 4, "float")
  local z = read_num_at(addr + 8, "float")
  return x and y and z and {x=x, y=y, z=z}
end
local function readFRotator(addr) --type is double in ue5 and the struct is x+0x8 = y and x+0x16 =z
  local pitch = read_num_at(addr, "float")
  local yaw   = read_num_at(addr + 4, "float")
  local roll  = read_num_at(addr + 8, "float")
  return pitch and yaw and roll and {pitch=pitch, yaw=yaw, roll=roll}
end

-- Not-Me (Enemy) Check can replace with team id checks
local function not_me_check(ps)
  local pawn = deref_long_at(ps + offsets.PlayerStateToPawn)
  if not pawn then return false end
  local flag = deref_long_at(pawn + 0x164) --0x164 is a unique value that doesnt exist in my pawn but exists in all others you might need to find it for ur game
  return flag == 65793 --this value may change for each game
end

local baseStr = getModuleBase("libUnreal.so") or getModuleBase("libUE4.so")
local base = tonumber(tostring(baseStr):gsub("^0[xX]", ""), 16)
local gworldPtr = deref_long_at(base + offsets.GWorld)

local CAM_POS_ADDR, CAM_ROT_ADDR, CAM_FOV_ADDR, PS_ARRAY_ADDR, PS_COUNT = nil, nil, nil, nil, 0

local function refresh_addresses()
  local posPtr = deref_long_at(gworldPtr + offsets.CameraPosPtr)
  CAM_POS_ADDR = posPtr

  local gi = deref_long_at(gworldPtr + offsets.GameInstance)
  local lp = gi and deref_long_at(gi + offsets.LocalPlayers)
  local flp = lp and deref_long_at(lp)
  local pc = flp and deref_long_at(flp + offsets.PlayerController)
  if not pc then return end

  CAM_ROT_ADDR = pc + offsets.CameraRot
  local camMgr = deref_long_at(pc + offsets.PlayerCameraMgr)
  CAM_FOV_ADDR = camMgr and (camMgr + offsets.CameraFOVOffset)

  local gs = deref_long_at(gworldPtr + offsets.GameState)
  PS_ARRAY_ADDR = gs and deref_long_at(gs + offsets.PlayerStateArray)
  PS_COUNT = gs and read_num_at(gs + offsets.PlayerStateCount, "int") or 0
end

local function fetch_camera()
  local pos = readFVector(CAM_POS_ADDR)
  local rot = readFRotator(CAM_ROT_ADDR)
  local fov = read_num_at(CAM_FOV_ADDR, "float") or 80.0
  return pos, rot, fov
end

local players = {}
local function drop(ps)
  local o = players[ps]
  if o and o.ids then for _,id in pairs(o.ids) do pcall(canvas.remove, id) end end
  players[ps] = nil
end

local function build(ps)
  local pawn = deref_long_at(ps + offsets.PlayerStateToPawn)
  local root = pawn and deref_long_at(pawn + offsets.PawnRootComponent)
  return root and {
    ps = ps,
    pawn = pawn,
    root = root,
    posAddr = root + offsets.RootComponentToPos,
    ids = {},
    label = ""
  } or nil
end

local function scan_players()
  for i = 0, math.min(PS_COUNT-1, BATCH_PER_FRAME) do
    local ps = deref_long_at(PS_ARRAY_ADDR + i*8)
    if ps and not players[ps] then
      local o = build(ps)
      if o then players[ps] = o end
    end
  end
end


local fillPaint       = { color = "#5000FF00", style = "FILL" }
local boxPaint        = { color = "#FFFF0000", strokeWidth = 3, style = "STROKE" }
local textPaint       = { color = "#FFFFFFFF", textSize = 27 }
local countPaint      = { color = "#FFFFFF00", textSize = 40 }
local textBGPaint     = { color = "#80000000", style = "FILL" }
local linePaint       = { color = "#FFFFFFAA", strokeWidth = 2, style = "STROKE" }

local function worldToScreen(camPos, camRot, worldPos, fov)
  local dx, dy, dz = worldPos.x - camPos.x, worldPos.y - camPos.y, worldPos.z - camPos.z
  local radPitch, radYaw = math.rad(camRot.pitch), math.rad(camRot.yaw)
  local cp, sp = math.cos(radPitch), math.sin(radPitch)
  local cy, sy = math.cos(radYaw), math.sin(radYaw)
  local x = dy * cy - dx * sy
  local y = dz * cp - dx * cy * sp - dy * sy * sp
  local z = dz * sp + dx * cy * cp + dy * sy * cp
  if z < 1.0 then return nil end
  local fovRad = math.rad(fov)
  local screenX = CX + x * (CX / math.tan(fovRad * 0.5)) / z
  local screenY = CY - y * (CX / math.tan(fovRad * 0.5)) / z
  return { x = screenX, y = screenY, z = z }
end

local function update_or_create_rect(id, x1, y1, x2, y2, paint)
  if id then pcall(canvas.remove, id) end
  return canvas.drawRect(x1, y1, x2, y2, paint)
end
local function update_or_create_text(id, old_text, new_text, x, y, paint)
  if id and old_text == new_text then return id, old_text end
  if id then pcall(canvas.remove, id) end
  return canvas.drawText(new_text, x, y, paint), new_text
end
local function update_or_create_line(id, x1, y1, x2, y2, paint)
  if id then pcall(canvas.remove, id) end
  return canvas.drawLine(x1, y1, x2, y2, paint)
end

local function render_player(ps, camPos, camRot, fov)
  local o = players[ps]; if not o then return false end
  local pos = readFVector(o.posAddr); if not pos then return drop(ps) end

  local top = worldToScreen(camPos, camRot, {x=pos.x, y=pos.y, z=pos.z + CAPSULE_HALF_HEIGHT}, fov)
  local bot = worldToScreen(camPos, camRot, {x=pos.x, y=pos.y, z=pos.z - CAPSULE_HALF_HEIGHT}, fov)

  local onscreen = (top and bot)

  if onscreen then
    local h = math.abs(bot.y - top.y)
    local w = h * 0.4
    local cx = (top.x + bot.x) * 0.5
    local lx, rx = cx - w/2, cx + w/2
    local ty, by = top.y, bot.y
    local label = math.floor(math.sqrt((camPos.x - pos.x)^2 + (camPos.y - pos.y)^2 + (camPos.z - pos.z)^2) / 100 + 0.5).."m"

    local ids = o.ids

    if SHOW_BOX then
      ids.box = update_or_create_rect(ids.box, lx, ty, rx, by, boxPaint)
  --    ids.fill = update_or_create_rect(ids.fill, lx, ty, rx, by, fillPaint)
    else
      if ids.box then canvas.remove(ids.box); ids.box = nil end
   --   if ids.fill then canvas.remove(ids.fill); ids.fill = nil end
    end

    if SHOW_LINE then
      ids.line = update_or_create_line(ids.line, CX, SH * 0, cx, ty, linePaint)
    elseif ids.line then canvas.remove(ids.line); ids.line = nil end

    if SHOW_DIST then
      ids.distBG = update_or_create_rect(ids.distBG, lx, by+10, rx, by+38, textBGPaint)
      ids.text, o.label = update_or_create_text(ids.text, o.label, label, lx + 10, by + 14, textPaint)
    else
      if ids.distBG then canvas.remove(ids.distBG); ids.distBG = nil end
      if ids.text then canvas.remove(ids.text); ids.text = nil end
    end
  else
    drop(ps)
  end

  return true
end

-- MAIN LOOP
local bannerId = nil
while true do
  refresh_addresses()
  scan_players()
  local camPos, camRot, fov = fetch_camera()
  local count = 0
  if camPos and camRot and fov then
    for ps,_ in pairs(players) do
      if not_me_check(ps) then
        count = count + 1
        render_player(ps, camPos, camRot, fov)
      else
        drop(ps)
      end
    end
  end

  if bannerId then pcall(canvas.remove, bannerId) end
  if SHOW_COUNT then
    bannerId = canvas.drawText("PLAYER COUNT: " .. tostring(count), 40, 40, countPaint)
  end

  sleep(RENDER_SLEEP_MS)
end