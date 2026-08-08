package com.simplot.android.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToLong

/**
 * 海图画布视口（相机）：管理世界坐标 ↔ 屏幕坐标映射、缩放、平移
 *
 * 世界坐标 = 存档文件坐标（海里×100000）
 * 屏幕坐标 = Canvas 像素
 *
 * 变换：screenX = (worldX - centerWorldX) * zoom + canvasWidth/2
 *
 * ⚠️ 字段均为 Compose snapshot state：手势/引擎修改后自动触发画布重组重绘
 * （此前是普通 var，pan/zoom 后 Canvas 不重绘 → 拖动缩放“没反应”的根因）
 */
class Camera {
    var zoom by mutableFloatStateOf(0.0015f)                    // 世界单位 → 像素比例（初始近似）
    var centerWorldX by mutableLongStateOf(0L)
    var centerWorldY by mutableLongStateOf(0L)

    // 最小/最大缩放
    var minZoom by mutableFloatStateOf(0.00001f)
    var maxZoom by mutableFloatStateOf(0.05f)

    /** 世界坐标 → 屏幕坐标 */
    fun worldToScreen(wx: Long, wy: Long, canvasW: Int, canvasH: Int): Pair<Float, Float> {
        val sx = ((wx - centerWorldX) * zoom) + canvasW / 2f
        val sy = ((wy - centerWorldY) * zoom) + canvasH / 2f
        return sx to sy
    }

    /** 屏幕坐标 → 世界坐标 */
    fun screenToWorld(sx: Float, sy: Float, canvasW: Int, canvasH: Int): Pair<Long, Long> {
        val wx = ((sx - canvasW / 2f) / zoom).roundToLong() + centerWorldX
        val wy = ((sy - canvasH / 2f) / zoom).roundToLong() + centerWorldY
        return wx to wy
    }

    /** 以屏幕某点为锚点缩放（双指捏合/双击） */
    fun zoomAt(factor: Float, anchorSx: Float, anchorSy: Float, canvasW: Int, canvasH: Int) {
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        if (newZoom == zoom) return
        // 保持锚点对应的世界坐标不动
        val worldBefore = screenToWorld(anchorSx, anchorSy, canvasW, canvasH)
        zoom = newZoom
        val worldAfter = screenToWorld(anchorSx, anchorSy, canvasW, canvasH)
        centerWorldX += worldBefore.first - worldAfter.first
        centerWorldY += worldBefore.second - worldAfter.second
    }

    /** 平移（屏幕像素偏移） */
    fun pan(deltaSx: Float, deltaSy: Float) {
        centerWorldX -= (deltaSx / zoom).roundToLong()
        centerWorldY -= (deltaSy / zoom).roundToLong()
    }

    /** 居中到某世界坐标 */
    fun centerOn(wx: Long, wy: Long) {
        centerWorldX = wx
        centerWorldY = wy
    }

    /** 自适应缩放使世界范围可见 */
    fun fitBounds(minX: Long, maxX: Long, minY: Long, maxY: Long, canvasW: Int, canvasH: Int, paddingPx: Float = 80f) {
        val w = (maxX - minX).coerceAtLeast(1)
        val h = (maxY - minY).coerceAtLeast(1)
        val zoomX = (canvasW - 2 * paddingPx) / w.toFloat()
        val zoomY = (canvasH - 2 * paddingPx) / h.toFloat()
        zoom = (minOf(zoomX, zoomY)).coerceIn(minZoom, maxZoom)
        centerOn((minX + maxX) / 2, (minY + maxY) / 2)
    }
}
