package com.simplot.android.data.repo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import java.io.InputStream
import java.io.OutputStream

/**
 * 场景存档仓库：负责三文件读写（与桌面版 Scenarios/ 目录互通）
 *
 * 需求一：保存裁判存档时自动生成三个文件
 * - <场景名>.json  → Referee 明文
 * - Blue.SpScn     → 蓝方混淆
 * - Red.SpScn      → 红方混淆
 * 需求二：红蓝存档按感知过滤（FogOfWar），见 [savePerceptionAware]
 */
class ScenarioRepository(private val context: Context) {

    // ============ 读取 ============

    /**
     * 从任意 URI 读取存档（自动识别明文 .json / 混淆 .SpScn）。
     * 修复⑰（真机反馈：保存的 SpScn 无法读取）：SAF 的 queryName 在部分 provider 返回空/null，
     * 导致混淆 SpScn 被当明文解析失败。改为：先按文件名后缀判断；后缀不可用时
     * 用内容探测（明文 JSON 以 { 开头，混淆 SpScn 首个字节是密文 {+1='z'）。
     */
    fun load(uri: Uri): ScenarioFile {
        val raw = readBytes(uri)
        val name = queryName(uri).lowercase()
        // 修复⑰（真机反馈：保存的 SpScn 无法读取）：SAF queryName 可能为空 → 后缀判断失效。
        // 策略：后缀明确 .spscn → 解密；否则先按明文解析，失败再按 SpScn 解密（鲁棒回退）。
        val text = if (name.endsWith(".spscn")) {
            SpScnCodec.fromScnFileBytes(raw)
        } else {
            val plain = SpScnCodec.fromJsonFileBytes(raw)
            if (JsonUtil.isScenarioJson(plain)) plain
            else SpScnCodec.fromScnFileBytes(raw)
        }
        if (!JsonUtil.isScenarioJson(text)) {
            throw IllegalArgumentException("所选文件不是有效的 SimPlot 场景存档")
        }
        return JsonUtil.fromJson(text)
    }

    // ============ 保存（三文件） ============

    /**
     * 需求一：保存裁判场景，自动生成四个文件（与桌面版 Save Scenario 一致）：
     * - <场景名>.json → Referee 明文
     * - Blue.SpScn / Red.SpScn → 红蓝混淆
     * - player_settings.json → 玩家本地显示设置（已存在则不覆盖）
     * @param directory 已选中的场景目录 URI（SAF tree uri）
     * @param fileName  场景文件名主体（不含扩展名），如 "冰海巨兽"
     * @param data      裁判全量数据（File 字段会被覆盖为对应视角）
     */
    fun saveThreeFiles(directory: Uri, fileName: String, data: ScenarioFile) {
        saveJson(childOrCreate(directory, "$fileName.json"), data.copy(file = "Referee"))
        saveScn(childOrCreate(directory, "Blue.SpScn"), data.copy(file = "Blue"))
        saveScn(childOrCreate(directory, "Red.SpScn"), data.copy(file = "Red"))
        ensurePlayerSettings(directory)
    }

    /**
     * 需求二：保存四文件，红蓝存档按感知过滤。
     * 需要调用方提供 FogOfWar 过滤后的视图：
     * @param refereeData 裁判全量
     * @param blueView    蓝方可见视图
     * @param redView     红方可见视图
     */
    fun savePerceptionAware(
        directory: Uri, fileName: String,
        refereeData: ScenarioFile, blueView: ScenarioFile, redView: ScenarioFile
    ) {
        saveJson(childOrCreate(directory, "$fileName.json"), refereeData.copy(file = "Referee"))
        saveScn(childOrCreate(directory, "Blue.SpScn"), blueView.copy(file = "Blue"))
        saveScn(childOrCreate(directory, "Red.SpScn"), redView.copy(file = "Red"))
        ensurePlayerSettings(directory)
    }

