# 更新日志 (Changelog)

版本号规则：开发版从 **0.1** 开始，每次发布递增（`MAJOR.MINOR.PATCH`，开发期 MAJOR=0）。
**每次 push 到 GitHub 前必须更新本文件 + `app/build.gradle.kts` 的 versionName/versionCode。**

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.4.9] - 2026-08-10

旧存档读取兼容修复（真机反馈：读取旧 Red 文件抛 IllegalStateException）。

### 修复
- **旧存档空结构容错**：桌面版旧存档把空数组写为 `{}`（空对象）、空 Map 写为 `[]`（空数组）。此前仅 PastWaypointArray 有兼容，Objects/SensorArray/PerceptionArray/Turns 等字段遇 `{}`、Formations/Overlays 遇 `[]` 会抛 `Expected BEGIN_ARRAY/BEGIN_OBJECT but was ...`。新增 Gson 通用容错 TypeAdapterFactory：Collection 目标遇对象→空列表，Map 目标遇数组→空 Map

### 测试
- 新增 LegacyRedFileCompatTest（4 用例：旧 Red JSON 容错解析 / SpScn 字节链路 / 正常数组不受影响 / 往返兼容）——**148 测试全绿**

## [0.4.8] - 2026-08-10

架构重设计第九轮：参考点/声呐浮标编辑适配 + 全部计划项收尾。

### 新增
- **参考点/浮标编辑适配**：UnitEditSheet 按 Domain 判定——参考点（ReferencePoint）隐藏航向/航速输入，声呐浮标（Sonobuoy）显示深度字段

### 其他
- 架构重设计开发文档 v1 全部 P0-P3 计划项落地完成（感知双视角/弧/航路点/新位置/护航队/新单位/运动命令/自动存档/Setup/玩家设置/WW2/光栅地图/编队/复制分派/轨迹/Misc 标注/被动方位/颜色/UseCase 层）
- 144 测试全绿

## [0.4.7] - 2026-08-10

架构重设计第八轮：场景 I/O UseCase 层（ViewModel 继续瘦身）。

### 新增
- **ScenarioUseCases**：场景加载/保存三文件/自动存档/Setup 抽为独立 UseCase（依赖注入 Repository），GameViewModel 的 loadScenario/saveThreeFilesTo/autoSave/saveSetup 全部委托 UseCase

### 测试
- 144 测试全绿（本轮为重构，无新增用例；行为不变）

## [0.4.6] - 2026-08-10

架构重设计第七轮：Misc 标注对象 + 被动方位 + 颜色配置。

### 新增
- **Misc 标注对象**：Overlays 解析（桌面版 MiscLabel/Box/Oval/Line/Polygon CreateFromJson），画布 Overlay 层绘制（MiscAnnotationParser 纯 Kotlin + MiscAnnotationRenderer）
- **被动方位**：PassiveBearing 模型（桌面版 PassiveBearings：Type/Bearing/Emitter/ES/Label）+ 方位线绘制（BearingRenderer）
- **颜色配置**：PlayerSettings 增 6 个颜色键（背景/网格/蓝方/红方/陆地/海洋），设置对话框预设色块选择，画布背景色可配置

### 测试
- 新增 MiscAnnotationAndBearingTest（4 用例：标注解析/空安全/被动方位 Gson 往返/null 安全）——**144 测试全绿**

## [0.4.5] - 2026-08-10

架构重设计第六轮：编队引擎（准备/撤销）+ 复制分派增强 + 轨迹点样式。

### 新增
- **编队管理**：新增编队对话框（桌面版 WindowFormation），列出现有编队，支持移动准备（DoPrepare）/撤销（DoCancel）；逻辑抽为 FormationEngine 纯 Kotlin
- **复制分派增强**：duplicateUnit 用 UnitTypeRegistry.domainOf 判定 Domain（替代 idNum 前缀硬编码），陆上单位（Installation/LandFormation）不复制传感器/武器，IdNum 按 Domain 前缀分派；编辑面板新增「复制」按钮
- **历史轨迹增强**：轨迹点绘制小圆点（桌面版 TrackHistory 样式）

### 测试
- 新增 FormationEngineTest（5 用例：中心/成员识别、准备记录位置、撤销恢复、移出编队、编队列表）——**140 测试全绿**

## [0.4.4] - 2026-08-10

