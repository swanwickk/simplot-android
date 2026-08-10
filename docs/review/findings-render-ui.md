# SimPlot2 绘制/地图/UI 交互审核报告（simplot-android vs Windows 反编译）

- 审核范围：render/、ui/GameViewModel.kt、ui/components/、MainActivity.kt、domain/engine/MiscAnnotationParser.kt、domain/model/MiscAnnotation.kt
- 依据：`decompile-win/simplot-decompile-win/` 四份伪代码文档 + asm/ + strings_ascii.txt 交叉验证
- 结论摘要：坐标/缩放/翻转数学与桌面版一致（已确认）；弧绘制角度语义基本一致但有 2 处偏差；约 6 个显示开关是死开关；CSV 导出语义与桌面版不符；光栅 SCALE 换算方向与桌面版矛盾；若干桌面功能缺失（未来航路点显示、参考点符号、编队连线、Relocate、导入航路点、删除三选弹窗等）。

---

## 1. 问题清单

### 【P1】弧渲染：ArcAngle=0 被画成整圆，桌面版画的是退化弧（不可见）
- **安卓证据**：`render/ArcRenderer.kt:80-91`
  ```kotlin
  if (sweep == 0f) {
      // 整圆（ArcAngle=0 表示圆，桌面版约定）
      val rect = ...radiusMax...
      canvas.drawOval(rect, paint)
  ```
  且 `data/model/Unit.kt` 的 `Sensor`/`Weapon` 默认 `arcAngle = 0.0`、`isFilled = false`、`isVisible = false`——新加一条弧默认即以整圆显示。
- **桌面版依据**：`伪代码_绘制层.md §37 DrawSensorArc`：用 `MinRange/MaxRange/StartAngle/ArcAngle` 逐步构建弧线路径（`rad = StartAngle + i*step`），无任何 "0=整圆" 特判；`asm/ui_0001407042d0_DrawSensors.asm` 全文无 360 常量。弧 JSON 示例（`伪代码_剩余模块.md §12`）整圆写法是 `"ArcAngle": 360`。即桌面版整圆 = ArcAngle 360，ArcAngle=0 是 0 度弧（不可见）。
- **修复建议**：删掉 `sweep == 0f` 整圆分支；`arcAngle == 0` 时不绘制（或按桌面语义只画 0 度退化弧）；`Sensor/Weapon` 默认 `arcAngle` 改为 360 或与 CArc.Constructor 一致（asm 确认构造默认 MaxRange=50.0 ✓，ArcAngle 默认 0 —— 0 即不画）。编辑器新建弧默认值需同步调整。

### 【P1】弧渲染：非整圆路径忽略 MinRange，实心扇形从圆心一直填到 MaxRange；桌面版为 Min/Max 双半径弧带
- **安卓证据**：`render/ArcRenderer.kt:93-95`
  ```kotlin
  val rect = RectF(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
  canvas.drawArc(rect, startDeg, sweep, true, paint)
  ```
  仅用 radiusMax；`radiusMin` 只在整圆分支用于白色挖洞。
- **桌面版依据**：`伪代码_绘制层.md §37`：DrawSensorArc 显式接收 `MinRange / MaxRange` 两个参数并逐点构建路径（`x = cx + range*Sin(rad)`），MinRange 参与弧带几何。MinRange>0 的弧（如声呐环带）在桌面版显示为 min~max 之间的环带，安卓显示为全实心扇形（min 以内区域也被覆盖）。
- **附带问题**：整圆分支的挖洞用**不透明白色** `drawOval(rectMin, Paint{color=WHITE; FILL})`（ArcRenderer.kt:86-88），背景为地图/贴图时会出现白色圆斑。应画成环带（even-odd 填充或 inner/outer 两条弧），且未填充样式（STROKE）下 `useCenter=true` 会画出到圆心的两根半径线，桌面版未填充弧只描弧线本身。
- **修复建议**：用 Path 构建 min/max 双半径弧带；STROKE 时 useCenter=false；整圆分支同样按环带绘制。

