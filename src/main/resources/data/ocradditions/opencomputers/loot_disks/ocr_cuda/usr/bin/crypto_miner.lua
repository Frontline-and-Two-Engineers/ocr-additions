local component = require("component")
local computer = require("computer")
local term = require("term")

local DIFFICULTY_BITS = 16
local BLOCK_PREFIX = "OCR-COIN:block=1:prev=00000000:nonce="

local MINER_KERNEL = [[
  nonce = start + id
  h = sha256(sha256(concat(header, nonce)))
  ok = leadingZeroBits(h) >= bits
]]

local gpus = {}
for addr in component.list("gpu") do
  local proxy = component.proxy(addr)
  if proxy.compileKernel and proxy.runKernel then
    local kid, kerr = proxy.compileKernel(MINER_KERNEL)
    if kid then
      table.insert(gpus, {
        proxy = proxy,
        addr = addr,
        kernelId = kid,
        info = proxy.getComputeInfo()
      })
    else
      io.stderr:write("Kernel compile error: " .. tostring(kerr) .. "\n")
    end
  end
end

if #gpus == 0 then
  io.stderr:write("No GPUs with OCR Additions CUDA support found.\n")
  return
end

local bridge = component.isAvailable("glasses_bridge") and component.glasses_bridge or nil
if bridge then
  bridge.clear()
  bridge.addRect("mine_bg", 10, 10, 215, 48, 0xAA0D1520)
  bridge.addText("mine_t1", 16, 15, "OCR-Coin User Kernel Miner", 0xFF00E5FF, 1.0)
  bridge.addText("mine_t2", 16, 28, "Mining...", 0xFFFFFFFF, 0.9)
  bridge.addText("mine_t3", 16, 41, "", 0xFF22EE88, 0.9)
end

local nextNonce = 0
local totalHashes = 0
local blocksFound = 0
local startUptime = computer.uptime()
local pendingJobs = {}

local function submitNextBatch(gpuEntry)
  local batch = math.min(gpuEntry.info.maxBatchSize, 65536)
  local start = nextNonce
  nextNonce = nextNonce + batch

  local jobId, err = gpuEntry.proxy.runKernel(batch, gpuEntry.kernelId, {
    header = BLOCK_PREFIX,
    start = start,
    bits = DIFFICULTY_BITS
  }, {
    where = "ok",
    out = { "nonce", "h" },
    stopOnFirst = true
  })

  if jobId then
    pendingJobs[gpuEntry.addr .. ":" .. tostring(jobId)] = gpuEntry
  else
    print("Queue notice on " .. gpuEntry.addr:sub(1, 6) .. ": " .. tostring(err))
  end
end

term.clear()
print(string.format("Starting custom-kernel miner across %d GPU(s) | bits=%d", #gpus, DIFFICULTY_BITS))
for i, g in ipairs(gpus) do
  print(string.format("  GPU #%d [%s]: Tier %d, %d CUDA cores, batch=%d",
    i, g.addr:sub(1, 8), g.info.tier, g.info.cudaCores, g.info.maxBatchSize))
  submitNextBatch(g)
end
print("Press Ctrl+C to stop.")

while true do
  local ev = { computer.pullSignal(1.0) }
  local name = ev[1]

  if name == "interrupted" then
    break
  elseif name == "gpu_compute_done" then
    local addr, jobId, kind, ok = ev[2], ev[3], ev[4], ev[5]
    local key = tostring(addr) .. ":" .. tostring(jobId)
    local gpuEntry = pendingJobs[key]
    if gpuEntry and kind == "kernel" then
      pendingJobs[key] = nil
      local job = gpuEntry.proxy.getComputeJob(jobId)
      local executed = (job and job.threadsExecuted) or 0
      totalHashes = totalHashes + executed

      if ok and job and job.result and #job.result > 0 then
        local hit = job.result[1]
        local verify = gpuEntry.proxy.hash("sha256",
          gpuEntry.proxy.hash("sha256", BLOCK_PREFIX .. string.format("%.0f", hit.nonce)))

        blocksFound = blocksFound + 1
        print(string.format("\n[BLOCK FOUND #%d] GPU=%s nonce=%.0f hash=%s (verified=%s)",
          blocksFound, addr:sub(1, 6), hit.nonce, hit.h:sub(1, 20) .. "...", tostring(verify == hit.h)))

        BLOCK_PREFIX = "OCR-COIN:block=" .. tostring(blocksFound + 1) .. ":prev=" .. hit.h:sub(1, 16) .. ":nonce="
        nextNonce = 0
      end

      local elapsed = math.max(0.01, computer.uptime() - startUptime)
      local khs = (totalHashes / elapsed) / 1000.0
      if bridge then
        bridge.addText("mine_t2", 16, 28,
          string.format("Hashrate: %.1f kH/s | Blocks: %d", khs, blocksFound), 0xFFFFFFFF, 0.9)
        bridge.addText("mine_t3", 16, 41,
          string.format("Total: %d hashes (%d GPUs)", totalHashes, #gpus), 0xFF22EE88, 0.9)
      end

      submitNextBatch(gpuEntry)
    end
  end
end

for _, g in ipairs(gpus) do
  g.proxy.cancelAllCompute()
  g.proxy.dropKernel(g.kernelId)
end
if bridge then
  bridge.clear()
end
print("Miner stopped cleanly.")
