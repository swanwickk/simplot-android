package com.simplot.android.render

import kotlin.math.roundToLong

/**
 * 视口数学（纯 Kotlin，无 Android/Compose 依赖 → 可纯 JVM 单测）。
 *
 * 世界坐标 = 存档文件坐标（海里×100000）；屏幕坐标 = Canvas 像素。
 * 变换：screenX = (worldX - centerX) * zoom + canvasWidth/2
 *
 * 所有函数纯计算，不持有状态；状态由 [Camera]（Compose snapshot）持有。
 */
object CameraMath {

    /** 世界坐标 → 屏幕坐标 */
    fun worldToScreen(
        wx: Long, wy: Long, centerX: Long, centerY: Long, zoom: Float,
        canvasW: Int, canvasH: Int
    ): Pair<Float, Float> {
        val sx = ((wx - centerX) * zoom) + canvasW / 2f
        val sy = ((wy - centerY) * zoom) + canvasH / 2f
        return sx to sy
    }

    /** 屏幕坐标 → 世界坐标 */
    fun screenToWorld(
        sx: Float, sy: Float, centerX: Long, centerY: Long, zoom: Float,
        canvasW: Int, canvasH: Int
    ): Pair<Long, Long> {
        val wx = ((sx - canvasW / 2f) / zoom).roundToLong() + centerX
        val wy = ((sy - canvasH / 2f) / zoom).roundToLong() + centerY
        return wx to wy
    }

    /** 以屏幕锚点缩放：返回新 (zoom, centerX, centerY)，保持锚点世界坐标不动 */
    fun zoomAt(
        factor: Float, anchorSx: Float, anchorSy: Float,
        zoom: Float, centerX: Long, centerY: Long,
        minZoom: Float, maxZoom: Float, canvasW: Int, canvasH: Int
    ): Triple<Float, Long, Long> {
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        if (newZoom == zoom) return Triple(zoom, centerX, centerY)
        val before = screenToWorld(anchorSx, anchorSy, centerX, centerY, zoom, canvasW, canvasH)
        val after = screenToWorld(anchorSx, anchorSy, centerX, centerY, newZoom, canvasW, canvasH)
        return Triple(newZoom, centerX + before.first - after.first, centerY + before.second - after.second)
    }

    /** 平移（屏幕像素偏移）→ 新中心 */
    fun pan(deltaSx: Float, deltaSy: Float, centerX: Long, centerY: Long, zoom: Float): Pair<Long, Long> {
        return (centerX - (deltaSx / zoom).roundToLong()) to (centerY - (deltaSy / zoom).roundToLong())
    }

    /** 自适应缩放使世界范围可见 → (zoom, centerX, centerY) */
    fun fitBounds(
        minX: Long, maxX: Long, minY: Long, maxY: Long,
        canvasW: Int, canvasH: Int,
        minZoom: Float, maxZoom: Float, paddingPx: Float = 80f
    ): Triple<Float, Long, Long> {
        val w = (maxX - minX).coerceAtLeast(1)
        val h = (maxY - minY).coerceAtLeast(1)
        val zoomX = (canvasW - 2 * paddingPx) / w.toFloat()
        val zoomY = (canvasH - 2 * paddingPx) / h.toFloat()
        val zoom = (minOf(zoomX, zoomY)).coerceIn(minZoom, maxZoom)
        return Triple(zoom, (minX + maxX) / 2, (minY + maxY) / 2)
    }
}
