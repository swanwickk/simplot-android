package com.simplot.android.ui

import android.app.Application
import android.content.Context
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
    private val settingsRepo = com.simplot.android.data.repo.SettingsRepository(application)

    // ---- 视口/地图（放 ViewModel：跨配置旋转保留视野与已加载地图） ----
    val camera = Camera()
    val mapRenderer = MapRenderer()

    // ---- 场景状态 ----
    var file by mutableStateOf<ScenarioFile?>(null)
        private set

    /** 当前打开存档的 URI（供自动加载同目录地图等） */
    var currentUri by mutableStateOf<Uri?>(null)
        private set

    // 以下为纯 UI 交互状态（点选/编辑/测量/计算弹窗），UI 直接读写
    var selectedUnitId by mutableStateOf<String?>(null)
    var editUnit by mutableStateOf<SimUnit?>(null)
    var measureMode by mutableStateOf(false)
    /** 弧（传感器/武器）编辑目标单位（P1：RangeGraphics 编辑入口） */
    var editArcUnit by mutableStateOf<SimUnit?>(null)
    /** 航路点编辑目标单位（P1：WindowWaypoints 对应） */
    var editWaypointsUnit by mutableStateOf<SimUnit?>(null)
    /** 新建单位弹窗开关（P1：桌面版各类型新建窗口入口） */
    var showNewUnit by mutableStateOf(false)
    /** 新位置计算器开关（P2 恢复：桌面版 ContainerNewPosition） */
    var showNewPosition by mutableStateOf(false)
    /** 护航队创建弹窗开关（P2 恢复：桌面版 WindowConvoy） */
    var showConvoy by mutableStateOf(false)

    /** 玩家显示设置（R4：桌面版 PlayerSettings，本地持久化） */
    var settings by mutableStateOf(settingsRepo.load())
        private set
    /** 设置弹窗开关 */
    var showSettings by mutableStateOf(false)

    /** 已完成的测量（桌面版 Measurement，用于 CSV 导出 + 画布留存绘制）：起终点世界坐标
     *  SnapshotStateList：draw 阶段迭代读 → 变更即触发 Canvas 失效重绘（反馈①修复核心） */
    val measureLog = mutableStateListOf<Pair<Pair<Long, Long>, Pair<Long, Long>>>()

    /** 符号风格（桌面版玩家设置：NTDS / CWS）；契约8：默认 CWS（打开存档即显示类型独特精灵图标，NTDS 仍可手动切换） */
    var symbolStyle by mutableStateOf(com.simplot.android.render.UnitRenderer.SymbolStyle.CWS)

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

    // ============ 玩家设置（R4） ============

    /** 更新并持久化玩家设置 */
    fun updateSettings(transform: (com.simplot.android.domain.model.PlayerSettings) -> com.simplot.android.domain.model.PlayerSettings) {
        val next = transform(settings)
        settings = next
        settingsRepo.save(next)
    }

    /** 直接应用玩家设置（对话框保存） */
    fun applySettings(new: com.simplot.android.domain.model.PlayerSettings) {
        settings = new
        settingsRepo.save(new)
    }

    /** 开关切换便捷方法 */
    fun toggleSetting(set: (com.simplot.android.domain.model.PlayerSettings) -> com.simplot.android.domain.model.PlayerSettings) = updateSettings(set)

    // ============ 场景加载 ============

    /** 从任意 URI 加载存档，并尝试自动加载场景自带地图 */
    fun loadScenario(uri: Uri) {
        currentUri = uri
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

    /** 加载地图文件：.json → MapMaker 配置；.map/.txt → 光栅地图配置；图片 → 位图 */
    fun loadMapFile(uri: Uri) {
        try {
            val name = queryDisplayName(uri)?.lowercase() ?: ""
            if (name.endsWith(".json")) {
                val text = openText(uri) ?: throw IllegalStateException("无法读取地图配置")
                mapRenderer.parseMapConfigJson(text)
                toast("地图配置已加载${if (mapRenderer.pendingBackgroundName != null) "：${mapRenderer.pendingBackgroundName}" else ""}")
            } else if (name.endsWith(".map") || name.endsWith(".txt")) {
                // R5：光栅地图（桌面版 MercatorRaster）MAP/SCALE/CITY/COUNTRY
                val text = openText(uri) ?: throw IllegalStateException("无法读取地图配置")
                val mapName = StringBuilder()
                val ok = mapRenderer.parser.parseRasterMap(text, mapName)
                if (mapName.isNotEmpty()) {
                    // 尝试加载同目录光栅图
                    val imgUri = findSibling(uri, mapName.toString())
                    if (imgUri != null) mapRenderer.loadMapImage(getApplication<Application>().contentResolver, imgUri)
                }
                toast(if (ok) "光栅地图已加载${if (mapName.isNotEmpty()) "：$mapName" else ""}" else "光栅地图已解析（缺 SCALE，标注未换算）")
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

    /**
     * 反馈⑱：保存三文件到指定目标文件（系统「保存为」对话框返回的 document uri）。
     * 目标文件写 Referee json；Blue/Red.SpScn 写到同一目录（由目标文件 documentId 推导父目录 tree uri）。
     */
    fun saveThreeFilesTo(targetUri: Uri) {
        val current = file ?: run {
            toast("请先打开一个场景")
            return
        }
        try {
            val blueView = FogOfWar.applyPerspective(current, "Blue")
            val redView = FogOfWar.applyPerspective(current, "Red")
            val parent = repo.parentTreeUri(targetUri)
            val saved = repo.saveTo(targetUri, parent, current, blueView, redView)
            toast(if (saved) "已保存：Referee json + Blue.SpScn + Red.SpScn" else "已保存 Referee json")
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

    /** 导入运动命令（桌面版 LoadMoveOrders）：恢复匹配单位的未来航路点 */
    fun importMovementOrders(uri: Uri) {
        val f = file ?: run { toast("请先打开一个场景"); return }
        try {
            val imported = repo.importMovementOrders(uri, f)
            revision++
            toast("已导入运动命令：${imported.size} 个单位恢复航路点")
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    /** 自动存档（桌面版 SaveAuto）：Do 回合后调用，写 Referee Turn N json 到场景目录（静默，不打扰） */
    fun autoSave() {
        val f = file ?: return
        val currentUri = currentUri ?: return
        try {
            val turnNo = f.turns.size + 1
            repo.saveAuto(currentUri, f, turnNo)
        } catch (e: Exception) {
            // 自动存档失败不阻塞推演
        }
    }

    /** 保存 Setup 文件（桌面版 SaveSetupFile）：与场景同格式标记 Setup */
    fun saveSetup(target: Uri) {
        val f = file ?: run { toast("请先打开一个场景"); return }
        try {
            if (repo.saveSetup(target, f)) toast("已保存 Setup 文件") else toast("保存 Setup 失败")
        } catch (e: Exception) {
            toast("保存 Setup 失败：${e.message}")
        }
    }

    // ============ 回合操作 ============

    fun doTurn() {
        val f = file ?: return
        // 门禁（反馈②③）：非 DO_BEFORE/DO_NEXT 状态禁止 Do，不产生任何副作用
        val result = com.simplot.android.domain.usecase.AdvanceTurnUseCase.execute(f, f.time.currentTurnInterval)
            ?: run { toast("当前状态不可 Do（请先 Undo 或 Next）"); return }
        revision++
        // Range 耗尽检测：桌面版三选弹窗（Continue/Delete/Stop）；已选"继续移动"不再提示
        result.rangeExhausted.firstOrNull()?.let { id -> rangeExhaustedUnit = f.units.firstOrNull { it.idNum == id } }
        // 自动存档（桌面版 SaveAuto：每回合 Referee Turn N）
        autoSave()
        toast("Do：已移动至 ${result.newPositionTime}")
    }

    fun undo() {
        val f = file ?: return
        // 门禁（反馈②③）：仅 Do 后未确认可 Undo（拦截 DO_BEFORE 下回退时间的危险路径）
        if (!com.simplot.android.domain.usecase.AdvanceTurnUseCase.undo(f, f.time.currentTurnInterval)) {
            toast("当前状态不可 Undo"); return
        }
        revision++
        toast("Undo")
    }

    fun next() {
        val f = file ?: return
        // 门禁（反馈②③）：仅 Do 后未确认可 Next
        if (!com.simplot.android.domain.usecase.AdvanceTurnUseCase.next(f, f.time.currentTurnInterval)) {
            toast("请先 Do 再 Next 确认"); return
        }
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

    /** 弧（传感器/武器）编辑应用：替换目标单位的 SensorArray/WeaponArray */
    fun applyArcEdit(unit: SimUnit, sensors: List<com.simplot.android.data.model.Sensor>, weapons: List<com.simplot.android.data.model.Weapon>) {
        file?.let { f ->
            val idx = f.units.indexOfFirst { it.idNum == unit.idNum }
            if (idx >= 0) {
                f.units[idx].sensorArray = sensors.toMutableList()
                f.units[idx].weaponArray = weapons.toMutableList()
            }
            revision++
        }
    }

    /** 航路点编辑应用：替换目标单位的 FutureWaypointArray */
    fun applyWaypointsEdit(unit: SimUnit, waypoints: List<com.simplot.android.data.model.Waypoint>) {
        file?.let { f ->
            val idx = f.units.indexOfFirst { it.idNum == unit.idNum }
            if (idx >= 0) f.units[idx].futureWaypointArray = waypoints.toMutableList()
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

    /** 新 IdNum（桌面版：Domain 首字母 + 递增序号） */
    private fun nextId(f: ScenarioFile, prefix: String = "S"): String {
        var max = 0
        f.units.forEach { u ->
            u.idNum.removePrefix(prefix).toIntOrNull()?.let { if (it > max) max = it }
        }
        return prefix + (max + 1).toString().padStart(3, '0')
    }

    /**
     * 新建单位（P1：桌面版各类型 NewUnit 窗口）。按 Domain 分派前缀与类型，插入到场景中。
     */
    fun createNewUnit(
        domain: com.simplot.android.domain.registry.UnitTypeRegistry.Domain,
        name: String, unitType: String, unitClass: String,
        side: String, x: Long, y: Long
    ) {
        file?.let { f ->
            val prefix = when (domain) {
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SURFACE -> "S"
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.AIR -> "A"
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SUBSURFACE -> "U"
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.VEHICLE -> "V"
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.INSTALLATION -> "I"
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.LAND_FORMATION -> "L"
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.REFERENCE_POINT -> "R"
                com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SONOBUOY -> "B"
                else -> "S"
            }
            val newUnit = SimUnit(
                idNum = nextId(f, prefix),
                side = side,
                name = name,
                unitType = unitType,
                unitClass = unitClass,
                trackNumber = (f.units.maxOfOrNull { it.trackNumber } ?: 2400) + 1,
                x = x, y = y,
                isNewThisTurn = true
            )
            f.units.add(newUnit)
            selectedUnitId = newUnit.idNum
            revision++
            toast("已新建：${newUnit.name}")
        }
    }

    /**
     * 创建护航队（P2 恢复，桌面版 Game.Convoy.CreateConvoy）：
     * COMMODORE 居中，Merchant 环绕，dist=2000 码，角度均匀分布。
     * 逻辑在 [com.simplot.android.domain.engine.ConvoyEngine]（纯 Kotlin 可单测）。
     */
    fun createConvoy(commodoreName: String = "COMMODORE", escortCount: Int = 6, distYards: Int = 2000) {
        file?.let { f ->
            val units = com.simplot.android.domain.engine.ConvoyEngine.build(
                f,
                com.simplot.android.domain.engine.ConvoyEngine.ConvoySpec(
                    commodoreName = commodoreName,
                    escortCount = escortCount,
                    distYards = distYards
                ),
                nextId = { prefix -> nextId(f, prefix) }
            )
            f.units.addAll(units)
            revision++
            toast("已创建护航队：1 指挥舰 + $escortCount 商船")
        }
    }

    /**
     * 新位置计算（P2 恢复，桌面版 ContainerNewPosition.PushCalcPosition）：
     * 参考单位 + 方位角 + 距离 → 新坐标，toast 显示。
     */
    fun calcNewPosition(refId: String, bearingDeg: Double, distNm: Double) {
        val f = file ?: return
        val ref = f.units.firstOrNull { it.idNum == refId } ?: run {
            toast("请选择参考单位")
            return
        }
        val (nx, ny) = com.simplot.android.domain.engine.CalcEngine.newPosition(ref.x, ref.y, bearingDeg, distNm)
        toast("${ref.name}：方位 $bearingDeg° 距离 $distNm nmi\nX=$nx Y=$ny")
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

    /** 切换符号风格：NTDS → CWS → WW2 → NTDS（R5：三态循环） */
    fun toggleSymbolStyle() {
        symbolStyle = when (symbolStyle) {
            com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS -> com.simplot.android.render.UnitRenderer.SymbolStyle.CWS
            com.simplot.android.render.UnitRenderer.SymbolStyle.CWS -> com.simplot.android.render.UnitRenderer.SymbolStyle.WW2
            com.simplot.android.render.UnitRenderer.SymbolStyle.WW2 -> com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS
        }
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
