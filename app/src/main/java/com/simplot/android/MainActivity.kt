package com.simplot.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplot.android.data.util.unitDistances
import com.simplot.android.data.model.Unit as SimUnit
import com.simplot.android.ui.GameViewModel
import com.simplot.android.ui.components.ArcEditorDialog
import com.simplot.android.ui.components.ConvoyDialog
import com.simplot.android.ui.components.FormationDialog
import com.simplot.android.ui.components.ManualMoveSheet
import com.simplot.android.ui.components.NewUnitDialog
import com.simplot.android.ui.components.NewScenarioDialog
import com.simplot.android.ui.components.ReplayBar
import com.simplot.android.ui.components.SceneCanvas
import com.simplot.android.ui.components.SettingsDialog
import com.simplot.android.ui.components.TurnControlBar
import com.simplot.android.ui.components.UnitEditSheet
import com.simplot.android.ui.components.WaypointEditorDialog
import com.simplot.android.ui.theme.SimPlotTheme

/**
 * 入口 Activity（架构重构 Phase 1：瘦身为薄壳）。
 *
 * 职责仅剩：
 * - SAF 文件选择（打开/保存/导出/地图）→ 转发 GameViewModel
 * - Compose 组合：读 ViewModel 状态、上抛事件
 * 业务逻辑与状态全部在 [GameViewModel]。
 */
