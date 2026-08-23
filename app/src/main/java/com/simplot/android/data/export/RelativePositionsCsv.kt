package com.simplot.android.data.export

import com.simplot.android.data.model.Unit
import com.simplot.android.data.util.CoordUtil
import java.util.Locale

/**
 * 相对位置 CSV 导出（桌面版 ExportData.RelativeUnitPositions.Export 格式）。
 *
 * 表头与桌面版逐字节一致：
 *   TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI,Range Yards,Range Meters
 * 每行 = 一个单位相对参考单位（选中单位，无则第一个）的方位/距离，
 * 距离同时输出 海里/码/米 三列（桌面版约定）。
 *
 * 数字格式沿用安卓端既有实现（%.1f / %.2f，审查 R-P3.4 已记录为有意偏离）。
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object RelativePositionsCsv {

    /** 表头（与桌面版 ExportData.RelativeUnitPositions.Export 字符串一致） */
    const val HEADER = "TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI,Range Yards,Range Meters"

    /** G57：月份缩写（桌面 GetDate_s_ 反汇编确认 JAN..DEC） */
    private val MONTHS = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /**
     * G57：CSV 导出文件名（桌面版约定 `<前缀>_<日期>_<时间>.csv`）。
     * 日期 = `<年>-<MON>-<日>`、时间 = `<时>-<分>`（'-' 分隔，桌面 GetDate_s_/GetTime_s_ 反汇编确认；
     * 年月日时分补零对齐桌面 '0' 占位格式化）。
     * 例：csvFileName("TN", 2026-08-12T10:30) = "TN_2026-AUG-12_10-30.csv"。
     * 纯函数 → JVM 单测。
     */
    fun csvFileName(prefix: String, now: java.time.LocalDateTime): String {
        val mon = MONTHS[now.monthValue - 1]
        val date = "${now.year}-$mon-${"%02d".format(now.dayOfMonth)}"
        val time = "${"%02d".format(now.hour)}-${"%02d".format(now.minute)}"
        return "${prefix}_${date}_${time}.csv"
    }

    /** 参考单位：选中单位，无（或已删除）则取第一个 */
    fun resolveReference(units: List<Unit>, selectedUnitId: String?): Unit =
        units.firstOrNull { it.idNum == selectedUnitId } ?: units.first()

    /**
     * 生成相对位置 CSV 文本（含表头；参考单位自身不出现在行数据中）。
     *
     * @param units 场景全部单位（非空；空返回空串）
     * @param selectedUnitId 当前选中单位 IdNum（参考单位优先，null/无效则取第一个）
     */
    fun build(units: List<Unit>, selectedUnitId: String?): String {
        if (units.isEmpty()) return ""
        val ref = resolveReference(units, selectedUnitId)
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        units.forEach { u ->
            if (u.idNum == ref.idNum) return@forEach
            val bearing = CoordUtil.bearingDeg(ref.x, ref.y, u.x, u.y)
            val distNm = CoordUtil.distanceNm(ref.x, ref.y, u.x, u.y)
            val distYards = distNm * CoordUtil.YARDS_PER_NMI
            val distMeters = distNm * 1852.0
            val altDepth = u.altitudeMeters() ?: u.depthMeters() ?: 0
            sb.append("${u.trackNumber},")
            sb.append("${u.x},${u.y},")
            // #15 修复：显式 Locale.US，避免其他 locale 十进制分隔符差异导致 CSV 数值列异常
            sb.append("${String.format(Locale.US, "%.0f", u.courseDeg())},${String.format(Locale.US, "%.0f", u.speedKnots())},$altDepth,")
            sb.append("${String.format(Locale.US, "%.1f", bearing)},${String.format(Locale.US, "%.2f", distNm)},")
            sb.append("${String.format(Locale.US, "%.1f", distYards)},${String.format(Locale.US, "%.1f", distMeters)}\n")
        }
        return sb.toString()
    }
}
