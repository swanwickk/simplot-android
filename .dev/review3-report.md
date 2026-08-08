# Review 3 报告 — 三个反馈问题修复审核（Reviewer 产出）

> 审核者：Reviewer（dev-pipeline 第 3 轮）｜ 日期：2026-08-08
> 契约：`.dev/contract3.md` v1.0｜ Coder 报告：`.dev/coder3-report.md`
> 审核方式：强制重跑单测（--rerun-tasks，非缓存）+ assembleDebug + 逐文件 git diff 审阅 + 关键机制源码级核查

---

## 0. 结论：**有条件批准** ✅（附 2 项提示级说明，无阻塞项）

- 自动验证全部通过：**83 单测全绿（0 失败 0 错误）**，assembleDebug BUILD SUCCESSFUL
- diff 与契约 §4 完全一致，无契约外改动，存档/模型/资源/引擎零改动
- 无阻塞（Blocking）问题；1 项非阻塞偏差（测试用例 7 终点修正，数学已验证正确）；5 项提示级说明
- **条件**：① 契约假设 1（快照列表触发重绘）无法静态验证，须按契约 §7.2 手动验收 ① 的 1/2/3 项，若不通过按既定回退方案（onMeasureComplete 末尾加 revision++）；② 契约验收清单「测量模式下不显示②辅助线」与契约技术方案「线保持（无害）」存在内部矛盾，Coder 按技术方案实现，需 Designer 确认最终取舍

---

## 1. 测试实测结果（本人强制重跑，非 Coder 转述）

命令：`./gradlew testDebugUnitTest --no-daemon --max-workers=1 --rerun-tasks`（22 tasks 全部执行，无 UP-TO-DATE 缓存；随后单独跑 `assembleDebug`，无并发）

**11 个测试类，共 83 tests，0 failures，0 errors**（app/build/test-results/testDebugUnitTest/ 实测）：

| 测试类 | 用例数 | 结果 |
|--------|-------:|------|
| CameraMathTest | 8 | ✅ |
| EngineTest | 21 | ✅ |
| ReplayTest | 15 | ✅ |
| ScenarioRoundTripTest | 7 | ✅ |
| SpScnCodecTest | 5 | ✅ |
| MapDataParserTest | 5 | ✅ |
| SideParsingTest | 4 | ✅ |
| TurnStateGateTest | 3 | ✅ |
| UnitRendererTest | 3 | ✅ |
| **CoordUtilTest（新增）** | **7** | ✅ |
| **UnitMeasureTest（新增）** | **5** | ✅ |

- 既有 9 类合计 71 = 契约基线 71 ✅（契约 §0 写"8 个测试文件"系笔误，实际基线为 9 个测试类，总数一致）
- 新增 12 用例与契约 §5 清单一一对应 ✅

**构建**：`assembleDebug --no-daemon --max-workers=1` → **BUILD SUCCESSFUL**（36 tasks）。唯一警告 SceneCanvas L168 "Condition is always 'true'" —— 已用 `git show HEAD:` 比对，该行 `replaying && replayFrame != null` 在 HEAD 版本即存在（L165 同款代码），**非本次改动引入**，Coder 声明属实。

---

## 2. 逐文件 diff 审阅（对照契约 §4）

