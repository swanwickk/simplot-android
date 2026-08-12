package com.simplot.android

import com.simplot.android.ui.components.SceneFileInfo
import com.simplot.android.ui.components.isSceneFileName
import com.simplot.android.ui.components.sortSceneFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 场景库管理：纯逻辑 JVM 单测（文件名过滤 + 排序）。
 * SceneFileInfo 为纯数据（docId 字符串，不含 Uri），可在 JVM 直接构造。
 */
class SceneLibraryTest {

    @Test
    fun `isSceneFileName 识别 json 与 SpScn 后缀（大小写不敏感）`() {
        assertTrue(isSceneFileName("scenario.json"))
        assertTrue(isSceneFileName("SCENARIO.JSON"))
        assertTrue(isSceneFileName("Red.SpScn"))
        assertTrue(isSceneFileName("blue.spscn"))
        assertTrue(isSceneFileName("冰海巨兽.SpScn"))
    }

    @Test
    fun `isSceneFileName 排除非场景文件与后缀伪造`() {
        assertFalse(isSceneFileName("notes.txt"))
        assertFalse(isSceneFileName("map.json.bak"))
        assertFalse(isSceneFileName("unit.jsonx"))
        assertFalse(isSceneFileName("scenario"))
        assertFalse(isSceneFileName(""))
        assertFalse(isSceneFileName("json"))
        assertFalse(isSceneFileName(".json"))
    }

    @Test
    fun `sortSceneFiles 按文件名大小写不敏感排序`() {
        val files = listOf(
            SceneFileInfo("battle.B.SpScn", "doc3"),
            SceneFileInfo("Alpha.json", "doc1"),
            SceneFileInfo("bravo.json", "doc2"),
            SceneFileInfo("2nd.json", "doc4")
        )
        val sorted = sortSceneFiles(files)
        // "battle" vs "bravo"：第 1 位 'a' < 'r'，故 battle 在前
        assertEquals(
            listOf("2nd.json", "Alpha.json", "battle.B.SpScn", "bravo.json"),
            sorted.map { it.name }
        )
        // 排序不改变元素（docId 跟随原名）
        assertEquals("doc4", sorted.first().docId)
        assertEquals("doc2", sorted.last().docId)
    }

    @Test
    fun `sortSceneFiles 空表与单元素不变`() {
        assertEquals(emptyList<SceneFileInfo>(), sortSceneFiles(emptyList()))
        val single = listOf(SceneFileInfo("only.json", "doc1"))
        assertEquals(single, sortSceneFiles(single))
    }
}
