# Coder Report：三个 Bug 修复（阵营显示 / 标签缩放 / 手势冲突）

> 编码者 Coder（flash）| 日期：2026-08-08 | 契约：`.dev/contract.md` | 版本：v0.3.1 → v0.3.2（bump 由 release 步骤完成，见下）
> 基线：修复前 `testDebugUnitTest` 60 测试全过（其中 SideParsingTest 为**空转**：资源缺失 `?: return` 静默通过）

## 一、改动文件清单（5 个源文件 + 2 个测试资源）

| 文件 | 改动 | 对应契约条目 |
|---|---|---|
| `app/src/main/java/com/simplot/android/ui/GameViewModel.kt` | `applyLoaded`（loadScenario/loadSample 统一入口）加载后防御：units 非空且全部 `side=="Blue"`（Gson 缺字段默认值）→ `toast("场景单位缺少 Side 字段，已按蓝方显示")`。真实场景（冰海巨兽 2红5蓝、拉普拉塔 1红3蓝）不命中，不误报 | Bug 1（§4） |
| `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt` | **Bug 2**：`drawUnitLabel` 增 `zoom` 参数；`BASE_ZOOM=0.0015f`（Camera 初始 zoom）；`k = (zoom/BASE_ZOOM).coerceIn(0.5f, 4f)`；`textSize = (11f*k).coerceIn(8f, 28f)`；锚点偏移 `sx+10f*k, sy-8f*k`。回放 + 正常两处调用传 `camera.zoom`。默认 zoom 下 k=1，行为与旧 11f/10/-8 完全一致<br>**Bug 3**：transform 手势块 `pointerInput(Unit)` → `pointerInput(measureMode)`，块首 `if (measureMode) return@pointerInput`（测量模式完全不注册 transform 手势）；点选/画线块 `pointerInput(file)` → `pointerInput(file, measureMode)`（见下方“契约外补充”）；C1 的 pan 禁用保留为双保险 | Bug 2、Bug 3（§4） |
| `app/src/main/java/com/simplot/android/render/UnitRenderer.kt` | `sideColors` 与 `colorOf` 兜底由 `Color.rgb(...)` 内联为纯 Kotlin Int 常量（**逐字节同值**：Blue=0xFF005AC8、Red=0xFFC81E1E、Neutral=0xFF787878、Unknown=0xFF5A5A5A）。零行为变化 | Bug 1 测试使能（见下方说明） |
| `app/src/test/java/com/simplot/android/SideParsingTest.kt` | 修空转：资源缺失改为 `assertNotNull` 失败（不再 `?: return`）；真实断言冰海巨兽 **2 红 5 蓝**、拉普拉塔 **1 红 3 蓝**；新增解析→渲染色值管道断言（每单位 `colorOf(side)` 与阵营色一致）；新增 `colorOf("Red") != colorOf("Blue")` | Bug 1（§4） |
| `app/src/test/resources/scenarios/冰海巨兽.json`、`拉普拉塔河口海战.json` | 自 `app/src/main/assets/scenarios/` 复制（cp，中文文件名正常） | Bug 1（§4） |

> 注：`CHANGELOG.md` + `app/build.gradle.kts`（versionName 0.3.2 / versionCode 9）的 0.3.2 条目与 bump 由主代理 release 步骤完成（21:29 并发写入，非本 Coder 改动）。本报告成稿时 debug APK 已重建为 0.3.2。

## 二、每项修复的依据与实现细节

### Bug 1（阵营显示防御 + 测试修复）
- 根因链已验证：`Unit.side` 默认值 `"Blue"`（Unit.kt），Gson 对缺字段静默落默认值；两个内置场景 Side 正确（脚本实测 7 单位=2红5蓝、4 单位=1红3蓝）；`colorOf` 映射正确（Blue=0xFF005AC8、Red=0xFFC81E1E）。
- 防御放在 `applyLoaded`（loadScenario 与 loadSample 的唯一汇聚点）。注意：toast 为单槽 StateFlow，警告 toast 会覆盖“已加载场景”toast（同步赋值 conflate 后取最后一个）——警告信息优先展示，符合契约意图。
- 测试修复：`getResourceAsStream` 结果 null 时直接断言失败（`assertNotNull` + `!!`），彻底消除空转。

### Bug 2（标签随缩放）
- 实现完全按契约：字号 `11f * zoom / BASE_ZOOM` clamp 8..28f；锚点偏移乘 k。k 的 clamp 区间契约未硬性指定，取 `coerceIn(0.5f, 4f)`（契约文本“coerce 后”），默认 zoom 下 k=1 与原视觉一致；放大 10 倍时字号达 28f 上限（验收条款的 clamp 语义）。
- ⚠️ 与 CHANGELOG 条目的差异提示：CHANGELOG 中写“k clamp 0.7..2.5”，实际实现为 **0.5..4**（文字描述与实现有出入，建议 release 步骤核对修正）。

