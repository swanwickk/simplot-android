package com.simplot.android.domain.engine

import com.simplot.android.data.util.CoordUtil

/**
 * 计算引擎（文档 §4.3 CalcEngine，对应桌面版 Game.CalcBearing / CalcRange / CalcMoveVector）。
 *
 * 全部为纯 Kotlin 顶层函数（无 Android 依赖）→ JVM 可直接单测。
 * 公式与桌面版反汇编确认一致：
 * - 方位角：ATan2(dx, dy) → 度 → 负数+360
 * - 距离：Sqrt(dx² + dy²)
 * - 新位置：ref + 距离 × (Sin 方位, Cos 方位)
 * - 到达时间：当前时间 + 距离/速度
 *
 * 坐标单位约定（与存档一致）：X/Y 文件坐标（海里 × 100000，Y 北为正）。
 */
object CalcEngine {

    /** 罗盘方位角（0=北，顺时针）从 (x1,y1) 到 (x2,y2)，单位度 */
    fun bearing(x1: Long, y1: Long, x2: Long, y2: Long): Double =
        CoordUtil.bearingDeg(x1, y1, x2, y2)

    /** 两点距离，单位海里 */
    fun rangeNm(x1: Long, y1: Long, x2: Long, y2: Long): Double =
        CoordUtil.distanceNm(x1, y1, x2, y2)

    /**
     * 新位置计算（桌面版 ContainerNewPosition.PushCalcPosition）：
     * 参考点 + 方位角(度, 0=北) + 距离(海里) → 新坐标(文件单位)。
     */
    fun newPosition(refX: Long, refY: Long, bearingDeg: Double, distNm: Double): Pair<Long, Long> {
        val (dx, dy) = CoordUtil.offsetNm(bearingDeg, distNm)
        return (refX + dx) to (refY + dy)
    }

    /**
     * 到达时间计算（桌面版 CalcArriveTime）：
     * 当前时间 + 距离/速度（小时）。
     * @param currentTime 存档时间格式 "yyyy-MM-dd HH:mm:ss"
     * @param distanceNm  距离（海里）
     * @param speedKnots  航速（节），0 无法计算返回 null
     * @return 到达时间字符串；速度<=0 返回 null
     */
    fun arriveTime(currentTime: String, distanceNm: Double, speedKnots: Double): String? {
        if (speedKnots <= 0) return null
        val hours = distanceNm / speedKnots
        val minutes = (hours * 60).toLong()
        return try {
            val dt = com.simplot.android.data.util.TimeUtil.parse(currentTime)
            dt.plusMinutes(minutes).format(com.simplot.android.data.util.TimeUtil.FORMAT)
        } catch (e: Exception) {
            null
        }
    }

    /** 航速(节) × 时长(分钟) → 移动距离（海里） */
    fun distanceInMinutes(speedKnots: Double, minutes: Double): Double =
        CoordUtil.distNm(speedKnots, minutes)
}
