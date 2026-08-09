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
import com.simplot.android.data.model.Unit
import java.util.concurrent.ConcurrentHashMap

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

    /** 单位图标尺寸（契约7/反馈⑧）：默认 zoom 下 16f，随 zoom 等比缩放（与标签同链路），clamp [14f, 40f] 保证可辨且不过大 */
    fun iconSizePx(zoom: Float): Float = (16f * (zoom / LABEL_BASE_ZOOM)).coerceIn(14f, 40f)

    // ============ CWS 彩色类型图标（契约7：桌面版 CwsSymbols color_filled 精灵图） ============

    /** 精灵图网格尺寸：1560x455 = 24x7 格，每格 65px（已实测确认） */
    const val CWS_GRID = 65f

    /** row1（第二行）类型→格位映射 (行, 列)；别名 CL/CA → CC 格（桌面版同款，场景中 CL/CA 无专属格） */
    private val CWS_CLASS_CELLS = mapOf(
        "BB" to (1 to 2), "CC" to (1 to 3), "DD" to (1 to 4), "FF" to (1 to 5), "PC" to (1 to 6),
        "LA" to (1 to 7), "LC" to (1 to 8), "LS" to (1 to 9), "AR" to (1 to 10), "AS" to (1 to 11),
        "CL" to (1 to 3), "CA" to (1 to 3)   // 别名：CL/CA → CC 格
    )

    private const val SPRITE_ASSET_DIR = "symbols"

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
        val ctx = appContext ?: return null
        val bmp = try {
            ctx.assets.open("$SPRITE_ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bmp != null) spriteCache[name] = bmp
        return bmp
    }

    /**
     * 绘制 CWS 类型独特图标：从精灵图裁剪 65x65 格 → 目标 (sx-sizePx/2, sy-sizePx/2, sizePx, sizePx)。
     * @return true=已绘制精灵图标；false=类型未命中映射或精灵图加载失败（调用方画矢量兜底）
     */
    private fun drawCwsIcon(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float): Boolean {
        val cell = CWS_CLASS_CELLS[u.unitClass.trim().uppercase()] ?: return false
        val bmp = loadSprite(u.side) ?: return false
        val g = CWS_GRID.toInt()
        val src = Rect(cell.second * g, cell.first * g, cell.second * g + g, cell.first * g + g)
        val dst = RectF(sx - sizePx / 2f, sy - sizePx / 2f, sx + sizePx / 2f, sy + sizePx / 2f)
        canvas.drawBitmap(bmp, src, dst, null)
        return true
    }

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
        // 契约7：CWS 先尝试类型独特精灵图标（BB/CC/DD/FF/PC/LA/LC/LS/AR/AS + 别名 CL/CA）；
        // 未命中映射或加载失败 → 矢量兜底（原 CWS 填充符号），保证任何类型都有可见符号
        if (!(cws && drawCwsIcon(canvas, u, sx, sy, sizePx))) {
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
}
