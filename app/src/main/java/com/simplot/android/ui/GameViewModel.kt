package com.simplot.android.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit as SimUnit
import com.simplot.android.data.repo.ScenarioRepository
import com.simplot.android.data.util.CoordUtil
import com.simplot.android.engine.FogOfWar
import com.simplot.android.engine.MovementEngine
import com.simplot.android.engine.ReplayEngine
import com.simplot.android.engine.TurnState
import com.simplot.android.render.Camera
import com.simplot.android.render.MapRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 游戏状态容器（架构重构 Phase 1 核心）。
 *
 * 职责：
 * - 持有全部 UI 状态（场景/选中/编辑/回放/测量/弹窗）
 * - 所有业务操作入口（Do/Undo/Next/编辑/复制/护航队/保存/导出）
 * - [revision] 显式版本号：ScenarioFile 就地可变、引用不变，
 *   每次操作后自增，UI 以其为重组 key（替代旧 turnTick hack）
 *
 * MainActivity 只保留：SAF 文件选择注册 + Compose 组合 + 事件转发。
 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ScenarioRepository(application)

    // ---- 视口/地图（放 ViewModel：跨配置旋转保留视野与已加载地图） ----
    val camera = Camera()
    val mapRenderer = MapRenderer()

    // ---- 场景状态 ----
    var file by mutableStateOf<ScenarioFile?>(null)
        private set

    // 以下为纯 UI 交互状态（点选/编辑/测量/计算弹窗），UI 直接读写
    var selectedUnitId by mutableStateOf<String?>(null)
    var editUnit by mutableStateOf<SimUnit?>(null)
    var measureMode by mutableStateOf(false)
    var showCalcPosition by mutableStateOf(false)

    /** 已完成的测量（桌面版 Measurement，用于 CSV 导出 + 画布留存绘制）：起终点世界坐标
     *  SnapshotStateList：draw 阶段迭代读 → 变更即触发 Canvas 失效重绘（反馈①修复核心） */
    val measureLog = mutableStateListOf<Pair<Pair<Long, Long>, Pair<Long, Long>>>()

    /** 符号风格（桌面版玩家设置：NTDS / CWS） */
    var symbolStyle by mutableStateOf(com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS)

    /** 显式版本号：任何场景变更后自增，驱动 Compose 重组（替代 turnTick） */
    var revision by mutableStateOf(0)
        private set

    var rangeExhaustedUnit by mutableStateOf<SimUnit?>(null)
        private set

    // ---- 回放状态 ----
    var replayTimeline by mutableStateOf<List<ReplayEngine.Frame>>(emptyList())
        private set
    var replayIndex by mutableStateOf(0)
        private set
    var replayPlaying by mutableStateOf(false)
        private set
    var replayDelayMs by mutableStateOf(1000L)
        private set

    // ---- 一次性消息（Toast），UI 订阅 ----
    private val _toasts = MutableStateFlow<String?>(null)
    val toasts: StateFlow<String?> = _toasts.asStateFlow()

    fun toast(msg: String) {
        _toasts.value = msg
    }

    /** 消费当前 Toast（UI 显示后调用，避免重复弹） */
    fun clearToast() {
        _toasts.value = null
    }

    // ============ 场景加载 ============

    /** 从任意 URI 加载存档，并尝试自动加载场景自带地图 */
    fun loadScenario(uri: Uri) {
        try {
            val loaded = repo.load(uri)
            applyLoaded(loaded)
            loaded.scenario.mapFileName.takeIf { it.isNotBlank() }?.let { mapName ->
                autoLoadMap(mapName, uri)
            }
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    fun applyLoaded(loaded: ScenarioFile) {
        file = loaded
        selectedUnitId = null
        revision++
        // 视野自适应由 SceneCanvas.onSizeChanged 按真实画布尺寸执行
        toast("已加载场景：${loaded.scenario.scenarioName}（${loaded.units.size} 单位）")
        // Bug 1 防御：Gson 对缺 Side 字段的单位静默落默认值 "Blue"（Unit.side 默认值）
        // → 单位非空且全部为 Blue 时提示用户场景可能缺 Side 字段；
        // 真实场景（如冰海巨兽含 Red）不会命中，不误报
        val units = loaded.units
        if (units.isNotEmpty() && units.all { it.side == "Blue" }) {
            toast("场景单位缺少 Side 字段，已按蓝方显示")
        }
    }

    /** 加载地图文件：.json → MapMaker 配置；图片 → 位图 */
    fun loadMapFile(uri: Uri) {
        try {
            val name = queryDisplayName(uri)?.lowercase() ?: ""
            if (name.endsWith(".json")) {
                val text = openText(uri) ?: throw IllegalStateException("无法读取地图配置")
                mapRenderer.parseMapConfigJson(text)
                toast("地图配置已加载${if (mapRenderer.pendingBackgroundName != null) "：${mapRenderer.pendingBackgroundName}" else ""}")
            } else {
                mapRenderer.loadMapImage(getApplication<Application>().contentResolver, uri)
                toast("地图图片已加载")
            }
        } catch (e: Exception) {
            toast("地图加载失败：${e.message}")
        }
    }

    /** 场景 MapFileName 指向的地图：从场景同目录解析配置 + 背景图 */
    private fun autoLoadMap(mapName: String, scenarioUri: Uri) {
        try {
            val cfgUri = findSibling(scenarioUri, mapName)
            if (cfgUri != null) {
                val text = openText(cfgUri) ?: return
                mapRenderer.parseMapConfigJson(text)
                mapRenderer.pendingBackgroundName?.let { bg ->
                    val bgUri = findSibling(scenarioUri, bg)
                    if (bgUri != null) mapRenderer.loadMapImage(getApplication<Application>().contentResolver, bgUri)
                }
                toast("已加载地图：$mapName")
            }
        } catch (e: Exception) {
            // 地图缺失不阻塞场景加载
        }
    }

    /** 在 URI 所在目录按文件名查找兄弟文件（document id 最后一段替换，文件名需 URL 编码） */
    private fun findSibling(uri: Uri, name: String): Uri? {
        val docId = uri.lastPathSegment ?: return null
        val slash = docId.lastIndexOf('/')
        val parent = if (slash >= 0) docId.substring(0, slash + 1) else ""
        return Uri.parse("content://" + uri.authority + "/document/" + parent + Uri.encode(name))
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openText(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    // ============ 保存 / 导出 ============

    /** 保存四文件（红蓝视角按感知过滤） */
    fun saveThreeFiles(directory: Uri) {
        val current = file ?: run {
            toast("请先打开一个场景")
            return
        }
        try {
            val fileName = current.scenario.scenarioName.ifBlank { "scenario" }
            val blueView = FogOfWar.applyPerspective(current, "Blue")
            val redView = FogOfWar.applyPerspective(current, "Red")
            repo.savePerceptionAware(directory, fileName, current, blueView, redView)
            toast("已保存：$fileName.json + Blue.SpScn + Red.SpScn")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    /** 导出运动命令（桌面版 WindowExportOrders） */
    fun exportMovementOrders(directory: Uri) {
        val f = file ?: return
        try {
            repo.exportMovementOrders(directory, "Player", f.units)
            toast("已导出运动命令：Movement - Player.json")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    // ============ 回合操作 ============

    fun doTurn() {
        val f = file ?: return
        // 门禁（反馈②③）：非 DO_BEFORE/DO_NEXT 状态禁止 Do，不产生任何副作用
        if (!TurnState.canDo(TurnState.detect(f))) { toast("当前状态不可 Do（请先 Undo 或 Next）"); return }
        MovementEngine.advance(f, f.time.currentTurnInterval)
        revision++
        // Range 耗尽检测：桌面版三选弹窗（Continue/Delete/Stop）；已选"继续移动"不再提示
        f.units.firstOrNull { it.range == 0 && !it.showSunk && !it.ignoreRange }?.let { rangeExhaustedUnit = it }
        toast("Do：已移动至 ${f.time.currentPositionTime}")
    }

    fun undo() {
        val f = file ?: return
        // 门禁（反馈②③）：仅 Do 后未确认可 Undo（拦截 DO_BEFORE 下回退时间的危险路径）
        if (!TurnState.canUndo(TurnState.detect(f))) { toast("当前状态不可 Undo"); return }
        TurnState.undo(f, f.time.currentTurnInterval)
        revision++
        toast("Undo")
    }

    fun next() {
        val f = file ?: return
        // 门禁（反馈②③）：仅 Do 后未确认可 Next
        if (!TurnState.canNext(TurnState.detect(f))) { toast("请先 Do 再 Next 确认"); return }
        TurnState.confirmNext(f, f.time.currentTurnInterval)
        revision++
        toast("Next：回合已确认")
    }

    // ============ 单位编辑 ============

    fun applyEdit(unit: SimUnit) {
        file?.let { f ->
            val idx = f.units.indexOfFirst { it.idNum == unit.idNum }
            if (idx >= 0) f.units[idx] = unit
            revision++
        }
    }

    fun deleteUnit(unit: SimUnit) {
        file?.let { f ->
            f.units.removeAll { it.idNum == unit.idNum }
            f.objects.removeAll { it == unit.idNum }
            selectedUnitId = null
            revision++
        }
    }

    /**
     * 单位复制（桌面版 CopyUnit）：深拷贝 + 新 IdNum/TrackNumber + 附近偏移。
     * 陆上单位（Installation/LandFormation）不复制传感器/武器。
     */
    fun duplicateUnit(unit: SimUnit) {
        file?.let { f ->
            val gson = com.simplot.android.data.codec.JsonUtil.gson
            val copy = gson.fromJson(gson.toJson(unit), SimUnit::class.java)
            copy.idNum = nextId(f)
            copy.trackNumber = (f.units.maxOfOrNull { it.trackNumber } ?: 2400) + 1
            copy.name = unit.name + " (副本)"
            copy.isNewThisTurn = true
            val (dx, dy) = CoordUtil.offsetNm(135.0, 2.0)
            copy.x = unit.x + dx
            copy.y = unit.y + dy
            if (copy.idNum.startsWith("L")) {
                copy.sensorArray = null
                copy.weaponArray = null
            }
            f.units.add(copy)
            selectedUnitId = copy.idNum
            revision++
            toast("已复制：${copy.name}")
        }
    }

    /**
     * 生成护航队（桌面版 Game.Convoy.CreateConvoy）：
     * COMMODORE 居中，Merchant 环绕，dist=2000 码，角度均匀分布。
     * FormationDistance 单位 = 文件单位（×100000 海里定点）。
     */
    fun createConvoy() {
        file?.let { f ->
            val commodore = SimUnit(
                idNum = nextId(f),
                side = "Blue",
                unitType = "Merchant",
                unitClass = "AO",
                name = "COMMODORE",
                trackNumber = (f.units.maxOfOrNull { it.trackNumber } ?: 2400) + 1,
                isNewThisTurn = true,
                isFormationCenter = true,
                formationName = "Convoy"
            )
            val units = mutableListOf(commodore)
            val escortCount = 6
            val distFile = CoordUtil.yardsToFile(2000.0).toInt()
            for (i in 0 until escortCount) {
                val angle = 360.0 / escortCount * i
                val (dx, dy) = CoordUtil.offsetYards(angle, 2000.0)
                units.add(
                    SimUnit(
                        idNum = nextId(f),
                        side = "Blue",
                        unitType = "Merchant",
                        unitClass = "AO",
                        name = "Merchant ${i + 1}",
                        trackNumber = (f.units.maxOfOrNull { it.trackNumber } ?: 2400) + 1 + i + 1,
                        x = commodore.x + dx,
                        y = commodore.y + dy,
                        isNewThisTurn = true,
                        isInFormation = true,
                        formationName = "Convoy",
                        formationBearing = (angle * 1000).toInt(),
                        formationDistance = distFile
                    )
                )
            }
            f.units.addAll(units)
            revision++
            toast("已生成护航队：1 COMMODORE + $escortCount 商船")
        }
    }

    /** 新 IdNum（桌面版：Domain 首字母 + 递增序号） */
    private fun nextId(f: ScenarioFile): String {
        val prefix = "S"
        var max = 0
        f.units.forEach { u ->
            u.idNum.removePrefix(prefix).toIntOrNull()?.let { if (it > max) max = it }
        }
        return prefix + (max + 1).toString().padStart(3, '0')
    }

    /** 测量完成回调：记录测量线（不自动退出测量模式，可连续画多条对照；退出由按钮/选中单位触发） */
    fun onMeasureComplete(start: Pair<Long, Long>, end: Pair<Long, Long>) {
        measureLog.add(start to end)
        // 修复 B：画完不自动退出测量模式（用户可连续画多条对照）；由「退出测量」按钮或选中单位时退出
        toast("已记录测量线 ${measureLog.size} 条")
    }

    /** 清除全部已保存测量线（退出测量模式时调用，修复 B） */
    fun clearMeasures() {
        measureLog.clear()
    }

    /** 导出测量 CSV（桌面版 Measurement CSV：TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI/Yards/Meters） */
    fun exportMeasureCsv(directory: Uri) {
        if (measureLog.isEmpty()) {
            toast("无测量记录")
            return
        }
        try {
            val sb = StringBuilder()
            sb.append("TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI,Range Yards,Range Meters\n")
            measureLog.forEachIndexed { i, (start, end) ->
                val bearing = CoordUtil.bearingDeg(start.first, start.second, end.first, end.second)
                val distNm = CoordUtil.distanceNm(start.first, start.second, end.first, end.second)
                val distYards = distNm * CoordUtil.YARDS_PER_NMI
                val distMeters = distNm * 1852.0
                sb.append("M${i + 1},")
                sb.append("${start.first},${start.second},")
                sb.append("0,0,0,")
                sb.append(String.format("%.1f,%.2f,%.1f,%.1f\n", bearing, distNm, distYards, distMeters))
            }
            val uri = repo.createFile(directory, "Measurements.csv", "text/csv")
            repo.writeText(uri, sb.toString())
            toast("已导出测量 CSV：${measureLog.size} 条")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    /** 切换符号风格 */
    fun toggleSymbolStyle() {
        symbolStyle = if (symbolStyle == com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS)
            com.simplot.android.render.UnitRenderer.SymbolStyle.CWS
        else
            com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS
        toast("符号风格：${symbolStyle}")
    }

    // ============ 弹窗 / 测量 ============

    fun dismissRangeDialog() {
        rangeExhaustedUnit = null
    }

    /** Range 耗尽三选：继续移动（无视 Range） */
    fun continueRangeExhausted() {
        val u = rangeExhaustedUnit ?: return
        rangeExhaustedUnit = null
        file?.let { f ->
            f.units.firstOrNull { it.idNum == u.idNum }?.let { it.ignoreRange = true }
            revision++
        }
        toast("${u.name} 继续（无视航程限制）")
    }

    /** Range 耗尽三选：删除单位 */
    fun deleteRangeExhausted() {
        val u = rangeExhaustedUnit ?: return
        rangeExhaustedUnit = null
        file?.let { f ->
            f.units.removeAll { it.idNum == u.idNum }
            f.objects.removeAll { it == u.idNum }
            selectedUnitId = null
            revision++
        }
        toast("已删除 ${u.name}")
    }

    /** Range 耗尽三选：停止单位（Range 置无限制并停船） */
    fun stopRangeExhausted() {
        val u = rangeExhaustedUnit ?: return
        rangeExhaustedUnit = null
        file?.let { f ->
            val t = f.units.firstOrNull { it.idNum == u.idNum }
            if (t != null) {
                t.range = MovementEngine.RANGE_UNLIMITED
                t.speed = 0
            }
            revision++
        }
        toast("${u.name} 已停止")
    }

    // ============ 回放 ============

    fun toggleReplay() {
        val f = file ?: return
        if (replayTimeline.isNotEmpty()) {
            replayTimeline = emptyList()
            replayPlaying = false
            replayIndex = 0
        } else {
            val tl = ReplayEngine.buildTimeline(f)
            if (tl.isEmpty()) {
                toast("当前场景无轨迹可回放")
            } else {
                replayTimeline = tl
                replayIndex = 0
                replayPlaying = false
            }
        }
    }

    fun setReplayFrame(index: Int) {
        replayIndex = index.coerceIn(0, (replayTimeline.size - 1).coerceAtLeast(0))
    }

    fun toggleReplayPlay() {
        if (replayIndex >= replayTimeline.size - 1 && !replayPlaying) replayIndex = 0
        replayPlaying = !replayPlaying
    }

    fun setReplaySpeed(delayMs: Long) {
        replayDelayMs = delayMs
    }

    /** 回放自动播放：UI LaunchedEffect 每帧调用 */
    fun replayTick() {
        if (replayPlaying && replayIndex < replayTimeline.size - 1) {
            replayIndex += 1
            if (replayIndex >= replayTimeline.size - 1) replayPlaying = false
        }
    }

    override fun onCleared() {
        mapRenderer.release()
        super.onCleared()
    }
}
