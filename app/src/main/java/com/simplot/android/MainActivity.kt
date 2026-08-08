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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplot.android.data.util.CoordUtil
import com.simplot.android.data.model.Unit as SimUnit
import com.simplot.android.ui.GameViewModel
import com.simplot.android.ui.components.ReplayBar
import com.simplot.android.ui.components.SceneCanvas
import com.simplot.android.ui.components.TurnControlBar
import com.simplot.android.ui.components.UnitEditSheet
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
    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm?.saveThreeFiles(it) }
    }
    private val exportDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm?.exportMovementOrders(it) }
    }
    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm?.loadMapFile(it) }
    }

    // 供回调使用的 ViewModel 引用（onCreate 中赋值）
    private var vm: GameViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    SceneCanvas(
                        file = f,
                        camera = vm.camera,
                        mapRenderer = vm.mapRenderer,
                        selectedUnitId = vm.selectedUnitId,
                        onSelect = { vm.selectedUnitId = it },
                        onLongPress = { vm.editUnit = it },
                        replayFrame = if (replaying) vm.replayTimeline[vm.replayIndex] else null,
                        tick = vm.revision,
                        measureMode = vm.measureMode && !replaying,
                        onMeasureDone = { vm.measureMode = false },
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

        // 新位置计算器（桌面版 ContainerNewPosition：参考单位+方位+距离 → 坐标）
        if (vm.showCalcPosition) {
            CalcPositionDialog(
                units = vm.file?.units ?: emptyList(),
                onToast = { vm.toast(it) },
                onDismiss = { vm.showCalcPosition = false },
                onResult = { name, x, y, bearing, distNm ->
                    vm.toast("$name：方位 $bearing° 距离 $distNm nmi\nX=$x  Y=$y")
                    vm.showCalcPosition = false
                }
            )
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.TextButtonRow(vm: GameViewModel) {
        // 顶部按钮：示例 / 打开 / 保存 / 地图 / 测量 / 计算 / 护航队 / 导出 / 回放
        Button(onClick = { vm.loadSample("冰海巨兽.json") }) { Text("示例") }
        Button(onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
            Text("打开")
        }
        Button(onClick = { pickDir.launch(null) }) { Text("保存") }
        Button(onClick = { pickMap.launch(arrayOf("image/*")) }) { Text("地图") }
        Button(onClick = {
            if (vm.file == null) return@Button
            if (vm.replayTimeline.isNotEmpty()) { vm.toast("回放中不可测量"); return@Button }
            vm.measureMode = !vm.measureMode
            if (vm.measureMode) vm.toast("测量模式：拖动画线，松手结束") else vm.selectedUnitId = null
        }) { Text(if (vm.measureMode) "退出测量" else "测量") }
        Button(onClick = {
            if (vm.file?.units?.isEmpty() != false) { vm.toast("无单位可作参考"); return@Button }
            vm.showCalcPosition = true
        }) { Text("计算") }
        Button(onClick = { vm.createConvoy() }) { Text("护航队") }
        Button(onClick = {
            if (vm.file?.units?.isEmpty() != false) { vm.toast("无单位可导出"); return@Button }
            exportDir.launch(null)
        }) { Text("导出") }
        Button(onClick = { vm.toggleReplay() }) { Text(if (vm.replayTimeline.isNotEmpty()) "退出回放" else "回放") }
    }

    /** 新位置计算器对话框（桌面版 ContainerNewPosition） */
    @Composable
    private fun CalcPositionDialog(
        units: List<SimUnit>,
        onToast: (String) -> Unit,
        onDismiss: () -> Unit,
        onResult: (name: String, x: Long, y: Long, bearing: Double, distNm: Double) -> Unit
    ) {
        var refId by remember { mutableStateOf(units.firstOrNull()?.idNum ?: "") }
        var bearingText by remember { mutableStateOf("") }
        var distText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("新位置计算") },
            text = {
                Column {
                    Text("参考单位", style = MaterialTheme.typography.labelMedium)
                    units.take(30).forEach { u ->
                        Row(Modifier.fillMaxWidth()) {
                            androidx.compose.material3.RadioButton(
                                selected = refId == u.idNum,
                                onClick = { refId = u.idNum }
                            )
                            Text("${u.name} (${u.side}) TN ${u.trackNumber}", Modifier.padding(top = 10.dp))
                        }
                    }
                    OutlinedTextField(
                        value = bearingText, onValueChange = { bearingText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("方位角(度, 0=北)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = distText, onValueChange = { distText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("距离(海里)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val ref = units.firstOrNull { it.idNum == refId }
                    val bearing = bearingText.toDoubleOrNull()
                    val dist = distText.toDoubleOrNull()
                    if (ref == null) { onToast("请选择参考单位"); return@Button }
                    if (bearing == null || dist == null) { onToast("请输入方位和距离"); return@Button }
                    val (dx, dy) = CoordUtil.offsetNm(bearing, dist)
                    onResult(ref.name, ref.x + dx, ref.y + dy, bearing, dist)
                }) { Text("计算") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
}
