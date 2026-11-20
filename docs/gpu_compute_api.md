# API GPU-ускорителей / CUDA-ядер (`gpu`)

**OCR Additions** добавляет всем видеокартам и APU из **OpenComputers Rebooted** (`graphicscard1..4`, `apu1..3`, `apucreative` и 4 ядрам `quadgraphicscard`) поддержку **пользовательских параллельных CUDA-ядер** и базовых математических/криптографических примитивов.

У видеокарты **нет** зашитых «готовых кнопок майнинга» — игроки сами пишут логику (для PoW-криптовалюты, подбора, расчётов физики, матриц и т.д.) простым и понятным языком выражений **без ассемблера**, а видеокарта исполняет этот код параллельно в многопоточном пуле с защитой TPS сервера.

---

## Уровни видеокарт и характеристики

| Карта / APU | Уровень (`tier`) | CUDA-ядер (`cudaCores`) | Макс. потоков в запуске (`maxBatchSize`) | Макс. размер массива | Ускорение |
| :--- | :---: | :---: | :---: | :---: | :---: |
| Tier 1 (`graphicscard1`, `apu1`) | `1` | `16` | `16 384` | `16 384` | `1x` |
| Tier 2 (`graphicscard2`, `apu2`, `quadgraphicscard` x4) | `2` | `64` | `131 072` | `65 536` | `4x` |
| Tier 3 (`graphicscard3`, `apu3`) | `3` | `256` | `1 048 576` | `65 536` | `16x` |
| Tier 4 / Creative (`graphicscard4`, `apucreative`) | `4` | `512` | `4 194 304` | `65 536` | `32x` |

> Все параметры уровней (`cudaCores`, `maxBatchSize`, `maxArrayElements`, `pacingMsPer4kOps`) настраиваются в `config/ocradditions-common.toml` в секциях `[gpuTier1]` .. `[gpuTier4]`, а максимальное число возвращаемых строк задаётся через `gpuComputeMaxOutputRows`.

---

## 1. Как писать CUDA-ядро (Kernel DSL)

Ядро — это обычные формулы вида `переменная = выражение`, разделённые переносом строки или `;`. Комментарии: `--`, `//` или `#`.  
Внутри ядра для каждого из `count` параллельных потоков автоматически доступны:
- `id` — номер текущего CUDA-потока от `0` до `count - 1`;
- `count` — общее число потоков в запуске;
- все параметры, переданные в таблице `params` (числа, строки, булевы значения и массивы вида `a[id]`, индексация с `0`).

### Пример: свой PoW-майнер в 3 строки ядра

```lua
local kernel = [[
  nonce = start + id
  h = sha256(sha256(concat(header, nonce)))
  ok = leadingZeroBits(h) >= bits
]]

local kernelId = gpu.compileKernel(kernel)
local jobId = gpu.runKernel(65536, kernelId, {
  header = "coin:block1:",
  start = 0,
  bits = 16
}, {
  where = "ok",
  out = { "nonce", "h" },
  stopOnFirst = true
})
```

### Поддерживаемые операции и функции в ядре

- **Арифметика и логика**: `+`, `-`, `*`, `/`, `%`, `^`, `==`, `!=` / `~=`, `<`, `<=`, `>`, `>=`, `and` / `&&`, `or` / `||`, `not` / `!`, скобки `(...)`, условный выбор `iif(условие, если_да, если_нет)`.
- **Хэш-примитивы (однопроходные)**:
  - `sha256(x)`, `sha512(x)`, `sha3(x)` *(или `sha3_256(x)`)*, `md5(x)`.
  - Для двойного хэша пишите `sha256(sha256(x))`.
- **Помощники для анализа хэшей и строк**:
  - `leadingZeroBits(hex)` *(алиас `zeroBits`)* — сколько нулевых бит в начале hex-строки;
  - `leadingZeros(hex)` *(алиас `zeroChars`)* — сколько символов `'0'` в начале hex-строки;
  - `hexByte(hex, index)`, `hexAt(hex, index)` — байт/ниббл по 0-индексу;
  - `concat(a, b, ...)` *(до 16 аргументов, целые числа склеиваются без `.0`)*;
  - `str(x)`, `num(s)`, `len(s)`, `sub(s, start[, len])`, `startsWith(s, prefix)`, `contains(s, needle)`, `upper(s)`, `lower(s)`, `toHex(n[, width])`.
