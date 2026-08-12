package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.simplot.android.data.model.Sensor
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Weapon

/**
 * G23 弧固定排序（桌面 ContainerSensors/ContainerWeapons 的列表顺序即绘制顺序；
 * 安卓弧编辑器暂无上移/下移 UI，此处用确定性键序保证绘制顺序跨会话稳定）：
 * startAngle 升序 → maxRange 升序（同角小半径先画，重叠时大弧盖小弧）。
 * 返回新列表，不改原数组；顶层纯函数 → 可 JVM 单测。
 */
fun <T> sortedArcs(arcs: List<T>, startAngleOf: (T) -> Double, maxRangeOf: (T) -> Double): List<T> =
    arcs.sortedWith(compareBy(startAngleOf, maxRangeOf))

/** G23 便捷入口：传感器弧排序（null 视为空列表） */
fun sortedSensorArcs(arcs: List<Sensor>?): List<Sensor> =
    sortedArcs(arcs.orEmpty(), { it.startAngle }, { it.maxRange })

/** G23 便捷入口：武器弧排序（null 视为空列表） */
fun sortedWeaponArcs(arcs: List<Weapon>?): List<Weapon> =
    sortedArcs(arcs.orEmpty(), { it.startAngle }, { it.maxRange })

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

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
             showSensors: Boolean = true, showWeapons: Boolean = true) {
        val (cx, cy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)
        val headingRad = Math.toRadians(u.courseDeg())

        // 传感器弧（G23：按固定顺序绘制——startAngle 升序 → maxRange 升序）
        if (showSensors) {
            sortedSensorArcs(u.sensorArray).forEach { s ->
                if (!s.isVisible) return@forEach
                drawArc(canvas, cx, cy, s.minRange, s.maxRange, s.startAngle, s.arcAngle, headingRad, s.isFilled, s.arcColor, camera)
            }
        }
        // 武器弧（G23：同传感器固定顺序）
        if (showWeapons) {
            sortedWeaponArcs(u.weaponArray).forEach { w ->
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

        val style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (filled) Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                         else Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            this.style = style
            strokeWidth = 2f
        }

        // 弧：从 (航向+StartAngle) 顺时针扫 ArcAngle 度
        // Android drawArc: startAngle 0=3点钟方向，顺时针为正；罗盘 0=北（画布上方），需 -90 偏移
        val startDeg = Math.toDegrees(headingRad).toFloat() - 90f + startAngle.toFloat()
        val sweep = arcAngle.toFloat()

        if (filled) {
            // R2 修复：MinRange>0 时画 min~max 双半径环带（桌面 DrawSensorArc 逐点双半径路径）；
            // 用 even-odd 填充：外弧 + 内弧反向构成环带，不再用白挖洞
            if (radiusMin > 0f) {
                val path = android.graphics.Path().apply {
                    val outer = android.graphics.RectF(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
                    addArc(outer, startDeg, sweep)
                    val inner = android.graphics.RectF(cx - radiusMin, cy - radiusMin, cx + radiusMin, cy + radiusMin)
                    addArc(inner, startDeg + sweep, -sweep)
                    close()
                }
                canvas.drawPath(path, paint)
            } else {
                val rect = android.graphics.RectF(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
                canvas.drawArc(rect, startDeg, sweep, true, paint)
            }
        } else {
            // 未填充：只描外弧线（useCenter=false，避免画出到圆心的两条半径线）
            val rect = android.graphics.RectF(cx - radiusMax, cy - radiusMax, cx + radiusMax, cy + radiusMax)
            canvas.drawArc(rect, startDeg, sweep, false, paint)
        }
    }
}
