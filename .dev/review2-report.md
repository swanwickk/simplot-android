# 审核报告：六个反馈问题修复（Y 翻转 / 回合门禁 / Do 即时刷新 / 标签字号）

> 审核者 Reviewer（第 2 轮）| 日期：2026-08-08 | 契约：`.dev/contract2.md`（v1.0，权威）| Coder 报告：`.dev/coder2-report.md`
> 基线：`git diff` 7 个主源码 .kt + CameraMathTest.kt 修改；TurnStateGateTest.kt / UnitRendererTest.kt 新增（未跟踪）
> 方法：**强制重跑测试**（`--rerun-tasks`，非 UP-TO-DATE）→ 全量重建（`--rerun-tasks`）→ 逐个 diff 审阅 → 变换互逆数学验证 → 存档/模型/资源零改动核验

## 结论：🟡 有条件批准（代码/测试层面通过，需设备验证 + 发布准备项）

六个修复与契约 §4 逐条一致，全部自动验收项通过；测试 **71/71 全绿**（62 基线 + 9 新增，强制重跑实测）；全量重建成功。未发现功能级 bug、无 vacuous 测试、无契约冲突。**附 1 项发布准备事项（CHANGELOG/版本号）与 6 项设备待验证项**，不阻塞代码验收。

---

## 一、测试实测（先跑测试，非只读代码）

强制重跑（`rm -rf app/build/test-results && ./gradlew testDebugUnitTest --no-daemon --max-workers=1 --rerun-tasks` → BUILD SUCCESSFUL in 55s，22 任务全执行），XML 实测：

```
CameraMathTest:        8 tests, 0 failures   （基线 5 + 翻转断言 3）
EngineTest:           21 tests, 0 failures   （TurnState 引擎用例未因门禁失效 ✓）
MapDataParserTest:     5 tests, 0 failures
ReplayTest:           15 tests, 0 failures
ScenarioRoundTripTest: 7 tests, 0 failures
SideParsingTest:       4 tests, 0 failures
SpScnCodecTest:        5 tests, 0 failures
TurnStateGateTest:     3 tests, 0 failures   （新增文件：矩阵/闭环/危险路径）
UnitRendererTest:      3 tests, 0 failures   （新增文件：字号/k clamp）
TOTAL: 71 tests, 0 failures, 0 errors, 0 skipped
```

- **基线 62 = 契约预估 56 的历史偏差**（0.3.2 已扩至 62），Coder 报告如实标注，71 = 62 + 9 ✓
- **非 vacuous 验证**：TurnStateGateTest 用真实 `MovementEngine.advance`/`TurnState.confirmNext`/`undo` 驱动，闭环断言时间串（00:00→00:03→00:06），危险路径实测回退到 `2025-12-31 23:57`；CameraMathTest 定量断言（sy=250/550、wy=266667、pan=+66667）；UnitRendererTest 断言 12f/16f/40f 边界与 0.7/1/2.5 clamp——9 个新用例全部真断言，无 `?: return` 空转路径

## 二、构建实测

`./gradlew assembleDebug --rerun-tasks --no-daemon --max-workers=1` → **BUILD SUCCESSFUL in 1m 6s**（36 任务全部真实执行，非缓存）

```
app/build/outputs/apk/debug/app-debug.apk  9,843,858 B（9.8 MB）
aapt badging: package='com.simplot.android' versionCode='9' versionName='0.3.2'
```

（首个 `--rerun-tasks` 尝试输出里的 "Run with --stacktrace" 提示来自 `stripDebugDebugSymbols` 的 benign 警告块——"Unable to strip libandroidx.graphics.path.so"，非构建失败；完整重跑确认 BUILD SUCCESSFUL。）

## 三、逐条契约验收

### ①+⑤ Y 翻转（CameraMath + MapRenderer）— ✅ 通过（自动项）

| 契约验收（自动） | 结果 | 证据 |
|---|---|---|
| 世界 (0,100000) 北 1 海里 → sy < H/2 | ✅ | `worldToScreen`: sy = 400−150 = **250 < 400**（测试定量断言）；(0,−100000) → 550 > 400 南在下 |
| screenToWorld(500,300) → wy > centerY；roundtrip | ✅ | wy = (400−300)/0.0015 + 200000 = **266667**（北 66667）；roundtrip 精确还原 (500,300) |
| pan(0,+100) → centerY 增加 | ✅ | cy + round(100/0.0015) = **+66667**；X 分量语义不变 |
| 既有 5 用例仍通过 | ✅ | 中心对称/zoomAt 锚点用例全绿（变换严格互逆，见下） |