架构重设计第五轮：WW2 符号 + 光栅地图（.map/.txt）解析。

### 新增
- **WW2 符号系统**：第三套符号风格（桌面版 WW2Symbols），菱形框架 + 类型字母 + 阵营色；顶部「符号」按钮三态循环 NTDS→CWS→WW2
- **光栅地图解析**：.map/.txt 格式（桌面版 MercatorRaster）：MAP=图片文件名 / SCALE=比例尺 / CITY、COUNTRY=名称|X|Y，像素坐标按比例尺换算为世界坐标，自动加载同目录光栅图

### 测试
- 新增 RasterMapParseTest（4 用例：MAP/SCALE 解析、城市/国家标注换算、缺 SCALE 回退、空文本安全）、UnitRendererTest 补三态断言——**135 测试全绿**

## [0.4.3] - 2026-08-10

架构重设计第四轮：玩家设置完整版（显示开关/玩家名持久化）。

### 新增
- **玩家设置**：新增设置对话框（桌面版 WindowCustomizeDisplay），12 个显示开关（网格/比例尺/标签/速度领导线/传感器/武器/航路点/队形/城市/国家/水域/深度）+ 玩家名
- **本地持久化**：SettingsRepository（SharedPreferences 存储 PlayerSettings，桌面版 Player_Settings.json 的本地对应物），设置重启不丢失
- **显示开关接入渲染**：SceneCanvas 网格/比例尺/标签/轨迹/弧绘制均受设置控制，顶部「弧开/弧关」按钮改为「设置」入口

### 测试
- 新增 PlayerSettingsTest（3 用例：默认值/独立开关/玩家名）——**130 测试全绿**

## [0.4.2] - 2026-08-10

架构重设计第三轮：运动命令导入 + 自动存档 + Setup 文件。

### 新增
- **运动命令导入**：新增导入入口（顶部「导入」），按 IdNum 匹配场景单位恢复未来航路点；编解码抽为 MovementOrdersCodec（纯 Kotlin 可单测）
- **自动存档**：Do 回合后自动写 "Referee Turn N_日期_时间.json" 到场景目录（桌面版 SaveAuto，静默不打扰）
- **Setup 文件保存**：顶部「Setup」按钮，与场景同格式标记 Setup（桌面版 SaveSetupFile）

### 测试
- 新增 MovementOrdersCodecTest（3 用例：导出→导入恢复航路点/忽略未知 IdNum/字段往返）——**127 测试全绿**

## [0.4.1] - 2026-08-10

架构重设计第二轮：功能补齐（新单位/新位置/护航队恢复）+ UseCase 层。

### 新增
- **新建单位**：新建单位对话框（类型大类 Domain 下拉 + 子类型菜单，用 UnitTypeRegistry 桌面版全量子类型表），按 Domain 分派 IdNum 前缀（S/A/U/V/I/L/R/B）
- **新位置计算器（恢复）**：参考单位 + 方位角 + 距离 → 新坐标（CalcEngine.newPosition）
- **护航队创建（恢复）**：指挥舰 + 环绕商船（数量/距离可调），逻辑抽为 ConvoyEngine 纯 Kotlin 可单测
- **UseCase 层**：新增 domain/usecase/AdvanceTurnUseCase（Do/Undo/Next 门禁 + Range 耗尽/最终航路点检测），GameViewModel 回合操作改为调用 UseCase（瘦身）

### 测试
- 新增 AdvanceTurnUseCaseTest（5 用例：Do 推进/Undo 恢复/门禁拦截/Range 耗尽/最终航路点）、ConvoyEngineTest（3 用例：1+6 生成/自定义参数/航迹号递增）——**124 测试全绿**

## [0.4.0] - 2026-08-10

架构重设计第一轮：前后端分层落地（domain 层）+ 感知双视角 + 弧/航路点编辑。

