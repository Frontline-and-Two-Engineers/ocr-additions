local filesystem = require("filesystem")
local shell = require("shell")

local runningProgram = os.getenv("_")
if not runningProgram then
  io.stderr:write("Could not locate the OCR Glasses Tests disk.\n")
  return 1
end

local diskRoot = filesystem.path(shell.resolve(runningProgram))
local ok, reason = shell.execute(
  "/bin/install.lua",
  nil,
  "--from=" .. diskRoot,
  "--fromDir=usr",
  "--root=usr",
  "OCR Glasses Tests"
)

if not ok and reason then
  io.stderr:write(tostring(reason) .. "\n")
end
return ok and 0 or 1
