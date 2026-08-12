package com.simplot.android.ui.components

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 场景库条目（文件名 + 目录内文档 id）。
 * 纯数据（不含 Uri），便于 JVM 单测；实际 Uri 由 [DocumentsContract.buildDocumentUriUsingTree] 按需构建。
 */
data class SceneFileInfo(val name: String, val docId: String)

/** 是否场景文件名（.json / .SpScn，大小写不敏感；桌面 SimPlot 存档后缀）。点号前必须存在文件名（排除 ".json" 这类无主名文件） */
fun isSceneFileName(name: String): Boolean {
    val lower = name.lowercase()
    val dot = lower.lastIndexOf('.')
    if (dot <= 0) return false
    val ext = lower.substring(dot)
    return ext == ".json" || ext == ".spscn"
}

/** 场景文件按名称排序（大小写不敏感） */
fun sortSceneFiles(files: List<SceneFileInfo>): List<SceneFileInfo> =
    files.sortedBy { it.name.lowercase() }

/**
 * 遍历 SAF tree 目录下全部场景文件（buildChildDocumentsUriUsingTree + contentResolver.query），
 * 过滤目录与 .json/.SpScn 之外的文件，按名称排序。
 * 目录未选择返回空表；Provider 异常向上抛出，由调用方 toast。
 */
fun querySceneFiles(context: Context, dirUri: Uri?): List<SceneFileInfo> {
    if (dirUri == null) return emptyList()
    val treeId = DocumentsContract.getTreeDocumentId(dirUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, treeId)
    val files = mutableListOf<SceneFileInfo>()
    context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, null
    )?.use { cursor ->
        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            if (cursor.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR) continue
            val name = cursor.getString(nameIdx) ?: continue
            if (!isSceneFileName(name)) continue
            val docId = cursor.getString(idIdx) ?: continue
            files += SceneFileInfo(name, docId)
        }
    }
    return sortSceneFiles(files)
}

/** 查询 tree 根文档的显示名（目录名）；失败返回 null（调用方回退 uri 路径段） */
fun queryTreeDisplayName(context: Context, dirUri: Uri?): String? {
    if (dirUri == null) return null
    return try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            dirUri, DocumentsContract.getTreeDocumentId(dirUri)
        )
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) else null
        }
    } catch (e: Exception) {
        null
    }
}

/** 删除 SAF 文档（DocumentsContract.deleteDocument）；成功返回 true，失败（无权限/只读/Provider 不支持）返回 false */
fun deleteSceneDocument(context: Context, uri: Uri): Boolean = try {
    DocumentsContract.deleteDocument(context.contentResolver, uri)
    true
} catch (e: Exception) {
    false
}

/**
 * 场景库对话框（P3：应用内场景列表/管理，桌面版 WindowLoadScenarios 的安卓对应物）。
 *
 * 能力：
 * - 当前目录显示 + 「更换目录」（由 MainActivity 拉起 SAF tree 选择器）
 * - LazyColumn 列出目录内 .json/.SpScn 文件（按名称排序），轻点行打开（回调上抛 Uri）
 * - 每行「删除」按钮 → AlertDialog 确认 → DocumentsContract.deleteDocument
 * - 空态/加载失败提示；目录变化（首次打开/更换）自动重新遍历
 *
 * 横竖屏：列表高度按屏幕高度自适应（横屏矮屏自动收窄），无固定宽高，不溢出。
 */
@Composable
fun SceneLibraryDialog(
    dirUri: Uri?,
    onChangeDir: () -> Unit,
    onOpen: (Uri) -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var dirName by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<SceneFileInfo>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SceneFileInfo?>(null) }

    // 目录变化（含首次打开/更换目录）时重新遍历子文档
    LaunchedEffect(dirUri) {
        loadFailed = false
        dirName = queryTreeDisplayName(context, dirUri)
        files = try {
            querySceneFiles(context, dirUri)
        } catch (e: Exception) {
            loadFailed = true
            onToast("场景库加载失败：${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }
    }

    // 列表高度自适应：约 190dp 为标题/目录行/按钮/留白预算，横屏矮屏自动收窄避免溢出
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp - 190).coerceIn(140, 340)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("场景库") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 当前目录 + 更换目录
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dirName ?: (dirUri?.lastPathSegment ?: "未选择目录"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    TextButton(onClick = onChangeDir) { Text("更换目录") }
                }
                if (loadFailed || files.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp, max = listMaxHeight.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                loadFailed -> "目录加载失败"
                                dirUri == null -> "未选择目录，点击「更换目录」"
                                else -> "目录为空或未选择目录"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight.dp)) {
                        items(files, key = { it.docId }) { info ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = info.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            dirUri?.let { onOpen(DocumentsContract.buildDocumentUriUsingTree(it, info.docId)) }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 10.dp)
                                )
                                TextButton(onClick = { pendingDelete = info }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    // 删除确认（独立 AlertDialog 层叠在场景库之上）
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除场景") },
            text = { Text("确定删除「${target.name}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = if (dirUri != null) DocumentsContract.buildDocumentUriUsingTree(dirUri, target.docId) else null
                    if (uri != null && deleteSceneDocument(context, uri)) {
                        files = files.filterNot { it.docId == target.docId }
                        onToast("已删除：${target.name}")
                    } else {
                        onToast("删除失败：${target.name}")
                    }
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}
