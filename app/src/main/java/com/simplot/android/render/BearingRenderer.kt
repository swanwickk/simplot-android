package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.simplot.android.data.model.Unit

/**
 * 被动方位线渲染器（R7，桌面版 PassiveBearings.Draw）。
 *
 * 从单位中心沿 Bearing（罗盘角 0=北 顺时针）画方位线，
 * 线长与 BeamLength 成比例（无则默认 40 海里屏显长度）。
 *
 * 桌面语义对齐（反编译 DrawBearings @0x1406ebd20 + CalcBearing @0x1406fbfb0 实测）：
 * - CalcBearing：Bearing = Floor(真实方位角(本站 → Emitter 目标))——目标移动后方位实时重算；
 *   安卓端复刻：Emitter 非空且能在场景中找到时，按目标当前位置算方位（[bearingOf] 纯函数）。
 * - BeamWidth>0：画波束扇形填充（半透明）+ 两条边界线（[beamEdgeBearings] ± width/2，常量 2.0 配合除法）。
 *
 * G45（批次3）：BeamWidth（波束宽度，度）参与绘制；[beamEdgeBearings] 纯函数可单测。
 * 反馈㉔：可见性强化——加粗线宽、提高 alpha、波束填充，深色底图上清晰可辨。
 */
object BearingRenderer {

    /** #9：复用画笔（主方位线；使用点改色）——G68 惰性初始化保持 JVM 可测 */
    private val linePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
    }

    /** 波束扇形填充画笔（半透明） */
    private val beamFillPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }

    /** #9：复用画笔（波束边界线，更淡更细） */
    private val edgePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; alpha = 120 }
    }

    /** 复用 Path（每帧 reset） */
    private val beamPath by lazy { Path() }

    /** ARGB 保留 RGB、覆盖 alpha（纯函数语义，内联辅助） */
    private fun Int.withAlpha(a: Int): Int = (a shl 24) or (this and 0x00FFFFFF)

    /**
     * G45：波束宽度边界方位角（纯函数可单测）：中心方位 ± beamWidth/2。
     * beamWidth<=0 时两条边界与中心重合（调用方据此判断不画边线）。
     */
    fun beamEdgeBearings(bearing: Double, beamWidth: Double): Pair<Double, Double> {
        val half = beamWidth.coerceAtLeast(0.0) / 2.0
        return (bearing - half) to (bearing + half)
    }

    /**
     * 桌面 CalcBearing 复刻（纯函数可单测）：本站 → 目标 的真实方位角（度，0=北顺时针），Floor 取整。
     * 与移动公式同系：ATan2(dx, dy)（注意桌面 Sin/Cos 系 x=dx, y=dy 北正）。
     * 返回 null 当两点重合（方位无定义）。
     */
    fun calcBearing(fromX: Long, fromY: Long, toX: Long, toY: Long): Double? {
        val dx = (toX - fromX).toDouble()
        val dy = (toY - fromY).toDouble()
        if (dx == 0.0 && dy == 0.0) return null
        var deg = Math.toDegrees(kotlin.math.atan2(dx, dy))
        if (deg < 0) deg += 360.0
        return kotlin.math.floor(deg)
    }

    /**
     * 单条方位的显示角（纯函数可单测）：Emitter 非空且找到目标 → 实时重算；
     * 否则用存档 Bearing 值。对齐桌面 CBearing.CalcBearing 语义。
     */
    fun bearingOf(bearingStored: Double, emitterId: String, allUnits: List<Unit>, owner: Unit): Double =
        if (emitterId.isNotBlank()) {
            val target = allUnits.firstOrNull { it.idNum == emitterId }
            if (target != null) {
                calcBearing(owner.x, owner.y, target.x, target.y) ?: bearingStored
            } else bearingStored
        } else bearingStored

    /**
     * 被动方位线（桌面版 PassiveBearings.Draw）。
     * R4：受 ShowSonar（Type="Sonar"）与 ShowEs（Type="ES" 等）开关控制。
     * 颜色按 ShowAsSide（目标阵营）分派：蓝方=蓝、红方=红、未知=黄（桌面反编译分支实测），
     * 蓝红色读 [palette]（PlayerSettings 阵营色，桌面 Colors 全局变量同语义）。
     * G45：beamWidth>0 时画波束扇形填充 + 两条边界线。
     * 反馈㉔：allUnits 传入以支持 Emitter 实时方位重算。
     */
    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
             showSonar: Boolean = true, showEs: Boolean = true,
             allUnits: List<Unit> = emptyList(),
             palette: com.simplot.android.render.UnitRenderer.Palette = com.simplot.android.render.UnitRenderer.Palette()) {
        val bearings = u.passiveBearingArray ?: return
        if (bearings.isEmpty()) return
        val (cx, cy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)

        for (b in bearings) {
            val isSonar = b.type.equals("Sonar", true) || b.type.isBlank()
            if (isSonar && !showSonar) continue
            if (!isSonar && !showEs) continue

            // 桌面 CalcBearing 复刻：Emitter 非空 → 按目标当前位置实时算方位
            val effBearing = bearingOf(b.bearing, b.emitter, allUnits, u)

            // 桌面 DrawBearings 颜色分派（反编译 0x1406eed22/0x1406eeda5 分支实测）：
            // 按 ShowAsSide（目标阵营）选色——Blue→玩家蓝方色、Red→玩家红方色、
            // Unknown/Neutral/其他→黄色（未知接触）。色值读 PlayerSettings（桌面 Colors 全局变量同语义）。
            val baseColor = when (b.showAsSide.lowercase()) {
                "blue" -> palette.blueFor.withAlpha(230)
                "red" -> palette.redFor.withAlpha(230)
                else -> Color.argb(230, 255, 215, 0)      // 未知 = 黄色
            }
            // #9：复用池画笔，按需改色
            val paint = linePaint.apply { color = baseColor }
            val rad = Math.toRadians(effBearing)
            // 屏显长度：BeamLength(海里)×zoom；0 时默认 80px
            val lenPx = if (b.beamLength > 0) (b.beamLength * 100000.0 * camera.zoom).toFloat() else 80f
            val ex = cx + lenPx * Math.sin(rad).toFloat()
            val ey = cy - lenPx * Math.cos(rad).toFloat()
            canvas.drawLine(cx, cy, ex, ey, paint)

            // G45：波束宽度 >0 → 扇形填充 + 两条边界线（更淡、更细）
            if (b.beamWidth > 0.0) {
                val (lo, hi) = beamEdgeBearings(effBearing, b.beamWidth)
                // 扇形填充（半透明）
                val fillPaint = beamFillPaint.apply {
                    color = Color.argb(50, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                }
                beamPath.reset()
                beamPath.moveTo(cx, cy)
                val steps = 24
                val loRad = Math.toRadians(lo)
                val hiRad = Math.toRadians(hi)
                for (i in 0..steps) {
                    val a = loRad + (hiRad - loRad) * i / steps
                    beamPath.lineTo(cx + lenPx * Math.sin(a).toFloat(), cy - lenPx * Math.cos(a).toFloat())
                }
                beamPath.close()
                canvas.drawPath(beamPath, fillPaint)
                // 边界线
                val edgePaintLocal = this@BearingRenderer.edgePaint.apply { color = baseColor }
                for (edge in listOf(lo, hi)) {
                    val er = Math.toRadians(edge)
                    val eeX = cx + lenPx * Math.sin(er).toFloat()
                    val eeY = cy - lenPx * Math.cos(er).toFloat()
                    canvas.drawLine(cx, cy, eeX, eeY, edgePaintLocal)
                }
            }
        }
    }
}