    /**
     * player_settings.json：玩家本地显示设置（桌面版 40+ 显示开关，非共享数据）。
     * 仅当不存在时创建默认值（玩家设置优先，不覆盖）。
     */
    fun ensurePlayerSettings(directory: Uri) {
        val name = "player_settings.json"
        if (findChild(directory, name) != null) return
        // 修复⑩：createDocument 需 document uri（tree uri 转换，同 childOrCreate）
        val parent = try {
            val treeId = DocumentsContract.getTreeDocumentId(directory)
            DocumentsContract.buildDocumentUriUsingTree(directory, treeId)
        } catch (e: Exception) {
            directory
        }
        val uri = DocumentsContract.createDocument(
            context.contentResolver, parent, "application/json", name
        ) ?: return
        writeBytes(uri, SpScnCodec.toJsonFileBytes(DEFAULT_PLAYER_SETTINGS))
    }

    companion object {
        /** 默认玩家显示设置（对应桌面版 player_settings.json 常见开关） */
        const val DEFAULT_PLAYER_SETTINGS: String = """{"ShowTracks":true,"ShowTrackTimes":false,"ShowTurnTrackTimes":false,"ShowUnits":true,"ShowTextTags":true,"ShowSensors":false,"ShowWeapons":false,"ShowGrid":true,"ShowScale":true,"ShowArcs":false,"ShowBearings":false}"""
    }

