package com.simplot.android.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.simplot.android.data.model.Unit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单位军标渲染器：按阵营着色，支持水面/潜艇/飞机/岸上四种基础符号。
 *
 * 符号风格（桌面版 SymbolGenerator，玩家设置选择）：
 * - NTDS（默认）：描边符号（水面圆+船头线、飞机三角、潜艇椭圆、岸上方形）
 * - CWS：填充符号（水面实心圆点、飞机实心三角、潜艇实心椭圆、岸上实心方块）
 *   —— 对齐桌面版 CwsSymbols color_filled 变体语义；
 *   契约7：BB/CC/DD/FF/PC/LA/LC/LS/AR/AS（及别名 CL/CA）额外使用彩色类型精灵图标（assets/symbols 下 4 张 color_filled 图）
 */
object UnitRenderer {

    enum class SymbolStyle { NTDS, CWS, WW2 }

    private val sideColors = mapOf(
        // R3 修复：桌面 GetUnitColor 语义 "All"=灰, "Neutral"=白, "Red"=红, "Blue"=蓝, "Unknown"=未知色
        "Blue" to 0xFF005AC8.toInt(),      // Color.rgb(0, 90, 200)
        "Red" to 0xFFC81E1E.toInt(),       // Color.rgb(200, 30, 30)
        "Neutral" to 0xFFFFFFFF.toInt(),   // Color.rgb(255, 255, 255) 桌面 Neutral=白
        "All" to 0xFF787878.toInt(),       // Color.rgb(120, 120, 120) 桌面 All=灰
        "Unknown" to 0xFF5A5A5A.toInt()    // Color.rgb(90, 90, 90)
    )

    fun colorOf(side: String): Int = sideColors[side] ?: 0xFF5A5A5A.toInt()

    /** 是否需要用深色描边保证可读性（D8：Neutral=白 在浅底图上不可见 → 加描边） */
    fun needsOutline(side: String): Boolean = side == "Neutral" || side == "All"

    /** 标签基准缩放：默认视野（Camera 初始 zoom）下的“1 倍”参考（反馈⑥） */
    const val LABEL_BASE_ZOOM = 0.0015f

    /** 标签字号（反馈⑥/契约6）：默认 24f，随 zoom 等比缩放，clamp [18f, 48f] 保证可读且不过大（最小 18f > 按钮文字 14sp） */
    fun labelTextSize(zoom: Float): Float = (24f * (zoom / LABEL_BASE_ZOOM)).coerceIn(18f, 48f)

    /** 标签锚点偏移系数（反馈⑥）：zoom/LABEL_BASE_ZOOM，clamp [0.7f, 2.5f]（偏移规则本身不变） */
    fun labelScaleK(zoom: Float): Float = (zoom / LABEL_BASE_ZOOM).coerceIn(0.7f, 2.5f)

    /**
     * 单位图标尺寸（契约7/反馈⑧/反馈⑨/反馈⑩）：默认 zoom 下 12dp，随 zoom 等比缩放；
     * 修复⑩（真机）：图标仍偏大挡航向标 → 基准 14dp→12dp、上限 40dp→32dp、下限 12dp→10dp；
     * density 感知（3x 屏可辨）+ 上限收紧（不遮挡航向标）。
     * appContext 为 null（单测环境）时 density 按 1 处理，纯函数可单测。
     */
    fun iconSizePx(zoom: Float): Float {
        val d = appContext?.resources?.displayMetrics?.density ?: 1f
        return (12f * (zoom / LABEL_BASE_ZOOM) * d).coerceIn(10f * d, 32f * d)
    }

    // ============ CWS 彩色类型图标（契约7：桌面版 CwsSymbols color_filled 精灵图） ============

    /** 精灵图网格尺寸：1560x455 = 24x7 格，每格 65px（已实测确认） */
    const val CWS_GRID = 65f

    /**
     * CWS 类型→格位映射 (行, 列)。
     * 映射依据（contract7 实测 + 反馈⑯ 全格像素验证）：row1（第二行）为水面舰类型行：
     *   col1=CV（航母：左 C 弧 + 右 V 斜线收拢，1px 全格确认；非剪影）
     *   col2=BB col3=CC col4=DD col5=FF col6=PC col7=LA col8=LC col9=LS col10=AR col11=AS
     *  ⚠️ 勿用 row0（非水面舰行）。CV 及其变体映射 row1 col1。
     */
    private val CWS_CLASS_CELLS = mapOf(
        "BB" to (1 to 2), "CC" to (1 to 3), "DD" to (1 to 4), "FF" to (1 to 5), "PC" to (1 to 6),
        "LA" to (1 to 7), "LC" to (1 to 8), "LS" to (1 to 9), "AR" to (1 to 10), "AS" to (1 to 11),
        "CV" to (1 to 1), "CVA" to (1 to 1), "CVN" to (1 to 1), "CVL" to (1 to 1), "CVS" to (1 to 1), "CVH" to (1 to 1),
        "CL" to (1 to 3), "CA" to (1 to 3), "CG" to (1 to 3)   // 别名：CL/CA/CG → CC 格（导弹巡洋舰同格）
    )

