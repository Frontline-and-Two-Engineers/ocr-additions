local component = require("component")
local computer = require("computer")
local term = require("term")

if not component.isAvailable("radar") then
  io.stderr:write("Component 'radar' not found.\n")
  return
end

local radar = component.radar
local tier = radar.getTier()
local maxRange = radar.getMaxRange()
local relative = radar.isRelativeCoordinates()

print(string.format("Radar Tier: %d | Max range: %.0f | Relative coords: %s",
  tier, maxRange, tostring(relative)))
print("Press Ctrl+C to stop.")

while true do
  local entities, err = radar.scan()
  if not entities then
    print("Scan error: " .. tostring(err))
  else
    term.clear()
    term.setCursor(1, 1)
    print(string.format("=== RADAR Mk%d (range: %.0f, found: %d) ===", tier, maxRange, #entities))

    local shown = math.min(#entities, 15)
    for i = 1, shown do
      local e = entities[i]
      if tier == 1 then
        print(string.format("%2d. type=%s", i, tostring(e.type)))
      elseif tier == 2 then
        print(string.format("%2d. [%.1fm] %s", i, e.distance or 0, tostring(e.type)))
      else
        local tag = e.isPlayer and "[PLAYER] " or ""
        print(string.format("%2d. %s%s (%s) | dist=%.1fm | rel=(%.1f, %.1f, %.1f)",
          i, tag, tostring(e.name), tostring(e.type),
          e.distance or 0, e.x or 0, e.y or 0, e.z or 0))
      end
    end
  end

  local ev = { computer.pullSignal(1.0) }
  if ev[1] == "interrupted" then
    break
  end
end
