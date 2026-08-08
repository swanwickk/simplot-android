package com.simplot.android.render

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 地图渲染器：网格背景 + 位图地图贴图 + 陆地多边形。
 *
 * 官方地图配置（MapMaker JSON，实测 Iron Bottom Sound JJWS1.json）：
 * - BackgroundFileName: 背景图文件名（与配置同目录）
 * - Scale: 比例（次要，渲染以 BoundaryRect 为准）
 * - BoundaryRect: {Left, Top, Width, Height} —— 地图世界范围
 *   ⚠️ 坐标语义：地图坐标为 海里×10000，存档坐标为 海里×100000
 *   （实测 Henderson Field：地图 (121574,-158178) ↔ 存档 (1214285,-1581818)，正好 ×10）
 * - Land Polygons / Misc Polygons: 陆地/覆盖多边形（Path 为 [x1,y1,x2,y2,...]）
 * - Depth Labels / Misc Labels: 文字标注（X/Y 为锚点）
 *
 * 渲染时全部坐标 ×10 转换到存档世界坐标。
 */
class MapRenderer {

    var bitmap: Bitmap? = null
    var mapScaleMetersPerPx: Double = 0.0    // 米/像素（旧 txt 格式兼容）
    var mapWorldMinX = 0L
    var mapWorldMinY = 0L

    // ---- 官方 JSON 配置（BoundaryRect，存档世界坐标 = 地图坐标×10） ----
    var boundaryLeft = 0L
    var boundaryTop = 0L
    var boundaryWidth = 0L
    var boundaryHeight = 0L
    var hasBoundary = false

    // 陆地多边形（存档世界坐标）
    private val landPaths = mutableListOf<Path>()
    private val miscPaths = mutableListOf<Pair<Path, Int>>()   // Path, colorName 索引
    private val labels = mutableListOf<Triple<String, Long, Long>>()  // text, x, y

    fun loadMapImage(contentResolver: ContentResolver, uri: Uri) {
        bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
    }

    /**
     * 解析官方 JSON 地图配置（MapMaker 输出）。坐标 ×10 转为存档世界坐标。
     */
    fun parseMapConfigJson(text: String) {
        val root = try { JsonParser.parseString(text).asJsonObject } catch (e: Exception) { return }
        // BoundaryRect
        root.getAsJsonObject("BoundaryRect")?.let { b ->
            boundaryLeft = b.get("Left")?.asLong ?: 0L
            boundaryTop = b.get("Top")?.asLong ?: 0L
            boundaryWidth = b.get("Width")?.asLong ?: 0L
            boundaryHeight = b.get("Height")?.asLong ?: 0L
            if (boundaryWidth > 0 && boundaryHeight > 0) {
                hasBoundary = true
                // 地图世界范围（存档坐标 = 地图坐标 × 10）
                mapWorldMinX = boundaryLeft * 10
                mapWorldMinY = (boundaryTop - boundaryHeight) * 10
                // 旧字段兼容（米/像素 → 由 BoundaryRect 反推）
                val metersPerWorldUnit = 1852.0 / 100000.0
                mapScaleMetersPerPx = metersPerWorldUnit * 10 * (boundaryWidth / (boundaryWidth.toDouble()))
            }
        }
        // 背景图
        root.get("BackgroundFileName")?.takeIf { !it.isJsonNull }?.let {
            pendingBackgroundName = it.asString
        }
        // 陆地多边形
        landPaths.clear()
        parsePolygons(root.getAsJsonArray("Land Polygons")) { path, colorIdx ->
            landPaths.add(path)
        }
        // 覆盖多边形（机场、标注区等）
        miscPaths.clear()
        parsePolygons(root.getAsJsonArray("Misc Polygons")) { path, colorIdx ->
            miscPaths.add(path to colorIdx)
        }
        // 文字标注
        labels.clear()
        root.getAsJsonArray("Misc Labels")?.forEach { el ->
            val o = el.asJsonObject
            val name = o.get("Name")?.asString ?: return@forEach
            val x = (o.get("X")?.asLong ?: 0L) * 10
            val y = (o.get("Y")?.asLong ?: 0L) * 10
            labels.add(Triple(name, x, y))
        }
    }

