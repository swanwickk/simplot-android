package com.simplot.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.util.CoordUtil
import com.simplot.android.engine.ReplayEngine
import com.simplot.android.render.ArcRenderer
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
 * - 回放模式：传入 [replayFrame] 时按帧位置渲染（不响应点选编辑）
 */
@Composable
fun SceneCanvas(
    file: ScenarioFile,
    camera: Camera,
    mapRenderer: MapRenderer,
    selectedUnitId: String?,
    onSelect: (String?) -> kotlin.Unit,
    onLongPress: (Unit) -> kotlin.Unit,
    modifier: Modifier = Modifier,
    replayFrame: ReplayEngine.Frame? = null,
    tick: Int = 0,
    measureMode: Boolean = false,
    onMeasureDone: (() -> kotlin.Unit)? = null
) {
    val replaying = replayFrame != null
    // 读取 tick 建立重组依赖：回合推进/编辑后重绘（file 引用不变）
    @Suppress("UNUSED_EXPRESSION") tick
    // 仅在新场景首次布局时自适应视野；用户手动平移/缩放后不再重置
    var fittedFile by remember(file) { mutableStateOf<ScenarioFile?>(null) }
    // 测量状态（桌面版 Measurement.AddNewMeasure/ExtendMeasure）
    var measureStart by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var measureEnd by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    Canvas(
        modifier = modifier
            .onSizeChanged { size ->
                // 仅新场景首次布局时自适应视野（场景单位范围），避免覆盖用户手势
                if (size.width > 0 && size.height > 0 && fittedFile !== file) {
                    fittedFile = file
                    val xs = file.units.map { it.x }
                    val ys = file.units.map { it.y }
                    if (xs.isNotEmpty()) {
                        camera.fitBounds(xs.min(), xs.max(), ys.min(), ys.max(), size.width, size.height)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // 缩放：以双指中心为锚点（阈值判断，避免浮点噪声吞掉 pan）
                    if (abs(zoom - 1f) > 0.001f) {
                        camera.zoomAt(zoom, centroid.x, centroid.y, size.width, size.height)
                    }
                    // 平移：始终生效（单指拖动 / 双指缩放时跟随）
                    // ⚠️ 测量模式下禁用单指平移（否则拖动画线与地图拖动冲突，C1 修复）
                    val measuring = measureMode
                    if (!measuring && (abs(pan.x) > 0.5f || abs(pan.y) > 0.5f)) {
                        camera.pan(pan.x, pan.y)
                    }
                }
            }
            .pointerInput(file) {
                if (replaying) return@pointerInput   // 回放模式下不响应点选
                if (measureMode) {
                    // 测量模式：按下=起点，拖拽=延伸，抬起=完成（桌面版 AddNewMeasure/ExtendMeasure）
                    detectDragGestures(
                        onDragStart = { pos ->
                            val (wx, wy) = camera.screenToWorld(pos.x, pos.y, size.width, size.height)
                            measureStart = wx to wy
                            measureEnd = wx to wy
                        },
                        onDrag = { change, _ ->
                            val (wx, wy) = camera.screenToWorld(change.position.x, change.position.y, size.width, size.height)
                            measureEnd = wx to wy
                        },
                        onDragEnd = {
                            onMeasureDone?.invoke()
                        },
                        onDragCancel = {
                            measureStart = null; measureEnd = null
                        }
                    )
                } else {
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
            }
    ) {
        val w = size.width.toInt()
        val h = size.height.toInt()

        // 背景
        drawRect(androidx.compose.ui.graphics.Color(0xFFF0F2F5))

        // 地图贴图（如有）
        mapRenderer.drawBitmap(drawContext.canvas.nativeCanvas, camera, w, h)

        // 陆地/覆盖多边形 + 标注（官方地图）
        mapRenderer.drawPolygons(drawContext.canvas.nativeCanvas, camera, w, h)

        // 网格
        mapRenderer.drawGrid(drawContext.canvas.nativeCanvas, camera, w, h)

        // 轨迹
        for (u in file.units) {
            TrackRenderer.draw(drawContext.canvas.nativeCanvas, u, camera, w, h)
        }

        // 传感器/武器射程弧（在单位下方绘制）
        if (!replaying) {
            for (u in file.units) {
                ArcRenderer.draw(drawContext.canvas.nativeCanvas, u, camera, w, h)
            }
        }

        // 单位：回放模式用帧位置；正常模式用实时位置
        if (replaying && replayFrame != null) {
            val posById = replayFrame.positions
            for (u in file.units) {
                val pos = posById[u.idNum] ?: continue
                val (sx, sy) = camera.worldToScreen(pos.x, pos.y, w, h)
                if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                    val frameUnit = u.copy(x = pos.x, y = pos.y)
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, frameUnit, sx, sy)
                    drawUnitLabel(drawContext.canvas.nativeCanvas, frameUnit, sx, sy)
                }
            }
        } else {
            for (u in file.units) {
                val (sx, sy) = camera.worldToScreen(u.x, u.y, w, h)
                if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, u, sx, sy, selected = u.idNum == selectedUnitId)
                    drawUnitLabel(drawContext.canvas.nativeCanvas, u, sx, sy)
                }
            }
        }

        // 测量线（桌面版 Measurement）：起点→终点 + 方位/距离标签
        val ms = measureStart
        val me = measureEnd
        if (ms != null && me != null) {
            val nc = drawContext.canvas.nativeCanvas
            val (sx0, sy0) = camera.worldToScreen(ms.first, ms.second, w, h)
            val (sx1, sy1) = camera.worldToScreen(me.first, me.second, w, h)
            val mPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(230, 220, 60, 40)
                strokeWidth = 3f
                style = android.graphics.Paint.Style.STROKE
            }
            nc.drawLine(sx0, sy0, sx1, sy1, mPaint)
            nc.drawCircle(sx0, sy0, 8f, mPaint)
            val distNm = CoordUtil.distanceNm(ms.first, ms.second, me.first, me.second)
            val bearing = CoordUtil.bearingDeg(ms.first, ms.second, me.first, me.second)
            val label = String.format("%.1f nmi  方位 %.0f°", distNm, bearing)
            val tPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 16f
            }
            val midX = (sx0 + sx1) / 2f
            val midY = (sy0 + sy1) / 2f - 14f
            nc.drawText(label, midX + 2f, midY + 2f, tPaint)
            tPaint.color = android.graphics.Color.argb(255, 220, 60, 40)
            nc.drawText(label, midX, midY, tPaint)
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
