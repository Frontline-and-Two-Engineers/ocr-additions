-- oc-crypto/client/wallet.lua
-- Клиентский кошелёк для криптовалюты OCCoin (OpenComputers)
-- Требования: Data Card Tier 3, Wireless Network Card, GPU + Screen

package.path = package.path .. ";/home/oc-crypto/lib/?.lua;./lib/?.lua"

local component = require("component")
local event = require("event")
local filesystem = require("filesystem")
local serialization = require("serialization")
local term = require("term")
local occoin = require("occoin")

local WALLET_PATH = "/home/occoin_wallet.dat"

-- Проверка компонентов
local dataCard, errData = occoin.getDataCard()
if not dataCard then
  io.stderr:write(errData .. "\n")
  return
end

local modem, errModem = occoin.getWirelessModem()
if not modem then
  io.stderr:write(errModem .. "\n")
  return
end

if not component.isAvailable("gpu") then
  io.stderr:write("GPU не найдена!\n")
  return
end
local gpu = component.gpu

modem.open(occoin.PORT)
if modem.setStrength then
  modem.setStrength(400)
end

-- Загрузка или создание кошелька
local wallet = nil

local function saveWallet()
  local f, err = io.open(WALLET_PATH, "w")
  if not f then return false, err end
  f:write(serialization.serialize(wallet))
  f:close()
  return true
end

local function loadOrCreateWallet()
  if filesystem.exists(WALLET_PATH) then
    local f = io.open(WALLET_PATH, "r")
    if f then
      local raw = f:read("*a")
      f:close()
      local ok, loaded = pcall(serialization.unserialize, raw)
      if ok and type(loaded) == "table" and loaded.address and loaded.privB64 and loaded.pubB64 then
        wallet = loaded
        return false
      end
    end
  end
  wallet = occoin.generateWalletKeys(dataCard)
  wallet.hostAddr = nil
  saveWallet()
  return true
end

local isNewWallet = loadOrCreateWallet()

-- Состояние клиента
local statusMsg = isNewWallet and "Создан новый кошелёк ECDSA-256!" or "Кошелёк загружен."
local statusColor = 0x55FF55
local balance = 0
local nonce = 0
local chainHeight = 0
local hostDistance = nil
local historyItems = {}
local clientLogs = {}
local activeTab = "OVERVIEW" -- "OVERVIEW" | "HISTORY"

local W, H = gpu.maxResolution()
if W > 80 then W = 80 end
if H > 25 then H = 25 end
gpu.setResolution(W, H)

local function addLog(msg, color)
  table.insert(clientLogs, 1, { text = tostring(msg), color = color or 0xE2E8F0 })
  if #clientLogs > 15 then
    table.remove(clientLogs)
  end
end

local function setStatus(msg, color, writeLog)
  statusMsg = msg
  statusColor = color or 0xE2E8F0
  if writeLog then
    addLog(msg, statusColor)
  end
end

addLog(isNewWallet and "Создан новый кошелёк" or "Кошелёк загружен", 0x55FF55)

-- Отправка запроса к хосту и ожидание ответа
local function requestHost(cmdTbl, expectedCmd, timeout)
  if not wallet.hostAddr then
    return nil, "Хост не найден. Нажмите [F] для поиска."
  end
  modem.send(wallet.hostAddr, occoin.PORT, occoin.encodeMsg(cmdTbl))
  local deadline = require("computer").uptime() + (timeout or 3)
  while true do
    local rem = deadline - require("computer").uptime()
    if rem <= 0 then
      return nil, "Таймаут ответа от хоста"
    end
    local ev, _, remoteAddr, port, dist, raw = event.pull(rem, "modem_message")
    if ev == "modem_message" and port == occoin.PORT and remoteAddr == wallet.hostAddr then
      local res = occoin.decodeMsg(raw)
      if res then
        if res.cmd == "NEW_BLOCK" then
          chainHeight = res.height or chainHeight
        elseif res.cmd == expectedCmd then
          hostDistance = dist
          return res
        end
      end
    end
  end