### 新增
- **domain 层（后端）**：新增 `domain/registry/UnitTypeRegistry`（单位 Domain 判定 + 桌面版全量子类型表：飞机/水面/潜艇/设施/车辆/陆地编队/参考点/声呐浮标）、`domain/engine/CalcEngine`（方位/距离/新位置/到达时间，纯 Kotlin 可 JVM 单测）
- **传感器/武器弧编辑**：新增弧编辑器对话框（增删改 MinRange/MaxRange/StartAngle/ArcAngle/填充/显示/颜色），选中单位操作条新增「弧」入口
- **弧显示开关**：顶部工具栏「弧开/弧关」切换传感器与武器弧绘制
- **航路点编辑**：新增航路点编辑器对话框（列表/增删/字段编辑/到达时间展示），选中单位操作条新增「航路点」入口
- **感知双视角**：单位受限项编辑从单一蓝方视角扩展为 Blue/Red 双分组，红蓝方脱敏独立

### 修复
- `ScenarioRepository.load()` 重复代码（不可达死代码）
- `GameViewModel` 未使用 `Intent` import
- `RepoLoadFallbackTest` 依赖 `/tmp` 外部文件改为内存构造（可靠可回归）
- CWS 类型映射补 `CG`（导弹巡洋舰同 CC 格），与编辑下拉选项对齐

### 测试
- 新增 CalcEngineTest（9 用例：方位/距离/新位置/到达时间）、UnitTypeRegistryTest（7 用例：Domain 判定/类型菜单）、FogOfWarDualSideTest（3 用例：红蓝脱敏独立）——**116 测试全绿**

## [0.3.8] - 2026-08-09

四项优化：图标随放大变大、选中单位可编辑、CWS 独特类型图标、去掉示例功能。

### 新增
- **CWS 模式单位独特类型图标**：移植桌面版 SimPlot2 彩色符号精灵图（blue/red/neutral/unknown 四色），按单位类型裁剪绘制——战列舰 BB、巡洋舰 CC（含 CL/CA）、驱逐舰 DD、护卫舰 FF、巡逻艇 PC、登陆/辅助舰艇等均有专属图标；未映射类型回退矢量符号
- **选中单位操作条**：点选单位后顶部出现「单位名 + 类型 + 编辑 + 取消选中」操作条，点「编辑」直接打开单位属性面板（航向/航速/高度/深度/标签/可见性等），不再依赖长按

### 修复
- **单位图标随地图放大而放大**：图标尺寸改为随 zoom 等比缩放（默认 16f，clamp [14f, 40f]），与标签缩放同链路；点击命中区域同步放大，放大后单位不再"淹没"难以选中
- **去掉示例功能**：移除顶部「示例」按钮及内置示例场景，界面更简洁

### 测试
- 新增 iconSizePx 随 zoom 缩放断言（默认 16f / 上限 40f / 下限 14f）——**87 测试全绿**

## [0.3.7] - 2026-08-09

字号整体加大（用户多次反馈看不清）。

### 修复
- **全部标签字号加大并统一**：单位名称/测量线/点选辅助线标签统一走 `labelTextSize`（随 zoom 同步缩放），基准字号 16f→**24f**，最小下限 12f→**18f**（任何缩放下都明显大于按钮文字 ≈14sp），上限 40f→48f
  - 测量线中点标签：原固定 20f → 随 zoom 缩放（≥18f）
  - 点选辅助线两行标签：原固定 17f → 随 zoom 缩放（≥18f），行高随字号 1.2x 适配不重叠
  - 拉普拉塔默认场景：12f → **18f**（+50%）
- **比例尺文字加大**：15f → **20f**（固定不随 zoom）

### 测试
- 更新 UnitRendererTest 字号期望（24/18/48），新增 UnitRendererTextSizeTest（默认 zoom=24f、拉普拉塔 zoom≥18f、放大≤48f）——**86 测试全绿**

## [0.3.6] - 2026-08-09

两个视觉反馈问题修复（纯绘制层，无逻辑变更）。

### 修复
- **测量距离/方位标签看不清**：测量线中点标签 16f→**20f**，点选辅助线两行标签 13f→**17f**；统一改为「白字+黑描边」两遍画法（先 STROKE 黑边再 FILL 白字，同坐标无偏移），深色/浅色海图均可读
- **右下角比例尺模糊**：原实现用 STROKE 样式画笔画文字导致空心字+低对比。重写为白色实线（2.5f）+ 两端竖线刻度 + 「50 nmi」实心白字（15f）黑描边，去除 DKGRAY

### 其他
- 83 测试全过（强制重跑；绘制改动，无新增单测）；引擎/模型/存档/手势零改动

## [0.3.5] - 2026-08-08

两个反馈问题修复（测量模式交互重做）。

