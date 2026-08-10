# findings-engine — 运动/回合/编队引擎审核（对照 Win 版反编译）

审核人：主会话（两轮子代理均超时未完成，改由主会话亲自审核，全部证据已逐行核实）。
依据：`decompile-win/simplot-decompile-win/` 伪代码文档 + asm 实测（CalcBearing / Move 已查 asm 原文）。

## 问题清单

### E1 [P1] 编队移动不同步成员航向/航速
- 安卓证据：`engine/MovementEngine.kt` `moveFormations()`（L107-141）只写 `m.x`/`m.y`，从不写 `m.course`/`m.speed`。
- 桌面依据：伪代码_剩余模块.md §10 `MoveCourseFormation`：`member.Course = center.Course`、`member.Speed = center.Speed`（反汇编注释确认偏移 [member-0xa0]）。
- 后果：成员保留旧航向/航速 → 下一回合 `applyUnitMove` 按旧航向移动后再被编队重定位（轨迹点错误），速度领导者/标签显示错误，存档成员 Course/Speed 与桌面版分歧。
- 修复：moveFormations 重定位后同步 `m.course = center.course; m.speed = center.speed`。

### E2 [P2] RelativeToCourse 用标称站位而非"实际偏移旋转"
- 安卓证据：`MovementEngine.moveFormations` L116-121：`bearing = formationBearing/1000 + center.course`，`dist = formationDistance`，直接重算站位。
- 桌面依据：`MoveCourseFormation` 取成员**当前实际偏移** `offX = member.X - center.X`，按航向做旋转矩阵 `newX = offX*Cos(h) - offY*Sin(h)`。成员被手动拖离标称站位时：桌面保持实际几何旋转，安卓拉回标称站位。
- 另需确认：安卓把 FormationBearing 当"相对航向角"，桌面 FormationBearing 字段在 Course 模式下的真实语义（罗盘角 or 相对角）需对拍。

### E3 [P2] 高度/深度缺单回合 180 米上限（注释与实现自相矛盾）
- 安卓证据：`MovementEngine.advance()` L68-72 注释声称"单回合变化上限 180（桌面版 180 常量）"；但 `applyAltitudeDepth()` L143-176 实现为 `step = min(rate, |target-cur|)`，**没有 180 封顶**。wp.ascent/descent 若填 1000，安卓一回合跳 1000 米。
- 桌面依据：伪代码_UI交互层.md §28："按 Ascent/Descent 速率爬升/下降，**限制单回合变化量（180 常量 = 最大变化）**"。
- 修复：`step = minOf(rate, 180L, abs(target-cur))`，或删除注释承认不同行为。

### E4 [P2] Range 扣减整数化且"至少扣 1 海里"，比桌面消耗快
- 安卓证据：`MovementEngine.applyUnitMove` L232-241：`u.range -= maxOf(1, distNm.roundToInt())`。5 节船 3 分钟回合实际 0.25nm 也扣 1；0.4nm 扣 1 但 0.6nm 也扣 1（roundToInt）。
- 桌面依据：伪代码_核心算法.md §2 `CalcDistanceToMove` 只按实际距离计算（Double），`HasRangeRemaining` 仅在 ≤0 时弹窗三选，无"每回合至少扣 1"。
- 修复：Range 改 Double 或 ×1000 定点累加真实距离；至少去掉 maxOf(1,…)。

### E5 [P2] Undo 恢复路径会摧毁 Objects 数组；快照漏 ignoreRange
- 安卓证据：`engine/TurnState.kt` `undo()` L100-103：`file.objects = file.units.map { it.idNum }` —— Objects 在桌面版是独立 JSON 键（`SaveObjects`，README 存档结构确认），重建为单位 IdNum 列表会丢内容/顺序。`MovementEngine.deepCopyUnit`（L186-192）手动补了 isNewThisTurn/maxSpeedKnots，**漏了 ignoreRange**（@Transient，Gson 往返丢失）。
- 修复：undo 快照连 objects/time 一起存；deepCopyUnit 补 ignoreRange。

### E6 [P1] 方位角约定与桌面版不一致（数学角 vs 罗盘角）
- 安卓证据：`data/util/CoordUtil.kt` `bearingDeg()` L52-58：`atan2(dx, dy)` → 0=北、顺时针（罗盘角）。
- 桌面依据（asm 实测，`asm/0001405a3a20_Game.CalcBearing_f8_o_Xojo.Point_o_Xojo.Point_.asm` L123 前）：`xmm0=dy, xmm1=dx → Xojo.ATan2(dy, dx)` → 0=东、逆时针（数学角），负值 +360。
- 影响面：测量工具方位显示（Measurement.CalcBearing）、CSV 导出 Bearing 列、被动方位 CBearing.CalcBearing、航路点 Bearing 显示——全部调 Game.CalcBearing。
- 注：Bearing 不落盘（Waypoint JSON 无 Bearing 键），所以是显示语义问题而非存档兼容问题。安卓罗盘角更符合航海习惯，但与桌面显示值差 (90°−x) 镜像。**需用户决策**：保持安卓罗盘约定（推荐，文档化差异）还是逐 bit 复刻桌面。

