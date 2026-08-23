package com.simplot.android.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.data.util.CoordUtil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 运动计算引擎（D9 决策 2026-08-10：向桌面版看齐）。
 *
 * 桌面版语义（反汇编确认，伪代码_核心算法.md）：
 * - 距离 = 速度 × 时间（整回合/分钟/秒三分支，Speed 节 → 海里）
 * - 位移 = 距离 × (Sin航向, Cos航向)，沿当前航向匀速直行
 * - 无转向损失 / 无前冲 / 无 45° 分段 / 无加速能力表（加速档）
 * - MaxDistanceToMove 截断；Range 耗尽检查（三选弹窗）
 * - 高度/深度按航路点 Ascent/Descent 速率趋近，单回合变化上限 180（E3）
 * - 航路点到达后归档（ArchiveFutureWaypoint）
 *
 * 指令表（UnitMove）保留为 UI 便捷入口，但语义简化为直接设定：
 * newCourse/courseDelta → 直接改航向；newSpeed/speedDelta → 直接改航速。
 */
object MovementEngine {

    const val NO_ADVANCE_DEG = 10.0
    const val RANGE_UNLIMITED = -100000

    // ============ 单位运动指令 ============

    /**
     * 航速指令（D9 简化：直接设定，无能力表/无转向损失）：
     * - [speedDelta]：加速/减速 X 节（负数=减速）
     * - [newSpeed]：具体航速（节）
     * - [newAltitude]/[newDepth]：飞机/潜艇新高度/深度（米，原样存取）
     * - [emergency]：急舵（保留字段，桌面版无此概念，不生效）
     * （#8 G66：boost/decel 尺寸级能力表加减速为 D9 移除的死代码，已删除；
     *  依赖这些字段/极地转向表的过时回归测试已同步清理）
     */
    data class UnitMove(
        val idNum: String,
        var newCourse: Double? = null,      // 指定新航向（度）
        var courseDelta: Double? = null,    // 相对转向量（度，右正左负）
        var newSpeed: Double? = null,       // 指定新航速（节）
        var speedDelta: Double? = null,     // 相对加减速（节，负数=减速）
        var newAltitude: Int? = null,       // 飞机新高度（米，原样存取）
        var newDepth: Int? = null,          // 潜艇新深度（米，原样存取）
        var emergency: Boolean = false      // 急舵（保留字段，桌面版无此概念，不生效）
    )

    /**
     * 推进一个回合：移动所有单位（或按指令仅移动指定单位），推进时间，记录轨迹
     * @param file 存档（会被就地修改）
     * @param interval 回合时长
     * @param moves 单位移动指令表（key=IdNum）；为空则全部单位直行
     */
    fun advance(file: ScenarioFile, interval: com.simplot.android.data.model.TurnInterval,
                moves: Map<String, UnitMove> = emptyMap()) {
        val minutes = interval.totalMinutes()
        val curTime = file.time.currentPositionTime
        // #6（G40）：记录本回合开始时仍有未来航路点的单位——"本回合消费了最后一个未来航路点"
        // 的判定基线（避免误报：从未设航线的单位（历史有轨迹、当前无未来航路点）不再被标记）。
        val hadFutureWpIds = file.units.filter { it.futureWaypointArray.isNotEmpty() }.map { it.idNum }.toSet()
        // 每回合重置最终航路点标记（引擎精确标记，替代 AdvanceTurnUseCase 的过宽条件）
        file.units.forEach { it.reachedFinalWaypoint = false }
        // 保存 undo 快照（深拷贝，不落盘）；E5：连同 Objects 一起快照
        file.undoSnapshot = file.units.map { deepCopyUnit(it) }
        file.undoObjects = file.objects.toMutableList()
        // R1：新建单位当回合不移动、次回合参与（桌面无此标志；安卓用 isNewThisTurn 实现一次豁免）
        // 捕获本回合新建集合，快照已保留标记，随后逐单位清标记并跳过本回合移动/归档/高度
        val newThisTurnIds = file.units.filter { it.isNewThisTurn }.map { it.idNum }.toSet()
        // 清标记（下一次 Do 即参与）
        file.units.forEach { if (it.idNum in newThisTurnIds) it.isNewThisTurn = false }

        // R2：传真实 distNm 并改顺序 move→archive→altitude（桌面 CustomTimer：Move→Archive→ChangeAltitude）
        val distById = mutableMapOf<String, Double>()
        for (u in file.units) {
            if (u.idNum in newThisTurnIds) continue
            val move = moves[u.idNum]
            val d = applyUnitMove(file, u, move, minutes, curTime)
            distById[u.idNum] = d
        }
        // 先归档（用真实移动距离，含 Range 截断；Range=0 时 dist=0 仍以 1nm 最小阈值判定）
        for (u in file.units) {
            if (u.idNum in newThisTurnIds) continue
            val d = distById[u.idNum] ?: 0.0
            archiveReachedWaypoint(u, distOfTurn = d)
        }
        // 后高度/深度（目标取归档后的首个未来航路点，与桌面 ChangeAltitude 时序一致）
        for (u in file.units) {
            if (u.idNum in newThisTurnIds) continue
            applyAltitudeDepth(u)
        }

        // 编队移动（桌面版 Formations.Move）：成员相对中心按编队几何重定位
        moveFormations(file.units)

        // #6（G40）：本回合消费了最后一个未来航路点的单位 → 精确标记触发弹窗（桌面 NoFutureWaypoints）
        for (u in file.units) {
            if (u.idNum in hadFutureWpIds && u.futureWaypointArray.isEmpty()) u.reachedFinalWaypoint = true
        }

        TurnState.advanceTime(file, interval)
    }

