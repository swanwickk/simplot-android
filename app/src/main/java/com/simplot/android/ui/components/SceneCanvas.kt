package com.simplot.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.render.Camera
import com.simplot.android.render.MapRenderer
import com.simplot.android.render.TrackRenderer
import com.simplot.android.render.UnitRenderer
import kotlin.math.abs

/**
 * 海图主画布（触摸交互核心）：
 * - 单指拖拽平移
 * - 双指捏合缩放
 * - 轻点选择单位
 * - 长按弹出单位编辑（回调上层）
 */
@Composable
fun SceneCanvas(
    file: ScenarioFile,
    camera: Camera,
    mapRenderer: MapRenderer,
    selectedUnitId: String?,
    onSelect: (String?) -> kotlin.Unit,
    onLongPress: (Unit) -> kotlin.Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) {
                        // 以画布中心为锚缩放
                        camera.zoomAt(zoom, size.width / 2f, size.height / 2f, size.width.toInt(), size.height.toInt())
                    } else if (abs(pan.x) > 1f || abs(pan.y) > 1f) {
                        camera.pan(pan.x, pan.y)
                    }
                }
            }
            .pointerInput(file) {
                detectTapGestures(
                    onTap = { pos ->
                        // 命中检测：点选单位
                        val hit = hitTest(file.units, camera, pos, size.width.toInt(), size.height.toInt())
                        onSelect(hit?.idNum)
                    },
                    onLongPress = { pos ->
                        val hit = hitTest(file.units, camera, pos, size.width.toInt(), size.height.toInt())
                        if (hit != null) onLongPress(hit)
                    }
                )
            }
    ) {
        val w = size.width.toInt()
        val h = size.height.toInt()

        // 背景
        drawRect(androidx.compose.ui.graphics.Color(0xFFF0F2F5))

        // 地图贴图（如有）
        mapRenderer.drawBitmap(drawContext.canvas.nativeCanvas, camera, w, h)

        // 网格
        mapRenderer.drawGrid(drawContext.canvas.nativeCanvas, camera, w, h)

        // 轨迹 + 单位
        for (u in file.units) {
            TrackRenderer.draw(drawContext.canvas.nativeCanvas, u, camera, w, h)
        }
        for (u in file.units) {
            val (sx, sy) = camera.worldToScreen(u.x, u.y, w, h)
            if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                UnitRenderer.draw(drawContext.canvas.nativeCanvas, u, sx, sy, selected = u.idNum == selectedUnitId)
                drawUnitLabel(drawContext.canvas.nativeCanvas, u, sx, sy)
            }
        }

        // 坐标比例尺条（右下角）
        drawScaleBar(drawContext.canvas.nativeCanvas, w, h)
    }
}

/** 标签绘制（名称 + 航向航速） */
private fun drawUnitLabel(canvas: android.graphics.Canvas, u: Unit, sx: Float, sy: Float) {
    val tag = u.textTags
    if (!tag.tagName && !tag.tagCourseSpeed) return
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = UnitRenderer.colorOf(u.side)
        textSize = 11f
    }
    val parts = mutableListOf<String>()
    if (tag.tagName && u.name.isNotEmpty()) parts.add(u.name)
    if (tag.tagCourseSpeed) {
        parts.add("${u.speedKnots().toInt()}节/${u.courseDeg().toInt()}°")
    }
    val text = parts.joinToString("  ")
    if (text.isNotEmpty()) {
        canvas.drawText(text, sx + 10f, sy - 8f, paint)
    }
}

/** 右下角比例尺条：50 海里示意 */
private fun drawScaleBar(canvas: android.graphics.Canvas, w: Int, h: Int) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    val x0 = w - 90f
    val y0 = h - 30f
    canvas.drawLine(x0, y0, x0 + 70f, y0, paint)
    canvas.drawText("50 nmi", x0, y0 - 6f, paint)
}

/** 命中检测：返回被点中的单位（若有） */
internal fun hitTest(units: List<Unit>, camera: Camera, pos: Offset, w: Int, h: Int): Unit? {
    val hitRadius = 20f
    var best: Unit? = null
    var bestDist = hitRadius * hitRadius
    for (u in units) {
        val (sx, sy) = camera.worldToScreen(u.x, u.y, w, h)
        val dx = sx - pos.x
        val dy = sy - pos.y
        val d = dx * dx + dy * dy
        if (d < bestDist) {
            bestDist = d
            best = u
        }
    }
    return best
}