| 文件 | 契约要求 | 实测 | 判定 |
|------|---------|------|------|
| GameViewModel.kt | measureLog → mutableStateListOf（1 行 + import） | ✅ import `androidx.compose.runtime.mutableStateListOf`；声明改快照列表；注释说明用途 | ✅ 最小 diff |
| SceneCanvas.kt | +savedMeasures、+unitDistances 参数；抽 drawMeasureLine；绘制块加两段 | ✅ 参数均有默认值（emptyList / null）不破坏其它调用方；drawMeasureLine 抽取干净（saved=true argb(150,220,60,40) 2f 线 + 4f 圆点 + 16f 标签两遍；saved=false 原样式 3f/8f 不变）；绘制顺序 = 已保存线 → 单位距离辅助线 → 临时线，位于单位绘制之后、比例尺条之前 | ✅ |
| MainActivity.kt | 传 savedMeasures；算 unitDistances；onIntervalSet toast | ✅ `savedMeasures = vm.measureLog`（直接传快照列表实例，未包装拷贝）；`unitDist = if (!replaying) vm.selectedUnitId?.let { unitDistances(f, it) } else null`（f 非空分支内）；toast 复用 vm.toast/toasts StateFlow（L81-82 已核实） | ✅ |
| TurnControlBar.kt | 文案「设置时长」+ onIntervalSet 回调 | ✅ 参数带默认值 null；onClick 内**先写** `file.time.currentTurnInterval = TurnInterval(m, s)` **再** `onIntervalSet?.invoke(m, s)`；分/秒兜底（?: 3 / ?: 0）零改动 → toast 显示的是实际生效值 | ✅ |
| UnitMeasure.kt（新） | UnitDistance + unitDistances 顶层纯函数 | ✅ 无 Android 依赖；单位不存在/空场景 → emptyList；排除自身；复用 CoordUtil；`sortedBy { it.distNm }` 升序 | ✅ |
| CoordUtilTest.kt（新） | 7 用例 | ✅ 纯 JUnit；数值断言已实测通过（见 §4 数学复核） | ✅ |
| UnitMeasureTest.kt（新） | 5 用例 | ✅ 构造 ScenarioFile 直调顶层函数，无 Robolectric；排除自身/排序/数值/不存在/空单单位 全覆盖 | ✅ |

**零改动约束**：`git status --short` 仅 4 个修改（MainActivity/GameViewModel/SceneCanvas/TurnControlBar）+ 3 个新增源文件（UnitMeasure/CoordUtilTest/UnitMeasureTest）+ 2 个 .dev 文档（contract3/coder3-report，未跟踪属正常）。**无 json/存档/模型/资源/gradle.properties/现有测试文件改动** ✅

---

## 3. 重点核查项（逐条）

### ① 测量线留存
- **CSV 导出兼容**：exportMeasureCsv 用 `measureLog.isEmpty()` / `forEachIndexed { i, (start, end) -> }` / `.size`（L365/372/384 实测）——SnapshotStateList 实现 MutableList，全部兼容，**导出函数零逻辑改动** ✅
- **淡色不遮单位**：saved=true 为 argb(150,...) 半透明 2f 细线 + 4f 圆点，绘制于单位之后但 alpha 低，可透出单位符号；契约设计如此 ✅
- **MainActivity 传参**：直接传 `vm.measureLog` 实例（同一快照列表），无中间拷贝 ✅
- **画布感知新条目的机制（契约假设 1 核心）**：**自洽，风险低**。SceneCanvas L60-61 `drawEpoch` 先例：draw 阶段快照读（`val epoch = drawEpoch`）→ 快照写（LaunchedEffect）→ draw 失效。本方案 `for (m in savedMeasures)` 在**同一 draw lambda** 内迭代 SnapshotStateList（迭代/size 均为快照读），`measureLog.add()`（VM 内快照写，主线程回调）即触发 Canvas draw 失效，无需重组。写方在 VM 不影响机制（Compose 快照写全局通知 draw 观察者）。**与 drawEpoch 机制同款，本文件内已有成功先例** ✅
  - 残余风险：无法在本环境（无真机）静态确认 draw 失效实际触发。**回退方案已在 Coder 报告 §5 记录**：onMeasureComplete 末尾加 `revision++`（走 drawEpoch 已验证链路）。此即本次"有条件"的条件①
- **回放模式**：savedMeasures 无条件绘制（无 replaying 守卫）——契约假设 6 采纳，与桌面版一致 ✅
- 退出测量模式线保留、多线条并存、同起点不去重、平移缩放随图变换：均从代码路径推导成立（measureLog 不清空、无去重、worldToScreen 每帧换算），留待手动验收

