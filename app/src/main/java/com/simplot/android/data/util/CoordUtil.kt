package com.simplot.android.data.util

import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 坐标与定点换算工具（与桌面版 scn_tool.py 一致）
 *
 * - 文件 X/Y = 实际值（海里）× 100000（整数定点）
 * - Speed/Course/Altitude/Depth = 实际值 × 1000
 * - 罗盘方位：0=北，顺时针；Y 轴向北为正
 * - 1 海里 = 2025.37 码
 */
object CoordUtil {
    const val NMI_SCALE = 100000L
    const val YARDS_PER_NMI = 2025.37

    /** 海里 → 文件坐标（×100000） */
    fun nmToFile(nmi: Double): Long = (nmi * NMI_SCALE).roundToLong()

    /** 文件坐标 → 海里 */
    fun fileToNm(coord: Long): Double = coord.toDouble() / NMI_SCALE

    /** 码 → 文件坐标 */
    fun yardsToFile(yards: Double): Long = (yards / YARDS_PER_NMI * NMI_SCALE).roundToLong()

    /** 罗盘方位(度) + 海里距离 → (dx, dy) 文件单位 */
    fun offsetNm(bearingDeg: Double, distNmi: Double): Pair<Long, Long> {
        val rad = Math.toRadians(bearingDeg)
        return nmToFile(distNmi * sin(rad)) to nmToFile(distNmi * cos(rad))
    }

    /** 罗盘方位(度) + 码 → (dx, dy) 文件单位 */
    fun offsetYards(bearingDeg: Double, yards: Double): Pair<Long, Long> {
        val d = yardsToFile(yards)
        val rad = Math.toRadians(bearingDeg)
        return (d * sin(rad)).roundToLong() to (d * cos(rad)).roundToLong()
    }

    /** 距离（海里）两点间 */
    fun distanceNm(x1: Long, y1: Long, x2: Long, y2: Long): Double {
        val dx = fileToNm(x2 - x1)
        val dy = fileToNm(y2 - y1)
        return Math.hypot(dx, dy)
    }

    /** 方位（罗盘角 0=北 顺时针）从 (x1,y1) 到 (x2,y2) */
    fun bearingDeg(x1: Long, y1: Long, x2: Long, y2: Long): Double {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        // 罗盘：0=北(正Y)，顺时针；dx 向东为正
        var deg = Math.toDegrees(Math.atan2(dx, dy))
        if (deg < 0) deg += 360.0
        return deg
    }

    /** 航速(节) × 时长(分钟) → 海里距离 */
    fun distNm(speedKnots: Double, minutes: Double): Double = speedKnots * minutes / 60.0
}
