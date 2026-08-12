package com.simplot.android.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.data.util.CoordUtil
import com.simplot.android.data.util.TimeUtil
import kotlin.math.abs
import kotlin.math.ceil
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
 * newCourse/courseDelta → 直接改航向；newSpeed/speedDelta/boost/decel → 直接改航速。
 */
object MovementEngine {

    const val NO_ADVANCE_DEG = 10.0
    const val RANGE_UNLIMITED = -100000

    // ============ 单位运动指令 ============

    /**
     * 航速指令（D9 简化：直接设定，无能力表/无转向损失）：
     * - [boost]/[decel]：按尺寸等级能力表加速/减速一档（保留参考，无 75% 分档）
     * - [speedDelta]：加速/减速 X 节（负数=减速）
     * - [newSpeed]：具体航速（节）
     */
    data class UnitMove(
        val idNum: String,
        var newCourse: Double? = null,      // 指定新航向（度）
        var courseDelta: Double? = null,    // 相对转向量（度，右正左负）
        var newSpeed: Double? = null,       // 指定新航速（节）
        var speedDelta: Double? = null,     // 相对加减速（节，负数=减速）
        var boost: Boolean = false,         // 提速一档（按能力表 accel）
        var decel: Boolean = false,         // 减速一档（按能力表 decel）
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
        // 必须在移动前捕获状态（移动会写入轨迹点，影响 detect）
        val stateBefore = TurnState.detect(file)
        // 保存 undo 快照（深拷贝，不落盘）；E5：连同 Objects 一起快照
        file.undoSnapshot = file.units.map { deepCopyUnit(it) }
        file.undoObjects = file.objects.toMutableList()

        for (u in file.units) {
            if (u.isNewThisTurn) continue   // 新单位当回合不移动
            val move = moves[u.idNum]
            applyUnitMove(file, u, move, minutes, curTime)
        }

        // 高度/深度随航路点调整（桌面版 ChangeAltitude/ChangeDepth）
        for (u in file.units) {
            if (u.isNewThisTurn) continue
            applyAltitudeDepth(u)
            // E10：归档阈值用本回合实际移动距离（新航速 × 时长）
            archiveReachedWaypoint(u, distOfTurn = newSpeedOf(u) * minutes / 60.0)
        }

        // 编队移动（桌面版 Formations.Move）：成员相对中心按编队几何重定位
        moveFormations(file.units)

        TurnState.advanceTime(file, interval, stateBefore)
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
                if (m.isNewThisTurn) continue
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
    private fun newSpeedOf(u: Unit): Double = u.speedKnots()

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
        val threshold = maxOf(distOfTurn * CoordUtil.NMI_SCALE.toDouble(), CoordUtil.NMI_SCALE.toDouble())
        if (distFile <= threshold) {
            u.futureWaypointArray.removeAt(0)
            u.pastWaypointArray.add(wp)
        }
    }

    /** 深拷贝单位（保留瞬态字段）；E5：补 ignoreRange */
    private fun deepCopyUnit(src: Unit): Unit {
        val gson = com.simplot.android.data.codec.JsonUtil.gson
        val copy = gson.fromJson(gson.toJson(src), Unit::class.java)
        // 瞬态字段手动复制
        copy.isNewThisTurn = src.isNewThisTurn
        copy.maxSpeedKnots = src.maxSpeedKnots
        copy.ignoreRange = src.ignoreRange
        return copy
    }