### 【P1】阵营色不符桌面 GetUnitColor：Neutral 应为白、All 应为灰，安卓 Neutral=灰且无 All
- **安卓证据**：`render/UnitRenderer.kt:30-39`
  ```kotlin
  "Blue" to 0xFF005AC8, "Red" to 0xFFC81E1E,
  "Neutral" to 0xFF787878.toInt(),   // 灰
  "Unknown" to 0xFF5A5A5A.toInt()
  fun colorOf(side) = sideColors[side] ?: 0xFF5A5A5A  // "All" 落到 Unknown 深灰
  ```
- **桌面版依据**：`伪代码_剩余模块.md §16 GetUnitColor`：**"All"=灰, "Neutral"=白, "Red"=红, "Blue"=蓝, "Unknown"=未知色**。且 `Unit.kt` 中 `MiscAnnotation` 默认 `Side="All"`，`player_settings` 场景里 "All" 阵营单位（及 Misc 标注）在安卓上会显示成 Unknown 深灰而非桌面版的灰/白。
- **修复建议**：`"Neutral" -> 白（0xFFFFFFFF）`，新增 `"All" -> 灰`；注意白底背景下的可读性需同步考虑描边（桌面版同样存在该问题，属原版语义）。

### 【P1】显示开关死开关：ShowCities/ShowCountries/ShowWaters/ShowDepths/ShowSpeedLeaders 在渲染层完全未生效
- **安卓证据**：
  - `render/MapRenderer.kt:144`（水域名）、`:159`（深度色带）、`:207`（国家名）、`:218`（城市）全部无条件绘制；
  - `render/UnitRenderer.kt:153` 速度领导线 `if (u.speedKnots() > 0)` 无条件绘制；
  - `ui/components/SceneCanvas.kt:174-288` 只消费 showGrid/showWaypoints/showLabels/showSensors/showWeapons/showScaleBar。
- **桌面版依据**：`伪代码_剩余模块.md §16` 15 项显示选项 JSON 键（ShowCities/ShowCountries/ShowWaters/ShowDepths/ShowSpeedLeaders 均在列）。
- **修复建议**：把 settings 传入 MapRenderer（或 SceneCanvas 层 gate），城市/国家/水域/深度/速度领导线按对应开关过滤；`SettingsDialog` 已提供这些开关，用户勾了无效属明显缺陷。

### 【P1】CSV 导出语义不符：桌面版导出"单位相对参考单位位置"，安卓导出的是"测量线"
- **安卓证据**：`ui/GameViewModel.kt:552-572` `exportMeasureCsv`：遍历 `measureLog`（测量线），行数据伪造 `TN = "M${i+1}"`，Course/Speed/Alt/Depth 恒为 0；文件名固定 `Measurements.csv`（:570）。
- **桌面版依据**：`伪代码_剩余模块.md §15`：`ExportData.RelativeUnitPositions.Export` 表头 `TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI,Range Yards,Range Meters`，**每行是一个单位相对参考单位**的方位/距离（`####`/`#######.##` 数字格式），文件名 `<前缀>_<日期>_<时间>.csv`（JAN..DEC 月份缩写）。表头相同但数据来源完全不同——安卓的"导出CSV"按钮并不是桌面版同名功能。
- **修复建议**：二选一：a) 实现真正的相对位置导出（选参考单位→遍历单位→CalcBearing/CalcRange→三列距离），文件名带日期；b) 若保留测量线导出，改名"导出测量"并更新表头，避免与桌面版功能混淆。

