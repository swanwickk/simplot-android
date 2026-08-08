# 契约：三个 Bug 修复（阵营显示 / 标签缩放 / 手势冲突）

> 来源：dev-pipeline Designer 分析（subagent 会话历史提取）+ 主代理代码审阅
> 项目：simplot-android（v0.3.1 → v0.3.2）
> 日期：2026-08-08

## 1. 目标（可验证验收标准）

### Bug 1：阵营红蓝不分，示例剧本都是蓝的
- **根因分析（已实锤）**：
  - 代码链路全对：内置 `assets/scenarios/冰海巨兽.json` Side 正确（Red 2 + Blue 5）、`拉普拉塔河口海战.json` 正确（Red 1 + Blue 3）；`UnitRenderer.colorOf` 映射正确；`SceneCanvas` 传 `u.side` 正确；git 历史 v0.1.x 起就正确
  - **最可能真实原因**：用户加载的场景 JSON **缺 Side 字段** → Gson 落 `Unit.side` 默认值 `"Blue"`（静默，无提示）→ 全军蓝
  - **附带发现（测试缺陷）**：`SideParsingTest.full scenario side distribution` 是**空转测试**——冰海巨兽.json 不在 test resources，`getResourceAsStream` 返回 null → `?: return` 静默通过，未真正断言
- **验收**：
  1. 修复测试：把两个示例场景复制到 `app/src/test/resources/scenarios/`，`SideParsingTest` 真实验证 Red/Blue 计数
  2. 新增防御：`GameViewModel.loadScenario/loadSample` 加载后检测"单位非空但全部 side==默认值 Blue"→ toast 警告"场景单位缺少 Side 字段，已按蓝方显示"
  3. 新增测试：`colorOf("Red") != colorOf("Blue")`，且通过解析→渲染色值管道验证 2 红 5 蓝

### Bug 2：放大地图时算子信息（标签文字）不跟着放大
- **根因（已定位）**：`SceneCanvas.drawUnitLabel` 的 `paint.textSize = 11f` 固定像素，不随 `camera.zoom` 变化；标签锚点偏移 `sx+10, sy-8` 也不缩放
- **修复方案**：标签字号与偏移按 zoom 等比缩放，带上下限：
  - `textSize = (11f * camera.zoom / BASE_ZOOM).coerceIn(8f, 28f)`，BASE_ZOOM 取场景 fitBounds 的初始 zoom（或直接用 `camera.zoom` 比例：`11f * zoom / 0.0015f`）
  - 锚点偏移 `sx + 10f * k, sy - 8f * k`（k = 缩放比例，coerce 后）
  - 需要把 zoom 传入 drawUnitLabel；注意 SceneCanvas 里 zoom 从 `camera.zoom` 读（snapshot state，Canvas 已重组依赖）
- **验收**：zoom 放大 10 倍时标签文字 ≥ 原来的 10 倍视觉大小（clamp 到 28f 上限）；手动步骤：加载场景 → 双指放大 → 标签可读

### Bug 3：测距按住画线和拖动地图逻辑重叠
- **根因（已定位）**：`SceneCanvas` 两个并行 `pointerInput` 块：`pointerInput(Unit)` 里 `detectTransformGestures`（缩放+pan），`pointerInput(file)` 里 measureMode 时 `detectDragGestures`（画线）。并行手势竞争——transform 手势的 `awaitFirstDown(requireUnconsumed=false)` 不检查消费状态，touchSlop 后仍消费事件（虽然 C1 修了"测量模式不应用 pan"，但 transform 手势仍参与事件竞争，且 onGesture 里 zoom 分支仍可触发）
- **修复方案（手势路由互斥）**：
  - 在 `pointerInput(Unit)` 的 transform 手势开头：`if (measureMode) return@pointerInput`（测量模式完全不注册 transform 手势）——但注意 key 是 Unit，measureMode 变化不会重启协程
  - 正确做法：transform 块 key 改为 `Unit` 但内部判断 `if (measureMode) return@pointerInput` 无效（协程已启动）；应把 measureMode 作为 pointerInput key 之一：`.pointerInput(measureMode) { if (measureMode) return@pointerInput ... }`，measureMode 变化时协程取消重启
  - 或：测量模式下 transform 手势 `awaitEachGesture` 内 `awaitFirstDown` 后检查 `measureMode` 直接 consume 全部不处理
  - **推荐**：`pointerInput(measureMode, Unit)` 作为 key，measureMode=true 时 transform 块直接 return（不注册）；measureMode=false 时正常。这样单指=画线，双指缩放也在测量模式禁用（简单可靠）
- **验收**：测量模式下单指拖动画线不移动地图；退出测量后单指拖动正常平移

## 2. 非目标
- 不重构手势架构（不做自定义 GestureDetector 路由）
- 不改 BH2VOQ 存档格式兼容（`PastWaypointArray1` 带1后缀格式不在本任务范围，另开任务）
- 不改 FogOfWar/存档保存逻辑
- 不处理"用户装旧 APK"（发新版即解决）

## 3. 约束
- 构建命令（3.4G 内存沙箱，勿改 gradle.properties）：
  `LANG=C.utf8 LC_ALL=C.utf8 JAVA_HOME=/home/node/.openclaw/workspace/toolchain/jdk-17.0.2 ANDROID_HOME=/home/node/.openclaw/workspace/toolchain/android-sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1`
- 存档字节兼容（Gson 键序、@SerializedName 不动）
- 改动走 CHANGELOG + version bump（0.3.2）+ release（用户规则）
- 参考权威：BH2VOQ-ATG/simplot/scn_tool.py（Side 处理、坐标换算）

## 4. 技术方案与触碰文件
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt` | Bug2：drawUnitLabel 加 zoom 参数，textSize/偏移按 zoom 缩放 clamp；Bug3：transform pointerInput key 加 measureMode，测量模式不注册 |
| `app/src/main/java/com/simplot/android/ui/GameViewModel.kt` | Bug1：loadScenario/loadSample 后检测全 Blue → toast 警告 |
| `app/src/test/java/com/simplot/android/SideParsingTest.kt` | 修空转：复制场景到 test resources，真实验证；加 colorOf 断言 |
| `app/src/test/resources/scenarios/` | 复制 冰海巨兽.json + 拉普拉塔河口海战.json |
| `CHANGELOG.md` / `app/build.gradle.kts` | 0.3.2 条目 + bump |

## 5. 假设
- Bug1 用户场景缺 Side 字段（无法复现用户文件，按最可能根因防御）
- Bug2 BASE_ZOOM 用 `camera.zoom` 当前值做比例即可（不引入场景基准 zoom 状态）

## 6. 验证计划
1. `./gradlew testDebugUnitTest`（新增/修复测试全过，总数 ≥ 59）
2. `./gradlew assembleDebug` 构建成功
3. 手动步骤（发给用户）：
   - 加载示例 → 红蓝阵营可辨（沙恩霍斯特/格奈森瑙红色，敦刻尔克等蓝色）
   - 双指放大 → 标签文字变大可读
   - 测量模式拖画线 → 地图不动；退出后拖动正常
