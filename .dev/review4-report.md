# Review4 审核报告（contract4 两个反馈问题修复）

> 审核者：流水线 Reviewer | 日期 2026-08-08 | 基线 0197cd5 (v0.3.4)
> 审核对象：工作区最终状态（Coder 实现 + 主代理 2 处追加修正），未改任何代码、未 commit

---

## 1. 结论：有条件批准 ✅

代码层面**通过**：3 个 .kt 最小 diff 与契约 §4 一致，主代理两处追加修正**正确且必要**，强制重跑 `testDebugUnitTest` 83/83 全绿、`assembleDebug` BUILD SUCCESSFUL，无契约外改动（json/assets/model 零改动）。

**条件**：验收标准 §6 的 1–4 项为手势交互，无法 JVM 单测，必须真机人工验收通过后方可发版（契约本身即标注"手动，真机"）；另有 2 个提示级问题建议顺手修复（见 §7）。

---

## 2. 审核方法（强制项逐条执行）

1. ✅ **先跑测试（非只读）**：`./gradlew testDebugUnitTest --no-daemon --max-workers=1 --rerun-tasks`（强制重跑）→ BUILD SUCCESSFUL，83 tests / 0 failures / 0 errors（11 个 suite 逐个数核对：8+7+21+5+15+7+4+5+3+5+3=83）；随后单独 `./gradlew assembleDebug --no-daemon --max-workers=1` → BUILD SUCCESSFUL（APK 9.8MB 生成）。两次 gradle 未并发。
2. ✅ **逐文件 diff 审阅**：`git diff` 仅 3 个 .kt（MainActivity +15/-1、GameViewModel +7、SceneCanvas +65，合计 +61/-26）；契约 §4「触碰文件」3 个文件一一对应；无其他改动。
3. ✅ **重点核查**：见 §4（含对 foundation 1.7.3 实际字节码的反编译验证）。
4. ✅ **契约 §6 验收清单逐条对照**：见 §5。

---

## 3. 测试 / 构建实测

