# Contract 2 — SimPlot Android v0.3.2 六个反馈问题修复契约（Designer 产出）

> 版本：v1.0（最终）｜ 设计者：Designer（dev-pipeline 第 2 轮）
> 项目：/home/node/.openclaw/workspace/simplot-android（v0.3.2，Kotlin/Compose MVVM，compose BOM 2024.09.03 → compose-ui 1.7.0）
> 构建命令（勿改 gradle.properties）：
> `export LANG=C.utf8 LC_ALL=C.utf8 JAVA_HOME=/home/node/.openclaw/workspace/toolchain/jdk-17.0.2 ANDROID_HOME=/home/node/.openclaw/workspace/toolchain/android-sdk && ./gradlew testDebugUnitTest --no-daemon --max-workers=1`
> 权威坐标参考：`/home/node/.openclaw/workspace/BH2VOQ-ATG/simplot/scripts/scn_tool.py`（calc_offset/offset_yards/detect_state）

## 0. 问题总览（含验证结论）

| # | 反馈 | 根因（已验证程度） | 修复归属 |
|---|------|--------------------|----------|
| ① | 示例初设坐标完全不对 | **确认**：CameraMath 无 Y 翻转（世界 Y 北为正 vs 屏幕 Y 向下）→ 场景垂直镜像 | CameraMath + MapRenderer |
| ⑤ | 航向显示与航迹奇怪 | **确认**：与①同根因。航迹经 worldToScreen 镜像，而速度领导线用屏幕空间北向上 → 领导线指向与航迹方向矛盾 | 同①（领导线/航迹本身无需改） |
| ② | 没按 Do 前不能按 Undo/Next | **确认**：TurnState 状态机存在但未用于门禁（按钮无 enabled、VM 无防御） | TurnState + TurnControlBar + GameViewModel |
| ③ | Do/Undo/Next 可无限按 | **确认**：同上；且 TurnState.undo 在 DO_BEFORE 会无条件回退时间（危险路径） | 同上 |
| ④ | Do 后单位不实时移动（拖动才刷新） | **半确认**：revision→重组→新 draw lambda 链路中「重绘」环节失效。证据见 §4.4 | SceneCanvas（快照 epoch 触发重绘） |
| ⑥ | 标注字体还是小 | **确认（定量）**：拉普拉塔场景 fitBounds 后 zoom≈0.00116（1080 宽屏）→ textSize=8.5f 撞下限 8f；720 宽屏 zoom≈0.0007 → 8f | SceneCanvas + UnitRenderer（纯函数） |

## 1. 目标与验收

### ①+⑤ 世界↔屏幕 Y 翻转（同一根因，一个修复）
**目标**：世界 Y 向北为正（0°=北顺时针，与桌面版 scn_tool.py 一致），屏幕 Y 向下为正 → 渲染层做翻转，北在上。
**验收（自动）**：
- [ ] CameraMathTest 新增：世界 (0, 100000)（北 1 海里）→ sy < H/2（屏幕上方）
- [ ] CameraMathTest 新增：screenToWorld(500, 300)（中心上方）→ wy > centerY；roundtrip 仍成立
- [ ] CameraMathTest 新增：pan(0, +100)（内容下拉）→ centerY **增加**（符号随翻转调整）
- [ ] 既有 5 个 CameraMath 用例仍通过（中心对称，不依赖翻转；`zoom at anchor` 因 worldToScreen/screenToWorld 保持互逆而继续成立）

**验收（手动）**：
- [ ] 示例场景：S002（Y=24687，位于 S001 北）显示在 S001 **上方**（当前版本在下方）
- [ ] 航向 0° 单位的速度领导线指向屏幕上方；航向 90° 指向右侧
- [ ] 单位/轨迹/多边形/地图贴图不错位（同一世界坐标的渲染点一致）
- [ ] 拖动方向符合直觉：内容随手指移动；捏合缩放锚点不漂移
- [ ] 测量线：线的屏幕方向与标签方位角一致（翻转后间接修复）

### ②+③ Do/Undo/Next 门禁
**目标**：按 TurnState 状态机门禁按钮 + ViewModel 防御；非法操作不产生任何副作用。
**验收**：
- [ ] 单测：`TurnState.canDo/canUndo/canNext` 矩阵 = { DO_BEFORE: Do✓, Undo✗, Next✗；DO_AFTER: Do✗, Undo✓, Next✓；DO_NEXT: Do✓, Undo✗, Next✗ }
- [ ] 单测：DO→detect=DO_AFTER→Next→detect=DO_NEXT→Do→detect=DO_AFTER 闭环
- [ ] 单测（危险路径文档化）：直接调 TurnState.undo 于 DO_BEFORE 会回退时间——证明 VM 门禁的必要性
- [ ] 手动：DO_BEFORE 时 Undo/Next 灰；DO_AFTER 时 Do 灰；无限连点 Do 不连推回合；无限连点 Undo 不回溯到初始之前；非法点击弹 toast 提示

