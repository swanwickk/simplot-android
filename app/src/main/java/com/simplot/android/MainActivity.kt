package com.simplot.android

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.simplot.android.ui.components.SceneLibraryDialog
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
    // 保存（反馈⑱：每次弹出系统「保存为」对话框，可选路径和文件名；不再直接覆盖原文件）
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm?.saveThreeFilesTo(it) }
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
    // P3 场景库：选择场景目录（SAF tree）→ 持久化授权 + SharedPreferences 记忆 + 刷新场景库列表
    private val sceneLibraryDirPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 个别 Provider 不支持持久授权：忽略，本次会话内仍可用
            }
            getSharedPreferences(PREFS_SCENE_LIBRARY, MODE_PRIVATE)
                .edit().putString(PREF_SCENE_LIBRARY_DIR, it.toString()).apply()
            sceneLibraryDir = it
            vm?.toast("场景库目录已设置")
        }
    }

    // 供回调使用的 ViewModel 引用（onCreate 中赋值）
    private var vm: GameViewModel? = null

    // P3 场景库：记住的目录（SharedPreferences 持久化，onCreate 恢复）+ 对话框开关
    // 用 activity 级 mutableStateOf：回调（SAF 选择器返回）可直接更新，Compose 侧自动重组
    private var sceneLibraryDir by mutableStateOf<Uri?>(null)
    private var showSceneLibrary by mutableStateOf(false)

    // G06：导出运动命令的单位子集 + 玩家名暂存（导出对话框确认 → SAF 目录选择回调之间）
    private var pendingExportUnits: List<SimUnit>? = null
    private var pendingExportPlayerName: String = ""

    companion object {
        // P3 场景库：SharedPreferences 文件与键（目录 uri 持久化，跨启动记忆）
        private const val PREFS_SCENE_LIBRARY = "simplot_scene_library"
        private const val PREF_SCENE_LIBRARY_DIR = "scene_library_dir"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 契约7：注入应用级 Context 供 UnitRenderer 懒加载 CWS 精灵图（assets/symbols/）
        com.simplot.android.render.UnitRenderer.init(applicationContext)
        // P3 场景库：恢复上次记住的目录（takePersistableUriPermission 在授权时已完成，无需再校验）
        sceneLibraryDir = getSharedPreferences(PREFS_SCENE_LIBRARY, MODE_PRIVATE)
            .getString(PREF_SCENE_LIBRARY_DIR, null)?.let { Uri.parse(it) }
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
    private fun MainScreen(vm: GameViewModel) {
        // Toast 订阅（一次性消息）
        val toastMsg by vm.toasts.collectAsState()
        LaunchedEffect(toastMsg) {
            toastMsg?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                vm.clearToast()
            }
        }
        // G06：导出运动命令单位选择对话框开关（桌面 WindowExportOrders；状态在 vm.showExportOrders）
        // G15：手动移动弹层目标单位（桌面 ContainerMove；null=关闭）
        var manualMoveUnit by remember { mutableStateOf<SimUnit?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(vm.file?.scenario?.scenarioName ?: "SimPlot 安卓") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    actions = { TextButtonRow(vm) }
                )
            }
        ) { padding ->
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
                    // 契约7：选中单位 → 显性操作条（编辑入口显性化；测量/回放中不显示，长按编辑路径保留）
                    val selUnit = vm.selectedUnitId?.let { id -> f.units.firstOrNull { it.idNum == id } }
                    if (selUnit != null && !vm.measureMode && !replaying) {
                        SelectedUnitBar(
                            unit = selUnit,
                            onEdit = { vm.editUnit = selUnit },
                            onWaypoints = { vm.editWaypointsUnit = selUnit },
                            onArcs = { vm.editArcUnit = selUnit },
                            // G29：Paste 入口（粘贴剪贴板单位到视野中心；剪贴板为空时 toast 提示）
                            onPaste = { vm.pasteUnit(vm.camera.centerWorldX, vm.camera.centerWorldY) },
                            // G15：手动移动入口（桌面版 ContainerMove DoMove/Pause/UndoMove）
                            onManualMove = { manualMoveUnit = selUnit },
                            onClear = { vm.selectedUnitId = null }
                        )
                    }
                    SceneCanvas(
                        file = f,
                        camera = vm.camera,
                        mapRenderer = vm.mapRenderer,
                        selectedUnitId = vm.selectedUnitId,
                        onSelect = { id ->
                            vm.selectedUnitId = id
                            // 修复 A：轻点选中单位即退出测量模式（用户可直接看 ② 辅助线）；轻点空白不退出
                            // 修复 B：退出（无论按钮还是选中）即清除测量线，语义一致
                            if (id != null && vm.measureMode) {
                                vm.measureMode = false
                                vm.clearMeasures()
                            }
                        },
                        // G32：长按拖拽 Relocate（长按不再弹编辑窗；编辑入口在选中操作条）
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
                        TurnControlBar(
                            file = f,
                            onDo = { vm.doTurn() },
                            onUndo = { vm.undo() },
                            onNext = { vm.next() },
                            tick = vm.revision,
                            onIntervalSet = { m, s -> vm.toast("回合时长已设为 $m 分 $s 秒") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("请点击左上角「打开」选择场景存档", style = MaterialTheme.typography.bodyLarge)
                    }
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

        // G15：手动移动控制弹层（桌面版 ContainerMove DoMove/Pause/UndoMove + 速度档位）
        manualMoveUnit?.let { unit ->
            ManualMoveSheet(
                unit = unit,
                currentTime = vm.file?.time?.currentPositionTime ?: "",
                onApply = { vm.applyEdit(it); manualMoveUnit = null },
                onDismiss = { manualMoveUnit = null }
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

        // P3 场景库（应用内场景列表/管理：记住目录 + 列表打开 + 删除）
        if (showSceneLibrary) {
            SceneLibraryDialog(
                dirUri = sceneLibraryDir,
                onChangeDir = { sceneLibraryDirPicker.launch(null) },
                onOpen = { uri ->
                    showSceneLibrary = false
                    vm.loadScenario(uri)
                },
                onToast = { vm.toast(it) },
                onDismiss = { showSceneLibrary = false }
            )
        }

        // G01：新场景创建（桌面版 WindowNewScenario：场景名 + 起始日期时间 + 地图选择）
        if (vm.showNewScenario) {
            NewScenarioDialog(
                defaultStartTime = vm.defaultScenarioStartTime(),
                mapFileName = vm.newScenarioMapName,
                onDismiss = { vm.showNewScenario = false; vm.newScenarioMapName = null },
                onPickMap = { pickNewScenarioMap.launch(arrayOf("application/json", "*/*")) },
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

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.TextButtonRow(vm: GameViewModel) {
        // 顶部按钮：新场景 / 打开 / 保存 / 地图 / 测量 / CWS-NTDS / 导出CSV / 导出 / 回放（契约7：去掉示例；反馈⑩：新位置、护航队功能已整体移除）
        // G01：新场景创建入口（桌面版 File → New Scenario；无需先打开场景，可从零开始）
        Button(onClick = { vm.showNewScenario = true }) { Text("新场景") }
        Button(onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
            Text("打开")
        }
        // P3 场景库：应用内场景列表（记住目录，点选即开，可删除）
        Button(onClick = { showSceneLibrary = true }) { Text("场景库") }
        Button(onClick = {
            // 反馈⑱：每次保存都弹系统「保存为」对话框（选路径+文件名）
            if (vm.file == null) { vm.toast("请先打开一个场景"); return@Button }
            val defaultName = vm.file?.scenario?.scenarioName?.ifBlank { "scenario" } ?: "scenario"
            saveFile.launch("$defaultName.json")
        }) { Text("保存") }
        Button(onClick = { pickMap.launch(arrayOf("image/*")) }) { Text("地图") }
        // P2 恢复：新建单位 / 护航队 / 编队
        Button(onClick = { if (vm.file != null) vm.showNewUnit = true else vm.toast("请先打开一个场景") }) { Text("新单位") }
        Button(onClick = { if (vm.file != null) vm.showConvoy = true else vm.toast("请先打开一个场景") }) { Text("护航队") }
        Button(onClick = { if (vm.file != null) vm.showFormation = true else vm.toast("请先打开一个场景") }) { Text("编队") }
        Button(onClick = {
            if (vm.file == null) return@Button
            if (vm.replayTimeline.isNotEmpty()) { vm.toast("回放中不可测量"); return@Button }
            vm.measureMode = !vm.measureMode
            if (vm.measureMode) vm.toast("测量模式：拖动画线，轻点选中单位；退出即清除测量线") else {
                vm.selectedUnitId = null
                vm.clearMeasures()  // 修复 B：退出测量模式清除全部测量线
            }
        }) { Text(if (vm.measureMode) "退出测量" else "测量") }
        Button(onClick = { vm.toggleSymbolStyle() }) { Text("符号:${if (vm.symbolStyle == com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS) "NTDS" else if (vm.symbolStyle == com.simplot.android.render.UnitRenderer.SymbolStyle.CWS) "CWS" else "WW2"}") }
        // G30：Show Side 视图过滤三态（桌面 Show Side 菜单 All/Blue/Red；仅影响视图，不落盘）
        Button(onClick = { vm.cycleShowSide() }) { Text("视图:${com.simplot.android.ui.showSideLabel(vm.showSide)}") }
        // R4：玩家显示设置（桌面版 WindowCustomizeDisplay）
        Button(onClick = { vm.showSettings = true }) { Text("设置") }
        // 导出CSV 菜单：相对位置（N1 新增，桌面版 ExportData.RelativeUnitPositions）/ 测量（原行为）
        Box {
            var exportMenuOpen by remember { mutableStateOf(false) }
            Button(onClick = { exportMenuOpen = true }) { Text("导出CSV") }
            DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("相对位置") },
                    onClick = {
                        exportMenuOpen = false
                        if (vm.file?.units?.isEmpty() != false) { vm.toast("场景无单位，请先打开场景"); return@DropdownMenuItem }
                        exportCsvRelativeDir.launch(null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("测量") },
                    onClick = {
                        exportMenuOpen = false
                        if (vm.measureLog.isEmpty()) { vm.toast("无测量记录，先测量再导出"); return@DropdownMenuItem }
                        exportCsvDir.launch(null)
                    }
                )
            }
        }
        // G06：导出运动命令——先弹单位选择对话框（桌面 WindowExportOrders），确认后选目录
        Button(onClick = {
            if (vm.file?.units?.isEmpty() != false) { vm.toast("无单位可导出"); return@Button }
            vm.showExportOrders = true
        }) { Text("导出") }
        Button(onClick = {
            if (vm.file == null) { vm.toast("请先打开一个场景"); return@Button }
            importOrders.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }) { Text("导入") }
        // G28：单位级导入导出（桌面 Units → Import Unit / Export Unit；导出需先选中单位）
        Box {
            var unitMenuOpen by remember { mutableStateOf(false) }
            Button(onClick = { unitMenuOpen = true }) { Text("单位") }
            DropdownMenu(expanded = unitMenuOpen, onDismissRequest = { unitMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("导出单位") },
                    onClick = {
                        unitMenuOpen = false
                        if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }
                        if (vm.selectedUnitId == null) { vm.toast("请先选中要导出的单位"); return@DropdownMenuItem }
                        exportUnitDir.launch(null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("导入单位") },
                    onClick = {
                        unitMenuOpen = false
                        if (vm.file == null) { vm.toast("请先打开一个场景"); return@DropdownMenuItem }
                        importUnitFile.launch(arrayOf("application/json", "*/*"))
                    }
                )
            }
        }
        Button(onClick = {
            if (vm.file == null) { vm.toast("请先打开一个场景"); return@Button }
            val defaultName = vm.file?.scenario?.scenarioName?.ifBlank { "setup" } ?: "setup"
            saveSetupFile.launch("$defaultName.json")
        }) { Text("Setup") }
        // G25：显式保存玩家设置到场景目录（桌面 File → Save Player Settings）
        Button(onClick = { vm.savePlayerSettingsToScenarioDir() }) { Text("存设置") }
        // G27：地图截图导出 PNG（桌面 File → Save Map Screenshot；View.draw → MediaStore 相册）
        Button(onClick = {
            if (vm.file == null) { vm.toast("请先打开一个场景"); return@Button }
            val bmp = captureScreenshot()
            if (bmp == null) { vm.toast("截图失败：画布不可用"); return@Button }
            saveScreenshotToGallery(bmp)
        }) { Text("截图") }
        Button(onClick = { vm.toggleReplay() }) { Text(if (vm.replayTimeline.isNotEmpty()) "退出回放" else "回放") }
    }

    // ============ G27 地图截图（桌面 File → Save Map Screenshot） ============

    /** 将当前窗口内容绘制到 Bitmap（View.draw：兼容 Compose 视图树，无需 Surface 权限） */
    private fun captureScreenshot(): Bitmap? {
        val view = window.decorView.rootView
        if (view.width <= 0 || view.height <= 0) return null
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        return bmp
    }

    /** 保存 PNG 到系统相册（MediaStore；API 29+ 免权限，低版本尝试旧接口并容错提示） */
    private fun saveScreenshotToGallery(bmp: Bitmap) {
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
                // 注：insertImage 自 API 29 起弃用，此分支为低版本兼容路径，有意保留，仅压制告警
                @Suppress("DEPRECATION")
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

    /** 选中单位操作条（契约7：需求2 编辑入口显性化）：单位名+类型 + 编辑 + 航路点 + 弧 + 移动 + Paste + 取消选中 */
    @Composable
    private fun SelectedUnitBar(
        unit: SimUnit,
        onEdit: () -> Unit,
        onWaypoints: () -> Unit,
        onArcs: () -> Unit,
        onPaste: () -> Unit,
        onManualMove: () -> Unit,
        onClear: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${unit.name}（${unit.unitType}）",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onWaypoints) { Text("航路点") }
                TextButton(onClick = onArcs) { Text("弧") }
                // G15：手动移动（桌面版 ContainerMove：DoMove/Pause/UndoMove/速度档位）
                TextButton(onClick = onManualMove) { Text("移动") }
                // G29：Paste（粘贴剪贴板单位到视野中心；无选中单位时操作条不显示，剪贴板内单位仍可经编辑窗复制）
                TextButton(onClick = onPaste) { Text("Paste") }
                TextButton(onClick = onClear) { Text("取消选中") }
            }
        }
    }

    /**
     * G06：导出运动命令单位选择对话框（桌面 WindowExportOrders）。
     * 左列全部单位（点击选中）→ 「添加→」进右列已选列表；「←移除」退回；
     * 玩家名参与文件名（Movement - <玩家名>.json）。确认后由调用方发起目录选择。
     */
    @Composable
    private fun ExportOrdersDialog(
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
                Column {
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
}
