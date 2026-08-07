package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.simplot.android.data.model.Unit

/**
 * 轨迹/航路点渲染器：绘制 PastWaypointArray1 历史轨迹线
 *
 * 轨迹点格式：["", X, Y, 0,0, 高度/深度, 0,0,0, 1, true, "时间"]
 * 索引 1=X, 2=Y
 */
object TrackRenderer {

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int) {
        val past = u.pastWaypointArray1 as? List<*> ?: return
        if (past.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UnitRenderer.colorOf(u.side)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 170
        }

        var prev: Pair<Float, Float>? = null
        for (wp in past) {
            if (wp is List<*>) {
                val x = wp.getOrNull(1) as? Number ?: continue
                val y = wp.getOrNull(2) as? Number ?: continue
                val (sx, sy) = camera.worldToScreen(x.toLong(), y.toLong(), canvasW, canvasH)
                if (prev != null) {
                    canvas.drawLine(prev.first, prev.second, sx, sy, paint)
                }
                prev = sx to sy
            }
        }
        // 连到当前位置
        if (prev != null) {
            val (sx, sy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)
            canvas.drawLine(prev.first, prev.second, sx, sy, paint)
        }
    }
}