### 修复
- **单位无法被选中（回归）**：测量模式下手势块只注册了 `detectDragGestures`——轻点（未超 touchSlop）不触发任何回调，测量模式不退出、无任何反馈，且轻点画不出线时永远卡在测量模式。改用 `awaitEachGesture` 手动实现：**测量模式下轻点=选中单位**（hitTest 复用），拖拽=画线；轻点选中单位即自动退出测量模式并显示点选测距辅助线，轻点空白不退出可继续画线；系统取消手势不再记录半条线
- **退出测量模式清除测量线**：v0.3.4 的「退出后仍常驻显示」与预期相反。改为：测量模式内画线松手后**留存**（可连续画多条对照），点「退出测量」或选中单位即**清除全部测量线**；已保存线仅在测量模式内绘制
- 「测量模式」toast 文案更新为「拖动画线，轻点选中单位；退出即清除测量线」

### 其他
- 83 测试全过（本次为手势/UI 行为改动，无新增单测；引擎/模型/存档零改动）

## [0.3.4] - 2026-08-08

三个反馈问题修复（测量线留存 / 点选单位测距 / 设置按钮反馈）。

### 修复
- **测量线拖完松手就消失**：测量线此前只在拖拽过程中绘制（本地临时状态），`measureLog` 仅用于 CSV 导出、从未画回画布。改为 `measureLog` 用 Compose 快照列表（`mutableStateListOf`）+ 画布新增 `savedMeasures` 参数，拖拽松手后已保存测量线**常驻画布**（淡色红），退出测量模式仍显示；临时拖拽线保持高亮；CSV 导出不变
- **新增：点选单位自动测距**：点击任意单位即自动计算并显示该单位到**所有其它单位**的距离（nmi）与方位角（°），画布上以灰色辅助线 + 中点标签呈现（按距离升序）；换点其它单位即时刷新；回放/测量模式下隐藏（避免干扰）；新纯函数 `unitDistances`（可单测，复用 CoordUtil 公式，与桌面版一致）
- **「设置」按钮用途不明**：该按钮实为「回合时长确认」（把分钟/秒输入框的值写入当前回合时长）。文案改为「设置时长」，点击后 toast 反馈「回合时长已设为 X 分 Y 秒」

### 其他
- 83 测试全过（新增 CoordUtilTest 7 用例：距离/方位/定点换算边界；UnitMeasureTest 5 用例：排除自身/排序/空场景）

## [0.3.3] - 2026-08-08

六个反馈问题修复（Y 翻转 / 回合门禁 / Do 即时刷新 / 标签字号）。

### 修复
- **示例初设坐标完全不对 + 航向/航迹奇怪（同根因）**：`CameraMath.worldToScreen/screenToWorld` 缺少 Y 轴翻转（世界 Y 北为正、屏幕 Y 向下）→ 场景垂直镜像（北显示在下方）。渲染层补齐翻转（北在上），`pan` Y 分量符号同步调整；`MapRenderer` txt 格式贴图分支改取北边作左上角、多边形边界框改「左上角+尺寸」画法避免倒置。存档坐标字段零改动，仅渲染层修正
- **Do/Undo/Next 按钮逻辑错误 + 可无限按**：`TurnState` 状态机此前未用于门禁——新增纯函数 `canDo/canUndo/canNext`（DO_BEFORE 只能 Do；DO_AFTER 只能 Undo/Next；DO_NEXT 只能 Do）；`TurnControlBar` 三按钮按状态机 `enabled`（非法按钮灰显）；`GameViewModel.doTurn/undo/next` 加防御（非法状态 toast 提示 + 直接 return，零副作用）。引擎本体（advanceTime/confirmNext/undo）语义未改
- **按 Do 后单位不实时移动（需拖动地图才刷新）**：画布绘制阶段读的是普通字段、不注册 Compose 快照依赖，tick 参数变化触发的重组未带动 draw 失效。改为画布内显式快照机制：`LaunchedEffect(tick) { drawEpoch = tick }` + draw lambda 首行读 `drawEpoch` → 版本号变化必重绘（对一切 revision++ 操作生效：Do/Undo/编辑/复制/护航队/回放）
- **标注单位字体仍太小**：v0.3.2 字号公式 `11f·zoom/0.0015f` 下限 8f 在示例场景（fitBounds 后 zoom≈0.0011）撞下限不可读。基准字号提至 16f、下限 12f、上限 40f，抽为纯函数 `UnitRenderer.labelTextSize/labelScaleK`（可单测），锚点偏移规则不变

