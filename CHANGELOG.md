# 更新日志 (Changelog)

## [v0.7.6] - 2026-08-25

### 射程弧与可视化选色
- **ArcAngle=0 整圆语义修复**：桌面反编译确认 ArcAngle=0 为整圆（雷达环 FC L/M/S 均为 0 度整圈），修复此前 sweep=0 不绘制问题。
- **可视化选色器**：新增 `ColorPickerDialog`（调色板预设 + HSV 滑杆），替代手动输入 VB 十六进制颜色代码；`ArcColorCodec` 统一双向编解码。
- **弧上文字标注**：对齐桌面 `DrawCircleLabels` / `DrawArcLabels`，整圆 225° 标注 `[名称] [最小距离]-[最大距离]`（如 `FC L 0-15`），多环密集微调寻空防叠字。

### 被动声呐 / 电子支援（ES）方位线
- **桌面语义对齐**：复刻桌面 `CBearing.CalcBearing`，关联目标后实时重算方位；波束宽度内随机散布（目标处于扇区内但不恒定居中）；调小误差角时自动收紧，保证目标绝不越界。
- **无限探测距离动态穿透**：`BeamLength=0`（或留空）时，射线与扇区动态根据视口距离延伸贯穿整个屏幕视野（保证放大几万倍、单位远在屏外几万像素也绝不消失）。
- **波束视觉优化**：波束宽度 >0 时绘制半透明扇区与边界线，消除中间多余黄线。
- **阵营色联动**：方位线与波束按 `ShowAsSide`（目标阵营）分派颜色（蓝=蓝、红=红、未知=黄）。
- **存档键名对齐**：使用桌面官方键名 `BearingArray`（旧键 `PassiveBearingArray` 兼容读取）。

### UI 与中文化
- **标签排版与多行分段**：第 1 行单位身份、第 2 行紧凑航向航速 `190°/28kts`、第 3 行高度/深度/附加信息；全透明背景避免遮挡海图底图。
- **屏幕密度（density）感知字号**：单位与弧度文字基准 16sp，远近缩放清晰可读。
- **编辑界面全面中文化**：被动方位类型下拉选择（声呐 / ES）、目标单位下拉选择；字段参数中文化并补充单位说明。

### 测试
- **全量测试套件 100% 通过**（含新增 `ArcSweepSemanticsTest`、`ArcLabelFormattingTest`、`BearingArrayAndCalcBearingTest`、`CourseSpeedLabelFormatTest` 等回归锁）。

### 回合控制（对齐 PC 版 Do/Undo/Next）
- **Do/Undo/Next 亮灭彻底修复**：ViewModel 新增显式可观察 `turnState`，三处按钮（竖屏底栏 / 横屏右侧竖条 / 回合控制卡）全部直接绑定；Do 后 Undo+Next 立即点亮、Do 灰；Next/Undo 后恢复。
- **状态机主判据改为桌面权威 Phase 字段**（advanceTime→2，confirmNext/undo→0），轨迹推断降为旧存档兜底。
- **R3**：`lastTurnInterval` 快照——Next 写入 Turns 复用 Do 时时长；Undo 跨重启回退优先 Turns 历史 interval。
- **T1**：脏时间不再跳变 now()，回原值 + 错误日志。

### 单位符号（核对桌面素材后重做）
- **NTDS 与 CWS 分离**：NTDS 是独立矢量符号系统（水面=圆点+航向线、潜艇=十字、飞机=三角翼），不再误用 CWS 的 PNG 精灵图（此前两套一模一样的根因）；精灵图仅服务 CWS 三变体。

### 数据与持久化
- **R4 RangeMm 全链路**：毫米海里余额跨存盘（0.4/0.6 海里不再取整漂移）；编辑面板支持一位小数输入、显示真实余额（修复"剩0.6显示为0"的假耗尽）。
- **W1/W2**：`UnitAdapter` — I/R 不可移动单位剔除 Speed/Course/Range/RangeMm/WpDistance；空串 Formation 归一 null。
- **W3**：侧文件 Blue/Red.SpScn 保留触发可见的 Perception 记录（Mediterranean 实测对齐）。
- **J1**：lenient 空 {} 返回可变集合（根治 "Operation is not supported for read-only collection" → Do 失败）。

### 引擎修正
- **R2**：归档阈值改用本回合实走距离（去 1nm 下限），顺序 move→archive→altitude 对齐桌面 CustomTimer。
- 编队移动同步成员航向/航速（E1）等历史项保持。

