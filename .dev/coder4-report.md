# Coder4 变更说明（contract4.md 两个反馈问题修复）

> 编码者报告 | 日期 2026-08-08 | 基线 0197cd5 (v0.3.4) | 流水线第 4 轮

## 改了什么（最小 diff，仅 3 文件，+56/-25）

### 修复 A：测量模式下轻点 = 选中单位（解决"单位无法选中"）

**文件**：`app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt`

- 手势块测量分支：`detectDragGestures(...)` → `awaitEachGesture` 手动实现：
  - `awaitFirstDown()` 等按下；`drag(down.id) { change -> ... }` 循环内：
    - 首次位移 `>= viewConfiguration.touchSlop` 判定为画线：`measureStart = 按下点世界坐标`（`start = sx to sy`），后续 `measureEnd = 当前点`，`change.consume()`
    - 松手（drag 返回）后：画过线 → `onMeasureDone(start, last)`；否则（轻点，无位移）→ `hitTest(file.units, camera, down.position, ...)` → `onSelect(hit?.idNum)`（不 consume，空白则 `hit=null` → `onSelect(null)` 取消选中）
  - 每轮结束清 `measureStart = null; measureEnd = null`
- 非测量分支 `detectTapGestures`（点选/长按）**未动**；replaying 早退**未动**
- 新增 imports：`androidx.compose.foundation.gestures.awaitEachGesture` / `awaitFirstDown` / `drag`（`detectDragGestures` import 移除，已无引用）

### 修复 B：退出测量模式清除测量线 + 测量模式内留存

1. **`GameViewModel.kt`**：新增 `fun clearMeasures() { measureLog.clear() }`（`measureLog` 为 `mutableStateListOf`，clear 触发快照失效 → 画布重绘）
2. **`MainActivity.kt`**：
   - 测量按钮 else 分支（退出时）：`vm.selectedUnitId = null` 后追加 `vm.clearMeasures()`
   - `onSelect` 包装：`onSelect = { id -> vm.selectedUnitId = id; if (id != null && vm.measureMode) vm.measureMode = false }` —— 轻点选中单位即退出测量模式（可直接看 ② 辅助线，`unitDist` 的 `!vm.measureMode` 条件生效）；轻点空白不退出（可继续画线）
3. **`SceneCanvas.kt`** 绘制块：已保存测量线 `for (m in savedMeasures)` 外层包 `if (measureMode)` —— 仅测量模式内绘制留存线（双保险：列表在退出时已清空 + 绘制条件）

## 与契约的偏差（1 处，已核实，非回退）

- 契约假设 `touchSlop` 可直接 import —— **在 foundation 1.7.3 中不成立**：`AwaitPointerEventScope.touchSlop` 是 `internal`（javap 反编译 `DragGestureDetectorKt` 确认，仅有 private static `defaultTouchSlop`）。
- **替代**：改用公开 API `viewConfiguration.touchSlop`（`AwaitPointerEventScope.getViewConfiguration()` + `ViewConfiguration.getTouchSlop()`，均 public，已用 javap 对 gradle 缓存中的 foundation-android 1.7.3 / ui-android 1.7.3 验证）。语义完全等价（同一个 `viewConfiguration.touchSlop` 值），**awaitEachGesture 主方案无需回退**。
- 其余 `awaitEachGesture` / `awaitFirstDown` / `drag` 均已确认存在于 foundation 1.7.3 公开 API。

## 手势冲突分析（契约 §4 遗留风险项）

- **与 transform 手势块 `pointerInput(measureMode)` 无冲突**：该块首行 `if (measureMode) return@pointerInput` —— 测量模式下协程立即返回，**不注册任何 transform 检测器**。同一 modifier 链中只剩 `pointerInput(file, measureMode)` 的 awaitEachGesture 检测器，无竞争者。
- `awaitFirstDown()` 默认 consume 按下事件：测量模式下无其它检测器需要该事件，无影响。
- 非测量模式：`detectTapGestures` + `detectTransformGestures` 并存行为与基线完全一致（未改动）。
- 双指同时按下画线：`drag(down.id)` 只跟踪首指，第二指不干扰（transform 已禁用，无缩放冲突）。

## 测试 / 构建结果

- 命令：`./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1`（LANG/JAVA_HOME/ANDROID_HOME 按契约）
- **`testDebugUnitTest`：83 tests, 0 failures, 0 errors**（全绿）
- **`assembleDebug`：BUILD SUCCESSFUL**（`app/build/outputs/apk/debug/app-debug.apk` 生成）
- 编译仅 1 条 warning：`SceneCanvas.kt:184` "Condition is always 'true'" —— **基线已存在**（`if (replaying && replayFrame != null)`，HEAD 版本位于 L168，本改动未触碰该代码，仅行号下移 16 行），非本次引入。

## 验收对照（手动项，待主代理/真机验证）

1. 测量模式画线松手 → 线留存；连续多条都在（`if (measureMode)` 内绘制 + measureLog 未清）✓ 代码路径就绪
2. 测量模式轻点单位 → `onSelect(id)` → selectedUnitId 高亮 + measureMode=false → ② 辅助线出现（`unitDist` 的 `!vm.measureMode` 条件解除）✓
3. 测量模式轻点空白 → `onSelect(null)` → 不退出、可继续画线 ✓
4. 再进测量模式留存线仍在；「退出测量」→ `clearMeasures()` → 全部清除 + 绘制条件不再命中 ✓
5. 非测量模式点选/长按：detectTapGestures 分支未动 ✓
6. 回放模式：replaying 早退保留，测量/点选均禁用 ✓

## 遗留风险

- 手势交互（轻点 vs 拖拽阈值、画线手感）无法 JVM 单测，需真机人工验收（契约验收标准 1-6）。
- `drag()` 回调仅在位移超过 touchSlop 后触发，与手动阈值检查双重过滤，逻辑等价但多一层判断；若后续发现首帧画线起点偏移，可删掉内层 `if (!isDrag)` 判断直接信任 `drag()` 的 slop 过滤。
- 测量模式内未处理多指第二指（保持基线行为：仅首指有效）。