**数学验证**：
- **互逆性**：`worldToScreen` sy = H/2 − (wy−centerY)·zoom；`screenToWorld` wy = ((H/2−sy)/zoom) + centerY → 代入得 wy' = wy，**精确互逆**（仅 roundToLong 舍入）→ zoomAt 锚点不漂、fitBounds 对称不受影响 ✓
- **pan 符号**：拖动 deltaSy>0（内容下拉）→ centerY 增大 → 固定世界点 sy = H/2 − (wy−centerY)·zoom 增大（内容随手指下移）✓ 符合直觉
- **MapRenderer txt 分支**：左上角改 `mapWorldMinY + mapWorldH.toLong()`（北边），与 boundary 分支（未动，翻转自动修正）同构；翻转后北边 → sy 小（屏上），`drawBitmap` 按正尺寸向下绘制 → 北在上且不镜像 ✓
- **drawPolygons 边界框**：`worldToScreen(minX, minY + h)` 左上角 + `(w·zoom, h·zoom)` 尺寸，避免翻转后 top>bottom 倒置 ✓（契约 §4.2 逐字）
- **未波及**：`Camera.kt`/`zoomAt`/`fitBounds`/`drawGrid`/`screenPath`/`hitTest`/`drawScaleBar`/`UnitRenderer.draw`（速度领导线屏幕空间北向上，翻转后与世界航迹自动一致——⑤ 的直接修复点）/`TrackRenderer`/`ArcRenderer` 零改动 ✓

### ②+③ Do/Undo/Next 门禁 — ✅ 通过

| 契约验收 | 结果 | 证据 |
|---|---|---|
| 门禁矩阵 {DO_BEFORE: Do✓ Undo✗ Next✗；DO_AFTER: Do✗ Undo✓ Next✓；DO_NEXT: Do✓ Undo✗ Next✗} | ✅ | `canDo = state != DO_AFTER`、`canUndo/canNext = state == DO_AFTER`——与矩阵逐格一致；TurnStateGateTest 三状态全矩阵真断言 |
| DO→DO_AFTER→Next→DO_NEXT→Do→DO_AFTER 闭环 | ✅ | 测试 `do next do closed loop` 实测时间推进 00:00→00:03→00:06，状态迁移全对 |
| 危险路径文档化（DO_BEFORE 直调 undo 回退时间） | ✅ | KDoc 补充 + 测试实测回退到前日 23:57（证明 VM 门禁必要） |
| 按钮灰显 + VM 防御 | ✅（代码级） | TurnControlBar 三按钮 `enabled = canDo/canUndo/canNext(state)`（state 复用既有 `TurnState.detect(file)`）；GameViewModel 三方法开头 `if (!canX) { toast + return }` 不改任何状态；toast 文案与契约一致 |
| 引擎本体不改 | ✅ | `advanceTime/confirmNext/undo` diff 零改动（仅 undo 加 KDoc）；EngineTest 21 个用例全绿为证 |

### ④ Do 后即时刷新 — ✅ 实现符合契约（待设备验证）

- `var drawEpoch by remember { mutableIntStateOf(0) }` + `LaunchedEffect(tick) { drawEpoch = tick }`（快照写）+ draw lambda 首行 `val epoch = drawEpoch`（draw 阶段快照读 → epoch 变化必重绘）——契约 §4.4 方案逐字落地 ✓
- 移除 `UNUSED_EXPRESSION tick` 裸读 hack；`tick` 参数保留（驱动重组 + LaunchedEffect）✓
- **链路核实**：MainActivity `tick = vm.revision` → `GameViewModel.revision = mutableStateOf(0)`（快照状态），**11 处 revision++** 覆盖 doTurn/undo/next/编辑/复制/护航队/加载等全部变更路径 → epoch 变化 → draw 失效重绘 ✓
- 初始无副作用：首帧 epoch 初值 = tick 初值，同值写入不触发失效 ✓
- **契约 A1 假设如实保留**：失效环节未静态定位，本方案对「重组失效/重绘失效」双保险；**必须设备验证**（见 §五）

### ⑥ 标签字号 — ✅ 通过（自动项）

| 契约验收 | 结果 | 证据 |
|---|---|---|
| labelTextSize(0.0007) = 12f | ✅ | 16×0.467=7.47 → clamp 12f（测试断言） |
| labelTextSize(0.0015) = 16f | ✅ | 默认基准 ✓ |
| labelTextSize(0.05) = 40f | ✅ | 16×33.3=533 → clamp 40f ✓ |
| labelScaleK clamp 0.7..2.5 | ✅ | 0.00001→0.7、0.0015→1.0、0.05/1f→2.5 |
| 锚点偏移规则不变 | ✅ | `sx + 10f·k, sy − 8f·k` 原样保留，仅 k 改接 `labelScaleK` |
| 拉普拉塔默认视野 ≥12f | ✅（推算） | fitBounds zoom≈0.00116 → 16×0.773=12.37f ≥12 ✓ |

