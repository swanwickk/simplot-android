package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.simplot.android.data.model.Unit

/**
 * 轨迹/航路点渲染器：绘制 PastWaypointArray 历史轨迹线
 *
 * 轨迹点（桌面版对象结构）：{Name, X, Y, Speed, Course, AltitudeDepth, ...}
 * R6：补轨迹点小圆点（桌面版 TrackHistory.Draw 样式）
 */
object TrackRenderer {

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int) {
        val past = u.pastWaypointArray
        if (past.isEmpty()) return

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UnitRenderer.colorOf(u.side)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 170
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UnitRenderer.colorOf(u.side)
            style = Paint.Style.FILL
            alpha = 200
        }

        var prev: Pair<Float, Float>? = null
        for (wp in past) {
            val (sx, sy) = camera.worldToScreen(wp.x, wp.y, canvasW, canvasH)
            // 轨迹点小圆点（历史位置标记，桌面版 TrackHistory）
            canvas.drawCircle(sx, sy, 3f, dotPaint)
            if (prev != null) {
                canvas.drawLine(prev.first, prev.second, sx, sy, linePaint)
            }
            prev = sx to sy
        }
        // 连到当前位置
        if (prev != null) {
            val (sx, sy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)
            canvas.drawLine(prev.first, prev.second, sx, sy, linePaint)
        }
    }
}
