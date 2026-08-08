# Coder 3 报告 — 三个反馈问题修复（contract3.md 实现）

> 编码者：Coder（dev-pipeline 第 3 轮）｜ 日期：2026-08-08
> 契约：`.dev/contract3.md` v1.0（最终）｜ 基线：71 单测全绿（已确认）

## 1. 完成情况总览

| 契约条目 | 状态 | 说明 |
|---------|------|------|
| §1 测量线留存（3 处） | ✅ | GameViewModel + SceneCanvas + MainActivity |
| §2 点选单位自动测量（新功能，3 处） | ✅ | 新文件 UnitMeasure.kt + SceneCanvas + MainActivity |
| §3 设置按钮（2 处） | ✅ | TurnControlBar + MainActivity |
| §5 新增测试（2 文件 12 用例） | ✅ | 71 → **83 全绿** |
| 构建 assembleDebug | ✅ | BUILD SUCCESSFUL |
| 零改动约束（存档/引擎/渲染/现有测试/gradle.properties） | ✅ | git diff 仅含契约 §4 清单文件 |

## 2. 改动文件清单（与契约 §4 完全一致）

### 修改（4 个现有文件）

**1. `app/src/main/java/com/simplot/android/ui/GameViewModel.kt`**（+1 import，measureLog 声明）
- `measureLog`：`mutableListOf<...>()` → `mutableStateListOf<...>()`（import `androidx.compose.runtime.mutableStateListOf`）
- SnapshotStateList 实现 MutableList → `exportMeasureCsv`（forEachIndexed）、`isEmpty()`、`.size` 全部兼容，零逻辑改动
- **未加 `revision++`**（契约假设 1 的主方案；回退方案见 §5）

**2. `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt`**
- 新参数：`savedMeasures: List<Pair<Pair<Long, Long>, Pair<Long, Long>>> = emptyList()`、`unitDistances: List<UnitDistance>? = null`（默认值 → 其它调用方不破坏）
- 抽取私有 `drawMeasureLine(canvas, camera, w, h, start, end, saved)`：
  - saved=true：`argb(150,220,60,40)` 2f 细线 + 4f 起点圆点 + 标签 `"%.1f nmi  方位 %.0f°"`（16f 白底红字两遍）
  - saved=false：原样式不变（argb(230,...) 3f 线 + 8f 圆点 + 同款标签）
- 绘制块（单位绘制之后、比例尺之前）顺序：**savedMeasures（saved=true）→ unitDistances 灰线辅助 → 临时 ms/me（saved=false）**
- ② 辅助线：选中单位 worldToScreen 单独算一次（单位循环内坐标不可复用）→ 每目标 1.5f 灰线 `argb(160,90,90,90)` + 中点两行标签（名称 / `"%.1f nmi %.0f°"`，深灰 rgb(60,60,60) 13f + 白色 STROKE 描边两遍绘制保证可读）；target 在单位列表找不到时 `continue` 防御

**3. `app/src/main/java/com/simplot/android/MainActivity.kt`**
- SceneCanvas 调用处：`savedMeasures = vm.measureLog`、`unitDistances = unitDist`
- `val unitDist = if (!replaying) vm.selectedUnitId?.let { unitDistances(f, it) } else null`（f 非空分支内；replaying 时 null → 回放不显示）
- TurnControlBar 调用处：`onIntervalSet = { m, s -> vm.toast("回合时长已设为 $m 分 $s 秒") }`（复用现有 toasts StateFlow 订阅）
- import `com.simplot.android.data.util.unitDistances`

**4. `app/src/main/java/com/simplot/android/ui/components/TurnControlBar.kt`**
- 新参数 `onIntervalSet: ((minutes: Int, seconds: Int) -> Unit)? = null`
- 按钮文案「设置」→「**设置时长**」
- onClick 写完 `file.time.currentTurnInterval` 后追加 `onIntervalSet?.invoke(m, s)`（分/秒兜底 3/0 逻辑零改动）

### 新增（3 个文件）

**5. `app/src/main/java/com/simplot/android/data/util/UnitMeasure.kt`**（纯 Kotlin，无 Android 依赖）
- `data class UnitDistance(idNum, name, side, distNm, bearingDeg)`
- 顶层函数 `unitDistances(file, unitId)`：单位不存在 → emptyList()；排除自身；复用 `CoordUtil.distanceNm/bearingDeg`；`sortedBy { it.distNm }` 升序
- 顶层函数而非 VM 成员方法（契约要求）：避免 AndroidViewModel 需 Application 实例化导致单测依赖 Robolectric

**6. `app/src/test/java/com/simplot/android/CoordUtilTest.kt`**（7 用例，纯 JUnit）
**7. `app/src/test/java/com/simplot/android/UnitMeasureTest.kt`**（5 用例，构造 ScenarioFile 直调顶层函数）

## 3. 测试结果

```
testDebugUnitTest: 11 个测试类，83 tests，0 failures，0 errors
  - 既有 71 用例：CameraMathTest 8 / EngineTest 21 / ReplayTest 15 / ScenarioRoundTripTest 7 /
    SpScnCodecTest 5 / MapDataParserTest 5 / SideParsingTest 4 / TurnStateGateTest 3 / UnitRendererTest 3 —— 全绿（0 回归）
  - 新增 12 用例：CoordUtilTest 7 / UnitMeasureTest 5 —— 全绿
```
`assembleDebug`：**BUILD SUCCESSFUL**（1m 17s，42 tasks；唯一警告 SceneCanvas L168 "Condition is always 'true'" 为既有代码 `replaying && replayFrame != null`，非本次改动）

## 4. 契约偏差（1 处，需 Designer/审核知晓）

**§5 CoordUtilTest 用例 7 终点坐标修正**：
- 契约原文：`(100000,200000)→(-100000,-200000)` 断言 dist≈sqrt(8)、bearing==225.0
- 实际数学：该终点 dx=-200000（-2 nmi）、dy=-400000（-4 nmi）→ dist=sqrt(20)≈4.472、bearing≈206.565°，**与契约自身断言矛盾**
- 修正：终点改为 `(-100000, 0)`（dx=dy=-2 nmi → dist=sqrt(8)≈2.828、bearing=225.0），完整保留契约意图「象限 III（双负）验证」与断言数值。已在测试注释中说明

## 5. 遗留风险（契约假设 1：快照列表重绘是否生效）

- **状态**：采用契约优先方案（SnapshotStateList + draw 阶段迭代读），**未加 `revision++` 回退**
- **理由**：机制与项目现有 `drawEpoch`（反馈④修复）完全同款——draw 阶段快照读注册失效观察，`measureLog.add`（快照写）即触发 Canvas 重绘，无需重组；本文件内已有成功先例，风险低
- **无法静态验证**：本环境无真机/模拟器，验收项「松手后线保留/退出测量仍显示/多线条并存」需按契约 §7.2 手动验证
- **回退方案（若手动验证不通过）**：在 `onMeasureComplete` 末尾加 `revision++`（走 drawEpoch 已验证链路），一行改动即可，不影响本次已交付代码

## 6. 其它说明

- 未做：测量线删除/编辑/清空/数量上限/持久化（契约 §1 非目标）；距离列表 UI/持久化/CSV（§2 非目标）；输入校验规则与说明文字改动（§3 非目标）
- 回放模式下：savedMeasures 照常绘制（契约假设 6 采纳），unitDistances 传 null 不绘制，测量模式与回放互斥逻辑未动
- git status：仅 4 修改 + 3 新增（+ 既有未跟踪的 contract3.md），无存档/构建产物意外改动
