package com.simplot.android

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.repo.ScenarioRepository
import com.simplot.android.engine.FogOfWar
import com.simplot.android.engine.MovementEngine
import com.simplot.android.engine.ReplayEngine
import com.simplot.android.engine.TurnState
import com.simplot.android.render.Camera
import com.simplot.android.render.MapRenderer
import com.simplot.android.ui.components.ReplayBar
import com.simplot.android.ui.components.SceneCanvas
import com.simplot.android.ui.components.TurnControlBar
import com.simplot.android.ui.components.UnitEditSheet
import com.simplot.android.ui.theme.SimPlotTheme
import com.simplot.android.data.model.Unit

class MainActivity : ComponentActivity() {

    private lateinit var repo: ScenarioRepository
    private val camera = Camera()
    private val mapRenderer = MapRenderer()

    // 文件选择回调
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadScenario(it) }
    }
    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { saveThreeFiles(it) }
    }
    // 地图选择
    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { mapRenderer.loadMapImage(contentResolver, it) }
    }

    // 场景状态
    private var file by mutableStateOf<ScenarioFile?>(null)
    private var selectedUnitId by mutableStateOf<String?>(null)
    private var editUnit by mutableStateOf<Unit?>(null)

    // 回放状态
    private var replayTimeline by mutableStateOf<List<ReplayEngine.Frame>>(emptyList())
    private var replayIndex by mutableStateOf(0)
    private var replayPlaying by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScenarioRepository(this)
        setContent {
            SimPlotTheme {
                MainScreen()
            }
        }
    }

    private fun loadScenario(uri: Uri) {
        try {
            val loaded = repo.load(uri)
            applyLoaded(loaded)
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    private fun loadSample(name: String) {
        try {
            val loaded = repo.loadFromAssets(name)
            applyLoaded(loaded)
        } catch (e: Exception) {
            toast("示例加载失败：${e.message}")
        }
    }

    private fun applyLoaded(loaded: ScenarioFile) {
        file = loaded
        selectedUnitId = null
        // 自适应视图
        if (loaded.units.isNotEmpty()) {
            val xs = loaded.units.map { it.x }
            val ys = loaded.units.map { it.y }
            camera.fitBounds(xs.min(), xs.max(), ys.min(), ys.max(), 1000, 1000)
        }
        toast("已加载场景：${loaded.scenario.scenarioName}（${loaded.units.size} 单位）")
    }

    private fun saveThreeFiles(directory: Uri) {
        val current = file ?: run {
            toast("请先打开一个场景")
            return
        }
        try {
            val fileName = current.scenario.scenarioName.ifBlank { "scenario" }
            // 需求二：红蓝视角按感知过滤
            val blueView = FogOfWar.applyPerspective(current, "Blue")
            val redView = FogOfWar.applyPerspective(current, "Red")
            repo.savePerceptionAware(directory, fileName, current, blueView, redView)
            toast("已保存：$fileName.json + Blue.SpScn + Red.SpScn")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    private fun doTurn() {
        val f = file ?: return
        MovementEngine.advance(f, f.time.currentTurnInterval)
        file = f
        toast("Do：已移动至 ${f.time.currentPositionTime}")
    }

    private fun undo() {
        val f = file ?: return
        TurnState.undo(f, f.time.currentTurnInterval)
        file = f
        toast("Undo")
    }

    private fun next() {
        val f = file ?: return
        TurnState.confirmNext(f, f.time.currentTurnInterval)
        file = f
        toast("Next：回合已确认")
    }

    private fun applyEdit(unit: Unit) {
        file?.let { f ->
            // 就地更新单位
            val idx = f.units.indexOfFirst { it.idNum == unit.idNum }
            if (idx >= 0) f.units[idx] = unit
            file = f
        }
    }

    private fun deleteUnit(unit: Unit) {
        file?.let { f ->
            f.units.removeAll { it.idNum == unit.idNum }
            f.objects.removeAll { it == unit.idNum }
            file = f
            selectedUnitId = null
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @androidx.compose.runtime.Composable
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun MainScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(file?.scenario?.scenarioName ?: "SimPlot 安卓") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    actions = {
                        TextButtonRow()
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val f = file
                if (f != null) {
                    // 回放模式：帧覆盖画布 + 回放控制条；正常模式：实时编辑
                    val replaying = replayTimeline.isNotEmpty()
                    SceneCanvas(
                        file = f,
                        camera = camera,
                        mapRenderer = mapRenderer,
                        selectedUnitId = selectedUnitId,
                        onSelect = { selectedUnitId = it },
                        onLongPress = { editUnit = it },
                        replayFrame = if (replaying) replayTimeline[replayIndex] else null,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    if (replaying) {
                        // 自动播放：每 1 秒前进一帧，到尾停止
                        LaunchedEffect(replayPlaying, replayIndex) {
                            if (replayPlaying && replayIndex < replayTimeline.size - 1) {
                                kotlinx.coroutines.delay(1000)
                                replayIndex += 1
                                if (replayIndex >= replayTimeline.size - 1) replayPlaying = false
                            }
                        }
                        ReplayBar(
                            timeline = replayTimeline,
                            frameIndex = replayIndex,
                            playing = replayPlaying,
                            onFrameChange = { replayIndex = it },
                            onPlayPause = {
                                if (replayIndex >= replayTimeline.size - 1 && !replayPlaying) replayIndex = 0
                                replayPlaying = !replayPlaying
                            }
                        )
                    } else {
                        TurnControlBar(
                            file = f,
                            onDo = ::doTurn,
                            onUndo = ::undo,
                            onNext = ::next,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("请点击左上角「打开」选择场景存档", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // 单位编辑弹层
        editUnit?.let { unit ->
            UnitEditSheet(
                unit = unit,
                onApply = ::applyEdit,
                onDelete = ::deleteUnit,
                onDismiss = { editUnit = null }
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun androidx.compose.foundation.layout.RowScope.TextButtonRow() {
        // 顶部按钮：示例 / 打开 / 保存 / 地图 / 回放
        Button(onClick = { loadSample("冰海巨兽.json") }) { Text("示例") }
        Button(onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
            Text("打开")
        }
        Button(onClick = { pickDir.launch(null) }) { Text("保存") }
        Button(onClick = { pickMap.launch(arrayOf("image/*")) }) { Text("地图") }
        Button(onClick = {
            val f = file ?: return@Button
            if (replayTimeline.isNotEmpty()) {
                // 再次点击退出回放
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
        }) { Text(if (replayTimeline.isNotEmpty()) "退出回放" else "回放") }
    }
}
