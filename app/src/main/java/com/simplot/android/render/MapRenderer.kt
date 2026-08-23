package com.simplot.android.render

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri

/**
 * 地图渲染器（架构重构 Phase 1：拆为「解析 + 绘制」两层）。
 *
 * - 解析：委托 [MapDataParser]（纯 Kotlin，产出点列表）
 * - 绘制：本类只把点列表转屏幕坐标画出来（android.graphics 薄壳）
 *
 * 旧格式兼容：parseMapConfig（txt）保留；官方 JSON 一律走 parser。
 *
 * G09（批次3）：网格/陆地/海洋/国界等颜色不再硬编码，由调用方按 PlayerSettings 颜色键传入
 * （SceneCanvas 接线；默认值 = 原硬编码色，保持行为不变）。纯色值辅助 [darkerColor]/[withAlphaColor] 可单测。
 */
class MapRenderer {

    var bitmap: Bitmap? = null
    var mapScaleMetersPerPx: Double = 0.0    // 米/像素（旧 txt 格式兼容）

    // ---- 解析器（数据源） ----
    val parser = MapDataParser()

    // ---- G68：Paint 复用（批次4）——每帧构造对象 → 实例字段复用，仅按调用改色/样式。
    // 渲染为 UI 线程串行执行（单画布），字段复用无并发冲突；样式固定项在字段初始化时配置，
    // 颜色等调用期参数在 draw* 开头 set（setColor 为原生调用，零分配）。
    // ⚠️ 必须 by lazy：字段直接初始化会在 JVM 单元测试加载类时构造 android.graphics.Paint
    //    （android.jar stub → ExceptionInInitializerError，与 UnitRenderer 同坑）；惰性初始化后
    //    纯函数测试（如 DisplayCustomizationTest 调 darkerColor/withAlphaColor）不触发 Paint 构造。
    private val gridPaint by lazy { Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE } }
    private val bitmapPaint by lazy { Paint().apply { isFilterBitmap = true } }
    private val waterMinorPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; isFakeBoldText = true } }
    private val waterMajorPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; isFakeBoldText = true } }
    private val depthPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val depthLabelPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; isFakeBoldText = true } }
    private val landPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val landStrokePaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f } }
    private val borderPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f } }
    private val countryPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; isFakeBoldText = true } }
    private val cityPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f } }
    private val labelPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f } }
    private val boundaryPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f } }
    private val miscPaints by lazy { listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 60, 120, 200); style = Paint.Style.FILL },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 200, 60, 60); style = Paint.Style.FILL },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 200, 180, 60); style = Paint.Style.FILL }
    ) }

    // 便捷访问（保留旧调用点兼容）
    var mapWorldMinX: Long get() = parser.mapWorldMinX; set(v) { parser.mapWorldMinX = v }
    var mapWorldMinY: Long get() = parser.mapWorldMinY; set(v) { parser.mapWorldMinY = v }
    var boundaryLeft: Long get() = parser.boundaryLeft; set(v) { parser.boundaryLeft = v }
    var boundaryTop: Long get() = parser.boundaryTop; set(v) { parser.boundaryTop = v }
    var boundaryWidth: Long get() = parser.boundaryWidth; set(v) { parser.boundaryWidth = v }
    var boundaryHeight: Long get() = parser.boundaryHeight; set(v) { parser.boundaryHeight = v }
    var hasBoundary: Boolean get() = parser.hasBoundary; set(v) { parser.hasBoundary = v }
    var pendingBackgroundName: String?
        get() = parser.pendingBackgroundName
        set(v) { parser.pendingBackgroundName = v }

    fun loadMapImage(contentResolver: ContentResolver, uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                bitmap = BitmapFactory.decodeStream(stream)
                onRasterImageLoaded()
            }
        } catch (e: Exception) {
            // 图片解码失败不阻塞
        }
    }

    fun loadMapImage(inputStream: java.io.InputStream) {
        try {
            bitmap = BitmapFactory.decodeStream(inputStream)
            onRasterImageLoaded()
        } catch (e: Exception) {
            // 图片解码失败不阻塞
        }
    }

    /** 光栅底图加载后：把 CITY/COUNTRY 原始像素换算为「以图片中心为原点」的世界坐标 */
    private fun onRasterImageLoaded() {
        val bmp = bitmap ?: return
        if (!parser.hasBoundary && parser.mapScale > 0.0) {
            parser.applyRasterCenter(bmp.width, bmp.height)
        }
    }

    fun clearMap() {
        parser.clear()
        bitmap = null
        mapScaleMetersPerPx = 0.0
    }

    /** 解析官方 JSON 地图配置（委托 MapDataParser） */
    fun parseMapConfigJson(text: String) {
        bitmap = null
        parser.parse(text)
        // #7（G65）修复：删除 v0.6.0 遗留的残废公式
        //   mapScaleMetersPerPx = metersPerWorldUnit * 10 * (boundaryWidth / boundaryWidth.toDouble())
        //   其中 boundaryWidth/自身 恒为 1，该赋值恒产出 0.1852 且无意义——
        //   JSON 边界地图由 drawBitmap 的 hasBoundary 分支按 BoundaryRect 直接定位并提前 return，
        //   mapScaleMetersPerPx 仅用于旧 txt 格式（parseMapConfig），JSON 分支写它纯属死代码。
    }

    /** 兼容旧 txt 配置：MAP=xxx.png  SCALE=3.071(km/px) */
    fun parseMapConfig(text: String) {
        val map = Regex("MAP=(.+)").find(text)?.groupValues?.get(1)?.trim()
        val scale = Regex("SCALE=([\\d.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (scale != null) mapScaleMetersPerPx = scale * 1000.0
    }

    /**
     * 光栅地图定位：解析 SCALE（像素/海里）后，计算并写入底图比例尺。
     * 桌面语义：底图世界宽 = 图宽像素 ÷ SCALE（海里）× 100000。
     * drawBitmap 非边界分支 worldPerPx = mapScaleMetersPerPx / 1852 × 100000，
     * 令其 = 100000/SCALE → mapScaleMetersPerPx = 1852/SCALE（米/像素）。
     */
    fun applyRasterScale(scale: Double) {
        if (scale > 0.0) mapScaleMetersPerPx = 1852.0 / scale
    }

    /**
     * 绘制网格（G09：网格色读 PlayerSettings.gridColor；默认 = 原硬编码 argb(60,120,140,160)）。
     */
    fun drawGrid(canvas: Canvas, camera: Camera, canvasW: Int, canvasH: Int, gridColor: Long = 0x883C789C) {
        // G68：复用字段 paint（样式字段初始化已配），仅改色
        gridPaint.color = gridColor.toInt()
        val worldPerPx = 1.0f / camera.zoom
        val screenSpacing = 80f
        val worldSpacing = (worldPerPx * screenSpacing).toLong()
        val step = niceStep(worldSpacing)

        val topLeft = camera.screenToWorld(0f, 0f, canvasW, canvasH)
        val bottomRight = camera.screenToWorld(canvasW.toFloat(), canvasH.toFloat(), canvasW, canvasH)

        val startX = topLeft.first / step * step
        var gx = startX
        while (gx <= bottomRight.first) {
            val (sx, _) = camera.worldToScreen(gx, 0, canvasW, canvasH)
            canvas.drawLine(sx, 0f, sx, canvasH.toFloat(), gridPaint)
            gx += step
        }
        val startY = topLeft.second / step * step
        var gy = startY
        while (gy <= bottomRight.second) {
            val (_, sy) = camera.worldToScreen(0, gy, canvasW, canvasH)
            canvas.drawLine(0f, sy, canvasW.toFloat(), sy, gridPaint)
            gy += step
        }
    }

    private fun niceStep(raw: Long): Long {
        if (raw <= 0) return 100L
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble())))
        val norm = raw / mag
        return when {
            norm < 2 -> (2 * mag).toLong()
            norm < 5 -> (5 * mag).toLong()
            else -> (10 * mag).toLong()
        }
    }

    /** 若已加载地图位图且配置比例尺，绘制贴图（官方 JSON：按 BoundaryRect 定位；光栅：中心锚定世界原点） */
    fun drawBitmap(canvas: Canvas, camera: Camera, canvasW: Int, canvasH: Int) {
        val bmp = bitmap ?: return
        val p = parser
        if (p.hasBoundary) {
            val worldW = p.boundaryWidth * 10
            val worldH = p.boundaryHeight * 10
            val (sx0, sy0) = camera.worldToScreen(p.mapWorldMinX, p.mapWorldMinY + worldH, canvasW, canvasH)
            val screenW = (worldW * camera.zoom).toFloat()
            val screenH = (worldH * camera.zoom).toFloat()
            val rect = android.graphics.RectF(sx0, sy0, sx0 + screenW, sy0 + screenH)
            // G68：复用字段 paint（样式字段初始化已配）
            canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            return
        }
        if (mapScaleMetersPerPx <= 0.0) {
            val bm = android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            canvas.drawBitmap(bmp, null, bm, null)
            return
        }
        val metersPerWorldUnit = 1852.0
        val worldPerPx = mapScaleMetersPerPx / metersPerWorldUnit * 100000.0
        val mapWorldW = bmp.width * worldPerPx
        val mapWorldH = bmp.height * worldPerPx
        // 光栅底图：图片中心 = 世界 (0,0)。翻转后北边（大 Y）在上，
        // 左上角世界坐标 = (-W/2, +H/2)
        val (sx0, sy0) = camera.worldToScreen(
            (-mapWorldW / 2.0).toLong(), (mapWorldH / 2.0).toLong(), canvasW, canvasH
        )
        val screenW = (mapWorldW * camera.zoom).toFloat()
        val screenH = (mapWorldH * camera.zoom).toFloat()
        val rect = android.graphics.RectF(sx0, sy0, sx0 + screenW, sy0 + screenH)
        canvas.drawBitmap(bmp, null, rect, bitmapPaint)
    }

    /**
     * 绘制地图要素层（桌面版 Z 序：Waters→DepthPolys→Land→Countries→Cities→Border→Misc→标注）。
     *
     * G09：landColor=PlayerSettings.mapLandColor（陆地）、oceanColor=PlayerSettings.mapOceanColor（水域/深度）、
     * redForColor=PlayerSettings.redForColor（国界/边界框）。默认值 = 原硬编码色。
     */
    fun drawPolygons(canvas: Canvas, camera: Camera, canvasW: Int, canvasH: Int,
                     showCities: Boolean = true, showCountries: Boolean = true,
                     showWaters: Boolean = true, showDepths: Boolean = true,
                     landColor: Long = 0x7896AA82, oceanColor: Long = 0x40C8DCE8,
                     redForColor: Long = 0xFFC81E1E) {
        val p = parser
        val oceanInt = oceanColor.toInt()
        // 水域名（浅蓝半透明文字）；R4：ShowWaters 开关；G49：IsMajor 水域放大加粗；G09：色读 oceanColor
        if (showWaters) {
            // G68：复用字段 paint（textSize/粗体字段初始化已配），仅按调用改色
            waterMinorPaint.color = withAlphaColor(oceanInt, 160)
            waterMajorPaint.color = withAlphaColor(oceanInt, 190)
            for ((i, item) in p.waterLabels.withIndex()) {
                val (text, x, y) = item
                val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
                if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                    val paint = if (p.waterIsMajor.getOrNull(i) == true) waterMajorPaint else waterMinorPaint
                    canvas.drawText(text, sx, sy, paint)
                }
            }
        }

        // 深度色带（5 级：浅→深，alpha 递增）；R4：ShowDepths 开关；G09：由 oceanColor 派生
        if (showDepths) {
            val depthColors = List(5) { i ->
                withAlphaColor(darkerColor(oceanInt, 1f - i * 0.05f), 60 + i * 20)
            }
            for ((pts, lvl) in p.depthPolys) {
                val sp = screenPath(pts, camera, canvasW, canvasH)
                if (sp != null) {
                    // G68：原循环内每多边形 new Paint → 单 paint 按 lvl 改色（零分配）
                    depthPaint.color = depthColors[lvl % depthColors.size]
                    canvas.drawPath(sp, depthPaint)
                }
            }
            // G49：水深标注（"Depth Labels" 无坐标，画在对应索引深度多边形质心；越界即跳过）
            depthLabelPaint.color = withAlphaColor(darkerColor(oceanInt, 0.5f), 180)
            for (i in p.depthTexts.indices) {
                val pts = p.depthPolys.getOrNull(i)?.first ?: continue
                if (pts.isEmpty()) continue
                var sumX = 0.0
                var sumY = 0.0
                for ((px, py) in pts) { sumX += px; sumY += py }
                val (sx, sy) = camera.worldToScreen(
                    (sumX / pts.size).toLong(), (sumY / pts.size).toLong(), canvasW, canvasH
                )
                if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                    canvas.drawText(p.depthTexts[i], sx, sy, depthLabelPaint)
                }
            }
        }

        // 陆地（G09：填充/描边由 landColor 派生；描边 = RGB×0.6 加深、alpha 200）
        val landInt = landColor.toInt()
        // G68：复用字段 paint（样式字段初始化已配），仅改色
        landPaint.color = landInt
        landStrokePaint.color = withAlphaColor(darkerColor(landInt, 0.6f), 200)

        for (pts in p.landPolys) {
            val sp = screenPath(pts, camera, canvasW, canvasH)
            if (sp != null) {
                canvas.drawPath(sp, landPaint)
                canvas.drawPath(sp, landStrokePaint)
            }
        }
        // 国界线（G09：色读 redForColor）
        borderPaint.color = withAlphaColor(redForColor.toInt(), 200)
        for (pts in p.borderPolys) {
            val sp = screenPath(pts, camera, canvasW, canvasH)
            if (sp != null) canvas.drawPath(sp, borderPaint)
        }
        // 国家名；R4：ShowCountries 开关
        if (showCountries) {
            countryPaint.color = Color.argb(200, 90, 70, 40)
            for ((text, x, y) in p.countryLabels) {
                val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
                if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                    canvas.drawText(text, sx, sy, countryPaint)
                }
            }
        }
        // 城市（黑色小字）；R4：ShowCities 开关；G49：按 Position 锚点偏移文字
        if (showCities) {
            cityPaint.color = Color.argb(200, 40, 40, 40)
            for ((i, item) in p.cityLabels.withIndex()) {
                val (text, x, y) = item
                val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
                if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                    val pos = p.cityPositions.getOrNull(i) ?: ""
                    val tw = cityPaint.measureText(text)
                    val dx: Float
                    val dy: Float
                    when (pos) {
                        "Above Right" -> { dx = 4f; dy = -5f }
                        "Above Left" -> { dx = -tw - 4f; dy = -5f }
                        "Below Right" -> { dx = 4f; dy = cityPaint.textSize + 4f }
                        "Below Left" -> { dx = -tw - 4f; dy = cityPaint.textSize + 4f }
                        else -> { dx = 3f; dy = 3f }   // 旧格式无 Position：保持原左下偏移
                    }
                    canvas.drawText(text, sx + dx, sy + dy, cityPaint)
                }
            }
        }
        for ((pts, idx) in p.miscPolys) {
            val sp = screenPath(pts, camera, canvasW, canvasH)
            if (sp != null) {
                canvas.drawPath(sp, miscPaints[idx % miscPaints.size])
            }
        }
        // 文字标注
        labelPaint.color = Color.argb(200, 60, 60, 60)
        for ((text, x, y) in p.labels) {
            val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
            if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                canvas.drawText(text, sx + 4f, sy - 4f, labelPaint)
            }
        }
        // 地图边界框（桌面版 DrawMapBoundary；G09：色读 redForColor）
        if (p.hasBoundary) {
            val w = p.boundaryWidth * 10
            val h = p.boundaryHeight * 10
            // 翻转后：左上角 = 北边（minY + h）经 worldToScreen；尺寸 = 世界尺寸 × zoom
            // （与 drawBitmap 同构，避免翻转后 top>bottom 倒置）
            val (x0, y0) = camera.worldToScreen(p.mapWorldMinX, p.mapWorldMinY + h, canvasW, canvasH)
            val screenW = (w * camera.zoom).toFloat()
            val screenH = (h * camera.zoom).toFloat()
            // G68：复用字段 paint（样式字段初始化已配），仅改色
            boundaryPaint.color = withAlphaColor(redForColor.toInt(), 220)
            canvas.drawRect(x0, y0, x0 + screenW, y0 + screenH, boundaryPaint)
        }
    }

    // ---- P3-4：多边形路径复用（G68 补齐）——screenPath 每多边形 new Path 改字段复用。
    // 主线程串行绘制，返回后立即 drawPath，复用无冲突；by lazy 保持 JVM 可测。
    private val reusableScreenPath by lazy { Path() }

    /** 世界坐标点列表 → 屏幕 Path（跳过视口外大块；大路径降采样）。返回复用实例，调用方须立即绘制。 */
    private fun screenPath(pts: List<Pair<Long, Long>>, camera: Camera, w: Int, h: Int): Path? {
        if (pts.isEmpty()) return null
        val sp = reusableScreenPath
        sp.reset()
        var inView = false
        val n = pts.size
        // 大路径（>2000 点）降采样步长
        val step = if (n > 2000) n / 2000 else 1
        var first = true
        for (i in pts.indices step step) {
            val (wx, wy) = pts[i]
            val (sx, sy) = camera.worldToScreen(wx, wy, w, h)
            if (sx in -1000f..w + 1000f && sy in -1000f..h + 1000f) inView = true
            if (first) { sp.moveTo(sx, sy); first = false } else sp.lineTo(sx, sy)
        }
        sp.close()
        return if (inView) sp else null
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        /**
         * G09：ARGB 颜色加深（RGB×factor，alpha 不变）。
         * 纯位运算实现（不依赖 android.graphics.Color，JVM 单测可直接调用）。
         * @param factor 0..1（越小越深；1 保持不变）
         */
        fun darkerColor(color: Int, factor: Float): Int {
            val f = factor.coerceIn(0f, 1f)
            val a = (color ushr 24) and 0xFF
            val r = ((color ushr 16) and 0xFF)
            val g = ((color ushr 8) and 0xFF)
            val b = color and 0xFF
            return (a shl 24) or ((r * f).toInt() shl 16) or ((g * f).toInt() shl 8) or (b * f).toInt()
        }

        /** G09：替换 ARGB 的 alpha 分量（0..255）。纯位运算实现，可单测。 */
        fun withAlphaColor(color: Int, alpha: Int): Int {
            val a = alpha.coerceIn(0, 255)
            return (a shl 24) or (color and 0x00FFFFFF)
        }
    }
}
