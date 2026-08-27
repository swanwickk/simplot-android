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
import com.simplot.android.domain.model.PlayerSettings
import com.simplot.android.domain.model.SymbolSet
import com.simplot.android.domain.model.SymbolSize
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
 *
 * G47（批次3）：符号集四选（SymbolSet）——CWS Color Filled / CWS Color Unfilled /
 * CWS Mono Filled / NTDS，精灵图按 阵营×变体 选（color_filled|color_unfilled|mono_filled），
 * 缺失变体资产时走矢量兜底；WW2 为附加切换（ww2Mode）。
 * G08：符号尺寸档（SymbolSize：Dots/Reduced/Default/Enlarged），Dots 画位置点。
 * G09：阵营色板 Palette 由 PlayerSettings 蓝/红颜色键驱动，去硬编码。
 * G46：速度领导线线端补箭头。
 */
object UnitRenderer {

    /** 兼容保留（R5 三态循环：MainActivity 旧按钮/派生显示用）；G47 之后主选择走 [SymbolSet] */
    enum class SymbolStyle { NTDS, CWS, WW2 }

    /**
     * G09：阵营色板。蓝/红可配置（PlayerSettings.blueForColor/redForColor），
     * Neutral=白 / All=灰 / Unknown=深灰 固定桌面 GetUnitColor 语义（R3 修复）。
     */
    data class Palette(
        val blueFor: Int = 0xFF005AC8.toInt(),      // Color.rgb(0, 90, 200)
        val redFor: Int = 0xFFC81E1E.toInt()        // Color.rgb(200, 30, 30)
    ) {
        fun sideColor(side: String): Int = when (side) {
            "Blue" -> blueFor
            "Red" -> redFor
            "Neutral" -> 0xFFFFFFFF.toInt()   // 桌面 Neutral=白
            "All" -> 0xFF787878.toInt()        // 桌面 All=灰
            else -> 0xFFFFD500.toInt()         // Unknown=黄色（桌面语义）
        }
    }

    /** G09：PlayerSettings 颜色键 → 阵营色板（纯函数可单测） */
    fun paletteOf(settings: PlayerSettings): Palette = Palette(
        blueFor = settings.blueForColor.toInt(),
        redFor = settings.redForColor.toInt()
    )

    /** 默认色板下的阵营色（SideParsingTest 等旧调用点兼容；新代码优先 [colorOf(side, palette)]） */
    fun colorOf(side: String): Int = Palette().sideColor(side)

    /** G09：指定色板下的阵营色 */
    fun colorOf(side: String, palette: Palette): Int = palette.sideColor(side)

    /** 是否需要用深色描边保证可读性（D8：Neutral=白 在浅底图上不可见 → 加描边） */
    fun needsOutline(side: String): Boolean = side == "Neutral" || side == "All"

    /** 标签基准缩放：默认视野（Camera 初始 zoom）下的“1 倍”参考（反馈⑥） */
    const val LABEL_BASE_ZOOM = 0.0015f

    /**
     * 标签字号（反馈⑥/契约6/反馈㉑/反馈㉒/反馈㉖）：真机 density 感知，基准 16sp*d（默认 zoom），
     * 随 zoom 等比缩放，clamp [13sp*d, 32sp*d]（微调缩小一点，避免遮挡紧凑海图）。
     * 单测环境 appContext==null → d=1f，纯函数直接可单测。
     */
    fun labelTextSize(zoom: Float): Float {
        val d = appContext?.resources?.displayMetrics?.density ?: 1f
        return (16f * (zoom / LABEL_BASE_ZOOM) * d).coerceIn(13f * d, 32f * d)
    }

    /** 标签锚点偏移系数（反馈⑥）：zoom/LABEL_BASE_ZOOM，clamp [0.7f, 2.5f]（偏移规则本身不变） */
    fun labelScaleK(zoom: Float): Float = (zoom / LABEL_BASE_ZOOM).coerceIn(0.7f, 2.5f)