| 项 | 结果 |
|---|---|
| `testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL，**83 tests, 0 failures, 0 errors**（与主代理/Coder 报告一致，且为本次强制重跑） |
| `assembleDebug` | BUILD SUCCESSFUL，`app/build/outputs/apk/debug/app-debug.apk` 存在 |
| 编译 warning | 仅 1 条：`SceneCanvas.kt:184:26 Condition is always 'true'` —— 已核对该行是 `if (replaying && replayFrame != null)`，基线 HEAD 中位于 L168（`git show HEAD:...` 核对），本次改动未触碰该表达式、仅行号下移 16 行，**非本次引入** |

---

## 4. 关键核查（含字节码级证据）

### 4.1 修复 A 手势正确性 —— 对 foundation 1.7.3 实际实现反编译验证 ✅

从 gradle 缓存 `foundation-android-1.7.3/foundation-release.aar` 解包 `classes.jar`，用 `javap -c -p` 反编译 `DragGestureDetectorKt` / `ForEachGestureKt`，得到与直觉不同的**关键事实**：

- **`drag(pointerId, onDrag)` 在 1.7.3 中返回 `Boolean` 且不抛异常、内部无 touchSlop 过滤**：
  - 循环体：`awaitDragOrCancellation(pointer)` → 返回 **null**（指针消失/事件被 consume/起始即 up）→ `return false`；返回 change 且 `changedToUpIgnoreConsumed`（首指抬起且无第二指）→ `return true`；否则 `onDrag(change)` 后循环。
  - 即：**轻点（down→up 无位移）时 onDrag 一次都不会被调用，drag() 直接返回 true**（up change），**不会抛 CancellationException**（旧版 1.6 才抛）。`awaitEachGesture` 的 catch 仅在任务仍活跃时吞 CancellationException，任务取消时 rethrow（字节码 `isActive` 判断），**无 busy-loop 风险**。
- **轻点路径成立**：轻点 → onDrag 未调用 → `isDrag=false` → 走 `hitTest → onSelect` 分支。**修复 A 的核心目标（测量模式轻点选中单位）在代码路径上真实可达**，非死代码。
- **手动 slop 判断是必要的，不是冗余**：由于 `drag()` 对**任意位移**（哪怕 1px）都会回调 onDrag（`positionChangedIgnoreConsumed` 即返回），Coder 在 onDrag 内的 `dx²+dy² >= touchSlop²` 手动阈值判断**承担了全部成拖判定职责**（start/measureStart 仅在超过 slop 后设置）。Coder 报告"遗留风险"中"若后续发现首帧画线起点偏移可删内层判断直接信任 drag() 的 slop 过滤"——**该假设不成立**（1.7.3 的 drag() 无 slop 过滤，删掉内层判断会变成任何微动都画线），建议保留现状、勿删。
- **画线路径**：超过 slop 后 `isDrag=true` → start=按下点世界坐标、`change.consume()`、last/measureEnd 随动 → 松手 drag() 返回 true → `onMeasureDone(start, last)` → `onMeasureComplete`。✓
- **transform 无冲突**：transform 块 `pointerInput(measureMode)` 首行 `if (measureMode) return@pointerInput`，测量模式下**未注册任何 transform 检测器**，无竞争者（与契约 §4 判断一致）。轻点不 consume 的事件在本模式下无其它检测器可抢。
- **清理路径**：每轮正常结束 `measureStart/measureEnd = null` 均执行；measureMode 切换中断协程（如拖拽中按退出）→ 尾部清理被跳过 → 临时线状态残留 —— **与基线 detectDragGestures 行为完全一致**（基线同样在协程取消时跳过 onDragCancel），非回归。
- **与 detectTapGestures（非测量）一致性**：非测量分支未动，点选/长按行为与基线一致。✓

### 4.2 Coder 偏差核实（touchSlop API）✅ 已用 javap 独立验证

- `AwaitPointerEventScope.touchSlop`：反编译 `DragGestureDetectorKt` 全部方法签名，**不存在公开 touchSlop 扩展属性**（仅有 private static `defaultTouchSlop` / `mouseToTouchSlopRatio` 与各 `await*SlopOrCancellation`），Coder 关于"internal"的判断成立。
- 替代 API：`AwaitPointerEventScope.getViewConfiguration()`（public abstract）+ `ViewConfiguration.getTouchSlop()`（public abstract），均存在于 ui-android 1.7.3 —— **公开可用**，语义等价（同一值），`awaitEachGesture` 主方案无需回退。编译通过即为最终证明。

### 4.3 修复 B（退出=清线）+ 主代理修正后的行为闭环 ✅

- `measureMode = false` 全仓仅剩 2 处：测量按钮 toggle（`!` 取反）与 MainActivity onSelect 包装（选中单位时）。`onMeasureComplete` 不再改 measureMode → **无任何调用点依赖"画完自动退出"**（grep 核实）。
- 退出路径语义一致：按钮退出（L230-236：`selectedUnitId=null` + `clearMeasures()`）与选中单位退出（L125-130：`selectedUnitId=id` + `measureMode=false` + `clearMeasures()`）**均清线**；轻点空白 `onSelect(null)` → 不退出不清线（可继续画）。✓
- SceneCanvas 留存线绘制 `if (measureMode)` 双保险；`savedMeasures=vm.measureLog`（SnapshotStateList）在 draw 阶段读快照 → add/clear 均自动重绘（v0.3.4 既有机制）。连续画多条：`onMeasureComplete` 只 add + toast（条数提示保留 ✓）。
- ② 辅助线：MainActivity `unitDist = if (!replaying && !vm.measureMode) ... else null`，退出测量模式（含选中退出）后立即生效。✓
- CSV 导出：`exportMeasureCsv` 读 measureLog 快照，逻辑零改动，不受退出清线影响。✓

### 4.4 存档/模型/资源零改动 ✅

`git diff --name-only` 仅 3 个 .kt；无 json / assets / model / 引擎改动；`git status` 未跟踪文件仅 `.dev/contract4.md`、`.dev/coder4-report.md`（流水线文档，符合预期）。

---

## 5. 契约 §6 验收清单逐条对照（代码路径就绪度）

| # | 验收项 | 判定 | 依据 |
|---|---|---|---|
| 1 | 测量模式画线松手→线留存；再画一条→两条都在 | **通过（待真机）** | onMeasureComplete 不再退出模式（主代理修正），measureLog 累积，`if(measureMode)` 绘制留存线；draw 阶段快照读自动重绘 |
| 2 | 测量模式轻点单位→选中+自动退出+②辅助线 | **通过（待真机）** | 轻点→onSelect(id)→包装退出+清线→`!measureMode` 解除→② 显示（§4.1 已证轻点路径真实可达） |
| 3 | 测量模式轻点空白→不退出、可继续画线 | **通过（待真机）** | onSelect(null)→id==null 不触发退出；hitTest 空白返回 null |
| 4 | 再进测量模式留存线还在；「退出测量」→全部清除 | **通过（待真机）** | 重进不清列表；按钮退出 clearMeasures() + 绘制条件双保险 |
| 5 | 非测量模式点选/长按回归正常 | **通过** | detectTapGestures 分支零改动；83 单测含点选相关回归全绿 |
| 6 | 回放模式测量/点选禁用 | **通过** | `if (replaying) return@pointerInput` 早退保留；按钮侧 `回放中不可测量` 拦截 |

注：1–4 为手势交互，JVM 单测不可达，代码路径均已核实就绪，**必须真机验证**。

---

## 6. 主代理两处追加修正的独立评估 ✅ 均正确且必要

1. **`onMeasureComplete` 移除 `measureMode = false`（画完不自动退出）**
   - **必要**：契约 §6 验收第 1 条要求"再画一条 → 两条都在"，若保留自动退出，画完第一条即退出测量模式、无法连续画第二条，验收 1 直接不成立。契约 §4 修复方案未显式改此函数（与验收清单自相矛盾），主代理修正使其与验收语义对齐。
   - **无副作用**：grep 证实无其它调用点依赖自动退出；toast 条数提示保留；CSV 导出不受影响；退出路径（按钮/选中）均已覆盖。
2. **`onSelect` 退出时同时 `clearMeasures()`（退出=清线语义一致）**
   - **必要**：若不在此处清线，选中退出后列表残留，用户再进测量模式时旧线"复活"，违反"退出测量模式就要取消测量线"的用户语义（契约根因确认）。
   - **正确性**：与按钮退出路径完全对称；轻点空白不清线（id==null 守卫）保持"可继续画线"。

---

## 7. 问题清单

### 阻塞级
- 无。

### 非阻塞级（建议处理，可合入后处理）
- **N1｜取消手势会记录半条线（行为差异，非回归）**：1.7.3 `drag()` 取消（指针被系统取消，非协程取消）时返回 false，此时若已过 slop（isDrag=true、start/last 有值），`else if (start != null && last != null) onMeasureDone(...)` 会记录一条"按下→最后位置"的**半条线**并 toast；基线 detectDragGestures 的 onDragCancel 是清空不记录。触发场景罕见（画线中系统手势/切后台等）。建议：将 `else if` 分支加 `else if (isDrag && start != null && last != null)`（或记录前判断手势未取消）。低风险、一行可改。

### 提示级
- **P1｜过期注释**：`GameViewModel.kt:356` `/** 测量完成回调：记录测量线 + 关闭测量模式 */` —— "关闭测量模式"已不再成立（主代理修正后），建议改为"记录测量线（不退出测量模式，可连续画线）"。
- **P2｜进入测量模式的 toast 文案**："测量模式：拖动画线，松手结束"——"松手结束"在连续画线语义下易误解为"松手退出模式"，建议改为"松手记录一条，可连续画；退出时清除"。
- **P3｜Coder 报告数字小偏差**：报告称 +56/-25，实际 git diff 为 +61/-26（3 文件）；不影响结论。
- **P4｜Coder 报告遗留风险表述不准确**（见 §4.1）："可删掉内层 if(!isDrag) 判断直接信任 drag() 的 slop 过滤"——1.7.3 drag() 无 slop 过滤，该内层判断是成拖判定的唯一依据，**不可删**（已在代码注释中保留，仅报告表述需修正）。

---

## 8. 审核结论

**有条件批准**。实现与契约 §4 一致、最小 diff、测试构建全绿、无契约外改动；主代理两处追加修正经独立评估**正确且必要**（分别支撑验收 1 与"退出=清除"语义闭环）；修复 A 的核心手势逻辑经 foundation 1.7.3 实际字节码反编译验证**真实可达**（轻点→onSelect、画线→onMeasureDone、transform 无冲突）。

放行条件：
1. 真机人工验收契约 §6 第 1–4 项（第 5、6 项代码路径已确证）通过后发版；
2. 合入前或紧随其后处理 N1（建议一行修复）；P1/P2 顺手更新。

未修改任何代码，未 commit。