### 【P1】光栅地图 SCALE 换算方向与桌面版矛盾（乘 vs 除）
- **安卓证据**：`render/MapDataParser.kt:122-126`
  ```kotlin
  val pxToWorld = scale * 1000.0 * 100000.0 / 1852.0   // px × scale × 53995.7
  cityLabels.add(Triple(name, (px * pxToWorld).toLong(), ...))   // :134/:143
  ```
  且测试 `RasterMapParseTest.kt` 断言 `100 × 3.071 × 1000 × 100000/1852`。
- **桌面版依据**：`伪代码_绘制层.md §35 MercatorRaster`：`SCALE = 比例尺（Double）`，LoadMapData 第 4 步 **"按比例换算坐标（SimPlotX/Y ← 像素 / Scale）"**——桌面是**除以** Scale。同一份 `.map` 文件两边的换算结果完全不同（如 px=100, scale=3.071：桌面 32.6 单位 vs 安卓 1658 海里）。
- **修复建议**：需要一份真实 `.map` 样例裁决：若桌面 SCALE 语义是"px/单位"，则安卓应 `px / scale` 再转文件单位；若真实文件的 SCALE 是 km/px（安卓注释说法），则桌面伪代码与安卓必有一处与真实文件不符，需以实际文件为准并回写文档。当前实现与权威伪代码直接冲突，至少要在代码中注明。

### 【P1】单位文本标签远未对齐桌面：只画"名称 + 速度节/航向°"，桌面 9 项标签开关 + "TN xxx x N" 格式 + 感知变体全缺
- **安卓证据**：`ui/components/SceneCanvas.kt:337-355`
  ```kotlin
  if (!tag.tagName && !tag.tagCourseSpeed) return
  if (tag.tagName && u.name.isNotEmpty()) parts.add(u.name)
  parts.add("${u.speedKnots().toInt()}节/${u.courseDeg().toInt()}°")
  ```
  仅消费 `tagName/tagCourseSpeed`；`data/model/Unit.kt` `TextTags` 的 `TagTrackNum/TagClass/TagUnitType/TagAltitude/TagDepth/TagCallsign/AdditionalText` 全部不画；`UnitEditSheet.kt` 也只暴露这两个开关。
- **桌面版依据**：`伪代码_剩余模块.md §13` 标签 JSON 9 键 + 格式串（二进制确认）：潜艇 `"TN 123 x 4  Depth UNK  Course UNK  Speed UNK"`、水面 `"TN 123 x 4  Course UNK  Speed UNK"`、飞机含 Altitude、感知版加 `"x Contacts"`、设施/参考点只有 `"TN 123"`、浮标 `"TN 123  50 m"`。安卓的"速度节/航向°"格式（且顺序速度在前）与桌面"Course 在前"也不一致。
- **修复建议**：按 TagXxx 开关拼装桌面格式（TN 轨迹号、类、类型、高度/深度、呼叫号、附加文本）；TagTrackNum 用 `u.trackNumber`；感知变体待 FogOfWar 侧接入后补充。

### 【P2】未来航路点完全不绘制（桌面 ShowWaypoints 画未来航路点标记，颜色按阵营）
- **安卓证据**：`ui/components/SceneCanvas.kt:179` `if (settings.showWaypoints)` 只包住 `TrackRenderer.draw`（历史轨迹）；`render/TrackRenderer.kt` 全文只遍历 `pastWaypointArray`，无 `futureWaypointArray`。
- **桌面版依据**：`伪代码_剩余模块.md §21`：`GetWaypointColor(unit)：Neutral=白/Red=红/Blue=蓝`；`ShowWaypoints` 即未来航路点显示开关。玩家看不到计划航线，编辑航路点后无视觉反馈。
- **修复建议**：TrackRenderer（或独立 WaypointRenderer）绘制 `futureWaypointArray` 空心圆/菱形 + 序号，按 `GetWaypointColor` 着色；关 `ShowWaypoints` 时同时隐藏。

