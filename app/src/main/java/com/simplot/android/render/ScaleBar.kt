package com.simplot.android.render

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * G17：比例尺动态数值计算（桌面版 ContainerScalebar 动态数值）。
 *
 * 世界坐标 1 海里 = 100000 文件单位（CoordUtil.NMI_SCALE）；相机 zoom = 世界单位 → 像素比例，
 * 因此 1 海里的屏幕长度 = zoom × 100000 像素。按 1-2-5 序列取整出"整值"海里数，
 * 使比例尺像素长度落在 [maxPx×0.4, maxPx] 区间，数值随 zoom 动态变化（缩放越大显示越小范围）。
 *
 * 纯 Kotlin 无 Android 依赖 → JVM 单测（与 CameraMath 同模式）。
 */
object ScaleBar {

    /** 1 海里 = 100000 世界坐标单位（与 CoordUtil.NMI_SCALE 一致） */
    const val NMI_SCALE = 100000.0

    /** 缩放级别下 1 海里对应的屏幕像素数 */
    fun pxPerNmi(zoom: Float): Float = (zoom * NMI_SCALE).toFloat()

    /**
     * 计算比例尺显示值（海里）与对应像素长度。
     * @param zoom 相机缩放（世界单位 → 像素）
     * @param maxPx 比例尺允许的最大像素宽度
     * @return (整值海里数, 实际像素长度)；zoom<=0 退化返回 (50.0, maxPx)
     */
    fun compute(zoom: Float, maxPx: Float = 100f): Pair<Double, Float> {
        val per = pxPerNmi(zoom)
        if (per <= 0f) return 50.0 to maxPx
        val nmi = (maxPx / per).toDouble()
        val nice = niceNumber(nmi)
        return nice to (nice * per).toFloat()
    }

    /**
     * 1-2-5 序列取整：返回不大于 value 的最大 1/2/5×10^k 整值（k 可为负，支持亚海里）。
     * 例：0.37→0.2、1.9→1、4.2→2、9.9→5、37→20、100→100。
     * 因下一个档位是 2 倍（2→5 为 2.5 倍），故 nice ≥ value/2.5 → 像素长度 ≥ maxPx×0.4。
     */
    fun niceNumber(value: Double): Double {
        require(value > 0) { "比例尺计算值必须为正：$value" }
        val exp = floor(log10(value))
        val base = 10.0.pow(exp)
        val frac = value / base          // [1, 10)
        val mult = when {
            frac < 2.0 -> 1.0
            frac < 5.0 -> 2.0
            else -> 5.0
        }
        return mult * base
    }

    /** 显示文本（1-2-5 序列 ≥1 均为整数不带小数；亚海里去尾零）：50 nmi / 2 nmi / 0.5 nmi / 0.05 nmi */
    fun label(nmi: Double): String {
        // P3-3 修复（#15 覆盖不全）：显式 Locale.US，防德/法等 locale 输出逗号小数点
        val num = if (nmi >= 1) String.format(java.util.Locale.US, "%.0f", nmi)
        else String.format(java.util.Locale.US, "%.2f", nmi).trimEnd('0').trimEnd('.')
        return "$num nmi"
    }
}
