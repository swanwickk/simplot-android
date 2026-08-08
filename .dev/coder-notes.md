# Coder 变更说明（v0.3.2 三个 Bug 修复）

> 编码者：dev-pipeline Coder（flash）| 日期：2026-08-08
> 契约：`.dev/contract.md`；构建命令按契约原文执行

## 复现（先复现失败）

- Bug 1 空转测试：`SideParsingTest.full scenario side distribution` 原代码 `getResourceAsStream(...) ?: return`
  静默通过——即使断言写错（如期望 0 红 0 蓝）也不会失败，无法守护 Side 解析。场景现已复制到
  `app/src/test/resources/scenarios/`（冰海巨兽.json 2 红 5 蓝、拉普拉塔河口海战.json 1 红 3 蓝，已验证）。
- Bug 2/3 为代码走查定位（textSize 固定 11f、偏移固定 10/-8；transform 手势 key=Unit 读到陈旧 measureMode）。

## 改动（5 文件 + 2 新资源）

### Bug 1：阵营红蓝不分（缺 Side → Gson 静默落默认 "Blue"）
- `GameViewModel.applyLoaded`（loadScenario/loadSample 共用入口）加载后检测：
  **单位非空且全部 side=="Blue"** → `toast("场景单位缺少 Side 字段，已按蓝方显示")`（排除空单位列表）。
- `SideParsingTest`（重写）：
  - `resourceText()` 用 `assertNotNull` 替代 `?: return`——资源缺失即失败，杜绝空转；
  - `full scenario side distribution`：真实验证 2 红 5 蓝，且逐单位走**解析→渲染色值管道**
    （`UnitRenderer.colorOf(it.side)` 与 Red/Blue 基准色逐一比对）；
  - 新增 `la plata scenario side distribution`（1 红 3 蓝）、`red and blue render colors differ`
    （`colorOf("Red") != colorOf("Blue")`，契约要求）。
- `UnitRenderer.sideColors`：`Color.rgb(r,g,b)` 内联为逐字节等价的纯 Kotlin Int 常量
  （`0xFF005AC8.toInt()` 等，渲染色值零变化）——否则 JVM 单测调用 `colorOf` 会因
  android.jar stub 抛异常，colorOf 断言无法落地。**未动** `draw()`/符号逻辑/序列化。

### Bug 2：标签不随缩放放大
- `SceneCanvas.drawUnitLabel` 增加 `zoom` 参数（两个调用处传 `camera.zoom`，含回放分支）；
  - `BASE_ZOOM = 0.0015f`（= Camera 初始 zoom，作为 1 倍基准）；
  - `textSize = (11f * (zoom / BASE_ZOOM)).coerceIn(8f, 28f)`（契约公式）；
  - 锚点偏移 `sx + 10f * k, sy - 8f * k`，`k = (zoom / BASE_ZOOM).coerceIn(0.7f, 2.5f)`（契约值，
    默认 zoom 下 k=1 与原行为一致）。

### Bug 3：测量模式手势冲突
- transform 手势 `pointerInput(Unit)` → `.pointerInput(measureMode)`，块开头
  `if (measureMode) return@pointerInput`——measureMode 作 key 使协程随切换取消重启，
  测量模式下 transform（缩放+pan）**完全不注册**；非测量模式行为不变（camera 快照状态
  变更 → 自动重组重绘，重绘逻辑保留）。
- 点选/画线块 key `pointerInput(file)` → `.pointerInput(file, measureMode)`：配套修复——
  否则 key=file 时协程不重启，进入测量模式后仍跑旧分支（画线/点选不切换），测量功能本身失效。
- 块内 `measuring` 双保险判断保留（防御未来 key 回退）。

### 版本
- `CHANGELOG.md`：新增 [0.3.2] 条目（三个修复 + 测试数）。
- `app/build.gradle.kts`：versionCode 8→9、versionName 0.3.1→0.3.2。

### 未触碰（契约约束）
- 存档键序 / `@SerializedName` / FogOfWar / 保存逻辑 / gradle.properties / 手势架构重构。

## 验证结果

命令（契约原文）：
`LANG=C.utf8 LC_ALL=C.utf8 JAVA_HOME=.../jdk-17.0.2 ANDROID_HOME=.../android-sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1`

- **BUILD SUCCESSFUL in 25s**（42 tasks；首次运行因与 main agent 并发构建争 2GB cgroup 内存
  daemon 被 OOM 杀，等其结束后独占重跑即过）。
- **62 tests, 0 failures, 0 errors, 0 skipped**（v0.3.1 为 58；SideParsingTest 4 项全 PASS：
  `full scenario side distribution` / `side parsed from json` / `red and blue render colors differ` /
  `la plata scenario side distribution`，契约要求 ≥59 达成）。
- APK 产出 `app/build/outputs/apk/debug`：versionName **0.3.2**、versionCode **9**。

## 待人工验证（发给用户的手动步骤）
1. 加载示例场景 → 红蓝阵营可辨（沙恩霍斯特/格奈森瑙红，敦刻尔克等蓝）；
2. 双指放大 → 标签文字随缩放变大可读（clamp 8..28）；
3. 测量模式单指拖动画线 → 地图不动；退出测量后单指拖动正常平移。
