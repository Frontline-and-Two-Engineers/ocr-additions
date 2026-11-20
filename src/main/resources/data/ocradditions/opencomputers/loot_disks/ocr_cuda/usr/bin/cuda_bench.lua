local component = require("component")
local computer = require("computer")

local gpus = {}
for addr in component.list("gpu") do
  local proxy = component.proxy(addr)
  if proxy.getComputeInfo then
    table.insert(gpus, { proxy = proxy, addr = addr })
  end
end

if #gpus == 0 then
  io.stderr:write("No GPUs with OCR Additions CUDA compute support found.\n")
  return 1
end

local function waitJob(gpu, targetAddr, jobId)
  while true do
    local ev = { computer.pullSignal(0.1) }
    if ev[1] == "gpu_compute_done" and ev[2] == targetAddr and ev[3] == jobId then
      return gpu.getComputeJob(jobId)
    elseif ev[1] == "interrupted" then
      gpu.cancelComputeJob(jobId)
      return nil, "interrupted"
    end
    local snap = gpu.getComputeJob(jobId)
    if snap and (snap.status == "done" or snap.status == "failed" or snap.status == "canceled") then
      return snap
    end
  end
end

local passed, failed = 0, 0
local function check(cond, name, detail)
  if cond then
    passed = passed + 1
    print(string.format("  [PASS] %s", name))
  else
    failed = failed + 1
    print(string.format("  [FAIL] %s (%s)", name, tostring(detail or "mismatch")))
  end
end

for idx, entry in ipairs(gpus) do
  local gpu, addr = entry.proxy, entry.addr
  local info = gpu.getComputeInfo()
  print(string.format("\n=== GPU #%d [%s] Tier %d | %d CUDA cores | MaxBatch=%d | Workers=%d (duty=%.0f%%) ===",
    idx, addr:sub(1, 8), info.tier, info.cudaCores, info.maxBatchSize,
    info.workerThreads, (info.dutyCycle or 0) * 100))

  -- 1. Direct single-pass hash primitive
  local h1 = gpu.hash("sha256", "ocradditions")
  check(type(h1) == "string" and #h1 == 64, "gpu.hash('sha256', ...)", h1)

  -- 2. Custom user double-SHA256 PoW kernel with stopOnFirst
  local powSrc = [[
    nonce = start + id
    h = sha256(sha256(concat(header, nonce)))
    ok = leadingZeroBits(h) >= bits
  ]]
  local kid, kerr = gpu.compileKernel(powSrc)
  check(kid ~= nil, "gpu.compileKernel(powSrc)", kerr)

  if kid then
    local j1, e1 = gpu.runKernel(math.min(8192, info.maxBatchSize), kid, {
      header = "test-blk:", start = 0, bits = 8
    }, {
      where = "ok", out = { "nonce", "h" }, stopOnFirst = true
    })
    check(j1 ~= nil, "gpu.runKernel PoW launch", e1)
    if j1 then
      local s1 = waitJob(gpu, addr, j1)
      local hit = s1 and s1.result and s1.result[1]
      local verified = hit and gpu.hash("sha256", gpu.hash("sha256", "test-blk:" .. string.format("%.0f", hit.nonce))) == hit.h
      check(s1 and s1.status == "done" and verified,
        string.format("PoW kernel match verified (checked=%s, %.0fms)",
          tostring(s1 and s1.threadsExecuted), (s1 and s1.durationMs) or 0),
        s1 and s1.error)
    end
    check(gpu.dropKernel(kid), "gpu.dropKernel")
  end

  -- 3. Bitwise + SHA3 prefix kernel
  local bwSrc = [[
    mix = bxor(id, rotl32(id, 5))
    h = sha3(concat("tx:", mix))
    ok = startsWith(h, "00")
  ]]
  local j2, e2 = gpu.runKernel(math.min(4096, info.maxBatchSize), bwSrc, {}, {
    where = "ok", out = "h", stopOnFirst = true
  })
  if j2 then
    local s2 = waitJob(gpu, addr, j2)
    check(s2 and s2.status == "done" and s2.result and #s2.result == 1 and s2.result[1]:sub(1, 2) == "00",
      "Bitwise bxor/rotl32 + sha3 kernel", s2 and s2.error)
  else
    check(false, "Bitwise kernel launch", e2)
  end

  -- 4. Array indexing + parallel reduction
  local j3, e3 = gpu.runKernel(4, "prod = a[id] * b[id]", {
    a = { 1, 2, 3, 4 }, b = { 10, 20, 30, 40 }
  }, { out = "prod", reduce = "sum" })
  if j3 then
    local s3 = waitJob(gpu, addr, j3)
    check(s3 and s3.status == "done" and math.abs((s3.reduced or 0) - 300) < 1e-6,
      "Array a[id] + parallel reduce('sum') == 300", s3 and s3.reduced)
  else
    check(false, "Reduction kernel launch", e3)
  end

  -- 5. vectorOp & matrixMul primitives
  local j4 = gpu.vectorOp("dot", { 1, 2, 3 }, { 4, 5, 6 })
  local s4 = j4 and waitJob(gpu, addr, j4)
  check(s4 and s4.status == "done" and math.abs((s4.dot or 0) - 32) < 1e-6,
    "gpu.vectorOp('dot') == 32", s4 and s4.dot)

  local j5 = gpu.matrixMul(2, 2, 2, { 1, 2, 3, 4 }, { 5, 6, 7, 8 })
  local s5 = j5 and waitJob(gpu, addr, j5)
  check(s5 and s5.status == "done" and s5.result and s5.result[1] == 19 and s5.result[4] == 50,
    "gpu.matrixMul 2x2 == [19,22,43,50]", s5 and s5.error)
end

print(string.format("\nSummary: %d passed, %d failed.", passed, failed))
return failed == 0 and 0 or 1
