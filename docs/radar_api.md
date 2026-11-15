# API компонента `radar` (Радары Tier 1–3)

Имя компонента в OpenComputers Rebooted: **`radar`** (для всех трёх блоков `ocradditions:radar_tier_1`, `ocradditions:radar_tier_2`, `ocradditions:radar_tier_3`).

```lua
local component = require("component")
local radar = component.radar
```

---

## Характеристики уровней

| Уровень | Блок | Макс. радиус | Поля каждой записи в результате `scan` |
| :---: | :--- | :---: | :--- |
| **1** | `ocradditions:radar_tier_1` | `64` | `type` |
| **2** | `ocradditions:radar_tier_2` | `128` | `type`, `distance` |
| **3** | `ocradditions:radar_tier_3` | `256` | `type`, `name`, `isPlayer`, `distance`, `x`, `y`, `z`, `uuid` |

- Поиск выполняется в сфере радиуса `r` от центра блока радара (`worldPosition + 0.5`).
- Результаты **отсортированы по возрастанию дистанции** от радара.
- За одно сканирование возвращается максимум **`128` ближайших сущностей** (`MAX_RESULTS`).
- По умолчанию `radarScanEnergyCost = 0.0` (можно включить расход энергии сети OC в конфиге).

---

## Методы

### `radar.getTier(): number` *(direct)*
Возвращает уровень радара (`1`, `2` или `3`).

### `radar.getMaxRange(): number` *(direct)*
Возвращает максимальный радиус сканирования в блоках (`64`, `128` или `256`).

### `radar.isRelativeCoordinates(): boolean` *(direct)*
Возвращает `true`, если координаты Tier 3 вычисляются относительно блока радара.

### `radar.scan([radius: number]): table | (nil, string)`
*(Алиас: `radar.getEntities([radius])`)*

Сканирует сущности в указанном радиусе.
- Если `radius` не передан или `<= 0`, используется `getMaxRange()`.
- Если `radius` больше максимального для данного уровня, он автоматически ограничивается до `getMaxRange()`.
- Если в конфиге задан `radarScanEnergyCost > 0` и энергии в сети недостаточно, возвращает `nil, "not enough energy"`.

#### Поля возвращаемой таблицы

Массив (1-индексированный в Lua), где каждый элемент — таблица:
- `type`: `string` — ID типа сущности, например `"minecraft:zombie"`, `"minecraft:player"`, `"minecraft:item"`.
- `distance`: `number` *(Tier 2+)* — расстояние в блоках от центра радара.
- `name`: `string` *(Tier 3)* — никнейм игрока (`GameProfile.getName()`) для `Player`, либо отображаемое имя сущности.
- `isPlayer`: `boolean` *(Tier 3)* — `true`, если сущность является игроком.
- `x`, `y`, `z`: `number` *(Tier 3)* — координаты сущности относительно блока радара (`e.pos - radarBlockPos`, либо мировые, если `relativeCoordinates = false`).
- `uuid`: `string` *(Tier 3)* — UUID сущности (удобно передавать напрямую в `glasses_bridge.addEntityMarker(...)`).
