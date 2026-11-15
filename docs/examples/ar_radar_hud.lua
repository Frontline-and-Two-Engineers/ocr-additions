-- Комбинированная программа: Радар + Умные очки.
-- На очках Tier 1 показывает список целей, на Tier 2 строит график количества сущностей,
-- а при наличии Радара Tier 3 + Очков Tier 3 подсвечивает ближайших мобов и игроков 3D-маркерами.

local component = require("component")
local computer = require("computer")

if not component.isAvailable("glasses_bridge") or not component.isAvailable("radar") then
  io.stderr:write("Требуются компоненты 'glasses_bridge' и 'radar'.\n")
  return
end

local bridge = component.glasses_bridge
local radar = component.radar

local radarTier = radar.getTier()
local maxRange = radar.getMaxRange()

bridge.clear()
bridge.addRect("ar_bg", -210, 10, 200, 118, 0x99101620)
bridge.setAnchor("ar_bg", "TOP_RIGHT")

bridge.addText("ar_title", -202, 16, string.format("Radar Mk%d (%.0fm)", radarTier, maxRange), 0xFF00E5FF, 1.0)
bridge.setAnchor("ar_title", "TOP_RIGHT")

local MAX_ROWS = 5
for i = 1, MAX_ROWS do
  local id = "ar_row_" .. i
  bridge.addText(id, -202, 20 + i * 11, "", 0xFFFFFFFF, 0.85)
  bridge.setAnchor(id, "TOP_RIGHT")
end

local history = {}
local activeMarkers = {}

print("AR Radar HUD active. Press Ctrl+C to stop.")

while true do
  local list, err = radar.scan()
  if list then
    table.insert(history, #list)
    if #history > 40 then
      table.remove(history, 1)
    end

    bridge.addText("ar_title", -202, 16,
      string.format("Radar Mk%d: %d entities", radarTier, #list), 0xFF00E5FF, 1.0)
    bridge.setAnchor("ar_title", "TOP_RIGHT")

    bridge.addGraph("ar_graph", -202, 86, 184, 34, history, 0xFF22EE88, 0x60101820)
    bridge.setAnchor("ar_graph", "TOP_RIGHT")

    for i = 1, MAX_ROWS do
      local e = list[i]
      local line = ""
      if e then
        if radarTier == 1 then
          line = e.type
        elseif radarTier == 2 then
          line = string.format("[%.0fm] %s", e.distance or 0, e.type)
        else
          line = string.format("[%.0fm] %s", e.distance or 0, e.name or e.type)
        end
      end
      local id = "ar_row_" .. i
      bridge.addText(id, -202, 20 + i * 11, line:sub(1, 30), 0xFFE0E0E0, 0.85)
      bridge.setAnchor(id, "TOP_RIGHT")
    end

    -- 3D AR-маркеры на ближайшие 16 сущностей (работает при Радаре Tier 3 + Очках Tier 3)
    if radarTier >= 3 then
      local nextMarkers = {}
      local limit = math.min(#list, 16)
      for i = 1, limit do
        local e = list[i]
        if e.uuid then
          local mid = "em_" .. e.uuid
          nextMarkers[mid] = true
          local color = e.isPlayer and 0xFF00E5FF or 0xFFFFAA00
          local label = string.format("%s (%.0fm)", e.name or e.type, e.distance or 0)
          bridge.addEntityMarker(mid, e.uuid, label, color)
        end
      end
      for oldId in pairs(activeMarkers) do
        if not nextMarkers[oldId] then
          bridge.removeWidget(oldId)
        end
      end
      activeMarkers = nextMarkers
    end
  else
    bridge.addText("ar_title", -202, 16, "Radar err: " .. tostring(err), 0xFFFF5555, 0.9)
    bridge.setAnchor("ar_title", "TOP_RIGHT")
  end

  local ev = { computer.pullSignal(0.5) }
  if ev[1] == "interrupted" then
    break
  end
end

bridge.clear()
