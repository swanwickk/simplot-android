package com.simplot.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.simplot.android.data.util.UnitDistance
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
    onMeasureDone: ((start: Pair<Long, Long>, end: Pair<Long, Long>) -> kotlin.Unit)? = null,
    savedMeasures: List<Pair<Pair<Long, Long>, Pair<Long, Long>>> = emptyList(),
    unitDistances: List<UnitDistance>? = null,
    symbolStyle: com.simplot.android.render.UnitRenderer.SymbolStyle = com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS
) {
    val replaying = replayFrame != null
    // 重绘纪元（反馈④）：tick 变化 → LaunchedEffect 快照写；draw 阶段快照读（epoch）→ 必重绘。
    // 修复：revision++ 触发的重组在 compose-ui 1.7.0 下未带动 draw 失效，
    // 通过 draw 内显式快照读保证 epoch 变化即重绘（Do/编辑/复制/护航队/Undo 均覆盖）。
    var drawEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { drawEpoch = tick }
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
            .pointerInput(measureMode) {
                // Bug 3 修复：测量模式下完全不注册 transform 手势（单指=画线、无地图拖动/缩放）。
                // measureMode 作为 key：切换时协程取消重启，重新评估（key=Unit 时读到的是陈旧值，C1 的 pan 禁用不生效）。
                if (measureMode) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // 缩放：以双指中心为锚点（阈值判断，避免浮点噪声吞掉 pan）
                    if (abs(zoom - 1f) > 0.001f) {
                        camera.zoomAt(zoom, centroid.x, centroid.y, size.width, size.height)
                    }
                    // 平移：始终生效（单指拖动 / 双指缩放时跟随）
                    // ⚠️ 测量模式下禁用单指平移（否则拖动画线与地图拖动冲突，C1 修复，双保险）
                    val measuring = measureMode
                    if (!measuring && (abs(pan.x) > 0.5f || abs(pan.y) > 0.5f)) {
                        camera.pan(pan.x, pan.y)
                    }
                }
            }
            .pointerInput(file, measureMode) {
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
                            val s = measureStart
                            val e = measureEnd
                            measureStart = null
                            measureEnd = null
                            if (s != null && e != null) onMeasureDone?.invoke(s, e)
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

        // draw 阶段快照读：epoch 变化 → Canvas 失效重绘（④ 修复核心）
        @Suppress("UNUSED_VARIABLE") val epoch = drawEpoch

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
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, frameUnit, sx, sy, symbolStyle = symbolStyle)
                    drawUnitLabel(drawContext.canvas.nativeCanvas, frameUnit, sx, sy, camera.zoom)
                }
            }
        } else {
            for (u in file.units) {
                val (sx, sy) = camera.worldToScreen(u.x, u.y, w, h)
                if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, u, sx, sy, selected = u.idNum == selectedUnitId, symbolStyle = symbolStyle)
                    drawUnitLabel(drawContext.canvas.nativeCanvas, u, sx, sy, camera.zoom)
                }
            }
        }

        // 测量线（桌面版 Measurement）绘制顺序：已保存留存线 → 点选单位距离辅助线 → 拖拽中临时线
        val nc = drawContext.canvas.nativeCanvas
        // ① 已保存测量线（松手后留存，淡色细线；draw 阶段读快照列表 → 自动重绘）
        for (m in savedMeasures) {
            drawMeasureLine(nc, camera, w, h, m.first, m.second, saved = true)
        }
        // ② 点选单位到其它单位的距离/方位辅助线（灰线 + 中点标签）
        val ud = unitDistances
        if (ud != null && ud.isNotEmpty()) {
            val sel = file.units.firstOrNull { it.idNum == selectedUnitId }
            if (sel != null) {
                val (selX, selY) = camera.worldToScreen(sel.x, sel.y, w, h)
                for (d in ud) {
                    val target = file.units.firstOrNull { it.idNum == d.idNum } ?: continue
                    val (tx, ty) = camera.worldToScreen(target.x, target.y, w, h)
                    val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(160, 90, 90, 90)
                        strokeWidth = 1.5f
                        style = android.graphics.Paint.Style.STROKE
                    }
                    nc.drawLine(selX, selY, tx, ty, linePaint)
                    val midX = (selX + tx) / 2f
                    val midY = (selY + ty) / 2f - 6f
                    val lines = listOf(d.name, String.format("%.1f nmi %.0f°", d.distNm, d.bearingDeg))
                    val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 3f
                        textSize = 13f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(60, 60, 60)
                        textSize = 13f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val lineHeight = 15f
                    var textY = midY - (lines.size - 1) * lineHeight / 2f + 5f
                    for (line in lines) {
                        nc.drawText(line, midX, textY, outlinePaint)
                        nc.drawText(line, midX, textY, fillPaint)
                        textY += lineHeight
                    }
                }
            }
        }
        // 拖拽中临时测量线（saved=false，现样式不变）
        val ms = measureStart
        val me = measureEnd
        if (ms != null && me != null) {
            drawMeasureLine(nc, camera, w, h, ms, me, saved = false)
        }

        // 坐标比例尺条（右下角）
        drawScaleBar(drawContext.canvas.nativeCanvas, w, h)
    }
}

