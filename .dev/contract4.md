# SimPlot Android v0.3.4 两个反馈问题修复契约（contract4.md）

> 版本 v1.0 | 日期 2026-08-08 | 主代理根因确认后落盘 | 流水线第 4 轮
> 基线：v0.3.4（0197cd5），83 测试全绿

## 反馈问题

1. **退出测量模式就要取消测量线**：v0.3.4 实现的「退出测量模式后已保存测量线仍常驻显示」与用户预期相反——用户要求**测量模式内松手后线留存（可连续画多条对照），退出测量模式时清除全部测量线**。
2. **单位根本无法被选中**：测量模式下 `pointerInput(file, measureMode)` 注册 `detectDragGestures`——**轻点（未超过 touch slop）不触发 onDragStart/onDragEnd 任何回调**，测量模式不退出、无任何反馈；用户轻点想选中单位 → 完全无反应。且 `onMeasureComplete` 仅在成功画完一条线（onDragEnd 触发）后才 `measureMode=false`——轻点画不出线时**永远卡在测量模式**，之后点任何单位都无效。

## 根因（已由主代理确认，证据链完整）

- `SceneCanvas.kt` L94-141 手势块：`if (measureMode) { detectDragGestures(...) } else { detectTapGestures(...) }`——测量模式分支只有 drag 手势，**无 tap**；`detectDragGestures` 轻点不触发任何回调（Compose 需超过 touchSlop 才进 drag）。
- `GameViewModel.onMeasureComplete`（L357-361）：`measureLog.add + measureMode=false + toast`——退出测量模式的唯一路径是画完一条线；轻点路径永远不退出。
- 用户语义确认：测量线留存 = **测量模式内**留存；退出 = 清除。v0.3.4 的「常驻显示」是错误实现（用户原话"退出测量模式就要取消测量线"）。

## 修复方案（最小 diff）

### 修复 A：测量模式下轻点 = 选中单位（解决"无法选中"）
`SceneCanvas.kt` 手势块测量分支改造：**测量模式下同时支持 轻点(选中) + 拖拽(画线)**。

方案：在测量分支用 `detectTapGestures(onTap = 选中)` **替换** `detectDragGestures`？——不行，画线是核心功能。
正确做法：**两个手势并存**——Compose 同一 pointerInput 内 `detectTapGestures` 与 `detectDragGestures` 不能共存（会互相 consume）。
替代：**用 `pointerInput` + `awaitEachGesture` 手动实现**：

```kotlin
.pointerInput(file, measureMode) {
    if (replaying) return@pointerInput
    if (measureMode) {
        // 测量模式：按下→拖拽=画线；轻点（无位移）=选中单位
        awaitEachGesture {
            val down = awaitFirstDown()
            var isDrag = false
            var start: Pair<Long, Long>? = null
            var last: Pair<Long, Long>? = null
            drag(down.id) {
                val (wx, wy) = camera.screenToWorld(it.position.x, it.position.y, size.width, size.height)
                if (!isDrag) {
                    // 首次位移超过阈值才判定为画线
                    val dx = it.position.x - down.position.x
                    val dy = it.position.y - down.position.y
                    if (dx*dx + dy*dy >= touchSlop*touchSlop) {
                        isDrag = true
                        val (sx, sy) = camera.screenToWorld(down.position.x, down.position.y, size.width, size.height)
                        start = sx to sy
                        measureStart = start
                    }
                }
                if (isDrag) {
                    it.consume()
                    last = wx to wy
                    measureEnd = last
                }
            }
            if (!isDrag) {
                // 轻点：选中单位（hitTest 复用现有逻辑）
                val hit = hitTest(file.units, camera, down.position, size.width.toInt(), size.height.toInt())
                onSelect(hit?.idNum)
            } else if (start != null && last != null) {
                onMeasureDone?.invoke(start!!, last!!)
            }
            measureStart = null
            measureEnd = null
        }
    } else {
        detectTapGestures(...) // 现有非测量分支不变
    }
}
```