@androidx.compose.material3.ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {

    // SAF 文件选择回调（唯一留在 Activity 的 Android 相关部分）
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm?.loadScenario(it) }
    }
    // 打开场景文件夹（桌面版打开 Scenarios 文件夹语义）：授权整目录 → 同目录地图/背景图/玩家设置均可自动读取
    private val openScenarioDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm?.loadScenarioFromDirectory(it) }
    }
    // 保存（反馈⑱：每次弹出系统「保存为」对话框，可选路径和文件名；不再直接覆盖原文件）
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm?.saveThreeFilesTo(it) }
    }
    // 保存场景包（选择目录，自动生成 <场景名>.json + Blue.SpScn + Red.SpScn + player_settings.json）
    private val saveScenarioDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm?.saveThreeFilesToDirectory(it) }
    }
    private val exportDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            // G06：导出目录选择完成后，用导出对话框中挑选的单位子集 + 玩家名导出
            val units = pendingExportUnits
            if (units != null) vm?.exportMovementOrders(it, units, pendingExportPlayerName)
            else vm?.exportMovementOrders(it)   // 兜底：全量 + 设置内玩家名
            pendingExportUnits = null
        }
    }
    // G28：单位级导入导出（桌面 Units → Import Unit / Export Unit）
    private val exportUnitDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm?.exportSelectedUnit(it) }
    }
    private val importUnitFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm?.importUnit(it) }
    }
    // R3：导入运动命令（桌面版 LoadMoveOrders）
    private val importOrders = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm?.importMovementOrders(it) }
    }
    // R3：保存 Setup 文件（桌面版 SaveSetupFile）
    private val saveSetupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm?.saveSetup(it) }
    }
    private val exportCsvDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm?.exportMeasureCsv(it) }
    }
    // N1：相对位置 CSV 导出入口（桌面版 ExportData.RelativeUnitPositions.Export）
    private val exportCsvRelativeDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm?.exportRelativePositionsCsv(it) }
    }
    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm?.loadMapFile(it) }
    }
    // G01：新场景对话框「选择地图」：立即加载地图到画布预览 + 记录文件名到新场景（桌面 WindowNewScenario PushMap）
    private val pickNewScenarioMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            vm?.loadMapFile(it)
            vm?.rememberNewScenarioMapName(it)
        }
    }

    // 供回调使用的 ViewModel 引用（onCreate 中赋值）
    private var vm: GameViewModel? = null

    // G06：导出运动命令的单位子集 + 玩家名暂存（导出对话框确认 → SAF 目录选择回调之间）
    private var pendingExportUnits: List<SimUnit>? = null
    private var pendingExportPlayerName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 契约7：注入应用级 Context 供 UnitRenderer 懒加载 CWS 精灵图（assets/symbols/）
        com.simplot.android.render.UnitRenderer.init(applicationContext)
        setContent {
            SimPlotTheme {
                val viewModel: GameViewModel = viewModel()
                vm = viewModel
                MainScreen(viewModel)
            }
        }
    }

    // ============ UI ============

    @Composable
    fun MainScreen(vm: GameViewModel) {
        // Toast 订阅（一次性消息）
        val toastMsg by vm.toasts.collectAsState()
        LaunchedEffect(toastMsg) {
            toastMsg?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                vm.clearToast()
            }
        }
        // G06：导出运动命令单位选择对话框开关（桌面 WindowExportOrders；状态在 vm.showExportOrders）
        // G15 使用 vm.manualMoveUnit（顶部 EditMenu 入口，不遮挡地图）

        Scaffold(
            topBar = {
                // 横屏时 TopAppBar 紧凑化至 40dp（释放纵向 24dp+），竖屏保持 64dp 原样—— 不遮挡地图
                val isLandscapeTop = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (isLandscapeTop) {
                    LandscapeCompactTopBar(vm)
                } else {
                    TopAppBar(
                        title = { Text(vm.file?.scenario?.scenarioName ?: "SimPlot 安卓", style = if (isLandscapeTop) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = if (isLandscapeTop) Modifier.height(40.dp) else Modifier,
                        windowInsets = if (isLandscapeTop) androidx.compose.foundation.layout.WindowInsets(0.dp) else TopAppBarDefaults.windowInsets,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        actions = { TopActions(vm) }
                    )
                }
            },
            bottomBar = {
                // 横屏：bottomBar 不占纵向空间（释放地图高度）；主操作收至右侧竖条
                val landscapeBottom = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (!landscapeBottom && vm.file != null) {
                    val replaying = vm.replayTimeline.isNotEmpty()
                    @Suppress("UNUSED_VARIABLE") val turnTick = vm.revision   // FIX-TICK：订阅 revision，Do 后触发按钮区重组刷新 Undo/Next 可用态
                    BottomActionBar(
                        replaying = replaying,
                        measureMode = vm.measureMode,
                        turnState = vm.turnState,
                        onDo = { vm.doTurn() },
                        onUndo = { vm.undo() },
                        onNext = { vm.next() },
                        onMeasure = {
                            if (vm.file == null) return@BottomActionBar
                            if (vm.replayTimeline.isNotEmpty()) { vm.toast("回放中不可测量"); return@BottomActionBar }
                            vm.measureMode = !vm.measureMode
                            if (vm.measureMode) vm.toast("测量模式：拖动画线，轻点选中单位；退出即清除测量线") else {
                                vm.selectedUnitId = null
                                vm.clearMeasures()
                            }
                        },
                        onReplay = { vm.toggleReplay() },
                        file = vm.file
                    )
                }
            }
        ) { padding ->
            val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape && vm.file != null) {
                // 横屏：Row 侧边布局 — 地图占满剩余宽高，操作竖条固定 96dp 不挤纵向
                Row(Modifier.fillMaxSize().padding(padding)) {
                    val f2 = vm.file!!
                    val replaying2 = vm.replayTimeline.isNotEmpty()
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        SceneCanvas(
                            file = f2,
                            camera = vm.camera,
                            mapRenderer = vm.mapRenderer,
                            selectedUnitId = vm.selectedUnitId,
                            onSelect = { id ->
                                vm.selectedUnitId = id
                                if (id != null && vm.measureMode) { vm.measureMode = false; vm.clearMeasures() }
                            },
                            onRelocate = { id, x, y -> vm.relocate(id, x, y) },
                            replayFrame = if (replaying2) vm.replayTimeline[vm.replayIndex] else null,
                            tick = vm.revision,
                            measureMode = vm.measureMode && !replaying2,
                            onMeasureDone = { s, e -> vm.onMeasureComplete(s, e) },
                            savedMeasures = vm.measureLog,
                            unitDistances = if (!replaying2 && !vm.measureMode) vm.selectedUnitId?.let { id -> com.simplot.android.data.util.unitDistances(f2, id).filter { d -> vm.showSide.allows(d.side) } } else null,
                            symbolStyle = vm.symbolStyle,
                            settings = vm.settings,
                            miscAnnotations = vm.miscAnnotations,
                            showSide = vm.showSide,
                            distanceUnit = vm.distanceUnit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // 右侧紧凑竖条：固定宽度 96dp + 垂直滚动，不遮挡地图、不占纵向；U1：底部避让导航栏/手势条
                    Surface(
                        tonalElevation = 2.dp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.width(96.dp).fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp).navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val replayingB = vm.replayTimeline.isNotEmpty()
                            val btnMod = Modifier.fillMaxWidth()
                            // 横屏取消选中入口（置顶，选中态常驻，不遮挡地图；点空白或按钮均可取消）
                            vm.selectedUnitId?.let { selId ->
                                val selUnitTop = f2.units.firstOrNull { it.idNum == selId }
                                if (selUnitTop != null && !vm.measureMode && !replayingB) {
                                    Button(onClick = { vm.selectedUnitId = null }, modifier = btnMod) { Text("✕ 取消选中", style = MaterialTheme.typography.labelSmall) }
                                    Text(selUnitTop.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                                    TextButton(onClick = { vm.editUnit = selUnitTop }, modifier = btnMod) { Text("编辑", style = MaterialTheme.typography.labelSmall) }
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                            if (replayingB) {
                                Button(onClick = { vm.toggleReplay() }, modifier = btnMod) { Text("退出回放", style = MaterialTheme.typography.labelSmall) }
                                Button(onClick = {
                                    if (vm.file == null) return@Button
                                    if (vm.replayTimeline.isNotEmpty()) { vm.toast("回放中不可测量"); return@Button }
                                    vm.measureMode = !vm.measureMode
                                    if (vm.measureMode) vm.toast("测量模式：拖动画线，轻点选中单位；退出即清除测量线") else { vm.selectedUnitId = null; vm.clearMeasures() }
                                }, modifier = btnMod) { Text(if (vm.measureMode) "退出测量" else "测量", style = MaterialTheme.typography.labelSmall) }
                            } else {
                                // FIX-STATE：直接绑定 VM 可观察回合状态（Do/Undo/Next 成功即写入，重组必然刷新）
                                val st2 = vm.turnState
                                val canDo2 = com.simplot.android.engine.TurnState.canDo(st2)
                                val canUndo2 = com.simplot.android.engine.TurnState.canUndo(st2)
                                val canNext2 = com.simplot.android.engine.TurnState.canNext(st2)
                                Button(onClick = { vm.doTurn() }, enabled = canDo2, modifier = btnMod) { Text("Do", style = MaterialTheme.typography.labelMedium) }
                                Button(onClick = { vm.undo() }, enabled = canUndo2, modifier = btnMod) { Text("Undo", style = MaterialTheme.typography.labelMedium) }
                                Button(onClick = { vm.next() }, enabled = canNext2, modifier = btnMod) { Text("Next", style = MaterialTheme.typography.labelMedium) }
                                Button(onClick = {
                                    if (vm.file == null) return@Button
                                    if (vm.replayTimeline.isNotEmpty()) { vm.toast("回放中不可测量"); return@Button }
                                    vm.measureMode = !vm.measureMode
                                    if (vm.measureMode) vm.toast("测量模式：拖动画线，轻点选中单位；退出即清除测量线") else { vm.selectedUnitId = null; vm.clearMeasures() }
                                }, modifier = btnMod) { Text(if (vm.measureMode) "退出测量" else "测量", style = MaterialTheme.typography.labelSmall) }
                                Button(onClick = { vm.toggleReplay() }, modifier = btnMod) { Text("回放", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                val f = vm.file
                if (f != null) {
                    // 回放模式：帧覆盖画布 + 回放控制条；正常模式：实时编辑
                    val replaying = vm.replayTimeline.isNotEmpty()
                    // ② 点选单位自动测量：仅非回放且非测量模式且选中单位时计算（selectedUnitId 变更即重组刷新）
                    // G30：测量辅助线按 Show Side 过滤（被过滤方单位不画连线）
                    val unitDist = if (!replaying && !vm.measureMode) vm.selectedUnitId?.let { id ->
                        unitDistances(f, id).filter { d -> vm.showSide.allows(d.side) }
                    } else null
                    // 取消选中：点空白（SceneCanvas hitTest 未命中 -> onSelect(null)）、下方选中条按钮、编辑溢出菜单均可；不做覆盖地图的弹窗/悬浮
                    // 返回键：有选中则取消选中（不退出页面），无选中走系统默认
                    if (vm.selectedUnitId != null) {
                        BackHandler { vm.selectedUnitId = null }
                    }
                    SceneCanvas(
                        file = f,
                        camera = vm.camera,
                        mapRenderer = vm.mapRenderer,
                        selectedUnitId = vm.selectedUnitId,
                        onSelect = { id ->
                            vm.selectedUnitId = id
                            if (id != null && vm.measureMode) {
                                vm.measureMode = false
                                vm.clearMeasures()
                            }
                        },
                        onRelocate = { id, x, y -> vm.relocate(id, x, y) },
                        replayFrame = if (replaying) vm.replayTimeline[vm.replayIndex] else null,
                        tick = vm.revision,
                        measureMode = vm.measureMode && !replaying,
                        onMeasureDone = { start, end -> vm.onMeasureComplete(start, end) },
                        savedMeasures = vm.measureLog,
                        unitDistances = unitDist,
                        symbolStyle = vm.symbolStyle,
                        settings = vm.settings,
                        miscAnnotations = vm.miscAnnotations,
                        showSide = vm.showSide,
                        distanceUnit = vm.distanceUnit,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    if (replaying) {
                        // 自动播放：每 1 秒前进一帧，到尾停止
                        LaunchedEffect(vm.replayPlaying, vm.replayIndex, vm.replayDelayMs) {
                            if (vm.replayPlaying) {
                                kotlinx.coroutines.delay(vm.replayDelayMs)
                                vm.replayTick()
                            }
                        }
                        ReplayBar(
                            timeline = vm.replayTimeline,
                            frameIndex = vm.replayIndex,
                            playing = vm.replayPlaying,
                            onFrameChange = { vm.setReplayFrame(it) },
                            onPlayPause = { vm.toggleReplayPlay() },
                            onSpeedChange = { vm.setReplaySpeed(it) }
                        )
                    } else {
                        // 竖屏：控局条可收起（不挤地图，选中条在画布下方）
                        var showTurnPanel by remember { mutableStateOf(false) }
                        TextButton(onClick = { showTurnPanel = !showTurnPanel }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (showTurnPanel) "收起控局条 ▲" else "展开控局条 ▼ · ${f.time.currentTurnTime} → ${f.time.currentPositionTime}")
                        }
                        if (showTurnPanel) {
                            TurnControlBar(
                                file = f,
                                onDo = { vm.doTurn() },
                                onUndo = { vm.undo() },
                                onNext = { vm.next() },
                                tick = vm.revision,
                                vmTurnState = vm.turnState,
                                onIntervalSet = { m, s -> vm.toast("回合时长已设为 $m 分 $s 秒") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    // 竖屏已选中轻量条（不遮挡地图，置于画布与底部栏之间）：保留取消入口（编辑+取消；非弹窗/悬浮覆盖，下方条状出现）
                    if (!replaying && !vm.measureMode) vm.selectedUnitId?.let { selId ->
                        val selUnit = f.units.firstOrNull { it.idNum == selId }
                        if (selUnit != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${selUnit.name}（${selUnit.unitType}）", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.editUnit = selUnit }) { Text("编辑") }
                                TextButton(onClick = { vm.selectedUnitId = null }) { Text("✕ 取消选中") }
                            }
                        }
                    }
                } else {
                    // P0-4：居家空状态卡（超大触控入口，3 秒内可开局）
                    com.simplot.android.ui.components.HomeEmptyState(
                        onNewScenario = { vm.showNewScenario = true },
                        onOpenFile = { openFile.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                        onOpenFolder = { openScenarioDir.launch(null) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // G06：导出运动命令单位选择对话框（桌面 WindowExportOrders：ListboxUnits + Add/Remove + TextPlayerName）
        if (vm.showExportOrders) {
            ExportOrdersDialog(
                units = vm.file?.units ?: emptyList(),
                initialPlayerName = vm.settings.playerName,
                onDismiss = { vm.showExportOrders = false },
                onExport = { selected, name ->
                    vm.showExportOrders = false
                    if (selected.isEmpty()) { vm.toast("请至少选择一个单位"); return@ExportOrdersDialog }
                    pendingExportUnits = selected
                    pendingExportPlayerName = name
                    exportDir.launch(null)
                }
            )
        }

        // 单位编辑弹层
        vm.editUnit?.let { unit ->
            UnitEditSheet(
                unit = unit,
                onApply = { vm.applyEdit(it); vm.editUnit = null },
                onDelete = { vm.deleteUnit(it); vm.editUnit = null },
                onShowAsSunk = { vm.showAsSunk(it); vm.editUnit = null },
                // G29：复制 → 剪贴板（不再立即生成副本；Paste 放置时防撞号分配）
                onCopy = { vm.copyUnitToClipboard(it) },
                onDismiss = { vm.editUnit = null }
            )
        }

        // G15：手动移动控制（vm.manualMoveUnit，EditMenu 入口）
        vm.manualMoveUnit?.let { unit ->
            ManualMoveSheet(
                unit = unit,
                currentTime = vm.file?.time?.currentPositionTime ?: "",
                onApply = { vm.applyEdit(it); vm.manualMoveUnit = null },
                onDismiss = { vm.manualMoveUnit = null }
            )
        }

        // 航路点编辑器（P1：桌面版 WindowWaypoints）
        vm.editWaypointsUnit?.let { unit ->
            WaypointEditorDialog(
                unit = unit,
                currentTime = vm.file?.time?.currentPositionTime ?: "",
                onApply = { u, wps -> vm.applyWaypointsEdit(u, wps); vm.editWaypointsUnit = null },
                onDismiss = { vm.editWaypointsUnit = null }
            )
        }

        // 传感器/武器弧编辑器（P1：桌面版 ContainerSensors/ContainerWeapons）
        vm.editArcUnit?.let { unit ->
            ArcEditorDialog(
                unit = unit,
                onApply = { u, sensors, weapons -> vm.applyArcEdit(u, sensors, weapons); vm.editArcUnit = null },
                onDismiss = { vm.editArcUnit = null }
            )
        }

        // 新建单位（P1：桌面版各类型 NewUnit 窗口）
        if (vm.showNewUnit) {
            NewUnitDialog(
                // 问题2修复：默认位置 = 当前视野中心（避免新建单位落在 (0,0) 视野外不可见/不可编辑）
                defaultX = vm.camera.centerWorldX,
                defaultY = vm.camera.centerWorldY,
                onDismiss = { vm.showNewUnit = false },
                onCreate = { domain, name, unitType, unitClass, side, x, y ->
                    vm.createNewUnit(domain, name, unitType, unitClass, side, x, y)
                    vm.showNewUnit = false
                }
            )
        }

        // 护航队创建（P2 恢复：桌面版 WindowConvoy；G03：参数契约 = ConvoySpec）
        if (vm.showConvoy) {
            ConvoyDialog(
                onDismiss = { vm.showConvoy = false },
                onCreate = { spec -> vm.createConvoy(spec); vm.showConvoy = false }
            )
        }

        // 玩家显示设置（R4：桌面版 WindowCustomizeDisplay）
        if (vm.showSettings) {
            SettingsDialog(
                settings = vm.settings,
                onDismiss = { vm.showSettings = false },
                onSave = { vm.applySettings(it) },
                // G10：自动存档开关（桌面 WindowControlOptions CheckAutoSave）
                autoSaveEnabled = vm.autoSaveEnabled,
                onAutoSaveChange = { vm.autoSaveEnabled = it },
                // G11：错误日志（桌面 WindowErrorLog）
                errorLog = vm.errorLog,
                onClearErrorLog = { vm.clearErrorLog() }
            )
        }

        // 编队管理（R6/G02：桌面版 WindowFormation 完整接线——创建/重命名/删除/成员增删/设中心/类型/距离单位）
        if (vm.showFormation) {
            FormationDialog(
                formationNames = vm.formationNames(),
                units = vm.file?.units ?: emptyList(),
                specs = vm.formationSpecs(),
                onCreate = { name, type, unit -> vm.formationCreate(name, type, unit) },
                onRename = { old, new -> vm.formationRename(old, new) },
                onDelete = { name -> vm.formationDelete(name) },
                onAddMember = { formation, unitId -> vm.formationMemberAdd(formation, unitId) },
                onRemoveMember = { formation, unitId -> vm.formationMemberRemove(formation, unitId) },
                onSetCenter = { formation, unitId -> vm.formationSetCenter(formation, unitId) },
                onSetType = { formation, type -> vm.formationSetType(formation, type) },
                onSetDistanceUnit = { formation, unit -> vm.formationSetDistanceUnit(formation, unit) },
                onPrepare = { name -> vm.formationPrepare(name) },
                onCancel = { name -> vm.formationCancel(name) },
                onDismiss = { vm.showFormation = false }
            )
        }

        // G01：新场景创建（桌面版 WindowNewScenario：场景名 + 起始日期时间 + 地图选择）
        if (vm.showNewScenario) {
            NewScenarioDialog(
                defaultStartTime = vm.defaultScenarioStartTime(),
                mapFileName = vm.newScenarioMapName,
                onDismiss = { vm.showNewScenario = false; vm.newScenarioMapName = null },
                onPickMap = { pickNewScenarioMap.launch(arrayOf("application/json", "text/plain", "image/*", "*/*")) },
                onClearMap = { vm.newScenarioMapName = null },
                onCreate = { name, startTime, mapName ->
                    vm.createNewScenario(name, startTime, mapName)
                    vm.showNewScenario = false
                    vm.newScenarioMapName = null
                }
            )
        }

        // Range 耗尽三选弹窗（桌面版 HasRangeRemaining：Continue/Delete/Stop）
        vm.rangeExhaustedUnit?.let { u ->
            AlertDialog(
                onDismissRequest = { vm.dismissRangeDialog() },
                title = { Text("航程耗尽") },
                text = { Text("${u.name} 剩余航程为 0，如何处理？") },
                confirmButton = {
                    Button(onClick = { vm.continueRangeExhausted() }) { Text("继续移动") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { vm.deleteRangeExhausted() }) { Text("删除单位") }
                        TextButton(onClick = { vm.stopRangeExhausted() }) { Text("停止单位") }
                    }
                }
            )
        }

        // G40：到达最终航路点三选弹窗（桌面版 NoFutureWaypoints：Continue Movement/Delete Unit/Stop Unit）
        vm.finalWaypointUnit?.let { u ->
            AlertDialog(
                onDismissRequest = { vm.dismissFinalWaypointDialog() },
                title = { Text("到达最终航路点") },
                text = { Text("TN ${u.trackNumber} ${u.name} 已到达最终航路点，如何处理？") },
                confirmButton = {
                    Button(onClick = { vm.continueFinalWaypoint() }) { Text("继续移动") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { vm.deleteFinalWaypoint() }) { Text("删除单位") }
                        TextButton(onClick = { vm.stopFinalWaypoint() }) { Text("停止单位") }
                    }
                }
            )
        }

        // 新位置计算器已删除（反馈⑩：功能整体移除，原顶部按钮与编辑菜单入口一并去掉）
    }
    }

    // TextButtonRow 已废弃：旧顶部横滚按钮堆已由 TopActions(场景/编辑/视图/更多) 溢出菜单替代（不遮挡地图）
    // 保留空桩避免历史分支合入编译失败；禁止恢复横滚 Button（会遮挡/挤压地图）
    @Composable
    @Suppress("UNUSED_PARAMETER")
    private fun androidx.compose.foundation.layout.RowScope.TextButtonRow(vm: GameViewModel) { }

    // ============ G27 地图截图（桌面 File → Save Map Screenshot） ============

    /** 将当前窗口内容绘制到 Bitmap（View.draw：兼容 Compose 视图树，无需 Surface 权限） */
    fun captureScreenshot(): Bitmap? {
        val view = window.decorView.rootView
        if (view.width <= 0 || view.height <= 0) return null
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        return bmp
    }

    /** 保存 PNG 到系统相册（MediaStore；API 29+ 免权限，低版本尝试旧接口并容错提示） */
    fun saveScreenshotToGallery(bmp: Bitmap) {
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val name = "SimPlot_$stamp.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SimPlot")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    vm?.toast("截图已保存：$name")
                    return
                }
            } else {
                // API 26-28：旧接口写入相册（部分设备需存储权限，失败走提示）
                val inserted = MediaStore.Images.Media.insertImage(contentResolver, bmp, name, "SimPlot 地图截图")
                if (inserted != null) {
                    vm?.toast("截图已保存：$name")
                    return
                }
            }
            vm?.toast("截图保存失败：无法写入相册")
        } catch (e: Exception) {
            vm?.toast("截图保存失败：${e.message}")
        }
    }

    // SelectedUnitBar 已移除（不遮挡地图）：取消选中靠 SceneCanvas 空白点 hitTest 未命中 -> onSelect(null)、
    // 竖屏画布下方轻量条、横屏右侧竖条顶端按钮、编辑溢出菜单、返回键（BackHandler）；禁止覆盖地图的弹窗/悬浮浮层

    /**
     * G06：导出运动命令单位选择对话框（桌面 WindowExportOrders）。
     * 左列全部单位（点击选中）→ 「添加→」进右列已选列表；「←移除」退回；
     * 玩家名参与文件名（Movement - <玩家名>.json）。确认后由调用方发起目录选择。
     */
    @Composable
    fun ExportOrdersDialog(
        units: List<SimUnit>,
        initialPlayerName: String,
        onDismiss: () -> Unit,
        onExport: (List<SimUnit>, String) -> Unit
    ) {
        var playerName by remember { mutableStateOf(initialPlayerName) }
        val selected = remember { mutableStateListOf<SimUnit>() }
        var pickedId by remember { mutableStateOf<String?>(null) }
        val picked = units.firstOrNull { it.idNum == pickedId }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("导出运动命令（选择单位）") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()
                ) {
                    OutlinedTextField(
                        value = playerName,
                        onValueChange = { playerName = it },
                        label = { Text("玩家名（文件名）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        // 全部单位（点击选中待添加项）
                        Column(Modifier.weight(1f)) {
                            Text("全部单位", style = MaterialTheme.typography.labelMedium)
                            LazyColumn(Modifier.height(170.dp)) {
                                items(units, key = { it.idNum }) { u ->
                                    Text(
                                        text = "${u.name}（${u.idNum}）",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                            .background(
                                                if (u.idNum == pickedId) MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent
                                            )
                                            .clickable { pickedId = u.idNum }
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                        // Add / Remove（桌面 PushAdd / PushRemove）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Button(
                                onClick = {
                                    picked?.let { p -> if (selected.none { it.idNum == p.idNum }) selected.add(p) }
                                },
                                enabled = picked != null && selected.none { it.idNum == picked!!.idNum }
                            ) { Text("添加→") }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { picked?.let { p -> selected.removeAll { it.idNum == p.idNum } } },
                                enabled = picked != null && selected.any { it.idNum == picked!!.idNum }
                            ) { Text("←移除") }
                        }
                        // 已选单位
                        Column(Modifier.weight(1f)) {
                            Text("已选 ${selected.size}", style = MaterialTheme.typography.labelMedium)
                            LazyColumn(Modifier.height(170.dp)) {
                                items(selected.size) { i ->
                                    Text(
                                        text = "${selected[i].name}（${selected[i].idNum}）",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { onExport(selected.toList(), playerName) }) { Text("导出") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }

    // ============ P0 居家友好：分组溢出菜单 + 底部主操作（拇指可达） ===========

    @Composable
    private fun TopActions(vm: GameViewModel) {
        // 顶部只留 4 个分组溢出，避免 15+ 文本 Button 横向硬滚
        SceneMenu(vm)
        EditMenu(vm)
        ViewMenu(vm)
        MoreMenu(vm)
    }

    @Composable
    private fun SceneMenu(vm: GameViewModel) {
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("场景") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("新建场景") }, onClick = { open = false; vm.showNewScenario = true })
                DropdownMenuItem(text = { Text("打开文件") }, onClick = { open = false; openFile.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
                DropdownMenuItem(text = { Text("打开文件夹") }, onClick = { open = false; openScenarioDir.launch(null) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("保存场景包(选目录)") }, onClick = { open = false; if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }; saveScenarioDir.launch(null) })
                DropdownMenuItem(text = { Text("保存单文件JSON") }, onClick = {
                    open = false; if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }
                    val n = vm.file?.scenario?.scenarioName?.ifBlank { "scenario" } ?: "scenario"; saveFile.launch("$n.json")
                })
                DropdownMenuItem(text = { Text("导入运动命令") }, onClick = { open = false; if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }; importOrders.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
                DropdownMenuItem(text = { Text("保存 Setup") }, onClick = {
                    open = false; if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }
                    val n = vm.file?.scenario?.scenarioName?.ifBlank { "setup" } ?: "setup"; saveSetupFile.launch("$n.json")
                })
                DropdownMenuItem(text = { Text("存设置到场景目录") }, onClick = { open = false; vm.savePlayerSettingsToScenarioDir() })
                DropdownMenuItem(
                    text = { Text("地图") },
                    onClick = { open = false; pickMap.launch(arrayOf("application/json", "text/plain", "image/*", "*/*")) }
                )
            }
        }
    }

    @Composable
    private fun EditMenu(vm: GameViewModel) {
        var open by remember { mutableStateOf(false) }
        val sel = vm.selectedUnitId?.let { id -> vm.file?.units?.firstOrNull { it.idNum == id } }
        val hasSel = sel != null
        Box {
            TextButton(onClick = { open = true }) { Text("编辑") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("新单位") }, onClick = { open = false; if (vm.file != null) vm.showNewUnit = true else vm.toast("请先打开一个场景") })
                DropdownMenuItem(text = { Text("护航队") }, onClick = { open = false; if (vm.file != null) vm.showConvoy = true else vm.toast("请先打开一个场景") })
                DropdownMenuItem(text = { Text("编队") }, onClick = { open = false; if (vm.file != null) vm.showFormation = true else vm.toast("请先打开一个场景") })
                HorizontalDivider()
                // 选中单位编辑入口（不遮挡地图：收至溢出菜单；未选中时禁用）
                DropdownMenuItem(
                    text = { Text(if (hasSel) "编辑单位：${sel!!.name}" else "编辑单位（请先选点单位）") },
                    onClick = { open = false; if (sel != null) vm.editUnit = sel else vm.toast("请先轻点选中一个单位") }
                )
                DropdownMenuItem(
                    text = { Text("航路点") },
                    enabled = hasSel,
                    onClick = { open = false; if (sel != null) vm.editWaypointsUnit = sel else vm.toast("请先轻点选中一个单位") }
                )
                DropdownMenuItem(
                    text = { Text("传感器/武器弧") },
                    enabled = hasSel,
                    onClick = { open = false; if (sel != null) vm.editArcUnit = sel else vm.toast("请先轻点选中一个单位") }
                )
                DropdownMenuItem(
                    text = { Text("手动移动") },
                    enabled = hasSel,
                    onClick = {
                        open = false
                        if (sel == null) { vm.toast("请先轻点选中一个单位"); return@DropdownMenuItem }
                        vm.manualMoveUnit = sel
                    }
                )
                DropdownMenuItem(
                    text = { Text("粘贴（中心）") },
                    onClick = {
                        open = false
                        if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }
                        vm.pasteUnit(vm.camera.centerWorldX, vm.camera.centerWorldY)
                    }
                )
                if (hasSel) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("✕ 取消选中：${sel!!.name}") },
                        onClick = { open = false; vm.selectedUnitId = null }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("导出单位") }, onClick = {
                    open = false; if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }
                    if (vm.selectedUnitId == null) { vm.toast("请先选中要导出的单位"); return@DropdownMenuItem }; exportUnitDir.launch(null)
                })
                DropdownMenuItem(text = { Text("导入单位") }, onClick = { open = false; if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }; importUnitFile.launch(arrayOf("application/json", "*/*")) })
            }
        }
    }

    @Composable
    private fun ViewMenu(vm: GameViewModel) {
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("视图") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("单位:${vm.distanceUnit.code}") }, onClick = { vm.cycleDistanceUnit() })
                DropdownMenuItem(text = { Text("视图:${com.simplot.android.ui.showSideLabel(vm.showSide)}") }, onClick = { vm.cycleShowSide() })
                DropdownMenuItem(text = { Text("符号:${if (vm.symbolStyle == com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS) "NTDS" else if (vm.symbolStyle == com.simplot.android.render.UnitRenderer.SymbolStyle.CWS) "CWS" else "WW2"}") }, onClick = { vm.toggleSymbolStyle() })
                HorizontalDivider()
                // 弧显示三态（传感器/武器/声呐被动方位）
                DropdownMenuItem(text = { Text("${if (vm.settings.showSensors) "✓ " else "  "}传感器弧") }, onClick = { vm.toggleSetting { it.copy(showSensors = !it.showSensors) } })
                DropdownMenuItem(text = { Text("${if (vm.settings.showWeapons) "✓ " else "  "}武器弧") }, onClick = { vm.toggleSetting { it.copy(showWeapons = !it.showWeapons) } })
                DropdownMenuItem(text = { Text("${if (vm.settings.showSonar && vm.settings.showEs) "✓ " else "  "}声呐/被动方位") }, onClick = {
                    val next = !(vm.settings.showSonar && vm.settings.showEs); vm.toggleSetting { it.copy(showSonar = next, showEs = next) }
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("设置…") }, onClick = { open = false; vm.showSettings = true })
            }
        }
    }

    @Composable
    private fun MoreMenu(vm: GameViewModel) {
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("更多") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("导出CSV · 相对位置") }, onClick = {
                    open = false; if (vm.file?.units?.isEmpty() != false) { vm.toast("场景无单位，请先打开场景"); return@DropdownMenuItem }; exportCsvRelativeDir.launch(null)
                })
                DropdownMenuItem(text = { Text("导出CSV · 测量") }, onClick = {
                    open = false; if (vm.measureLog.isEmpty()) { vm.toast("无测量记录，先测量再导出"); return@DropdownMenuItem }; exportCsvDir.launch(null)
                })
                DropdownMenuItem(text = { Text("导出运动命令") }, onClick = {
                    open = false; if (vm.file?.units?.isEmpty() != false) { vm.toast("无单位可导出"); return@DropdownMenuItem }; vm.showExportOrders = true
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("截图（保存到相册）") }, onClick = {
                    open = false; if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }
                    val bmp = captureScreenshot(); if (bmp == null) { vm.toast("截图失败：画布不可用"); return@DropdownMenuItem }; saveScreenshotToGallery(bmp)
                })
            }
        }
    }

    @Composable
    private fun LandscapeCompactTopBar(vm: GameViewModel) {
        // 横屏专用 40dp 紧凑顶栏（M3 TopAppBar 默认 64dp → 压至 40dp，释放 ~24dp 地图纵向）
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().height(40.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = vm.file?.scenario?.scenarioName ?: "SimPlot 安卓",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(start = 6.dp)
                ) { TopActions(vm) }
            }
        }
    }

    @Composable
    private fun BottomActionBar(
        replaying: Boolean,
        measureMode: Boolean,
        onDo: () -> Unit,
        onUndo: () -> Unit,
        onNext: () -> Unit,
        onMeasure: () -> Unit,
        onReplay: () -> Unit,
        file: com.simplot.android.data.model.ScenarioFile? = null,
        turnState: com.simplot.android.engine.TurnState.State? = null   // FIX-STATE：直接绑定 VM 可观察状态
    ) {
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val state = turnState ?: file?.let { com.simplot.android.engine.TurnState.detect(it) }
        val canDo = state?.let { com.simplot.android.engine.TurnState.canDo(it) } ?: (file != null)
        val canUndo = state?.let { com.simplot.android.engine.TurnState.canUndo(it) } ?: false
        val canNext = state?.let { com.simplot.android.engine.TurnState.canNext(it) } ?: false
        Surface(
            tonalElevation = if (landscape) 3.dp else 6.dp,
            shadowElevation = if (landscape) 4.dp else 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (replaying) {
                    Button(onClick = onReplay, modifier = Modifier.weight(1f)) { Text("退出回放") }
                    Button(onClick = onMeasure, modifier = Modifier.weight(1f)) { Text(if (measureMode) "退出测量" else "测量") }
                } else {
                    Button(onClick = onDo, enabled = canDo, modifier = Modifier.weight(1f)) { Text("▶ Do") }
                    Button(onClick = onUndo, enabled = canUndo, modifier = Modifier.weight(1f)) { Text("↩ Undo") }
                    Button(onClick = onNext, enabled = canNext, modifier = Modifier.weight(1f)) { Text("✓ Next") }
                    Button(onClick = onMeasure, modifier = Modifier.weight(1f)) { Text(if (measureMode) "退出测量" else "测量") }
                    Button(onClick = onReplay, modifier = Modifier.weight(1f)) { Text("回放") }
                }
            }
        }
    }
}