### ② 点选单位自动测量
- **纯函数正确性**：排除自身（filter idNum != unitId）✅；距离升序 sortedBy ✅；单位不存在/空场景 → emptyList ✅；复用 CoordUtil.distanceNm/bearingDeg ✅（单测实测通过）
- **绘制**：选中单位 worldToScreen 单独算一次，目标单位在 file 中找不到时 `continue` 防御 ✅；灰线 argb(160,90,90,90) 1.5f + 中点两行标签（名称/数值，深灰 13f + 白 STROKE 3f 两遍，可读性有保障）✅；回放时 MainActivity 传 null 不绘制 ✅
- **MainActivity 计算时机（性能与正确性）**：
  - 正确性：`!replaying` 短路（回放时完全不调用 unitDistances）✅；Do 移动后 revision 变更 → 重组 → 重算，距离实时刷新 ✅；选中单位变更 → selectedUnitId 读 → 重组刷新 ✅
  - 性能：`vm.camera` 在本组合作用域被读 → **平移/缩放每次手势重组都会重算 unitDistances**。O(n) 数学运算（每个单位一次 hypot+atan2），捆绑示例场景仅 7/4 个单位，微秒级，**当前无实际影响**。若后续场景规模大幅增长可加 `remember(selectedUnitId, revision, file)` 缓存 —— 提示级
  - 与长按编辑冲突：无。tap→onSelect / long-press→onLongPress 分属 detectTapGestures 不同回调，长按命中后不触发 onTap，选中态不被长按改变；UnitEditSheet 为 AlertDialog 模态盖上层（契约已验证事实）✅
  - 与选中高亮共存：高亮=符号 selected 态，辅助线独立绘制互不干扰 ✅
- **测量模式行为（契约内部矛盾，提示级）**：验收清单写"测量模式/回放模式下不显示"，但契约技术方案明确写"measureMode 下 pointerInput 不响应 tap，selectedUnitId 不变，**线保持（无害）**"。Coder 按技术方案实现（仅回放隐藏）。技术方案更具体、应视为权威，但验收清单字面不一致，**建议 Designer 确认**：若要求测量模式下也隐藏，需在 MainActivity 条件加 `&& !vm.measureMode`（一行）。当前实现无功能性错误

### ③ 设置按钮
- 文案「设置时长」✅；onIntervalSet 回调在写 `file.time.currentTurnInterval` **之后**调用，且用兜底后的 m/s 值，toast「回合时长已设为 X 分 Y 秒」信息完整 ✅
- 回合时长写入仍生效：`file.time.currentTurnInterval = TurnInterval(m, s)` 保留原逻辑；TurnState/Do 读 currentTurnInterval 未动（git diff 确认零改动）；TurnStateGateTest 3 用例全绿无回归 ✅

---

## 4. Coder 报告 2 点声明复核

### 声明 1：契约 §5 用例 7 终点坐标修正（非阻塞偏差，数学验证 ✅）
- 契约原文 `(100000,200000)→(-100000,-200000)` 自相矛盾：dx=-200000（-2 nmi）、dy=-400000（-4 nmi）→ dist=hypot(2,4)=**sqrt(20)≈4.472**、bearing=atan2(-2,-4)+360=**206.565°**，与契约断言（sqrt(8)/225°）不符
- Coder 修正为 `→(-100000,0)`：dx=dy=-200000（各 -2 nmi）→ dist=**sqrt(8)≈2.82842712** ✅、bearing=atan2(-2,-2)+360=**225.0** ✅
- **数学复核通过**；修正完整保留契约意图（象限 III 双负验证），CoordUtilTest 实测全绿。属合理偏差，需 Designer 知悉（契约文本笔误，非实现错误）

### 声明 2：假设 1（快照列表主方案，未加 revision++ 兜底）
- 风险评估：**低-中**。机制与 drawEpoch 同款自洽（见 §3①）；唯一不确定性在"draw 阶段对 SnapshotStateList 的迭代读是否注册 draw 失效"——与已证明有效的 drawEpoch 快照读属于同一 Compose 快照机制（读发生在同一 draw lambda、写经全局快照通知），理论成立
- 兜底路径明确且一行可改（onMeasureComplete 末尾 revision++），不影响已交付代码结构。**结论：风险可接受，但必须手动验收 ① 的线留存，作为本次有条件批准的条件①**

---

## 5. 契约 §5 验收清单逐条对照

