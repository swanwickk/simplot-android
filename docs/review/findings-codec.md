# SimPlot2 存档编解码/数据模型/文件 I/O 审阅报告（Android vs 桌面版）

审阅对象：`simplot-android`（Kotlin/Compose）`data/codec`、`data/model`、`data/repo`、`domain/registry`、`domain/usecase`、`engine/FogOfWar` 及关联测试。
权威依据：`decompile-win/simplot-decompile-win/`（README.md、analysis_report.txt、asm/ 反汇编）+ 官方 2.3.9 真实存档 `app/src/test/resources/scenarios/IronBottomSound_Referee.json`（与 `simplot-desktop/reference/` 完全一致）。
审阅日期：2026-08-10。

---

## 一、问题清单

### P0-1 新建单位后 `Scenario.LastId / CurrentTrackNumber / CurrentPlayerTrackNumber` 从不维护，桌面版续编必然撞 IdNum / TrackNumber（数据损坏）

**安卓侧证据**
- `data/model/Scenario.kt:8-10`：仅声明字段，全仓 grep（`lastId|currentTrackNumber|currentPlayerTrackNumber`）除声明外**无任何写入点**（仅 `engine/TurnState.kt:63,78,95` 写 `phase`）。
- `ui/GameViewModel.kt:427-431`（`nextId`）：`f.units` 中按**同类前缀**取 `max+1`，`padStart(3,'0')` 生成新 IdNum；`createNewUnit`（435 行起）与 `duplicateUnit`（384 行起）都**不更新** `scenario.lastId` / `scenario.currentTrackNumber`，TrackNumber 用 `(f.units.maxOfOrNull { it.trackNumber } ?: 2400) + 1` 本地计算。

**桌面版依据**
- 反汇编 `Game.Unit.GetIdNumber` @ 0x1406c2770（字符串 `'0'`/`'00'` 补零）+ README：「全局自增 ID（LastId 存于场景 JSON）」；`GetTrackNumber` @ 0x1406c2a30 / `GetPlayerTrackNumber` @ 0x1406c4430 自增 `CurrentTrackNumber` / `CurrentPlayerTrackNumber`。桌面新建单位**只依赖存档里的计数器**，不扫描现有单位。
- 参考实现 `SimPlot-harpoon-cas/scripts/scn_tool.py:114-133`（`add_unit`）明确维护：`LastId = 全部单位 IdNum 数字后缀最大值（与现有 LastId 取大，避免删除单位后回退）`、`CurrentTrackNumber` 自增。

**影响**：安卓建单位 S017（本地 max+1）保存后 `LastId` 仍是 16；桌面版再建一个单位 → `GetIdNumber` 返回 17 → 与安卓的 S017 **同 IdNum**。桌面端 `Objects` 列表、感知匹配、运动命令 `ParseUnitArray` 均按 IdNum 定位 → 二义性 → 存档数据错乱。TrackNumber 同理（桌面 `CurrentTrackNumber` 仍 5338，新单位与安卓单位撞 5339）。

**修复建议**：在 `nextId`/`createNewUnit`/`duplicateUnit` 内同步：
1. `scenario.lastId = max(scenario.lastId, 全部单位 IdNum 数字后缀最大值)`（照抄 scn_tool.add_unit 逻辑）；
2. TrackNumber 分配改用 `scenario.currentTrackNumber` 并 `+1` 写回；
3. 删除单位时 LastId 不回退（与 scn_tool 注释一致）。

---

### P1-1 序列化恒输出桌面版不写的键：每单位多 `FormationType` + 5 个编队键；丢 `WpDistance`（用户原始存档证实，2026-08-10 最终裁决）

**最终裁决（用户重新上传原始存档，md5=fixture `光荣号航母.json`，权威）**：
- **用户原始存档单位无 FormationType、无任何编队键（IsInFormation/IsFormationCenter/FormationBearing/FormationDistance/FormationName）、无 PerceptionArray**；**有 `WpDistance: 0`**（scn_tool 特有键）与 `PastWaypointArray1/FutureWaypointArray1`（带 1 后缀）。
- **app 保存行为实测**（对比原始 vs app 改动版）：新增 `MapFileName`、`FormationType`、5 个编队键、`PerceptionArray`（每单位 1 条 SeenBySide=自身）、数组键改无后缀；**删除 WpDistance 与 ...1 后缀** → 文件膨胀 + 键漂移。
- 修复：① `FormationType` 不落盘（或仅入队时写）；② 编队键改可空默认 null（Gson 省略 null），入队时才写；③ Unit 增加 `WpDistance` 字段（默认 0）保留；④ Scenario 不写空 `MapFileName`（或与官方一致仅在非空时写）。

