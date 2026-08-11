package com.simplot.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
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
import kotlin.math.max

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
    symbolStyle: com.simplot.android.render.UnitRenderer.SymbolStyle = com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS,
    settings: com.simplot.android.domain.model.PlayerSettings = com.simplot.android.domain.model.PlayerSettings.DEFAULT,
    miscAnnotations: List<com.simplot.android.domain.model.MiscAnnotation> = emptyList()
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
                    // 测量模式（修复 A）：awaitEachGesture 手动实现，轻点（无位移）= 选中单位，拖拽 = 画线。
                    // detectDragGestures 轻点不触发任何回调 → 单位无法选中；此处两手势并存。
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var isDrag = false
                        var start: Pair<Long, Long>? = null
                        var last: Pair<Long, Long>? = null
                        // drag() 内部已按 touchSlop 过滤：位移超过阈值后才回调；松手返回是否成拖
                        // drag() 返回 Boolean：手势正常结束=true，系统取消（如来电打断）=false（N1 修复）
                        val completed = drag(down.id) { change ->
                            val (wx, wy) = camera.screenToWorld(change.position.x, change.position.y, size.width, size.height)
                            if (!isDrag) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (dx * dx + dy * dy >= viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                                    isDrag = true
                                    val (sx, sy) = camera.screenToWorld(down.position.x, down.position.y, size.width, size.height)
                                    start = sx to sy
                                    measureStart = start
                                }
                            }
                            if (isDrag) {
                                change.consume()
                                last = wx to wy
                                measureEnd = last
                            }
                        }
                        if (!isDrag) {
                            // 轻点：选中单位（不 consume；空白则 hit=null → onSelect(null) 取消选中）
                            val hit = hitTest(file.units, camera, down.position, size.width.toInt(), size.height.toInt(), camera.zoom)
                            onSelect(hit?.idNum)
                        } else if (completed && start != null && last != null) {
                            // 仅在手势正常完成（非取消）时记录测量线，避免半条线（N1）
                            onMeasureDone?.invoke(start!!, last!!)
                        }
                        measureStart = null
                        measureEnd = null
                    }
                } else {
                    detectTapGestures(
                        onTap = { pos ->
                            // 命中检测：点选单位
                            val hit = hitTest(file.units, camera, pos, size.width.toInt(), size.height.toInt(), camera.zoom)
                            onSelect(hit?.idNum)
                        },
                        onLongPress = { pos ->
                            val hit = hitTest(file.units, camera, pos, size.width.toInt(), size.height.toInt(), camera.zoom)
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

        // 背景（R7：颜色可配置，桌面版 Colors.BackgroundColor）
        drawRect(androidx.compose.ui.graphics.Color(settings.backgroundColor))

        // 地图贴图（如有）
        mapRenderer.drawBitmap(drawContext.canvas.nativeCanvas, camera, w, h)

        // 陆地/覆盖多边形 + 标注（官方地图；R4：城市/国家/水域/深度开关接线）
        mapRenderer.drawPolygons(
            drawContext.canvas.nativeCanvas, camera, w, h,
            showCities = settings.showCities, showCountries = settings.showCountries,
            showWaters = settings.showWaters, showDepths = settings.showDepths
        )

        // 网格（R4：ShowGrid 开关）
        if (settings.showGrid) {
            mapRenderer.drawGrid(drawContext.canvas.nativeCanvas, camera, w, h)
        }

        // 轨迹（R4：ShowWaypoints 关时不画轨迹线）
        if (settings.showWaypoints) {
            for (u in file.units) {
                TrackRenderer.draw(drawContext.canvas.nativeCanvas, u, camera, w, h)
            }
        }

        // 传感器/武器射程弧（在单位下方绘制；R4：ShowSensors/ShowWeapons 开关）
        if (!replaying) {
            for (u in file.units) {
                ArcRenderer.draw(drawContext.canvas.nativeCanvas, u, camera, w, h, settings.showSensors, settings.showWeapons)
                // 被动方位线（R4：ShowSonar / ShowEs 开关）
                com.simplot.android.render.BearingRenderer.draw(
                    drawContext.canvas.nativeCanvas, u, camera, w, h,
                    showSonar = settings.showSonar, showEs = settings.showEs
                )
            }
        }

        // 编队连线（桌面版 ShowFormations）：同编队成员与中心连线（细灰线）
        if (settings.showFormations) {
            drawFormationLines(drawContext.canvas.nativeCanvas, file.units, camera, w, h)
        }

        // Misc 标注（R7：桌面版 MiscBox/Oval/Line/Polygon/Label，Overlay 层）
        if (miscAnnotations.isNotEmpty()) {
            com.simplot.android.render.MiscAnnotationRenderer.draw(
                drawContext.canvas.nativeCanvas, miscAnnotations, camera, w, h
            )
        }

        // 单位：回放模式用帧位置；正常模式用实时位置
        if (replaying && replayFrame != null) {
            val posById = replayFrame.positions
            for (u in file.units) {
                val pos = posById[u.idNum] ?: continue
                val (sx, sy) = camera.worldToScreen(pos.x, pos.y, w, h)
                if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                    val frameUnit = u.copy(x = pos.x, y = pos.y)
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, frameUnit, sx, sy,
                        sizePx = UnitRenderer.iconSizePx(camera.zoom), symbolStyle = symbolStyle,
                        showSpeedLeader = settings.showSpeedLeaders)
                    if (settings.showLabels) {
                        drawUnitLabel(drawContext.canvas.nativeCanvas, frameUnit, sx, sy, camera.zoom)
                    }
                }
            }
        } else {
            for (u in file.units) {
                val (sx, sy) = camera.worldToScreen(u.x, u.y, w, h)
                if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, u, sx, sy,
                        sizePx = UnitRenderer.iconSizePx(camera.zoom), selected = u.idNum == selectedUnitId, symbolStyle = symbolStyle,
                        showSpeedLeader = settings.showSpeedLeaders)
                    if (settings.showLabels) {
                        drawUnitLabel(drawContext.canvas.nativeCanvas, u, sx, sy, camera.zoom)
                    }
                }
            }
        }

        // 测量线（桌面版 Measurement）绘制顺序：已保存留存线 → 点选单位距离辅助线 → 拖拽中临时线
        val nc = drawContext.canvas.nativeCanvas
        // ① 已保存测量线（松手后留存，淡色细线；draw 阶段读快照列表 → 自动重绘）
        // 修复 B：仅测量模式内绘制留存线；退出测量模式即清除（MainActivity 调 clearMeasures + 此条件双保险）
        if (measureMode) {
            for (m in savedMeasures) {
                drawMeasureLine(nc, camera, w, h, m.first, m.second, saved = true)
            }
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
                    // 契约6：辅助线标签与单位名称统一走 labelTextSize（随 zoom 缩放），行高随字号
                    val labelSize = UnitRenderer.labelTextSize(camera.zoom)
                    val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 4f
                        textSize = labelSize
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = labelSize
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val lineHeight = labelSize * 1.2f
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

        // 坐标比例尺条（右下角；R4：ShowScaleBar 开关）
        if (settings.showScaleBar) {
            drawScaleBar(drawContext.canvas.nativeCanvas, w, h)
        }
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
    val midX = (sx0 + sx1) / 2f
    val midY = (sy0 + sy1) / 2f - 14f
    // 两遍画法：先黑描边再白填充（同坐标，无偏移阴影），任何底色可读
    // 契约6：测量标签与单位名称统一走 labelTextSize（随 zoom 缩放）
    val labelSize = UnitRenderer.labelTextSize(camera.zoom)
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
        textSize = labelSize
    }
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = labelSize
    }
    canvas.drawText(label, midX, midY, strokePaint)
    canvas.drawText(label, midX, midY, fillPaint)
}