    /**
     * 编队移动（桌面版 Formations.Movement.DoMove 分派三模式）：
     * 1. RelativeToCompass：位置 = 中心 + Distance × (Sin/Cos FormationBearing)
     * 2. RelativeToCourse：位置 = 中心 + Distance × (Sin/Cos (中心航向 + bearing))
     * 3. Column：成员排在中心正后方（沿编队航向反向），距离 = 序号 × Distance
     * E1 修复：重定位后成员 Course/Speed 同步为中心值（桌面 MoveCourseFormation 明确同步）。
     */
    private fun moveFormations(units: MutableList<Unit>) {
        val groups = units.filter { (it.isInFormation == true || it.isFormationCenter == true) && !it.formationName.isNullOrBlank() }
            .groupBy { it.formationName ?: "" }
        for ((_, members) in groups) {
            val center = members.firstOrNull { it.isFormationCenter == true }
                ?: members.firstOrNull() ?: continue
            val centerId = center.idNum
            val type = center.formationType ?: "RelativeToCompass"
            var colIdx = 1
            for (m in members) {
                if (m.idNum == centerId || m.isFormationCenter == true) continue
                // 新建单位已随中心编队重定位（R1 本回合豁免仅针对位移；队形几何仍跟中心）
                val bearingBase = (m.formationBearing ?: 0).toDouble() / 1000.0
                val distFile = (m.formationDistance ?: 0).toDouble()
                when (type) {
                    "RelativeToCourse" -> {
                        val bearingRad = Math.toRadians(bearingBase + center.courseDeg())
                        m.x = center.x + (distFile * sin(bearingRad)).roundToInt().toLong()
                        m.y = center.y + (distFile * cos(bearingRad)).roundToInt().toLong()
                    }
                    "Column" -> {
                        val bearingRad = Math.toRadians(center.courseDeg() + 180.0)
                        val d = distFile * colIdx
                        m.x = center.x + (d * sin(bearingRad)).roundToInt().toLong()
                        m.y = center.y + (d * cos(bearingRad)).roundToInt().toLong()
                        colIdx++
                    }
                    else -> {  // RelativeToCompass
                        val bearingRad = Math.toRadians(bearingBase)
                        m.x = center.x + (distFile * sin(bearingRad)).roundToInt().toLong()
                        m.y = center.y + (distFile * cos(bearingRad)).roundToInt().toLong()
                    }
                }
                // E1：成员航向/航速同步中心（桌面版 MoveCourseFormation 语义）
                m.course = center.course
                m.speed = center.speed
            }
        }
    }

    /** 当前航速（节） */

    // 兼容保留：已有调用点不再依赖

