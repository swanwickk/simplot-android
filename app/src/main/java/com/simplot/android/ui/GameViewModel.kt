package com.simplot.android.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit as SimUnit
import com.simplot.android.data.model.shiftWaypoints
import com.simplot.android.data.repo.ScenarioRepository
import com.simplot.android.data.util.CoordUtil
import com.simplot.android.domain.engine.FormationEngine
import com.simplot.android.domain.engine.FormationEngine.FormationSpec
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
 * Show Side 视图过滤（G30，桌面版 Show Side 菜单 All/Blue/Red）。
 * 仅影响当前视图展示与命中检测，不落盘、不改变引擎状态。
 * 纯 Kotlin 顶层枚举 → 可 JVM 单测。
 */
enum class ShowSide(val sideName: String?) {
    ALL(null), BLUE("Blue"), RED("Red");

    /** 单位（side 为空/未知按不过滤处理）是否属于当前视图 */
    fun allows(side: String?): Boolean = sideName == null || side == sideName
}

/** ShowSide 中文标签（纯函数，UI 与测试共用） */
fun showSideLabel(side: ShowSide): String = when (side) {
    ShowSide.ALL -> "全部"
    ShowSide.BLUE -> "蓝方"
    ShowSide.RED -> "红方"
}

/**
 * G10 自动存档门禁（桌面 WindowControlOptions CheckAutoSave + SaveAuto 前置条件）。
 * 顶层纯函数 → 可 JVM 单测：开关关 / 无场景 / 无 URI 均不执行自动存档。
 */
fun autoSaveGate(enabled: Boolean, hasFile: Boolean, hasUri: Boolean): Boolean = enabled && hasFile && hasUri

/**
 * G11 错误日志追加（桌面 WindowErrorLog UpdateErrorLog 的本地载体）：
 * 新条目插队首（最新在前），超过 [cap] 上限裁剪尾部，防止无限增长。
 * 顶层纯函数（入参显式传时间戳）→ 可 JVM 单测。返回实际写入的条目。
 */
fun appendErrorLogEntry(log: MutableList<String>, msg: String, timestamp: String, cap: Int = 200): String {
    val entry = "[$timestamp] $msg"
    log.add(0, entry)
    while (log.size > cap) log.removeAt(log.size - 1)
    return entry
}

/** G11 日志时间戳（yyyy-MM-dd HH:mm:ss；默认当前时间） */
fun formatLogTime(now: java.util.Date = java.util.Date()): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(now)

