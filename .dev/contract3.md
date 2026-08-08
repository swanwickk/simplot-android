# Contract 3 — SimPlot Android v0.3.3 三个反馈问题修复契约（Designer 产出）

> 版本：v1.0（最终）｜ 设计者：Designer（dev-pipeline 第 3 轮）
> 项目：/home/node/.openclaw/workspace/simplot-android（v0.3.3，Kotlin/Compose MVVM）
> 构建命令（勿改 gradle.properties）：
> `export LANG=C.utf8 LC_ALL=C.utf8 JAVA_HOME=/home/node/.openclaw/workspace/toolchain/jdk-17.0.2 ANDROID_HOME=/home/node/.openclaw/workspace/toolchain/android-sdk && cd /home/node/.openclaw/workspace/simplot-android && ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1`
> 权威坐标参考：/home/node/.openclaw/workspace/simplot-desktop/analysis/桌面版反编译对照分析.md（§9 测量工具）+ BH2VOQ-ATG/simplot/scn_tool.py
> 基线：**71 个单测全绿**（8 个测试文件 @Test 计数：8+36+5+7+4+5+3+3=71），不得回归

## 0. 问题总览（含验证结论）

| # | 反馈 | 根因（已验证程度） | 修复归属 |
|---|------|--------------------|----------|
| ① | 测量线拖完松手就消失 | **确认**：SceneCanvas 测量线仅存于本地 `measureStart/measureEnd`（remember 状态，onDragEnd 置 null）；`vm.measureLog` 只用于 CSV 导出从未画回画布；且 measureLog 是**普通 MutableList（非快照列表）**，即使传参也无法触发重绘 | GameViewModel + SceneCanvas + MainActivity |
| ② | 点单位想自动测量与其它单位距离/角度 | **确认（新功能）**：hitTest→onSelect→selectedUnitId 链路已存在；CoordUtil.distanceNm/bearingDeg 已存在且纯函数；桌面版 §9 **无点选列表功能**（仅拖拽测量线）→ 属 Android 增强，交互自定 | 新文件 UnitMeasure.kt + SceneCanvas + MainActivity |
| ③ | 设置按钮不知道用途 | **确认**：TurnControlBar「设置」按钮=把分/秒输入框写入 `file.time.currentTurnInterval`，无 toast、无说明、文案含糊；Do/TurnState 均直接读 currentTurnInterval → 修改本身已生效，缺的只是「告知」 | TurnControlBar + MainActivity |

### 验证过的关键事实（证据）
- **SceneCanvas.kt**：`measureStart/measureEnd` 为 remember 局部状态（~L61-63）；onDragEnd 置 null 后回调 `onMeasureDone`（~L111-119）；绘制块（~L195-232）只画 `ms/me` 临时线；`hitTest` 在文件底部（internal fun，可复用）。
- **GameViewModel.kt**：`val measureLog = mutableListOf<Pair<Pair<Long,Long>,Pair<Long,Long>>>()`（**普通 MutableList**）；`onMeasureComplete`（~L355-359）只 add + measureMode=false + toast，**不 revision++**；`exportMeasureCsv` 遍历 measureLog（SnapshotStateList 兼容）。
- **CoordUtil.kt**（纯函数，无 Android 依赖）：`distanceNm(x1,y1,x2,y2): Double`=hypot(dx,dy)/1e5 海里；`bearingDeg`=罗盘角（0=北=正 Y，顺时针，atan2(dx,dy)）。
- **CameraMath.kt**：`sy = canvasH/2 - (wy-centerY)*zoom` —— Y 翻转已修复，北在上；方位角为世界罗盘角，与屏幕朝向无关。
- **UnitEditSheet.kt**：是 **AlertDialog**（非底部弹层）→ ② 的选中态显示与长按编辑弹窗**无遮挡冲突**（对话框模态盖在上层）。
- **TurnState.kt**：undo/confirmNext 回写 currentTurnInterval（L64/L79）；VM.doTurn 用 `f.time.currentTurnInterval` 驱动移动 → 改时长即生效（逻辑勿动）。
- **TurnControlBar.kt**：分/秒输入框 + 「设置」按钮（~L96-104），无任何反馈回调参数。
- **MainActivity.kt**：SceneCanvas 调用处传 `tick = vm.revision`、`measureMode`、`onMeasureDone`；Toast 已走 `vm.toasts` StateFlow 订阅（可复用）。

## 1. 问题① 测量线拖完松手就消失（不留存）

### 目标（验收标准）
- [ ] 测量松手后线**保留在画布上**（淡色，与拖拽中临时线区分）
- [ ] 退出测量模式（measureMode=false，点「退出测量」）后已保存线仍显示
- [ ] 连续画多条，全部显示；重复同起点画多条（与桌面版一致，无去重）
- [ ] 导出 CSV 仍含全部记录（exportMeasureCsv 零逻辑改动）
- [ ] 平移/缩放后线随地图正确变换（worldToScreen 实时换算，天然满足）
- [ ] 回放模式下已保存线照常显示（与现临时线绘制位置一致，无条件绘制）

