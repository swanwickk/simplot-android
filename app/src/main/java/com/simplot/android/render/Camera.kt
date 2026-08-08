package com.simplot.android.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * 海图画布视口（相机）：Compose snapshot 状态包装 + 委托 [CameraMath] 纯数学。
 *
 * ⚠️ 字段为 Compose snapshot state：手势/引擎修改后自动触发画布重组重绘
 * （此前是普通 var，pan/zoom 后 Canvas 不重绘 → 拖动缩放"没反应"的根因）。
 * 数学逻辑全部在 [CameraMath]（纯 Kotlin，可单测），本类只做状态读写。
 */
class Camera {
    var zoom by mutableFloatStateOf(0.0015f)                    // 世界单位 → 像素比例（初始近似）
    var centerWorldX by mutableLongStateOf(0L)
    var centerWorldY by mutableLongStateOf(0L)

    // 最小/最大缩放
    var minZoom by mutableFloatStateOf(0.00001f)
    var maxZoom by mutableFloatStateOf(0.05f)

    /** 世界坐标 → 屏幕坐标 */
    fun worldToScreen(wx: Long, wy: Long, canvasW: Int, canvasH: Int): Pair<Float, Float> =
        CameraMath.worldToScreen(wx, wy, centerWorldX, centerWorldY, zoom, canvasW, canvasH)

    /** 屏幕坐标 → 世界坐标 */
    fun screenToWorld(sx: Float, sy: Float, canvasW: Int, canvasH: Int): Pair<Long, Long> =
        CameraMath.screenToWorld(sx, sy, centerWorldX, centerWorldY, zoom, canvasW, canvasH)

    /** 以屏幕某点为锚点缩放（双指捏合/双击） */
    fun zoomAt(factor: Float, anchorSx: Float, anchorSy: Float, canvasW: Int, canvasH: Int) {
        val (nz, nx, ny) = CameraMath.zoomAt(
            factor, anchorSx, anchorSy, zoom, centerWorldX, centerWorldY,
            minZoom, maxZoom, canvasW, canvasH
        )
        zoom = nz; centerWorldX = nx; centerWorldY = ny
    }

    /** 平移（屏幕像素偏移） */
    fun pan(deltaSx: Float, deltaSy: Float) {
        val (nx, ny) = CameraMath.pan(deltaSx, deltaSy, centerWorldX, centerWorldY, zoom)
        centerWorldX = nx; centerWorldY = ny
    }

    /** 居中到某世界坐标 */
    fun centerOn(wx: Long, wy: Long) {
        centerWorldX = wx
        centerWorldY = wy
    }

    /** 自适应缩放使世界范围可见 */
    fun fitBounds(minX: Long, maxX: Long, minY: Long, maxY: Long, canvasW: Int, canvasH: Int, paddingPx: Float = 80f) {
        val (nz, nx, ny) = CameraMath.fitBounds(minX, maxX, minY, maxY, canvasW, canvasH, minZoom, maxZoom, paddingPx)
        zoom = nz; centerWorldX = nx; centerWorldY = ny
    }
}
