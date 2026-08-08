# 审核报告：三个 Bug 修复（阵营显示 / 标签缩放 / 手势冲突）

> 审核者 Reviewer（pro）| 日期：2026-08-08 | 契约：`.dev/contract.md` | Coder 报告：`.dev/coder-report.md`
> 基线：`git diff` 5 文件（+45/−16）+ 2 个测试资源（未跟踪）+ SideParsingTest.kt（未跟踪）

## 结论：✅ 通过（附 2 处文档级修正建议，不阻塞）

三处修复均实现正确且与契约验收标准一致；测试全绿（62/62）；强制全量重建成功；APK 为 0.3.2（versionCode 9）。无功能级 bug。发现 2 处**文档/报告级出入**（CHANGELOG 测试数、Coder 报告 clamp 值描述与实现不符），1 处契约验收措辞自相矛盾（非代码问题），建议上游修正。

---

## 一、逐项审核发现

### Bug 1：阵营红蓝不分（防御 + 测试修复）— ✅ 通过

- **防御落点正确**：`GameViewModel.applyLoaded`（`GameViewModel.kt:119-125`）确为 `loadScenario` 与 `loadSample` 的唯一汇聚点；两入口均调用它。检测逻辑 `units.isNotEmpty() && units.all { it.side == "Blue" }` → `toast("场景单位缺少 Side 字段，已按蓝方显示")`。
- **根因链核实**：`Unit.kt:20` `@SerializedName("Side") var side: String = "Blue"` —— Gson 缺字段静默落默认值，实锤。
- **不误报核实**：冰海巨兽 2 红 5 蓝、拉普拉塔 1 红 3 蓝，均含 Red，不触发防御分支。
- **SideParsingTest 真实验证**（非空转）：
  - 资源存在：`app/src/test/resources/scenarios/冰海巨兽.json`、`拉普拉塔河口海战.json` 与 `main/assets/scenarios/` **逐字节一致**（diff 无输出）。
  - 无静默返回：`resourceText()` 用 `assertNotNull("test resources 缺少 $path", text)` + `!!`——资源缺失即测试失败，不存在 `?: return` 空转路径。
  - 4 用例全部真实断言且通过（见测试输出）：冰海巨兽 2 红 5 蓝、拉普拉塔 1 红 3 蓝、逐单位解析→渲染色值管道断言、`colorOf("Red") != colorOf("Blue")`。
- **Toast 细节**：`toasts` 为单槽 StateFlow（conflate）+ `collectAsState` + `LaunchedEffect`（MainActivity.kt:91-97），警告 toast 会覆盖"已加载场景"确认 toast——警告优先展示，符合契约意图；代价是命中警告时用户看不到加载确认信息（可接受，记录备查）。

### Bug 2：标签随 zoom 缩放 — ✅ 通过（契约验收措辞自相矛盾，见遗留风险）

- `BASE_ZOOM = 0.0015f` == `Camera.zoom` 初始值（`Camera.kt:16` `mutableFloatStateOf(0.0015f)`）✓
- **默认 zoom 下与原行为逐值一致**：zoom=0.0015 → k=1、textSize=11f×1=11f、偏移 `sx+10f*1, sy-8f*1` —— 与旧 `11f / +10 / -8` 完全相同 ✓
- 公式采用契约 §4 方案 B：`textSize = (11f * zoom/0.0015f).coerceIn(8f, 28f)`；偏移乘 k，`k = (zoom/0.0015f).coerceIn(0.7f, 2.5f)` ✓
- 两处调用点（回放帧 + 正常渲染）均传 `camera.zoom` ✓
- **⚠️ Coder 报告自身出入（不阻塞）**：报告 §二 写"取 `coerceIn(0.5f, 4f)`"、§五.2 声称"CHANGELOG 写 0.7..2.5 而实现为 0.5..4"——**与事实相反**。实际代码（SceneCanvas.kt:214）就是 `coerceIn(0.7f, 2.5f)`，与 CHANGELOG 完全一致。Coder 报告描述有误，代码与 CHANGELOG 是对的；建议修正报告。
- 微观察：textSize 用原始比值 clamp [8,28]，偏移用 k clamp [0.7,2.5]，比值 <0.7× 或 >2.5× 时字号与偏移缩放不再严格同步（如比值 0.667 时 textSize=8 而 k=0.7）。纯观感差异，两处各自有界，无功能问题。

