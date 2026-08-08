package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.simplot.android.data.model.Unit

/**
 * 轨迹/航路点渲染器：绘制 PastWaypointArray 历史轨迹线
 *
 * 轨迹点（桌面版对象结构）：{Name, X, Y, Speed, Course, AltitudeDepth, ...}
 */
object TrackRenderer {

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int) {
        val past = u.pastWaypointArray
        if (past.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UnitRenderer.colorOf(u.side)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 170
        }

        var prev: Pair<Float, Float>? = null
        for (wp in past) {
            val (sx, sy) = camera.worldToScreen(wp.x, wp.y, canvasW, canvasH)
            if (prev != null) {
                canvas.drawLine(prev.first, prev.second, sx, sy, paint)
            }
            prev = sx to sy
        }
        // 连到当前位置
        if (prev != null) {
            val (sx, sy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)
            canvas.drawLine(prev.first, prev.second, sx, sy, paint)
        }
    }
}
