package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Paint
import com.simplot.android.data.model.Unit

/**
 * 轨迹/航路点渲染器：
 * - PastWaypointArray 历史轨迹线（桌面 TrackHistory）
 * - R-P2 修复：FutureWaypointArray 未来航路点标记（空心圆 + 序号，颜色按阵营，桌面 ShowWaypoints）
 *
 * G09：阵营色走 [UnitRenderer.Palette]（PlayerSettings 蓝/红颜色键驱动），去硬编码。
 */
object TrackRenderer {

    /** #9：复用画笔（历史轨迹线；使用点改阵营色）——G68 惰性初始化保持 JVM 可测 */
    private val pastLinePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; alpha = 170 }
    }

    /** #9：复用画笔（历史轨迹圆点） */
    private val pastDotPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; alpha = 200 }
    }

    /** #9：复用画笔（未来航路点空心圆环） */
    private val futureRingPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    }

    /** #9：复用画笔（未来航路点序号） */
    private val futureNumPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }

    /** N1/D8：Neutral/All 白系前景在白底上描深色边保证可见（适配浅色底图） */
    private val outlinePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
    }

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
             palette: UnitRenderer.Palette = UnitRenderer.Palette()) {
        drawPast(canvas, u, camera, canvasW, canvasH, palette)
        drawFuture(canvas, u, camera, canvasW, canvasH, palette)
    }

    private fun drawPast(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
                         palette: UnitRenderer.Palette) {
        val past = u.pastWaypointArray
        if (past.isEmpty()) return

        // #9：复用池画笔，按阵营色覆盖
        val linePaint = pastLinePaint.apply { color = UnitRenderer.colorOf(u.side, palette) }
        val dotPaint = pastDotPaint.apply { color = UnitRenderer.colorOf(u.side, palette) }
        // N1：Neutral/All 历史轨迹圆点亦描深色边（与未来环一致）

        var prev: Pair<Float, Float>? = null
        for (wp in past) {
            val (sx, sy) = camera.worldToScreen(wp.x, wp.y, canvasW, canvasH)
            // 轨迹点小圆点（历史位置标记，桌面版 TrackHistory）
            canvas.drawCircle(sx, sy, 3f, dotPaint)
            if (UnitRenderer.needsOutline(u.side)) canvas.drawCircle(sx, sy, 3f, outlinePaint)
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
    private fun drawFuture(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
                           palette: UnitRenderer.Palette) {
        val future = u.futureWaypointArray
        if (future.isEmpty()) return

        val color = UnitRenderer.colorOf(u.side, palette)
        // #9：复用池画笔，按阵营色覆盖
        val ring = futureRingPaint.apply { this.color = color }
        val num = futureNumPaint.apply { this.color = color }
        future.forEachIndexed { i, wp ->
            val (sx, sy) = camera.worldToScreen(wp.x, wp.y, canvasW, canvasH)
            if (sx in -80f..canvasW + 80f && sy in -80f..canvasH + 80f) {
                canvas.drawCircle(sx, sy, 6f, ring)
                if (UnitRenderer.needsOutline(u.side)) canvas.drawCircle(sx, sy, 6f, outlinePaint)
                canvas.drawText((i + 1).toString(), sx, sy + 4f, num)
            }
        }
    }
}
