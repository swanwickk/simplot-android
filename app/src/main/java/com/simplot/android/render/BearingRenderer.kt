package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.simplot.android.data.model.Unit

/**
 * 被动方位线渲染器（R7，桌面版 PassiveBearings.Draw）。
 *
 * 从单位中心沿 Bearing（罗盘角 0=北 顺时针）画半透明方位线，
 * 线长与 BeamLength 成比例（无则默认 40 海里屏显长度）。
 *
 * G45（批次3）：BeamWidth（波束宽度，度）参与绘制——beamWidth>0 时补两条边界线
 * （bearing ± beamWidth/2，更淡），桌面版 R-P3.8 修复；[beamEdgeBearings] 纯函数可单测。
 */
object BearingRenderer {

    /**
     * G45：波束宽度边界方位角（纯函数可单测）：中心方位 ± beamWidth/2。
     * beamWidth<=0 时两条边界与中心重合（调用方据此判断不画边线）。
     */
    fun beamEdgeBearings(bearing: Double, beamWidth: Double): Pair<Double, Double> {
        val half = beamWidth.coerceAtLeast(0.0) / 2.0
        return (bearing - half) to (bearing + half)
    }

    /**
     * 被动方位线（桌面版 PassiveBearings.Draw）。
     * R4：受 ShowSonar（Type="Sonar"）与 ShowEs（Type="ES" 等）开关控制。
     * 声呐线深蓝、ES 线琥珀（桌面版两类区分）。
     * G45：beamWidth>0 时画两条更淡的边界线（波束张角）。
     */
    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
             showSonar: Boolean = true, showEs: Boolean = true) {
        val bearings = u.passiveBearingArray ?: return
        if (bearings.isEmpty()) return
        val (cx, cy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)

        for (b in bearings) {
            val isSonar = b.type.equals("Sonar", true) || b.type.isBlank()
            if (isSonar && !showSonar) continue
            if (!isSonar && !showEs) continue
            val baseColor = if (isSonar) Color.argb(140, 40, 120, 220) else Color.argb(140, 200, 160, 40)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = baseColor
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            val rad = Math.toRadians(b.bearing)
            // 屏显长度：BeamLength(海里)×zoom；0 时默认 80px
            val lenPx = if (b.beamLength > 0) (b.beamLength * 100000.0 * camera.zoom).toFloat() else 80f
            val ex = cx + lenPx * Math.sin(rad).toFloat()
            val ey = cy - lenPx * Math.cos(rad).toFloat()
            canvas.drawLine(cx, cy, ex, ey, paint)

            // G45：波束宽度 >0 → 两条边界线（更淡、更细）
            if (b.beamWidth > 0.0) {
                val (lo, hi) = beamEdgeBearings(b.bearing, b.beamWidth)
                val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = baseColor
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                    alpha = 90
                }
                for (edge in listOf(lo, hi)) {
                    val er = Math.toRadians(edge)
                    val eeX = cx + lenPx * Math.sin(er).toFloat()
                    val eeY = cy - lenPx * Math.cos(er).toFloat()
                    canvas.drawLine(cx, cy, eeX, eeY, edgePaint)
                }
            }
        }
    }
}
