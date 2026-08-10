package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.simplot.android.data.model.Unit

/**
 * 轨迹/航路点渲染器：
 * - PastWaypointArray 历史轨迹线（桌面 TrackHistory）
 * - R-P2 修复：FutureWaypointArray 未来航路点标记（空心圆 + 序号，颜色按阵营，桌面 ShowWaypoints）
 */
object TrackRenderer {

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int) {
        drawPast(canvas, u, camera, canvasW, canvasH)
        drawFuture(canvas, u, camera, canvasW, canvasH)
    }

    private fun drawPast(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int) {
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

    /** 未来航路点：空心圆 + 序号（桌面版 Waypoint 标记，颜色 GetWaypointColor 阵营色） */
    private fun drawFuture(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int) {
        val future = u.futureWaypointArray
        if (future.isEmpty()) return

        val color = UnitRenderer.colorOf(u.side)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 11f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        future.forEachIndexed { i, wp ->
            val (sx, sy) = camera.worldToScreen(wp.x, wp.y, canvasW, canvasH)
            if (sx in -80f..canvasW + 80f && sy in -80f..canvasH + 80f) {
                canvas.drawCircle(sx, sy, 6f, ring)
                canvas.drawText((i + 1).toString(), sx, sy + 4f, num)
            }
        }
    }
}
