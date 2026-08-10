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
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOf(a.colorName)
            textSize = (a.fontSize * 0.8f).toFloat().coerceIn(8f, 40f)
            isFakeBoldText = a.isBold
            isUnderlineText = a.isItalic
            textAlign = Paint.Align.CENTER
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOf(a.colorName)
            alpha = alpha(a.transparency)
            style = if (a.isFilled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 2f
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOf(a.colorName)
            alpha = alpha(a.transparency)
            style = if (a.isFilled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 2f
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOf(a.colorName)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val (sx0, sy0) = camera.worldToScreen(a.path[0].first, a.path[0].second, w, h)
        val path = Path().apply { moveTo(sx0, sy0) }
        for ((i, pt) in a.path.withIndex()) {
            if (i == 0) continue
            val (sx, sy) = camera.worldToScreen(pt.first, pt.second, w, h)
            path.lineTo(sx, sy)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPolygon(canvas: Canvas, a: MiscAnnotation.Polygon, camera: Camera, w: Int, h: Int) {
        if (a.path.size < 3) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOf(a.colorName)
            alpha = alpha(a.transparency)
            style = if (a.isFilled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 2f
        }
        val (sx0, sy0) = camera.worldToScreen(a.path[0].first, a.path[0].second, w, h)
        val path = Path().apply { moveTo(sx0, sy0) }
        for ((i, pt) in a.path.withIndex()) {
            if (i == 0) continue
            val (sx, sy) = camera.worldToScreen(pt.first, pt.second, w, h)
            path.lineTo(sx, sy)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
