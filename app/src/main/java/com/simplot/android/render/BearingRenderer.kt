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
     * 计算角度差 a - b，归一化到 [-180, 180] 区间
     */
    fun angleDiff(a: Double, b: Double): Double {
        var diff = (a - b) % 360.0
        if (diff > 180.0) diff -= 360.0
        if (diff < -180.0) diff += 360.0
        return diff
    }

    /**
     * 将角度归一化到 [0, 360) 区间
     */
    fun normalizeAngle(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        return d
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
     * 单条方位的显示中心角（纯函数可单测）：
     * - 若 Emitter 关联了目标单位：
     *   以目标真实方位为基准；允许在波束宽度 [-beamWidth/2, +beamWidth/2] 内存在随机散布误差（目标不在正中心，呈现探测模糊）；
     *   若存储的方位角偏离真实方位超过波束半角（例如用户调小了波束宽度），
     *   强制将其截断/限制在波束允许范围内（保证目标单位无论如何调整波束宽度，都必然处于扇区内）。
     * - 若无 Emitter 关联（固定方位线）：直接返回存档 bearingStored。
     */
    fun bearingOf(
        bearingStored: Double,
        emitterId: String,
        beamWidth: Double,
        allUnits: List<Unit>,
        owner: Unit
    ): Double {
        if (emitterId.isBlank()) return normalizeAngle(bearingStored)
        val target = allUnits.firstOrNull { it.idNum == emitterId } ?: return normalizeAngle(bearingStored)
        val trueBearing = calcBearing(owner.x, owner.y, target.x, target.y) ?: return normalizeAngle(bearingStored)

        if (beamWidth <= 0.0) {
            return trueBearing
        }

        // 最大允许偏角为半波束（保留 15% 缓冲边界，确保目标不会刚好切在边界线上）
        val maxDeviation = (beamWidth / 2.0) * 0.85
        val currentDiff = angleDiff(bearingStored, trueBearing)

        return if (kotlin.math.abs(currentDiff) <= maxDeviation) {
            normalizeAngle(bearingStored)
        } else {
            // 超出范围（如用户将 10° 改成 5°）：截断到新波束允许的最大散布内，确保目标始终在扇区内
            val clampedDiff = currentDiff.coerceIn(-maxDeviation, maxDeviation)
            normalizeAngle(trueBearing + clampedDiff)
        }
    }

    /** 旧签名兼容 */
    fun bearingOf(bearingStored: Double, emitterId: String, allUnits: List<Unit>, owner: Unit): Double =
        bearingOf(bearingStored, emitterId, 0.0, allUnits, owner)

    /**
     * 在目标真实方位基础上，按波束宽度生成一个随机散布方位（纯函数可单测）：
     * 保证真实目标处于生成扇区内，但不处于正中央。
     */
    fun randomizeBearingInBeam(trueBearing: Double, beamWidth: Double, randomFactor: Double = kotlin.random.Random.nextDouble()): Double {
        if (beamWidth <= 0.0) return normalizeAngle(trueBearing)
        val maxDev = (beamWidth / 2.0) * 0.75
        val offset = (randomFactor * 2.0 - 1.0) * maxDev
        return normalizeAngle(kotlin.math.floor(trueBearing + offset))
    }

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

            // 桌面 CalcBearing 复刻：Emitter 非空 → 按目标当前位置与波束宽度计算显示方位
            val effBearing = bearingOf(b.bearing, b.emitter, b.beamWidth, allUnits, u)

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
            // 屏显长度：BeamLength(海里)×zoom；0/未填表示探测距离无限（延伸覆盖整个海图视野）
            val maxViewDim = maxOf(canvasW.toFloat(), canvasH.toFloat()) * 3f
            val lenPx = if (b.beamLength > 0) (b.beamLength * 100000.0 * camera.zoom).toFloat() else maxOf(3000f, maxViewDim)

            if (b.beamWidth > 0.0) {
                // G45/反馈㉗：波束宽度 >0 时画扇形填充 + 两侧误差边界线（不画中间多余黄线）
                val (lo, hi) = beamEdgeBearings(effBearing, b.beamWidth)
                // 扇形填充（半透明）
                val fillPaint = beamFillPaint.apply {
                    color = Color.argb(45, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                }
                beamPath.reset()
                beamPath.moveTo(cx, cy)
                val steps = 36
                val loRad = Math.toRadians(lo)
                val hiRad = Math.toRadians(hi)
                for (i in 0..steps) {
                    val a = loRad + (hiRad - loRad) * i / steps
                    beamPath.lineTo(cx + lenPx * Math.sin(a).toFloat(), cy - lenPx * Math.cos(a).toFloat())
                }
                beamPath.close()
                canvas.drawPath(beamPath, fillPaint)
                // 两侧边界线
                val edgePaintLocal = this@BearingRenderer.edgePaint.apply {
                    color = baseColor
                    strokeWidth = 1.5f
                }
                for (edge in listOf(lo, hi)) {
                    val er = Math.toRadians(edge)
                    val eeX = cx + lenPx * Math.sin(er).toFloat()
                    val eeY = cy - lenPx * Math.cos(er).toFloat()
                    canvas.drawLine(cx, cy, eeX, eeY, edgePaintLocal)
                }
            } else {
                // 波束宽度 <= 0：单条精确方位线
                val ex = cx + lenPx * Math.sin(rad).toFloat()
                val ey = cy - lenPx * Math.cos(rad).toFloat()
                canvas.drawLine(cx, cy, ex, ey, paint)
            }
        }
    }
}