**关键点**：
- `awaitEachGesture`/`awaitFirstDown`/`drag`/`touchSlop` 来自 `androidx.compose.foundation.gestures`（compose BOM 2024.09.03 可用：`awaitEachGesture`、`awaitFirstDown`、`drag`、`touchSlop` 均存在于 foundation-gestures 1.7.x）
- 轻点不消费事件 → `onSelect` 正常；画线 consume → 不触发其它手势
- **测量模式下点单位 → 选中 → 同时退出测量模式吗？** 用户语义：轻点选中单位后应能立即看到 ② 辅助线（测量模式会遮挡 ②，因 MainActivity `!vm.measureMode` 才计算）。**决定：轻点选中单位时同时退出测量模式**（`onSelect` 回调里由 MainActivity 处理？不行——onSelect 是通用回调）。**改为**：轻点选中时 SceneCanvas 额外回调 `onMeasureExit`？——过度设计。
  **简化决定**：轻点选中单位 → 调 `onSelect(hit.idNum)`；同时若选中成功（hit != null），SceneCanvas 调 `onMeasureDone`？不行，语义错。
  **最简方案**：MainActivity 的 `onSelect` 包装：`onSelect = { id -> vm.selectedUnitId = id; if (vm.measureMode && id != null) { vm.measureMode = false } }`——轻点选中单位即退出测量模式（用户可直接看 ② 辅助线）；轻点空白不退出（可继续画线）。
  ⚠️ 但 SceneCanvas 测量分支轻点调的是 `onSelect`（通用），MainActivity 包装即可，**SceneCanvas 无需新回调**。✓
- `detectTapGestures` import 仍被非测量分支用；新增 import：`awaitEachGesture`、`awaitFirstDown`、`drag`、`touchSlop`

### 修复 B：退出测量模式清除测量线
`MainActivity.kt` 测量按钮 onClick（L224-226）：
```kotlin
vm.measureMode = !vm.measureMode
if (vm.measureMode) vm.toast("测量模式：拖动画线，松手结束") else {
    vm.selectedUnitId = null
    vm.clearMeasures()   // 新增：退出时清除
}
```
`GameViewModel` 新增：
```kotlin
fun clearMeasures() { measureLog.clear() }
```
`SceneCanvas.kt` 绘制块：`for (m in savedMeasures)` 加条件 `if (measureMode)`——**测量模式内才画留存线**；退出后列表清空 + 不再绘制。

### 绘制条件
- 已保存测量线绘制：`if (measureMode) { for (m in savedMeasures) { ... } }`（测量模式内留存可见；退出即无）
- 拖拽临时线：不变（只在拖拽中显示）
- ② 单位辅助线：MainActivity 已有 `!vm.measureMode` 条件 ✓

## 触碰文件
1. `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt` — 手势块测量分支 awaitEachGesture 重写 + 留存线绘制加 measureMode 条件 + imports
2. `app/src/main/java/com/simplot/android/ui/GameViewModel.kt` — 新增 `clearMeasures()`（1 行）
3. `app/src/main/java/com/simplot/android/MainActivity.kt` — onSelect 包装（选中即退出测量模式）+ 测量按钮 else 分支 clearMeasures()

## 新增/更新测试
- **无新增单测**（手势与 UI 行为无法 JVM 单测；clearMeasures 是快照列表 clear，测试价值低）。可加 1 个 GameViewModel 测试？AndroidViewModel 需 Application——现有测试均无 VM 测试，保持现状。
- 回归：现有 83 测试全绿（本次不改引擎/模型/存档）。

## 验收标准（手动，真机）
1. 点「测量」→ 画线松手 → 线**留存**（测量模式内）；再画一条 → 两条都在
2. 测量模式下**轻点单位** → 单位选中（高亮）+ **自动退出测量模式** + 出现 ② 辅助线（到其它单位距离/方位）
3. 测量模式下轻点**空白** → 不退出、无选中（可继续画线）
4. 再进测量模式 → 之前的留存线**还在**（列表未清）；画完点「退出测量」→ 所有测量线**清除**
5. 非测量模式：点单位选中/长按编辑 回归正常（detectTapGestures 分支未动）
6. 回放模式下测量/点选均禁用（replaying 早退保留）

## 非目标
- 不改测量线 CSV 导出格式
- 不改 ② 辅助线逻辑（仅依赖退出测量模式后 selectedUnitId 生效）
- 不改存档/模型/引擎

## 假设
- compose foundation 1.7.x 的 `awaitEachGesture`/`awaitFirstDown`/`drag`/`touchSlop` 可直接 import（BOM 2024.09.03 → ui 1.7.0，成立；若编译报错，回退方案：测量分支 keep detectDragGestures + 另加一个 `pointerInput` 块仅注册 detectTapGestures 于同一 modifier——两个 pointerInput 块并存，tap 轻点仍会触发（无 consume 冲突时），但需验证 drag 与 tap 是否互抢——首选 awaitEachGesture）
- 轻点选中退出测量模式符合用户预期（用户痛点就是"选了单位想看距离"）