### 【P2】地图 JSON 只支持 NewMap 变体（Xxx Polygons/Labels），不支持 MercatorPolygon 变体键（Countries/Cities/Waters/Land/Borders/Depths）
- **安卓证据**：`render/MapDataParser.kt:74-91` 只读 `"Land Polygons"/"Misc Polygons"/"Misc Labels"/"Water Labels"/"City Labels"/"Country Labels"/"Depth Polygons"/"Depth Labels"/"Border Polys"`。
- **桌面版依据**：`伪代码_剩余模块.md §17` 同时存在两套：`MercatorPolygon.LoadMapData`（`Countries:[{Name,SimPlotX,SimPlotY}]、Cities、Waters:[{...,IsMajor}]、Land:[{Name,Path}]、Borders、Depths:[{Id,Depth4,Path}]`）与 `NewMap.LoadMapData`（`BackgroundFileName、BoundaryRect、Country Labels、City Labels、Water Labels、Land Polygons`）。遇到 MercatorPolygon 格式的官方地图会静默解析为空（parse 失败即 return，无提示）。
- **修复建议**：补 MercatorPolygon 变体解析（SimPlotX/SimPlotY 命名、Waters 多边形、Depths 的 Depth4/Id 分级、Land/Borders 数组）；无 BoundaryRect 时用 Scale/Width/Height 推算范围。

### 【P2】City/Country 标签 "Position"（"Above Right"）被忽略，锚点全部相同
- **安卓证据**：`render/MapDataParser.kt:163-175` `parseLabelArray` 只取 Name/X/Y，丢弃 Position。
- **桌面版依据**：`伪代码_剩余模块.md §17` 城市元素含 `"Position": "Above Right"`；`strings_ascii.txt:32768` 确认 `Above Right` 存在。桌面按 Position 决定文字相对锚点的方位（上/下/左/右），安卓全部按左下偏移绘制（MapRenderer.kt:224 `sx+3, sy+3`），密集城市标签会重叠。
- **修复建议**：解析 Position 并让 MapRenderer 按方位偏移。

### 【P2】设置项缺口：ShowDepthKey/ShowEs/ShowSonar 无开关；被动方位线无视 ShowSonar 无条件绘制；颜色设置不参与渲染
- **安卓证据**：`domain/model/PlayerSettings.kt` 有 `showDepthKey/showSonar`（`showEs` 字段缺失，只有 14 键）；`SettingsDialog.kt` 只列 12 项；`ui/components/SceneCanvas.kt:190` `BearingRenderer.draw` 无条件调用；`render/BearingRenderer.kt` 无开关参数。颜色：`render/UnitRenderer.kt:30` 硬编码 sideColors（不读 settings.blueForColor/redForColor），`render/MapRenderer.kt:63` 网格色硬编码 `Color.argb(60,120,140,160)`（不读 settings.gridColor）。
- **桌面版依据**：`伪代码_剩余模块.md §16` 15 键含 ShowEs/ShowSonar/ShowDepthKey；Colors.SaveColors 11 色键（BackgroundColor/BlueForColor/BlueWaypointColor/CityColor/CountryColor/CursorColor/GridColor/MapCoastColor/MapLandColor/MapBorderColor/MapOceanColor）。安卓只落地了 Background/Grid/Blue/Red/Land/Ocean 6 个且未接入绘制。
- **修复建议**：补 showEs 字段与开关；BearingRenderer 按 showSonar/showEs gate；UnitRenderer/MapRenderer 改读 settings 颜色（保留默认值回退）。

### 【P2】单位删除无确认弹窗，缺"Show as Sunk"三选（桌面 DeleteUnit：Remove/Show as Sunk/Cancel）
- **安卓证据**：`ui/components/UnitEditSheet.kt:212` 删除按钮直接 `onDelete(unit); onDismiss()`（无确认）；`ui/GameViewModel.kt:374-381` 直接 `removeAll`。
- **桌面版依据**：`伪代码_剩余模块.md §14`：`dlg.Message="Confirm deletion for unit TN 123 x 4 (Name)"`，按钮 Remove / **Show as Sunk** / Cancel；`伪代码_绘制层.md §34` 右键菜单 Delete Unit 同弹窗。安卓没有确认环节，误触即删且无法恢复；"标为沉没"只有编辑面板里的复选框，非删除场景等价物。
- **修复建议**：删除前弹三选对话框（Remove/Show as Sunk/Cancel）。