### Bug 3：手势冲突 — ✅ 通过（javap 论证核实为真）

- **transform 块**：`.pointerInput(Unit)` → `.pointerInput(measureMode)` + 块首 `if (measureMode) return@pointerInput`。测量模式下 transform 手势完全不注册（单指=画线、无 pan、无双指缩放），符合契约推荐方案 ✓
- **javap 字节码核实（compose-ui 1.7.3，`SuspendingPointerInputModifierNodeImpl.update$ui_release`）**：`key1/key2` 用 `Intrinsics.areEqual`、`keys` 数组用 `Arrays.equals` 比对，任一变化才 `resetPointerInputHandler()`（重启协程）。**Coder 的论证逐字节属实**：key=Unit 时块内读 `measureMode` 是陈旧值，C1 的 pan 禁用确实不生效。
- **契约外补充 `.pointerInput(file, measureMode)` 第二个 key：必要且正确**。
  - 必要性：原块 `if (measureMode) detectDragGestures else detectTapGestures` 在协程启动时分支定死；`file` 不变时切换测量模式**不会**重启协程（javap 证实），块永远停留在首次组合的 tap 分支——测量拖画线永不生效，契约验收"测量模式下单指拖动画线"无法满足。加 `measureMode` 为 key 后切换即时生效。
  - 正确性：只新增 key、不改任何手势逻辑本身；双 key 变化触发协程取消重启，无状态残留。✓
- **C1 pan 禁用保留为双保险**：`val measuring = measureMode` 在注册时恒为 false，属冗余防御，无害（不算死代码）。
- **已知既有问题（非本次范围）**：点选/画线块内 `if (replaying)` 读的是陈旧值（`replaying` 不在 key 中），回放切换时手势不重启。git HEAD 原代码即如此（`.pointerInput(file)` 同款模式），确认为**既有问题**，Coder 已如实标注，建议另开任务。

### 契约外改动：UnitRenderer.kt Color.rgb → 纯 Kotlin 常量 — ✅ 可接受

- **逐字节一致（python 算术核实）**：
  - `Color.rgb(0,90,200)` = 0xFF005AC8（90=0x5A、200=0xC8）✓
  - `Color.rgb(200,30,30)` = 0xFFC81E1E（200=0xC8、30=0x1E）✓
  - `Color.rgb(120,120,120)` = 0xFF787878（120=0x78）✓
  - `Color.rgb(90,90,90)` = 0xFF5A5A5A ✓
  - `android.graphics.Color.rgb` alpha 恒为 0xFF，与常量一致。
- **字面量类型安全**：`0xFF005AC8` 超 Int.MAX（Kotlin 推断为 Long），`.toInt()` 截断保留低 32 位 = 同一位模式（int32 -16753976），无误。
- **运行时零变化**：`colorOf` 返回值与原来完全相同；`android.graphics.Color` 仍在文件内使用（第 98/108/121/137 行），import 无死代码。
- **必要性成立**：`app/build.gradle.kts` 无 `unitTests.isReturnDefaultValues` 配置 → mockable android.jar 下 `Color.rgb` 抛 "not mocked"，契约强制的 `colorOf("Red") != colorOf("Blue")` 断言在 JVM 单测不可行；替代方案 Robolectric 重依赖（沙箱 3.4G 内存/下载风险），本方案为最小使能改动。**可接受**。

### 代码质量 — ✅

- 注释（中文）与代码库风格一致，三处修复均有出处注释（契约条目/根因/C1 溯源）；命名清晰；无死代码、无未使用 import、无编译警告（`--rerun-tasks` 全量编译无 warning 输出）。

---

## 二、验证执行记录（原样）

### 1. 测试（强制重跑，非 UP-TO-DATE）

`rm -rf app/build/test-results && ./gradlew testDebugUnitTest --no-daemon --max-workers=1` → BUILD SUCCESSFUL