### 其他
- 71 测试全过（新增 CameraMath 翻转断言 ×3、TurnStateGateTest 门禁矩阵/闭环/危险路径 ×3、UnitRendererTest 字号 clamp ×3）

## [0.3.2] - 2026-08-08

三个 Bug 修复（阵营显示 / 标签缩放 / 手势冲突）。

### 修复
- **示例剧本“都是蓝的”**：用户场景 JSON 缺 `Side` 字段时 Gson 静默落默认值 `"Blue"`（无提示）→ 全军蓝。新增防御：加载场景后检测“单位非空但全部为 Blue”→ toast 警告“场景单位缺少 Side 字段，已按蓝方显示”；同时修复 `SideParsingTest` 空转（示例场景此前不在 test resources，断言从未真正执行），现真实验证冰海巨兽 2 红 5 蓝、拉普拉塔 1 红 3 蓝，并断言红蓝渲染色值不同
- **放大地图时算子标签不放大**：`drawUnitLabel` 字号/锚点偏移固定 11f/10/-8，现随相机 zoom 等比缩放（`textSize = 11f * zoom/0.0015f` clamp 8..28，偏移乘 k=zoom/0.0015f clamp 0.7..2.5）
- **测量模式画线与拖动地图冲突**：transform 手势 pointerInput key 从 `Unit` 改为 `measureMode`，测量模式下完全不注册缩放/平移（协程随 key 重启，不再读到陈旧值）；点选/画线块 key 同步加 `measureMode`，确保模式切换后手势即时生效

### 其他
- 62 测试全过（SideParsingTest 修复空转 + 新增断言）

## [0.3.1] - 2026-08-08

Phase 2 引擎补全 + Phase 3 渲染交互增强。

### 修复
- **运动命令导出格式对齐桌面版**：反汇编确认 BuildUnitArray 结构 {File, Units:[{IdNum, Waypoints}]}，Waypoints 用标准 WaypointToJson 12 键；此前自创 {Units:[{Name,Side,X,Y,...}]} 格式不兼容
- **回放性能 O(n·m·parse)**：positionAt 每帧对每单位每轨迹点重复 TimeUtil.parse → 预解析轨迹时间戳 + 二分查找最后已知位置，大场景回放流畅

### 新增
- **编队移动三模式**（桌面版 Formations.Movement.DoMove 分派）：RelativeToCompass（罗盘方位）/ RelativeToCourse（相对编队航向，转向跟随）/ Column（纵队排中心后方，间隔递增）；Unit 新增 FormationType 字段（存档互通）
- **符号风格切换**（桌面版玩家设置 NTDS/CWS）：顶部按钮切换；CWS 为填充式符号（水面实心圆、飞机实心三角等，对齐 CwsSymbols color_filled 语义）
- **测量 CSV 导出**（桌面版 Measurement CSV）：TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI/Yards/Meters；测量线累积记录 + 导出按钮

### 其他
- 58 测试全过（新增编队 Course/Column 2 个）

## [0.3.0] - 2026-08-08

Phase 1 架构重构（MVVM + 分层 + 渲染层拆分）。

### 重构
- **GameViewModel 状态集中**：新增 ui/GameViewModel.kt，持有全部 UI 状态（场景/选中/编辑/回放/测量/弹窗）+ 所有业务操作入口（Do/Undo/Next/编辑/复制/护航队/保存/导出/Range 三选/回放控制）；revision 显式版本号替代旧 turnTick hack（收敛到一处、可追踪）
- **MainActivity 瘦身**：581 → 220 行，只保留 SAF 文件选择注册 + Compose 组合 + 事件转发；业务逻辑全部移入 ViewModel
- **渲染层拆分**：新增 MapDataParser（纯 Kotlin，JSON → 点列表数据），MapRenderer 改为纯绘制薄壳（android.graphics）；新增 CameraMath（纯数学视口变换），Camera 只做 Compose snapshot 状态包装
- **跨配置保留**：camera/mapRenderer 移入 ViewModel，旋转屏幕不丢视野与已加载地图