### 【P2】无 Relocate（拖拽移动）且单位编辑面板没有 X/Y 字段（桌面各单位窗口有 X/Y 编辑 + Relocate Unit 菜单）
- **安卓证据**：`ui/components/UnitEditSheet.kt:92-135` 只有航向/航速/高度/深度/标签/可见性/沉没，无 X/Y；`SceneCanvas.kt` 无拖拽移动模式（pointerInput 只有 transform 平移/缩放与点选/测量）。
- **桌面版依据**：`伪代码_UI交互层.md §25`：右键菜单 **Relocate Unit** 进入拖拽移动（MouseDrag → RecalcWaypoints 实时更新）；`伪代码_绘制层.md §34` 菜单条目。结果：安卓上"新建单位/护航队"只能生成在 (0,0)（NewUnitDialog 默认 X/Y=0、ConvoyEngine commodore x=y=0），且**没有任何途径把单位挪到目标位置**（航路点编辑器只能改航路点 X/Y，改不了单位本身）。
- **修复建议**：编辑面板补 X/Y 输入；或长按拖拽进入 relocate 模式（对齐桌面 RecalcWaypoints）。

### 【P2】缺桌面功能：导入航路点（CopyExact/CopyOffset）、参考点特殊符号、编队连线、Misc 旋转
- **安卓证据**：
  - 无任何"导入航路点"入口（MainActivity/GameViewModel 均无）；
  - `render/UnitRenderer.kt` 无参考点分支（`unitType=="Reference Point"` 会落入水面圆分支，:236-247）；
  - `ui/components/SceneCanvas.kt` 无编队连线绘制（`showFormations` 开关无效果）；
  - `render/MiscAnnotationRenderer.kt:36-44` 画 Label/Box/Oval 时忽略 `rotation`（MiscAnnotation.kt 已解析 Rotation）。
- **桌面版依据**：`伪代码_UI交互层.md §32` WindowImportWaypoints（CopyExactWaypoints/CopyOffsetWaypoints）；`伪代码_绘制层.md §38` "参考点（CReferencePoint）单独处理（虚线圆/菱形标记）"；`伪代码_剩余模块.md §16` ShowFormations 开关存在；`伪代码_剩余模块.md §21` Misc 各对象含 Rotation。
- **修复建议**：按优先级补参考点符号（最常见）、Misc 旋转（canvas.rotate）；编队连线与导入航路点列入排期。

### 【P2】player_settings.json 写入的键名是自造的，与桌面 Display_Options 键不一致
- **安卓证据**：`data/repo/ScenarioRepository.kt:107`
  ```kotlin
  const val DEFAULT_PLAYER_SETTINGS = """{"ShowTracks":true,"ShowTrackTimes":false,..."ShowTextTags":true,"ShowSensors":false,...,"ShowScale":true,"ShowArcs":false,"ShowBearings":false}"""
  ```
- **桌面版依据**：`伪代码_剩余模块.md §16` 桌面键为 `ShowCities/ShowCountries/ShowWaters/ShowWaypoints/ShowDepths/ShowDepthKey/ShowEs/ShowGrid/ShowScaleBar/ShowWeapons/ShowSensors/ShowSonar/ShowLabels/ShowSpeedLeaders/ShowFormations`。安卓落盘的 `ShowTracks/ShowTextTags/ShowScale/ShowArcs/ShowBearings` 桌面版不识别（桌面读不到这些开关），桌面写的文件安卓 SettingsRepository 也不会读（安卓用 SharedPreferences 私有存储，与桌面文件完全不互通——需用户决策，见 §3）。
- **修复建议**：至少把默认值 JSON 改为桌面 15 键（ShowScaleBar 而非 ShowScale 等），保证桌面版能读取。