end

-- Поиск хоста по беспроводной сети
local function discoverHost()
  setStatus("Поиск хоста по беспроводной сети...", 0xFACC15)
  modem.broadcast(occoin.PORT, occoin.encodeMsg({ cmd = "PING" }))
  local deadline = require("computer").uptime() + 3
  while true do
    local rem = deadline - require("computer").uptime()
    if rem <= 0 then
      setStatus("Хост не ответил в радиусе действия сети.", 0xEF4444)
      return false
    end
    local ev, _, remoteAddr, port, dist, raw = event.pull(rem, "modem_message")
    if ev == "modem_message" and port == occoin.PORT then
      local res = occoin.decodeMsg(raw)
      if res and res.cmd == "PONG" then
        wallet.hostAddr = remoteAddr
        hostDistance = dist
        chainHeight = res.height or 0
        saveWallet()
        setStatus(string.format("Подключено к хосту %s (дист: %.1fм)", string.sub(remoteAddr, 1, 8), dist or 0), 0x55FF55)
        return true
      end
    end
  end
end

-- Обновление баланса и истории
local function refreshData()
  if not wallet.hostAddr then
    if not discoverHost() then return end
  end
  local res, err = requestHost({ cmd = "BALANCE", address = wallet.address }, "BALANCE_RES", 3)
  if not res then
    setStatus(err, 0xEF4444)
    return
  end
  if res.ok then
    balance = res.balance or 0
    nonce = res.nonce or 0
    chainHeight = res.height or 0
    setStatus("Данные обновлены.", 0x55FF55)
  else
    setStatus(res.err or "Ошибка получения баланса", 0xEF4444)
  end

  local hRes = requestHost({ cmd = "HISTORY", address = wallet.address, limit = 10 }, "HISTORY_RES", 3)
  if hRes and hRes.ok and type(hRes.items) == "table" then
    historyItems = hRes.items
  end
end

