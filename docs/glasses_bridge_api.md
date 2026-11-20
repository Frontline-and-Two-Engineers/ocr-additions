# API компонента `glasses_bridge` (Умные очки)

Имя компонента в OpenComputers Rebooted: **`glasses_bridge`**.

```lua
local component = require("component")
local bridge = component.glasses_bridge
```

---

## 1. Ограничения и форматы

- Максимум виджетов на слой (глобальный и на каждого игрока): **`512`** (`MAX_WIDGETS_PER_LAYER`).
- Максимум точек в одном графике: **`256`** (`MAX_GRAPH_POINTS`). Если передано больше — сохраняются последние 256 точек.
- Максимум событий в внутренней очереди (`pollEvent` / `getEvents`): **`256`** (`MAX_QUEUED_EVENTS`).
- **Формат цвета**: число `0xAARRGGBB` (например `0xFF00E5FF`) или строка `"#AARRGGBB"` / `"0xAARRGGBB"`.
- **Якоря экрана (`Anchor`)**:
  `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `CENTER_LEFT`, `CENTER`, `CENTER_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT` (регистр не важен, допускаются `-` и пробел, например `"top-right"`).

---

## 2. Общие методы, чат и телеметрия

### `bridge.getBridgeId(): string` *(direct)*
Возвращает UUID данного блока `glasses_bridge`.

### `bridge.isRelativeCoordinates(): boolean` *(direct)*
Возвращает `true`, если координаты моста работают в относительном режиме (`0,0,0` = блок моста).

### `bridge.getChatPrefix(): string` *(direct)*
Возвращает текущий префикс чат-команд для Tier 2+ (по умолчанию `"$$"`).

### `bridge.setChatPrefix(prefix: string): string`
Задаёт новый префикс чат-команд (1..16 непробельных символов) и сохраняет его в NBT блока. Возвращает предыдущий префикс.

### `bridge.getConnectedPlayers(): table`
Возвращает массив (индексация с `1`) таблиц подключённых игроков в очках. Поля каждого элемента:
- `uuid`: `string`
- `name`: `string`
- `tier`: `number` (`1`, `2` или `3`)
- `dimension`: `string` (например `"minecraft:overworld"`)
- `x`, `y`, `z`: `number` *(только при `tier >= 3`; относительно моста при `relativeCoordinates = true`)*

### `bridge.getPlayerStats(playerUuid: string): table | (nil, string)` *(требует Tier 3)*
Возвращает полную телеметрию носителя очков Tier 3. При ошибке — `nil, reason` (`"player not connected"` или `"tier 3 smart glasses required"`).

Поля таблицы:
- `uuid`, `name`, `tier`
- `hp`, `maxHp`
- `food`, `saturation`
- `armor`
- `air`, `maxAir`
- `xpLevel`, `xpProgress`
- `yaw`, `pitch`
- `x`, `y`, `z` *(относительно моста при `relativeCoordinates = true`)*
- `dimension`, `biome`, `dayTime`
- `isSprinting`, `isSneaking`, `isInWater`, `isOnGround`
- `energy`, `maxEnergy` *(запас FE в очках)*
- `screenWidth`, `screenHeight` *(масштабируемое разрешение GUI клиента)*

### `bridge.getScreenSize(playerUuid: string): (number, number) | (nil, string)` *(direct)*
Возвращает текущие `screenWidth, screenHeight` клиента носителя.

### `bridge.playSound(playerUuid: string, soundId: string[, volume: number, pitch: number]): boolean[, string]`
Воспроизводит приватный звуковой сигнал только в очках указанного игрока (например `"minecraft:entity.experience_orb.pickup"`). `volume` (`0.0..2.0`, по умолч. `1.0`), `pitch` (`0.5..2.0`, по умолч. `1.0`).

### `bridge.broadcastSound(soundId: string[, volume: number, pitch: number]): number`
Воспроизводит звуковой сигнал на всех подключённых к мосту очках. Возвращает число получателей.

### `bridge.sendMessage(playerUuid: string, message: string): boolean[, string]` *(требует Tier 2+)*
Отправляет сообщение в чат конкретному подключённому игроку с префиксом `[Glasses]`.

### `bridge.broadcastMessage(message: string): number` *(требует Tier 2+)*
Отправляет сообщение всем подключённым игрокам в очках Tier 2+. Возвращает количество получателей.

### `bridge.pollEvent(): table | nil` *(direct)*
Извлекает и возвращает самое старое событие из очереди (или `nil`, если очередь пуста). В отличие от `computer.pullSignal`, возвращает таблицу с именованными полями (см. раздел 5).

### `bridge.getEvents(): table` *(direct)*
Извлекает и возвращает массив всех накопленных событий, очищая очередь.

---

## 3. Глобальный слой (виден всем подключённым очкам)

### Управление слоем
- `bridge.clear(): boolean` — удаляет все глобальные виджеты.
- `bridge.removeWidget(id: string): boolean` — удаляет глобальный виджет по `id`.
- `bridge.setAnchor(id: string, anchor: string): boolean[, string]` — меняет якорь привязки виджета.

### 2D HUD (Tier 1+)
- `bridge.addRect(id, x, y, width, height[, color]): boolean`
- `bridge.addText(id, x, y, text[, color, scale]): boolean` *(по умолчанию `color = 0xFFFFFFFF`, `scale = 1.0`)*
- `bridge.addProgressBar(id, x, y, width, height, value, max[, fgColor, bgColor]): boolean`
- `bridge.addItemIcon(id, x, y, itemId[, scale]): boolean` *(`itemId` вида `"minecraft:diamond"`)*

### Интерактивность и графики (Tier 2+)
- `bridge.addGraph(id, x, y, width, height, values[, fgColor, bgColor]): boolean`
  - `values` — Lua-массив чисел (`{ 10, 25, 40, ... }`, до 256 точек). График автоматически масштабируется по минимуму/максимуму точек.
- `bridge.setClickable(id, clickable: boolean): boolean[, string]`
  - Делает 2D-виджет кликабельным в режиме курсора (клавиша `H`).

### 3D AR-оверлей (Tier 3)
- `bridge.addWorldMarker(id, x, y, z, label[, color]): boolean` — 3D-метка с текстом в мире (координаты относительно моста при `relativeCoordinates = true`).
- `bridge.addWorldLine(id, x1, y1, z1, x2, y2, z2[, color]): boolean` — 3D-линия между двумя точками.
- `bridge.addWorldBox(id, x1, y1, z1, x2, y2, z2[, label, color]): boolean` — 3D-рамка (wireframe куб/параллелепипед) между двумя углами.
- `bridge.addEntityMarker(id, entityUuid, label[, color]): boolean` — 3D-рамка и подпись, автоматически следующие за сущностью с указанным `entityUuid`.

---

## 4. Персональный слой (виден конкретному игроку)

Имеет те же параметры, но первым аргументом передаётся `playerUuid: string`. Виджеты персонального слоя перекрывают глобальный виджет с тем же `id` для данного игрока.

- `bridge.clearPlayer(playerUuid)`
- `bridge.removePlayerWidget(playerUuid, id)`
- `bridge.setPlayerAnchor(playerUuid, id, anchor)`
- `bridge.addPlayerRect(playerUuid, id, x, y, width, height[, color])`
- `bridge.addPlayerText(playerUuid, id, x, y, text[, color, scale])`
- `bridge.addPlayerProgressBar(playerUuid, id, x, y, width, height, value, max[, fgColor, bgColor])`
- `bridge.addPlayerItemIcon(playerUuid, id, x, y, itemId[, scale])`
- `bridge.addPlayerGraph(playerUuid, id, x, y, width, height, values[, fgColor, bgColor])` *(проверяет Tier 2+ игрока)*
- `bridge.setPlayerClickable(playerUuid, id, clickable)` *(проверяет Tier 2+ игрока)*
- `bridge.addPlayerWorldMarker(playerUuid, id, x, y, z, label[, color])` *(проверяет Tier 3 игрока)*
- `bridge.addPlayerWorldLine(playerUuid, id, x1, y1, z1, x2, y2, z2[, color])` *(проверяет Tier 3 игрока)*
- `bridge.addPlayerWorldBox(playerUuid, id, x1, y1, z1, x2, y2, z2[, label, color])` *(проверяет Tier 3 игрока)*
- `bridge.addPlayerEntityMarker(playerUuid, id, entityUuid, label[, color])` *(алиас: `addEntityPlayerMarker`)*

---

## 5. Сигналы (`computer.signal` / `computer.pullSignal`) и очередь событий

> **Важно про `computer.pullSignal()` в OpenComputers!**  
> OpenComputers всегда вставляет **адрес компонента-источника в `ev[2]`**:
> `local name, address, arg1, arg2, ... = computer.pullSignal()`

### Сигналы `computer.pullSignal`

| Сигнал (`ev[1]`) | Аргументы начиная с `ev[2]` |
| :--- | :--- |
| `glasses_player_connected` | `address, playerUuid, playerName, tier` |
| `glasses_player_disconnected` | `address, playerUuid, playerName` |
| `glasses_screen_resized` | `address, playerUuid, playerName, screenWidth, screenHeight` |
| `glasses_chat_command` *(Tier 2+)* | `address, playerUuid, playerName, command, prefix` |
| `glasses_hud_click` *(Tier 2+)* | `address, playerUuid, playerName, widgetId, button` |
| `glasses_gaze` (`targetType == "block"`, Tier 3) | `address, playerUuid, playerName, "block", hitX, hitY, hitZ, blockX, blockY, blockZ, face, blockId` |
| `glasses_gaze` (`targetType == "entity"`, Tier 3) | `address, playerUuid, playerName, "entity", hitX, hitY, hitZ, entityUuid, entityName, entityType` |
| `glasses_gaze` (`targetType == "miss"`, Tier 3) | `address, playerUuid, playerName, "miss", hitX, hitY, hitZ` |

### События очереди (`bridge.pollEvent()` / `bridge.getEvents()`)

Те же события, но в виде таблицы-словаря:
- общие поля: `event`, `playerUuid`, `playerName`
- дополнительно:
  - `glasses_player_connected`: `tier`
  - `glasses_chat_command`: `command`, `prefix`
  - `glasses_hud_click`: `widgetId`, `button`
  - `glasses_gaze`: `targetType` (`"block"` | `"entity"` | `"miss"`), `hitX`, `hitY`, `hitZ`; для `"block"` — `blockX`, `blockY`, `blockZ`, `face`, `blockId`; для `"entity"` — `entityUuid`, `entityName`, `entityType`.
