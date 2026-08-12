package com.simplot.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.simplot.android.ui.components.SceneFileInfo
import com.simplot.android.ui.components.deleteSceneDocument
import com.simplot.android.ui.components.querySceneFiles
import com.simplot.android.ui.components.queryTreeDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P3 场景库 SAF 交互函数 —— Robolectric 函数级测试（注册假 Provider 注入游标）。
 *
 * 覆盖：querySceneFiles 过滤+排序、queryTreeDisplayName 显示名、deleteSceneDocument 成功/失败路径。
 * 这些函数依赖 android.content 的 DocumentsContract / ContentResolver，无法用纯 JVM 单测，必须 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SceneLibraryDataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun treeUri(): Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AScenarios")

    /**
     * 假 SAF Provider：query() 对 childrenUri 返回场景列表游标、对 documentUri 返回显示名游标，
     * delete() 恒返回 1（模拟删除成功）。注册方式：ShadowContentResolver.registerProviderInternal（静态）。
     * 注意：childrenUri / documentUri 必须由【原始 tree uri】构造后传入，不能拿收到的 uri 再拼。
     */
    private class FakeDocumentsProvider(
        private val childrenUri: Uri?,
        private val documentUri: Uri?,
        private val childrenCursor: Cursor?,
        private val displayNameCursor: Cursor?,
        private val deleteResult: Int = 1
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?
        ): Cursor? = when (uri) {
            childrenUri -> childrenCursor
            documentUri -> displayNameCursor
            else -> null
        }
        override fun getType(uri: Uri): String? = DocumentsContract.Document.MIME_TYPE_DIR
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = deleteResult

        // DocumentsContract.deleteDocument（API 26+）内部走 ContentProviderClient.call(METHOD_DELETE_DOCUMENT)，
        // 不会调用本类 delete()。SDK 35 实测语义（Robolectric android-all 字节码确认）：call() 不抛异常即视为
        // 删除成功（返回 bundle 被框架丢弃），Provider 以抛异常表示删除失败。
        // 注：METHOD_DELETE_DOCUMENT 是 @SystemApi 隐藏常量（SDK stub 无此符号），直接用字面量 "android:deleteDocument"；
        //     call() 的 method 参数在 SDK 中标注 @NonNull，Kotlin 覆写必须用非空类型。
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            if (method == "android:deleteDocument") {
                Bundle() // 不抛异常 → 删除成功
            } else {
                null
            }

        override fun update(
            uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<String>?
        ): Int = 1
    }

    private fun treeChildrenUri(): Uri {
        val dir = treeUri()
        return DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))
    }

    private fun treeDocumentUri(): Uri {
        val dir = treeUri()
        return DocumentsContract.buildDocumentUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))
    }

    private fun registerProvider(provider: ContentProvider) {
        // 真实框架：provider 由 manifest 声明 authority、实例化后 attachInfo 注入。
        // registerProviderInternal 只进 provider 表不设置 mAuthority；DocumentsContract.deleteDocument
        // 走 4 参 ContentResolver.call → Transport 的 validateIncomingAuthority 校验，mAuthority=null 会抛
        // SecurityException。因此必须先 attachInfo 注入 authority（Robolectric 4.14 行为，已实测定位）。
        val info = android.content.pm.ProviderInfo().apply { authority = "com.android.externalstorage.documents" }
        provider.attachInfo(context, info)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "com.android.externalstorage.documents", provider
        )
    }

    private fun sceneCursor(rows: List<Array<Any?>>): MatrixCursor =
        MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
        ).apply { rows.forEach { addRow(it) } }

    // ============ querySceneFiles ============

    @Test
    fun `querySceneFiles - 目录为空返回空表`() {
        assertEquals(emptyList<SceneFileInfo>(), querySceneFiles(context, null))
    }

    @Test
    fun `querySceneFiles - 过滤目录与非场景文件并按名排序`() {
        val dir = treeUri()
        registerProvider(
            FakeDocumentsProvider(
                childrenUri = treeChildrenUri(),
                documentUri = treeDocumentUri(),
                childrenCursor = sceneCursor(
                    listOf(
                        arrayOf("primary:Scenarios/bravo.json", "bravo.json", "application/json"),
                        arrayOf("primary:Scenarios/Alpha.SpScn", "Alpha.SpScn", "application/octet-stream"),
                        arrayOf("primary:Scenarios/subdir", "subdir", DocumentsContract.Document.MIME_TYPE_DIR),
                        arrayOf("primary:Scenarios/notes.txt", "notes.txt", "text/plain"),
                        arrayOf("primary:Scenarios/2nd.json", "2nd.json", "application/json"),
                        arrayOf("primary:Scenarios/upper.JSON", "upper.JSON", "application/json")
                    )
                ),
                displayNameCursor = null
            )
        )
        val files = querySceneFiles(context, dir)
        assertEquals(
            listOf("2nd.json", "Alpha.SpScn", "bravo.json", "upper.JSON"),
            files.map { it.name }
        )
        assertEquals("primary:Scenarios/bravo.json", files[2].docId)
    }

    @Test
    fun `querySceneFiles - 缺列名的行安全跳过`() {
        val dir = treeUri()
        registerProvider(
            FakeDocumentsProvider(
                childrenUri = treeChildrenUri(),
                documentUri = treeDocumentUri(),
                childrenCursor = sceneCursor(
                    listOf(
                        arrayOf("primary:Scenarios/ok.json", "ok.json", "application/json"),
                        arrayOf("primary:Scenarios/nullname", null, "application/json")
                    )
                ),
                displayNameCursor = null
            )
        )
        val files = querySceneFiles(context, dir)
        assertEquals(listOf("ok.json"), files.map { it.name })
    }

    @Test
    fun `querySceneFiles - Provider 查询异常时向上抛出由调用方处理`() {
        val dir = treeUri()
        // 注册一个 query 必抛异常的假 Provider，验证 querySceneFiles 不吞异常、由调用方（对话框 LaunchedEffect）catch
        registerProvider(
            object : ContentProvider() {
                override fun onCreate(): Boolean = true
                override fun query(
                    uri: Uri, projection: Array<String>?, selection: String?,
                    selectionArgs: Array<String>?, sortOrder: String?
                ): Cursor? = throw SecurityException("SAF provider unavailable")
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
                override fun update(
                    uri: Uri, values: ContentValues?, selection: String?,
                    selectionArgs: Array<String>?
                ): Int = 0
            }
        )
        org.junit.Assert.assertThrows(SecurityException::class.java) {
            querySceneFiles(context, dir)
        }
    }

    // ============ queryTreeDisplayName ============

    @Test
    fun `queryTreeDisplayName - 目录为 null 返回 null`() {
        assertNull(queryTreeDisplayName(context, null))
    }

    @Test
    fun `queryTreeDisplayName - 返回根文档显示名`() {
        val dir = treeUri()
        registerProvider(
            FakeDocumentsProvider(
                childrenUri = treeChildrenUri(),
                documentUri = treeDocumentUri(),
                childrenCursor = null,
                displayNameCursor = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    .apply { addRow(arrayOf("Scenarios")) }
            )
        )
        assertEquals("Scenarios", queryTreeDisplayName(context, dir))
    }

    @Test
    fun `queryTreeDisplayName - Provider 异常时返回 null（调用方回退）`() {
        val dir = Uri.parse("content://no.such.provider/tree/primary%3AScenarios")
        assertNull(queryTreeDisplayName(context, dir))
    }

    // ============ deleteSceneDocument ============

    @Test
    fun `deleteSceneDocument - Provider 支持删除时返回 true`() {
        val dir = treeUri()
        val docUri = DocumentsContract.buildDocumentUriUsingTree(dir, "primary:Scenarios/del.json")
        registerProvider(
            FakeDocumentsProvider(
                childrenUri = treeChildrenUri(),
                documentUri = treeDocumentUri(),
                childrenCursor = null,
                displayNameCursor = null,
                deleteResult = 1
            )
        )
        assertTrue(deleteSceneDocument(context, docUri))
    }

    @Test
    fun `deleteSceneDocument - 无 Provider 或删除失败时返回 false 不崩溃`() {
        val dir = treeUri()
        val docUri = DocumentsContract.buildDocumentUriUsingTree(dir, "primary:Scenarios/del.json")
        assertFalse(deleteSceneDocument(context, docUri))
    }
}
