-- Тест умных очков Tier 1: базовый 2D HUD-оверлей, якоря, прогресс-бар и иконка предмета.

local component = require("component")
local computer = require("computer")

if not component.isAvailable("glasses_bridge") then
  io.stderr:write("Компонент 'glasses_bridge' не найден.\n")
  return
end

local bridge = component.glasses_bridge
bridge.clear()

bridge.addRect("panel", 10, 10, 175, 56, 0xAA101824)
bridge.addItemIcon("icon", 16, 16, "minecraft:emerald", 1.0)
bridge.addText("title", 38, 18, "OCR Additions HUD", 0xFF00E5FF, 1.0)
bridge.addText("sub", 38, 30, "Tier 1 Test", 0xFFFFFFFF, 0.9)
bridge.addProgressBar("bar", 16, 48, 158, 10, 0, 100, 0xFF22CC66, 0x80202020)

bridge.addText("clock", -95, 10, "Load: 0%", 0xFFFFFF55, 1.0)
bridge.setAnchor("clock", "TOP_RIGHT")

print("Tier 1 HUD running. Press Ctrl+C to stop and clear.")

local value = 0
while true do
  value = (value + 5) % 105
  bridge.addProgressBar("bar", 16, 48, 158, 10, value, 100, 0xFF22CC66, 0x80202020)
  bridge.addText("clock", -95, 10, string.format("Load: %d%%", value), 0xFFFFFF55, 1.0)
  bridge.setAnchor("clock", "TOP_RIGHT")

  local ev = { computer.pullSignal(0.25) }
  if ev[1] == "interrupted" then
    break
  end
end

bridge.clear()
