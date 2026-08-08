package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.simplot.android.data.model.Unit

/**
 * 单位军标渲染器：按阵营着色，支持水面/潜艇/飞机/岸上四种基础符号（NTDS 风格简化版）
 */
object UnitRenderer {

    private val sideColors = mapOf(
        "Blue" to Color.rgb(0, 90, 200),
        "Red" to Color.rgb(200, 30, 30),
        "Neutral" to Color.rgb(120, 120, 120),
        "Unknown" to Color.rgb(90, 90, 90)
    )

    fun colorOf(side: String): Int = sideColors[side] ?: Color.rgb(90, 90, 90)

    fun draw(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float = 16f, selected: Boolean = false) {
        // 速度领导线（桌面版 SpeedLeaders.Draw）：沿航向向前，长度与航速成比例
        if (u.speedKnots() > 0) {
            val leaderLen = (u.speedKnots() * 2.2).coerceAtLeast(10.0).coerceAtMost(90.0).toFloat()
            val hdgRad = Math.toRadians(u.courseDeg())
            val lx = sx + (leaderLen * kotlin.math.sin(hdgRad)).toFloat()
            val ly = sy - (leaderLen * kotlin.math.cos(hdgRad)).toFloat()
            val leader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = colorOf(u.side)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                alpha = 180
            }
            canvas.drawLine(sx, sy, lx, ly, leader)
        }
        val sideColor = colorOf(u.side)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = sideColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = sideColor
            style = Paint.Style.FILL
        }

        val r = sizePx / 2
        when {
            u.isAircraft() -> {
                // 飞机：三角翼符号
                val path = Path().apply {
                    moveTo(sx, sy - r)
                    lineTo(sx - r * 1.1f, sy + r * 0.8f)
                    lineTo(sx + r * 1.1f, sy + r * 0.8f)
                    close()
                }
                canvas.drawPath(path, stroke)
            }
            u.isSubmarine() -> {
                // 潜艇：横椭圆 + 中线
                canvas.drawOval(sx - r * 1.3f, sy - r * 0.7f, sx + r * 1.3f, sy + r * 0.7f, stroke)
                canvas.drawLine(sx - r * 1.3f, sy, sx + r * 1.3f, sy, stroke)
            }
            u.unitType.equals("Airfield", true) || u.idNum.startsWith("L") -> {
                // 岸上设施：方块
                canvas.drawRect(sx - r, sy - r, sx + r, sy + r, stroke)
            }
            else -> {
                // 水面舰艇：圆（北向船头线）
                canvas.drawCircle(sx, sy, r, stroke)
                canvas.drawLine(sx, sy - r, sx, sy + r * 0.6f, stroke)
                canvas.drawCircle(sx, sy, r * 0.35f, fill)
            }
        }

        // 选中高亮
        if (selected) {
            val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(255, 180, 0)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawCircle(sx, sy, r + 5f, sel)
        }

        // 沉没标记：叉
        if (u.showSunk) {
            val sunk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawLine(sx - r, sy - r, sx + r, sy + r, sunk)
            canvas.drawLine(sx + r, sy - r, sx - r, sy + r, sunk)
        }
    }
}