原始证据存档：
- 安卓侧证据：`data/model/Unit.kt:36`：`@SerializedName("FormationType") var formationType: String = "RelativeToCompass"` —— **非空默认值 → Gson 每个单位恒输出该键**。
- 官方 Iron Bottom Sound（2.3.9）16 单位**无一含 FormationType**（Formations 数组的队形对象才有 `Type` 键）；不可移动单位（I002 Installation）不含 `Speed/Course/Range/PastWaypointArray/FutureWaypointArray`，安卓恒输出 → 与官方 2.3.9 字节不一致。
- 空 `SensorArray`/`WeaponArray` 桌面整体省略该键；安卓 `sensorArray/weaponArray` 为可空默认 null → 省略，一致 ✓。

**影响**：安卓「加载桌面存档→修改→保存」后，每单位多出 FormationType + 编队键、设施多出 5 个运动键、丢 WpDistance → 与桌面输出不再字节一致。桌面 `LoadUnits` 只读已知键（宽容），不会崩溃，但反复两边编辑后文件噪音累积。

---

### P1-2 安卓生成的 `player_settings.json` 与桌面版 schema 完全不同（四文件要求中该文件不兼容）

**安卓侧证据**
- `data/repo/ScenarioRepository.kt:107`：`DEFAULT_PLAYER_SETTINGS = {"ShowTracks":true,"ShowTrackTimes":false,"ShowTurnTrackTimes":false,"ShowUnits":true,"ShowTextTags":true,"ShowSensors":false,"ShowWeapons":false,"ShowGrid":true,"ShowScale":true,"ShowArcs":false,"ShowBearings":false}` —— 扁平结构、键名全部是自造的（`ShowTracks/ShowTrackTimes/ShowTurnTrackTimes/ShowUnits/ShowTextTags/ShowScale/ShowArcs/ShowBearings`）。
- 仅当文件不存在时写入（`ensurePlayerSettings`，98-102 行），不覆盖已有文件。

**桌面版依据（analysis_report，权威字符串）**
- `PlayerSettings.SaveFile` 键：`Player_Settings`（根）、`File`、`Display_Options`、`PlayerName`、`Units`；`LoadFile` 读 `Display_Options/PlayerName/Units`。
- `SaveDisplayOptions` 键（15 个）：`ShowCities/ShowCountries/ShowWaters/ShowWaypoints/ShowDepths/ShowDepthKey/ShowEs/ShowGrid/ShowScaleBar/ShowWeapons/ShowSensors/ShowSonar/ShowLabels/ShowSpeedLeaders/ShowFormations`。安卓写的键**一个都不在其中**。
- 顺带：安卓 `domain/model/PlayerSettings.kt` 的 15 个 `show*` 键名与桌面 `Display_Options` 完全对应 ✓，但**只存 SharedPreferences，从不写场景目录的 player_settings.json**（`data/repo/SettingsRepository.kt`）。

**影响**：桌面 `LoadPlayerSettings` 在这个文件里读不到任何自己的键 → 全部回默认值。无崩溃、无覆盖，但「生成四文件」中的 player_settings.json 实际是无效文件，与桌面版互不兼容。

**修复建议**：`DEFAULT_PLAYER_SETTINGS` 改为桌面 schema：
`{"Player_Settings":{"File":"","Display_Options":{ShowCities:true,...,ShowFormations:true},"PlayerName":"Player","Units":[]}}`；或至少使用 `Display_Options` 的 15 个键。

---

### P2-1 运动命令（MovementOrders）导出含历史轨迹、导入只写 future —— 与桌面 `ParseUnitArray` 语义可能冲突

**安卓侧证据**
- `data/codec/MovementOrdersCodec.kt:27`：`(u.futureWaypointArray + u.pastWaypointArray).forEach { wps.add(...) }` —— 导出 = 未来+历史。
- `MovementOrdersCodec.kt:62`（`applyTo`）与 `data/repo/ScenarioRepository.kt:195`：导入只 `target.futureWaypointArray = wps.toMutableList()`。