    /**
     * 空中/水下单位精灵图格位（row2/row0，精灵图实测：
     * row2 col16/19/22 = 飞机（机头+双翼轮廓），row2 col12 = 潜艇（鱼形+指挥塔），
     * row0 col21 = 导弹（黄色弹体+两侧 A））。
     * 桌面版 CwsSymbols：飞机/潜艇同样用类型精灵图，非纯色三角形。
     */
    private val CWS_DOMAIN_CELLS = mapOf(
        "AIRCRAFT" to (2 to 15),   // row2 col16（0-based）固定翼飞机
        "HELICOPTER" to (2 to 18), // row2 col19（0-based）直升机
        "SUBMARINE" to (2 to 11),  // row2 col12（0-based）潜艇
        "MISSILE" to (0 to 20)     // row0 col21（0-based）导弹
    )

    private const val SPRITE_ASSET_DIR = "symbols"

    private const val TAG = "UnitRenderer"

    /** appContext==null 时只告警一次（防刷屏） */
    private val warnedNoContext = AtomicBoolean(false)

    /** 应用级 Context（MainActivity.onCreate 注入；为 null 时精灵图加载失败 → 矢量兜底，不崩溃） */
    @Volatile
    private var appContext: Context? = null

    /** 精灵图缓存：按文件名懒加载（每张 1560x455 ARGB≈2.8MB；按需只解码当前阵营单张，不一次性加载 4 张） */
    private val spriteCache = ConcurrentHashMap<String, Bitmap>()

    /** 注入应用级 Context（精灵图加载用，只需一次；放 MainActivity.onCreate） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun spriteFileName(side: String): String = when (side) {
        "Blue" -> "blue_color_filled.png"
        "Red" -> "red_color_filled.png"
        "Neutral" -> "neutral_color_filled.png"
        else -> "unknown_color_filled.png"   // Unknown 阵营（含未知 side）用 unknown 图
    }

    /** 懒加载阵营精灵图（缓存命中即返回；解码失败返回 null → 调用方走矢量兜底）。仅 UI 线程调用，无并发竞争风险 */
    private fun loadSprite(side: String): Bitmap? {
        val name = spriteFileName(side)
        spriteCache[name]?.let { return it }
        val ctx = appContext
        if (ctx == null) {
            // 契约8：Context 未注入时打一次日志便于真机排查（仍返回 null 走矢量兜底，不崩溃）
            if (warnedNoContext.compareAndSet(false, true)) {
                Log.w(TAG, "sprite load failed: $name (appContext==null, UnitRenderer.init 未调用)")
            }
            return null
        }
        val bmp = try {
            ctx.assets.open("$SPRITE_ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            // 契约8：加载失败打日志（assets 缺资源/IO 异常等），仍返回 null 走矢量兜底不崩溃
            Log.w(TAG, "sprite load failed: $name", e)
            null
        }
        if (bmp != null) spriteCache[name] = bmp
        return bmp
    }

    /**
     * 绘制 CWS 类型独特图标：从精灵图裁剪 65x65 格 → 目标 (sx-sizePx/2, sy-sizePx/2, sizePx, sizePx)。
     * 支持：水面舰（CWS_CLASS_CELLS）、飞机/直升机/潜艇/导弹（CWS_DOMAIN_CELLS）。
     * @return true=已绘制；false=未命中映射或精灵图加载失败（调用方画矢量兜底）
     */
    private fun drawCwsIcon(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float): Boolean {
        val cell = cwsCellOf(u) ?: return false
        val bmp = loadSprite(u.side) ?: return false
        val g = CWS_GRID.toInt()
        val src = Rect(cell.second * g, cell.first * g, cell.second * g + g, cell.first * g + g)
        val dst = RectF(sx - sizePx / 2f, sy - sizePx / 2f, sx + sizePx / 2f, sy + sizePx / 2f)
        // 反馈⑨：放大时位图滤波平滑，避免像素化模糊
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, src, dst, filter)
        return true
    }

