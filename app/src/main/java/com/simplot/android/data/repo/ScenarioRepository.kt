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
     * 从任意 URI 读取存档（自动识别明文 .json / 混淆 .SpScn）
     */
    fun load(uri: Uri): ScenarioFile {
        val raw = readBytes(uri)
        val name = queryName(uri).lowercase()
        val text = if (name.endsWith(".spscn")) {
            SpScnCodec.fromScnFileBytes(raw)
        } else {
            SpScnCodec.fromJsonFileBytes(raw)
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
        val uri = DocumentsContract.createDocument(
            context.contentResolver, directory, "application/json", name
        ) ?: return
        writeBytes(uri, SpScnCodec.toJsonFileBytes(DEFAULT_PLAYER_SETTINGS))
    }

    companion object {
        /** 默认玩家显示设置（对应桌面版 player_settings.json 常见开关） */
        const val DEFAULT_PLAYER_SETTINGS: String = """{"ShowTracks":true,"ShowTrackTimes":false,"ShowTurnTrackTimes":false,"ShowUnits":true,"ShowTextTags":true,"ShowSensors":false,"ShowWeapons":false,"ShowGrid":true,"ShowScale":true,"ShowArcs":false,"ShowBearings":false}"""
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
        val root = com.google.gson.JsonObject()
        root.addProperty("File", "Referee")   // 桌面版根键（LoadMoveOrders 读取 File 判断格式）
        val arr = com.google.gson.JsonArray()
        units.forEach { u ->
            val o = com.google.gson.JsonObject()
            o.addProperty("IdNum", u.idNum)
            val wps = com.google.gson.JsonArray()
            // 未来航路点 + 当前点（桌面版 BuildWaypointArray：WaypointToJson 标准 12 键）
            (u.futureWaypointArray + u.pastWaypointArray).forEach { w ->
                wps.add(com.simplot.android.data.codec.JsonUtil.gson.toJsonTree(w))
            }
            o.add("Waypoints", wps)
            arr.add(o)
        }
        root.add("Units", arr)
        val uri = childOrCreate(directory, "Movement - $playerName.json")
        writeBytes(uri, com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(root).toByteArray(Charsets.UTF_8))
    }

    // ============ SAF 文件操作 ============

    /**
     * 获取目录下已有子文档 URI；不存在则创建。
     * （避免重复保存生成 "Blue (1).SpScn" 副本）
     */
    private fun childOrCreate(directory: Uri, name: String): Uri {
        findChild(directory, name)?.let { return it }
        return DocumentsContract.createDocument(
            context.contentResolver, directory, "application/octet-stream", name
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