**桌面版依据**
- `BuildWaypointArray` @ 0x1406bbc70 遍历 `[unit+0x30]` 的**单个**路径点集合调 `WaypointToJson`；`CheckPastWaypoints` @ 0x1406d4f30 与 `CheckFutureWaypoints` @ 0x1406da1d0 **都访问 `[unit+0x30]`** → 桌面运行时是**单一路径点列表**，Past/Future 是存档拆分/视图概念。`ParseUnitArray` @ 0x1406bc4d0 按 IdNum 匹配后逐个 `JsonToWaypoint` 恢复。
- 结果：桌面导出的 Movement Orders 是否含历史点、恢复时如何落回 Past/Future，静态分析无法定论（见「无法确定」第 2 条）。

**影响**：若桌面文件含历史轨迹点，安卓导入会全部塞进 `FutureWaypointArray` → 船只路线错乱；安卓导出把已走历史写进「运动命令」（语义上命令不应含历史）。双方无真实样本对拍，风险等级 P2。

**修复建议**：取得一份桌面版真实生成的 `Movement - <玩家名>.json` 后：导出只写未来航路点（或与桌面一致的单列表序）；导入时按 `PositionTime`/`IsTurnTime`/`Number` 分派 Past/Future，或整体替换单列表（对齐桌面运行时结构）。

---

### P2-2 潜艇子类型表缺桌面版默认类型 `"Subsurface"`

**安卓侧证据**：`domain/registry/UnitTypeRegistry.kt:47-51` `SUBSURFACE_TYPES` 共 12 项：`Torpedo, Submarine, Sub Diesel, ...` —— **缺 `"Subsurface"`**。

**桌面版依据**：analysis_report `FillSubUnitTypes` 字符串：`['Torpedo', 'Subsurface', 'Submarine', 'Sub Diesel', ...]` 共 13 项；且 `CSubsurfaceUnit.Constructor` @ 0x1406cbf80 默认 UnitType 就是 `"Subsurface"`（README 第 9 节）。

**影响**：新建潜艇的默认/可选类型缺一项（UI 菜单不完整）；`domainOf` 因 `unit.depth != null` 回退仍能判对，无数据破坏。

**修复建议**：`SUBSURFACE_TYPES` 补 `"Subsurface"`（放 `"Torpedo"` 之后，与桌面顺序一致）。

---

### P2-3 自动存档文件名的时间源与桌面版不同（回合时间 vs 保存时刻）

**安卓侧证据**：`data/repo/ScenarioRepository.kt:206-209` `saveAuto`：`LocalDateTime.now()`（墙钟）格式 `"yyyy-MM-dd_HH-mm-ss"` → `"Referee Turn N_<墙钟>.json"`。

**桌面版依据**：`SaveAuto` @ 0x1406189c0 反汇编：
- 0x140618a68 `DateTime.SQLDateTime.Get`（edx=0）取**当前回合时间**（如 `"1942-10-01 00:00:00"`）；
- 0x140618af0 `String._ReplaceAll(':','_')` → 时间串变 `"1942-10-01 00_00_00"`；
- 拼接 `"Referee Turn " + 回合号 + "_" + 该时间 + ".json"`（README 第 18 节：`Referee Turn <N>_<日期>_<时间>.json`）。

**影响**：文件名内容不同（安卓用保存时刻、桌面用回合时间），且日期与时间之间桌面是空格（SQL 格式）安卓是下划线。仅命名差异，不影响加载与内容兼容。

**修复建议**：`saveAuto` 改用 `data.time.currentTurnTime`，`replace(':', '_')`。

---

### P2-4 Perception / PassiveBearing 键序无真实样本验证（键集吻合但顺序未证实）

**安卓侧证据**：`data/model/Unit.kt` `Perception` 11 键（`PositionTimeStart, PositionTimeEnd, DetectionTime, SeenBySide, ShowAsSide, ShowAsType, ShowAltitude, ShowClass, ShowCourseSpeed, ShowDepth, ShowName`）；`PassiveBearing` 10 键（`Type, BeamLength, BeamWidth, Bearing, Emitter, ES, Label, PositionTimeStart, PositionTimeEnd, ShowAsSide`）。

