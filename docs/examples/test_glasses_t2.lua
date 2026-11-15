-- Тест умных очков Tier 2: чат-префикс (по умолчанию '$$'), кликабельный HUD (клавиша H) и графики.

local component = require("component")
local computer = require("computer")

if not component.isAvailable("glasses_bridge") then
  io.stderr:write("Компонент 'glasses_bridge' не найден.\n")
  return
end

local bridge = component.glasses_bridge
bridge.clear()

bridge.setChatPrefix("$$")
print("Chat prefix: " .. bridge.getChatPrefix())
print("Try in chat: $$ping | $$add 50 | $$reset | $$prefix ! | $$clear")
print("Press H in game to click HUD buttons. Press Ctrl+C to exit.")

local points = { 10, 22, 18, 35, 48, 30, 55, 70, 62, 80 }
bridge.addRect("g_bg", 10, 10, 170, 80, 0x990D131D)
bridge.addText("g_lbl", 16, 15, "Graph (Tier 2)", 0xFF00E5FF, 1.0)
bridge.addGraph("g_main", 16, 28, 158, 54, points, 0xFF00FF99, 0x60101820)

bridge.addRect("btn_add", 10, 96, 80, 18, 0xCC1E4060)
bridge.addText("btn_add_txt", 18, 101, "+ Random", 0xFFFFFFFF, 1.0)
bridge.setClickable("btn_add", true)

bridge.addRect("btn_reset", 98, 96, 82, 18, 0xCC602020)
bridge.addText("btn_reset_txt", 106, 101, "Reset", 0xFFFFFFFF, 1.0)
bridge.setClickable("btn_reset", true)

bridge.addText("status", 10, 120, "Prefix: " .. bridge.getChatPrefix(), 0xFFCCCCCC, 0.9)

local function redrawGraph()
  bridge.addGraph("g_main", 16, 28, 158, 54, points, 0xFF00FF99, 0x60101820)
end

local function addPoint(val)
  table.insert(points, val)
  if #points > 30 then
    table.remove(points, 1)
  end
  redrawGraph()
end

while true do
  local ev = { computer.pullSignal(0.5) }
  local name = ev[1]

  if name == "interrupted" then
    break
  elseif name == "glasses_hud_click" and ev[2] == bridge.address then
    -- ev[2] = адрес моста (вставляется OpenComputers)
    local uuid, playerName, widgetId, button = ev[3], ev[4], ev[5], ev[6]
    print(string.format("[CLICK] %s clicked '%s' (btn=%s)", tostring(playerName), tostring(widgetId), tostring(button)))

    if widgetId == "btn_add" then
      addPoint(math.random(5, 100))
      bridge.sendMessage(uuid, "Added point, total=" .. #points)
    elseif widgetId == "btn_reset" then
      points = { 10, 20, 15 }
      redrawGraph()
      bridge.sendMessage(uuid, "Graph reset")
    end

  elseif name == "glasses_chat_command" and ev[2] == bridge.address then
    local uuid, playerName, cmd, prefix = ev[3], ev[4], ev[5], ev[6]
    print(string.format("[CHAT] <%s> %s%s", tostring(playerName), tostring(prefix), tostring(cmd)))

    if cmd == "ping" then
      bridge.sendMessage(uuid, "Pong! Graph points: " .. #points)
    elseif cmd == "reset" then
      points = { 10, 20, 15 }
      redrawGraph()
      bridge.sendMessage(uuid, "Graph reset via chat")
    elseif cmd == "clear" then
      points = {}
      redrawGraph()
      bridge.sendMessage(uuid, "Graph cleared")
    elseif cmd:sub(1, 4) == "add " then
      local n = tonumber(cmd:sub(5))
      if n then
        addPoint(n)
        bridge.sendMessage(uuid, "Added value " .. n)
      else
        bridge.sendMessage(uuid, "Usage: " .. tostring(prefix) .. "add <number>")
      end
    elseif cmd:sub(1, 7) == "prefix " then
      local newPrefix = cmd:sub(8)
      local ok, res = pcall(bridge.setChatPrefix, newPrefix)
      if ok then
        bridge.addText("status", 10, 120, "Prefix: " .. bridge.getChatPrefix(), 0xFFCCCCCC, 0.9)
        bridge.sendMessage(uuid, "Prefix changed from '" .. tostring(res) .. "' to '" .. bridge.getChatPrefix() .. "'")
      else
        bridge.sendMessage(uuid, "Invalid prefix: " .. tostring(res))
      end
    else
      bridge.sendMessage(uuid, "Echo: " .. tostring(cmd))
    end
  end
end

bridge.clear()