    /**
     * 单位图标尺寸（契约7/反馈⑧/反馈⑨/反馈⑩）：默认 zoom 下 12dp，随 zoom 等比缩放；
     * 修复⑩（真机）：图标仍偏大挡航向标 → 基准 14dp→12dp、上限 40dp→32dp、下限 12dp→10dp；
     * density 感知（3x 屏可辨）+ 上限收紧（不遮挡航向标）。
     * appContext 为 null（单测环境）时 density 按 1 处理，纯函数可单测。
     * G08：尺寸档由调用方乘 [SymbolSize.scale]（SceneCanvas 统一缩放，保持 hitTest 同链路）。
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
     * UnitType → CWS 格位回退（桌面精灵链路主链路补充）：
     * 当存档 UnitClass 为空（第三次所罗门海战 S006-S041 全部空串）时，
     * 按 UnitType 映射到与桌面 CwsSymbols.GetUnitType 同语义的格位，
     * 保证有素材必走精灵，不落入矢量红点兜底。
     * 映射覆盖该存档全部 4 种类型：Battleship/Cruiser/Destroyer/Surface Ship；
     * 通用 Surface Ship 回退到 DD 格（通用水面舰精灵，与桌面一致为带字母圆图标）。
     */
    private val CWS_UNITTYPE_CELLS = mapOf(
        "BATTLESHIP" to (1 to 2),   // BB
        "CRUISER" to (1 to 3),      // CC
        "DESTROYER" to (1 to 4),    // DD
        "FRIGATE" to (1 to 5),      // FF
        "PATROL" to (1 to 6),       // PC
        "CARRIER" to (1 to 1),      // CV
        "SURFACE SHIP" to (1 to 4)  // 通用水面舰 → DD 格（桌面素材为准，避免矢量红点）
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

    // ============ G68：Paint 复用（批次4） ============
    // 每帧/每单位 new Paint → object 级字段复用（样式固定项初始化配置，颜色/字号每单位 set，
    // setColor/setTextSize 为原生调用零分配）。渲染仅在 UI 线程串行执行（单画布），复用无并发冲突。
    // ⚠️ 必须 by lazy：object 字段直接初始化会在 JVM 单元测试加载类时构造 android.graphics.Paint
    //    （android.jar stub → ExceptionInInitializerError，批次4 踩过坑）；惰性初始化后
    //    纯函数测试不触发 Paint 构造，绘制路径（真机）首次访问时才创建，复用语义不变。
    private val leaderPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; alpha = 180
    } }
    private val arrowFillPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; alpha = 200
    } }
    private val strokePaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    } }
    private val fillPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val letterPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    private val selPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 180, 0); style = Paint.Style.STROKE; strokeWidth = 2.5f
    } }
    private val radarPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 200, 0); style = Paint.Style.FILL
    } }
    private val sonarPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 140, 220); style = Paint.Style.FILL
    } }
    private val sunkPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f
    } }
    private val spriteFilterPaint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }
    private val refDashedPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
    } }
    private val refFillPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val refOutlinePaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
    } }

    /**
     * #9：可复用 Path（每单位每帧会创建 6+ 个 Path，改用单例 + 每次使用前 reset()）。
     * drawPath 不清空几何，故同一 Path 需连续画两遍（如 WW2 frame）时两遍间不 reset，
     * 仅在下一次「开始构建新形状」前 reset()。惰性初始化 → JVM 单测不触发构造。
     */
    private val reusablePath by lazy { Path() }

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

    /** G47：符号集 → 精灵图变体后缀（桌面版 CwsSymbols.GetSymbolSet：阵营×填充/空心×彩色/单色） */
    fun spriteVariant(symbolSet: SymbolSet): String = when (symbolSet) {
        SymbolSet.CWS_COLOR_FILLED -> "color_filled"
        SymbolSet.CWS_COLOR_UNFILLED -> "color_unfilled"
        SymbolSet.CWS_MONO_FILLED -> "mono_filled"
        SymbolSet.NTDS -> "color_filled"   // NTDS 不应调用本函数（FIX-SYM 后 draw 已分流）；仅为编译完备保留
    }

    private fun spriteFileName(side: String, variant: String): String = when (side) {
        "Blue" -> "blue_${variant}.png"
        "Red" -> "red_${variant}.png"
        "Neutral" -> "neutral_${variant}.png"
        else -> "unknown_${variant}.png"   // Unknown 阵营（含未知 side）用 unknown 图
    }

    /** 懒加载阵营精灵图（缓存命中即返回；解码失败返回 null → 调用方走矢量兜底）。仅 UI 线程调用，无并发竞争风险 */
    private fun loadSprite(side: String, variant: String): Bitmap? {
        val name = spriteFileName(side, variant)
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
     * G47：按符号集变体（color_filled/color_unfilled/mono_filled）选图；资产缺失 → false（调用方画矢量兜底）。
     * 支持：水面舰（CWS_CLASS_CELLS）、飞机/直升机/潜艇/导弹（CWS_DOMAIN_CELLS）。
     * @return true=已绘制；false=未命中映射或精灵图加载失败（调用方画矢量兜底）
     */
    private fun drawCwsIcon(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float, variant: String): Boolean {
        val cell = cwsCellOf(u) ?: return false
        val bmp = loadSprite(u.side, variant) ?: return false
        val g = CWS_GRID.toInt()
        val src = Rect(cell.second * g, cell.first * g, cell.second * g + g, cell.first * g + g)
        val dst = RectF(sx - sizePx / 2f, sy - sizePx / 2f, sx + sizePx / 2f, sy + sizePx / 2f)
        // 反馈⑨：放大时位图滤波平滑，避免像素化模糊（G68：复用字段 paint）
        canvas.drawBitmap(bmp, src, dst, spriteFilterPaint)
        return true
    }

    /** CWS 精灵图格位解析：水面舰按 class（含 UnitType 回退），飞机/直升机/潜艇/导弹按 domain */
    private fun cwsCellOf(u: Unit): Pair<Int, Int>? {
        // 参考点不走精灵图（虚线圆/菱形单独绘制）
        val cls = u.unitClass.trim().uppercase()
        CWS_CLASS_CELLS[cls]?.let { return it }
        // 存档 UnitClass 为空时（如第三次所罗门海战 S006-S041）按 UnitType 回退到精灵格位
        // 保证有素材必用精灵，不落入矢量红点兜底；与桌面 CwsSymbols.GetUnitType 语义一致
        val typeKey = u.unitType.trim().uppercase()
        CWS_UNITTYPE_CELLS[typeKey]?.let { return it }
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

    /**
     * G46：速度领导线几何（纯函数可单测）——从单位图标边缘沿航向延伸 leaderLen，
     * 线端补箭头三角（桌面版 SpeedLeaders 折线 + 箭头）。
     *
     * @param sx sy     单位屏幕坐标（px）
     * @param courseDeg 罗盘角（0=北，顺时针）
     * @param leaderLen 线长（px）
     * @param r         图标半径（线起点偏移到图标边缘）
     * @return 起点/终点/箭头三点（屏幕坐标）
     */
    fun speedLeaderGeometry(
        sx: Float, sy: Float, courseDeg: Double, leaderLen: Float, r: Float
    ): SpeedLeaderGeometry {
        val hdgRad = Math.toRadians(courseDeg)
        val sinH = kotlin.math.sin(hdgRad).toFloat()
        val cosH = kotlin.math.cos(hdgRad).toFloat()
        val startX = sx + r * sinH
        val startY = sy - r * cosH
        val endX = startX + leaderLen * sinH
        val endY = startY - leaderLen * cosH
        // 箭头：tip=线端，base 沿航向回退 arrowLen，垂直半宽 arrowHalfW
        val arrowLen = 12f
        val arrowHalfW = 5f
        val baseCX = endX - arrowLen * sinH
        val baseCY = endY + arrowLen * cosH
        val bx1 = baseCX + arrowHalfW * cosH
        val by1 = baseCY + arrowHalfW * sinH
        val bx2 = baseCX - arrowHalfW * cosH
        val by2 = baseCY - arrowHalfW * sinH
        return SpeedLeaderGeometry(startX, startY, endX, endY, endX, endY, bx1, by1, bx2, by2)
    }

    /**
     * G08：是否画点标记（Dots 尺寸档 / 关闭友军符号时的蓝方单位）。
     * 纯函数可单测：Dots 档任何单位都画点；showFriendlySymbols=false 时 Blue（友方）画点。
     */
    fun isDotRendering(sizeLevel: SymbolSize, showFriendlySymbols: Boolean, side: String): Boolean =
        sizeLevel == SymbolSize.DOTS || (!showFriendlySymbols && side == "Blue")

    /**
     * 桌面 Perception 受限渲染：当前观察侧（showSideName）对该单位的可见数据快照。
     * - null=Referee/ALL 全知视角（不应用受限，直接用真实值）
     * - 非空时若存在对应该阵营的感知记录 → showName/showCS/showClass/showAltitude/showDepth/showAsSide/AsType 受限可见
     *   （与 FogOfWar.applyRestrictions 存档侧脱敏一致，地图侧实时生效）
     */
    data class RestrictedView(
        val showName: Boolean?,
        val showCourseSpeed: Boolean?,
        val showClass: Boolean?,
        val showAltitude: Boolean?,
        val showDepth: Boolean?,
        val showAsType: String?,
        val showAsSide: String?
    )

    fun restrictedViewOf(unit: Unit, showSideName: String?): RestrictedView? {
        if (showSideName == null) return null
        val per = unit.perceptionArray?.firstOrNull { it.seenBySide.equals(showSideName, true) } ?: return null
        return RestrictedView(per.showName, per.showCourseSpeed, per.showClass, per.showAltitude, per.showDepth, per.showAsType, per.showAsSide)
    }

    fun draw(
        canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float = 16f, selected: Boolean = false,
        symbolStyle: SymbolStyle = SymbolStyle.NTDS,
        symbolSet: SymbolSet = SymbolSet.CWS_COLOR_FILLED,
        ww2Mode: Boolean = false,
        sizeLevel: SymbolSize = SymbolSize.DEFAULT,
        showSpeedLeader: Boolean = true,
        palette: Palette = Palette(),
        friendlySymbols: Boolean = true,
        showSideName: String? = null
    ) {
        // 参考点（桌面版 CReferencePoint 单独处理：虚线圆/菱形标记）——在速度领导线之前绘制
        if (u.idNum.startsWith("R") || u.unitType.equals("Reference Point", true) || u.unitType.equals("Datum", true)) {
            drawReferencePoint(canvas, u, sx, sy, sizePx, selected, palette)
            return
        }
        val ww2 = ww2Mode || symbolStyle == SymbolStyle.WW2
        val dotMode = isDotRendering(sizeLevel, friendlySymbols, u.side)
        // 速度领导线（桌面版 SpeedLeaders.Draw）：沿航向向前，长度与航速成比例
        // 反馈⑩：更粗更长（与图标大小匹配）：线宽 1.5f→2.5f，长度系数 2.2→3.2、上限 90→140；
        // 起点从图标边缘出发（不遮挡航向标起点）；R4 修复：受 ShowSpeedLeaders 开关控制
        // G46：线端补箭头（speedLeaderGeometry）
        // 受限挂钩：对 X 方可见且该方 ShowCourseSpeed=false 时，地图不画速度领导线（与标签/落盘限制一致）
        val _rv = restrictedViewOf(u, showSideName)
        val showCsRestricted = _rv?.showCourseSpeed
        val canShowLeader = showSpeedLeader && (showCsRestricted == null || showCsRestricted) && u.speedKnots() > 0 && !dotMode
        if (canShowLeader) {
            val r = sizePx / 2
            val leaderLen = (u.speedKnots() * 3.2).coerceAtLeast(14.0).coerceAtMost(140.0).toFloat()
            val g = speedLeaderGeometry(sx, sy, u.courseDeg(), leaderLen, r)
            // G68：复用字段 paint（样式初始化已配），仅改色
            leaderPaint.color = palette.sideColor(u.side)
            canvas.drawLine(g.startX, g.startY, g.endX, g.endY, leaderPaint)
            arrowFillPaint.color = palette.sideColor(u.side)
            // #9：复用 Path（先 reset 再构建，避免每帧 new）
            reusablePath.reset()
            reusablePath.apply {
                moveTo(g.arrowTipX, g.arrowTipY)
                lineTo(g.arrowBase1X, g.arrowBase1Y)
                lineTo(g.arrowBase2X, g.arrowBase2Y)
                close()
            }
            canvas.drawPath(reusablePath, arrowFillPaint)
        }
        val sideColor = palette.sideColor(if (_rv?.showAsSide?.isNotBlank() == true) _rv.showAsSide!! else u.side)
        // G68：复用字段 paint（样式初始化已配），仅改色
        strokePaint.color = sideColor
        fillPaint.color = sideColor

        val r = sizePx / 2
        val effSideForOutline = if (_rv?.showAsSide?.isNotBlank() == true) _rv.showAsSide!! else u.side
        val needOutline = needsOutline(effSideForOutline)
        if (dotMode) {
            // G08：Dots 档 / 关闭友军符号 → 位置点（实心小圆，保留选中/传感器/沉没标记）；N1：Neutral白点加深色描边
            canvas.drawCircle(sx, sy, sizePx * 0.25f, fillPaint)
            if (needOutline) canvas.drawCircle(sx, sy, sizePx * 0.25f, refOutlinePaint)
        } else if (ww2) {
            // R5：WW2 符号（桌面版 WW2Symbols）：菱形框架 + 类型字母，阵营色；N1：Neutral/All 白系加深色描边
            // #9：复用 Path（先 reset 再构建；frame 需描边+填充两遍，两遍间不 reset）
            reusablePath.reset()
            reusablePath.apply {
                moveTo(sx, sy - r)
                lineTo(sx + r * 0.9f, sy)
                lineTo(sx, sy + r)
                lineTo(sx - r * 0.9f, sy)
                close()
            }
            canvas.drawPath(reusablePath, fillPaint)
            canvas.drawPath(reusablePath, strokePaint)
            if (needOutline) canvas.drawPath(reusablePath, refOutlinePaint)
            // 类型字母（类简码首字母）；G68：复用字段 paint（textAlign 初始化已配），仅改色/字号
            val letter = u.unitClass.firstOrNull()?.uppercaseChar() ?: u.unitType.firstOrNull()?.uppercaseChar() ?: '?'
            letterPaint.color = Color.WHITE
            letterPaint.textSize = sizePx * 0.7f
            canvas.drawText(letter.toString(), sx, sy + sizePx * 0.25f, letterPaint)
        } else if (symbolSet == SymbolSet.NTDS) {
            // FIX-SYM：NTDS 是独立符号系统（桌面 DrawUnitNtds：水面=实心圆点、潜艇=十字、飞机=三角形），
            // 不使用 PNG 精灵图（精灵图仅 CWS 三变体专用，GetSymbolSet 只服务 CWS）。
            // 此前把 NTDS 映射到 color_filled 导致与 CWS 完全相同——用户实测"CWS 和 NTDS 一样"即此根因。
            val rN = r
            // 水面舰艇：实心圆点 + 北向船头线（NTDS 主形状；纯点与 CWS 圆圈过近难辨，
            // 加船头线既保留"实心圆点"语义又能看航向——桌面 DrawUnitNtds 水面亦带航向指示）
            when {
                u.isAircraft() -> {
                    // 飞机：三角翼（填充+描边，覆盖圆点）
                    reusablePath.reset()
                    reusablePath.apply {
                        moveTo(sx, sy - rN * 1.15f)
                        lineTo(sx - rN * 1.25f, sy + rN * 0.95f)
                        lineTo(sx + rN * 1.25f, sy + rN * 0.95f)
                        close()
                    }
                    canvas.drawPath(reusablePath, fillPaint)
                    canvas.drawPath(reusablePath, strokePaint)
                    if (needOutline) canvas.drawPath(reusablePath, refOutlinePaint)
                }
                u.isSubmarine() -> {
                    // 潜艇：十字（桌面 DrawUnitNtds 明确：潜艇=十字）
                    canvas.drawLine(sx - rN * 1.2f, sy, sx + rN * 1.2f, sy, strokePaint)
                    canvas.drawLine(sx, sy - rN * 1.2f, sx, sy + rN * 1.2f, strokePaint)
                }
                else -> {
                    // 水面舰艇：实心圆点增大可视半径 + 航向船头线（NTDS 主形状；纯点与 CWS 圆圈难辨）
                    canvas.drawCircle(sx, sy, sizePx * 0.5f, strokePaint)
                    val hdg = Math.toRadians(u.courseDeg())
                    val sinH = kotlin.math.sin(hdg).toFloat()
                    val cosH = kotlin.math.cos(hdg).toFloat()
                    canvas.drawLine(
                        sx + sizePx * 0.5f * sinH,
                        sy - sizePx * 0.5f * cosH,
                        sx + sizePx * 0.95f * sinH,
                        sy - sizePx * 0.95f * cosH,
                        strokePaint
                    )
                }
            }
        } else {
            // G47：CWS 变体（color_filled/color_unfilled/mono_filled）精灵图优先；资产缺失才矢量兜底
            // 受限：showAsType/showAsSide 在地图侧实时伪装显示（与 FogOfWar.applyRestrictions 存档侧一致，互补落盘前实时受限渲染）
            val rv2 = _rv
            val effUnitTypeForSprite = rv2?.showAsType?.takeIf { it.isNotBlank() } ?: u.unitType
            val effSideForSprite = rv2?.showAsSide?.takeIf { it.isNotBlank() } ?: u.side
            // 用受限后的类型/阵营去解析精灵格位与阵营色（非空字符串才视为伪装）
            val spriteUnit = if (rv2 != null && (rv2.showAsType?.isNotBlank() == true || rv2.showAsSide?.isNotBlank() == true)) {
                // 轻量拷贝仅改显示字段（不影响原存档对象；draw 内只读 unitType/unitClass/side）
                u.copy(unitType = effUnitTypeForSprite, side = effSideForSprite)
            } else u
            // 若受限 showClass=false，伪装类型对应的格位仍可显示，但不在标签区显示级别（标签由 drawUnitLabel 控制）
            val spriteDrawn = drawCwsIcon(canvas, spriteUnit, sx, sy, sizePx, spriteVariant(symbolSet))
            // N1：Neutral/All 白系精灵图（位图）在浅底上也加描边外圈（位图本身无矢量描边，补一圈深色圆）保证可见
            if (spriteDrawn && needOutline) canvas.drawCircle(sx, sy, r, refOutlinePaint)
            if (!spriteDrawn) {
                // 填充语义：NTDS=不填充（仅中心小点）；CWS Color Filled / Mono Filled=填充；Color Unfilled=空心描边
                // N1：Neutral/All 白系在浅底（海图/白背景）上加深色描边打底保证可见
                val filled = symbolSet == SymbolSet.CWS_COLOR_FILLED || symbolSet == SymbolSet.CWS_MONO_FILLED
                when {
                    u.isAircraft() -> {
                        // 飞机：三角翼符号
                        // #9：复用 Path（先 reset 再构建）
                        reusablePath.reset()
                        reusablePath.apply {
                            moveTo(sx, sy - r)
                            lineTo(sx - r * 1.1f, sy + r * 0.8f)
                            lineTo(sx + r * 1.1f, sy + r * 0.8f)
                            close()
                        }
                        canvas.drawPath(reusablePath, strokePaint)
                        if (filled) canvas.drawPath(reusablePath, fillPaint)
                    }
                    u.isSubmarine() -> {
                        // 潜艇：横椭圆 + 中线
                        canvas.drawOval(sx - r * 1.3f, sy - r * 0.7f, sx + r * 1.3f, sy + r * 0.7f, strokePaint)
                        canvas.drawLine(sx - r * 1.3f, sy, sx + r * 1.3f, sy, strokePaint)
                        if (filled) canvas.drawOval(sx - r * 1.3f, sy - r * 0.7f, sx + r * 1.3f, sy + r * 0.7f, fillPaint)
                    }
                    u.unitType.equals("Airfield", true) || u.idNum.startsWith("L") -> {
                        // 岸上设施：方块
                        canvas.drawRect(sx - r, sy - r, sx + r, sy + r, strokePaint)
                        if (filled) canvas.drawRect(sx - r, sy - r, sx + r, sy + r, fillPaint)
                    }
                    else -> {
                        // 水面舰艇：圆（北向船头线）；精灵优先，无精灵才矢量兜底
                        canvas.drawCircle(sx, sy, r, strokePaint)
                        canvas.drawLine(sx, sy - r, sx, sy + r * 0.6f, strokePaint)
                        if (filled) {
                            canvas.drawCircle(sx, sy, r, fillPaint)
                        } else {
                            canvas.drawCircle(sx, sy, r * 0.35f, fillPaint)
                        }
                    }
                }
                // N1：矢量白符号在浅底上加深色描边外圈（仅对 Neutral/All 且走矢量分支）
                if (needOutline) {
                    when {
                        u.isAircraft() -> {
                            reusablePath.reset()
                            reusablePath.apply { moveTo(sx, sy - r); lineTo(sx - r * 1.1f, sy + r * 0.8f); lineTo(sx + r * 1.1f, sy + r * 0.8f); close() }
                            canvas.drawPath(reusablePath, refOutlinePaint)
                        }
                        u.isSubmarine() -> {
                            canvas.drawOval(sx - r * 1.3f, sy - r * 0.7f, sx + r * 1.3f, sy + r * 0.7f, refOutlinePaint)
                        }
                        u.unitType.equals("Airfield", true) || u.idNum.startsWith("L") -> {
                            canvas.drawRect(sx - r, sy - r, sx + r, sy + r, refOutlinePaint)
                        }
                        else -> canvas.drawCircle(sx, sy, r, refOutlinePaint)
                    }
                }
            }
        }

        // 选中高亮（G68：复用字段 paint，样式初始化已配）
        if (selected) {
            canvas.drawCircle(sx, sy, r + 5f, selPaint)
        }

        // 主动传感器激活标记（桌面版 ActiveSensors.Draw）：雷达=黄色三角（右上），声纳=蓝色菱形（左上）
        // G68：复用字段 paint（固定色，样式初始化已配）
        if (u.isActiveRadar) {
            // #9：复用 Path（先 reset 再构建）
            reusablePath.reset()
            reusablePath.apply {
                moveTo(sx + r + 2f, sy - r - 6f)
                lineTo(sx + r + 8f, sy - r - 10f)
                lineTo(sx + r + 10f, sy - r - 3f)
                close()
            }
            canvas.drawPath(reusablePath, radarPaint)
        }
        if (u.isActiveSonar) {
            // #9：复用 Path（先 reset 再构建）
            reusablePath.reset()
            reusablePath.apply {
                moveTo(sx - r - 8f, sy - r - 8f)
                lineTo(sx - r - 3f, sy - r - 11f)
                lineTo(sx - r + 2f, sy - r - 8f)
                lineTo(sx - r - 3f, sy - r - 5f)
                close()
            }
            canvas.drawPath(reusablePath, sonarPaint)
        }

        // 沉没标记：叉（G68：复用字段 paint，固定色）
        if (u.showSunk) {
            canvas.drawLine(sx - r, sy - r, sx + r, sy + r, sunkPaint)
            canvas.drawLine(sx + r, sy - r, sx - r, sy + r, sunkPaint)
        }
    }

    /**
     * 参考点符号（桌面版 CReferencePoint 单独绘制：虚线圆 + 中心菱形）。
     * 颜色取单位阵营色（Neutral/All 白灰时带深色描边保证可见）。
     */
    private fun drawReferencePoint(canvas: Canvas, u: Unit, sx: Float, sy: Float, sizePx: Float, selected: Boolean, palette: Palette) {
        val sideColor = palette.sideColor(u.side)
        val r = sizePx * 0.6f
        // G68：复用字段 paint（虚线 pathEffect 等样式初始化已配），仅改色
        refDashedPaint.color = sideColor
        canvas.drawCircle(sx, sy, r, refDashedPaint)
        // 中心菱形（#9：复用 Path，先 reset 再构建）
        reusablePath.reset()
        reusablePath.apply {
            moveTo(sx, sy - r * 0.8f)
            lineTo(sx + r * 0.8f, sy)
            lineTo(sx, sy + r * 0.8f)
            lineTo(sx - r * 0.8f, sy)
            close()
        }
        refFillPaint.color = sideColor
        canvas.drawPath(reusablePath, refFillPaint)
        // D8：白色/浅色符号加深色描边
        if (needsOutline(u.side)) {
            canvas.drawCircle(sx, sy, r, refOutlinePaint)
            canvas.drawPath(reusablePath, refOutlinePaint)
        }
        if (selected) {
            canvas.drawCircle(sx, sy, r + 5f, selPaint)
        }
    }
}

/** G46：速度领导线几何结果（屏幕坐标） */
data class SpeedLeaderGeometry(
    val startX: Float, val startY: Float,
    val endX: Float, val endY: Float,
    val arrowTipX: Float, val arrowTipY: Float,
    val arrowBase1X: Float, val arrowBase1Y: Float,
    val arrowBase2X: Float, val arrowBase2Y: Float
)