**桌面版依据**：
- `PerceptionToJson` @ 0x140714830 反汇编引用 **11 个**字符串地址（含 analysis_report 未列出的第 11 个，0x140d66cd0）→ 与安卓 11 键（含 `ShowName`）**数量吻合**；`JsonToPerception` 只回读 7 键（`Blue/PositionTimeEnd/PositionTimeStart/ShowAsSide/SeenBySide/DetectionTime/ShowAltitude`），其余键写后不回读（宽容）→ 安卓多写/写序不同不会导致桌面读取失败。
- `BearingToJson` @ 0x1406fa050 引用 10 个字符串地址 → 与安卓 10 键（含 `ES`）**数量吻合**（README 剩余模块也写 `BearingToJson 含 Emitter/ES 字段`）。
- 但：analysis_report 字符串顺序 ≠ 真实文件键序（反例：`ArcToJson` 字符串序 `IsFilled,IsVisible,Label,Tag,...`，而真实存档 SensorArray 键序是 `Tag,Label,MinRange,...`）。**Perception/Bearing 的键序无法靠字符串序推断**，当前安卓键序属于「无样本下的最佳猜测」。

**影响**：键序若与桌面不同 → 字节级不一致；桌面回读不受影响。**风险低但无法证实**。

**修复建议**：找一份带 `PerceptionArray` / `PassiveBearingArray` 的桌面真实存档对拍键序后固化（可仿照 ScenarioRoundTripTest 的做法补 fixture）。

---

### P3-1 明文 .json 尾部字节 → 已由用户样本裁定 `\r\n`（2026-08-10 更新）

**更新**：用户上传的光荣号 Referee.json 尾部为 `\r\n`（全文唯一 `\r`，`b'710}]}\r\n'` 结尾）→ **scn_tool 的 `\r\n` 验证成立**，官方 Iron Bottom Sound 样本的 `\n` 应是下载/分享平台行尾归一化。D3 决策随之撤回「改 \n」，保持 `\r\n`。原结论存档：

- 安卓侧证据：`data/codec/SpScnCodec.kt:44-47` `toJsonFileBytes` 尾部追加 `"\r\n"`。
- 桌面版依据：官方 Iron Bottom Sound 存档（test resources 与 `simplot-desktop/reference/` 逐字节相同）以单个 `\n` 结尾、全文 `\r` 计数为 0（单行 JSON）。

---

### P3-2 非整值 Double 序列化格式不同（0.6 vs 0.5999999999999999778）

**安卓侧**：Gson 输出 `0.6`；**桌面**：Xojo JSONItem 输出二进制 double 的精确 17 位 `0.5999999999999999778`（真实存档 `MinRange` 即如此）。整值 Double（18.0）两边一致 ✓。

**影响**：仅字节差异，值等价、解析无损。若追求严格字节一致需自定义 Double 序列化复刻 Xojo 表示；一般可接受。

---

### P3-3 单位 Domain 判定顺序与桌面不同（UnitType 优先 vs IdNum 前缀）

**安卓侧**：`UnitTypeRegistry.domainOf`（`UnitTypeRegistry.kt:77-97`）优先 UnitType 字符串匹配 → 回退 depth/altitude → 回退 idNum 前缀。
**桌面**：`Referee.LoadUnits` @ 0x140640480 / `LoadObjects` @ 0x1406ac5d0 用 `IdNum.Left(1)` 分派子类（R/S/A/U/I/B/L/V，字符串 + `String._Left` 调用）。

**影响**：正常数据两者一致；仅当 IdNum 前缀与 UnitType 矛盾（异常数据）时行为不同。低风险，记录备查。

---

### P3-4 `JsonUtil.lenientFactory` 全局宽容 Collection←{} / Map←[]

`JsonUtil.kt:66-97`：所有 Collection 字段遇 `{}` 返回空列表、所有 Map 字段遇 `[]` 返回空 Map。好处：解决旧 Red.SpScn 读取崩溃（真机反馈）。风险：会把真正的结构错误（如 `Objects` 本应为数组却写成对象）静默吞掉，写入端不变（序列化仍写 []/{} 由字段默认类型决定）。可接受，建议在日志里留 trace 便于排查。

---

### P3-5 `ScenarioRepository.load()` 的文件识别对 BOM/异常编码无防御