### 【P2】新航路点默认高度/深度被写成单位 X 坐标（海里值），桌面 CreateWaypoint 继承单位当前高度/深度
- **安卓证据**：`ui/components/WaypointEditorDialog.kt:74`
  ```kotlin
  altitudeDepth = com.simplot.android.data.util.CoordUtil.fileToNm(unit.x).toInt(),
  ```
  用单位 X 坐标（海里）当高度。新建航路点若被飞机/潜艇使用，会以荒谬的高度/深度为目标（MovementEngine.applyAltitudeDepth 会按此趋近）。
- **桌面版依据**：`伪代码_剩余模块.md §21`：CreateWaypoint 新航路点继承单位当前 Speed/Course，飞机/潜艇设 Ascent/Descent；`伪代码_核心算法.md §5` AssignedAltDepth 语义为目标高度。继承 `unit.altitude ?: unit.depth ?: 0` 即可。
- **修复建议**：`altitudeDepth = unit.altitude ?: unit.depth ?: 0`。

### 【P3】其它一致性/质量问题（汇总）
1. **比例尺条固定 50 nmi 文案**：`SceneCanvas.kt:358-382` 硬编码 "50 nmi"，不随 zoom 变化；桌面比例尺随缩放显示实际距离。建议按 zoom 计算整数距离。
2. **速度领导线**：`UnitRenderer.kt:153-171` 无箭头、长度固定像素（speed×3.2，clamp 14..140）不随 zoom 缩放、无桌面 DrawCourseLeader 的航路点折线；桌面 SpeedLeaders 长度按速度比例且随缩放。
3. **护航队阵位旋转约定与桌面相反**：桌面 `X += dist*Cos(rad); Y += dist*Sin(rad)`（`伪代码_UI交互层.md §27`，0°=东、逆时针），安卓 `ConvoyEngine.kt` 用 `offsetYards`（0°=北、顺时针），整环相对桌面旋转 90°（环对称所以形态相同，具体舰位不同）；且护航队生成在 (0,0)（见 P2 Relocate 项）。
4. **CSV 数字格式**：桌面 `####/#####/####.##/#######.##`，安卓 `%.1f/%.2f`（GameViewModel.kt:568）。
5. **运动命令文件名硬编码 "Player"**：`GameViewModel.kt:267`，桌面 WindowExportOrders 用 TextPlayerName（`伪代码_UI交互层.md §33`，`"Movement - <玩家名>.json"`）；SettingsDialog 已有 playerName 但未接入。
6. **复制单位轨迹号跨阵营取全局 max**：`GameViewModel.kt:390`，桌面蓝/红各一套 TrackNumber（`伪代码_剩余模块.md §9`）。
7. **每帧渲染大量 new Paint**：`MapRenderer.kt:159-190`、`UnitRenderer.kt` draw 内每多边形/每单位新建 Paint，单位多时 GC 压力大；建议缓存 Paint 对象。
8. **被动方位线忽略 BeamWidth**：`BearingRenderer.kt` 只画线，桌面 CBearing 有 BeamWidth（波束宽度楔形）。
9. **TurnControlBar 的 remember 无 key**：`TurnControlBar.kt:43-44`，切换场景后分钟/秒输入框保留旧值（显示与 file.time 不一致，编辑一次后自愈）。
10. **回放条缺 First/Last 跳转**：`ReplayBar.kt` 只有上帧/播放/下帧+滑块；桌面有 PushFirstTurn/PushLastTurn（滑块可近似替代，P3）。
11. **新建传感器/武器默认值**：`ArcEditorDialog.kt:71/90` 新建 Sensor/Weapon `isFilled=true, isVisible=true, arcAngle=0`（走模型默认）——按 P1 第 1 条修复后应给 360/50 默认并对齐 CArc.Constructor。
12. **MapRenderer.parseMapConfigJson 残废公式**：`MapRenderer.kt:93-97` `mapScaleMetersPerPx = metersPerWorldUnit * 10 * (boundaryWidth / boundaryWidth.toDouble())`，恒等于 0.1852，属于死代码（hasBoundary 分支先返回），建议删除避免误导。