### ④ Do 后即时刷新
**目标**：按 Do 后单位图标/标签立即移动到新位置，无需任何手势。
**验收（手动，设备/模拟器）**：
- [ ] 按 Do → 单位立即出现在新位置（无拖动）
- [ ] 时间行（文本）与画布（绘制）同步刷新
- [ ] 编辑单位、复制、护航队、Undo 等 revision++ 操作同样即时刷新（回归）

### ⑥ 标注字号
**目标**：默认视图可读（≥12f），放大显著增大（≤40f），锚点偏移规则不变。
**验收**：
- [ ] 单测：`labelTextSize(zoom)`：zoom=0.0007→12f；0.0015→16f；0.05→40f（含 clamp 边界）
- [ ] 手动：拉普拉塔场景默认视图标签 ≥12f 可读；放大后明显增大；不溢出屏幕、不遮挡符号

## 2. 非目标
- 不改存档文件格式/坐标字段值；不迁移/重写存档数据
- 不改世界坐标语义（Y 北为正保持与桌面版/CoordUtil/MovementEngine 一致——**只改渲染层**）
- 不做航迹历史持久化；Undo 语义维持「撤销最近一次 Do」（不含跨回合任意回退）
- 不把 Unit 字段改为 Compose 快照状态、不把 MovementEngine 改成不可变/事件流（④ 用画布层触发解决）
- 不重构手势架构；不引入新依赖；不改 gradle.properties / build 脚本
- 不重写 TurnState 状态机语义（仅加纯函数门禁 + VM 防御）
- 问题⑤ 只修渲染表现，不改航迹记录逻辑（记录本身经验证正确）

## 3. 约束
- 构建命令见顶部；`gradle.properties` 不可改
- 存档兼容：`ScenarioFile/Unit/TimeState` 字段语义不变、字段值零改动；`@SerializedName` 不动
- 坐标约定以桌面版 `scn_tool.py` 为权威（`calc_offset`: dx=d·sin(b)，dy=d·cos(b)；注释「罗盘方位(0=北顺时针, Y 向北为正)」；`detect_state` 与 TurnState.detect 一致）
- 现有 56 个测试全绿（CameraMathTest 5 个按 §1 增补而非改坏；EngineTest 的 TurnState 用例不得因门禁改动而失效）
- 代码风格沿用项目现状（Compose + 纯 Kotlin 可单测部分抽离）

## 4. 技术方案

### 4.1 ①⑤ 根因确认（已验证）
- `CameraMath.worldToScreen`：`sy = ((wy - centerY) * zoom) + H/2` —— 无翻转。世界 Y 大（北）→ 屏幕 sy 大（下）→ **垂直镜像**。`screenToWorld` 同样无翻转。
- 桌面版 `scn_tool.py` `calc_offset`：`(d·sin, d·cos)` + 注释「Y 向北为正」；Android `CoordUtil.offsetNm/bearingDeg`（`atan2(dx, dy)`）与之完全一致 → 世界层正确，问题纯在渲染层。
- `MapDataParser` L116：`mapWorldMinY = (boundaryTop - boundaryHeight) * 10`（南边界为 minY）→ 解析层也是北在上语义。
- 症状印证：冰海巨兽 S001(0,0)、S002(0,24687)（北）——S002 当前画在 S001 **下方**。

### 4.2 ①⑤ 修复思路与触碰点
**核心改动 `render/CameraMath.kt`（3 个函数）**：
```kotlin
// worldToScreen：sy = H/2 - (wy - centerY) * zoom
val sy = canvasH / 2f - ((wy - centerY) * zoom)
// screenToWorld：wy = ((H/2 - sy) / zoom) + centerY
val wy = ((canvasH / 2f - sy) / zoom).roundToLong() + centerY
// pan：Y 分量符号翻转（内容下拉 deltaSy>0 → 中心 Y 增大）
(centerX - (deltaSx / zoom).roundToLong()) to (centerY + (deltaSy / zoom).roundToLong())
```
- `zoomAt`/`fitBounds` **不改**：zoomAt 仅经 screenToWorld 求锚点世界坐标，worldToScreen/screenToWorld 保持互逆即锚点不动；fitBounds 对称。
- `Camera.kt` **不改**（纯状态壳）。