-- Отрисовка интерфейса
local function drawUI()
  gpu.setBackground(0x0F172A)
  gpu.setForeground(0xFFFFFF)
  gpu.fill(1, 1, W, H, " ")

  -- Заголовок
  occoin.ui.box(gpu, 1, 1, W, 3, 0x1E293B, 0x38BDF8)
  local hostLabel = wallet.hostAddr and (string.sub(wallet.hostAddr, 1, 8) .. "..") or "НЕТ СВЯЗИ"
  occoin.ui.text(gpu, 3, 2, string.format("OCCoin WALLET  |  Хост: %s  |  Блоков: %d", hostLabel, chainHeight), 0x1E293B, 0x38BDF8)

  -- Карточка кошелька
  occoin.ui.box(gpu, 1, 5, W, 4, 0x1E293B, 0xE2E8F0, "Ваш кошелёк")
  occoin.ui.text(gpu, 3, 6, "Адрес:  " .. wallet.address, 0x1E293B, 0xF8FAFC)
  occoin.ui.text(gpu, 3, 7, string.format("Баланс: %.4f %s    |    Nonce: %d", balance, occoin.CURRENCY_NAME, nonce), 0x1E293B, 0x4ADE80)

  -- Область контента
  if activeTab == "OVERVIEW" then
    local menuW = math.min(38, math.floor(W * 0.48))
    local logX = menuW + 2
    local logW = W - logX + 1
    local boxH = H - 14

    occoin.ui.box(gpu, 1, 10, menuW, boxH, 0x0B1120, 0xCBD5E1, "Управление")
    occoin.ui.text(gpu, 3, 12, "[R] Обновить баланс", 0x0B1120, 0x38BDF8)
    occoin.ui.text(gpu, 3, 13, "[S] Отправить монеты", 0x0B1120, 0x38BDF8)
    occoin.ui.text(gpu, 3, 14, "[H] История транзакций", 0x0B1120, 0x38BDF8)
    occoin.ui.text(gpu, 3, 15, "[M] Майнинг (SHA-256)", 0x0B1120, 0xFACC15)
    occoin.ui.text(gpu, 3, 16, "[F] Найти хост", 0x0B1120, 0xA855F7)
    occoin.ui.text(gpu, 3, 17, "[Q] Выход", 0x0B1120, 0xEF4444)

    occoin.ui.box(gpu, logX, 10, logW, boxH, 0x0B1120, 0xCBD5E1, "Журнал (Банк / Майнинг)")
    local maxLogRows = boxH - 2
    for i = 1, math.min(#clientLogs, maxLogRows) do
      local entry = clientLogs[i]
      occoin.ui.text(gpu, logX + 2, 10 + i, string.sub(entry.text, 1, logW - 4), 0x0B1120, entry.color)
    end
  elseif activeTab == "HISTORY" then
    occoin.ui.box(gpu, 1, 10, W, H - 14, 0x0B1120, 0xCBD5E1, "Последние транзакции ([O] Назад)")
    if #historyItems == 0 then
      occoin.ui.text(gpu, 4, 12, "Транзакций пока нет.", 0x0B1120, 0x94A3B8)
    else
      for i, item in ipairs(historyItems) do
        if i > H - 16 then break end
        local isOut = (item.from == wallet.address)
        local sign = isOut and "-" or "+"
        local peer = isOut and item.to or item.from
        local color = isOut and 0xF87171 or 0x4ADE80
        local line = string.format("#%-4d %-4s %s%.2f %s  (%s) %s",
          item.index or 0, item.type or "TX", sign, item.amount or 0, occoin.CURRENCY_NAME,
          string.sub(tostring(peer), 1, 14), item.memo or "")
        occoin.ui.text(gpu, 3, 10 + i, string.sub(line, 1, W - 4), 0x0B1120, color)
      end
    end
  end

  -- Статус-бар и подвал
  occoin.ui.box(gpu, 1, H - 3, W, 2, 0x1E293B, statusColor)
  occoin.ui.text(gpu, 3, H - 2, string.sub(statusMsg, 1, W - 4), 0x1E293B, statusColor)
  occoin.ui.box(gpu, 1, H - 1, W, 2, 0x020617, 0x94A3B8)
  occoin.ui.text(gpu, 3, H, "[R] Обновить  [S] Отправить  [H] История  [M] Майнинг  [F] Поиск  [Q] Выход", 0x020617, 0x94A3B8)
end

local function promptInput(label)
  gpu.setBackground(0x1E293B)
  gpu.setForeground(0xFFFFFF)
  gpu.fill(1, H - 3, W, 4, " ")
  term.setCursor(3, H - 2)
  io.write(label .. " ")
  return io.read()
end

-- Отправка транзакции
local function sendCoins()
  if not wallet.hostAddr then
    setStatus("Сначала подключитесь к хосту [F].", 0xEF4444)
    return
  end
  local toAddr = promptInput("Адрес получателя (OCC-...):")
  if not toAddr or #toAddr ~= 28 or string.sub(toAddr, 1, 4) ~= "OCC-" then
    setStatus("Неверный формат адреса получателя.", 0xEF4444)
    return
  end
  local amtStr = promptInput("Сумма (" .. occoin.CURRENCY_NAME .. "):")
  local amt = occoin.roundAmount(amtStr)
  if not amt or amt <= 0 then
    setStatus("Неверная сумма.", 0xEF4444)
    return
  end
  local memo = promptInput("Комментарий (опционально):") or ""

  setStatus("Подпись транзакции на Data Card...", 0xFACC15)
  drawUI()

  local sigB64, errSig = occoin.signTx(dataCard, wallet.privB64, wallet.address, toAddr, amt, nonce, memo)
  if not sigB64 then
    setStatus(errSig or "Ошибка подписи", 0xEF4444)
    return
  end

  local res, errReq = requestHost({
    cmd = "TX",
    from = wallet.address,
    to = toAddr,
    amount = amt,
    nonce = nonce,
    memo = memo,
    pubB64 = wallet.pubB64,
    sigB64 = sigB64
  }, "TX_RES", 4)

  if not res then
    setStatus("Ошибка банка: " .. tostring(errReq), 0xEF4444, true)
    return
  end
  if res.ok then
    balance = res.balance or balance
    nonce = res.nonce or (nonce + 1)
    setStatus(string.format("Отправлено %.2f %s! Блок #%d", amt, occoin.CURRENCY_NAME, res.index or 0), 0x55FF55, true)
  else
    setStatus("Банк отклонил: " .. tostring(res.err), 0xEF4444, true)
  end
end

-- Майнинг (все GPU через CUDA при наличии, иначе Data Card SHA-256)
local MINER_KERNEL = [[
  nonce = start + id
  h = sha256(concat(header, nonce))
  ok = leadingZeroBits(h) >= bits
]]

local function startMiningCuda(idx, prevHash, diff)
  local gpus = {}
  for addr in component.list("gpu") do
    local proxy = component.proxy(addr)
    if proxy.compileKernel and proxy.runKernel and proxy.getComputeJob and proxy.getComputeInfo then
      local kid = proxy.compileKernel(MINER_KERNEL)
      if kid then
        table.insert(gpus, {
          proxy = proxy,
          addr = addr,
          kernelId = kid,
          info = proxy.getComputeInfo()
        })
      end
    end
  end

  if #gpus == 0 then
    return false
  end

  local nextNonce = 0
  local pendingJobs = {}
  local header = prevHash .. "|" .. tostring(idx) .. "|" .. wallet.address .. "|"
  local bits = diff * 4

  local function submitBatch(g)
    local batch = math.min(g.info.maxBatchSize, 65536)
    local start = nextNonce
    nextNonce = nextNonce + batch
    local jobId = g.proxy.runKernel(batch, g.kernelId, {
      header = header,
      start = start,
      bits = bits
    }, {
      where = "ok",
      out = { "nonce", "h" },
      stopOnFirst = true
    })
    if jobId then
      pendingJobs[g.addr .. ":" .. tostring(jobId)] = { gpu = g, blockIdx = idx }
    end
  end

  local function restartAll(newIdx, newPrev, newDiff)
    for _, g in ipairs(gpus) do
      g.proxy.cancelAllCompute()
    end
    pendingJobs = {}
    idx = newIdx
    prevHash = newPrev
    diff = newDiff or diff
    bits = diff * 4
    header = prevHash .. "|" .. tostring(idx) .. "|" .. wallet.address .. "|"
    nextNonce = 0
    for _, g in ipairs(gpus) do
      submitBatch(g)
    end
  end

  local function cleanup()
    for _, g in ipairs(gpus) do
      g.proxy.cancelAllCompute()
      g.proxy.dropKernel(g.kernelId)
    end
  end

  setStatus(string.format("CUDA майнинг #%d (%d GPU, сложн. %d)... Клавиша = стоп", idx, #gpus, diff), 0xFACC15, true)
  drawUI()
  for _, g in ipairs(gpus) do
    submitBatch(g)
  end

  while true do
    local ev, a, b, c, d, raw = event.pull(1.0)
    if ev == "key_down" then
      cleanup()
      setStatus("Майнинг остановлен.", 0x94A3B8, true)
      return true
    elseif ev == "modem_message" and c == occoin.PORT and b == wallet.hostAddr then
      local msg = occoin.decodeMsg(raw)
      if msg and msg.cmd == "NEW_BLOCK" and msg.index and msg.prevHash and msg.index > idx then
        local foundNum = msg.index - 1
        chainHeight = msg.height or foundNum
        setStatus(string.format("Сеть: блок #%d найден -> ищу #%d", foundNum, msg.index), 0x38BDF8, true)
        restartAll(msg.index, msg.prevHash, msg.difficulty)
        drawUI()
      end
    elseif ev == "gpu_compute_done" then
      local addr, jobId, kind, ok = a, b, c, d
      local key = tostring(addr) .. ":" .. tostring(jobId)
      local entry = pendingJobs[key]
      if entry and kind == "kernel" then
        pendingJobs[key] = nil
        local job = entry.gpu.proxy.getComputeJob(jobId)
        if entry.blockIdx == idx then
          if ok and job and job.result and #job.result > 0 then
            local hit = job.result[1]
            local foundNonce = string.format("%.0f", hit.nonce)
            local res, errSub = requestHost({
              cmd = "SUBMIT_WORK",
              address = wallet.address,
              index = idx,
              nonce = foundNonce
            }, "SUBMIT_RES", 4)
            if res and res.ok then
              balance = res.balance or balance
              chainHeight = res.height or res.index or chainHeight
              setStatus(string.format("+%.2f %s! Блок #%d (%d GPU)", res.reward, occoin.CURRENCY_NAME, res.index, #gpus), 0x4ADE80, true)
              if res.nextIndex and res.nextPrevHash then
                restartAll(res.nextIndex, res.nextPrevHash, res.difficulty)
              else
                local nw = requestHost({ cmd = "GET_WORK" }, "WORK_RES", 3)
                if not nw or not nw.ok then
                  cleanup()
                  setStatus("Ошибка банка при получении блока", 0xEF4444, true)
                  return true
                end
                restartAll(nw.index, nw.prevHash, nw.difficulty)
              end
              drawUI()
            else
              setStatus("Банк отклонил блок #" .. tostring(idx) .. ": " .. tostring(res and res.err or errSub), 0xEF4444, true)
              local nw, errNext = requestHost({ cmd = "GET_WORK" }, "WORK_RES", 3)
              if not nw or not nw.ok then
                cleanup()
                setStatus("Ошибка банка: " .. tostring(errNext or "нет связи"), 0xEF4444, true)
                return true
              end
              restartAll(nw.index, nw.prevHash, nw.difficulty)
              drawUI()
            end
          else
            submitBatch(entry.gpu)
          end
        end
      end
    end
  end
end

local function startMining()
  if not wallet.hostAddr then
    setStatus("Сначала подключитесь к хосту [F].", 0xEF4444, true)
    return
  end
  local work, err = requestHost({ cmd = "GET_WORK" }, "WORK_RES", 3)
  if not work or not work.ok then
    setStatus("Ошибка банка: " .. tostring(err or "нет задания"), 0xEF4444, true)
    return
  end

  local idx = work.index
  local prevHash = work.prevHash
  local diff = work.difficulty or 3

  if startMiningCuda(idx, prevHash, diff) then
    return
  end

  local prefix = string.rep("0", diff)
  local pNonce = 0

  setStatus(string.format("Майнинг блока #%d (DataCard, сложн. %d)... Клавиша = стоп", idx, diff), 0xFACC15)
  drawUI()

  while true do
    for _ = 1, 40 do
      pNonce = pNonce + 1
      local candidate = prevHash .. "|" .. tostring(idx) .. "|" .. wallet.address .. "|" .. tostring(pNonce)
      local hash = occoin.sha256Hex(dataCard, candidate)
      if string.sub(hash, 1, diff) == prefix then
        local res, errSub = requestHost({
          cmd = "SUBMIT_WORK",
          address = wallet.address,
          index = idx,
          nonce = pNonce
        }, "SUBMIT_RES", 4)
        if res and res.ok then
          balance = res.balance or balance
          chainHeight = res.height or res.index or chainHeight
          setStatus(string.format("+%.2f %s! Блок #%d добыт", res.reward, occoin.CURRENCY_NAME, res.index), 0x4ADE80, true)
          if res.nextIndex and res.nextPrevHash then
            idx = res.nextIndex
            prevHash = res.nextPrevHash
            diff = res.difficulty or diff
            prefix = string.rep("0", diff)
            pNonce = 0
          else
            local nextWork = requestHost({ cmd = "GET_WORK" }, "WORK_RES", 3)
            if not nextWork or not nextWork.ok then
              setStatus("Ошибка банка при получении блока", 0xEF4444, true)
              return
            end
            idx = nextWork.index
            prevHash = nextWork.prevHash
            diff = nextWork.difficulty or diff
            prefix = string.rep("0", diff)
            pNonce = 0
          end
          drawUI()
        else
          setStatus("Банк отклонил блок #" .. tostring(idx) .. ": " .. tostring(res and res.err or errSub), 0xEF4444, true)
          local nextWork, errNext = requestHost({ cmd = "GET_WORK" }, "WORK_RES", 3)
          if not nextWork or not nextWork.ok then
            setStatus("Ошибка банка: " .. tostring(errNext or "нет связи"), 0xEF4444, true)
            return
          end
          idx = nextWork.index
          prevHash = nextWork.prevHash
          diff = nextWork.difficulty or diff
          prefix = string.rep("0", diff)
          pNonce = 0
          drawUI()
        end
        break
      end
    end

    -- Обработка событий без блокировки: остановка по клавише и переключение на новый блок по уведомлению сети
    while true do
      local ev, _, a, b, _, raw = event.pull(0)
      if not ev then break end
      if ev == "key_down" then
        setStatus("Майнинг остановлен (хешей: " .. pNonce .. ").", 0x94A3B8, true)
        return
      elseif ev == "modem_message" and b == occoin.PORT and a == wallet.hostAddr then
        local msg = occoin.decodeMsg(raw)
        if msg and msg.cmd == "NEW_BLOCK" and msg.index and msg.prevHash then
          if msg.index > idx then
            local foundNum = msg.index - 1
            idx = msg.index
            prevHash = msg.prevHash
            diff = msg.difficulty or diff
            prefix = string.rep("0", diff)
            chainHeight = msg.height or foundNum
            pNonce = 0
            setStatus(string.format("Сеть: блок #%d найден -> ищу #%d", foundNum, idx), 0x38BDF8, true)
            drawUI()
          end
        end
      end
    end
  end
end

-- Первичное подключение
refreshData()
drawUI()

while true do
  local ev, _, a, b, _, raw = event.pull()
  if ev == "modem_message" and b == occoin.PORT and a == wallet.hostAddr then
    local msg = occoin.decodeMsg(raw)
    if msg and msg.cmd == "NEW_BLOCK" then
      chainHeight = msg.height or chainHeight
      local foundNum = msg.index and (msg.index - 1) or chainHeight
      addLog(string.format("Сеть: блок #%d (%s)", foundNum, tostring(msg.blockType or "NEW")), 0x38BDF8)
      drawUI()
    end
  elseif ev == "key_down" then
    local ch = string.lower(string.char(a > 0 and a < 128 and a or 0))
    if ch == "q" then
      gpu.setBackground(0x000000)
      gpu.setForeground(0xFFFFFF)
      term.clear()
      print("Кошелёк закрыт.")
      break
    elseif ch == "r" then
      refreshData()
      drawUI()
    elseif ch == "f" then
      discoverHost()
      refreshData()
      drawUI()
    elseif ch == "s" then
      sendCoins()
      drawUI()
    elseif ch == "h" then
      activeTab = (activeTab == "HISTORY") and "OVERVIEW" or "HISTORY"
      if activeTab == "HISTORY" then refreshData() end
      drawUI()
    elseif ch == "o" then
      activeTab = "OVERVIEW"
      drawUI()
    elseif ch == "m" then
      startMining()
      drawUI()
    end
  elseif ev == "touch" then
    -- Поддержка кликов по строкам меню на экране
    local y = b
    if activeTab == "OVERVIEW" then
      if y == 12 then refreshData(); drawUI()
      elseif y == 13 then sendCoins(); drawUI()
      elseif y == 14 then activeTab = "HISTORY"; refreshData(); drawUI()
      elseif y == 15 then startMining(); drawUI()
      elseif y == 16 then discoverHost(); refreshData(); drawUI()
      elseif y == 17 then
        gpu.setBackground(0x000000); gpu.setForeground(0xFFFFFF); term.clear(); break
      end
    elseif activeTab == "HISTORY" and y == 10 then
      activeTab = "OVERVIEW"; drawUI()
    end
  end
end
