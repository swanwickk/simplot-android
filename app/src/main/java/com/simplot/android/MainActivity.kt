package com.simplot.android

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplot.android.data.util.unitDistances
import com.simplot.android.data.model.Unit as SimUnit
import com.simplot.android.ui.GameViewModel
import com.simplot.android.ui.components.ArcEditorDialog
import com.simplot.android.ui.components.ConvoyDialog
import com.simplot.android.ui.components.NewPositionDialog
import com.simplot.android.ui.components.NewUnitDialog
import com.simplot.android.ui.components.ReplayBar
import com.simplot.android.ui.components.SceneCanvas
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
        uri?.let { vm?.exportMovementOrders(it) }
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
    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm?.loadMapFile(it) }
    }

    // 供回调使用的 ViewModel 引用（onCreate 中赋值）
    private var vm: GameViewModel? = null

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
    private fun MainScreen(vm: GameViewModel) {
        // Toast 订阅（一次性消息）
        val toastMsg by vm.toasts.collectAsState()
        LaunchedEffect(toastMsg) {
            toastMsg?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                vm.clearToast()
            }
        }

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
                    val unitDist = if (!replaying && !vm.measureMode) vm.selectedUnitId?.let { unitDistances(f, it) } else null
                    // 契约7：选中单位 → 显性操作条（编辑入口显性化；测量/回放中不显示，长按编辑路径保留）
                    val selUnit = vm.selectedUnitId?.let { id -> f.units.firstOrNull { it.idNum == id } }
                    if (selUnit != null && !vm.measureMode && !replaying) {
                        SelectedUnitBar(
                            unit = selUnit,
                            onEdit = { vm.editUnit = selUnit },
                            onWaypoints = { vm.editWaypointsUnit = selUnit },
                            onArcs = { vm.editArcUnit = selUnit },
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
                        onLongPress = { vm.editUnit = it },
                        replayFrame = if (replaying) vm.replayTimeline[vm.replayIndex] else null,
                        tick = vm.revision,
                        measureMode = vm.measureMode && !replaying,
                        onMeasureDone = { start, end -> vm.onMeasureComplete(start, end) },
                        savedMeasures = vm.measureLog,
                        unitDistances = unitDist,
                        symbolStyle = vm.symbolStyle,
                        showSensors = vm.showSensors,
                        showWeapons = vm.showWeapons,
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

        // 单位编辑弹层
        vm.editUnit?.let { unit ->
            UnitEditSheet(
                unit = unit,
                onApply = { vm.applyEdit(it); vm.editUnit = null },
                onDelete = { vm.deleteUnit(it); vm.editUnit = null },
                onDuplicate = { vm.duplicateUnit(it) },
                onDismiss = { vm.editUnit = null }
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
                onDismiss = { vm.showNewUnit = false },
                onCreate = { domain, name, unitType, unitClass, side, x, y ->
                    vm.createNewUnit(domain, name, unitType, unitClass, side, x, y)
                    vm.showNewUnit = false
                }
            )
        }

        // 新位置计算器（P2 恢复：桌面版 ContainerNewPosition）
        if (vm.showNewPosition) {
            NewPositionDialog(
                units = vm.file?.units ?: emptyList(),
                onDismiss = { vm.showNewPosition = false },
                onCalc = { refId, bearing, dist -> vm.calcNewPosition(refId, bearing, dist); vm.showNewPosition = false }
            )
        }

        // 护航队创建（P2 恢复：桌面版 WindowConvoy）
        if (vm.showConvoy) {
            ConvoyDialog(
                onDismiss = { vm.showConvoy = false },
                onCreate = { name, count, dist -> vm.createConvoy(name, count, dist); vm.showConvoy = false }
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

        // 新位置计算器已删除（反馈⑩：功能整体移除，原顶部按钮与编辑菜单入口一并去掉）
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.TextButtonRow(vm: GameViewModel) {
        // 顶部按钮：打开 / 保存 / 地图 / 测量 / CWS-NTDS / 导出CSV / 导出 / 回放（契约7：去掉示例；反馈⑩：新位置、护航队功能已整体移除）
        Button(onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
            Text("打开")
        }
        Button(onClick = {
            // 反馈⑱：每次保存都弹系统「保存为」对话框（选路径+文件名）
            if (vm.file == null) { vm.toast("请先打开一个场景"); return@Button }
            val defaultName = vm.file?.scenario?.scenarioName?.ifBlank { "scenario" } ?: "scenario"
            saveFile.launch("$defaultName.json")
        }) { Text("保存") }
        Button(onClick = { pickMap.launch(arrayOf("image/*")) }) { Text("地图") }
        // P2 恢复：新建单位 / 新位置计算器 / 护航队
        Button(onClick = { if (vm.file != null) vm.showNewUnit = true else vm.toast("请先打开一个场景") }) { Text("新单位") }
        Button(onClick = {
            if (vm.file?.units?.isEmpty() != false) { vm.toast("无单位可作参考"); return@Button }
            vm.showNewPosition = true
        }) { Text("新位置") }
        Button(onClick = { if (vm.file != null) vm.showConvoy = true else vm.toast("请先打开一个场景") }) { Text("护航队") }
        Button(onClick = {
            if (vm.file == null) return@Button
            if (vm.replayTimeline.isNotEmpty()) { vm.toast("回放中不可测量"); return@Button }
            vm.measureMode = !vm.measureMode
            if (vm.measureMode) vm.toast("测量模式：拖动画线，轻点选中单位；退出即清除测量线") else {
                vm.selectedUnitId = null
                vm.clearMeasures()  // 修复 B：退出测量模式清除全部测量线
            }
        }) { Text(if (vm.measureMode) "退出测量" else "测量") }
        Button(onClick = { vm.toggleSymbolStyle() }) { Text(if (vm.symbolStyle == com.simplot.android.render.UnitRenderer.SymbolStyle.NTDS) "CWS" else "NTDS") }
        // P1：传感器/武器弧显示开关（桌面版 Display_Options ShowSensors/ShowWeapons）
        Button(onClick = { vm.showSensors = !vm.showSensors; vm.showWeapons = vm.showSensors }) {
            Text(if (vm.showSensors) "弧开" else "弧关")
        }
        Button(onClick = {
            if (vm.measureLog.isEmpty()) { vm.toast("无测量记录，先测量再导出"); return@Button }
            exportCsvDir.launch(null)
        }) { Text("导出CSV") }
        Button(onClick = {
            if (vm.file?.units?.isEmpty() != false) { vm.toast("无单位可导出"); return@Button }
            exportDir.launch(null)
        }) { Text("导出") }
        Button(onClick = {
            if (vm.file == null) { vm.toast("请先打开一个场景"); return@Button }
            importOrders.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }) { Text("导入") }
        Button(onClick = {
            if (vm.file == null) { vm.toast("请先打开一个场景"); return@Button }
            val defaultName = vm.file?.scenario?.scenarioName?.ifBlank { "setup" } ?: "setup"
            saveSetupFile.launch("$defaultName.json")
        }) { Text("Setup") }
        Button(onClick = { vm.toggleReplay() }) { Text(if (vm.replayTimeline.isNotEmpty()) "退出回放" else "回放") }
    }

    /** 选中单位操作条（契约7：需求2 编辑入口显性化）：单位名+类型 + 编辑 + 航路点 + 弧 + 取消选中 */
    @Composable
    private fun SelectedUnitBar(
        unit: SimUnit,
        onEdit: () -> Unit,
        onWaypoints: () -> Unit,
        onArcs: () -> Unit,
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
                TextButton(onClick = onClear) { Text("取消选中") }
            }
        }
    }
}