**受翻转影响的调用点清单（逐一验证结论）**：

| 位置 | 现状 | 结论 |
|------|------|------|
| `MapRenderer.drawBitmap` L109（boundary 分支）`worldToScreen(mapWorldMinX, mapWorldMinY + worldH)` | 取北边为左上角 | **无需改**：翻转后北边（大 Y）→ sy 小（屏幕上方），正是北向贴图左上角；screenH 向下为正，天然正确 |
| `MapRenderer.drawBitmap` L126（txt 格式分支）`worldToScreen(mapWorldMinX, mapWorldMinY)` | 取南边为左上角 | **需改**：改为 `mapWorldMinY + mapWorldH`（北边）作左上角，与 boundary 分支一致 |
| `MapRenderer.drawPolygons` 边界框（~L242-247）`RectF(x0,y0,x1,y1)` 来自 (minX,minY) 与 (maxX,maxY) | 翻转后 top>bottom 倒置 | **需改**：改为 `worldToScreen(minX, minY + h)` 得左上角 + `(w·zoom, h·zoom)` 尺寸画矩形（与 drawBitmap 同构） |
| `MapRenderer.drawGrid` | topLeft/bottomRight 均经 screenToWorld+worldToScreen | 无需改（自洽） |
| `MapRenderer.screenPath`/水域名/国名/城市/标注 | 逐点 worldToScreen | 无需改（自洽） |
| `SceneCanvas` hitTest / 测量线起终点 / 单位/回放绘制 / 轨迹 TrackRenderer / 弧 ArcRenderer | 全部经 worldToScreen 或屏幕空间距离 | 无需改（自洽）；测量线方位标签与屏幕方向在翻转后**自动一致**（间接修复） |
| `UnitRenderer.draw` 速度领导线 `lx=sx+len·sin, ly=sy-len·cos` | 已是屏幕空间北向上 | 无需改——翻转后与世界航迹方向一致（⑤ 的直接修复点） |
| `drawScaleBar` | 纯屏幕空间 | 无需改 |

### 4.3 ②③ 根因确认与修复
**确认**：
- `TurnState.detect` 正确（与桌面版 `detect_state` 一致）；`advanceTime`（Do：仅推进 PositionTime，Phase=2）、`confirmNext`（TurnTime 追上 + Turns 追加 + Phase=0）、`undo`（回退时间 + 恢复快照）语义齐备。
- `TurnControlBar` 三按钮**无 enabled**；`GameViewModel.doTurn/undo/next` 无条件执行。
- **危险路径确认**：DO_BEFORE 下直接调 `TurnState.undo` 会把 PositionTime 回退一个回合时长并清空快照 → 必须拦截（按钮灰 + VM 防御双保险）。

**修复**：
1. `engine/TurnState.kt` 新增纯函数（可单测）：
```kotlin
fun canDo(state: State) = state != State.DO_AFTER
fun canUndo(state: State) = state == State.DO_AFTER
fun canNext(state: State) = state == State.DO_AFTER
```
2. `ui/components/TurnControlBar.kt`：`Button(enabled = ...)` —— Do: `canDo(state)`；Undo: `canUndo(state)`；Next: `canNext(state)`（state 已由 `TurnState.detect(file)` 计算，复用）。
3. `ui/GameViewModel.kt` 三方法防御（非法 → toast + return，不改任何状态）：
```kotlin
fun doTurn() { val f = file ?: return
    if (!TurnState.canDo(TurnState.detect(f))) { toast("当前状态不可 Do（请先 Undo 或 Next）"); return }
    ... }
fun undo() { val f = file ?: return
    if (!TurnState.canUndo(TurnState.detect(f))) { toast("当前状态不可 Undo"); return }
    ... }
fun next() { val f = file ?: return
    if (!TurnState.canNext(TurnState.detect(f))) { toast("请先 Do 再 Next 确认"); return }
    ... }
```
4. `TurnState.advanceTime/confirmNext/undo` 本体**不改**（保持纯引擎语义；门禁在 VM 层，避免污染引擎可测性）。