    private fun parsePolygons(arr: JsonArray?, sink: (Path, Int) -> Unit) {
        if (arr == null) return
        var colorIdx = 0
        arr.forEach { el ->
            val o = el.asJsonObject
            val pathArr = o.getAsJsonArray("Path") ?: return@forEach
            val path = Path()
            var first = true
            for (i in 0 until pathArr.size() step 2) {
                val x = pathArr.get(i).asLong * 10
                val y = pathArr.get(i + 1).asLong * 10
                if (first) { path.moveTo(x.toFloat(), y.toFloat()); first = false }
                else path.lineTo(x.toFloat(), y.toFloat())
            }
            path.close()
            sink(path, colorIdx)
            colorIdx++
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
        // 计算网格间距（世界单位）—— 使屏幕间距 ≈ 80px
        val worldPerPx = 1.0 / camera.zoom
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

        if (hasBoundary) {
            // 官方格式：地图世界范围 = BoundaryRect（×10），左上角 = (mapWorldMinX, mapWorldMinY)
            val worldW = boundaryWidth * 10
            val worldH = boundaryHeight * 10
            val (sx0, sy0) = camera.worldToScreen(mapWorldMinX, mapWorldMinY + worldH, canvasW, canvasH)
            val screenW = (worldW * camera.zoom).toFloat()
            val screenH = (worldH * camera.zoom).toFloat()
            val rect = android.graphics.RectF(sx0, sy0, sx0 + screenW, sy0 + screenH)
            val paint = Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(bmp, null, rect, paint)
            return
        }

        if (mapScaleMetersPerPx <= 0.0) {
            // 无比例尺：直接以 1:1 世界单位贴图（退化处理）
            val bm = android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            canvas.drawBitmap(bmp, null, bm, null)
            return
        }
        // 旧格式：地图像素 → 世界单位：1px = mapScaleMetersPerPx 米；世界单位=海里×100000
        val metersPerWorldUnit = 1852.0 // 1 海里 = 1852 米
        val worldPerPx = mapScaleMetersPerPx / metersPerWorldUnit * 100000.0
        val mapWorldW = bmp.width * worldPerPx
        val mapWorldH = bmp.height * worldPerPx
        val (sx0, sy0) = camera.worldToScreen(mapWorldMinX, mapWorldMinY, canvasW, canvasH)
        val screenW = (mapWorldW * camera.zoom).toFloat()
        val screenH = (mapWorldH * camera.zoom).toFloat()
        val rect = android.graphics.RectF(sx0, sy0, sx0 + screenW, sy0 + screenH)
        val paint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bmp, null, rect, paint)
    }

    /** 绘制陆地多边形（官方 JSON 格式），颜色：陆地=灰绿，覆盖=蓝/红半透明 */
    fun drawPolygons(canvas: Canvas, camera: Camera, canvasW: Int, canvasH: Int) {
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

        for (path in landPaths) {
            val sp = screenPath(path, camera, canvasW, canvasH)
            if (sp != null) {
                canvas.drawPath(sp, landPaint)
                canvas.drawPath(sp, landStroke)
            }
        }
        miscPaths.forEach { (path, idx) ->
            val sp = screenPath(path, camera, canvasW, canvasH)
            if (sp != null) {
                canvas.drawPath(sp, miscPaints[idx % miscPaints.size])
            }
        }
        // 文字标注
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 60, 60, 60)
            textSize = 12f
        }
        for ((text, x, y) in labels) {
            val (sx, sy) = camera.worldToScreen(x, y, canvasW, canvasH)
            if (sx in -100f..canvasW + 100f && sy in -100f..canvasH + 100f) {
                canvas.drawText(text, sx + 4f, sy - 4f, labelPaint)
            }
        }
    }

    /** 世界坐标 Path → 屏幕坐标 Path（跳过视口外的大块） */
    private fun screenPath(worldPath: Path, camera: Camera, w: Int, h: Int): Path? {
        val sp = Path()
        val approx = android.graphics.PathMeasure(worldPath, false)
        val len = approx.length
        val step = 2000f  // 采样步长（世界单位），大路径降采样
        val n = ((len / step).toInt() + 1).coerceAtMost(2000)
        var inView = false
        for (i in 0..n) {
            val dist = len * i / n.toFloat()
            val pts = FloatArray(2)
            approx.getPosTan(dist, pts, null)
            val (sx, sy) = camera.worldToScreen(pts[0].toLong(), pts[1].toLong(), w, h)
            if (sx in -1000f..w + 1000f && sy in -1000f..h + 1000f) inView = true
            if (i == 0) sp.moveTo(sx, sy) else sp.lineTo(sx, sy)
        }
        sp.close()
        return if (inView) sp else null
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
    }

    /** 待加载背景图文件名（官方配置） */
    var pendingBackgroundName: String? = null
}