### UI
- 标签背景改 33% 半透明，不再遮挡单位；可在设置关闭标签背景。
- hitTest 命中半径乘尺寸档；对话框 imePadding 避键盘；横屏竖条 navigationBarsPadding。
- Neutral 白色单位描边全链路补强。

### 测试
- **374 tests / 62 suites 全绿**（含远端 v0.7.0 场景库测试回归 + 本轮新增 R2/R3/R4/T1/SpScn 往返回归）。

版本号规则：开发版从 **0.1** 开始，每次发布递增（`MAJOR.MINOR.PATCH`，开发期 MAJOR=0）。
**每次 push 到 GitHub 前必须更新本文件 + `app/build.gradle.kts` 的 versionName/versionCode。**

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.7.2] - 2026-08-21

### 修复：0.7.1 全面审阅缺陷批次（对照《0.7.1 代码审阅与修改意见书》P1/P2/P3 逐项）

**P1 逻辑缺陷**
- **G40 最终航路点双轨判定矛盾（P1-1）**：`AdvanceTurnUseCase.execute` 此前仍用过宽条件（`futureWaypointArray 空 && pastWaypointArray 非空`）判定 `finalWaypointReached`，导致从未设航线的单位每回合误弹「到达最终航路点」对话框；`hasReachedFinalWaypoint` 同源。现统一消费 `MovementEngine` 的精确标记 `reachedFinalWaypoint`（仅本回合消费最后一个未来航路点的单位触发），新增双用例回归。
- **单位类型反向切换死锁（P1-2/G13）**：此前 `showAltField` 以 `unit.isAircraft()`（altitude 非 null）判定恒 true、且高度输入清空时 `alt.isNotBlank()` 为 false 不写回 → 飞机/潜艇永远无法改回水面单位。新增纯函数 `UnitTypeRegistry.applyDomainDimensions` 按目标大类重置高度/深度维度（AIR 清深度、SUBSURFACE 清高度、其余全清），`UnitEditSheet` 提交逻辑改按 `editDomain` 分支处理，新增 3 用例回归。

**P2 数据完整性**
- **历史轨迹点丢失航向/航速（P2-1）**：`MovementEngine.makeWaypoint` 补齐 `Name/Speed/Course/AssignedAltDepth/递增 Number`（此前只写 x/y，桌面版读取航迹时方向航速恒 0 失真），新增 2 用例回归。

**P3 质量/性能**
- **ArcRenderer 画笔/Path/RectF 复用（P3-1/G68 补齐）**：此前每弧 new Paint/Path/RectF 造成大场景 GC 抖动，改 `by lazy` 字段复用。
- **海图标签忽略独立呼叫号（P3-2）**：`SceneCanvas.drawUnitLabel` 改走 `callsignOrName()`（此前直接用 `u.name`，UnitEditSheet 配置的独立 callsign 不显示）。
- **比例尺文字 locale（P3-3/#15 覆盖不全）**：`ScaleBar.label` 补显式 `Locale.US`，新增跨 locale 回归用例。
- **地图多边形 Path 复用（P3-4）**：`MapRenderer.screenPath` 每多边形 new Path 改字段复用。

### 验证
- 329 JUnit 用例 / 50 测试类全绿（0 失败 0 错误，较 0.7.1 新增 6 用例）
- assembleDebug 成功

## [0.7.1] - 2026-08-18

### 修复：读取新文件时视野不重置、旧地图与交互状态残留等缓存不刷新问题

- **视野相机跟随新文件自适应**：`SceneCanvas.kt` 记录 `canvasSize`，使用 `LaunchedEffect(file, canvasSize)` 监听场景切换与尺寸就绪，自动重置相机视野到新场景单位坐标（`camera.fitBounds`），场景无单位时自动回退到地图边界或默认原点，彻底解决连续打开不同场景时画面空白或停留在上一场景坐标的问题。
- **旧场景地图与比例尺彻底清空**：`GameViewModel.kt` 的 `applyLoaded` 与 `createNewScenario`（无地图时）在载入前强制调用 `mapRenderer.clearMap()`，彻底清空上一场景的解析数据、背景位图、地图边界和比例尺，避免新场景无地图时上一场景底图和边界残留。
- **多场景切换临时交互状态彻底重置**：`applyLoaded` 补充清理 `selectedUnitId`、`editUnit`、`editArcUnit`、`editWaypointsUnit`、`clipboardUnit`、`rangeExhaustedUnit`、`finalWaypointUnit`、`finalWaypointQueue`、`replayTimeline`、`replayPlaying`、`replayIndex`、`measureMode`、`clearMeasures()`、全部子弹窗开关（`showNewUnit`、`showConvoy`、`showFormation`、`showNewScenario`、`showExportOrders`）等，杜绝跨文件状态残留。
- **新增回归测试**：`ScenarioSwitchStateTest.kt` 全面覆盖 `MapRenderer.clearMap()` 重置、场景切换视野重算、地图边界回退与多场景数据隔离。