### 非目标
- 不做测量线的删除/编辑/拖拽调整/清空按钮
- 不做数量上限（桌面版无限制）；不做测量线持久化到存档

### 技术方案（根因 → 修复）
**根因**：① 测量数据（measureLog）与绘制源（本地 ms/me）分离，且 measureLog 无快照可观察性 → 加数据进列表不会触发任何重绘。

**修复（3 处，最小 diff）**：
1. **GameViewModel.kt**（1 行改动）：`measureLog` 声明改为快照列表：
   `val measureLog = mutableStateListOf<Pair<Pair<Long, Long>, Pair<Long, Long>>>()`
   （import `androidx.compose.runtime.mutableStateListOf`。SnapshotStateList 实现 MutableList，`exportMeasureCsv`、`measureLog.isEmpty()`、`.size`、`forEachIndexed` 全部兼容，**其余零改动**。不需要 revision++：draw 阶段迭代读取快照列表 → 变更即失效重绘，与现有 drawEpoch 快照读机制同款。）
2. **SceneCanvas.kt**：
   - 新参数：`savedMeasures: List<Pair<Pair<Long, Long>, Pair<Long, Long>>> = emptyList()`（默认值 → 其它调用方不破坏）。
   - 抽取私有绘制函数 `drawMeasureLine(canvas, camera, w, h, start, end, saved: Boolean)`：saved=true 用 `argb(150, 220, 60, 40)` 2f 细线 + 4f 起点圆点 + 标签 `"%.1f nmi  方位 %.0f°"`（沿用现有 16f 字号白底红字两遍绘制）；saved=false 保持现状（argb(230,...) 3f）。
   - 绘制块（现 ~L195 起）：**先**遍历 `savedMeasures` 逐条 drawMeasureLine(saved=true)（draw 阶段读快照 → 自动重绘），**再**画临时 ms/me（saved=false）。放在单位绘制之后、比例尺条之前（与现状同位置）。
3. **MainActivity.kt**（1 行）：SceneCanvas 调用处加 `savedMeasures = vm.measureLog`。

**不触碰**：`onMeasureComplete`（只加数据+退出模式+toast 的行为保留）、exportMeasureCsv、存档格式。

## 2. 问题② 点击单位自动测量与其它单位的距离和角度（新功能）

### 目标（验收标准）
- [ ] 点某单位 → 画布上显示「从该单位到**每个其它单位**」的细线 + 中点标签（名称/距离 nmi/方位 °）
- [ ] 换点其它单位 → 立即刷新；点空白处（onSelect(null)）→ 线全部消失
- [ ] 距离数值与桌面版公式一致（复用 CoordUtil，单测断言）
- [ ] 与选中高亮共存（高亮=单位符号 selected 态，线=辅助线，互不干扰）
- [ ] 长按编辑仍可用（hitTest + onLongPress 链路零改动；UnitEditSheet 是 AlertDialog，模态盖在上层无冲突）
- [ ] 测量模式/回放模式下不显示（避免与临时线混淆、避免回放帧位置错位）

### 非目标
- 不做距离列表的持久化、CSV 导出、排序筛选 UI
- 不做底部列表弹层组件（本次采用画布线方案；若后续要列表，数据层 UnitDistance 可直接复用）
- 不改 hitTest / 选中 / 长按手势链路

### 技术方案
**数据层（纯 Kotlin，可单测）**：新建 `app/src/main/java/com/simplot/android/data/util/UnitMeasure.kt`：
```kotlin
data class UnitDistance(
    val idNum: String, val name: String, val side: String,
    val distNm: Double, val bearingDeg: Double
)

/** 点选单位到所有其它单位的距离/方位，按距离升序。file 为空/单位不存在 → 空列表 */
fun unitDistances(file: ScenarioFile, unitId: String): List<UnitDistance>
```
实现：`file.units.firstOrNull { it.idNum == unitId }` 找不到返回 emptyList()；对其它每个单位算 `CoordUtil.distanceNm/bearingDeg`，`sortedBy { it.distNm }`；排除自身。
（顶层函数而非 VM 成员方法：**避免 AndroidViewModel 需 Application 实例化导致单测依赖 Robolectric**。）

**UI 层（画布细线 + 标签，随选中显示）**：
1. **MainActivity.kt**（SceneCanvas 调用处）：
   ```kotlin
   val unitDist = if (!replaying) vm.selectedUnitId?.let { unitDistances(f, it) } else null
   ```
   传 `unitDistances = unitDist` 给 SceneCanvas。selectedUnitId 是 mutableStateOf → 点选即重组重算；Do 后单位移动（revision 变更重组）→ 距离实时刷新。