### Bug 3（手势互斥）
- transform 块 key 改 `measureMode`：测量模式切入时协程取消重启 → 块首直接 return，**不注册** transform 手势（单指=画线、无地图拖动、双指缩放同样禁用）；退出测量后重启恢复。
- **契约外补充（必要，请审阅）**：点选/画线块 key 由 `pointerInput(file)` 改为 `pointerInput(file, measureMode)`。证据：反编译 Compose UI 1.7.3 `SuspendingPointerInputModifierNodeImpl.update$ui_release`（javap 字节码）确认 pointerInput **仅在 key 变化时重启协程**（`Intrinsics.areEqual` + `Arrays.equals` 比对 key，变化才 `resetPointerInputHandler`），块内读取的 `measureMode` 是陈旧值——原代码块内 `if (measureMode) detectDragGestures else detectTapGestures` 分支在首次组合后永不切换（C1 在 transform 块内 `val measuring = measureMode` 同理失效）。若不加此 key，测量模式“单指拖动画线”永不生效（画线块始终停留在 tap 分支），Bug 3 验收标准“测量模式下单指拖动画线不移动地图”无法满足。加 key 后模式切换即时生效，且不改变任何手势逻辑本身。
- 保留 C1 的 pan 禁用（双保险），未删。

## 三、测试结果

```
com.simplot.android.CameraMathTest:       5 tests, 0 failures
com.simplot.android.EngineTest:          21 tests, 0 failures
com.simplot.android.ReplayTest:          15 tests, 0 failures
com.simplot.android.MapDataParserTest:    5 tests, 0 failures
com.simplot.android.ScenarioRoundTripTest: 7 tests, 0 failures
com.simplot.android.SpScnCodecTest:       5 tests, 0 failures
com.simplot.android.SideParsingTest:      4 tests, 0 failures   ← 修复空转 + 新增 2 断言
─────────────────────────────────────────────────
TOTAL: 62 tests, 0 failures, 0 errors, 0 skipped   （契约要求 ≥ 59 ✓）
```

SideParsingTest 4 用例：`side parsed from json` / `full scenario side distribution`（冰海巨兽 2红5蓝 + 逐单位 colorOf 管道断言）/ `la plata scenario side distribution`（1红3蓝）/ `red and blue render colors differ`（colorOf(Red)≠colorOf(Blue)）。

## 四、构建结果

- `./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1` → **BUILD SUCCESSFUL**（含 0.3.2 bump 后的重建；APK `app/build/outputs/apk/debug/app-debug.apk`，versionName **0.3.2** / versionCode **9**，9.8MB）。

## 五、遗留风险 / 需上游关注

1. **UnitRenderer.kt 属契约触碰文件之外**（契约表未列）。为使契约强制的 `colorOf` 断言在 JVM 单测可用而做的最小使能改动：`android.graphics.Color.rgb` 在 mockable android.jar 下会抛 “not mocked”（AGP 默认），无法直接断言；内联常量与 `Color.rgb` 逐字节同值，**零运行时行为变化**。若审阅不接受此文件改动，替代方案是引入 Robolectric（重依赖，沙箱内存/下载风险大，不建议）。
2. **CHANGELOG 0.3.2 条目与实现的两处出入**（release 步骤已写入，建议修正）：① “59 测试全过”→ 实际 **62**；② “k clamp 0.7..2.5”→ 实际 **0.5..4**。
3. **已知既有问题（未在契约范围，未改）**：画线/点选块内 `if (replaying)` 同样存在陈旧值问题（key 未含 `replaying`），回放模式下点选回调理论上仍可能触发——与本次三 Bug 无关，建议另开任务。
4. **Bug 1 防御为启发式**：合法全蓝场景（如护航队生成）也会弹警告（toast 文案提示“可能缺 Side 字段”，非硬错误），符合契约“按最可能根因防御”的取舍。
5. **未做真机手势验证**：本沙箱无 UI 环境，Bug 2/Bug 3 为代码级验证（编译 + 单测 + 语义分析），需按契约 §6 手动步骤回归：加载示例看红蓝阵营、双指放大看标签、测量模式画线不动地图。
6. **未提交**：所有改动在工作区未 commit（`git status` 含本报告、测试资源等未跟踪文件），提交/发布由 release 步骤执行。