### [0.6.1] - 2026-08-15

### 修复：0.6.0 审查缺陷批次（对照《0.6.0 代码审查报告》#1-#26 逐项）

**P1 语义对齐**
- **G23 弧排序按桌面语义修复（#1）**：删除绘制期 startAngle 排序（与桌面「列表顺序即绘制顺序」偏离），ArcEditorDialog 新增 ↑/↓ 上移/下移重排（`moveItem` 纯函数可单测）

**P2 缺陷修复**
- **#2 编队设中心**：旧中心降级后回置 `isInFormation=true`，不再成为脱离编队的孤岛；修正被固化的错误断言
- **#3 编队规格跨场景泄漏**：`applyLoaded` 清空 formationSpecs（与 createNewScenario 一致），消除加载新场景后的「幻影编队」
- **#4 护航队落 (0,0) 视野外**：GameViewModel 注入视野中心（camera.centerWorldX/Y）作指挥舰原点；ConvoyEngine 新增 centerX/centerY 参数，新增绝对位置用例
- **#5 G13 类型切换不生效**：切到航空/水下大类时默认初始化高度/深度输入为 "0"，保证 `isAircraft()/isSubmarine()` 判定生效
- **#6 G40 最终航路点误报**：引擎精确标记（仅「本回合消费最后一个未来航路点」的单位）替代过宽条件；弹窗加「全部继续」批量跳过；新增精确标记回归用例
- **#7 G65 残废公式**：删除 MapRenderer.parseMapConfigJson 的恒 0.1852 死赋值（`boundaryWidth/自身` 恒 1）

**P3 清理**
- **#8 G66 死代码**：清除 D9 移除的 turnMotion/SizeLevels/boost 残留，同步清理依赖这些符号的过时回归测试
- **#9/#25 每帧分配**：SceneCanvas 新增 ScenePaintPool（by lazy 惰性初始化），`paletteOf` 每帧只算一次；BearingRenderer/TrackRenderer/MiscAnnotationRenderer/UnitRenderer 画笔与 Path 全部复用（G68 同策略）；回放帧 `u.copy` 为浅拷贝、语义必需，保留并注释
- **#10 PlayerSettingsCodec 双份键序**：toDesktopJson 改由单一 `DISPLAY_KEYS` 表生成，消灭死代码 `DISPLAY_KEY_ORDER`
- **#11 无用参数**：删除 SceneCanvas.onLongPress（G32 已改为长按拖拽 Relocate）
- **#12 错误日志分流**：toast 不再混入 errorLog，仅错误/警告路径 logError
- **#13 场景设置不静默覆盖全局**：`settingsLoadedFromFile` 标记，文件来源设置只更新内存 + 回写场景目录
- **#14 护航队默认布局**：ConvoyDialog 默认列/行/间距置 0 → 默认环绕布局（与旧版行为一致）
- **#15 Locale**：CSV/画布 String.format 统一显式 `Locale.US`（防其他 locale 十进制分隔符差异）
- **#16 误导变量名**：FormationDialog `newUnit` → `newDistanceUnit`（实际存距离单位）
- **#17 remember key**：ManualMoveSheet 补 `unit.idNum` key（防弹层复用旧快照）
- **#18 remember key**：SettingsDialog 编辑态补 `settings` key（随外部更新刷新）
- **#19 队形名颜色**：走玩家自定义 palette（此前 `colorOf(side)` 硬编码默认调色板）
- **#20 自动存档时间源**：saveAuto 改取 `currentPositionTime`——桌面「先推时间再 SaveAuto」，取 TurnTime 会滞后一回合
- **#21 手动移动轨迹时间戳**：DoMove 逐步推进 moveTime，轨迹点时间戳不再全部相同
- **#22 编队准备不污染状态机**：prepare 用瞬态字段 `formationPrepPosition` 替代向 PastWaypointArray 加轨迹点，DO_BEFORE 不误判「回合已确认」
- **#23 单位导入撞号提示**：导入后检测 TrackNumber 冲突并提示
- **#24 G47 mono 变体**：文档化（Mono 等价填充，不生成位图资产）
- **#26 新场景地图缺失提示**：autoLoadMap 未找到同名地图时提示放入场景目录