---

## 2. 确认正确的点

1. **坐标变换数学与桌面一致（含 Y 翻转）**：`CameraMath.worldToScreen/screenToWorld`（render/CameraMath.kt:28-52）与桌面 `GetScreenX/GetScreenY`（asm 000140739a70/000140739c20 确认 `screen = -Y*zoom + offset` 带符号翻转）同为线性变换+Y 翻转；`CameraMath.zoomAt` 锚点缩放保持锚点世界坐标不动（测试 CameraMathTest 覆盖），与桌面 `ChangeZoom`（analysis_report.txt:927 确认内部调用 GetScreenX/Y 反算）语义一致；`pan` 符号方向正确（内容随手指）。
2. **方位/距离/新位置公式**：`CalcEngine`/`CoordUtil.bearingDeg/offsetNm` 与 `伪代码_核心算法.md §1/§22` 完全一致（ATan2(dx,dy)→度→负加 360；newPos = ref + dist×(Sin,Cos)）。
3. **弧角度语义基本正确**：`ArcRenderer.kt:81-95` 起始角 = heading − 90 + StartAngle、顺时针 sweep——与 asm（DrawSensors 中 `[unit-0x98](Course) + [arc-0x20](StartAngle) − 90 → ConvertToRadians → Sin/Cos 画点`）推导的"相对单位航向 + StartAngle、顺时针"一致（0°=正前方）。传感器/武器弧在单位层下方绘制、IsVisible 过滤、弧 JSON 键（IsFilled/IsVisible/Label/Tag/MinRange/MaxRange/StartAngle/ArcAngle/ArcColor）与 `伪代码_剩余模块.md §12` 一致；`parseColor` 的 `&hRRGGBB` 解析与桌面 `Integer.FromHex(右截 6 位)` 一致。
4. **CArc 默认 MaxRange=50**：`Unit.kt` Sensor/Weapon 默认 maxRange=50.0，与 asm 000140712110 CArc.Constructor 写入 0x4049000000000000(=50.0) 一致。
5. **地图 Z 序**：背景→深度→陆地→国界→国家→城市→边界框，与 `伪代码_绘制层.md §36` 的 Basemap→Waters→DepthPolys→Land→Countries→Cities→Borders→Boundary 一致（差异：安卓水域名是文字非多边形，见 P2）。
6. **单位符号 Z 序**：TrackHistory→速度领导线→弧→单位符号→标签（SceneCanvas 顺序），与 `伪代码_绘制层.md §38` SymbolGenerator 的 TrackHistory→SpeedLeaders→ActiveSensors→Symbols→TextTags 一致。
7. **CWS 精灵图命名**：`UnitRenderer.spriteFileName`（blue/red/neutral/unknown_color_filled.png）与 `strings_ascii.txt`（blue_color_filled.png 等 12 个变体）前缀一致；只用了 color_filled 变体（P2 缺口见上）。
8. **传感器/武器弧编辑器字段**：ArcEditorDialog 的 Min/Max/StartAngle/ArcAngle/Color/Filled 列与桌面 ListboxArcs 列头（strings_ascii.txt:63029-63030 "Start Angle"/"Arc Angle"）对应。
9. **显示选项 15 键**：`PlayerSettings.kt` 完整保留 15 个桌面键名（含未落地的 ShowDepthKey/ShowSonar），Gson 序列化键与桌面 JSON 键一致；SettingsDialog 落地 12 项开关（4 项无效见 P1）。
10. **SpScn 混淆与三文件结构**：`SpScnCodec` 与桌面 `Referee/Blue/Red` 分侧文件（`伪代码_剩余模块.md §18`）一致；MovementOrdersCodec 的 `{"File":"Movement Orders","Units":[{IdNum,Waypoints}]}` 与 `伪代码_剩余模块.md §19` 一致。
11. **范围耗尽三选弹窗**（Continue/Delete/Stop）与 `伪代码_核心算法.md §7 HasRangeRemaining` 一致（MainActivity.kt:281-296）；Do/Undo/Next 门禁与桌面 Do→Undo→Next 流程一致。
12. **删除/沉没的 X 标记**：UnitRenderer 沉没画 X、选中画高亮框，与 `伪代码_绘制层.md §38` 一致；TrackRenderer 历史轨迹圆点+连线与 TrackHistory 一致。
13. **命中检测**：`SceneCanvas.hitTest` 屏幕距离阈值随 zoom 放大，与桌面 FindWaypoint 反算屏幕坐标命中语义一致。