    /**
     * 高度/深度引擎：向首个未来航路点指定的高度/深度趋近。
     * E3 修复：单回合变化上限 180（桌面版 180 常量 = 最大变化），
     * step = min(速率, 180, 距目标距离)。
     */
    private fun applyAltitudeDepth(u: Unit) {
        val wp = u.futureWaypointArray.firstOrNull() ?: return
        // E3：桌面版单回合最大变化 180 常量（米）；存档为 ×1000 定点 → 180000
        val maxChange = 180_000L
        if (u.altitude != null) {
            val target = wp.assignedAltDepth.toLong()   // 米 ×1000
            val cur = u.altitude!!.toLong()             // 米 ×1000
            val rate = if (target > cur) wp.ascent.toLong() else wp.descent.toLong()
            if (rate > 0) {
                val step = minOf(rate, maxChange, kotlin.math.abs(target - cur))
                u.altitude = when {
                    target > cur -> (cur + step).toInt()
                    target < cur -> (cur - step).toInt()
                    else -> cur.toInt()
                }
            }
        }
        if (u.depth != null) {
            val target = wp.assignedAltDepth.toLong()   // 米 ×1000
            val cur = u.depth!!.toLong()                // 米 ×1000
            val rate = if (target > cur) wp.descent.toLong() else wp.ascent.toLong()
            if (rate > 0) {
                val step = minOf(rate, maxChange, kotlin.math.abs(target - cur))
                u.depth = when {
                    target > cur -> (cur + step).toInt()
                    target < cur -> (cur - step).toInt()
                    else -> cur.toInt()
                }
            }
        }
    }

    /**
     * 航路点归档（桌面版 ArchiveFutureWaypoint）：单位到达首个未来航路点附近后
     * 将其移到 PastWaypointArray 末尾。到达判定：距离 ≤ max(本回合移动距离, 1 海里)。
     */
    private fun archiveReachedWaypoint(u: Unit, distOfTurn: Double) {
        val wp = u.futureWaypointArray.firstOrNull() ?: return
        val dx = (u.x - wp.x).toDouble()
        val dy = (u.y - wp.y).toDouble()
        val distFile = kotlin.math.sqrt(dx * dx + dy * dy)
        // FIX10：去掉 1nm 最小阈值——桌面 ArchiveFutureWaypoint 按本回合实走距离判定，
        // 慢速船（如拖轮 0.25nm）不应被提前吸走航路点。distOfTurn<=0（停船/Range耗尽）不归档。
        val threshold = distOfTurn * CoordUtil.NMI_SCALE.toDouble()
        if (threshold <= 0.0) return
        if (distFile <= threshold) {
            u.futureWaypointArray.removeAt(0)
            u.pastWaypointArray.add(wp)
        }
    }

    /** 深拷贝单位（保留瞬态字段）；E5：补 ignoreRange；#22：补编队准备瞬态字段；E3：已补 reachedFinalWaypoint/rangeNmMm */
    private fun deepCopyUnit(src: Unit): Unit {
        val gson = com.simplot.android.data.codec.JsonUtil.gson
        val copy = gson.fromJson(gson.toJson(src), Unit::class.java)
        // 瞬态字段手动复制（@Transient Gson 忽略）
        copy.isNewThisTurn = src.isNewThisTurn
        copy.ignoreRange = src.ignoreRange
        copy.reachedFinalWaypoint = src.reachedFinalWaypoint
        copy.rangeNmMm = src.rangeNmMm
        // rangeMm 持久化键会随 gson 往返自动复制，无需手动补
        copy.formationWaypointBackup = src.formationWaypointBackup?.toMutableList()
        copy.formationPrepPosition = src.formationPrepPosition
        return copy
    }

