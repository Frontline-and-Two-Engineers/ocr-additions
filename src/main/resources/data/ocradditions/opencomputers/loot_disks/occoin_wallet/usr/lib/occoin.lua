-- oc-crypto/lib/occoin.lua
-- Общая библиотека для криптовалюты OCCoin на базе OpenComputers
-- Использует только стандартные компоненты OpenComputers: Data Card (Tier 3), Modem, GPU

local component = require("component")
local serialization = require("serialization")

local occoin = {}

occoin.PORT = 4422
occoin.PROTOCOL_VERSION = 1
occoin.CURRENCY_NAME = "OCC"
occoin.DEFAULT_REWARD = 10
occoin.DEFAULT_DIFFICULTY = 3 -- количество ведущих нулей в hex SHA-256

-- Проверка наличия Data Card Tier 3
function occoin.getDataCard()
  if not component.isAvailable("data") then
    return nil, "Data Card не найдена! Требуется Data Card Tier 3."
  end
  local data = component.data
  if not data.ecdsa or not data.generateKeyPair or not data.sha256 then
    return nil, "Требуется Data Card Tier 3 (с поддержкой ECDSA и SHA-256)."
  end
  return data
end

-- Проверка наличия беспроводной сетевой карты
function occoin.getWirelessModem()
  if not component.isAvailable("modem") then
    return nil, "Сетевая карта (Modem) не найдена!"
  end
  local modem = component.modem
  if not modem.isWireless() then
    return nil, "Требуется беспроводная сетевая карта (Wireless Network Card)!"
  end
  return modem
end

-- Конвертация бинарной строки в hex
function occoin.toHex(bin)
  return (bin:gsub(".", function(c)
    return string.format("%02x", string.byte(c))
  end))
end

-- Конвертация hex в бинарную строку
function occoin.fromHex(hex)
  return (hex:gsub("..", function(cc)
    return string.char(tonumber(cc, 16))
  end))
end

-- Вычисление SHA-256 в hex
function occoin.sha256Hex(dataCard, str)
  return occoin.toHex(dataCard.sha256(str))
end

-- Генерация адреса кошелька из сырых байтов публичного ключа
function occoin.addressFromPubKeyRaw(dataCard, pubRaw)
  if type(pubRaw) ~= "string" or #pubRaw == 0 then
    error("addressFromPubKeyRaw: pubRaw is nil or empty")
  end
  local hashHex = occoin.toHex(dataCard.sha256(pubRaw))
  return "OCC-" .. string.sub(hashHex, 1, 24)
end

-- Генерация адреса кошелька из публичного ключа (Hex)
-- Формат: OCC-<первые 24 символа hex(sha256(pubKeyBytes))>
function occoin.addressFromPubKeyB64(dataCard, pubHex)
  if type(pubHex) ~= "string" or #pubHex == 0 then
    error("addressFromPubKeyB64: pubKey is nil or empty")
  end
  local raw = occoin.fromHex(pubHex)
  if type(raw) ~= "string" or #raw == 0 then
    error("addressFromPubKeyB64: fromHex failed")
  end
  return occoin.addressFromPubKeyRaw(dataCard, raw)
end

-- Генерация новой пары ключей ECDSA (256 бит)
function occoin.generateWalletKeys(dataCard)
  local pubKey, privKey = dataCard.generateKeyPair(256)
  if not pubKey or not privKey then
    error("generateKeyPair(256) returned nil (check energy / Data Card Tier 3)")
  end
  local pubRaw = pubKey.serialize()
  local privRaw = privKey.serialize()
  if type(pubRaw) ~= "string" or type(privRaw) ~= "string" then
    error("key.serialize() did not return string")
  end
  local pubHex = occoin.toHex(pubRaw)
  local privHex = occoin.toHex(privRaw)
  local address = occoin.addressFromPubKeyRaw(dataCard, pubRaw)
  return {
    address = address,
    pubB64 = pubHex,
    privB64 = privHex
  }
end

-- Формирование канонической строки транзакции для подписи
function occoin.txPayload(fromAddr, toAddr, amount, nonce, memo)
  return table.concat({
    tostring(fromAddr),
    tostring(toAddr),
    string.format("%.4f", tonumber(amount) or 0),
    tostring(math.floor(tonumber(nonce) or 0)),
    tostring(memo or "")
  }, "|")
end

-- Подпись транзакции приватным ключом (возвращает Hex подпись)
function occoin.signTx(dataCard, privHex, fromAddr, toAddr, amount, nonce, memo)
  local okKey, privKey = pcall(dataCard.deserializeKey, occoin.fromHex(privHex), "ec-private")
  if not okKey or not privKey then
    return nil, "Ошибка десериализации приватного ключа"
  end
  local payload = occoin.txPayload(fromAddr, toAddr, amount, nonce, memo)
  local okSig, sigRaw = pcall(dataCard.ecdsa, payload, privKey)
  if not okSig or not sigRaw then
    return nil, "Ошибка создания подписи ECDSA"
  end
  return occoin.toHex(sigRaw)
end

-- Проверка подписи транзакции публичным ключом
function occoin.verifyTx(dataCard, pubHex, sigHex, fromAddr, toAddr, amount, nonce, memo)
  if type(pubHex) ~= "string" or type(sigHex) ~= "string" then
    return false, "Неверный формат ключа или подписи"
  end
  local okAddr, expectedAddr = pcall(occoin.addressFromPubKeyB64, dataCard, pubHex)
  if not okAddr or expectedAddr ~= fromAddr then
    return false, "Адрес отправителя не соответствует публичному ключу"
  end
  local okKey, pubKey = pcall(dataCard.deserializeKey, occoin.fromHex(pubHex), "ec-public")
  if not okKey or not pubKey then
    return false, "Некорректный публичный ключ"
  end
  local okDec, sigRaw = pcall(occoin.fromHex, sigHex)
  if not okDec or not sigRaw then
    return false, "Некорректная подпись"
  end
  local payload = occoin.txPayload(fromAddr, toAddr, amount, nonce, memo)
  local okVer, valid = pcall(dataCard.ecdsa, payload, pubKey, sigRaw)
  if not okVer or not valid then
    return false, "Недействительная цифровая подпись ECDSA"
  end
  return true
end

-- Сериализация сообщения для отправки по модему
function occoin.encodeMsg(tbl)
  tbl.v = occoin.PROTOCOL_VERSION
  return serialization.serialize(tbl)
end

-- Десериализация сообщения из модема
function occoin.decodeMsg(raw)
  if type(raw) ~= "string" or #raw > 8192 then
    return nil
  end
  local ok, tbl = pcall(serialization.unserialize, raw)
  if not ok or type(tbl) ~= "table" then
    return nil
  end
  return tbl
end

-- Безопасное округление суммы до 4 знаков после запятой
function occoin.roundAmount(n)
  n = tonumber(n)
  if not n then return nil end
  return math.floor(n * 10000 + 0.5) / 10000
end

-- Вспомогательные функции для работы с GPU
occoin.ui = {}

function occoin.ui.box(gpu, x, y, w, h, bg, fg, title)
  gpu.setBackground(bg)
  gpu.setForeground(fg)
  gpu.fill(x, y, w, h, " ")
  if title then
    gpu.set(x + 2, y, " " .. title .. " ")
  end
end

function occoin.ui.text(gpu, x, y, str, bg, fg)
  if bg then gpu.setBackground(bg) end
  if fg then gpu.setForeground(fg) end
  gpu.set(x, y, str)
end

return occoin
