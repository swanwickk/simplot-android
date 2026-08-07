package com.simplot.android.data.repo

import android.content.Context
import android.net.Uri
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

    /** 从内置 assets 加载示例场景 */
    fun loadFromAssets(assetName: String): ScenarioFile {
        val text = context.assets.open("scenarios/$assetName").bufferedReader().use { it.readText() }
        if (!JsonUtil.isScenarioJson(text)) {
            throw IllegalArgumentException("内置场景无效：$assetName")
        }
        return JsonUtil.fromJson(text)
    }

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
     * 需求一：保存裁判场景，自动生成三文件。
     * @param directory 已选中的场景目录 URI（SAF tree uri）
     * @param fileName  场景文件名主体（不含扩展名），如 "冰海巨兽"
     * @param data      裁判全量数据（File 字段会被覆盖为对应视角）
     */
    fun saveThreeFiles(directory: Uri, fileName: String, data: ScenarioFile) {
        saveJson(createChild(directory, "$fileName.json"), data.apply { file = "Referee" })
        saveScn(createChild(directory, "Blue.SpScn"), data.apply { file = "Blue" })
        saveScn(createChild(directory, "Red.SpScn"), data.apply { file = "Red" })
    }

    /**
     * 需求二：保存三文件，红蓝存档按感知过滤。
     * 需要调用方提供 FogOfWar 过滤后的视图：
     * @param refereeData 裁判全量
     * @param blueView    蓝方可见视图
     * @param redView     红方可见视图
     */
    fun savePerceptionAware(
        directory: Uri, fileName: String,
        refereeData: ScenarioFile, blueView: ScenarioFile, redView: ScenarioFile
    ) {
        saveJson(createChild(directory, "$fileName.json"), refereeData.apply { file = "Referee" })
        saveScn(createChild(directory, "Blue.SpScn"), blueView.apply { file = "Blue" })
        saveScn(createChild(directory, "Red.SpScn"), redView.apply { file = "Red" })
    }

    fun saveJson(uri: Uri, data: ScenarioFile) {
        writeBytes(uri, SpScnCodec.toJsonFileBytes(JsonUtil.toCompactJson(data)))
    }

    fun saveScn(uri: Uri, data: ScenarioFile) {
        writeBytes(uri, SpScnCodec.toScnFileBytes(JsonUtil.toCompactJson(data)))
    }

    // ============ SAF 文件操作 ============

    private fun createChild(directory: Uri, name: String): Uri {
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            directory, android.provider.DocumentsContract.getTreeDocumentId(directory)
        )
        // 尝试创建子文档
        return try {
            android.provider.DocumentsContract.createDocument(
                context.contentResolver, directory, "application/octet-stream", name
            ) ?: doc
        } catch (e: Exception) {
            doc
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