### 4.4 ④ 根因分析（半确认）与修复
**分析**（代码走读 + CHANGELOG 佐证）：
- 链路本应：`revision++`（快照写）→ MainScreen 重组（读 `vm.revision`）→ `SceneCanvas(tick=新值)` 重组（参数变化不可跳过）→ `Canvas` content lambda 新实例 → `drawBehind` 新元素 → 节点更新 → 重绘。
- **失效点证据**：① 时间行（Text，组合阶段读 `file.time` 普通字段，靠 tick 参数变化强制重组）**会刷新**；② 画布（draw 阶段读 `file.units` 普通字段，**不注册快照依赖**）**不刷新**；③ 拖动/缩放有效，因为 draw 内读 `camera` 快照状态 → 说明「draw 内快照读 → 失效」机制正常。→ 结论：**tick 参数变化触发的重组没有带动 draw 失效**（compose-ui 1.7.0 的 drawBehind 节点更新未触发 invalidate，或 lambda 复用），与旧 bug「普通 var 不触发重绘」同类。
- 修复不依赖对该失效点的 100% 定位，采用**画布内显式快照读**方案（保证失效）：

`ui/components/SceneCanvas.kt`：
```kotlin
var drawEpoch by remember { mutableIntStateOf(0) }
LaunchedEffect(tick) { drawEpoch = tick }   // tick 变化 → 快照写
Canvas(modifier = ...) {
    @Suppress("UNUSED_VARIABLE") val epoch = drawEpoch  // draw 阶段快照读 → epoch 变化必重绘
    ...原有绘制...
}
```
- 移除 `@Suppress("UNUSED_EXPRESSION") tick` 裸读，改为真实机制（tick 参数仍保留，驱动重组与 LaunchedEffect）。
- 该方案同时覆盖 UnitEditSheet/复制/护航队/Undo 等一切 revision++ 操作（回归范围见 §1 ④ 验收）。
- **编码者需在设备上验证**：修复前按 Do 单位不动；修复后立即移动。若修复前即刷新（说明反馈基于旧版本），本改动作为双保险保留（无副作用）。

### 4.5 ⑥ 根因确认（定量）与修复
**确认**：`SceneCanvas.drawUnitLabel`：`textSize = (11f * (zoom / 0.0015f)).coerceIn(8f, 28f)`，`BASE_ZOOM = 0.0015f`。拉普拉塔河口 fitBounds（1080 宽屏，padding 80）：zoomX=(1080-160)/793420≈0.00116 → textSize≈8.5f→8f；720 宽屏 → zoom≈0.0007 → 8f。→ 撞下限，不可读。
**修复**：
1. 纯函数抽到 `render/UnitRenderer.kt`（object，纯 Kotlin，JVM 可单测）：
```kotlin
const val LABEL_BASE_ZOOM = 0.0015f
fun labelTextSize(zoom: Float): Float = (16f * (zoom / LABEL_BASE_ZOOM)).coerceIn(12f, 40f)
fun labelScaleK(zoom: Float): Float = (zoom / LABEL_BASE_ZOOM).coerceIn(0.7f, 2.5f)
```
2. `SceneCanvas.drawUnitLabel` 改用上述函数；锚点偏移 `sx + 10f * k, sy - 8f * k` **不变**（k 用 `labelScaleK`；偏移是屏幕空间，与翻转无关——翻转只影响锚点来源的 sy，而 sy 已由 worldToScreen 修正）。
3. 数量预期：默认 zoom=0.0015→16f；拉普拉塔默认→12.4f（≥12 ✓）；放大至 zoom=0.05→40f（封顶 ✓）。

