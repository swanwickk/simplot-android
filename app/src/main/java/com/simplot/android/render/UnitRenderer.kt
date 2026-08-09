package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.simplot.android.data.model.Unit

/**
 * 单位军标渲染器：按阵营着色，支持水面/潜艇/飞机/岸上四种基础符号。
 *
 * 符号风格（桌面版 SymbolGenerator，玩家设置选择）：
 * - NTDS（默认）：描边符号（水面圆+船头线、飞机三角、潜艇椭圆、岸上方形）
 * - CWS：填充符号（水面实心圆点、飞机实心三角、潜艇实心椭圆、岸上实心方块）
 *   —— 对齐桌面版 CwsSymbols color_filled 变体语义
 */
object UnitRenderer {

    enum class SymbolStyle { NTDS, CWS }

    private val sideColors = mapOf(
        // 与 Color.rgb(r,g,b) 逐字节一致（0xFF<<24 | r<<16 | g<<8 | b），
        // 内联为纯 Kotlin 常量以便 JVM 单测直接断言色值（android.graphics.Color 在单测中不可用）
        "Blue" to 0xFF005AC8.toInt(),      // Color.rgb(0, 90, 200)
        "Red" to 0xFFC81E1E.toInt(),       // Color.rgb(200, 30, 30)
        "Neutral" to 0xFF787878.toInt(),   // Color.rgb(120, 120, 120)
        "Unknown" to 0xFF5A5A5A.toInt()    // Color.rgb(90, 90, 90)
    )

    fun colorOf(side: String): Int = sideColors[side] ?: 0xFF5A5A5A.toInt()

    /** 标签基准缩放：默认视野（Camera 初始 zoom）下的“1 倍”参考（反馈⑥） */
    const val LABEL_BASE_ZOOM = 0.0015f

    /** 标签字号（反馈⑥/契约6）：默认 24f，随 zoom 等比缩放，clamp [18f, 48f] 保证可读且不过大（最小 18f > 按钮文字 14sp） */
    fun labelTextSize(zoom: Float): Float = (24f * (zoom / LABEL_BASE_ZOOM)).coerceIn(18f, 48f)

    /** 标签锚点偏移系数（反馈⑥）：zoom/LABEL_BASE_ZOOM，clamp [0.7f, 2.5f]（偏移规则本身不变） */
    fun labelScaleK(zoom: Float): Float = (zoom / LABEL_BASE_ZOOM).coerceIn(0.7f, 2.5f)

    fun draw(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float = 16f, selected: Boolean = false, symbolStyle: SymbolStyle = SymbolStyle.NTDS) {
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
        val cws = symbolStyle == SymbolStyle.CWS
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
                if (cws) canvas.drawPath(path, fill)   // CWS：填充
            }
            u.isSubmarine() -> {
                // 潜艇：横椭圆 + 中线
                canvas.drawOval(sx - r * 1.3f, sy - r * 0.7f, sx + r * 1.3f, sy + r * 0.7f, stroke)
                canvas.drawLine(sx - r * 1.3f, sy, sx + r * 1.3f, sy, stroke)
                if (cws) canvas.drawOval(sx - r * 1.3f, sy - r * 0.7f, sx + r * 1.3f, sy + r * 0.7f, fill)
            }
            u.unitType.equals("Airfield", true) || u.idNum.startsWith("L") -> {
                // 岸上设施：方块
                canvas.drawRect(sx - r, sy - r, sx + r, sy + r, stroke)
                if (cws) canvas.drawRect(sx - r, sy - r, sx + r, sy + r, fill)
            }
            else -> {
                // 水面舰艇：圆（北向船头线）；CWS 为实心圆点
                canvas.drawCircle(sx, sy, r, stroke)
                canvas.drawLine(sx, sy - r, sx, sy + r * 0.6f, stroke)
                if (cws) {
                    canvas.drawCircle(sx, sy, r, fill)
                } else {
                    canvas.drawCircle(sx, sy, r * 0.35f, fill)
                }
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

        // 主动传感器激活标记（桌面版 ActiveSensors.Draw）：雷达=黄色三角（右上），声纳=蓝色菱形（左上）
        if (u.isActiveRadar) {
            val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(240, 200, 0)
                style = Paint.Style.FILL
            }
            val tri = Path().apply {
                moveTo(sx + r + 2f, sy - r - 6f)
                lineTo(sx + r + 8f, sy - r - 10f)
                lineTo(sx + r + 10f, sy - r - 3f)
                close()
            }
            canvas.drawPath(tri, rp)
        }
        if (u.isActiveSonar) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(40, 140, 220)
                style = Paint.Style.FILL
            }
            val dia = Path().apply {
                moveTo(sx - r - 8f, sy - r - 8f)
                lineTo(sx - r - 3f, sy - r - 11f)
                lineTo(sx - r + 2f, sy - r - 8f)
                lineTo(sx - r - 3f, sy - r - 5f)
                close()
            }
            canvas.drawPath(dia, sp)
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