/** 测量线绘制：saved=true 留存淡色细线（松手后保留）；saved=false 拖拽中临时线（现样式） */
private fun drawMeasureLine(
    canvas: android.graphics.Canvas,
    camera: Camera,
    w: Int,
    h: Int,
    start: Pair<Long, Long>,
    end: Pair<Long, Long>,
    saved: Boolean
) {
    val (sx0, sy0) = camera.worldToScreen(start.first, start.second, w, h)
    val (sx1, sy1) = camera.worldToScreen(end.first, end.second, w, h)
    val mPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (saved) android.graphics.Color.argb(150, 220, 60, 40)
        else android.graphics.Color.argb(230, 220, 60, 40)
        strokeWidth = if (saved) 2f else 3f
        style = android.graphics.Paint.Style.STROKE
    }
    canvas.drawLine(sx0, sy0, sx1, sy1, mPaint)
    canvas.drawCircle(sx0, sy0, if (saved) 4f else 8f, mPaint)
    val distNm = CoordUtil.distanceNm(start.first, start.second, end.first, end.second)
    val bearing = CoordUtil.bearingDeg(start.first, start.second, end.first, end.second)
    val label = String.format("%.1f nmi  方位 %.0f°", distNm, bearing)
    val tPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 16f
    }
    val midX = (sx0 + sx1) / 2f
    val midY = (sy0 + sy1) / 2f - 14f
    canvas.drawText(label, midX + 2f, midY + 2f, tPaint)
    tPaint.color = android.graphics.Color.argb(255, 220, 60, 40)
    canvas.drawText(label, midX, midY, tPaint)
}

/** 标签绘制（名称 + 航向航速）：字号与锚点偏移随 zoom 等比缩放（Bug 2 / 反馈⑥） */
private fun drawUnitLabel(canvas: android.graphics.Canvas, u: Unit, sx: Float, sy: Float, zoom: Float) {
    val tag = u.textTags
    if (!tag.tagName && !tag.tagCourseSpeed) return
    // k = 缩放比例（UnitRenderer.labelScaleK：zoom/LABEL_BASE_ZOOM，clamp 0.7..2.5，默认 zoom 下 k=1）
    val k = UnitRenderer.labelScaleK(zoom)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = UnitRenderer.colorOf(u.side)
        textSize = UnitRenderer.labelTextSize(zoom)
    }
    val parts = mutableListOf<String>()
    if (tag.tagName && u.name.isNotEmpty()) parts.add(u.name)
    if (tag.tagCourseSpeed) {
        parts.add("${u.speedKnots().toInt()}节/${u.courseDeg().toInt()}°")
    }
    val text = parts.joinToString("  ")
    if (text.isNotEmpty()) {
        canvas.drawText(text, sx + 10f * k, sy - 8f * k, paint)
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