### 验证
- 309 JUnit 用例 / 50 测试类全绿（0 失败 0 错误）
- assembleDebug 成功

## [0.6.0] - 2026-08-12

### 大修：桌面版功能复刻 + 存档互通（对照反编译资料 4 批次）

**批次1 止血与高价值小件**
- G21 TextTags 9 开关补全 + AdditionalText；G30 Show Side 三态视图过滤（All/Blue/Red）
- G03 护航队对话框补全（航向/航速/列行数/列行间距，ConvoyEngine 网格布局）
- G40 最终航路点三选弹窗；G61/G67 时间字符串比较修复（TimeUtil.equal 语义一致）
- G62 TrackNumber 计数器三路径防撞号核验（新建/复制/护航队）

**批次2 核心编辑器补全**
- G01 新场景创建（场景名+起始时间+地图选择）
- G02 编队编辑器全功能（创建/重命名/删除、类型三选、距离单位、成员增删、设中心、罗盘预览；FormationDialog 50→284 行）
- G04 航路点导入（CopyExact/CopyOffset 两模式）；G13 单位编辑补全（名称/类型/阵营/Class/Number/Range/呼叫号）
- G19 被动方位编辑面板；G29 剪贴板式复制+Paste（防撞号）；G32 Relocate 拖拽移动（航路点实时跟随）
- G49 MercatorPolygon 矢量地图变体键（Countries/Cities/Waters/Land/Borders/Depths）
- tagCallsign 存档兼容：桌面 JSON 固定 9 键无独立呼叫号，不落盘、渲染回退 Name（字节级兼容保障）

**批次3 显示定制与互操作**
- G47 符号集四选（CWS Color Filled/Unfilled/Mono + NTDS）+ G08 符号尺寸档/友军符号/背景开关
- G09 颜色全量接入渲染（PlayerSettings 6 色键去硬编码）+ 颜色列表编辑/Load/Save/Reset
- G55 player_settings 桌面互通（读入应用+保存回写）；G25 Save Player Settings 入口
- G06 导出运动命令单位选择（WindowExportOrders 语义）；G17 比例尺动态数值（1-2-5 序列）
- G15 手动移动控制（DoMove/Pause/UndoMove 速度档）；G27 地图截图导出 PNG
- G20 主动传感器开关/G22 Altitude-Depth/G45 BeamWidth/G46 SpeedLeaders 箭头
- G28 单位级 JSON 导入导出；G65 死代码清理

**批次4 打磨收尾**
- G05 航路点删除全部；G07 回放首末帧跳转；G10 自动存档开关；G11 错误日志窗口；G23 弧排序
- G51 队形名绘制核验；G54 存档触发核对；G57 CSV 命名对齐
- G68 Paint 复用（UnitRenderer/MapRenderer 字段复用，惰性初始化保持 JVM 可测）
- G69 remember key 补齐

### 验证
- 311 JUnit 用例 / 50 测试类全绿（大修前 150）
- assembleDebug 成功（APK 10.6MB）

## [0.5.2] - 2026-08-11

空中单位显示修复（用户以 Red 存档反馈）。

### 修复
- **飞机高度/深度 ×1000 定点修正**：桌面版 Altitude/Depth/AssignedAltDepth/速率均为「米 ×1000」定点（Red 存档实测 `3000000`=3000 米）。此前误按原样米读取导致飞机显示 3000000 米；新增 `altitudeMeters()/depthMeters()/setAltitude()/setDepth()` 换算，标签/编辑面板/航路点编辑器/CSV 全部按米显示、×1000 落盘。引擎高度变化同步按定点（上限 180 米=180000）
- **飞机/潜艇/导弹 CWS 精灵图标**：飞机不再画纯色三角形——按桌面版 CwsSymbols 从精灵图裁剪：固定翼飞机 row2 col16、直升机 row2 col19、潜艇 row2 col12、导弹 row0 col21（图标格位经精灵图像素实测确认）

### 测试
- MediterraneanRedLoadTest 新增高度定点回归（A061 3000000→3000m / A120 0m / 水面无 Altitude）
- EngineTest 高度/深度用例全部改 ×1000 定点语义——**150 测试全绿**

## [0.5.1] - 2026-08-11

