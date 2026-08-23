package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.simplot.android.domain.model.MiscAnnotation

/**
 * Misc 标注绘制器（R7，桌面版 MiscBox/Oval/Line/Polygon/Label 绘制）。
 *
 * 在单位层之下绘制（Overlay 语义）。颜色按 ColorName 映射（蓝/红/绿/黄/黑/白/灰），
 * 透明度按 Transparency（0-100 → alpha）。
 */
object MiscAnnotationRenderer {

    /** #9：复用画笔（标注文字；使用点改 color/textSize/粗细/下划线）——G68 惰性初始化保持 JVM 可测 */
    private val labelPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    }

    /** #9：复用画笔（框/椭圆/多边形，填充或描边；使用点改 color/alpha/style） */
    private val shapePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    }

    /** #9：复用画笔（线段；STROKE） */
    private val lineStrokePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    }

    /** P2：复用 Path（Misc 线/多边形每帧 new Path 轻 GC；单画布串行绘制，字段复用无冲突；by lazy 保持 JVM 可测） */
    private val reusablePath by lazy { Path() }

    fun draw(canvas: Canvas, annotations: List<MiscAnnotation>, camera: Camera, canvasW: Int, canvasH: Int) {
        for (a in annotations) {
            when (a) {
                is MiscAnnotation.Label -> drawLabel(canvas, a, camera, canvasW, canvasH)
                is MiscAnnotation.Box -> drawBox(canvas, a, camera, canvasW, canvasH)
                is MiscAnnotation.Oval -> drawOval(canvas, a, camera, canvasW, canvasH)
                is MiscAnnotation.Line -> drawLine(canvas, a, camera, canvasW, canvasH)
                is MiscAnnotation.Polygon -> drawPolygon(canvas, a, camera, canvasW, canvasH)
            }
        }
    }

    /** ColorName（桌面版颜色名）→ ARGB；未知色回退灰 */
    fun colorOf(name: String): Int = when (name.lowercase()) {
        "blue" -> Color.rgb(0, 90, 200)
        "red" -> Color.rgb(200, 30, 30)
        "green" -> Color.rgb(30, 150, 60)
        "yellow" -> Color.rgb(220, 180, 30)
        "black" -> Color.BLACK
        "white" -> Color.WHITE
        "gray", "grey" -> Color.GRAY
        else -> Color.rgb(100, 100, 100)
    }

    private fun alpha(transparency: Double): Int = ((1.0 - transparency / 100.0) * 255).toInt().coerceIn(0, 255)

    private fun drawLabel(canvas: Canvas, a: MiscAnnotation.Label, camera: Camera, w: Int, h: Int) {
        val (sx, sy) = camera.worldToScreen(a.x, a.y, w, h)
        // #9：复用池画笔，按标注属性覆盖
        val p = labelPaint.apply {
            color = colorOf(a.colorName)
            textSize = (a.fontSize * 0.8f).toFloat().coerceIn(8f, 40f)
            isFakeBoldText = a.isBold
            isUnderlineText = a.isItalic
        }
        // R-P2：Rotation 支持（桌面版 MiscLabel 含 Rotation 字段，绕锚点旋转）
        if (a.rotation != 0.0) {
            canvas.save()
            canvas.rotate(-a.rotation.toFloat(), sx, sy)
            canvas.drawText(a.text, sx, sy, p)
            canvas.restore()
        } else {
            canvas.drawText(a.text, sx, sy, p)
        }
    }

    private fun drawBox(canvas: Canvas, a: MiscAnnotation.Box, camera: Camera, w: Int, h: Int) {
        val (sx, sy) = camera.worldToScreen(a.x, a.y, w, h)
        val bw = (a.width * camera.zoom).toFloat()
        val bh = (a.height * camera.zoom).toFloat()
        // #9：复用池画笔，按标注属性覆盖
        val paint = shapePaint.apply {
            color = colorOf(a.colorName)
            alpha = alpha(a.transparency)
            style = if (a.isFilled) Paint.Style.FILL else Paint.Style.STROKE
        }
        // R-P2：Rotation 支持（桌面版 MiscBox 含 Rotation）
        if (a.rotation != 0.0) {
            canvas.save()
            canvas.rotate(-a.rotation.toFloat(), sx, sy)
            canvas.drawRect(sx - bw / 2, sy - bh / 2, sx + bw / 2, sy + bh / 2, paint)
            canvas.restore()
        } else {
            canvas.drawRect(sx - bw / 2, sy - bh / 2, sx + bw / 2, sy + bh / 2, paint)
        }
    }

    private fun drawOval(canvas: Canvas, a: MiscAnnotation.Oval, camera: Camera, w: Int, h: Int) {
        val (sx, sy) = camera.worldToScreen(a.x, a.y, w, h)
        val bw = (a.width * camera.zoom).toFloat()
        val bh = (a.height * camera.zoom).toFloat()
        // #9：复用池画笔，按标注属性覆盖
        val paint = shapePaint.apply {
            color = colorOf(a.colorName)
            alpha = alpha(a.transparency)
            style = if (a.isFilled) Paint.Style.FILL else Paint.Style.STROKE
        }
        // R-P2：Rotation 支持（桌面版 MiscOval 含 Rotation）
        if (a.rotation != 0.0) {
            canvas.save()
            canvas.rotate(-a.rotation.toFloat(), sx, sy)
            canvas.drawOval(sx - bw / 2, sy - bh / 2, sx + bw / 2, sy + bh / 2, paint)
            canvas.restore()
        } else {
            canvas.drawOval(sx - bw / 2, sy - bh / 2, sx + bw / 2, sy + bh / 2, paint)
        }
    }

    private fun drawLine(canvas: Canvas, a: MiscAnnotation.Line, camera: Camera, w: Int, h: Int) {
        if (a.path.size < 2) return
        // #9：复用池画笔，按标注颜色覆盖
        val paint = lineStrokePaint.apply { color = colorOf(a.colorName) }
        val (sx0, sy0) = camera.worldToScreen(a.path[0].first, a.path[0].second, w, h)
        // P2：复用 Path（单画布串行，先 reset 再构建）
        val path = reusablePath
        path.reset()
        path.moveTo(sx0, sy0)
        for ((i, pt) in a.path.withIndex()) {
            if (i == 0) continue
            val (sx, sy) = camera.worldToScreen(pt.first, pt.second, w, h)
            path.lineTo(sx, sy)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPolygon(canvas: Canvas, a: MiscAnnotation.Polygon, camera: Camera, w: Int, h: Int) {
        if (a.path.size < 3) return
        // #9：复用池画笔，按标注属性覆盖
        val paint = shapePaint.apply {
            color = colorOf(a.colorName)
            alpha = alpha(a.transparency)
            style = if (a.isFilled) Paint.Style.FILL else Paint.Style.STROKE
        }
        val (sx0, sy0) = camera.worldToScreen(a.path[0].first, a.path[0].second, w, h)
        // P2：复用 Path（单画布串行，先 reset 再构建）
        val path = reusablePath
        path.reset()
        path.moveTo(sx0, sy0)
        for ((i, pt) in a.path.withIndex()) {
            if (i == 0) continue
            val (sx, sy) = camera.worldToScreen(pt.first, pt.second, w, h)
            path.lineTo(sx, sy)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
