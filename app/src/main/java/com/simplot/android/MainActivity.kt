package com.simplot.android

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import com.simplot.android.data.util.CoordUtil
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
    private val exportDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val f = file ?: return@registerForActivityResult
            try {
                repo.exportMovementOrders(it, "Player", f.units)
                toast("已导出运动命令：Movement - Player.json")
            } catch (e: Exception) {
                toast("导出失败：${e.message}")
            }
        }
    }
    // 地图选择：支持官方 MapMaker JSON 配置（BoundaryRect）或图片
    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadMapFile(it) }
    }

    // 场景状态
    private var file by mutableStateOf<ScenarioFile?>(null)
    private var selectedUnitId by mutableStateOf<String?>(null)
    private var editUnit by mutableStateOf<Unit?>(null)
    // 回合推进标记：file 对象可变但引用不变，用 tick 触发 Compose 重组（时间/画布刷新）
    private var turnTick by mutableStateOf(0)
    // 测量模式开关 + 新位置计算器状态
    private var measureMode by mutableStateOf(false)
    private var showCalcPosition by mutableStateOf(false)
    // Range 耗尽弹窗（桌面版 HasRangeRemaining 三选：继续移动/删除单位/停止单位）
    private var rangeExhaustedUnit by mutableStateOf<Unit?>(null)

    // 回放状态
    private var replayTimeline by mutableStateOf<List<ReplayEngine.Frame>>(emptyList())
    private var replayIndex by mutableStateOf(0)
    private var replayPlaying by mutableStateOf(false)
    private var replayDelayMs by mutableStateOf(1000L)

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
            // 场景自带地图（MapFileName 指向同目录 JSON 配置）
            loaded.scenario.mapFileName.takeIf { it.isNotBlank() }?.let { mapName ->
                autoLoadMap(mapName, uri)
            }
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    /**
     * 加载地图文件：
     * - .json → MapMaker 配置（解析 BoundaryRect，背景图名记录待同目录加载）
     * - 图片  → 直接作为地图位图
     */
    private fun loadMapFile(uri: Uri) {
        try {
            val name = queryDisplayName(uri)?.lowercase() ?: ""
            if (name.endsWith(".json")) {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("无法读取地图配置")
                mapRenderer.parseMapConfigJson(text)
                toast("地图配置已加载${if (mapRenderer.pendingBackgroundName != null) "：${mapRenderer.pendingBackgroundName}" else ""}")
            } else {
                mapRenderer.loadMapImage(contentResolver, uri)
                toast("地图图片已加载")
            }
        } catch (e: Exception) {
            toast("地图加载失败：${e.message}")
        }
    }

    /** 场景 MapFileName 指向的地图：尝试从场景同目录解析配置 + 背景图 */
    private fun autoLoadMap(mapName: String, scenarioUri: Uri) {
        try {
            val cfgUri = findSibling(scenarioUri, mapName)
            if (cfgUri != null) {
                val text = contentResolver.openInputStream(cfgUri)?.bufferedReader()?.use { it.readText() }
                    ?: return
                mapRenderer.parseMapConfigJson(text)
                // 背景图：配置同目录的 BackgroundFileName
                mapRenderer.pendingBackgroundName?.let { bg ->
                    val bgUri = findSibling(scenarioUri, bg)
                    if (bgUri != null) mapRenderer.loadMapImage(contentResolver, bgUri)
                }
                toast("已加载地图：$mapName")
            }
        } catch (e: Exception) {
            // 地图缺失不阻塞场景加载
        }
    }

    /** 在 URI 所在目录按文件名查找兄弟文件（替换 document id 最后一段，文件名需 URL 编码） */
    private fun findSibling(uri: Uri, name: String): Uri? {
        val docId = uri.lastPathSegment ?: return null
        val slash = docId.lastIndexOf('/')
        val parent = if (slash >= 0) docId.substring(0, slash + 1) else ""
        // name 含空格等字符（如 "Iron Bottom Sound Image.png"），必须编码
        return Uri.parse("content://" + uri.authority + "/document/" + parent + Uri.encode(name))
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
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
        turnTick++
        // 视野自适应由 SceneCanvas.onSizeChanged 按真实画布尺寸执行
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
        turnTick++
        // Range 耗尽检测：仅提示，桌面版三选弹窗（Continue/Delete/Stop）；已选"继续移动"的单位不再提示
        f.units.firstOrNull { it.range == 0 && !it.showSunk && !it.ignoreRange }?.let { rangeExhaustedUnit = it }
        toast("Do：已移动至 ${f.time.currentPositionTime}")
    }

    private fun undo() {
        val f = file ?: return
        TurnState.undo(f, f.time.currentTurnInterval)
        turnTick++
        toast("Undo")
    }

    private fun next() {
        val f = file ?: return
        TurnState.confirmNext(f, f.time.currentTurnInterval)
        turnTick++
        toast("Next：回合已确认")
    }

    private fun applyEdit(unit: Unit) {
        file?.let { f ->
            // 就地更新单位
            val idx = f.units.indexOfFirst { it.idNum == unit.idNum }
            if (idx >= 0) f.units[idx] = unit
            turnTick++
        }
    }

    private fun deleteUnit(unit: Unit) {
        file?.let { f ->
            f.units.removeAll { it.idNum == unit.idNum }
            f.objects.removeAll { it == unit.idNum }
            selectedUnitId = null
            turnTick++
        }
    }

    /**
     * 单位复制（桌面版 CopyUnit）：深拷贝并分配新 IdNum/TrackNumber，放到原单位附近偏移。
     * 陆上单位（Installation/LandFormation）不复制传感器/武器。
     */
    private fun duplicateUnit(unit: Unit) {
        file?.let { f ->
            val gson = com.simplot.android.data.codec.JsonUtil.gson
            val copy = gson.fromJson(gson.toJson(unit), Unit::class.java)
            // 新身份
            copy.idNum = nextId(f)
            copy.trackNumber = (f.units.maxOfOrNull { it.trackNumber } ?: 2400) + 1
            copy.name = unit.name + " (副本)"
            copy.isNewThisTurn = true
            // 偏移 2 海里（东南）
            val (dx, dy) = com.simplot.android.data.util.CoordUtil.offsetNm(135.0, 2.0)
            copy.x = unit.x + dx
            copy.y = unit.y + dy
            // 陆上单位不复制传感器/武器（桌面版 CopyLandUnit 无 CopySensors）
            if (copy.idNum.startsWith("L")) {
                copy.sensorArray = null
                copy.weaponArray = null
            }
            f.units.add(copy)
            selectedUnitId = copy.idNum
            turnTick++
            toast("已复制：${copy.name}")
        }
    }

    /**
     * 生成护航队（桌面版 Game.Convoy.CreateConvoy）：
     * COMMODORE 居中（Blue Merchant），Merchant 商船环绕，dist=2000 码，角度 360/count 均匀分布。
     * ⚠️ FormationDistance 存档单位 = 文件单位（海里×100000），创建时用 yardsToFile(2000) 转换
     * （反汇编确认 MoveCompassFormation 直接 Distance×Sin/Cos 与中心坐标相加）。
     */
    private fun createConvoy() {
        file?.let { f ->
            val commodore = com.simplot.android.data.model.Unit(
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
            val distFile = com.simplot.android.data.util.CoordUtil.yardsToFile(2000.0).toInt()   // 2000 码 → 文件单位
            for (i in 0 until escortCount) {
                val angle = 360.0 / escortCount * i
                val (dx, dy) = com.simplot.android.data.util.CoordUtil.offsetYards(angle, 2000.0)
                units.add(
                    com.simplot.android.data.model.Unit(
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
            turnTick++
            toast("已生成护航队：1 COMMODORE + $escortCount 商船")
        }
    }

    /** 新 IdNum（桌面版：Domain 首字母 + 递增序号） */
    private fun nextId(f: com.simplot.android.data.model.ScenarioFile): String {
        val prefix = "S"
        var max = 0
        f.units.forEach { u ->
            u.idNum.removePrefix(prefix).toIntOrNull()?.let { if (it > max) max = it }
        }
        return prefix + (max + 1).toString().padStart(3, '0')
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
                        tick = turnTick,
                        measureMode = measureMode && !replaying,
                        onMeasureDone = { measureMode = false },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    if (replaying) {
                        // 自动播放：每 1 秒前进一帧，到尾停止
                        LaunchedEffect(replayPlaying, replayIndex, replayDelayMs) {
                            if (replayPlaying && replayIndex < replayTimeline.size - 1) {
                                kotlinx.coroutines.delay(replayDelayMs)
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
                            },
                            onSpeedChange = { replayDelayMs = it }
                        )
                    } else {
                        TurnControlBar(
                            file = f,
                            onDo = ::doTurn,
                            onUndo = ::undo,
                            onNext = ::next,
                            tick = turnTick,
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
                onDuplicate = ::duplicateUnit,
                onDismiss = { editUnit = null }
            )
        }

        // Range 耗尽三选弹窗（桌面版 HasRangeRemaining：Continue Movement / Delete Unit / Stop Unit）
        rangeExhaustedUnit?.let { u ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { rangeExhaustedUnit = null },
                title = { Text("航程耗尽") },
                text = { Text("${u.name} 剩余航程为 0，如何处理？") },
                confirmButton = {
                    Button(onClick = {
                        // Continue Movement（桌面版语义）：无视 Range 继续航行（Range 保持 0）
                        val u2 = u
                        rangeExhaustedUnit = null
                        f4@ file?.let { f ->
                            val t = f.units.firstOrNull { it.idNum == u2.idNum }
                            if (t != null) t.ignoreRange = true
                            turnTick++
                        }
                        toast("${u2.name} 继续（无视航程限制）")
                    }) { Text("继续移动") }
                },
                dismissButton = {
                    Row {
                        androidx.compose.material3.TextButton(onClick = {
                            // Delete Unit
                            val u2 = u
                            rangeExhaustedUnit = null
                            f2@ file?.let { f ->
                                f.units.removeAll { it.idNum == u2.idNum }
                                f.objects.removeAll { it == u2.idNum }
                                selectedUnitId = null
                                turnTick++
                            }
                            toast("已删除 ${u2.name}")
                        }) { Text("删除单位") }
                        androidx.compose.material3.TextButton(onClick = {
                            // Stop Unit：Range 置 -100000 并停船
                            val u2 = u
                            rangeExhaustedUnit = null
                            f3@ file?.let { f ->
                                val t = f.units.firstOrNull { it.idNum == u2.idNum }
                                if (t != null) { t.range = MovementEngine.RANGE_UNLIMITED; t.speed = 0 }
                                turnTick++
                            }
                            toast("${u2.name} 已停止")
                        }) { Text("停止单位") }
                    }
                }
            )
        }

        // 新位置计算器（桌面版 ContainerNewPosition：参考单位+方位+距离 → 坐标）
        if (showCalcPosition) {
            CalcPositionDialog(
                units = file?.units ?: emptyList(),
                onDismiss = { showCalcPosition = false },
                onResult = { name, x, y, bearing, distNm ->
                    toast("$name：方位 $bearing° 距离 $distNm nmi\nX=$x  Y=$y")
                    showCalcPosition = false
                }
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun androidx.compose.foundation.layout.RowScope.TextButtonRow() {
        // 顶部按钮：示例 / 打开 / 保存 / 地图 / 测量 / 计算 / 回放
        Button(onClick = { loadSample("冰海巨兽.json") }) { Text("示例") }
        Button(onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
            Text("打开")
        }
        Button(onClick = { pickDir.launch(null) }) { Text("保存") }
        Button(onClick = { pickMap.launch(arrayOf("image/*")) }) { Text("地图") }
        Button(onClick = {
            val f = file ?: return@Button
            if (replayTimeline.isNotEmpty()) { toast("回放中不可测量"); return@Button }
            measureMode = !measureMode
            if (measureMode) toast("测量模式：拖动画线，松手结束") else selectedUnitId = null
        }) { Text(if (measureMode) "退出测量" else "测量") }
        Button(onClick = {
            val f = file ?: return@Button
            if (f.units.isEmpty()) { toast("无单位可作参考"); return@Button }
            showCalcPosition = true
        }) { Text("计算") }
        Button(onClick = {
            val f = file ?: return@Button
            createConvoy()
        }) { Text("护航队") }
        Button(onClick = {
            val f = file ?: return@Button
            if (f.units.isEmpty()) { toast("无单位可导出"); return@Button }
            exportDir.launch(null)
        }) { Text("导出") }
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

    /** 新位置计算器对话框（桌面版 ContainerNewPosition） */
    @androidx.compose.runtime.Composable
    private fun CalcPositionDialog(
        units: List<Unit>,
        onDismiss: () -> kotlin.Unit,
        onResult: (name: String, x: Long, y: Long, bearing: Double, distNm: Double) -> kotlin.Unit
    ) {
        var refId by remember { mutableStateOf(units.firstOrNull()?.idNum ?: "") }
        var bearingText by remember { mutableStateOf("") }
        var distText by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
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
                    if (ref == null) { toast("请选择参考单位"); return@Button }
                    if (bearing == null || dist == null) { toast("请输入方位和距离"); return@Button }
                    val (dx, dy) = CoordUtil.offsetNm(bearing, dist)
                    onResult(ref.name, ref.x + dx, ref.y + dy, bearing, dist)
                }) { Text("计算") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
}