### 4.6 触碰文件清单
| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/simplot/android/render/CameraMath.kt` | worldToScreen/screenToWorld/pan 翻转（§4.2） |
| `app/src/main/java/com/simplot/android/render/MapRenderer.kt` | drawBitmap txt 分支左上角改北边；drawPolygons 边界框改「左上+尺寸」（§4.2） |
| `app/src/main/java/com/simplot/android/render/UnitRenderer.kt` | 新增 `labelTextSize/labelScaleK` 纯函数（§4.5） |
| `app/src/main/java/com/simplot/android/engine/TurnState.kt` | 新增 `canDo/canUndo/canNext`（§4.3） |
| `app/src/main/java/com/simplot/android/ui/components/TurnControlBar.kt` | 三按钮 `enabled`（§4.3） |
| `app/src/main/java/com/simplot/android/ui/GameViewModel.kt` | doTurn/undo/next 防御（§4.3） |
| `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt` | ④ drawEpoch 触发（§4.4）；⑥ 标签字号接纯函数、删 UNUSED_EXPRESSION hack（§4.5） |
| `app/src/test/java/com/simplot/android/CameraMathTest.kt` | 翻转断言 ×3（§1 ①） |
| `app/src/test/java/com/simplot/android/EngineTest.kt`（或新 `TurnStateGateTest.kt`） | 门禁矩阵/闭环/危险路径（§1 ②③） |
| `app/src/test/java/com/simplot/android/UnitRendererTest.kt`（或并入现有） | labelTextSize clamp 断言 ×3（§1 ⑥） |

**明确不改**：`Unit.kt`、`ScenarioFile.kt`、`TimeState.kt`、codec/仓库/保存/FogOfWar、`Camera.kt`、`MovementEngine.kt`、`TrackRenderer.kt`、`ArcRenderer.kt`、示例 JSON、gradle 一切文件。

## 5. 假设（无法静态验证项，已标注）
- **A1（④，关键）**：tick→重组→drawBehind 重绘链路的失效环节未能在静态走读中 100% 定位（compose-ui 1.7.0 节点更新机制）；修复采用「draw 内快照读」方案，对「重组失效」与「重绘失效」两种可能均有效。编码者需按 §4.4 在设备上前后对比验证；若反馈源自旧版本、当前已正常，本改动保留为双保险。
- **A2（②③）**：GameViewModel 依赖 Android Application，JVM 单测不覆盖 VM 方法本身；门禁逻辑全部下沉为 TurnState 纯函数并单测，VM 层仅 3 行防御（人工评审 + 手动验收兜底）。
- **A3（①）**：翻转后 pan/zoom 手感方向变化（内容随手指）为预期行为，不属于回归；`zoom at anchor` 测试的既有断言不受影响（变换互逆）。
- **A4（⑥）**：12f 下限在极小屏/极远缩放下仍可能偏小，任务要求「≥12f 可读」按此验收；标签锚点偏移不变，不额外处理溢出。
- **A5**：示例场景无地图配置时（无 BoundaryRect），地图贴图不可见属既有行为，与本次修复无关。

## 6. 验证计划

### 6.1 单测（`testDebugUnitTest`，预计新增 9-11 个，总数 56 → 65-67）
1. **CameraMathTest**（+3）：
   - `world y flipped north is up`：center(0,0)、zoom 0.0015、(0,100000) → sy = 400-150 = 250 < 400 ✓；且 (0,-100000) → sy > 400（南在下）
   - `screenToWorld north of center`：screenToWorld(500, 300, …) → wy = cy + (400-300)/0.0015 = cy+66667（北）；roundtrip 断言
   - `pan vertical sign follows flip`：pan(0, 100f, …) → centerY + 66667
2. **TurnState 门禁**（+4~5，放 EngineTest 或新文件）：
   - canDo/canUndo/canNext 状态矩阵（3 状态 × 3 函数）
   - 闭环：advanceTime→detect=DO_AFTER；confirmNext→detect=DO_NEXT；再 advance→DO_AFTER
   - 危险路径文档化：DO_BEFORE 下 undo 会回退 PositionTime（断言时间回退，证明门禁必要）
   - （可选）detect 初态：空轨迹→DO_BEFORE
3. **UnitRenderer 标签**（+2~3）：labelTextSize(0.0007)=12f、(0.0015)=16f、(0.05)=40f；labelScaleK clamp。

### 6.2 构建
- 顶部构建命令全绿（含既有 56 测试不回归）；编码者另跑 `assembleDebug` 出 APK。

### 6.3 手动验收（模拟器/真机，安装新 APK）
1. 打开「示例」（冰海巨兽）：S002 在 S001 上方；单位布局与桌面版一致（北在上）；拖/缩正常、锚点不漂
2. 按 Do：单位**立即**移动（不拖动）；时间行同步；航迹方向与领导线一致（⑤）；测量模式画线，线方向与方位标签一致
3. Do 后：Do 按钮灰、Undo/Next 可点；Next 后：Undo/Next 灰、Do 可点；初始：Undo/Next 灰；连点 Do/Undo 无异常推进/回溯
4. 加载拉普拉塔河口：默认视图标签 ≥12f 可读；放大明显增大
5. 回归：打开/保存/导出/回放/编辑单位/复制/护航队/测量 CSV

## 7. 交付后置检查（Reviewer 关注点）
- 存档坐标字段值零改动（git diff 不含任何 json/assets 与 model 字段）
- 翻转只落在 CameraMath/MapRenderer/标签公式，`CoordUtil`/`MovementEngine`/`TrackRenderer` 未动
- 门禁矩阵与 §1 ② 验收一致；TurnState 引擎语义未被改写
- 新单测覆盖 §6.1 全部条目且全绿