`ScenarioRepository.kt:47-59`：非 `.spscn` 后缀先按明文 JSON 解析，`isScenarioJson` 失败再按 SpScn 解密。UTF-8 BOM 会直接失败（`JsonParser` 不认 BOM）且不会回退 SpScn（解密后也是乱码）→ 抛「不是有效的 SimPlot 场景存档」。桌面文件无 BOM，仅防御性缺口；建议 decode 前剥 BOM。

---

## 二、确认正确的点（有据可查）

1. **顶层键序**：`File, SimPlot Version, IsIntegerFile, Scenario, TypeOfGame, Time, Turns, Overlays, Objects, Units, Formations`（`ScenarioFile.kt` 声明序）与官方 2.3.9 存档逐字节一致；`Overlays`/`Formations` 均写 `{}`（空对象）与真实文件一致。
2. **单位键序**（除 P1-1 多余键）：`IdNum, Side, TrackNumber, Name, Number, UnitClass, UnitType, X, Y, ShowSunk, IsActiveRadar, IsActiveSonar, IsInFormation, IsFormationCenter, FormationBearing, FormationDistance, FormationName, PositionTimeCreated, PositionTimeDeleted, Speed, Course, Range, PastWaypointArray, FutureWaypointArray, TextTags` 与真实文件一致（Sensor/Weapon 紧随其后，桌面亦如此）。
3. **TextTags 9 键**（`TagAltitude, TagCallsign, TagClass, TagCourseSpeed, TagDepth, TagName, TagTrackNum, TagUnitType, AdditionalText`）键序与真实文件一致。
4. **Waypoint 12 键**（`Name, X, Y, Speed, Course, AltitudeDepth, AssignedAltDepth, Ascent, Descent, Number, IsTurnTime, PositionTime`）键序与真实文件一致；`PositionTime` 存在（analysis_report 字符串表未列但真实文件有，以真实文件为准）。
5. **Sensor/Weapon 9 键**（`Tag, Label, MinRange, MaxRange, StartAngle, ArcAngle, ArcColor, IsFilled, IsVisible`）键序与真实文件一致（注意与 ArcToJson 字符串序不同——真实文件优先）。
6. **TimeState / Turns**：`CurrentTurnTime/CurrentPositionTime/CurrentTurnInterval{Minutes,Seconds}`、`Turn` 的 `TurnTime/TurnInterval` 结构与键序与真实文件一致。
7. **SpScn 混淆**：ASCII−1/+1、尾部 `\x0c\t` 标记（加密后变 `\r\n`）、`{"File":"Red"` → `|#Gjmf#;#Sfe#` 样例断言正确（`SpScnCodecTest`）；与 scn_tool 及桌面已知样例一致。
8. **空 PastWaypointArray 写 `{}`**（`JsonUtil.WaypointListAdapter`）与真实文件一致；`PastWaypointArray1/FutureWaypointArray1` alternate 兼容旧文件/第三方工具（光荣号 fixture 即用 `...1` 键）。
9. **容错解析**：`Turns:{}`、`Objects:{}`、`SensorArray:{}`、`Formations:[]`、`Overlays:[]` 均能解析（`LegacyRedFileCompatTest`），与桌面旧版「空数组写 {}、空 Map 写 []」行为吻合。
10. **数值编码**：`Speed/Course ×1000`（真实文件 S004 `Speed:15000, Course:90000` = 15kt/90°）、`X/Y ×100000`（`X:-400000`）、`Range:-100000`=无限制、`Altitude/Depth` 米**原样无定点**（Unit.kt 注释已修正，MovementEngine 内部也按米处理，一致）。
11. **IdNum 格式**：前缀+3 位补零（`S001/L001/I002`…）与真实文件一致，与桌面 `GetIdNumber`（'0'/'00' 补零）一致；`nextId` 前缀分派 A/U/I/B/L/R/S/V 与桌面 `PasteNewUnit` 分派一致。
12. **运动命令文件**：根 `{"File":"Movement Orders","Units":[{"IdNum":"S001","Waypoints":[...]}]}`、文件名 `Movement - <玩家名>.json` 与桌面一致；`IdNum` 写**字符串**（桌面 BuildUnitArray 与 Referee.SaveUnits 用同一转换路径 0x140c5ffc0 写单位 -0x10 字符串字段，且真实存档 IdNum 是字符串）；analysis_report 中 `String._Left/_ToInteger` 仅用于内部去重/匹配，不影响文件值。
13. **Phase 状态机**（0=planning/2=post-movement）与桌面一致（`TurnState.kt:11` 注释 + 反汇编 `Game.Status`）；Do 只推进 `PositionTime`、Next 追 `TurnTime` 并追加 `Turns`，与桌面 UI 伪代码（PushNextTurn_Action @ 0x140b6c290）流程相符。
14. **`WpDistance` 丢弃是对的**：scn_tool 产物（光荣号 fixture）含 `WpDistance:0`，但官方 2.3.9 存档**没有**该键（桌面运行时虽有 `CMovableUnit.WaypointDistances` 属性，见 game_symbols.txt，但不落盘）；`ScenarioRoundTripTest.kt:134` 断言其消失符合官方格式。
15. **Setup 文件**：`saveSetup` 写 `File:"Setup"` 与桌面 `SaveSetupFile`（调 `Referee.SaveFile(stream,"Referee")`，文件类型 "Setup"/"JSON"/"json"）同格式。
16. **`TypeOfGame:0`（Int）** 与官方 2.3.9 存档一致；2024 桌面版 `LoadFile` 将读到的 TypeOfGame 归一为 Int 0/1（`LoadFile` asm 0x14062d0c0/0x14062d0d9 写全局 0x140d62220），`SaveFile` 写回 Int → 安卓写 Int 与两代桌面读兼容（侧文件取值见「无法确定」第 4 条）。
17. **Overlays 原样透传**（`Map<String,Any?>`，Gson LinkedTreeMap 保插入序）→ 桌面 Misc 标注数据往返不重排、不丢键。