- **Битовые операции** *(удобно, если игроки придумывают собственный алгоритм майнинга)*:
  - `band(a, b)`, `bor(a, b)`, `bxor(a, b)`, `bnot(a)`, `shl(a, n)`, `shr(a, n)`, `ashr(a, n)`, `rotl32(x, n)`, `rotr32(x, n)`.
- **Математика**:
  - `abs`, `floor`, `ceil`, `round`, `sqrt`, `pow`, `sin`, `cos`, `tan`, `log`, `exp`, `min`, `max`, `clamp(x, lo, hi)`, `lerp(a, b, t)`.

---

## 2. Методы `component.gpu`

### Информация и управление
- `gpu.getComputeInfo(): table` *(direct)* — возвращает `enabled`, `tier`, `cudaCores`, `maxBatchSize`, `maxArrayElements`, `maxOutputRows` (`16384`), `workerThreads`, `dutyCycle`, `energyPerOp`, `compiledKernels`, `activeJobs`, `queuedJobsGlobal`, `completedJobs`, `totalOps`.
- `gpu.compileKernel(source: string): number | (nil, string)` — компилирует код ядра один раз и возвращает `kernelId` для повторных быстрых запусков.
- `gpu.dropKernel(kernelId: number): boolean` — освобождает скомпилированное ядро.
- `gpu.getComputeJob(jobId: number): table | (nil, string)` *(direct)* — состояние и результаты задачи (`id`, `type`, `status`, `progress`, `opsDone`, `totalWork`, `durationMs`, а также `result` / `reduced` / `matchCount` / `threadsExecuted` / `throughput`).
- `gpu.cancelComputeJob(jobId: number): boolean`
- `gpu.cancelAllCompute(): number`

### Запуск параллельного ядра
- `gpu.runKernel(count: number, kernelOrId: string | number[, params: table, options: table]): number | (nil, string)`

Параметры в таблице `options` (все необязательны):
- `where`: имя булевой переменной (собирать только потоки, где она истинна);
- `out`: имя одной переменной (`"x"`) или массив имён (`{ "nonce", "h" }`) — для массива каждая запись будет таблицей `{ id = ..., nonce = ..., h = ... }`;
- `stopOnFirst`: `boolean` (`false`) — мгновенно остановить все CUDA-потоки при первом срабатывании `where`;
- `maxResults`: `number` (по умолчанию `1024`, макс. `16384`);
- `reduce`: `"sum" | "min" | "max" | "count"` — параллельная свёртка результатов в одно число `reduced`.

> Без `where` и `reduce` возвращать массивы результатов можно для `count <= 16384`. Для больших батчей используйте `where` или `reduce`.

### Базовые прямые и матричные операции
- `gpu.hash(algorithm: string, data: string): string | (nil, string)` *(direct)* — один однопроходный хэш (`"sha256"`, `"sha512"`, `"sha3_256"`, `"md5"`).
- `gpu.vectorOp(op: string, a: table, b: table | number): number` — поэлементные операции (`"add"`, `"sub"`, `"mul"`, `"div"`, `"pow"`, `"min"`, `"max"`, `"dot"`).
- `gpu.matrixMul(aRows, aCols, bCols, aFlat, bFlat): number` — параллельное перемножение матриц.

---

## 3. Сигнал `gpu_compute_done`

> В OpenComputers **`ev[2]` всегда равен адресу компонента GPU**.

- Для `runKernel` без `reduce`:
  `ev[1]="gpu_compute_done"`, `ev[2]=gpuAddress`, `ev[3]=jobId`, `ev[4]="kernel"`, `ev[5]=true`, `ev[6]=matchCount`, `ev[7]=threadsExecuted`, `ev[8]=durationMs`
- Для `runKernel` с `reduce`:
  `ev[5]=true`, `ev[6]=reducedValue`, `ev[7]=matchCount`, `ev[8]=threadsExecuted`, `ev[9]=durationMs`
- При отмене или ошибке:
  `ev[5]=false`, `ev[6]=reason`
- Сами данные результата читаются через `gpu.getComputeJob(jobId).result` (или `.reduced`).
