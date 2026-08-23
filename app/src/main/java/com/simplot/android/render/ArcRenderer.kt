package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.simplot.android.data.model.Unit

/**
 * G23 弧顺序（桌面 ContainerSensors/ContainerWeapons 语义：**列表顺序即绘制顺序**）。
 *
 * v0.6.1 修复：删除 v0.6.0 的「绘制期 startAngle 排序」——那与桌面"用户列表顺序"语义偏离；
 * 现由 ArcEditorDialog 提供 ↑/↓ 上移/下移重排（moveItem），渲染按数组原序直绘。
 */

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
 *
 * P3-1 修复（G68 补齐）：画笔/Path/RectF 复用为实例字段（by lazy 惰性初始化，
 * JVM 单测加载类不触发 android.jar stub 的 ExceptionInInitializerError，
 * 与 UnitRenderer/MapRenderer 同策略）；每帧每弧不再 new Paint/Path/RectF。
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

    // ---- P3-1：复用画笔/路径（主线程串行绘制，字段复用无并发冲突） ----
    private val arcPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f } }
    private val ringPath by lazy { Path() }
    private val outerRect by lazy { RectF() }
    private val innerRect by lazy { RectF() }

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
             showSensors: Boolean = true, showWeapons: Boolean = true) {
        val (cx, cy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)
        val headingRad = Math.toRadians(u.courseDeg())

        // 传感器弧（G23：按桌面列表原序绘制——编辑器可 ↑/↓ 重排）
        if (showSensors) {
            u.sensorArray.orEmpty().forEach { s ->
                if (!s.isVisible) return@forEach
                drawArc(canvas, cx, cy, s.minRange, s.maxRange, s.startAngle, s.arcAngle, headingRad, s.isFilled, s.arcColor, camera)
            }
        }
        // 武器弧（G23：按桌面列表原序绘制）
        if (showWeapons) {
            u.weaponArray.orEmpty().forEach { w ->
                if (!w.isVisible) return@forEach
                drawArc(canvas, cx, cy, w.minRange, w.maxRange, w.startAngle, w.arcAngle, headingRad, w.isFilled, w.arcColor, camera)
            }
        }
    }

    private fun drawArc(
        canvas: Canvas, cx: Float, cy: Float,
        minRangeNm: Double, maxRangeNm: Double,
        startAngle: Double, arcAngle: Double,
        headingRad: Double, filled: Boolean, vbColor: String?, camera: Camera
    ) {
        if (maxRangeNm <= 0) return
        // R1 修复：ArcAngle=0 是 0 度退化弧（桌面语义，整圆 = ArcAngle 360），不绘制
        if (arcAngle <= 0.0) return
        val color = parseColor(vbColor)
        val radiusMax = (maxRangeNm * 100000.0 * camera.zoom).toFloat()
        val radiusMin = (minRangeNm * 100000.0 * camera.zoom).toFloat()

        // P3-1：复用画笔，按需改色/样式
        val paint = arcPaint.apply {
            this.color = if (filled) Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                         else Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            this.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        }

        // 弧：从 (航向+StartAngle) 顺时针扫 ArcAngle 度
        // Android drawArc: startAngle 0=3点钟方向，顺时针为正；罗盘 0=北（画布上方），需 -90 偏移
        val startDeg = Math.toDegrees(headingRad).toFloat() - 90f + startAngle.toFloat()
        val sweep = arcAngle.toFloat()

        if (filled) {
            // R2 修复：MinRange>0 时画 min~max 双半径环带（桌面 DrawSensorArc 逐点双半径路径）；
            // 用 even-odd 填充：外弧 + 内弧反向构成环带，不再用白挖洞
            if (radiusMin > 0f) {
                // P3-1：复用 Path/RectF，先 reset 再构建
                val path = ringPath
                path.reset()
                val outer = outerRect
                outer.set(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
                path.addArc(outer, startDeg, sweep)
                val inner = innerRect
                inner.set(cx - radiusMin, cy - radiusMin, cx + radiusMin, cy + radiusMin)
                path.addArc(inner, startDeg + sweep, -sweep)
                path.close()
                canvas.drawPath(path, paint)
            } else {
                val rect = outerRect
                rect.set(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
                canvas.drawArc(rect, startDeg, sweep, true, paint)
            }
        } else {
            // 未填充：只描外弧线（useCenter=false，避免画出到圆心的两条半径线）
            val rect = outerRect
            rect.set(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
            canvas.drawArc(rect, startDeg, sweep, false, paint)
        }
    }
}
