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
 */
class MapRenderer {

    var bitmap: Bitmap? = null
    var mapScaleMetersPerPx: Double = 0.0    // 米/像素（旧 txt 格式兼容）

    // ---- 解析器（数据源） ----
    val parser = MapDataParser()

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
        bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
    }

    /** 解析官方 JSON 地图配置（委托 MapDataParser） */
    fun parseMapConfigJson(text: String) {
        parser.parse(text)
        // 旧字段兼容（米/像素 → 由 BoundaryRect 反推）
        if (parser.hasBoundary) {
            val metersPerWorldUnit = 1852.0 / 100000.0
            mapScaleMetersPerPx = metersPerWorldUnit * 10 * (parser.boundaryWidth / (parser.boundaryWidth.toDouble()))
        }
    }

    /** 兼容旧 txt 配置：MAP=xxx.png  SCALE=3.071(km/px) */
    fun parseMapConfig(text: String) {
        val map = Regex("MAP=(.+)").find(text)?.groupValues?.get(1)?.trim()
        val scale = Regex("SCALE=([\\d.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (scale != null) mapScaleMetersPerPx = scale * 1000.0
    }

    fun drawGrid(canvas: Canvas, camera: Camera, canvasW: Int, canvasH: Int) {
        val paint = Paint().apply {
            color = Color.argb(60, 120, 140, 160)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
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
            canvas.drawLine(sx, 0f, sx, canvasH.toFloat(), paint)
            gx += step
        }
        val startY = topLeft.second / step * step
        var gy = startY
        while (gy <= bottomRight.second) {
            val (_, sy) = camera.worldToScreen(0, gy, canvasW, canvasH)
            canvas.drawLine(0f, sy, canvasW.toFloat(), sy, paint)
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

    /** 若已加载地图位图且配置比例尺，绘制贴图（官方 JSON：按 BoundaryRect 定位） */
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
            val paint = Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(bmp, null, rect, paint)
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
        val (sx0, sy0) = camera.worldToScreen(p.mapWorldMinX, p.mapWorldMinY, canvasW, canvasH)
        val screenW = (mapWorldW * camera.zoom).toFloat()
        val screenH = (mapWorldH * camera.zoom).toFloat()
        val rect = android.graphics.RectF(sx0, sy0, sx0 + screenW, sy0 + screenH)
        val paint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bmp, null, rect, paint)
    }

    /** 绘制地图要素层（桌面版 Z 序：Waters→DepthPolys→Land→Countries→Cities→Border→Misc→标注） */
    fun drawPolygons(canvas: Canvas, camera: Camera, canvasW: Int, canvasH: Int) {
        val p = parser
        // 水域名（浅蓝半透明文字）
        val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 70, 120, 180)
            textSize = 14f
            isFakeBoldText = true
        }
        for ((text, x, y) in p.waterLabels) {
            val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
            if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                canvas.drawText(text, sx, sy, waterPaint)
            }
        }

        // 深度色带（5 级：浅蓝→深蓝）
        val depthColors = listOf(
            Color.argb(60, 200, 220, 240),
            Color.argb(80, 160, 195, 225),
            Color.argb(100, 120, 170, 210),
            Color.argb(120, 85, 145, 195),
            Color.argb(140, 50, 115, 180)
        )
        for ((pts, lvl) in p.depthPolys) {
            val sp = screenPath(pts, camera, canvasW, canvasH)
            if (sp != null) {
                canvas.drawPath(sp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = depthColors[lvl % depthColors.size]
                    style = Paint.Style.FILL
                })
            }
        }

        val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 150, 170, 130)
            style = Paint.Style.FILL
        }
        val landStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 90, 110, 70)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val miscPaints = listOf(
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 60, 120, 200); style = Paint.Style.FILL },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 200, 60, 60); style = Paint.Style.FILL },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 200, 180, 60); style = Paint.Style.FILL }
        )

        for (pts in p.landPolys) {
            val sp = screenPath(pts, camera, canvasW, canvasH)
            if (sp != null) {
                canvas.drawPath(sp, landPaint)
                canvas.drawPath(sp, landStroke)
            }
        }
        // 国界线（红色描边）
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 180, 60, 60)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        for (pts in p.borderPolys) {
            val sp = screenPath(pts, camera, canvasW, canvasH)
            if (sp != null) canvas.drawPath(sp, borderPaint)
        }
        // 国家名
        val countryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 90, 70, 40)
            textSize = 13f
            isFakeBoldText = true
        }
        for ((text, x, y) in p.countryLabels) {
            val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
            if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                canvas.drawText(text, sx, sy, countryPaint)
            }
        }
        // 城市（黑色小字）
        val cityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 40, 40, 40)
            textSize = 11f
        }
        for ((text, x, y) in p.cityLabels) {
            val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
            if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                canvas.drawText(text, sx + 3f, sy + 3f, cityPaint)
            }
        }
        for ((pts, idx) in p.miscPolys) {
            val sp = screenPath(pts, camera, canvasW, canvasH)
            if (sp != null) {
                canvas.drawPath(sp, miscPaints[idx % miscPaints.size])
            }
        }
        // 文字标注
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 60, 60, 60)
            textSize = 12f
        }
        for ((text, x, y) in p.labels) {
            val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
            if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                canvas.drawText(text, sx + 4f, sy - 4f, labelPaint)
            }
        }
        // 地图边界框（桌面版 DrawMapBoundary）
        if (p.hasBoundary) {
            val w = p.boundaryWidth * 10
            val h = p.boundaryHeight * 10
            val (x0, y0) = camera.worldToScreen(p.mapWorldMinX, p.mapWorldMinY, canvasW, canvasH)
            val (x1, y1) = camera.worldToScreen(p.mapWorldMinX + w, p.mapWorldMinY + h, canvasW, canvasH)
            val rect = android.graphics.RectF(x0, y0, x1, y1)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 120, 60, 60)
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })
        }
    }

    /** 世界坐标点列表 → 屏幕 Path（跳过视口外大块；大路径降采样） */
    private fun screenPath(pts: List<Pair<Long, Long>>, camera: Camera, w: Int, h: Int): Path? {
        if (pts.isEmpty()) return null
        val sp = Path()
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
}
