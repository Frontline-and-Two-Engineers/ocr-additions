local component = require("component")
local computer = require("computer")

if not component.isAvailable("glasses_bridge") then
  io.stderr:write("Component 'glasses_bridge' not found.\n")
  return
end

local bridge = component.glasses_bridge
bridge.clear()

print("Tier 3 AR + Gaze + Telemetry running.")
print("Relative coordinates mode: " .. tostring(bridge.isRelativeCoordinates()))
print("Press G while looking at a block or mob to place a 3D marker.")
print("Press Ctrl+C to exit.")

bridge.addWorldMarker("bridge_origin", 0.5, 1.5, 0.5, "Glasses Bridge (0,0,0)", 0xFF00E5FF)
bridge.addWorldLine("bridge_axis_y", 0.5, 1.0, 0.5, 0.5, 4.0, 0.5, 0xFF00FF88)

bridge.addRect("tele_bg", 10, 10, 230, 68, 0xAA0D1520)
bridge.addText("tele_title", 16, 14, "Tier 3 Wearer Telemetry", 0xFF00E5FF, 1.0)
bridge.addText("tele_l1", 16, 28, "Waiting for Tier 3 wearer...", 0xFFFFFFFF, 0.9)
bridge.addText("tele_l2", 16, 40, "", 0xFFCCCCCC, 0.9)
bridge.addText("tele_l3", 16, 52, "", 0xFFFFFF55, 0.9)
bridge.addText("tele_l4", 16, 64, "Press [G] to scan gaze target", 0xFF88FF88, 0.9)

local function updateTelemetry()
  local players = bridge.getConnectedPlayers()
  if #players == 0 then
    bridge.addText("tele_l1", 16, 28, "No connected wearers", 0xFFFF6666, 0.9)
    return
  end

  local p = players[1]
  if (p.tier or 0) < 3 then
    bridge.addText("tele_l1", 16, 28, p.name .. ": Tier " .. tostring(p.tier) .. " (need Tier 3)", 0xFFFFAA00, 0.9)
    return
  end

  local stats, err = bridge.getPlayerStats(p.uuid)
  if not stats then
    bridge.addText("tele_l1", 16, 28, "Telemetry error: " .. tostring(err), 0xFFFF6666, 0.9)
    return
  end

  bridge.addText("tele_l1", 16, 28,
    string.format("%s | HP: %.1f/%.0f | Food: %d", stats.name, stats.hp, stats.maxHp, stats.food),
    0xFFFFFFFF, 0.9)
  bridge.addText("tele_l2", 16, 40,
    string.format("Armor: %d | XP Lv: %d | FE: %d/%d", stats.armor, stats.xpLevel, stats.energy, stats.maxEnergy),
    0xFFCCCCCC, 0.9)
  bridge.addText("tele_l3", 16, 52,
    string.format("RelPos: (%.1f, %.1f, %.1f) Yaw: %.0f", stats.x, stats.y, stats.z, stats.yaw),
    0xFFFFFF55, 0.9)
end

while true do
  updateTelemetry()

  local ev = { computer.pullSignal(0.5) }
  local name = ev[1]

  if name == "interrupted" then
    break
  elseif name == "glasses_gaze" and ev[2] == bridge.address then
    local uuid, playerName, targetType = ev[3], ev[4], ev[5]
    local hitX, hitY, hitZ = ev[6], ev[7], ev[8]

    if targetType == "block" then
      local bx, by, bz, face, blockId = ev[9], ev[10], ev[11], ev[12], ev[13]
      local msg = string.format("Gaze Block: %s @ rel(%d,%d,%d) [%s]", tostring(blockId), bx, by, bz, tostring(face))
      print("[GAZE] " .. tostring(playerName) .. " -> " .. msg)
      bridge.addText("tele_l4", 16, 64, msg, 0xFF88FF88, 0.9)
      bridge.addWorldMarker("gaze_target", bx + 0.5, by + 0.5, bz + 0.5, tostring(blockId), 0xFFFFAA00)
      bridge.addWorldLine("gaze_ray", 0.5, 1.5, 0.5, bx + 0.5, by + 0.5, bz + 0.5, 0x80FFAA00)
      bridge.sendMessage(uuid, msg)

    elseif targetType == "entity" then
      local entUuid, entName, entType = ev[9], ev[10], ev[11]
      local msg = string.format("Gaze Entity: %s (%s)", tostring(entName), tostring(entType))
      print("[GAZE] " .. tostring(playerName) .. " -> " .. msg)
      bridge.addText("tele_l4", 16, 64, msg, 0xFFFF8888, 0.9)
      bridge.addEntityMarker("gaze_entity", entUuid, tostring(entName), 0xFFFF4444)
      bridge.sendMessage(uuid, msg)

    else
      print(string.format("[GAZE] %s -> miss (%.1f, %.1f, %.1f)", tostring(playerName), hitX or 0, hitY or 0, hitZ or 0))
      bridge.addText("tele_l4", 16, 64, "Gaze: sky / miss", 0xFFAAAAAA, 0.9)
    end
  end
end

bridge.clear()