/** Domain → IdNum 前缀（桌面版 GetIdNumber 分派；顶层纯函数，G29 粘贴与单测共用） */
fun domainPrefixOf(u: SimUnit): String = when (com.simplot.android.domain.registry.UnitTypeRegistry.domainOf(u)) {
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

/**
 * 新 IdNum（桌面版 GetIdNumber：全局自增，LastId 存于场景 JSON）。
 * 取 max(现有同类最大, scenario.lastId) + 1 并写回 lastId（删除单位后不回退）。
 * 顶层纯函数（G29 粘贴与单测共用）。
 */
fun nextIdFor(f: ScenarioFile, prefix: String = "S"): String {
    var max = 0
    f.units.forEach { u ->
        u.idNum.removePrefix(prefix).toIntOrNull()?.let { if (it > max) max = it }
    }
    val next = maxOf(max, f.scenario.lastId) + 1
    f.scenario.lastId = next
    return prefix + next.toString().padStart(3, '0')
}

/**
 * G29 粘贴核心逻辑（顶层纯函数，JVM 可单测）：
 * 深拷贝剪贴板单位 → 防撞号（IdNum 走 [nextIdFor]、TrackNumber 走 [TrackCounter.allocate]）
 * → 平移到粘贴位置（航路点随位移同步平移）→ 加入场景并返回新单位。
 */
fun pasteUnitInto(f: ScenarioFile, clipboard: SimUnit, x: Long, y: Long): SimUnit {
    val gson = com.simplot.android.data.codec.JsonUtil.gson
    val copy = gson.fromJson(gson.toJson(clipboard), SimUnit::class.java)
    copy.idNum = nextIdFor(f, domainPrefixOf(copy))
    copy.trackNumber = com.simplot.android.domain.engine.TrackCounter.allocate(f, copy.side)
    copy.name = clipboard.name + " (副本)"
    copy.isNewThisTurn = true
    val dx = x - clipboard.x
    val dy = y - clipboard.y
    copy.x = x
    copy.y = y
    if (dx != 0L || dy != 0L) {
        shiftWaypoints(copy.pastWaypointArray, dx, dy)
        shiftWaypoints(copy.futureWaypointArray, dx, dy)
    }
    f.units.add(copy)
    return copy
}

/**
 * G32 Relocate 核心逻辑（顶层纯函数，JVM 可单测）：
 * 移动单位到新位置并同步平移其历史/未来航路点（桌面版 CanvasMap_MouseDrag → RecalcWaypoints）。
 * @return 是否找到并移动了该单位
 */
fun relocateUnitInto(f: ScenarioFile, id: String, x: Long, y: Long): Boolean {
    val u = f.units.firstOrNull { it.idNum == id } ?: return false
    val dx = x - u.x
    val dy = y - u.y
    if (dx == 0L && dy == 0L) return true
    u.x = x
    u.y = y
    shiftWaypoints(u.pastWaypointArray, dx, dy)
    shiftWaypoints(u.futureWaypointArray, dx, dy)
    return true
}

/**
 * G01 新场景初始化核心逻辑（顶层纯函数，JVM 可单测）：
 * 构造空场景存档骨架（桌面版 WindowNewScenario → FileNewScenario → 空 Referee 存档）。
 * - name：场景名；startTime：起始日期时间（YYYY-MM-DD HH:MM:SS，双时钟同值）
 * - mapFileName 非空 → TypeOfMap=1（自定义地图）并记录文件名；否则 0（无地图）
 * - 计数器保持桌面默认（蓝方 2400 / 红方 9000 起点），LastId=0，无单位/回合/标注
 */
fun newScenarioFile(
    name: String,
    startTime: String,
    mapFileName: String? = null
): ScenarioFile = ScenarioFile(
    file = "Referee",
    simPlotVersion = "2.3",
    isIntegerFile = true,
    scenario = com.simplot.android.data.model.Scenario(
        scenarioName = name,
        lastId = 0,
        currentTrackNumber = 2400,
        currentPlayerTrackNumber = 9000,
        phase = 0,
        typeOfMap = if (!mapFileName.isNullOrBlank()) 1 else 0,
        mapFileName = mapFileName?.takeIf { it.isNotBlank() }
    ),
    typeOfGame = 0,
    time = com.simplot.android.data.model.TimeState(
        currentTurnTime = startTime,
        currentPositionTime = startTime
    ),
    turns = mutableListOf(),
    overlays = emptyMap(),
    objects = mutableListOf(),
    units = mutableListOf(),
    formations = emptyMap()
)

/** G01：起始日期时间格式校验（严格 YYYY-MM-DD HH:MM:SS，复用 TimeUtil 解析；顶层纯函数可单测） */
fun isValidScenarioStartTime(s: String): Boolean = try {
    com.simplot.android.data.util.TimeUtil.parse(s.trim())
    true
} catch (e: Exception) {
    false
}

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
    private val scenarioUseCases = com.simplot.android.domain.usecase.ScenarioUseCases(application)

    // ---- 视口/地图（放 ViewModel：跨配置旋转保留视野与已加载地图） ----
    val camera = Camera()
    val mapRenderer = MapRenderer()

    // ---- 场景状态 ----
    var file by mutableStateOf<ScenarioFile?>(null)
        private set

    /** 当前打开存档的 URI（供自动加载同目录地图等） */
    var currentUri by mutableStateOf<Uri?>(null)
        private set

    /** Misc 标注（R7：从 Overlays 解析，桌面版 MiscBox/Oval/Line/Polygon/Label） */
    var miscAnnotations by mutableStateOf<List<com.simplot.android.domain.model.MiscAnnotation>>(emptyList())
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
    /** 护航队创建弹窗开关（P2 恢复：桌面版 WindowConvoy） */
    var showConvoy by mutableStateOf(false)

    /** 玩家显示设置（R4：桌面版 PlayerSettings，本地持久化） */
    var settings by mutableStateOf(settingsRepo.load())
        private set
    /** 设置弹窗开关 */
    var showSettings by mutableStateOf(false)

    /** G10：自动存档开关（桌面 WindowControlOptions CheckAutoSave；默认开=桌面默认） */
    var autoSaveEnabled by mutableStateOf(true)
    /** 编队管理弹窗开关（R6：桌面版 WindowFormation） */
    var showFormation by mutableStateOf(false)
    /** G01：新场景创建弹窗开关（桌面版 File → New Scenario → WindowNewScenario） */
    var showNewScenario by mutableStateOf(false)
    /** G06：导出运动命令单位选择弹窗开关（桌面版 WindowExportOrders） */
    var showExportOrders by mutableStateOf(false)

    /** G01：新场景对话框已选地图文件名（SAF 选择后经 [rememberNewScenarioMapName] 回填；null=未选/无地图） */
    var newScenarioMapName by mutableStateOf<String?>(null)

    /** 已完成的测量（桌面版 Measurement，用于 CSV 导出 + 画布留存绘制）：起终点世界坐标
     *  SnapshotStateList：draw 阶段迭代读 → 变更即触发 Canvas 失效重绘（反馈①修复核心） */
    val measureLog = mutableStateListOf<Pair<Pair<Long, Long>, Pair<Long, Long>>>()

    /**
     * 符号风格（G47 重构：由 PlayerSettings.symbolSet / ww2Symbols 派生，不再独立存状态）。
     * 兼容保留（MainActivity 顶栏按钮显示/循环用）：WW2 附加切换开 → WW2；
     * 符号集=NTDS → NTDS；其余（CWS 三变体）→ CWS。契约8：默认 CWS（=CWS Color Filled）。
     */
    var symbolStyle: com.simplot.android.render.UnitRenderer.SymbolStyle
        get() = when {
            settings.ww2Symbols -> com.simplot.android.render.UnitRenderer.SymbolStyle.WW2
            settings.symbolSet == com.simplot.android.domain.model.SymbolSet.NTDS ->
                com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS
            else -> com.simplot.android.render.UnitRenderer.SymbolStyle.CWS
        }
        set(_) { /* 只读派生：切换请走 toggleSymbolStyle() 或设置对话框 */ }

    /** 显式版本号：任何场景变更后自增，驱动 Compose 重组（替代 turnTick） */
    var revision by mutableStateOf(0)
        private set

    var rangeExhaustedUnit by mutableStateOf<SimUnit?>(null)
        private set

    /** G30：Show Side 视图过滤（All/Blue/Red，仅影响视图，不落盘） */
    var showSide by mutableStateOf(ShowSide.ALL)
        private set

    /** G40：到达最终航路点三选弹窗当前单位（桌面 NoFutureWaypoints） */
    var finalWaypointUnit by mutableStateOf<SimUnit?>(null)
        private set

    /** G29：剪贴板单位（桌面 Copy Unit → 剪贴板；Paste 时防撞号分配并放置到任意位置） */
    var clipboardUnit by mutableStateOf<SimUnit?>(null)
        private set

    /** G40：一次 Do 多艘船到达最终航路点时的待弹队列（逐个弹出） */
    private val finalWaypointQueue = mutableListOf<String>()

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

    // ---- G11：错误日志（桌面 WindowErrorLog Listbox1 + UpdateErrorLog）----
    // 内存滚动日志：toast 通道顺带记录（带时间戳，最新在前），设置弹窗内可查看/清空。
    val errorLog = mutableStateListOf<String>()

    fun toast(msg: String) {
        _toasts.value = msg
        // G11：顺带写入内存错误日志（toast 含错误与关键操作反馈，供排查场景加载/文件错误）
        appendErrorLogEntry(errorLog, msg, formatLogTime())
    }

    /** G11：显式记录错误日志（不经 toast，静默记录） */
    fun logError(msg: String) = appendErrorLogEntry(errorLog, msg, formatLogTime())

    /** G11：清空错误日志（桌面 WindowErrorLog 清空语义） */
    fun clearErrorLog() {
        errorLog.clear()
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
            val loaded = scenarioUseCases.load(uri).getOrElse { throw it }
            applyLoaded(loaded)
            // G55：加载场景目录内 player_settings.json 并应用（桌面 LoadFile → LoadPlayerSettings）。
            // 语义：文件设置覆盖内存设置（渲染/导出玩家名即时生效）；不写本地 SharedPreferences
            // （本地是全局默认，文件是场景级设置，两者互不覆盖）。
            repo.parentTreeUri(uri)?.let { dir ->
                repo.loadPlayerSettings(dir)?.let { fileSettings ->
                    settings = fileSettings
                    toast("已应用场景目录玩家设置")
                }
            }
            loaded.scenario.mapFileName?.takeIf { it.isNotBlank() }?.let { mapName ->
                autoLoadMap(mapName, uri)
            }
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    fun applyLoaded(loaded: ScenarioFile) {
        file = loaded
        selectedUnitId = null
        // R7：解析 Overlays → Misc 标注
        miscAnnotations = com.simplot.android.domain.engine.MiscAnnotationParser.parse(loaded.overlays)
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
     * G55：保存时回写当前玩家设置到场景目录（桌面 SaveFile → SavePlayerSettings）。
     */
    fun saveThreeFilesTo(targetUri: Uri) {
        val current = file ?: run {
            toast("请先打开一个场景")
            return
        }
        try {
            val saved = scenarioUseCases.saveThreeFiles(targetUri, current).getOrElse { throw it }
            repo.parentTreeUri(targetUri)?.let { repo.savePlayerSettings(it, settings) }
            toast(if (saved) "已保存：Referee json + Blue.SpScn + Red.SpScn（含玩家设置）" else "已保存 Referee json")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    /**
     * G25：显式保存玩家设置到场景目录（桌面 File → Save Player Settings → SavePlayerSettings）。
     * 无场景/无法定位目录时给出提示（不弹系统对话框：目标固定为场景目录 player_settings.json）。
     */
    fun savePlayerSettingsToScenarioDir() {
        if (file == null) { toast("请先打开一个场景"); return }
        val parent = currentUri?.let { repo.parentTreeUri(it) }
        if (parent == null) { toast("无法定位场景目录（请先保存场景）"); return }
        repo.savePlayerSettings(parent, settings)
        toast("已保存玩家设置到场景目录：player_settings.json")
    }

    /**
     * 导出运动命令（桌面版 WindowExportOrders）；G06：支持单位子集 + 玩家名。
     * 缺省参数保持旧行为（全量单位 + 设置内玩家名）；玩家名空 → "Player"（R-P3 修复）。
     */
    fun exportMovementOrders(
        directory: Uri,
        units: List<SimUnit> = file?.units ?: emptyList(),
        playerName: String = settings.playerName
    ) {
        val f = file ?: return
        if (units.isEmpty()) { toast("无单位可导出"); return }
        try {
            val name = playerName.ifBlank { "Player" }
            repo.exportMovementOrders(directory, name, units)
            toast("已导出运动命令（${units.size} 个单位）：Movement - $name.json")
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

    /**
     * 自动存档（桌面版 SaveAuto）：Do 回合后调用，写 Referee Turn N json 到场景目录（静默，不打扰）。
     *
     * G54 核对（批次4）：触发时机与桌面版一致——
     * - 桌面：仅回合推进（ContainerTurn.PushNextTurn → CheckFutureWaypoints → SaveAuto）后自动存；UndoTurn 不存；
     * - 安卓：仅 doTurn() 成功后 autoSave()（undo()/next() 均不触发），语义对齐；
     * - 文件名 "Referee Turn <N>_<回合时间>.json"（冒号→下划线）与桌面 SaveAuto 反汇编
     *   字符串 ['Referee Turn ', ':', '_'] + ReplaceAll 逐字符一致；
     * - CheckAutoSave 开关已落地（G10：autoSaveEnabled + autoSaveGate 门禁，默认开）。
     */
    fun autoSave() {
        // G10：开关关 / 无场景 / 无 URI → 不执行（门禁逻辑集中，autoSaveGate 可单测）
        if (!autoSaveGate(autoSaveEnabled, file != null, currentUri != null)) return
        val f = file ?: return
        val currentUri = currentUri ?: return
        try {
            val turnNo = f.turns.size + 1
            scenarioUseCases.saveAuto(currentUri, f, turnNo)
            // G55：桌面 SaveAuto 也回写玩家设置（反汇编确认 SaveAuto 调用 SavePlayerSettings）
            repo.parentTreeUri(currentUri)?.let { repo.savePlayerSettings(it, settings) }
        } catch (e: Exception) {
            // 自动存档失败不阻塞推演
        }
    }

    /** 保存 Setup 文件（桌面版 SaveSetupFile）：与场景同格式标记 Setup；G55：同目录回写玩家设置 */
    fun saveSetup(target: Uri) {
        val f = file ?: run { toast("请先打开一个场景"); return }
        try {
            if (scenarioUseCases.saveSetup(target, f)) {
                repo.parentTreeUri(target)?.let { repo.savePlayerSettings(it, settings) }
                toast("已保存 Setup 文件")
            } else toast("保存 Setup 失败")
        } catch (e: Exception) {
            toast("保存 Setup 失败：${e.message}")
        }
    }

    // ============ G28：单位级导入导出（桌面 Units → Import Unit / Export Unit） ============

    /** G28：导出选中单位到目录（桌面 Export Unit）；无选中 → 提示 */
    fun exportSelectedUnit(directory: Uri) {
        val f = file ?: return
        val unit = selectedUnitId?.let { id -> f.units.firstOrNull { it.idNum == id } }
            ?: run { toast("请先选中要导出的单位"); return }
        try {
            repo.exportUnit(directory, unit)
            toast("已导出单位：${unit.name}（Unit ${unit.idNum}.json）")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    /** G28：导入单单位（桌面 Import Unit）；同 IdNum 替换 / 否则新增（基本版，不重分配 IdNum） */
    fun importUnit(uri: Uri) {
        val f = file ?: run { toast("请先打开一个场景"); return }
        try {
            val (unit, replaced) = repo.importUnit(uri, f)
            revision++
            toast(
                if (replaced) "已导入单位并替换：${unit.name}（${unit.idNum}）"
                else "已导入新单位：${unit.name}（${unit.idNum}）"
            )
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
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
        // G40：到达最终航路点三选弹窗（桌面 NoFutureWaypoints：Continue/Delete/Stop）。
        // 一次 Do 多艘船到达时排队逐个弹出；Range 弹窗优先（两个弹窗不叠加）。
        finalWaypointQueue.clear()
        finalWaypointQueue.addAll(result.finalWaypointReached)
        if (rangeExhaustedUnit == null) popNextFinalWaypoint()
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

    /** 标为沉没（桌面版 DeleteUnit 三选 "Show as Sunk"：单位保留但标沉没） */
    fun showAsSunk(unit: SimUnit) {
        file?.let { f ->
            val t = f.units.firstOrNull { it.idNum == unit.idNum }
            if (t != null) {
                t.showSunk = true
                t.speed = 0
                t.futureWaypointArray.clear()
            }
            revision++
            toast("${unit.name} 已标记为沉没")
        }
    }

    /**
     * G29：复制单位到剪贴板（桌面版 Copy Unit → 剪贴板，不再立即生成副本；
     * 粘贴时经 [pasteUnitInto] 分配防撞号并放置到指定位置）。
     * 深拷贝（Gson 往返）：后续对原单位的编辑不影响剪贴板内容。
     */
    fun copyUnitToClipboard(unit: SimUnit) {
        val gson = com.simplot.android.data.codec.JsonUtil.gson
        clipboardUnit = gson.fromJson(gson.toJson(unit), SimUnit::class.java)
        toast("已复制 ${unit.name}，选中任意单位后点 Paste 放置")
    }

    /** G29：粘贴剪贴板单位到指定位置（防撞号：IdNum/TrackNumber 走计数器分配，见 [pasteUnitInto]） */
    fun pasteUnit(x: Long, y: Long) {
        val f = file ?: return
        val clip = clipboardUnit ?: run { toast("剪贴板为空：请先在编辑窗口点「复制」"); return }
        val copy = pasteUnitInto(f, clip, x, y)
        selectedUnitId = copy.idNum
        revision++
        toast("已粘贴：${copy.name}")
    }

    /** G32：Relocate 拖拽移动（长按拖拽实时调用；同步平移该单位历史/未来航路点） */
    fun relocate(id: String, x: Long, y: Long) {
        val f = file ?: return
        if (relocateUnitInto(f, id, x, y)) revision++
    }

    /**
     * 新 IdNum（桌面版 GetIdNumber：全局自增，LastId 存于场景 JSON）。
     * P0 修复：max(现有同类最大, scenario.lastId) + 1，并写回 lastId（删除单位后不回退）。
     * 实现委托顶层纯函数 [nextIdFor]（G29 粘贴与单测共用同一逻辑）。
     */
    private fun nextId(f: ScenarioFile, prefix: String = "S"): String = nextIdFor(f, prefix)

    /**
     * 分配 TrackNumber（桌面版 GetTrackNumber/GetPlayerTrackNumber：蓝/红各一套计数器）。
     * P0 修复：红方用 currentPlayerTrackNumber，其余用 currentTrackNumber；
     * 取 max(计数器, 现有最大) + 1 并写回（删除后不回退）。
     * N2 修复：逻辑提取到 [com.simplot.android.domain.engine.TrackCounter]（可 JVM 单测），
     * 新建单位 / 复制单位 / 护航队三条路径共用。
     */
    private fun allocateTrackNumber(f: ScenarioFile, side: String): Int =
        com.simplot.android.domain.engine.TrackCounter.allocate(f, side)

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
                trackNumber = allocateTrackNumber(f, side),
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
     * 创建护航队（P2 恢复，桌面版 Game.Convoy.CreateConvoy）。
     * G03：参数契约 = [ConvoySpec]（含航向/速度/列行数/列行间距；列行>0 走网格布局）。
     * COMMODORE 居中，Merchant 环绕/网格，角度均匀分布或列×行排布。
     * 逻辑在 [com.simplot.android.domain.engine.ConvoyEngine]（纯 Kotlin 可单测）。
     */
    fun createConvoy(spec: com.simplot.android.domain.engine.ConvoyEngine.ConvoySpec = com.simplot.android.domain.engine.ConvoyEngine.ConvoySpec()) {
        file?.let { f ->
            val units = com.simplot.android.domain.engine.ConvoyEngine.build(
                f,
                spec,
                nextId = { prefix -> nextId(f, prefix) },
                // N2 修复：护航队也走 TrackNumber 计数器（与新建/复制单位一致），
                // 桌面续建不撞号（原 ConvoyEngine 本地 max+1 不写回 currentTrackNumber）
                nextTrackNumber = { side -> allocateTrackNumber(f, side) }
            )
            f.units.addAll(units)
            revision++
            toast("已创建护航队：1 指挥舰 + ${spec.merchantCount()} 商船")
        }
    }

    // ================= G01 新场景创建（桌面版 WindowNewScenario） =================

    /** G01：新场景默认起始时间（当前时刻，YYYY-MM-DD HH:MM:SS） */
    fun defaultScenarioStartTime(): String = com.simplot.android.data.util.TimeUtil.now()

    /** G01：记录新场景对话框所选地图文件名（从 SAF uri 查询显示名；地图本身已由 MainActivity 调 loadMapFile 加载预览） */
    fun rememberNewScenarioMapName(uri: Uri) {
        newScenarioMapName = queryDisplayName(uri)
    }

    /**
     * G01：创建新场景（桌面版 WindowNewScenario PushOk）。
     * 用 [newScenarioFile] 构造空 Referee 骨架替换当前场景，清空全部编辑/回放/编队状态，
     * 新场景立即可继续编辑（加单位/存航线）并走现有保存流程落盘。
     * 地图：选择时已加载到 mapRenderer（画布预览）；文件名写入 Scenario.MapFileName，
     * 保存后重开场景按桌面语义自动加载同目录地图。
     */
    fun createNewScenario(name: String, startTime: String, mapFileName: String?) {
        if (name.isBlank()) { toast("场景名不能为空"); return }
        if (!isValidScenarioStartTime(startTime)) { toast("起始时间格式应为 YYYY-MM-DD HH:MM:SS"); return }
        file = newScenarioFile(name.trim(), startTime, mapFileName)
        currentUri = null
        selectedUnitId = null
        editUnit = null
        editArcUnit = null
        editWaypointsUnit = null
        clipboardUnit = null
        rangeExhaustedUnit = null
        finalWaypointUnit = null
        finalWaypointQueue.clear()
        replayTimeline = emptyList()
        replayPlaying = false
        replayIndex = 0
        measureMode = false
        clearMeasures()
        miscAnnotations = emptyList()
        formationSpecs.clear()
        // 无地图的新场景清掉上一场景残留地图（parser 数据 + 背景位图）
        if (mapFileName.isNullOrBlank()) {
            mapRenderer.parser.clear()
            mapRenderer.bitmap = null
            mapRenderer.pendingBackgroundName = null
        }
        revision++
        toast("已创建新场景：${name.trim()}，起始 ${startTime}（可添加单位并保存）")
    }

    /**
     * 编队操作（R6：桌面版 Formations）。
     */
    /** 编队移动准备（DoPrepare）：为编队成员生成移动前轨迹点 */
    fun formationPrepare(formationName: String) {
        file?.let { f ->
            val n = com.simplot.android.domain.engine.FormationEngine.prepare(f.units, formationName)
            revision++
            toast("编队 $formationName 准备：$n 个成员记录位置")
        }
    }

    /** 编队移动撤销（DoCancel）：恢复成员到移动前位置 */
    fun formationCancel(formationName: String) {
        file?.let { f ->
            val n = com.simplot.android.domain.engine.FormationEngine.cancel(f.units, formationName)
            revision++
            toast("编队 $formationName 撤销：$n 个成员恢复")
        }
    }

    /** 把单位移出编队（RemoveUnitFromFormation） */
    fun removeFromFormation(unit: SimUnit) {
        com.simplot.android.domain.engine.FormationEngine.removeFromFormation(unit)
        revision++
        toast("${unit.name} 已移出编队")
    }

    /** 场景编队名列表 */
    fun formationNames(): List<String> = file?.let { com.simplot.android.domain.engine.FormationEngine.formationNames(it) } ?: emptyList()

    // ================= G02 编队编辑器：创建/重命名/删除/成员/设中心/类型/距离单位 =================

    /**
     * 编队规格表（G02：创建时定义的类型/距离单位）。
     * 空队形仅存内存注册表；存档层队形由成员单位携带字段，有成员才随存档持久化。
     */
    private val formationSpecs = mutableStateMapOf<String, FormationSpec>()

    /** 编队规格只读视图（FormationDialog 展示类型/距离单位用） */
    fun formationSpecs(): Map<String, FormationSpec> = formationSpecs

    /** 创建编队（命名 + 类型三选 + 距离单位；桌面版 CreateFormation） */
    fun formationCreate(name: String, type: String, distanceUnit: String) {
        if (name.isBlank()) { toast("队形名不能为空"); return }
        if (formationNames().any { it == name } || formationSpecs.containsKey(name)) {
            toast("编队 $name 已存在"); return
        }
        FormationEngine.registerFormation(formationSpecs, name, type, distanceUnit)
        revision++
        toast("编队 $name 已创建")
    }

    /** 重命名编队（桌面版队形名编辑） */
    fun formationRename(oldName: String, newName: String) {
        if (newName.isBlank()) { toast("队形名不能为空"); return }
        if (oldName == newName) return
        if (formationNames().any { it == newName } || formationSpecs.containsKey(newName)) {
            toast("编队 $newName 已存在"); return
        }
        file?.let { f -> FormationEngine.renameFormation(f.units, formationSpecs, oldName, newName) }
        revision++
        toast("编队 $oldName 已重命名为 $newName")
    }

    /** 删除编队（清全部成员队形标志 + 移除规格；桌面版 DeleteFormation） */
    fun formationDelete(name: String) {
        file?.let { f -> FormationEngine.deleteFormation(f.units, formationSpecs, name) }
        revision++
        toast("编队 $name 已删除")
    }

    /** 设中心单位（桌面版 SetCenter） */
    fun formationSetCenter(name: String, unitId: String) {
        file?.let { f ->
            if (FormationEngine.setCenter(f.units, name, unitId)) {
                revision++
                toast("编队 $name 中心已设为 $unitId")
            }
        }
    }

    /** 添加成员（桌面版 AddUnit；单位须在场景中） */
    fun formationMemberAdd(name: String, unitId: String) {
        file?.let { f ->
            val u = f.units.firstOrNull { it.idNum == unitId } ?: return
            FormationEngine.addMember(u, name, formationSpecs[name]?.type ?: FormationEngine.FormationTypes.COLUMN)
            revision++
            toast("${u.name} 已加入编队 $name")
        }
    }

    /** 移除成员（桌面版 RemoveUnit） */
    fun formationMemberRemove(name: String, unitId: String) {
        file?.let { f ->
            val u = f.units.firstOrNull { it.idNum == unitId } ?: return
            FormationEngine.removeMember(u)
            revision++
            toast("${u.name} 已移出编队 $name")
        }
    }

    /** 修改队形类型（规格 + 全部成员 formationType 同步） */
    fun formationSetType(name: String, type: String) {
        file?.let { f -> FormationEngine.setType(f.units, formationSpecs, name, type) }
        revision++
    }

    /** 修改距离单位（仅规格，显示层换算） */
    fun formationSetDistanceUnit(name: String, unit: String) {
        FormationEngine.setDistanceUnit(formationSpecs, name, unit)
        revision++
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

    /**
     * 导出相对位置 CSV（D5 决策：桌面版 ExportData.RelativeUnitPositions.Export）。
     * 表头：TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI,Range Yards,Range Meters
     * 每行 = 一个单位相对参考单位（选中单位，无则第一个）的方位/距离，三列距离同时输出。
     * 文件名：`<前缀>_<日期>_<时间>.csv`（G57：前缀 "TN"、JAN..DEC 月份缩写、'-' 分隔，桌面反汇编确认）。
     * N1 修复：CSV 文本生成提取到 [com.simplot.android.data.export.RelativePositionsCsv]（可 JVM 单测），
     * 并在 MainActivity 增加 UI 入口（导出CSV 菜单 → 相对位置）。
     */
    fun exportRelativePositionsCsv(directory: Uri) {
        val f = file ?: run { toast("请先打开一个场景"); return }
        if (f.units.isEmpty()) { toast("场景无单位"); return }
        val ref = com.simplot.android.data.export.RelativePositionsCsv.resolveReference(f.units, selectedUnitId)
        val refName = "TN ${ref.trackNumber} ${ref.name}".trim()
        try {
            val csv = com.simplot.android.data.export.RelativePositionsCsv.build(f.units, selectedUnitId)
            val name = com.simplot.android.data.export.RelativePositionsCsv.csvFileName("TN", java.time.LocalDateTime.now())
            val uri = repo.createFile(directory, name, "text/csv")
            repo.writeText(uri, csv)
            toast("已导出相对位置 CSV（参考：$refName，${f.units.size - 1} 个单位）")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    /** 导出测量 CSV（测量线专用，表头与相对位置导出区分，避免与桌面同名功能混淆）。
     *  G57：文件名不再固定 Measurements.csv（固定名重复导出互相覆盖、不符桌面命名约定），
     *  改走桌面 `<前缀>_<日期>_<时间>.csv` 约定（前缀 Measurements）。 */
    fun exportMeasureCsv(directory: Uri) {
        if (measureLog.isEmpty()) {
            toast("无测量记录")
            return
        }
        try {
            val sb = StringBuilder()
            sb.append("Measurement,FromX,FromY,ToX,ToY,Bearing,Range NMI,Range Yards,Range Meters\n")
            measureLog.forEachIndexed { i, (start, end) ->
                val bearing = CoordUtil.bearingDeg(start.first, start.second, end.first, end.second)
                val distNm = CoordUtil.distanceNm(start.first, start.second, end.first, end.second)
                val distYards = distNm * CoordUtil.YARDS_PER_NMI
                val distMeters = distNm * 1852.0
                sb.append("M${i + 1},")
                sb.append("${start.first},${start.second},${end.first},${end.second},")
                sb.append(String.format("%.1f,%.2f,%.1f,%.1f\n", bearing, distNm, distYards, distMeters))
            }
            val uri = repo.createFile(
                directory,
                com.simplot.android.data.export.RelativePositionsCsv.csvFileName("Measurements", java.time.LocalDateTime.now()),
                "text/csv"
            )
            repo.writeText(uri, sb.toString())
            toast("已导出测量 CSV：${measureLog.size} 条")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    /**
     * 切换符号风格：NTDS → CWS → WW2 → NTDS（R5 三态循环语义保留，G47 后主选择落 settings）。
     * WW2 为附加切换（ww2Symbols=true），其余写入 settings.symbolSet（设置对话框四选同源）。
     */
    fun toggleSymbolStyle() {
        val (set, ww2) = when {
            settings.ww2Symbols -> com.simplot.android.domain.model.SymbolSet.NTDS to false
            settings.symbolSet == com.simplot.android.domain.model.SymbolSet.NTDS ->
                com.simplot.android.domain.model.SymbolSet.CWS_COLOR_FILLED to false
            else -> com.simplot.android.domain.model.SymbolSet.CWS_COLOR_FILLED to true
        }
        updateSettings { it.copy(symbolSet = set, ww2Symbols = ww2) }
        toast("符号风格：${symbolStyle}")
    }

    // ============ 弹窗 / 测量 ============

    /** G30：Show Side 三态循环 All → Blue → Red → All（桌面 Show Side 菜单语义） */
    fun cycleShowSide() {
        showSide = when (showSide) {
            ShowSide.ALL -> ShowSide.BLUE
            ShowSide.BLUE -> ShowSide.RED
            ShowSide.RED -> ShowSide.ALL
        }
        // 过滤隐藏当前选中单位时清除选中（避免选中不可见单位）
        val sel = selectedUnitId?.let { id -> file?.units?.firstOrNull { it.idNum == id } }
        if (sel != null && !showSide.allows(sel.side)) selectedUnitId = null
        toast("视图：${showSideLabel(showSide)}")
    }

    fun dismissRangeDialog() {
        rangeExhaustedUnit = null
        // G40：Range 弹窗优先，关闭后顺延弹出最终航路点队列
        popNextFinalWaypoint()
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
        popNextFinalWaypoint()
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
        popNextFinalWaypoint()
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
        popNextFinalWaypoint()
    }

    // ============ G40：到达最终航路点三选（桌面 NoFutureWaypoints） ============

    /** 弹出队列中下一个仍在场的单位；队列空则关闭弹窗 */
    private fun popNextFinalWaypoint() {
        val f = file ?: run { finalWaypointUnit = null; return }
        while (finalWaypointQueue.isNotEmpty()) {
            val id = finalWaypointQueue.removeAt(0)
            val u = f.units.firstOrNull { it.idNum == id }
            if (u != null) {
                finalWaypointUnit = u
                return
            }
        }
        finalWaypointUnit = null
    }

    /** 取消/点击外部：清空队列（未处理的单位按桌面默认继续直行，不产生状态变更） */
    fun dismissFinalWaypointDialog() {
        finalWaypointUnit = null
        finalWaypointQueue.clear()
    }

    /** 继续移动：无航路点沿当前航向直行（引擎本就如此，无需状态变更） */
    fun continueFinalWaypoint() {
        val u = finalWaypointUnit ?: return
        finalWaypointUnit = null
        toast("${u.name} 继续移动（沿当前航向直行）")
        popNextFinalWaypoint()
    }

    /** 删除单位（桌面 NoFutureWaypoints Delete Unit） */
    fun deleteFinalWaypoint() {
        val u = finalWaypointUnit ?: return
        finalWaypointUnit = null
        file?.let { f ->
            f.units.removeAll { it.idNum == u.idNum }
            f.objects.removeAll { it == u.idNum }
            selectedUnitId = null
            revision++
        }
        toast("已删除 ${u.name}")
        popNextFinalWaypoint()
    }

    /** 停止单位（桌面 NoFutureWaypoints Stop Unit：停船保持位置） */
    fun stopFinalWaypoint() {
        val u = finalWaypointUnit ?: return
        finalWaypointUnit = null
        file?.let { f ->
            f.units.firstOrNull { it.idNum == u.idNum }?.let { it.speed = 0 }
            revision++
        }
        toast("${u.name} 已停止")
        popNextFinalWaypoint()
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