    private fun applyUnitMove(file: ScenarioFile, u: Unit, move: UnitMove?, minutes: Double, curTime: String): Double {
        val oldCourse = u.courseDeg()
        val oldSpeed = u.speedKnots()

        // 新航向：直接设定（桌面版无前冲/分段/转向损失）
        var newCourse = oldCourse
        if (move?.newCourse != null) newCourse = move.newCourse!!
        if (move?.courseDelta != null) newCourse = oldCourse + move.courseDelta!!
        newCourse = ((newCourse % 360.0) + 360.0) % 360.0   // 规范化 0-360

        // 新航速：直接设定（D9：无能力表/无 75% 档/无转向损失；#8 已移除 boost/decel 分支）
        var newSpeed = oldSpeed
        when {
            move?.newSpeed != null -> newSpeed = move.newSpeed!!
            move?.speedDelta != null -> newSpeed = oldSpeed + move.speedDelta!!
        }
        if (newSpeed < 0) newSpeed = 0.0

        // 高度/深度（单位：米，桌面版原样存取）
        if (move?.newAltitude != null && u.altitude != null) u.altitude = move.newAltitude!!
        if (move?.newDepth != null && u.depth != null) u.depth = move.newDepth!!

        // 记录起点轨迹（移动前位置）——桌面版对象结构
        u.pastWaypointArray.add(makeWaypoint(u, curTime))

        // R4：Range 毫米海里余额（避免 0.4→1/0.6→1 的 round 漂移；桌面 RangeRemaining Double；跨存盘用 RangeMm）
        var distNm = newSpeed * minutes / 60.0
        if (u.range >= 0 && !u.ignoreRange) {
            if (u.rangeNmMm < 0) u.initRangeMmFromPersisted()
            val needMm = (distNm * 1000.0).toLong()
            if (needMm >= u.rangeNmMm) {
                distNm = u.rangeNmMm / 1000.0
                u.rangeNmMm = 0
                u.syncRangeIntFromMm()
            } else {
                u.rangeNmMm -= needMm
                u.syncRangeIntFromMm()
            }
        }

        // 位移：沿新航向直行（桌面 CalcMoveVector + Position.Offset）
        val distFile = (distNm * CoordUtil.NMI_SCALE).toLong()
        if (newSpeed <= 0 || distFile <= 0) {
            // 0 距离：不移动（航向也不变——与既有测试语义一致）
            u.course = (oldCourse * 1000).roundToInt()
        } else {
            val rad = Math.toRadians(newCourse)
            u.x += (distFile * sin(rad)).roundToInt().toLong()
            u.y += (distFile * cos(rad)).roundToInt().toLong()
            u.course = (newCourse * 1000).roundToInt()
        }
        u.speed = (newSpeed * 1000).roundToInt()
        return distNm
    }

    // ============ G15 手动移动控制（桌面版 ContainerMove DoMove/Pause/UndoMove） ============

    /** 手动移动速度档位（桌面版 PopupMoveSpeed：倍率列表，应用在当前航速上） */
    val MANUAL_MOVE_GEARS = listOf(0.5, 1.0, 2.0, 4.0)

    /** G15：手动移动单步快照（UndoMove 恢复用，纯数据可测） */
    data class ManualMoveSnapshot(
        val x: Long,
        val y: Long,
        val speed: Int,
        val course: Int,
        val range: Int,
        val rangeNmMm: Long,   // R4：毫米余额一并快照，否则 UndoMove 凭空漏油
        val pastLen: Int
    )

    /** G15：捕获单位当前状态为撤销快照（DoMove 前调用） */
    fun snapshotOf(u: Unit): ManualMoveSnapshot = ManualMoveSnapshot(
        x = u.x, y = u.y, speed = u.speed, course = u.course,
        range = u.range, rangeNmMm = u.rangeNmMm, pastLen = u.pastWaypointArray.size
    )

    /** G15：从快照恢复单位状态（UndoMove；截断本步新增的轨迹点） */
    fun restoreSnapshot(u: Unit, s: ManualMoveSnapshot) {
        u.x = s.x
        u.y = s.y
        u.speed = s.speed
        u.course = s.course
        u.range = s.range
        u.rangeNmMm = s.rangeNmMm   // R4：恢复毫米余额镜像
        while (u.pastWaypointArray.size > s.pastLen) {
            u.pastWaypointArray.removeAt(u.pastWaypointArray.size - 1)
        }
    }

    /**
     * G15：规划一次手动移动（纯计算，不修改单位；供 UI 缓冲式编辑 / 预览）。
     * 沿当前航向以 航速×档位 直行 [minutes] 分钟，返回新位置与新 Range（E4 语义）。
     * @return null 表示不可移动（航速 0 / 距离为 0 / Range 已耗尽）
     */
    data class ManualMovePlan(
        val newX: Long,
        val newY: Long,
        val newRange: Int,
        val distNm: Double
    )