真机反馈三项修复（桌面 Red.SpScn 读取报错 / 新位置按钮 / 新单位位置）。

### 修复
- **桌面 Red.SpScn 读取报错（用户上传复现）**：桌面版航路点存在第二种序列化格式——嵌套 12 字段扁平数组 `[[Name,X,Y,Speed,Course,AltitudeDepth,AssignedAltDepth,Ascent,Descent,Number,IsTurnTime,PositionTime],...]`（官方 2.3.9 为对象数组）。`WaypointListAdapter` 新增该格式解析，两种格式均兼容
- **移除右上角「新位置」按钮**：与桌面同名功能（ContainerNewPosition 单位编辑面板内）不符，删除入口与 NewPositionDialog
- **新建单位默认位置 = 当前视野中心**：此前固定 (0,0)，若视野不在原点则新单位不可见/无法编辑；`NewUnitDialog` 新增 `defaultX/defaultY` 参数，打开时取相机中心

### 测试
- 新增 MediterraneanRedLoadTest（用户 Red.SpScn 加载回归：131 单位 / 29 Turns / 嵌套航路点解析）——**149 测试全绿**

## [0.5.0] - 2026-08-11

桌面版对齐大版本（审阅 + 用户 18 项决策 + 原始存档对拍后实施）。

### 新增
- **运动引擎桌面化（D9）**：移除鱼叉纸质规则的转向损失/前冲/45° 分段/加速档，改为桌面版语义「距离=速度×时间，沿当前航向匀速直行」；指令表简化为直接设定航向/航速
- **存档字节兼容修复（P0）**：新建/复制单位维护 `Scenario.LastId/CurrentTrackNumber/CurrentPlayerTrackNumber`（桌面续编不再撞号）
- **存档键集对齐（P1-1 + 原始档裁决）**：不再输出 `FormationType` 与 5 个编队键（未入队时）；保留用户原始存档的 `WpDistance` 字段；空 `MapFileName` 省略；`PositionTimeDeleted` 默认对齐 `2020-01-01 00:00:00`
- **player_settings.json 桌面 schema（D6）**：默认文件改为 `Player_Settings/Display_Options` 15 键结构
- **CSV 相对位置导出（D5）**：导出单位相对参考单位的方位/距离三列（TN 格式 + 日期文件名）；测量线导出独立表头
- **运动命令只导出未来航路点（D7）**
- **删除三选确认（R-P2）**：Remove / Show as Sunk / Cancel；新增「标为沉没」操作
- **单位 X/Y 编辑（R-P2）**：编辑面板可直接改位置（替代 Relocate）
- **未来航路点绘制（R-P2）**：空心圆 + 序号，阵营色
- **编队连线绘制 + 编队成员航向/航速同步（E1）+ 编队取消恢复航路点（E12）**
- **参考点符号（R-P2）**：虚线圆 + 菱形，独立绘制
- **显示开关接线（R4）**：ShowCities/Countries/Waters/Depths/SpeedLeaders/Sonar/Es/DepthKey 全部生效
- **阵营色对齐（R3）**：Neutral=白、All=灰（白灰符号加深色描边）
- **弧渲染修复（R1/R2）**：ArcAngle=0 不画（桌面整圆=360）；MinRange>0 画双半径环带，不再白挖洞；未填充只描外弧
- **光栅 SCALE 除法（R4/D4）**：SimPlotX ← 像素 ÷ Scale（桌面语义）
- **标签格式对齐（R7）**：按 TagXxx 开关拼装桌面 TN 格式
- **Misc 标注旋转（R-P2）**：Label/Box/Oval 支持 Rotation
- **高度/深度单回合 180 上限（E3）**
- **Range 扣减按实际距离（E4）**：去掉「至少扣 1 海里」的过度消耗
- **回放过滤（E7）**：单位创建前/删除后不显示
- **Undo 修复（E5）**：Objects 用快照恢复（不再由 units 重建）；快照保留 ignoreRange
- **到达时间秒级精度（E8）**；**SaveAuto 用回合时间命名（P2-3）**；**运动命令玩家名（R-P3）**；**BOM 防御（P3-5）**；**潜艇类型补 Subsurface（P2-2）**；**ArcEditor 新弧默认 ArcAngle=360（R-P3）**

### 测试
- 新增 GloriousOriginalCompatTest（原始档加载/序列化键集回归 3 用例）
- EngineTest 更新为 D9 桌面语义；新增 D9 回归 4 用例
- 全部 **148 测试通过**

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