---

## 3. 无法确定 / 需用户决策

1. **光栅 SCALE 的真实单位语义**（P1 第 6 条）：桌面伪代码"像素÷Scale"与安卓"px×km/px×54000"冲突。需要至少一份真实 `.map` 文件（含 SCALE 与城市像素坐标、以及真实世界坐标参照）来裁决哪边对，并据此改代码或改文档。**待用户提供样例**。
2. **地图坐标 ×10 换算**（MapDataParser.kt 注释"地图坐标×10000、存档×100000"）：仅凭"实测 Iron Bottom Sound"声明，反编译资料未直接给出矢量地图坐标→世界坐标的换算系数；若用户手头有桌面版生成的标准地图 JSON，建议抽查 City/Country 坐标验证 ×10 假设。
3. **弧的 MinRange 环带语义**（P1 第 2 条）：伪代码确认 MinRange 参与 DrawSensorArc，但未逐点展示双半径路径；若实际桌面版本对 MinRange=0 也画实心扇形，则仅需修挖洞白色问题。需一份含 MinRange>0 弧的存档截图对照。
4. **TextTags 默认值**：`Unit.kt` TextTags 默认 `tagCourseSpeed=true` 其余 false；桌面 TagXxx 默认值未见反编译记录（§13 只给了 JSON 键与格式）。默认标签内容以哪个为准需用户确认（桌面默认通常显示 "TN 轨迹号"）。
5. **player_settings 互通性**：安卓用 SharedPreferences 私有存储，桌面用 `player_settings.json` 文件。是否要求安卓读取/写回场景目录下的 player_settings.json（与桌面互通）？当前不互通（P2 第 8 条仅指默认文件键名）。**需产品决策**。
6. **CSV 导出定位**（P1 第 5 条）：保留"测量线导出"还是改回桌面"相对单位位置导出"？两者表头撞车但语义不同，需用户拍板。
7. **Neutral=白 的可见性**：桌面 Neutral 用白色画符号；安卓若改为纯白，在白背景/浅色海图上符号不可见（桌面地图通常有底色衬托）。是否同时给白色符号加深色描边，需用户确认是否接受与原版视觉有出入。
8. **护航队 2000 常量单位**：`伪代码_UI交互层.md §27` 写 `0x409fa4 = 2000` 且"某处 dist/2（0x40000000=2.0）"含义不明；安卓按 2000 码实现（ConvoyDialog 默认 2000）。若桌面实际是 2000 米/2000 码之外的单位，环半径会差 ~2×。需真机对比。

---

*审核日期：2026-08-10；证据全部来自 `decompile-win/simplot-decompile-win/`（伪代码×4 + asm/ + analysis_report.txt + strings_ascii.txt）与 simplot-android 源码（grep -n 核实行号）。*
