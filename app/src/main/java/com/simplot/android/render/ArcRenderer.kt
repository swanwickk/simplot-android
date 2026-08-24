package com.simplot.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.simplot.android.data.codec.ArcColorCodec
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

    /** VB 颜色 "&h00RRGGBB" → Android Color（不透明）；缺失/非法时回退桌面版默认 黄色 0xFFFF00。
     *  委托 ArcColorCodec 统一实现（与选色器共用，防两套解析漂移）；语义与原实现逐位等价。 */
    fun parseColor(vb: String?): Int = ArcColorCodec.parseVbColor(vb)

    /**
     * R1-v3：ArcAngle → drawArc 扫描角。
     * 桌面语义（反编译 DrawSensors 确认）：0 = 整圆 360°；负值 = 无效弧返回 null 不绘制；
     * 正值原样。纯 Kotlin 顶层函数，JVM 单测锁定（ArcSweepSemanticsTest）。
     */
    fun sweepOf(arcAngle: Double): Float? =
        if (arcAngle < 0.0) null else if (arcAngle == 0.0) 360f else arcAngle.toFloat()

    /**
     * 格式化距离数值：整海里显示为整数（如 15、0），非整海里显示 1 位小数（如 14.5）。
     */
    fun formatRangeNumber(r: Double): String =
        if (r % 1.0 == 0.0) r.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", r)

    /**
     * 构造弧上标注文字：[弧名称] [最小距离]-[最大距离]。
     * 例如：
     * - "FC L 0-15"
     * - "SS L M 0-22"
     * - "Main Gun 2-15"
     * - 若名称为空则显示 "0-15"
     */
    fun formatArcLabel(arcName: String, minRangeNm: Double, maxRangeNm: Double): String {
        val rangeStr = "${formatRangeNumber(minRangeNm)}-${formatRangeNumber(maxRangeNm)}"
        val cleanName = arcName.trim()
        return if (cleanName.isNotBlank()) "$cleanName $rangeStr" else rangeStr
    }

    // ---- P3-1：复用画笔/路径（主线程串行绘制，字段复用无并发冲突） ----
    private val arcPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f } }
    private val ringPath by lazy { Path() }
    private val outerRect by lazy { RectF() }
    private val innerRect by lazy { RectF() }
    /** 弧标注画笔（G-Labels：桌面 DrawCircleLabels/DrawArcLabels；字号 12×scale，弧色不透明） */
    private val labelPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f } }
    // 标注文字尺寸缓存（CheckLabelOverlap 简化版：同帧矩形相交即跳过，防密集环文字叠字）
    private val labelRect by lazy { RectF() }

    fun draw(canvas: Canvas, u: Unit, camera: Camera, canvasW: Int, canvasH: Int,
             showSensors: Boolean = true, showWeapons: Boolean = true) {
        val (cx, cy) = camera.worldToScreen(u.x, u.y, canvasW, canvasH)
        val headingRad = Math.toRadians(u.courseDeg())
        var occupied: MutableList<RectF>? = null

        // 传感器弧（G23：按桌面列表原序绘制——编辑器可 ↑/↓ 重排）
        if (showSensors) {
            u.sensorArray.orEmpty().forEach { s ->
                if (!s.isVisible) return@forEach
                val arcName = when {
                    s.tag.isNotBlank() && s.label.isNotBlank() -> "${s.tag} ${s.label}"
                    s.label.isNotBlank() -> s.label
                    s.tag.isNotBlank() -> s.tag
                    else -> ""
                }
                occupied = drawArc(canvas, cx, cy, s.minRange, s.maxRange, s.startAngle, s.arcAngle,
                    headingRad, s.isFilled, s.arcColor, camera, canvasW, canvasH, occupied, arcName)
            }
        }
        // 武器弧（G23：按桌面列表原序绘制）
        if (showWeapons) {
            u.weaponArray.orEmpty().forEach { w ->
                if (!w.isVisible) return@forEach
                val arcName = when {
                    w.tag.isNotBlank() && w.label.isNotBlank() -> "${w.tag} ${w.label}"
                    w.label.isNotBlank() -> w.label
                    w.tag.isNotBlank() -> w.tag
                    else -> ""
                }
                occupied = drawArc(canvas, cx, cy, w.minRange, w.maxRange, w.startAngle, w.arcAngle,
                    headingRad, w.isFilled, w.arcColor, camera, canvasW, canvasH, occupied, arcName)
            }
        }
    }

    /** 追加占用矩形（防重叠），null 安全 */
    private fun occupy(list: MutableList<RectF>?, rect: RectF): MutableList<RectF> {
        val l = list ?: mutableListOf()
        l.add(RectF(rect))
        return l
    }

    private fun overlaps(list: MutableList<RectF>?, rect: RectF): Boolean =
        list?.any { RectF.intersects(it, rect) } == true

    /**
     * 画一条弧 + 标注，返回更新后的占用矩形列表。
     * 标注语义（桌面反编译 DrawSensorLabels 分派 + DrawCircleLabels/DrawArcLabels）：
     * - 标注内容格式：[弧名称] [最小距离]-[最大距离]，如 "FC L 0-15"、"SS L M 0-22"；
     * - ArcAngle ≤ 0（整圆）：在圆的 225° 附近标「名称 最小-最大」；
     * - ArcAngle > 0（扇形）：在弧中点角度标「名称 最小-最大」。
     * 多环密集时在基准角附近微调寻空（防 14nm/15nm 相互遮挡），寻空失败保底绘制不丢标注。
     */
    private fun drawArc(
        canvas: Canvas, cx: Float, cy: Float,
        minRangeNm: Double, maxRangeNm: Double,
        startAngle: Double, arcAngle: Double,
        headingRad: Double, filled: Boolean, vbColor: String?, camera: Camera,
        canvasW: Int, canvasH: Int, occupied: MutableList<RectF>?,
        arcName: String = ""
    ): MutableList<RectF>? {
        if (maxRangeNm <= 0) return occupied
        // R1-v3 修复（桌面反编译 DrawSensors 实测语义）：ArcAngle=0 表示整圆（360°），
        // 典型存档雷达环 FC L/M/S 均为 ArcAngle=0；负角度才视为无效弧不绘制。
        // v0.7.5-arcfix2 只取消了提前 return，sweep 仍为 0° → drawArc 扫 0 度等于没画，真因在此。
        val sweep = sweepOf(arcAngle) ?: return occupied
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

        // ---- G-Labels：弧上文字标注（桌面 DrawSensorLabels → DrawCircleLabels / DrawArcLabels） ----
        // 屏外圆心不标（防远处单位的标注飘进屏幕边缘造成误读）
        if (cx < -radiusMax || cx > canvasW + radiusMax || cy < -radiusMax || cy > canvasH + radiusMax) {
            return occupied
        }
        val label = formatArcLabel(arcName, minRangeNm, maxRangeNm)
        val baseAngleDeg = if (arcAngle > 0.0) startAngle + arcAngle / 2.0 else 225.0

        val lp = labelPaint
        lp.color = color
        // 反馈㉑：弧标注字号与单位名称标签同链路（labelTextSize 随 zoom 缩放）
        lp.textSize = com.simplot.android.render.UnitRenderer.labelTextSize(camera.zoom)
        val tw = lp.measureText(label)
        val th = lp.textSize

        // 尝试最佳非重叠位置（整圆在 225° 附近微调寻空；扇形在中心角附近微调；寻空失败仍保底绘制，不丢标注）
        val angleCandidates = if (arcAngle > 0.0) {
            listOf(baseAngleDeg, baseAngleDeg - 8.0, baseAngleDeg + 8.0, baseAngleDeg - 16.0, baseAngleDeg + 16.0)
        } else {
            listOf(225.0, 210.0, 240.0, 195.0, 255.0, 180.0, 270.0, 165.0, 285.0)
        }

        var chosenLx = 0f
        var chosenLy = 0f
        var foundFree = false

        for (candAngle in angleCandidates) {
            val labRad = Math.toRadians(candAngle + Math.toDegrees(headingRad))
            val lx = cx + radiusMax * kotlin.math.sin(labRad).toFloat()
            val ly = cy - radiusMax * kotlin.math.cos(labRad).toFloat()
            labelRect.set(lx, ly - th, lx + tw, ly + 2f)
            if (!overlaps(occupied, labelRect)) {
                chosenLx = lx
                chosenLy = ly
                foundFree = true
                break
            }
        }

        if (!foundFree) {
            // 所有候选角度均有重叠：使用基准角度强行绘制（保底显示）
            val labRad = Math.toRadians(baseAngleDeg + Math.toDegrees(headingRad))
            chosenLx = cx + radiusMax * kotlin.math.sin(labRad).toFloat()
            chosenLy = cy - radiusMax * kotlin.math.cos(labRad).toFloat()
            labelRect.set(chosenLx, chosenLy - th, chosenLx + tw, chosenLy + 2f)
        }

        canvas.drawText(label, chosenLx, chosenLy, lp)
        return occupy(occupied, labelRect)
    }
}
