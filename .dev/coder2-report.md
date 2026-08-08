# Coder 2 变更说明（反馈 ①-⑥ 修复实现）

> 编码者：Coder（第 2 轮）｜ 时间：2026-08-08 ｜ 契约：`.dev/contract2.md`（v1.0）
> 项目：simplot-android v0.3.2（Kotlin/Compose MVVM）

## 一、改动总览

按契约 §4 实现，共触碰 **7 个主源码文件 + 3 个测试文件**（新增 2 个测试文件）。`git diff` 仅含 `.kt` 文件，**存档字段/JSON/assets/model 零改动**（已用 `git diff --name-only` 核验）。

| 文件 | 改动 | 对应问题 |
|------|------|---------|
| `render/CameraMath.kt` | worldToScreen/screenToWorld/pan Y 翻转（§4.2 逐字） | ①⑤ |
| `render/MapRenderer.kt` | drawBitmap txt 分支左上角改北边 `minY + mapWorldH.toLong()`；drawPolygons 边界框改「左上角+尺寸」 | ①⑤ |
| `render/UnitRenderer.kt` | 新增 `LABEL_BASE_ZOOM` / `labelTextSize` / `labelScaleK` 纯函数 | ⑥ |
| `engine/TurnState.kt` | 新增 `canDo/canUndo/canNext` 纯函数；undo 危险路径 KDoc 文档化（引擎本体不改） | ②③ |
| `ui/components/TurnControlBar.kt` | 三按钮加 `enabled = canDo/canUndo/canNext(state)` | ②③ |
| `ui/GameViewModel.kt` | doTurn/undo/next 开头门禁防御（非法 → toast + return，不改任何状态） | ②③ |
| `ui/components/SceneCanvas.kt` | ④ drawEpoch 快照机制（替换 `UNUSED_EXPRESSION tick` hack）；⑥ 标签字号改接 `UnitRenderer` 纯函数、删本地 `BASE_ZOOM` 常量 | ④⑥ |

测试文件：
- `CameraMathTest.kt`（5 → 8）：新增 3 个翻转断言
- `TurnStateGateTest.kt`（**新**）：门禁矩阵 / 闭环 / 危险路径，3 个
- `UnitRendererTest.kt`（**新**）：labelTextSize/labelScaleK clamp，3 个

## 二、各问题实现细节

### ①⑤ Y 翻转（CameraMath + MapRenderer）
- `worldToScreen`：`sy = canvasH / 2f - ((wy - centerY) * zoom)`（北在上）
- `screenToWorld`：`wy = ((canvasH / 2f - sy) / zoom).roundToLong() + centerY`（与 worldToScreen 严格互逆）
- `pan`：`(centerX - (deltaSx/zoom).roundToLong()) to (centerY + (deltaSy/zoom).roundToLong())`（内容下拉 → 中心 Y 增大）
- `zoomAt`/`fitBounds`/`Camera.kt` 未改（互逆保证锚点不漂；fitBounds 对称）
- MapRenderer：txt 分支取北边 `mapWorldMinY + mapWorldH` 作左上角（与 boundary 分支同构）；边界框改 `worldToScreen(minX, minY + h)` 左上角 + `(w·zoom, h·zoom)` 尺寸，避免翻转后 top>bottom 倒置

### ②③ 门禁（TurnState + TurnControlBar + GameViewModel）
- 纯函数：`canDo = state != DO_AFTER`；`canUndo/canNext = state == DO_AFTER`
- 按钮灰显 + VM 防御双保险；`advanceTime/confirmNext/undo` 引擎本体零改动
- undo 的 KDoc 补充危险路径说明（DO_BEFORE 下直接调用会回退时间）

### ④ Do 后即时刷新（SceneCanvas）
- `var drawEpoch by remember { mutableIntStateOf(0) }` + `LaunchedEffect(tick) { drawEpoch = tick }`
- Canvas draw lambda 首行 `@Suppress("UNUSED_VARIABLE") val epoch = drawEpoch`（draw 阶段快照读 → epoch 变化必重绘）
- 移除 `@Suppress("UNUSED_EXPRESSION") tick` 裸读（tick 参数保留，驱动重组 + LaunchedEffect）