    private fun applyUnitMove(file: ScenarioFile, u: Unit, move: UnitMove?, minutes: Double, curTime: String) {
        val oldCourse = u.courseDeg()
        val oldSpeed = u.speedKnots()

        // 新航向：直接设定（桌面版无前冲/分段/转向损失）
        var newCourse = oldCourse
        if (move?.newCourse != null) newCourse = move.newCourse!!
        if (move?.courseDelta != null) newCourse = oldCourse + move.courseDelta!!
        newCourse = ((newCourse % 360.0) + 360.0) % 360.0   // 规范化 0-360

        // 新航速：直接设定（D9：无能力表/无 75% 档/无转向损失）
        var newSpeed = oldSpeed
        when {
            move?.newSpeed != null -> newSpeed = move.newSpeed!!
            move?.speedDelta != null -> newSpeed = oldSpeed + move.speedDelta!!
            move?.boost == true -> newSpeed = oldSpeed + SizeLevels.of(u).accel
            move?.decel == true -> newSpeed = oldSpeed - SizeLevels.of(u).decel
        }
        if (newSpeed < 0) newSpeed = 0.0

        // 高度/深度（单位：米，桌面版原样存取）
        if (move?.newAltitude != null && u.altitude != null) u.altitude = move.newAltitude!!
        if (move?.newDepth != null && u.depth != null) u.depth = move.newDepth!!

        // 记录起点轨迹（移动前位置）——桌面版对象结构
        u.pastWaypointArray.add(makeWaypoint(u, curTime))

        // Range 限制（-100000 = 无限制；0 = 已耗尽停止）
        // E4 修复：按实际距离扣减（roundToInt），去掉"至少扣 1 海里"的过度消耗
        var distNm = newSpeed * minutes / 60.0
        if (u.range >= 0 && !u.ignoreRange) {
            if (distNm >= u.range) {
                distNm = u.range.toDouble()   // 本回合耗尽剩余 Range
                u.range = 0
            } else {
                u.range -= maxOf(0, distNm.roundToInt())   // 0.6nm→1，0.15nm→0
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
        val pastLen: Int
    )

    /** G15：捕获单位当前状态为撤销快照（DoMove 前调用） */
    fun snapshotOf(u: Unit): ManualMoveSnapshot = ManualMoveSnapshot(
        x = u.x, y = u.y, speed = u.speed, course = u.course,
        range = u.range, pastLen = u.pastWaypointArray.size
    )

    /** G15：从快照恢复单位状态（UndoMove；截断本步新增的轨迹点） */
    fun restoreSnapshot(u: Unit, s: ManualMoveSnapshot) {
        u.x = s.x
        u.y = s.y
        u.speed = s.speed
        u.course = s.course
        u.range = s.range
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
            if (distNm >= u.range) {
                distNm = u.range.toDouble()
                newRange = 0
            } else {
                newRange = u.range - maxOf(0, distNm.roundToInt())
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
        // Range 限制（-100000=无限制；0=已耗尽停止）——与回合引擎 E4 语义一致
        if (u.range >= 0 && !u.ignoreRange) {
            if (distNm >= u.range) {
                distNm = u.range.toDouble()
                u.range = 0
            } else {
                u.range -= maxOf(0, distNm.roundToInt())
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

    // ============ 参考函数（保留供测试/文档引用；D9 后不再用于主移动路径） ============

    data class TurnResult(val x: Long, val y: Long, val turnPoints: List<Pair<Long, Long>>, val actualCourse: Double)

    /** 渐进式转向（Harpoon 纸质规则遗留，D9 后保留为参考工具，主移动路径不再调用） */
    fun turnMotion(x: Long, y: Long, oldCourse: Double, newCourse: Double,
                   distFile: Double, advanceFile: Double): TurnResult {
        if (newCourse == oldCourse) {
            val dx = distFile * sin(Math.toRadians(oldCourse))
            val dy = distFile * cos(Math.toRadians(oldCourse))
            return TurnResult(x + dx.roundToInt().toLong(), y + dy.roundToInt().toLong(), emptyList(), oldCourse)
        }
        val delta = minimalDelta(oldCourse, newCourse)
        if (distFile <= 0) {
            return TurnResult(x, y, emptyList(), oldCourse)
        }
        if (abs(delta) <= NO_ADVANCE_DEG) {
            val dx = distFile * sin(Math.toRadians(newCourse))
            val dy = distFile * cos(Math.toRadians(newCourse))
            return TurnResult(x + dx.roundToInt().toLong(), y + dy.roundToInt().toLong(), emptyList(), newCourse)
        }
        val n = ceil(abs(delta) / 45.0).toInt()
        val step = if (delta > 0) 45.0 else -45.0
        var cx = x.toDouble(); var cy = y.toDouble()
        var remaining = distFile
        var cur = oldCourse
        val points = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until n) {
            if (remaining < advanceFile) {
                val dx = remaining * sin(Math.toRadians(cur))
                val dy = remaining * cos(Math.toRadians(cur))
                return TurnResult((cx + dx).roundToInt().toLong(), (cy + dy).roundToInt().toLong(), points, cur)
            }
            val dx = advanceFile * sin(Math.toRadians(cur))
            val dy = advanceFile * cos(Math.toRadians(cur))
            cx += dx; cy += dy
            points.add(cx.roundToInt().toLong() to cy.roundToInt().toLong())
            remaining -= advanceFile
            cur = (cur + step) % 360.0
        }
        val dx = remaining * sin(Math.toRadians(newCourse))
        val dy = remaining * cos(Math.toRadians(newCourse))
        return TurnResult((cx + dx).roundToInt().toLong(), (cy + dy).roundToInt().toLong(), points, newCourse)
    }

    /** 最小转向角（带符号） */
    fun minimalDelta(oldCourse: Double, newCourse: Double): Double {
        var d = (newCourse - oldCourse) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    fun altDepthOf(u: Unit): Int = u.altitude ?: u.depth ?: 0

    private fun makeWaypoint(u: Unit, ts: String): Waypoint {
        return Waypoint(
            x = u.x, y = u.y,
            altitudeDepth = altDepthOf(u),
            number = 1, isTurnTime = true,
            positionTime = ts
        )
    }
}

/** 尺寸等级能力表（保留供 boost/decel 一档加减速参考；D9 后无转向损失/无 75% 分档） */
data class SizeLevel(
    val accel: Double, val accelHigh: Double, val decel: Double,
    val adv: Double, val loss: Double, val advEmer: Double, val lossEmer: Double
)

object SizeLevels {
    val TABLE = mapOf(
        "slowA" to SizeLevel(4.0, 2.0, 6.0, 400.0, 2.0, 300.0, 3.0),
        "fastA" to SizeLevel(6.0, 3.0, 9.0, 400.0, 2.0, 300.0, 3.0),
        "B" to SizeLevel(10.0, 5.0, 12.0, 300.0, 2.0, 200.0, 3.0),
        "C" to SizeLevel(12.0, 6.0, 15.0, 300.0, 1.0, 200.0, 2.0),
        "D" to SizeLevel(12.0, 6.0, 15.0, 200.0, 1.0, 100.0, 2.0),
        "slowF" to SizeLevel(15.0, 8.0, 18.0, 100.0, 1.0, 50.0, 2.0),
        "fastF" to SizeLevel(25.0, 12.0, 30.0, 100.0, 0.5, 50.0, 1.0)
    )

    val CLASS_DEFAULT = mapOf(
        "BB" to "A", "BC" to "A", "CL" to "B", "CA" to "B", "CC" to "B",
        "CV" to "B", "DD" to "C", "FF" to "F"
    )

    /** 尺寸级名（A/F 按最大航速分快慢） */
    fun levelName(unitClass: String, maxSpeedKnots: Double?): String {
        val lv = CLASS_DEFAULT[unitClass] ?: "B"
        return when (lv) {
            "A" -> if (maxSpeedKnots == null || maxSpeedKnots >= 25.0) "fastA" else "slowA"
            "F" -> if (maxSpeedKnots == null || maxSpeedKnots >= 30.0) "fastF" else "slowF"
            else -> lv
        }
    }

    fun of(unit: com.simplot.android.data.model.Unit): SizeLevel {
        return TABLE[levelName(unit.unitClass, unit.maxSpeedKnots)] ?: TABLE["B"]!!
    }
}
