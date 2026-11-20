local component = require("component")
local computer = require("computer")

if not component.isAvailable("radar") then
  io.stderr:write("Component 'radar' not found.\n")
  return
end

local radar = component.radar
local tier = radar.getTier()
local range = radar.getMaxRange()

print(string.format("Radar Watch active (Mk%d, %.0fm). Monitoring changes... (Ctrl+C to exit)", tier, range))
if tier < 3 then
  print("Note: Tier < 3 has no per-entity UUID; tracking counts by entity type.")
end

local prevSet = {}

while true do
  local list, err = radar.scan()
  if list then
    if tier >= 3 then
      local curSet = {}
      for _, e in ipairs(list) do
        local key = e.uuid or e.name
        curSet[key] = e
        if not prevSet[key] then
          local tag = e.isPlayer and "[PLAYER]" or "[ENTITY]"
          print(string.format("+ ENTER %s %s (%s) at rel(%.1f, %.1f, %.1f) [%.1fm]",
            tag, tostring(e.name), tostring(e.type), e.x or 0, e.y or 0, e.z or 0, e.distance or 0))
        end
      end
      for key, old in pairs(prevSet) do
        if not curSet[key] then
          print(string.format("- LEAVE %s (%s)", tostring(old.name), tostring(old.type)))
        end
      end
      prevSet = curSet
    else
      local counts = {}
      for _, e in ipairs(list) do
        counts[e.type] = (counts[e.type] or 0) + 1
      end
      for k, v in pairs(counts) do
        if prevSet[k] ~= v then
          print(string.format("~ %s count: %s -> %d", k, tostring(prevSet[k] or 0), v))
        end
      end
      for k, v in pairs(prevSet) do
        if not counts[k] then
          print(string.format("- %s left (%d -> 0)", k, v))
        end
      end
      prevSet = counts
    end
  else
    print("Radar error: " .. tostring(err))
  end

  local ev = { computer.pullSignal(0.5) }
  if ev[1] == "interrupted" then
    break
  end
end