### 新增
- JUnit：MapDataParserTest（5：BoundaryRect ×10/多边形/标注/非法 JSON）+ CameraMathTest（5：坐标往返/平移/锚点缩放/fitBounds/缩放钳制），共 56 测试全过（此前 46）

### 其他
- scripts/setup-toolchain.sh：沙箱环境重建脚本（JDK 17 + Android SDK 装入 workspace/toolchain 持久目录，防网关重启丢失；gradle.properties 自动追加省内存配置）
- **构建环境最省资源方案**：in-process Kotlin 编译（不启 daemon，省 ~600MB）+ 单 worker + Xmx768m，3.4GB 内存沙箱下 2m31s 构建成功（此前频繁 OOM）

## [0.2.2] - 2026-08-08

Phase 0 确定性 bug 修复（全项目审阅后执行）。

### 修复
- **高度/深度单位制错误（A1，致命）**：模型把 Altitude/Depth 当"米×1000 定点"，但桌面版反汇编确认存档**原样存实际米**（JsonToUnit/UnitToJson 对高度字段直接 movsd，无换算；帮助文档 + TextTags 显示格式 `' m'` 佐证）。修正模型/引擎/编辑 UI 全链路，存档互通数值正确
- **高度/深度引擎重写**：改为向航路点 AssignedAltDepth（米）按 Ascent/Descent 速率（米/回合）趋近，速率 0 不调整；修正下潜/上浮速率选择（深度 target>cur 用 descent、target<cur 用 ascent）
- **护航队 formationDistance 单位错误（A2）**：桌面版 MoveCompassFormation 反汇编确认 FormationDistance 直接是**文件单位**（×100000 海里定点），与中心坐标直接相加；此前创建时写码、移动时按海里×10⁵ 解释 → 编队成员被甩到 2000 海里外。修正创建（yardsToFile 转换）与移动（不再乘 NMI_SCALE）
- **编队移动找不到中心（顺带）**：分组 filter 只保留 isInFormation 成员，把中心单位（仅 IsFormationCenter）排除 → 找不到中心、成员不动。修正为中心也纳入分组
- **测量手势与地图平移冲突（C1）**：测量模式拖动画线时单指同时平移地图。修正为测量模式下禁用单指平移（双指缩放保留）
- **Range 耗尽"继续移动"语义错误（C3）**：桌面版 Continue Movement = 无视 Range 继续航行；此前实现成"下一回合仍 0 距离不动"。新增瞬态 ignoreRange 标记，选"继续"后正常移动且不再弹窗

### 新增
- JUnit：高度爬升/下降/不越界/速率0/单位制、编队方位距离/文件单位、Range 扣减/耗尽停船/无视限制继续，共 46 测试全过（此前 35）

## [0.2.1] - 2026-08-08

### 修复
- **地图拖动/缩放仍无效（根因）**：Camera 的 zoom/center 是普通 var，手势修改后 Compose 不感知 → Canvas 不重绘，画面纹丝不动。改为 Compose snapshot state（mutableFloatStateOf/mutableLongStateOf），手势/引擎修改自动触发画布重绘
- **Do 后回合时间一起动**：TurnState.advanceTime 的 DO_NEXT 分支（Next 后再次 Do）把 TurnTime 和 PositionTime 一起推进；内置示例自带轨迹 → 首次 Do 即命中。改为 Do 只推进 PositionTime，TurnTime 由 Next 追上（桌面版语义）
- **回放末帧缺失/位置错误**（预先存在，ReplayTest 首次纳入跑批暴露）：buildTimeline 用最后轨迹点时间替代当前时间 → 丢帧；positionAt 在 t=当前时间时返回最后轨迹点而非当前位置。修复后 35 测试全过

## [0.2.0] - 2026-08-08

功能补齐 + 交互修复。对照桌面版反编译分析（用户 2026-08-08 提供）完成 P1-P3 全部待办。

### 修复
- **地图手势失效**：`detectTransformGestures` 回调把 centroid 丢弃、`zoom != 1f` 浮点比较吞掉 pan（单指拖动恒走缩放分支）→ 改为阈值判断 + pan/zoom 同时处理 + 双指中心锚点
- **视野自适应错误**：fitBounds 硬编码 1000×1000 → 改为 onSizeChanged 按真实画布尺寸，仅新场景首次布局时执行（不覆盖用户手势）
- **回合时间/位置时间不刷新**：`file = f` 引用不变导致 Compose 不重组 → 引入 turnTick 状态驱动重组

