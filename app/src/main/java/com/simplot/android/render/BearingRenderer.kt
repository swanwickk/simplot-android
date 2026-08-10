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
 */
object BearingRenderer {

    /**
     * 被动方位线（桌面版 PassiveBearings.Draw）。
     * R4：受 ShowSonar（Type="Sonar"）与 ShowEs（Type="ES" 等）开关控制。
     * 声呐线深蓝、ES 线琥珀（桌面版两类区分）。
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
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isSonar) Color.argb(140, 40, 120, 220) else Color.argb(140, 200, 160, 40)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            val rad = Math.toRadians(b.bearing)
            // 屏显长度：BeamLength(海里)×zoom；0 时默认 80px
            val lenPx = if (b.beamLength > 0) (b.beamLength * 100000.0 * camera.zoom).toFloat() else 80f
            val ex = cx + lenPx * Math.sin(rad).toFloat()
            val ey = cy - lenPx * Math.cos(rad).toFloat()
            canvas.drawLine(cx, cy, ex, ey, paint)
        }
    }
}