## 四、重点核查（上次审核踩坑项）

| 核查项 | 结果 | 证据 |
|---|---|---|
| 存档坐标字段/JSON/model 零改动 | ✅ | `git diff --name-only` 仅 8 个 .kt（7 主源码 + CameraMathTest）；无 assets/json/Unit.kt/ScenarioFile.kt/TimeState.kt/codec；无暂存改动 |
| 翻转不波及 MovementEngine/CoordUtil/TrackRenderer | ✅ | diff 不含三者；`CameraMath.` 全部消费方仅经 `Camera.kt`（未改）转发 |
| CHANGELOG 测试数一致性 | ⚠️ 见问题 1 | 0.3.2 条目「62 测试全过」与基线一致 ✓；但**尚无 0.3.3 条目** |
| assembleDebug 成功 + APK | ✅ | 全量重建 1m6s；APK 9,843,858 B；versionName 0.3.2 / code 9（发布时需 bump） |
| 防 vacuous 测试 | ✅ | 见 §一 非 vacuous 验证段；资源类测试（SideParsingTest）仍真加载断言 |

## 五、发现的问题

### 严重度分级

| # | 级别 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| 1 | 发布准备（不阻塞代码） | CHANGELOG 无 0.3.3 条目；`app/build.gradle.kts` 仍 versionName 0.3.2 / code 9 | `CHANGELOG.md` 顶部为 [0.3.2]；build.gradle.kts:15-16 | 发布步骤需新增 [0.3.3] 条目（六修复 + **71 测试全过**）+ bump versionName/versionCode。按任务分工由主代理发布时统一做 |
| 2 | 提示（不阻塞） | txt 分支 `mapWorldH.toLong()` 为 Double→Long 截断（<1 世界单位，亚像素） | MapRenderer.kt L126 | 无需处理，记录备查 |
| 3 | 提示（不阻塞） | `drawEpoch` 初值与 tick 同值时不触发写失效——正常（首帧无需重绘）；`remember` 未 keyed by file，但换场景必 revision++ → epoch 更新，已覆盖 | SceneCanvas.kt L56-58 | 无需处理 |

**Coder 报告准确性**：与实现逐项一致（71 测试、9.8MB APK、.toLong() 编译修复、A1-A5 假设、手动项未执行声明诚实）——上轮「报告与实现不符」问题本轮未再现 ✓

## 六、设备待验证项清单（沙箱无 UI，无法覆盖——发布前必须在真机/模拟器验收）

1. **北在上**：示例「冰海巨兽」S002（Y=24687）显示在 S001 上方；单位/轨迹/多边形/地图贴图不错位；**贴图方向不镜像**（txt 格式地图按「图像顶行=北」假设绘制，boundary 分支同构——真机确认海岸线/文字方向）
2. **⑤ 领导线一致**：航向 0° 单位速度领导线指屏幕上方、90° 指右侧；测量线屏幕方向与方位标签一致
3. **④ Do 即时刷新**：按 Do 单位立即移动（无拖动）；时间行与画布同步；编辑/复制/护航队/Undo 同样即时刷新（回归）
4. **②③ 按钮灰显 + toast**：DO_BEFORE 时 Undo/Next 灰、DO_AFTER 时 Do 灰、DO_NEXT 时 Undo/Next 灰；非法点击弹 toast 且无副作用；连点 Do/Undo 无异常推进/回溯
5. **⑥ 标签可读性**：拉普拉塔默认视图 ≥12f 可读；放大显著增大（≤40f）；不溢出/不遮挡符号
6. **手势方向**：拖动内容随手指、捏合缩放锚点不漂移（契约 A3 预期行为）

## 七、遗留风险

- **A1（④）**：若设备上修复前 Do 已即时刷新（反馈源自旧版），本改动作为双保险保留——无副作用，但需真机确认修复后行为
- **A2（②③）**：VM 层防御（3 行）+ 按钮灰显为代码级验证，toast 文案与点击行为需手动验收兜底
- 提交状态：全部改动未 commit（7 modified + 2 untracked 测试 + `.dev/`），由主代理发布时统一提交

---

*审核方法：git diff 全文审阅 + 变换互逆数学推导 + 快照机制链路核验（revision→tick→LaunchedEffect→epoch→draw 失效）+ 强制重跑测试（--rerun-tasks）与全量重建 + aapt badging 核验 + 测试非 vacuous 逐条确认。*