```
com.simplot.android.CameraMathTest:        5 tests, 0 failures, 0 errors, 0 skipped
com.simplot.android.EngineTest:           21 tests, 0 failures, 0 errors, 0 skipped
com.simplot.android.MapDataParserTest:     5 tests, 0 failures, 0 errors, 0 skipped
com.simplot.android.ReplayTest:           15 tests, 0 failures, 0 errors, 0 skipped
com.simplot.android.ScenarioRoundTripTest: 7 tests, 0 failures, 0 errors, 0 skipped
com.simplot.android.SideParsingTest:       4 tests, 0 failures, 0 errors, 0 skipped
com.simplot.android.SpScnCodecTest:        5 tests, 0 failures, 0 errors, 0 skipped
TOTAL: 62 tests, 0 failures, 0 errors, 0 skipped
```

SideParsingTest 4 用例逐条 PASS（`full scenario side distribution` 0.011s 等，XML 实测）。资源缺失场景下 `assertNotNull` 会失败——测试无法空转。

### 2. 构建（强制全量重建）

`./gradlew assembleDebug --rerun-tasks --no-daemon --max-workers=1` → **BUILD SUCCESSFUL in 59s**（compileDebugKotlin/packageDebug/assembleDebug 全部真实执行）

```
app/build/outputs/apk/debug/app-debug.apk  9,777,503 B
aapt badging: package='com.simplot.android' versionCode='9' versionName='0.3.2'
```

### 3. SideParsingTest 真实验证确认

- 资源在 test classpath：`app/src/test/resources/scenarios/` 两 JSON 与 main assets 逐字节一致（diff 无差异）
- 无 `?: return` 静默路径：`resourceText` = `assertNotNull` + `!!`
- colorOf 断言：`red and blue render colors differ` PASS；解析→色值管道断言（冰海巨兽 7 单位逐单位校验）PASS

---

## 三、Bug 清单

无功能级 bug。以下为文档/流程级问题：

| # | 级别 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| 1 | 文档 | CHANGELOG 0.3.2 写"59 测试全过"，实际 **62**（基线 60，契约阈值 ≥59） | CHANGELOG.md:18 vs 测试输出 | release 步骤改 62 |
| 2 | 文档 | Coder 报告 §二/§五.2 称 k clamp 为 0.5..4 且与 CHANGELOG 0.7..2.5 不符——**实际代码即 0.7..2.5**，与 CHANGELOG 一致，报告描述反了 | SceneCanvas.kt:214 vs coder-report §二 | 修正报告；代码无需改 |
| 3 | 契约措辞 | 契约 Bug2 验收"zoom 放大 10 倍时标签 ≥ 原来 10 倍视觉大小（clamp 到 28f 上限）"自相矛盾：28f/11f=2.55×，任何 clamp 下都不可能 ≥10× | contract.md §1 Bug2 | 以 clamp 语义为准（实现正确）；契约措辞下次修订 |

## 四、遗留风险与建议

1. **无真机手势/视觉验证**（沙箱无 UI 环境）：Bug 2 的标签观感、Bug 3 的单指画线/双指缩放互斥为代码级 + 字节码级验证，需按契约 §6 手动回归：加载示例看红蓝阵营 → 双指放大看标签 → 测量模式画线地图不动。
2. **Bug 1 防御为启发式**：合法全蓝场景（如护航队生成器产物）会误弹警告（文案为"可能缺 Side 字段"，非硬错误）——契约明示的取舍，接受。
3. **Bug 1 toast 覆盖**：命中警告时"已加载场景"确认 toast 被覆盖（单槽 StateFlow conflate），用户少一条确认信息——若在意可改为双槽队列，非本任务范围。
4. **既有问题（另开任务）**：点选/画线块 `if (replaying)` 陈旧值（key 缺 `replaying`），回放切换时点选手势理论上仍可能触发——Coder 已标注，非本次引入。
5. **提交状态**：全部改动未 commit（`git status`：5 modified + 3 untracked + `.dev/`），由 release 步骤统一提交发布。

---

*审核方法：git diff 全文审阅 + 源码逐行核对 + compose-ui 1.7.3 AAR javap 反编译核实 + 强制重跑测试与全量重建 + 色值算术验证。*