---

## 三、无法确定 / 需用户决策的点

1. **Perception / PassiveBearing 键序**：无真实桌面样本（测试资源与 reference 均无 `PerceptionArray`/`PassiveBearingArray`）；analysis_report 字符串序已被 Arc 反例证明不可靠。需一份带感知/被动方位的桌面存档对拍。ShowName/ES 键集与桌面反汇编字符串数量吻合，建议保留。
2. **运动命令文件 Waypoints 的 Past/Future 组成与顺序**：桌面 `BuildWaypointArray` 遍历 `[unit+0x30]` 单列表（CheckPast/CheckFuture 同址），但存档里 Past/Future 的拆分规则、导出时是否含历史点、`ParseUnitArray` 恢复时落回哪个数组 —— 静态分析无法定论。**需用户提供一份桌面版真实生成的 Movement Orders 文件**，或决策安卓侧语义（建议导出只含未来航路点）。
3. **明文 .json 尾部字节 `\r\n` vs `\n`**：scn_tool「已逐字节验证 \r\n」与官方样本「\n」矛盾，疑似平台行尾归一化。需用户决策/实测。
4. **Blue/Red.SpScn 的 `TypeOfGame` 取值**：安卓恒写 `0`；2024 桌面 `LoadFile` 把 TypeOfGame 归一为 0/1 后 `SaveFile` 写回全局值 —— 侧文件可能写 `1`（映射未确证）。需桌面实测或接受 0。
5. **桌面 Blue.SpScn / Red.SpScn 内容语义**：安卓 `FogOfWar.applyPerspective` 剔除不可见单位 + 清空敌方可见单位的 PerceptionArray + 重写 Objects（`engine/FogOfWar.kt:114-129`）。桌面侧文件是「全量+按感知显示」还是「物理剔除」无样本可证；这属于产品/规则决策，建议用桌面版导出一对红蓝文件对拍。
6. **非整 Double 是否复刻 Xojo 17 位表示**：要严格字节一致则需自定义序列化；一般场景建议接受差异（P3-2）。

---

## 附：审阅方法说明

- 键名/键序结论以官方 2.3.9 真实存档（16 单位）逐单位键集实测 + `analysis_report.txt` 字符串表 + 关键函数 asm（SaveUnits/LoadUnits/SaveFile/LoadFile/SaveAuto/BuildUnitArray/ParseUnitArray/BuildWaypointArray/PerceptionToJson/BearingToJson）交叉验证；字符串地址序与真实文件键序冲突时**以真实文件为准**（如 Arc、Overlays 位置）。
- 未改动任何代码；仅输出本报告。