/** 标签绘制（名称 + 航向航速）：字号与锚点偏移随 zoom 等比缩放（Bug 2 / 反馈⑥） */
private fun drawUnitLabel(canvas: android.graphics.Canvas, u: Unit, sx: Float, sy: Float, zoom: Float) {
    val tag = u.textTags
    // R7 修复：按桌面版 9 项 TagXxx 开关拼装（桌面 Create*Tag 格式串）；
    // 无任何开关开启时不画
    if (!tag.tagName && !tag.tagCourseSpeed && !tag.tagTrackNum && !tag.tagClass && !tag.tagUnitType &&
        !tag.tagAltitude && !tag.tagDepth && !tag.tagCallsign && tag.additionalText.isBlank()) return
    val k = UnitRenderer.labelScaleK(zoom)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = UnitRenderer.colorOf(u.side)
        textSize = UnitRenderer.labelTextSize(zoom)
    }
    val parts = mutableListOf<String>()
    // 桌面格式："TN 123 x 4  名称" 风格，按开关拼装
    if (tag.tagTrackNum) parts.add("TN ${u.trackNumber}")
    if (tag.tagName && u.name.isNotEmpty()) parts.add(u.name)
    if (tag.tagClass && u.unitClass.isNotEmpty()) parts.add(u.unitClass)
    if (tag.tagUnitType && u.unitType.isNotEmpty()) parts.add(u.unitType)
    if (tag.tagCourseSpeed) {
        parts.add("Course ${u.courseDeg().toInt()}°  Speed ${u.speedKnots().toInt()} kts")
    }
    if (tag.tagAltitude && u.altitude != null) parts.add("Alt ${u.altitudeMeters()} m")
    if (tag.tagDepth && u.depth != null) parts.add("Depth ${u.depthMeters()} m")
    if (tag.tagCallsign && u.name.isNotEmpty()) parts.add(u.name)
    if (tag.additionalText.isNotBlank()) parts.add(tag.additionalText)
    val text = parts.joinToString("  ")
    if (text.isNotEmpty()) {
        canvas.drawText(text, sx + 10f * k, sy - 8f * k, paint)
    }
}

