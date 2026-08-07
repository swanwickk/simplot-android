package com.simplot.android.render

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri

/**
 * 地图渲染器：网格背景 + 位图地图贴图
 *
 * 支持：
 * - 无地图（TypeOfMap=0）：画网格 + 距离刻度
 * - 位图地图：按 MapFileName 加载 PNG + txt 配置（MAP/SCALE）
 *   地图范围 = 图片尺寸 × SCALE(km/px) × 1000（米）
 */
class MapRenderer {

    var bitmap: Bitmap? = null
    var mapScaleMetersPerPx: Double = 0.0    // 米/像素
    var mapWorldMinX = 0L
    var mapWorldMinY = 0L

    fun loadMapImage(contentResolver: ContentResolver, uri: Uri) {
        bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
    }

    /** 解析地图配置文本（Maps 目录下 txt 文件）：MAP=xxx.png  SCALE=3.071(km/px) */
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

    /** 若已加载地图位图且配置比例尺，绘制贴图 */
    fun drawBitmap(canvas: Canvas, camera: Camera, canvasW: Int, canvasH: Int) {
        val bmp = bitmap ?: return
        if (mapScaleMetersPerPx <= 0.0) {
            // 无比例尺：直接以 1:1 世界单位贴图（退化处理）
            val bm = android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            canvas.drawBitmap(bmp, null, bm, null)
            return
        }
        // 地图像素 → 世界单位：1px = mapScaleMetersPerPx 米；世界单位=海里×100000
        val metersPerWorldUnit = 1852.0 // 1 海里 = 1852 米
        val worldPerPx = mapScaleMetersPerPx / metersPerWorldUnit * 100000.0
        val mapWorldW = bmp.width * worldPerPx
        val mapWorldH = bmp.height * worldPerPx
        // 地图左上角世界坐标
        val tlWorldX = mapWorldMinX
        val tlWorldY = mapWorldMinY
        val (sx0, sy0) = camera.worldToScreen(tlWorldX, tlWorldY, canvasW, canvasH)
        val screenW = (mapWorldW * camera.zoom).toFloat()
        val screenH = (mapWorldH * camera.zoom).toFloat()
        val rect = android.graphics.RectF(sx0, sy0, sx0 + screenW, sy0 + screenH)
        val paint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bmp, null, rect, paint)
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
    }
}
