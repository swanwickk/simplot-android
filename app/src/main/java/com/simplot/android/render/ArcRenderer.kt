package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.simplot.android.data.model.Unit

/**
 * 传感器/武器射程弧渲染器（对应桌面版 Sensors / Weapons 显示）。
 *
 * 数据：Unit.SensorArray / Unit.WeaponArray，每项：
 * - MinRange / MaxRange：海里（双精度）
 * - StartAngle / ArcAngle：度，顺时针，0=单位航向
 * - ArcColor：VB 格式 "&h00RRGGBB"（ARGB，前 2 位=Alpha）
 * - IsFilled / IsVisible：填充 / 显示开关
 *
 * 渲染：以单位为中心画弧（扇形），0° 指向单位航向，顺时针。
 * 半径 = MaxRange（海里 → 文件单位 ×100000）。
 */
object ArcRenderer {

    /** VB 颜色 "&h00RRGGBB" → Android Color（不透明）；缺失时回退桌面版默认 黄色 0xFFFF00 */
    fun parseColor(vb: String?): Int {
        if (vb == null) return Color.rgb(255, 255, 0)
        val hex = vb.removePrefix("&h").removePrefix("&H")
        return try {
            val v = hex.toLong(16)
            val r = ((v shr 16) and 0xFF).toInt()
            val g = ((v shr 8) and 0xFF).toInt()
            val b = (v and 0xFF).toInt()
            Color.rgb(r, g, b)
        } catch (e: Exception) {
            Color.rgb(255, 255, 0)
        }
    }

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int) {
        val (cx, cy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)
        val headingRad = Math.toRadians(u.courseDeg())

        // 传感器弧
        u.sensorArray?.forEach { s ->
            if (!s.isVisible) return@forEach
            drawArc(canvas, cx, cy, s.minRange, s.maxRange, s.startAngle, s.arcAngle, headingRad, s.isFilled, s.arcColor, camera)
        }
        // 武器弧
        u.weaponArray?.forEach { w ->
            if (!w.isVisible) return@forEach
            drawArc(canvas, cx, cy, w.minRange, w.maxRange, w.startAngle, w.arcAngle, headingRad, w.isFilled, w.arcColor, camera)
        }
    }

    private fun drawArc(
        canvas: Canvas, cx: Float, cy: Float,
        minRangeNm: Double, maxRangeNm: Double,
        startAngle: Double, arcAngle: Double,
        headingRad: Double, filled: Boolean, vbColor: String?, camera: Camera
    ) {
        if (maxRangeNm <= 0) return
        val color = parseColor(vbColor)
        val radiusMax = (maxRangeNm * 100000.0 * camera.zoom).toFloat()
        val radiusMin = (minRangeNm * 100000.0 * camera.zoom).toFloat()

        val style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (filled) Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                         else Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            this.style = style
            strokeWidth = 2f
        }

        // 弧：从 (航向+StartAngle) 顺时针扫 ArcAngle 度
        // Android drawArc: startAngle 0=3点钟方向，顺时针为正；罗盘 0=北（画布上方），需 -90 偏移
        val sweep = arcAngle.toFloat()
        if (sweep == 0f) {
            // 整圆（ArcAngle=0 表示圆，桌面版约定）
            val rect = android.graphics.RectF(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
            canvas.drawOval(rect, paint)
            if (minRangeNm > 0) {
                val rectMin = android.graphics.RectF(cx - radiusMin, cy - radiusMin, cx + radiusMin, cy + radiusMin)
                canvas.drawOval(rectMin, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    this.style = Paint.Style.FILL
                })
                canvas.drawOval(rectMin, paint)
            }
        } else {
            val startDeg = Math.toDegrees(headingRad).toFloat() - 90f + startAngle.toFloat()
            val rect = android.graphics.RectF(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
            canvas.drawArc(rect, startDeg, sweep, true, paint)
        }
    }
}