### ⑥ 标签字号（UnitRenderer + SceneCanvas）
- `labelTextSize(zoom) = (16f * (zoom/0.0015f)).coerceIn(12f, 40f)`；`labelScaleK(zoom) = (zoom/0.0015f).coerceIn(0.7f, 2.5f)`
- 锚点偏移 `sx + 10f*k, sy - 8f*k` 规则不变，k 改用 labelScaleK

## 三、测试结果

**基线（改动前）**：62 个测试全绿（CameraMathTest 5 / EngineTest 21 / MapDataParserTest 5 / ReplayTest 15 / ScenarioRoundTripTest 7 / SideParsingTest 4 / SpScnCodecTest 5）。
> 注：契约预估基线 56，实际仓库当前为 62（历史迭代新增），新增后总数相应为 71。

**改动后**：`testDebugUnitTest` 全绿，**71 个测试，0 失败 0 错误**（新增 9 个）：
- CameraMathTest **8**（+3：北在上/南在下、screenToWorld 中心上方、pan 符号翻转）
- TurnStateGateTest **3**（新：门禁矩阵 3 状态×3 函数、Do→DO_AFTER→Next→DO_NEXT→Do 闭环、DO_BEFORE 危险路径文档化）
- UnitRendererTest **3**（新：labelTextSize 12/16/40 边界、极端 clamp、labelScaleK clamp）
- 既有 62 个全部保持通过（中心对称/zoomAt 锚点用例因变换互逆不受影响）

**构建**：`./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1` 一次通过（BUILD SUCCESSFUL，1m2s），产出 `app/build/outputs/apk/debug/app-debug.apk`（9.8 MB）。
- 过程中一次编译错误已修复：txt 分支 `mapWorldMinY + mapWorldH`（Double）与 `worldToScreen` 的 Long 参数类型不匹配 → 加 `.toLong()`（boundary 分支的 worldH 本就是 Long，无此问题）。

## 四、遗留风险 / 假设

- **A1（④，关键）**：tick→重组→drawBehind 重绘链路的失效环节未在静态走读中 100% 定位。本改动采用「draw 内显式快照读」方案，对「重组失效」与「重绘失效」两种可能均有效。**若设备上修复前就正常**（反馈源自旧版），本改动作为双保险保留，无副作用（epoch 首次写 = tick 初值，不改变初始绘制）。需在设备/模拟器上手动验证：Do 后单位立即移动、编辑/复制/护航队/Undo 同样即时刷新。
- **A2（②③）**：GameViewModel 依赖 Android Application，JVM 单测不覆盖 VM 方法本身；门禁逻辑全部下沉为 TurnState 纯函数并单测（矩阵/闭环/危险路径），VM 层仅 3 行防御。按钮灰显与 toast 需手动验收。
- **A3（①）**：翻转后 pan/zoom 手感方向变化（内容随手指）为预期行为；`zoom at anchor` 既有断言因变换互逆继续成立（已由全量测试证明）。
- **A4（⑥）**：12f 下限在极小屏/极远缩放下仍可能偏小；锚点偏移不变，不额外处理溢出。
- **A5**：示例场景无地图配置时贴图不可见属既有行为，与本次修复无关。
- 手动验收项（§6.3 契约）未执行（无设备/模拟器），需 Reviewer/主代理安排：北在上布局、领导线方向、Do 即时刷新、按钮灰显、标签可读性、测量线方位一致。

## 五、约束遵守核验

- ✅ 最小 diff：只碰契约 §4.6 列出的文件（含测试）
- ✅ 存档零改动：git diff 仅 .kt，无 json/assets/model 字段
- ✅ gradle.properties / build 脚本未动
- ✅ 引擎本体（advanceTime/confirmNext/undo/MovementEngine）未改
- ✅ 单条 gradle 命令串行跑，无并发（沙箱 2GB 防 OOM）