    /**
     * 反馈⑱：从目标文件 document uri 推导父目录 tree uri。
     * document uri 形如 content://.../document/primary:Dir/file.json → 父 treeId = primary:Dir。
     * 推导失败返回 null（仅保存目标文件本身）。
     */
    fun parentTreeUri(documentUri: Uri): Uri? {
        return try {
            val docId = DocumentsContract.getDocumentId(documentUri)
            val slash = docId.lastIndexOf('/')
            val parentId = if (slash >= 0) docId.substring(0, slash) else docId
            // tree uri = content://authority/tree/<parentId>
            val treeUri = DocumentsContract.buildTreeDocumentUri(documentUri.authority, parentId)
            // 校验可写（部分 provider 根目录不支持，交给调用方容错）
            treeUri
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 反馈⑱：写三文件——目标文件写 Referee json；同目录写 Blue/Red.SpScn（父目录可推导时）。
     * @return true=三文件都写了；false=只写了目标 json（父目录不可用）
     */
    fun saveTo(
        target: Uri, parent: Uri?,
        referee: ScenarioFile, blueView: ScenarioFile, redView: ScenarioFile
    ): Boolean {
        saveJson(target, referee.copy(file = "Referee"))
        if (parent != null) {
            try {
                saveScn(childOrCreate(parent, "Blue.SpScn"), blueView.copy(file = "Blue"))
                saveScn(childOrCreate(parent, "Red.SpScn"), redView.copy(file = "Red"))
                ensurePlayerSettings(parent)
                return true
            } catch (e: Exception) {
                // 父目录写入失败：目标 json 已保存，SpScn 留待用户手动处理
                return false
            }
        }
        return false
    }

    fun saveJson(uri: Uri, data: ScenarioFile) {
        writeBytes(uri, SpScnCodec.toJsonFileBytes(JsonUtil.toCompactJson(data)))
    }

    fun saveScn(uri: Uri, data: ScenarioFile) {
        writeBytes(uri, SpScnCodec.toScnFileBytes(JsonUtil.toCompactJson(data)))
    }

    /** 在目录中创建文件（已存在则复用），返回 URI */
    fun createFile(directory: Uri, name: String, mime: String = "application/octet-stream"): Uri {
        return childOrCreate(directory, name)
    }

    /** 写入文本（覆盖） */
    fun writeText(uri: Uri, text: String) {
        writeBytes(uri, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 导出运动命令（桌面版 WindowExportOrders / MovementOrders，反汇编确认）：
     * 文件名 "Movement - <玩家名>.json"，根结构 {File, Units}，每单位 {IdNum, Waypoints}，
     * Waypoints 用标准 WaypointToJson 12 键格式（含 AltitudeDepth/AssignedAltDepth/Ascent/Descent/
     * Number/IsTurnTime/PositionTime）。
     * 对齐：BuildUnitArray 字符串 ['IdNum','Waypoints']，BuildWaypointArray 调 WaypointToJson。
     */
    fun exportMovementOrders(directory: Uri, playerName: String, units: List<com.simplot.android.data.model.Unit>) {
        val json = com.simplot.android.data.codec.MovementOrdersCodec.toJson(units)
        val uri = childOrCreate(directory, "Movement - $playerName.json")
        writeBytes(uri, json.toByteArray(Charsets.UTF_8))
    }

    /**
     * 运动命令导入（桌面版 LoadMoveOrders）：读取 Movement Orders 文件，
     * 按 IdNum 匹配场景单位并恢复其未来航路点（JsonToWaypoint 兼容 12 键）。
     * @return 已导入航路点的单位 IdNum 列表；找不到匹配单位时该条目跳过
     */
    fun importMovementOrders(uri: Uri, file: ScenarioFile): List<String> {
        val raw = readBytes(uri)
        val text = String(raw, Charsets.UTF_8).trim()
        val parsed = com.simplot.android.data.codec.MovementOrdersCodec.parse(text)
        val imported = mutableListOf<String>()
        val byId = file.units.associateBy { it.idNum }
        parsed.forEach { (idNum, wps) ->
            val target = byId[idNum] ?: return@forEach
            target.futureWaypointArray = wps.toMutableList()
            imported.add(idNum)
        }
        return imported
    }

    /**
     * 自动存档（桌面版 SaveAuto）："Referee Turn N_日期_时间.json" 写到场景同目录。
     * 目标目录由 target 文件 URI 推导（复用 parentTreeUri）。
     */
    fun saveAuto(targetFileUri: Uri, data: ScenarioFile, turnNumber: Int): Boolean {
        val parent = parentTreeUri(targetFileUri) ?: return false
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val name = "Referee Turn ${turnNumber}_$stamp.json"
        return try {
            saveJson(childOrCreate(parent, name), data.copy(file = "Referee"))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Setup 文件保存（桌面版 SaveSetupFile）：与场景同格式，标记 "Setup"。
     * 写到目标文件 URI（系统「保存为」对话框）。
     */
    fun saveSetup(target: Uri, data: ScenarioFile): Boolean {
        return try {
            saveJson(target, data.copy(file = "Setup"))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ============ SAF 文件操作 ============

    /**
     * 获取目录下已有子文档 URI；不存在则创建。
     * （避免重复保存生成 "Blue (1).SpScn" 副本）
     */
    /**
     * 在目录中创建文件（已存在则复用），返回 URI。
     * 修复⑩（真机保存失败 Invalid URI）：DocumentsContract.createDocument 要求父目录为
     * document uri；OpenDocumentTree 返回的是 tree uri，需先 buildDocumentUriUsingTree 转换，
     * 否则抛 IllegalArgumentException("Invalid URI: ...")。
     */
    private fun childOrCreate(directory: Uri, name: String): Uri {
        findChild(directory, name)?.let { return it }
        val parent = try {
            val treeId = DocumentsContract.getTreeDocumentId(directory)
            DocumentsContract.buildDocumentUriUsingTree(directory, treeId)
        } catch (e: Exception) {
            directory   // 非 tree uri（如已有 document uri）直接使用
        }
        return DocumentsContract.createDocument(
            context.contentResolver, parent, "application/octet-stream", name
        ) ?: throw IllegalStateException("无法在所选目录创建文件：$name")
    }

    /** 在 tree uri 目录中按显示名查找子文档 */
    private fun findChild(directory: Uri, name: String): Uri? {
        val treeId = DocumentsContract.getTreeDocumentId(directory)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directory, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val display = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    if (display == name) {
                        val docId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                        return DocumentsContract.buildDocumentUriUsingTree(directory, docId)
                    }
                }
                null
            } ?: null
        } catch (e: Exception) {
            null
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开文件")
        input.use { return it.readBytes() }
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        val output: OutputStream = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("无法写入文件")
        output.use { it.write(bytes) }
    }

    private fun queryName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else uri.lastPathSegment ?: ""
                } else uri.lastPathSegment ?: ""
            } ?: uri.lastPathSegment ?: ""
        } catch (e: Exception) {
            uri.lastPathSegment ?: ""
        }
    }
}
