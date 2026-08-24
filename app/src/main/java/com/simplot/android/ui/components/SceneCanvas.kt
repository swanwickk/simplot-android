package com.simplot.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.unit.IntSize
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
 * - 长按单位进入拖拽 Relocate（G32：实时 relocate + 航路点同步平移，替代原长按弹编辑窗）
 * - 回放模式：传入 [replayFrame] 时按帧位置渲染（不响应点选编辑）
 */
@Composable
fun SceneCanvas(
    file: ScenarioFile,
    camera: Camera,
    mapRenderer: MapRenderer,
    selectedUnitId: String?,
    onSelect: (String?) -> kotlin.Unit,
    // #11 修复：删除无调用方的 onLongPress 参数（G32 已改为长按拖拽 Relocate）
    onRelocate: (unitId: String, x: Long, y: Long) -> kotlin.Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    replayFrame: ReplayEngine.Frame? = null,
    tick: Int = 0,
    measureMode: Boolean = false,
    onMeasureDone: ((start: Pair<Long, Long>, end: Pair<Long, Long>) -> kotlin.Unit)? = null,
    savedMeasures: List<Pair<Pair<Long, Long>, Pair<Long, Long>>> = emptyList(),
    unitDistances: List<UnitDistance>? = null,
    symbolStyle: com.simplot.android.render.UnitRenderer.SymbolStyle = com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS,
    settings: com.simplot.android.domain.model.PlayerSettings = com.simplot.android.domain.model.PlayerSettings.DEFAULT,
    miscAnnotations: List<com.simplot.android.domain.model.MiscAnnotation> = emptyList(),
    showSide: com.simplot.android.ui.ShowSide = com.simplot.android.ui.ShowSide.ALL,
    distanceUnit: com.simplot.android.data.util.CoordUtil.DistanceUnit = com.simplot.android.data.util.CoordUtil.DistanceUnit.NM
) {
    val replaying = replayFrame != null
    // G30：Show Side 视图过滤（All/Blue/Red）——仅影响绘制与命中检测，不落盘、不改引擎状态
    val viewUnits: List<Unit> =
        if (showSide.sideName == null) file.units else file.units.filter { it.side == showSide.sideName }
    // G32：当前被长按拖拽的单位 IdNum（非空时 transform 手势禁用地图平移，防单位拖动与地图拖动冲突）
    var draggingUnitId by remember { mutableStateOf<String?>(null) }
    // 重绘纪元（反馈④）：tick 变化 → LaunchedEffect 快照写；draw 阶段快照读（epoch）→ 必重绘。
    // 修复：revision++ 触发的重组在 compose-ui 1.7.0 下未带动 draw 失效，
    // 通过 draw 内显式快照读保证 epoch 变化即重绘（Do/编辑/复制/护航队/Undo 均覆盖）。
    var drawEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { drawEpoch = tick }
    // 记录画布像素尺寸；新场景载入时自适应视野（单位坐标或地图边界）
    // 修复：选中单位导致 file.units.size/key 稳定但 canvasSize 因底部栏显隐变化时不应重做 fitBounds 缩到最小
    // 用 file 顶层 key（场景名 + 单位数 + 地图名）作为稳定 key，且仅在 fileKey 变化时拟合，其余仅响应尺寸变化做居中保持
    var lastFileKey by remember { mutableStateOf<String?>(null) }
    val fileKey = file.scenario.scenarioName + "|" + file.units.size + "|" + (file.scenario.mapFileName ?: "")
    var canvasSize by remember { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(fileKey, canvasSize) {
        if (lastFileKey == fileKey) return@LaunchedEffect
        lastFileKey = fileKey
        val size = canvasSize ?: return@LaunchedEffect
        if (size.width > 0 && size.height > 0) {
            val xs = file.units.map { it.x }
            val ys = file.units.map { it.y }
            if (xs.isNotEmpty()) {
                camera.fitBounds(xs.min(), xs.max(), ys.min(), ys.max(), size.width, size.height)
            } else if (mapRenderer.hasBoundary) {
                camera.fitBounds(
                    mapRenderer.boundaryLeft,
                    mapRenderer.boundaryLeft + mapRenderer.boundaryWidth,
                    mapRenderer.boundaryTop,
                    mapRenderer.boundaryTop + mapRenderer.boundaryHeight,
                    size.width,
                    size.height
                )
            } else {
                camera.centerOn(0L, 0L)
                camera.zoom = 0.0015f
            }
        }
    }
    // 测量状态（桌面版 Measurement.AddNewMeasure/ExtendMeasure）
    var measureStart by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var measureEnd by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    Canvas(
        modifier = modifier
            .onSizeChanged { size ->
                canvasSize = size
            }
            .pointerInput(measureMode, showSide) {
                // Bug 3 修复：测量模式下完全不注册 transform 手势（单指=画线、无地图拖动/缩放）。
                // measureMode 作为 key：切换时协程取消重启，重新评估（key=Unit 时读到的是陈旧值，C1 的 pan 禁用不生效）。
                // G30：showSide 作为 key——过滤切换后 hitTest 闭包重新捕获 viewUnits（否则读到陈旧列表）
                if (measureMode) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // 缩放：以双指中心为锚点（阈值判断，避免浮点噪声吞掉 pan）
                    if (abs(zoom - 1f) > 0.001f) {
                        camera.zoomAt(zoom, centroid.x, centroid.y, size.width, size.height)
                    }
                    // 平移：始终生效（单指拖动 / 双指缩放时跟随）
                    // ⚠️ 测量模式下禁用单指平移（否则拖动画线与地图拖动冲突，C1 修复，双保险）
                    // G32：单位长按拖拽期间（draggingUnitId 非空）禁用地图平移（防单位拖动与地图拖动冲突）
                    val measuring = measureMode
                    val relocating = draggingUnitId != null
                    if (!measuring && !relocating && (abs(pan.x) > 0.5f || abs(pan.y) > 0.5f)) {
                        camera.pan(pan.x, pan.y)
                    }
                }
            }
            .pointerInput(file, measureMode, showSide) {
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
                            // 轻点：选中单位（不 consume；空白则 hit=null → onSelect(null) 取消选中）；H1：命中半径需×SymbolSize.scale
                            val hit = hitTest(viewUnits, camera, down.position, size.width.toInt(), size.height.toInt(), camera.zoom, settings.symbolSize.scale)
                            onSelect(hit?.idNum)
                        } else if (completed && start != null && last != null) {
                            // 仅在手势正常完成（非取消）时记录测量线，避免半条线（N1）
                            onMeasureDone?.invoke(start!!, last!!)
                        }
                        measureStart = null
                        measureEnd = null
                    }
                } else {
                    // G32：长按单位 → 拖拽 Relocate（实时 relocate，替代原「长按弹编辑窗」）；
                    // 轻点 → 选中；长按空白无动作；拖动期间 draggingUnitId 非空 → transform 禁用地图平移。
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val hit = hitTest(viewUnits, camera, down.position, size.width.toInt(), size.height.toInt(), camera.zoom, settings.symbolSize.scale)
                        if (hit != null) {
                            // 按下即选中（拖拽中的单位即当前选中，桌面 MouseDrag 语义）
                            onSelect(hit.idNum)
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                draggingUnitId = hit.idNum
                                try {
                                    // 长按成立后单指拖动：实时把单位挪到手指世界坐标（revision++ 由上层触发重绘）
                                    drag(down.id) { change ->
                                        val (wx, wy) = camera.screenToWorld(change.position.x, change.position.y, size.width, size.height)
                                        onRelocate(hit.idNum, wx, wy)
                                        change.consume()
                                    }
                                } finally {
                                    draggingUnitId = null
                                }
                            }
                            // 长按未成立（提前抬起=轻点，已选中；移动超阈值=地图平移，transform 处理）
                        } else {
                            // 空白按下：轻点（无位移且未长按）取消选中；拖动平移地图不取消
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress == null) {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    val dx = up.position.x - down.position.x
                                    val dy = up.position.y - down.position.y
                                    if (dx * dx + dy * dy < viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                                        onSelect(null)
                                    }
                                }
                            } else {
                                // 长按空白无动作：等待手势结束（期间 transform 手势可平移地图）
                                waitForUpOrCancellation()
                            }
                        }
                    }
                }
            }
    ) {
        val w = size.width.toInt()
        val h = size.height.toInt()

        // draw 阶段快照读：epoch 变化 → Canvas 失效重绘（④ 修复核心）
        @Suppress("UNUSED_VARIABLE") val epoch = drawEpoch

        // #9 修复：调色板每帧只算一次（此前每单位每帧 paletteOf(settings) 造成大量小对象分配）
        val palette = UnitRenderer.paletteOf(settings)

        // 背景（R7：颜色可配置，桌面版 Colors.BackgroundColor）
        drawRect(androidx.compose.ui.graphics.Color(settings.backgroundColor))

        // 地图贴图（如有）
        mapRenderer.drawBitmap(drawContext.canvas.nativeCanvas, camera, w, h)

        // 陆地/覆盖多边形 + 标注（官方地图；R4：城市/国家/水域/深度开关接线；G09：颜色读 PlayerSettings）
        mapRenderer.drawPolygons(
            drawContext.canvas.nativeCanvas, camera, w, h,
            showCities = settings.showCities, showCountries = settings.showCountries,
            showWaters = settings.showWaters, showDepths = settings.showDepths,
            landColor = settings.mapLandColor, oceanColor = settings.mapOceanColor,
            redForColor = settings.redForColor
        )

        // 网格（R4：ShowGrid 开关；G09：网格色读 settings.gridColor）
        if (settings.showGrid) {
            mapRenderer.drawGrid(drawContext.canvas.nativeCanvas, camera, w, h, gridColor = settings.gridColor)
        }

        // 轨迹（R4：ShowWaypoints 关时不画轨迹线）
        if (settings.showWaypoints) {
            for (u in viewUnits) {
                TrackRenderer.draw(drawContext.canvas.nativeCanvas, u, camera, w, h, palette = palette)
            }
        }

        // 传感器/武器射程弧（在单位下方绘制；R4：ShowSensors/ShowWeapons 开关）
        if (!replaying) {
            for (u in viewUnits) {
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
            drawFormationLines(drawContext.canvas.nativeCanvas, viewUnits, camera, w, h, palette)
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
            for (u in viewUnits) {
                val pos = posById[u.idNum] ?: continue
                val (sx, sy) = camera.worldToScreen(pos.x, pos.y, w, h)
                if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                    // #25 说明：u.copy 仅为回放帧位置渲染（UnitRenderer.draw 按 u.x/y 取位），
                    // Unit.copy 为浅拷贝（航路点等引用共享），分配开销可接受，保留。
                    val frameUnit = u.copy(x = pos.x, y = pos.y)
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, frameUnit, sx, sy,
                        sizePx = UnitRenderer.iconSizePx(camera.zoom) * settings.symbolSize.scale,
                        symbolStyle = symbolStyle, symbolSet = settings.symbolSet,
                        ww2Mode = settings.ww2Symbols, sizeLevel = settings.symbolSize,
                        showSpeedLeader = settings.showSpeedLeaders,
                        palette = palette,
                        friendlySymbols = settings.showFriendlySymbols,
                        showSideName = showSide.sideName)
                    if (settings.showLabels) {
                        drawUnitLabel(drawContext.canvas.nativeCanvas, frameUnit, sx, sy, camera.zoom, showSideName = showSide.sideName,
                            useLabelBackground = settings.useLabelBackground,
                            backgroundColor = settings.backgroundColor,
                            palette = palette)
                    }
                }
            }
        } else {
            for (u in viewUnits) {
                val (sx, sy) = camera.worldToScreen(u.x, u.y, w, h)
                if (sx in -60f..w + 60f && sy in -60f..h + 60f) {
                    UnitRenderer.draw(drawContext.canvas.nativeCanvas, u, sx, sy,
                        sizePx = UnitRenderer.iconSizePx(camera.zoom) * settings.symbolSize.scale,
                        selected = u.idNum == selectedUnitId, symbolStyle = symbolStyle,
                        symbolSet = settings.symbolSet, ww2Mode = settings.ww2Symbols,
                        sizeLevel = settings.symbolSize,
                        showSpeedLeader = settings.showSpeedLeaders,
                        palette = palette,
                        friendlySymbols = settings.showFriendlySymbols,
                        showSideName = showSide.sideName)
                    if (settings.showLabels) {
                        drawUnitLabel(drawContext.canvas.nativeCanvas, u, sx, sy, camera.zoom, showSideName = showSide.sideName,
                            useLabelBackground = settings.useLabelBackground,
                            backgroundColor = settings.backgroundColor,
                            palette = palette)
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
                drawMeasureLine(nc, camera, w, h, m.first, m.second, saved = true, distanceUnit = distanceUnit)
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
                    val linePaint = ScenePaintPool.distLine
                    nc.drawLine(selX, selY, tx, ty, linePaint)
                    val midX = (selX + tx) / 2f
                    val midY = (selY + ty) / 2f - 6f
                    // #15：显式 Locale.US，支持距离单位切换
                    val distStr = com.simplot.android.data.util.CoordUtil.formatDistance(d.distNm, distanceUnit)
                    val lines = listOf(d.name, String.format(java.util.Locale.US, "%s %.0f°", distStr, d.bearingDeg))
                    // 契约6：辅助线标签与单位名称统一走 labelTextSize（随 zoom 缩放），行高随字号
                    val labelSize = UnitRenderer.labelTextSize(camera.zoom)
                    // #9：复用池画笔（描边/填充两遍，CENTER 对齐在池内初始化）
                    val outlinePaint = ScenePaintPool.distLabelOutline.apply { textSize = labelSize }
                    val fillPaint = ScenePaintPool.distLabelFill.apply { textSize = labelSize }
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
            drawMeasureLine(nc, camera, w, h, ms, me, saved = false, distanceUnit = distanceUnit)
        }

        // 坐标比例尺条（右下角；R4：ShowScaleBar 开关；G17：数值随 zoom 动态计算）
        if (settings.showScaleBar) {
            drawScaleBar(drawContext.canvas.nativeCanvas, camera, w, h)
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
    saved: Boolean,
    distanceUnit: com.simplot.android.data.util.CoordUtil.DistanceUnit = com.simplot.android.data.util.CoordUtil.DistanceUnit.NM
) {
    val (sx0, sy0) = camera.worldToScreen(start.first, start.second, w, h)
    val (sx1, sy1) = camera.worldToScreen(end.first, end.second, w, h)
    // #9：复用池画笔（保留/临时线色与线宽按需覆盖）
    val mPaint = ScenePaintPool.measureLine.apply {
        color = if (saved) android.graphics.Color.argb(150, 220, 60, 40)
        else android.graphics.Color.argb(230, 220, 60, 40)
        strokeWidth = if (saved) 2f else 3f
    }
    canvas.drawLine(sx0, sy0, sx1, sy1, mPaint)
    canvas.drawCircle(sx0, sy0, if (saved) 4f else 8f, mPaint)
    val distNm = CoordUtil.distanceNm(start.first, start.second, end.first, end.second)
    val bearing = CoordUtil.bearingDeg(start.first, start.second, end.first, end.second)
    // #15：显式 Locale.US，支持距离单位切换
    val distStr = CoordUtil.formatDistance(distNm, distanceUnit)
    val label = String.format(java.util.Locale.US, "%s  方位 %.0f°", distStr, bearing)
    val midX = (sx0 + sx1) / 2f
    val midY = (sy0 + sy1) / 2f - 14f
    // 两遍画法：先黑描边再白填充（同坐标，无偏移阴影），任何底色可读
    // 契约6：测量标签与单位名称统一走 labelTextSize（随 zoom 缩放）
    val labelSize = UnitRenderer.labelTextSize(camera.zoom)
    // #9：复用池画笔（描边/填充两遍）
    val strokePaint = ScenePaintPool.measureLabelOutline.apply { textSize = labelSize }
    val fillPaint = ScenePaintPool.measureLabelFill.apply { textSize = labelSize }
    canvas.drawText(label, midX, midY, strokePaint)
    canvas.drawText(label, midX, midY, fillPaint)
}

/**
 * 标签绘制（名称 + 航向航速）：字号与锚点偏移随 zoom 等比缩放（Bug 2 / 反馈⑥）。
 * G08/G09：CheckBackground（"Use background color under labels"）开启时在文字下垫背景色矩形；
 * 文字颜色走 PlayerSettings 蓝/红键（palette）。
 */
private fun drawUnitLabel(
    canvas: android.graphics.Canvas, u: Unit, sx: Float, sy: Float, zoom: Float,
    showSideName: String? = null,
    useLabelBackground: Boolean = true,
    backgroundColor: Long = 0xFFF0F2F5,
    palette: UnitRenderer.Palette = UnitRenderer.Palette()
) {
    val tag = u.textTags
    val per = if (showSideName != null) u.perceptionArray?.firstOrNull { it.seenBySide.equals(showSideName, true) } else null
    val effName = if (per != null) per.showName else tag.tagName
    val effCS = if (per != null) per.showCourseSpeed else tag.tagCourseSpeed
    val effClass = if (per != null) per.showClass else tag.tagClass
    val effAlt = if (per != null) per.showAltitude else tag.tagAltitude
    val effDepth = if (per != null) per.showDepth else tag.tagDepth
    val effUnitType = tag.tagUnitType
    val effTrack = tag.tagTrackNum
    // R7 修复：按桌面版 9 项 TagXxx 开关拼装（桌面 Create*Tag 格式串）；
    // 无任何开关开启时不画
    if (!effName && !effCS && !effTrack && !effClass && !effUnitType &&
        !effAlt && !effDepth && !tag.tagCallsign && tag.additionalText.isBlank()) return
    val k = UnitRenderer.labelScaleK(zoom)
    // #9：复用池画笔，按阵营色/字号覆盖后绘制（避免每单位每帧 new Paint）
    val paint = ScenePaintPool.labelFill.apply {
        color = UnitRenderer.colorOf(u.side, palette)
        textSize = UnitRenderer.labelTextSize(zoom)
    }
    val parts = mutableListOf<String>()
    // 桌面格式："TN 123 x 4  名称" 风格，按开关拼装
    if (effTrack) parts.add("TN ${u.trackNumber}")
    // P3-2 修复：标签呼叫号走 callsignOrName()（优先独立呼叫号、空串回退 Name），
    // 此前直接用 u.name 导致 UnitEditSheet 配置的独立 callsign 在主海图标签不显示。
    val cn = u.callsignOrName()
    if (effName && cn.isNotEmpty()) parts.add(cn)
    if (effClass && u.unitClass.isNotEmpty()) parts.add(u.unitClass)
    if (effUnitType && u.unitType.isNotEmpty()) parts.add(u.unitType)
    if (effCS) {
        parts.add("Course ${u.courseDeg().toInt()}°  Speed ${u.speedKnots().toInt()} kts")
    }
    if (effAlt && u.altitude != null) parts.add("Alt ${u.altitudeMeters()} m")
    if (effDepth && u.depth != null) parts.add("Depth ${u.depthMeters()} m")
    if (tag.tagCallsign && u.name.isNotEmpty()) parts.add(u.name)
    if (tag.additionalText.isNotBlank()) parts.add(tag.additionalText)
    val text = parts.joinToString("  ")
    if (text.isNotEmpty()) {
        val tx = sx + 10f * k
        val ty = sy - 8f * k
        // G08/反馈㉓更正：标签背景**全透明**——不绘制任何底色矩形（此前误解为改不透明）。
        // useLabelBackground 开关保留在设置中但当前语义=无底色；文字可读性由描边兜底。
        canvas.drawText(text, tx, ty, paint)
    }
}

/** 编队连线：同编队成员 ↔ 中心（细灰线，桌面版 ShowFormations）。
 *  G51：队形名标签（桌面版 SymbolGenerator.TextTags.DrawFormationName）——
 *  在编队中心单位上方绘制 formationName，阵营色 + 黑描边，随 zoom 缩放。 */
private fun drawFormationLines(canvas: android.graphics.Canvas, units: List<Unit>, camera: Camera, w: Int, h: Int,
                               palette: UnitRenderer.Palette = UnitRenderer.Palette()) {
    val groups = units.filter { it.isInFormation == true || it.isFormationCenter == true }
        .groupBy { it.formationName ?: "" }
    if (groups.isEmpty()) return
    // #9：复用池画笔（细灰线，样式字段已初始化）
    val linePaint = ScenePaintPool.formationLine
    for ((name, members) in groups) {
        val center = members.firstOrNull { it.isFormationCenter == true } ?: members.firstOrNull() ?: continue
        val (cx, cy) = camera.worldToScreen(center.x, center.y, w, h)
        for (m in members) {
            if (m.idNum == center.idNum) continue
            val (sx, sy) = camera.worldToScreen(m.x, m.y, w, h)
            canvas.drawLine(cx, cy, sx, sy, linePaint)
        }
        // G51：队形名标签（桌面 DrawFormationName：编队名显示在画布上）
        if (name.isNotBlank()) {
            drawFormationNameLabel(canvas, name, center, cx, cy, camera.zoom, palette)
        }
    }
}

/**
 * G51：队形名标签绘制（桌面版 TextTags.DrawFormationName 语义）。
 * 白底可读性两遍画法（黑描边 + 阵营色填充，同测量/单位标签风格），
 * 字号与锚点偏移随 zoom 等比缩放（与 drawUnitLabel 同一套系数）。
 */
private fun drawFormationNameLabel(
    canvas: android.graphics.Canvas, name: String, center: Unit,
    cx: Float, cy: Float, zoom: Float, palette: UnitRenderer.Palette = UnitRenderer.Palette()
) {
    val k = UnitRenderer.labelScaleK(zoom)
    val ty = cy - 18f * k
    // 越界跳过（视口外 ±200px 缓冲）
    if (cx < -200f || cx > canvas.width + 200f || ty < -200f || ty > canvas.height + 200f) return
    // #9/#19：复用池画笔；颜色走玩家自定义 palette（此前 colorOf(side) 单参硬编码默认调色板，
    // 用户自定义蓝/红/中立色不会反映到队形名）
    val fill = ScenePaintPool.formationNameFill.apply {
        color = UnitRenderer.colorOf(center.side, palette)
        textSize = UnitRenderer.labelTextSize(zoom)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val outline = ScenePaintPool.formationNameOutline.apply {
        color = android.graphics.Color.BLACK
        textSize = UnitRenderer.labelTextSize(zoom)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    canvas.drawText(name, cx, ty, outline)
    canvas.drawText(name, cx, ty, fill)
}

/**
 * G17：右下角比例尺条（动态数值，随 zoom 变化；桌面版 ContainerScalebar）。
 * 数值与像素长度由 [com.simplot.android.render.ScaleBar] 按 1-2-5 序列取整计算
 * （1 海里 = 100000 世界单位 × zoom = 屏幕像素），右对齐，白线 + 两端竖线刻度 + 实心白字黑描边。
 */
private fun drawScaleBar(canvas: android.graphics.Canvas, camera: Camera, w: Int, h: Int) {
    val (nmi, px) = com.simplot.android.render.ScaleBar.compute(camera.zoom, 100f)
    val x0 = w - px - 20f
    val y0 = h - 30f
    // 线条：白色实线 + 两端竖线刻度（与文字 paint 分离，不复用 STROKE 样式画字）
    // #9：复用池画笔
    val linePaint = ScenePaintPool.scaleLine
    canvas.drawLine(x0, y0, x0 + px, y0, linePaint)
    canvas.drawLine(x0, y0 - 6f, x0, y0 + 6f, linePaint)
    canvas.drawLine(x0 + px, y0 - 6f, x0 + px, y0 + 6f, linePaint)
    // 文字：白字 + 黑描边两遍画法（FILL 实心字，显式 textSize）
    val label = com.simplot.android.render.ScaleBar.label(nmi)
    val strokePaint = ScenePaintPool.scaleLabelOutline
    val fillPaint = ScenePaintPool.scaleLabelFill
    canvas.drawText(label, x0, y0 - 8f, strokePaint)
    canvas.drawText(label, x0, y0 - 8f, fillPaint)
}

/** 命中检测：返回被点中的单位（若有）。hitRadius 随 zoom 放大并按 SymbolSize.scale 同步缩放（H1：与绘制链路一致） */
internal fun hitTest(
    units: List<Unit>, camera: Camera, pos: Offset, w: Int, h: Int, zoom: Float = camera.zoom,
    symbolSizeScale: Float = 1f
): Unit? {
    val hitRadius = max(20f, UnitRenderer.iconSizePx(zoom) * symbolSizeScale * 1.2f)
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

/**
 * #9/#25：渲染画笔复用池（G68 同策略：by lazy 惰性初始化——JVM 单测加载本类文件
 * 不会因 android.jar stub 抛 ExceptionInInitializerError；主线程绘制，非线程安全可接受）。
 * 每个使用点在绘制前按需覆盖 color/textSize/strokeWidth 等属性，避免每帧每单位 new Paint 的 GC 压力。
 */
private object ScenePaintPool {
    /** 单位标签填充（阵营色，使用点改 color/textSize） */
    val labelFill by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

    /** 标签背景矩形（G08 CheckBackground；FILL 实心） */
    val labelBg by lazy { android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL } }

    /** 编队连线（细灰线） */
    val formationLine by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(120, 140, 140, 140)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
        }
    }

    /** 队形名标签填充（阵营色，CENTER） */
    val formationNameFill by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    }

    /** 队形名标签黑描边（CENTER） */
    val formationNameOutline by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    /** 测量线（保留/临时，使用点改色/线宽；STROKE） */
    val measureLine by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    }

    /** 测量标签黑描边 */
    val measureLabelOutline by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }
    }

    /** 测量标签白填充 */
    val measureLabelFill by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE } }

    /** 单位距离辅助线（灰） */
    val distLine by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(160, 90, 90, 90)
            strokeWidth = 1.5f
            style = android.graphics.Paint.Style.STROKE
        }
    }

    /** 单位距离标签黑描边（CENTER） */
    val distLabelOutline by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    /** 单位距离标签白填充（CENTER） */
    val distLabelFill by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    /** 比例尺线条（白实线） */
    val scaleLine by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5f
        }
    }

    /** 比例尺文字黑描边 */
    val scaleLabelOutline by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            textSize = 20f
        }
    }

    /** 比例尺文字白填充 */
    val scaleLabelFill by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 20f
        }
    }
}
