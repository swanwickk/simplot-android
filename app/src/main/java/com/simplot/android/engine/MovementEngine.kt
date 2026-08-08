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
 * 运动计算引擎（移植自 scn_tool.py / simplot_cmd.py 的移动逻辑）
 *
 * 规则（鱼叉/Harpoon V）：
 * - 所有速度>0 的单位按 回合时长×航速 沿当前航向移动
 * - 转向 ≤10° 无需前冲，直接沿新航向移动
 * - 转向 >10° 需前冲 + 分段（单次最多 45°），渐进式（距离不足部分转向，0 距离不转向）
 * - 转向 ≥45° 加速减半；每 45° 转向损失航速
 * - 轨迹：移动前位置写入 PastWaypointArray（桌面版对象结构），转向点追加
 * - Range（海里，-100000=无限制）：随移动递减，耗尽后停止移动
 * - 新单位（isNewThisTurn）：当回合不移动（导弹/鱼雷发射规则）
 */
object MovementEngine {

    const val NO_ADVANCE_DEG = 10.0
    const val RANGE_UNLIMITED = -100000

    // ============ 单位运动指令 ============

    data class UnitMove(
        val idNum: String,
        var newCourse: Double? = null,      // 指定新航向（度）
        var courseDelta: Double? = null,    // 相对转向量（度，右正左负）
        var newSpeed: Double? = null,       // 指定新航速（节）
        var speedDelta: Double? = null,     // 相对加减速（节）
        var newAltitude: Int? = null,       // 飞机新高度（米）
        var newDepth: Int? = null,          // 潜艇新深度（米）
        var emergency: Boolean = false      // 急舵
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

        for (u in file.units) {
            if (u.isNewThisTurn) continue   // 新单位当回合不移动
            val move = moves[u.idNum]
            applyUnitMove(file, u, move, minutes, curTime)
        }

        TurnState.advanceTime(file, interval, stateBefore)
    }

    private fun applyUnitMove(file: ScenarioFile, u: Unit, move: UnitMove?, minutes: Double, curTime: String) {
        val oldCourse = u.courseDeg()
        val oldSpeed = u.speedKnots()
        val emergency = move?.emergency == true

        // 新航向
        var newCourse = oldCourse
        if (move?.newCourse != null) newCourse = move.newCourse!!
        if (move?.courseDelta != null) newCourse = (oldCourse + move.courseDelta!!) % 360.0
        if (newCourse < 0) newCourse += 360.0   // 规范化到 0-360

        // 新航速：原速 + 加减速 - 转向损失（每 45° 按表）
        val delta = minimalDelta(oldCourse, newCourse)
        val turnCount = turnCount(delta)
        val turnLoss = turnCount * turnLossKnots(u, emergency)
        var newSpeed = oldSpeed
        if (move?.newSpeed != null) newSpeed = move.newSpeed!!
        if (move?.speedDelta != null) {
            var accel = move.speedDelta!!
            if (accel > 0 && abs(delta) >= 45) accel /= 2.0  // 转向≥45° 加速减半
            newSpeed = oldSpeed + accel
        }
        newSpeed -= turnLoss
        if (newSpeed < 0) newSpeed = 0.0

        // 高度/深度
        if (move?.newAltitude != null && u.altitude != null) u.altitude = move.newAltitude!! * 1000
        if (move?.newDepth != null && u.depth != null) u.depth = move.newDepth!! * 1000

        // 记录起点轨迹（移动前位置）——桌面版对象结构
        u.pastWaypointArray.add(makeWaypoint(u, curTime))

        // Range 限制：可移动海里数（-100000 = 无限制；0 = 已耗尽停止）
        var distNm = newSpeed * minutes / 60.0
        if (u.range >= 0) {
            if (distNm >= u.range) {
                distNm = u.range.toDouble()   // 本回合耗尽剩余 Range
                u.range = 0
            } else {
                u.range -= maxOf(1, distNm.roundToInt())   // 至少扣 1 海里
            }
        }

        val distFile = (distNm * CoordUtil.NMI_SCALE).toLong()
        if (newSpeed <= 0 || distFile <= 0) {
            // 0 距离：不移动不转向
            u.course = (oldCourse * 1000).roundToInt()
        } else {
            val adv = advanceYards(u, emergency) * CoordUtil.NMI_SCALE / CoordUtil.YARDS_PER_NMI
            val (nx, ny, turnPts, actualCourse) = turnMotion(
                u.x, u.y, oldCourse, newCourse, distFile.toDouble(), adv
            )
            // 转向点加入轨迹（桌面版对象结构）
            for (pt in turnPts) {
                u.pastWaypointArray.add(
                    Waypoint(
                        x = pt.first, y = pt.second,
                        altitudeDepth = altDepthOf(u),
                        number = 1, isTurnTime = true,
                        positionTime = curTime
                    )
                )
            }
            u.x = nx; u.y = ny
            u.course = (actualCourse * 1000).roundToInt()
        }
        u.speed = (newSpeed * 1000).roundToInt()
    }