    /** CWS 精灵图格位解析：水面舰按 class，飞机/直升机/潜艇/导弹按 domain */
    private fun cwsCellOf(u: Unit): Pair<Int, Int>? {
        // 参考点不走精灵图（虚线圆/菱形单独绘制）
        val cls = u.unitClass.trim().uppercase()
        CWS_CLASS_CELLS[cls]?.let { return it }
        // 空中/水下单位（桌面版 CwsSymbols 有独立图标，不是纯色三角形）
        val type = u.unitType.lowercase()
        return when {
            u.isAircraft() && (type.contains("helo") || type.contains("helicopter")) -> CWS_DOMAIN_CELLS["HELICOPTER"]
            u.isAircraft() && (type.contains("missile") || type.contains("torpedo")) -> CWS_DOMAIN_CELLS["MISSILE"]
            u.isAircraft() -> CWS_DOMAIN_CELLS["AIRCRAFT"]
            u.isSubmarine() -> CWS_DOMAIN_CELLS["SUBMARINE"]
            else -> null
        }
    }

    fun draw(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float = 16f, selected: Boolean = false, symbolStyle: SymbolStyle = SymbolStyle.NTDS, showSpeedLeader: Boolean = true) {
        // 参考点（桌面版 CReferencePoint 单独处理：虚线圆/菱形标记）——在速度领导线之前绘制
        if (u.idNum.startsWith("R") || u.unitType.equals("Reference Point", true) || u.unitType.equals("Datum", true)) {
            drawReferencePoint(canvas, u, sx, sy, sizePx, selected)
            return
        }
        // 速度领导线（桌面版 SpeedLeaders.Draw）：沿航向向前，长度与航速成比例
        // 反馈⑩：更粗更长（与图标大小匹配）：线宽 1.5f→2.5f，长度系数 2.2→3.2、上限 90→140；
        // 起点从图标边缘出发（不遮挡航向标起点）；R4 修复：受 ShowSpeedLeaders 开关控制
        if (showSpeedLeader && u.speedKnots() > 0) {
            val r = sizePx / 2
            val leaderLen = (u.speedKnots() * 3.2).coerceAtLeast(14.0).coerceAtMost(140.0).toFloat()
            val hdgRad = Math.toRadians(u.courseDeg())
            val sinH = kotlin.math.sin(hdgRad).toFloat()
            val cosH = kotlin.math.cos(hdgRad).toFloat()
            // 起点：圆心沿航向偏移 r（到图标边缘），终点：起点再延伸 leaderLen
            val startX = sx + r * sinH
            val startY = sy - r * cosH
            val lx = startX + leaderLen * sinH
            val ly = startY - leaderLen * cosH
            val leader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = colorOf(u.side)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                alpha = 180
            }
            canvas.drawLine(startX, startY, lx, ly, leader)
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
        val ww2 = symbolStyle == SymbolStyle.WW2
        // R5：WW2 符号（桌面版 WW2Symbols）：菱形框架 + 类型字母，阵营色
        if (ww2) {
            val frame = Path().apply {
                moveTo(sx, sy - r)
                lineTo(sx + r * 0.9f, sy)
                lineTo(sx, sy + r)
                lineTo(sx - r * 0.9f, sy)
                close()
            }
            canvas.drawPath(frame, fill)
            canvas.drawPath(frame, stroke)
            // 类型字母（类简码首字母）
            val letter = u.unitClass.firstOrNull()?.uppercaseChar() ?: u.unitType.firstOrNull()?.uppercaseChar() ?: '?'
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sizePx * 0.7f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText(letter.toString(), sx, sy + sizePx * 0.25f, tp)
            // 沉没标记
            if (u.showSunk) {
                val sunk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                canvas.drawLine(sx - r, sy - r, sx + r, sy + r, sunk)
                canvas.drawLine(sx + r, sy - r, sx - r, sy + r, sunk)
            }
        } else if (!(cws && drawCwsIcon(canvas, u, sx, sy, sizePx))) {
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
        } // end !(cws && drawCwsIcon)

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

    /**
     * 参考点符号（桌面版 CReferencePoint 单独绘制：虚线圆 + 中心菱形）。
     * 颜色取单位阵营色（Neutral/All 白灰时带深色描边保证可见）。
     */
    private fun drawReferencePoint(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float, selected: Boolean) {
        val sideColor = colorOf(u.side)
        val r = sizePx * 0.6f
        val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = sideColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
            }
        }
        canvas.drawCircle(sx, sy, r, dashed)
        // 中心菱形
        val dia = Path().apply {
            moveTo(sx, sy - r * 0.8f)
            lineTo(sx + r * 0.8f, sy)
            lineTo(sx, sy + r * 0.8f)
            lineTo(sx - r * 0.8f, sy)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = sideColor; style = Paint.Style.FILL }
        canvas.drawPath(dia, fill)
        // D8：白色/浅色符号加深色描边
        if (needsOutline(u.side)) {
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFF333333.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            canvas.drawCircle(sx, sy, r, outline)
            canvas.drawPath(dia, outline)
        }
        if (selected) {
            val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(255, 180, 0)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawCircle(sx, sy, r + 5f, sel)
        }
    }
}