### E7 [P2] 回放对"当时不存在/无轨迹"的单位显示当前位置
- 安卓证据：`engine/ReplayEngine.kt` `positionAt()` L81-84：`track.isEmpty()` 或无 ≤target 点时返回 `u.x, u.y`（当前位置）。第 5 回合才创建的单位在第 1-4 回合回放帧中也以当前位置出现；已被删除的单位全程"复活"。
- 桌面依据：WindowTurnReplay 按回合归档恢复真实状态（伪代码_UI交互层.md §26，MatchUnitsToWaypoints）。
- 修复：帧位置缺失时按 PositionTimeCreated/Deleted 过滤单位，或显式标记"该时刻不存在"。

### E8 [P3] 到达时间丢秒级精度
- 安卓证据：`domain/engine/CalcEngine.kt` `arriveTime()` L41：`minutes = (hours*60).toLong()` 截断，秒被丢弃（低速长航程误差至 59 秒）。
- 桌面依据：伪代码_核心算法.md §5 CalcArriveTime：天/时/分/**秒** 逐级 Floor 分解。
- 修复：改用 seconds 取整：`dt.plusSeconds((hours*3600).toLong())`。

### E9 [P2] 转向/加速规则是鱼叉纸质规则扩展，桌面版无此模型（需模式开关）
- 安卓证据：`MovementEngine.applyUnitMove`/`turnMotion`/`SizeLevels`：前冲、45°分段、转向损失、0-75%/75-100% 加速档、急舵、isNewThisTurn 当回合不动。
- 桌面依据：`CMovableUnit.Move`（asm 0x1406d4480 经伪代码_核心算法.md §4）：位移 = 速度×时间 沿当前航向，**无转向损失/前冲/加速限制**；CalcCourseToTarget 是空实现。
- 定性：README 已声明为特性（鱼叉规则），属**有意偏离**；但应在文档和设置中明确"鱼叉规则模式 vs 桌面兼容模式"，否则与桌面同场景对拍永远对不上。

### E10 [P3] 航路点归档阈值为启发式，与桌面语义未对拍
- 安卓证据：`MovementEngine.archiveReachedWaypoint` L178-184：阈值 = max(本回合移动距离, 1nm)。且 L76 传参 `distOfTurn = newSpeedOf(u)*minutes/60.0` 用**回合末新航速**估算，Range 截断回合会高估阈值（可能误吞下一航路点）。
- 桌面依据：伪代码 CheckFutureWaypoints/ArchiveFutureWaypoint 的触发条件本身不完整（伪代码暗示每回合归档，与常理不符），需实机对拍。
- 修复：传实际 `distNm`；阈值语义待对拍后定。

### E11 [P3] 时间比较用字符串相等
- 安卓证据：`TurnState.detect` L31 `tt == pt`；`undo()` L108 `it.turnTime == ptBefore`。格式微差（如秒位补齐差异）即失效。
- 修复：统一 `TimeUtil.equal()`（已存在）。

### E12 [P2] FormationEngine.cancel 清空未来航路点而非恢复
- 安卓证据：`domain/engine/FormationEngine.kt` `cancel()` L54-56：`m.futureWaypointArray.clear()`。prepare() 也只记位置不备份原未来航路点。
- 桌面依据：伪代码_剩余模块.md §10 CancelMovement："恢复各成员的**原航路点**（CopyWayPoint 复制回来）"。
- 后果：编队 Do 后 Undo/取消，成员原有未来航路点永久丢失。
- 修复：prepare 时备份 futureWaypointArray 快照，cancel 时恢复。

## 确认正确的点
- 位移矢量公式 `距离×(sin航向, cos航向)`（0=北顺时针）与桌面 CalcMoveVector 的 `dX=speed·Sin(h), dY=speed·Cos(h)` 一致（asm + 伪代码 §3）。
- 距离=速度×时间（分钟分支 speed/60×minutes）与 CalcDistanceToMove 一致。
- Do/Undo/Next 三态状态机（TurnTime 与 PositionTime 分离、Next 追平）与桌面回合机制一致；门禁 canDo/canUndo/canNext 设计合理。
- 新位置计算 `ref + 距离×(sin,cos)` 与 ContainerNewPosition.PushCalcPosition 一致。
- 护航队 CreateConvoy：COMMODORE 居中 + 360/n 均布 + 2000 距离常量，与 asm 常量（0x409fa4…=2000）一致（距离单位码/文件单位待对拍，见 render 报告）。
- MaxDistanceToMove==0 → 不移动，与 Move asm 首条分支一致。

## 无法确定/需用户决策
1. E6 方位角约定：罗盘（安卓现状）vs 数学角（桌面原样）。
2. E9 是否提供"桌面兼容模式"（无转向损失/前冲）。
3. E10 航路点归档确切语义需桌面实机对拍。
4. E2 FormationBearing 在 RelativeToCourse 模式下的字段语义需对拍。