### ① 测量留存
| 验收项 | 状态 | 依据 |
|--------|------|------|
| 松手后线保留（淡色区分） | ✅ 代码满足，待手动 | drawMeasureLine(saved=true) argb(150) 2f |
| 退出测量模式线仍显示 | ✅ 代码满足，待手动 | measureLog 不清空，无条件绘制 |
| 连续多条/同起点并存 | ✅ 代码满足，待手动 | savedMeasures 全量循环，无去重 |
| CSV 仍含全部记录 | ✅ 自动验证 | exportMeasureCsv 零改动，快照列表兼容（§3①） |
| 平移缩放随图变换 | ✅ 代码满足，待手动 | 每帧 worldToScreen |
| 回放模式照常显示 | ✅ 代码满足，待手动 | 无 replaying 守卫（假设 6） |

### ② 点选测量
| 验收项 | 状态 | 依据 |
|--------|------|------|
| 点选显示到每其它单位线+中点标签 | ✅ 代码满足，待手动 | 灰线+两行标签绘制块 |
| 换点刷新/点空白消失 | ✅ 代码满足，待手动 | selectedUnitId 读→重组；onSelect(null) |
| 数值与桌面版公式一致 | ✅ 自动验证 | CoordUtilTest 7 用例全绿 |
| 与选中高亮共存 | ✅ | 独立绘制层 |
| 长按编辑仍可用 | ✅ | 手势链路零改动，AlertDialog 模态 |
| 测量/回放模式不显示 | ⚠️ 部分（见 §3②矛盾） | 回放不显示 ✅；测量模式**显示**（按契约技术方案）——需 Designer 定夺 |

### ③ 设置按钮
| 验收项 | 状态 | 依据 |
|--------|------|------|
| 文案「设置时长」 | ✅ | TurnControlBar diff 实测 |
| toast「回合时长已设为 X 分 Y 秒」 | ✅ | onIntervalSet 回调 + vm.toast 复用 |
| 时长修改仍生效（Do/TurnState） | ✅ 自动验证 | 引擎/状态零改动，TurnStateGateTest 3 绿 |

---

## 6. 问题清单（分级）

### 🔴 阻塞（Blocking）：无

### 🟡 非阻塞（Minor）：1 项
1. **契约 §5 用例 7 终点坐标偏差**：契约文本 `(-100000,-200000)` 与自身断言矛盾（实际 dist≈4.472/206.565°）；Coder 改为 `(-100000,0)` 使断言成立（sqrt(8)/225°）。**数学复核通过**，修正合理且保留意图。需 Designer 知悉并在契约修订时更正原文。不影响交付。

### 🟢 提示级（Nit）：5 项
1. **契约假设 1 待手动验证（本次有条件批准的条件①）**：快照列表 draw 失效机制理论自洽（drawEpoch 同款），但本环境无真机无法确认；若 §7.2 手动验收① 项 1/2/3 不通过，按回退方案 onMeasureComplete 末尾加 `revision++`（一行）
2. **②测量模式行为契约内部矛盾**：验收清单"测量模式下不显示" vs 技术方案"线保持（无害）"。Coder 按技术方案实现。若 Designer 意图为隐藏，MainActivity 条件加 `&& !vm.measureMode` 即可
3. **②重组性能**：camera 变化（平移/缩放）触发 unitDistances 全量重算；当前场景仅 4-7 单位无影响，大规模场景可加 remember 缓存（非本轮必要）
4. **SceneCanvas L168 编译警告**（"Condition is always 'true'"）：经 git show HEAD 比对为**既有代码**，非本次引入，可不处理
5. **标签/线遮挡**：已保存线（alpha 150）与②灰线绘制在单位之上，密集场景中点标签可能重叠——契约假设 2 明确接受，无避让算法

---

## 7. 交付门槛核对（契约 §7.3）

| 门槛 | 结果 |
|------|------|
| 自动验证全绿（71 既有 + 12 新增 = 83） | ✅ 实测 83/83 |
| assembleDebug 成功 | ✅ |
| diff 仅含 §4 清单文件 | ✅ |
| git status 无存档/构建产物意外改动 | ✅（仅 4 M + 3 新源文件 + .dev 文档） |

**未修改任何代码，未执行 commit。**