/** 编队连线：同编队成员 ↔ 中心（细灰线，桌面版 ShowFormations） */
private fun drawFormationLines(canvas: android.graphics.Canvas, units: List<Unit>, camera: Camera, w: Int, h: Int) {
    val groups = units.filter { it.isInFormation == true || it.isFormationCenter == true }
        .groupBy { it.formationName ?: "" }
    if (groups.isEmpty()) return
    val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(120, 140, 140, 140)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1f
    }
    for ((_, members) in groups) {
        val center = members.firstOrNull { it.isFormationCenter == true } ?: members.firstOrNull() ?: continue
        val (cx, cy) = camera.worldToScreen(center.x, center.y, w, h)
        for (m in members) {
            if (m.idNum == center.idNum) continue
            val (sx, sy) = camera.worldToScreen(m.x, m.y, w, h)
            canvas.drawLine(cx, cy, sx, sy, linePaint)
        }
    }
}

/** 右下角比例尺条：50 海里示意（白线 + 两端竖线刻度 + 实心白字黑描边） */
private fun drawScaleBar(canvas: android.graphics.Canvas, w: Int, h: Int) {
    val x0 = w - 90f
    val y0 = h - 30f
    // 线条：白色实线 + 两端竖线刻度（与文字 paint 分离，不复用 STROKE 样式画字）
    val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    canvas.drawLine(x0, y0, x0 + 70f, y0, linePaint)
    canvas.drawLine(x0, y0 - 6f, x0, y0 + 6f, linePaint)
    canvas.drawLine(x0 + 70f, y0 - 6f, x0 + 70f, y0 + 6f, linePaint)
    // 文字：白字 + 黑描边两遍画法（FILL 实心字，显式 textSize）
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
        textSize = 20f
    }
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 20f
    }
    canvas.drawText("50 nmi", x0, y0 - 8f, strokePaint)
    canvas.drawText("50 nmi", x0, y0 - 8f, fillPaint)
}

/** 命中检测：返回被点中的单位（若有）。hitRadius 随 zoom 放大（契约7：与图标尺寸同链路，放大后易选中） */
internal fun hitTest(units: List<Unit>, camera: Camera, pos: Offset, w: Int, h: Int, zoom: Float = camera.zoom): Unit? {
    val hitRadius = max(20f, UnitRenderer.iconSizePx(zoom) * 1.2f)
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