2. **SceneCanvas.kt**：新参数 `unitDistances: List<UnitDistance>? = null`。绘制块（savedMeasures 之后、临时线之前）：列表非空时，选中单位屏幕坐标 → 每单位画 1.5f 灰线（`argb(160, 90, 90, 90)`）+ 中点标签 `"名称\n%.1f nmi %.0f°"`（深灰字 13f，两遍绘制白描边保证可读）。
   - 标签方向：bearingDeg 为世界罗盘角（0=北顺时针），CameraMath Y 翻转已修复（北在上）→ 屏幕与数值一致，**无需额外换算**。
   - 与测量模式：measureMode 下 pointerInput 不响应 tap，selectedUnitId 不变，线保持（无害）；回放时 MainActivity 传 null 不画。
   - 密集场景标签可能重叠：v0.3.3 接受，不做避让算法（见假设）。

**交互定稿说明**：采用「选中即测」而非独立开关 —— 理由：零新按钮、零新手势、复用现有 onSelect；退出=点空白。与长按编辑无冲突（不同手势：tap vs long-press，pointerInput 分别注册）。

## 3. 问题③ 设置按钮用途不明

### 目标（验收标准）
- [ ] 按钮文案从「设置」改为「**设置时长**」→ 用户见文案即懂（与左侧「回合时长」标签呼应）
- [ ] 点击后有 toast 反馈：「回合时长已设为 X 分 Y 秒」
- [ ] 回合时长修改仍生效：Do 按新时长移动、TurnState 依赖 currentTurnInterval（**逻辑零改动**，仅验证不回归）

### 非目标
- 不改分/秒校验规则（现有兜底：非数字→3/0）、不改 TurnInterval/TimeState/TurnState/MovementEngine
- 不加输入框旁说明文字（按钮文案+toast 已满足验收；说明文字属可选项，本次不做）

### 技术方案（最小改动，2 处）
1. **TurnControlBar.kt**：
   - 新参数：`onIntervalSet: ((minutes: Int, seconds: Int) -> Unit)? = null`
   - 「设置」→「设置时长」；onClick 内写完 `file.time.currentTurnInterval` 后追加 `onIntervalSet?.invoke(m, s)`
2. **MainActivity.kt**（TurnControlBar 调用处）：`onIntervalSet = { m, s -> vm.toast("回合时长已设为 $m 分 $s 秒") }`（复用现有 toasts StateFlow 订阅，无需新机制）

## 4. 触碰文件清单

| 文件 | 改动 |
|------|------|
| app/src/main/java/com/simplot/android/ui/GameViewModel.kt | measureLog → `mutableStateListOf(...)`（1 行 + import） |
| app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt | +`savedMeasures` 参数、+`unitDistances` 参数、抽取 `drawMeasureLine`、绘制块加两段绘制 |
| app/src/main/java/com/simplot/android/MainActivity.kt | +`savedMeasures = vm.measureLog`；计算 `unitDistances` 并传参；+`onIntervalSet` toast |
| app/src/main/java/com/simplot/android/ui/components/TurnControlBar.kt | 文案「设置时长」+ `onIntervalSet` 回调参数 |
| **新增** app/src/main/java/com/simplot/android/data/util/UnitMeasure.kt | `UnitDistance` data class + `unitDistances` 顶层纯函数 |
| **新增** app/src/test/java/com/simplot/android/CoordUtilTest.kt | CoordUtil 距离/方位断言（7 用例） |
| **新增** app/src/test/java/com/simplot/android/UnitMeasureTest.kt | unitDistances 行为断言（5 用例） |

**零改动**：存档模型/编解码（SpScnCodec/Scenario/Unit/TimeState）、引擎（TurnState/MovementEngine/ReplayEngine/FogOfWar）、渲染器（CameraMath/Camera/UnitRenderer/MapRenderer/ArcRenderer/TrackRenderer）、gradle.properties、现有 8 个测试文件。

## 5. 新增测试清单（预期 71 → ~83）

**CoordUtilTest.kt**（新文件，纯 JUnit，无 Android 依赖；断言与桌面版 scn_tool.py 公式一致）：
1. 同点：`distanceNm(0,0,0,0) == 0.0`
2. 正北 1 nmi：`distanceNm(0,0,0,100000)` ≈ 1.0（±1e-6）；`bearingDeg(0,0,0,100000)` == 0.0
3. 正东 1 nmi：`(0,0)→(100000,0)`：dist ≈ 1.0；bearing == 90.0
4. 正南/正西：`(0,0)→(0,-100000)` bearing == 180.0；`(0,0)→(-100000,0)` bearing == 270.0
5. 斜向：`(0,0)→(100000,100000)`：dist ≈ sqrt(2)（1.41421356…）；bearing == 45.0
6. 大距离：`(0,0)→(500000,0)` dist ≈ 5.0
7. 负坐标：`(100000,200000)→(-100000,-200000)`：dist ≈ sqrt(8)（2.82842712…），bearing == 225.0（验证象限处理）