### 新增
- **地图绘制层补齐**（桌面版 Z 序）：水域名 / 深度色带(5级) / 国家名 / 城市 / 国界线 / 地图边界框
- **高度/深度引擎**：ChangeAltitude/ChangeDepth 向航路点 AssignedAltDepth 趋近，Ascent/Descent 速率，单回合上限 180 米
- **航路点归档**：到达航路点后移入 PastWaypointArray（轨迹/回放）
- **编队移动**（Compass 模式）：成员相对中心单位按 FormationBearing/Distance 重定位
- **测量工具**：拖拽画线 + 实时方位/距离显示（桌面版 Measurement）
- **新位置计算器**：参考单位 + 方位角 + 距离 → 坐标（桌面版 ContainerNewPosition）
- **单位复制**：深拷贝 + 新 IdNum/TrackNumber + 2 海里偏移（陆上单位不复制传感器/武器）
- **护航队生成**：COMMODORE 居中 + 6 Merchant 环绕（2000 码均匀分布）
- **运动命令导出**：Movement - Player.json（桌面版 WindowExportOrders）
- **Range 耗尽三选弹窗**：继续移动 / 删除单位 / 停止单位（桌面版 HasRangeRemaining）
- **速度领导线**：单位前方与航速成比例的指示线（桌面版 SpeedLeaders）
- **回放倍速**：1x/2x/4x/8x（桌面版 PopupSpeed）

### 验证
- 33 个 JUnit 测试全过
- 非 Compose 层 kotlinc 编译通过；完整 Gradle 构建成功

## [0.1.1] - 2026-08-08

### 修复
- SceneCanvas 缺少 `ArcRenderer` import，导致 Gradle 真机构建失败（首次沙箱构建发现）。

## [0.1.0] - 2026-08-08

开发版首个版本。SimPlot 桌面版（Windows）的 Android 复刻，目标：读取/保存官方桌面版场景存档（JSON），支持回合制推演、海图显示与基本编辑。

### 新增
- **存档兼容（桌面版 2.3.9 格式）**
  - 场景顶层键序与官方存档逐字节一致（File/Scenario/TypeOfGame/Time/Turns/Overlays/Objects/Units/Formations）
  - Waypoint 对象结构（PastWaypointArray/FutureWaypointArray），空轨迹输出 `{}`
  - Unit 建模 SensorArray/WeaponArray（射程弧）、编队字段（IsInFormation/Formation*）
  - TurnInterval 大写键（Minutes/Seconds）
  - 四文件保存：referee + Blue + Red + player_settings.json
- **推演引擎**
  - 移动指令解析：具体航速 / 加速X节（≥45° 减半）/ boost·decel 能力表
  - A 级快慢判定 + 75% 加速档（maxSpeed 瞬态）
  - 极地行动转向表（harpoonv 总结：200/1、100/2）
  - Range 耗尽停船、新单位当回合不移动
  - 完整 undo（深拷贝快照回退）
  - 回合回放（ReplayEngine + ReplayBar，1s/帧）
- **海图渲染**
  - 单位军标（水面/潜艇/飞机/岸上）、航迹线、网格、比例尺
  - 传感器/武器射程弧（VB 颜色、整圆/扇形、填充/描边）
  - 官方 MapMaker JSON 地图配置（BoundaryRect 坐标 ×10 转换、陆地/覆盖多边形、文字标注、背景图自动加载）
- **战争迷雾**
  - 阵营视角隔离（Blue/Red 各自可见集合）
  - 可见敌方单位按感知受限项脱敏（名称/航向航速/级别/类型/阵营/高度/深度）
- **其他**
  - 场景文件选择（系统文件选择器）、内置示例场景（冰海巨兽、拉普拉塔河口海战）
  - 单位编辑面板（含受限项开关）

### 验证
- 33 个 JUnit 测试（引擎 24 + 编解码 5 + 存档往返 6，含官方 Iron Bottom Sound Referee 存档回归）
- 官方地图配置 Iron Bottom Sound JJWS1.json 解析验证通过
- 待真机 Gradle 构建验证（沙箱无 Android SDK）