    fun planManualMove(u: Unit, minutes: Double, gear: Double): ManualMovePlan? {
        var distNm = u.speedKnots() * gear * minutes / 60.0
        if (distNm <= 0) return null
        var newRange = u.range
        if (u.range >= 0 && !u.ignoreRange) {
            if (u.rangeNmMm < 0) u.initRangeMmFromPersisted()
            val needMm = (distNm * 1000.0).toLong()
            if (needMm >= u.rangeNmMm) {
                distNm = u.rangeNmMm / 1000.0
                newRange = 0
            } else {
                newRange = ((u.rangeNmMm - needMm) / 1000).toInt()
            }
        }
        val distFile = (distNm * CoordUtil.NMI_SCALE).toLong()
        if (distFile <= 0) return null
        val rad = Math.toRadians(u.courseDeg())
        val nx = u.x + (distFile * sin(rad)).roundToInt().toLong()
        val ny = u.y + (distFile * cos(rad)).roundToInt().toLong()
        return ManualMovePlan(nx, ny, newRange, distNm)
    }

    /**
     * G15：手动移动一步（桌面版 ContainerMove PushDoMove）。
     * 沿当前航向以 航速×档位 直行 [minutes] 分钟，记录起点轨迹点并扣减 Range（E4 语义）。
     * 不推进回合时间、不移动其他单位（桌面版可单独驱动单位移动而不推回合）。
     * @return 是否发生位移（航速>0 且距离>0）
     */
    fun manualMoveStep(u: Unit, minutes: Double, gear: Double, curTime: String): Boolean {
        var distNm = u.speedKnots() * gear * minutes / 60.0
        if (distNm <= 0) return false
        // R4：毫米海里余额，与回合引擎一致（跨存盘用 RangeMm）
        if (u.range >= 0 && !u.ignoreRange) {
            if (u.rangeNmMm < 0) u.initRangeMmFromPersisted()
            val needMm = (distNm * 1000.0).toLong()
            if (needMm >= u.rangeNmMm) {
                distNm = u.rangeNmMm / 1000.0
                u.rangeNmMm = 0
                u.syncRangeIntFromMm()
            } else {
                u.rangeNmMm -= needMm
                u.syncRangeIntFromMm()
            }
        }
        val distFile = (distNm * CoordUtil.NMI_SCALE).toLong()
        if (distFile <= 0) return false
        // 记录起点轨迹（移动前位置）——与回合引擎 applyUnitMove 一致
        u.pastWaypointArray.add(makeWaypoint(u, curTime))
        val rad = Math.toRadians(u.courseDeg())
        u.x += (distFile * sin(rad)).roundToInt().toLong()
        u.y += (distFile * cos(rad)).roundToInt().toLong()
        return true
    }

    /** 当前高度/深度（米 ×1000 定点原值；飞机取高度、潜艇取深度，均无则 0） */
    fun altDepthOf(u: Unit): Int = u.altitude ?: u.depth ?: 0

    private fun makeWaypoint(u: Unit, ts: String): Waypoint {
        return Waypoint(
            name = u.name,
            x = u.x, y = u.y,
            // P2-1 修复：轨迹点补齐 Speed/Course/AssignedAltDepth（此前只写 x/y，历史轨迹
            // 航向航速恒 0，桌面版读取航迹时数据失真）——记录移动完成后的状态，与桌面
            // TrackHistory 语义一致。
            speed = u.speed,
            course = u.course,
            altitudeDepth = altDepthOf(u),
            // FIX11/13：assignedAltDepth 仅对飞机/潜艇有意义；水面单位写 0（桌面 ArchiveFutureWaypoint 对水面为 0）
            assignedAltDepth = if (u.altitude != null || u.depth != null) altDepthOf(u) else 0,
            number = u.pastWaypointArray.size + 1,   // 递增序号（桌面 ArchiveFutureWaypoint 序号语义）
            isTurnTime = true,
            positionTime = ts
        )
    }
}
