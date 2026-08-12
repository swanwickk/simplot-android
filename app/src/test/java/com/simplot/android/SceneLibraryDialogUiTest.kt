package com.simplot.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.simplot.android.ui.components.SceneLibraryDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P3 场景库对话框 —— Robolectric + Compose UI 测试（无模拟器，JVM 上跑真 UI 语义树）。
 *
 * 覆盖：空态提示 / 场景列表渲染与排序 / 点击行上抛打开 Uri / 删除确认弹窗（取消与失败路径）。
 * 删除成功路径依赖真实 SAF Provider 的持久授权语义，由 SceneLibraryDataTest 的函数级测试覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SceneLibraryDialogUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 构造一棵真实形态的 SAF tree URI：content://…/tree/primary%3AScenarios */
    private fun treeUri(): Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AScenarios")

    private fun childrenUri(dir: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))

    private fun documentUri(dir: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(dir, docId)

    /**
     * 假 SAF Provider：query() 对 childrenUri 返回场景列表游标，其余返回 null。
     * deleteThrows=true 时 call("android:deleteDocument") 抛 SecurityException —— SDK 35 的
     * DocumentsContract.deleteDocument 语义：call() 不抛异常即视为删除成功（结果 bundle 被忽略），
     * Provider 必须抛异常表示删除失败。因此"删除失败"路径必须用抛异常的 Provider 模拟。
     * 注意：childrenUri 必须由【原始 tree uri】构造后传入；若用收到的 uri 再拼会得到错误的嵌套路径。
     */
    private class FakeDocumentsProvider(
        private val childrenUri: Uri,
        private val childrenCursor: Cursor?,
        private val deleteThrows: Boolean = false
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?
        ): Cursor? = if (uri == childrenUri) childrenCursor else null
        override fun getType(uri: Uri): String? = DocumentsContract.Document.MIME_TYPE_DIR
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (deleteThrows && method == "android:deleteDocument") {
                throw SecurityException("provider denies delete")
            }
            return null
        }
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<String>?
        ): Int = 0
    }

    /** 注册假 Provider，使 contentResolver.query 落到我们的游标实现（无需真实 SAF）。
     *  注意：必须 attachInfo 注入 authority，否则 DocumentsContract.deleteDocument 的 4 参 call
     *  会被 Transport.validateIncomingAuthority 以 SecurityException 拒绝（Robolectric 4.14 实测）。 */
    private fun stubChildren(rows: List<Array<Any?>>, deleteThrows: Boolean = false) {
        val dir = treeUri()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            dir, DocumentsContract.getTreeDocumentId(dir)
        )
        val cursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
        )
        rows.forEach { cursor.addRow(it) }
        val fake = FakeDocumentsProvider(childrenUri, cursor, deleteThrows)
        val info = android.content.pm.ProviderInfo().apply { authority = "com.android.externalstorage.documents" }
        fake.attachInfo(context, info)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "com.android.externalstorage.documents",
            fake
        )
    }

    // ============ 空态 ============

    @Test
    fun `空态 - 未选择目录时显示引导文案`() {
        composeRule.setContent {
            SceneLibraryDialog(
                dirUri = null,
                onChangeDir = {},
                onOpen = {},
                onToast = {},
                onDismiss = {}
            )
        }
        composeRule.onNodeWithText("场景库").assertIsDisplayed()
        composeRule.onNodeWithText("未选择目录，点击「更换目录」").assertIsDisplayed()
        composeRule.onNodeWithText("更换目录").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").assertIsDisplayed()
    }

    @Test
    fun `空态 - 目录非空但无场景文件时显示空目录提示`() {
        stubChildren(emptyList())
        composeRule.setContent {
            SceneLibraryDialog(
                dirUri = treeUri(),
                onChangeDir = {},
                onOpen = {},
                onToast = {},
                onDismiss = {}
            )
        }
        composeRule.onNodeWithText("目录为空或未选择目录").assertIsDisplayed()
    }

    // ============ 列表渲染与打开 ============

    @Test
    fun `列表 - 渲染场景文件并按名称排序`() {
        stubChildren(
            listOf(
                arrayOf("primary:Scenarios/bravo.json", "bravo.json", "application/json"),
                arrayOf("primary:Scenarios/Alpha.SpScn", "Alpha.SpScn", "application/octet-stream"),
                // 目录与非场景文件应被过滤
                arrayOf("primary:Scenarios/subdir", "subdir", DocumentsContract.Document.MIME_TYPE_DIR),
                arrayOf("primary:Scenarios/notes.txt", "notes.txt", "text/plain"),
                arrayOf("primary:Scenarios/2nd.json", "2nd.json", "application/json")
            )
        )
        composeRule.setContent {
            SceneLibraryDialog(
                dirUri = treeUri(),
                onChangeDir = {},
                onOpen = {},
                onToast = {},
                onDismiss = {}
            )
        }
        // 排序：2nd.json < Alpha.SpScn < bravo.json（大小写不敏感）
        composeRule.onNodeWithText("2nd.json").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha.SpScn").assertIsDisplayed()
        composeRule.onNodeWithText("bravo.json").assertIsDisplayed()
        // 目录与非场景文件不出现在列表
        composeRule.onNodeWithText("subdir").assertDoesNotExist()
        composeRule.onNodeWithText("notes.txt").assertDoesNotExist()
    }

    @Test
    fun `列表 - 点击文件名上抛该文件的 tree document Uri`() {
        val docId = "primary:Scenarios/bravo.json"
        stubChildren(listOf(arrayOf(docId, "bravo.json", "application/json")))
        var openedUri: Uri? = null
        composeRule.setContent {
            SceneLibraryDialog(
                dirUri = treeUri(),
                onChangeDir = {},
                onOpen = { openedUri = it },
                onToast = {},
                onDismiss = {}
            )
        }
        composeRule.onNodeWithText("bravo.json").performClick()
        composeRule.waitForIdle()
        assertEquals(documentUri(treeUri(), docId), openedUri)
        assertTrue(openedUri.toString().startsWith("content://com.android.externalstorage.documents/tree/"))
    }

    // ============ 删除确认流 ============

    @Test
    fun `删除 - 取消不触发删除且文件保留`() {
        stubChildren(listOf(arrayOf("primary:Scenarios/del.json", "del.json", "application/json")))
        var toastMsg: String? = null
        composeRule.setContent {
            SceneLibraryDialog(
                dirUri = treeUri(),
                onChangeDir = {},
                onOpen = {},
                onToast = { toastMsg = it },
                onDismiss = {}
            )
        }
        // 打开删除确认（行内唯一「删除」按钮）
        composeRule.onAllNodesWithText("删除").onFirst().performClick()
        composeRule.onNodeWithText("删除场景").assertIsDisplayed()
        composeRule.onNodeWithText("确定删除「del.json」？此操作不可恢复。").assertIsDisplayed()
        // 取消
        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()
        assertNull(toastMsg)
        composeRule.onNodeWithText("del.json").assertIsDisplayed()
        composeRule.onNodeWithText("删除场景").assertDoesNotExist()
    }

    @Test
    fun `删除 - 确认但 Provider 删除失败时 toast 删除失败且文件保留`() {
        stubChildren(listOf(arrayOf("primary:Scenarios/del.json", "del.json", "application/json")), deleteThrows = true)
        var toastMsg: String? = null
        composeRule.setContent {
            SceneLibraryDialog(
                dirUri = treeUri(),
                onChangeDir = {},
                onOpen = {},
                onToast = { toastMsg = it },
                onDismiss = {}
            )
        }
        // 确认删除（确认按钮是第二个「删除」；假 Provider call() 抛 SecurityException → deleteSceneDocument 失败 → 失败路径）
        composeRule.onAllNodesWithText("删除").onFirst().performClick()
        composeRule.onAllNodesWithText("删除").onLast().performClick()
        composeRule.waitForIdle()
        assertEquals("删除失败：del.json", toastMsg)
        composeRule.onNodeWithText("del.json").assertIsDisplayed()
    }
}