**UnitMeasureTest.kt**（新文件；构造 ScenarioFile 直接调顶层函数，无需 VM 实例）：
1. 3 单位（A@(0,0)，B@(0,100000) 北 1 nmi，C@(200000,0) 东 2 nmi）：`unitDistances(file,"A")` 返回 **2 条**；排除自身；含 B/C
2. 排序：distNm 升序（B 1.0 在前，C 2.0 在后）
3. 数值：B 的 distNm≈1.0、bearingDeg≈0.0；C 的 distNm≈2.0、bearingDeg≈90.0；name/idNum/side 字段透传正确
4. 单位不存在：`unitDistances(file, "ZZZ")` → emptyList()
5. 空/单单位场景：units 为空或仅 1 个 → emptyList()

（③ 无新单测：纯 UI 文案+回调；TurnInterval 已有逻辑，验收靠手动 + 现有 TurnStateGateTest 覆盖的时长回写不回归。）

## 6. 假设（无法在静态验证中确认的项）

1. **SnapshotStateList 在 draw 阶段迭代读能触发 Canvas 失效** —— 机制与现有 drawEpoch（draw 内快照读）同款，风险低；**回退方案**：若重绘不触发，在 `onMeasureComplete` 末尾加 `revision++`（走 drawEpoch 已验证链路，feedback④ 同款）。实现时优先用快照列表方案，构建+手动验证① 验收项 1/2/3 后确认。
2. **② 密集场景标签重叠可接受** —— 不做标签避让；如验收反馈差，后续轮次再加（本轮不做）。
3. **无 Compose UI 自动化测试设施**（项目现仅 JVM 单测）→ UI 行为（线绘制、toast、文案）全部走手动验收清单。
4. **Toast 文案**「回合时长已设为 X 分 Y 秒」为设计建议，实现可按项目风格微调（须保留「已设置+数值」信息）。
5. **③ 按钮文案**「设置时长」已足够传达用途（与左侧「回合时长」标签并排），不追加说明文字。
6. 回放模式画已保存测量线（①）与桌面版一致（桌面版测量不随回放隐藏）；如实际体验冲突可在验收时决定，不影响本次交付。

## 7. 验证计划

### 7.1 自动验证（构建 + 单测）
```bash
export LANG=C.utf8 LC_ALL=C.utf8 JAVA_HOME=/home/node/.openclaw/workspace/toolchain/jdk-17.0.2 ANDROID_HOME=/home/node/.openclaw/workspace/toolchain/android-sdk
cd /home/node/.openclaw/workspace/simplot-android
./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1
```
- 通过标准：**既有 71 用例全绿 + 新增 ~12 用例全绿**（总数 ~83）；assembleDebug 成功。
- 检查点：UnitMeasureTest 不依赖 Android 类（JVM 直跑）；CoordUtilTest 数值断言与 scn_tool.py 公式一致。

### 7.2 手动验收步骤（真机/模拟器，打开示例「冰海巨兽」）
**① 测量留存**：
1. 点「测量」→ 拖动画线 → 松手：toast「已记录测量线 N 条」，**线保留在画布上**（淡红色）
2. 点「退出测量」：线仍在
3. 再画 2-3 条：全部显示；同起点画两条 → 两条并存
4. 平移/缩放地图：线跟随地图正确变换
5. 点「导出CSV」：文件含全部 N 条记录（Bearing/Range 数值与桌面版一致）
6. 回放模式下：已保存线仍显示

**② 点选测量**：
1. 点某单位：画布出现**到所有其它单位的灰线 + 中点标签**（名称 / 距离 nmi / 方位 °）
2. 换点另一单位：线立即刷新为新选中单位的测量
3. 点空白处：线消失
4. 北向单位标签方位 ≈ 0°/360°（验证 Y 翻转后方向与屏幕一致）
5. 长按单位：仍弹出编辑对话框（不冲突）；编辑保存后线数据随之更新
6. 点「测量」进入测量模式 / 「回放」：不显示测量线（避免混淆）

**③ 设置按钮**：
1. 回合时长输入 5 分 30 秒 → 点「**设置时长**」：toast「回合时长已设为 5 分 30 秒」，状态行「时长 5:30」更新
2. 点「Do 移动」：位置时间按新时长推进（如 00:05:30 → 00:10:30...）
3. 非法输入（空/字母）：回退 3:00 且 toast 数值正确（现有兜底不回归）

### 7.3 交付门槛
- 以上自动 + 手动全部通过；diff 仅含 §4 清单文件；git status 无存档/构建产物意外改动。