    // ============ 转向运动（渐进式） ============

    data class TurnResult(val x: Long, val y: Long, val turnPoints: List<Pair<Long, Long>>, val actualCourse: Double)

    fun turnMotion(x: Long, y: Long, oldCourse: Double, newCourse: Double,
                   distFile: Double, advanceFile: Double): TurnResult {
        if (newCourse == oldCourse) {
            // 直行
            val dx = distFile * sin(Math.toRadians(oldCourse))
            val dy = distFile * cos(Math.toRadians(oldCourse))
            return TurnResult(x + dx.roundToInt().toLong(), y + dy.roundToInt().toLong(), emptyList(), oldCourse)
        }
        val delta = minimalDelta(oldCourse, newCourse)
        if (distFile <= 0) {
            return TurnResult(x, y, emptyList(), oldCourse) // 0 距离：不转向
        }
        if (abs(delta) <= NO_ADVANCE_DEG) {
            // ≤10° 无需前冲，直接沿新航向移动
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
                // 距离不足以完成下一次前冲：沿当前航向走完剩余，不再转向
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
        // 全部前冲完成，剩余沿最终航向走
        val dx = remaining * sin(Math.toRadians(newCourse))
        val dy = remaining * cos(Math.toRadians(newCourse))
        return TurnResult((cx + dx).roundToInt().toLong(), (cy + dy).roundToInt().toLong(), points, newCourse)
    }

    // ============ 规则表 ============

    /** 最小转向角（带符号） */
    fun minimalDelta(oldCourse: Double, newCourse: Double): Double {
        var d = (newCourse - oldCourse) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    /** 转向次数 n = ceil(|Δ|/45) */
    fun turnCount(delta: Double): Int = if (abs(delta) >= 0.5) ceil(abs(delta) / 45.0).toInt() else 0

    /**
     * 每 45° 转向航速损失（节）。
     * 水下潜艇固定：标准舵 1 节 / 急舵 2 节（不随尺寸等级）。
     */
    fun turnLossKnots(u: Unit, emergency: Boolean): Double {
        if (isSubmerged(u)) return if (emergency) 2.0 else 1.0
        val lv = SizeLevels.of(u)
        return if (emergency) lv.lossEmer else lv.loss
    }

    /** 单次 45° 转向前冲距离（文件单位） */
    fun advanceYards(u: Unit, emergency: Boolean): Double {
        if (isSubmerged(u)) return if (emergency) 200.0 else 300.0
        val lv = SizeLevels.of(u)
        return if (emergency) lv.advEmer else lv.adv
    }

    fun isSubmerged(u: Unit): Boolean = (u.depth ?: 0) > 0

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

/** 尺寸等级能力表（对应 SIZE_LEVELS） */
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
        return TABLE[levelName(unit.unitClass, null)] ?: TABLE["B"]!!
    }
}
