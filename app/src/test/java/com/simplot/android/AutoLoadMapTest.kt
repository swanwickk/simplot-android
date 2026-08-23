package com.simplot.android

import com.simplot.android.render.MapDataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 针对地图自动加载、同目录查找、背景图解析与大小写容错的单元测试。
 * （不包含任何内置地图/Assets回退，严格测试用户场景目录查找）。
 */
class AutoLoadMapTest {

    @Test
    fun testPendingBackgroundNameParsing() {
        val parser = MapDataParser()

        // 1. 正常背景图
        parser.parse("""{"BackgroundFileName": "Ironbottom_Sound_Map.jpg", "BoundaryRect": {"Left":0,"Top":100,"Width":100,"Height":100}}""")
        assertEquals("Ironbottom_Sound_Map.jpg", parser.pendingBackgroundName)

        // 2. BackgroundFileName 为 "None"（MapMaker 缺省标记）
        parser.parse("""{"BackgroundFileName": "None", "BoundaryRect": {"Left":0,"Top":100,"Width":100,"Height":100}}""")
        assertNull("None 应被过滤为 null", parser.pendingBackgroundName)

        // 3. BackgroundFileName 为空白字符串
        parser.parse("""{"BackgroundFileName": "   ", "BoundaryRect": {"Left":0,"Top":100,"Width":100,"Height":100}}""")
        assertNull("空白字符串应被过滤为 null", parser.pendingBackgroundName)

        // 4. 无 BackgroundFileName 键
        parser.parse("""{"BoundaryRect": {"Left":0,"Top":100,"Width":100,"Height":100}}""")
        assertNull(parser.pendingBackgroundName)
    }

    @Test
    fun testGuadalcanalMapJsonParsing() {
        val parser = MapDataParser()
        val guadalcanalJson = """
        {
          "BackgroundFileName": "Ironbottom_Sound_Map.jpg",
          "BoundaryRect": {
            "Left": -250000,
            "Top": 150000,
            "Width": 480000,
            "Height": 291500
          }
        }
        """.trimIndent()

        parser.parse(guadalcanalJson)
        assertTrue(parser.hasBoundary)
        assertEquals(-250000L * 10, parser.mapWorldMinX)
        // Top 150000 - Height 291500 = -141500 -> * 10 = -1415000
        assertEquals((150000L - 291500L) * 10, parser.mapWorldMinY)
        assertEquals(480000L, parser.boundaryWidth)
        assertEquals(291500L, parser.boundaryHeight)
        assertEquals("Ironbottom_Sound_Map.jpg", parser.pendingBackgroundName)
    }

    @Test
    fun testIronBottomSoundJJWS1JsonParsing() {
        val parser = MapDataParser()
        val jjws1Json = """
        {
          "BackgroundFileName": "Ironbottom_Sound_Map.jpg",
          "BoundaryRect": {
            "Left": -250000,
            "Top": 150000,
            "Width": 480000,
            "Height": 291500
          },
          "Land Polygons": [
            { "Name": "Guadalcanal", "Path": [100, 200, 300, 200, 300, 400, 100, 400] },
            { "Name": "Florida", "Path": [500, 100, 700, 100, 600, 300] }
          ],
          "Misc Polygons": [
            { "Name": "Airfield", "Path": [1000, 1000, 1200, 1000, 1100, 1200] }
          ],
          "Misc Labels": [
            { "Name": "Henderson Field", "X": 1100, "Y": 900 }
          ],
          "Water Labels": [
            { "Name": "Iron Bottom Sound", "X": 500, "Y": 500 }
          ],
          "City Labels": [
            { "Name": "Honiara", "X": 300, "Y": 300 }
          ],
          "Depth Polygons": [
            { "Name": "Deep", "DepthLevelIndex": 4, "Path": [50, 50, 150, 50, 100, 150] }
          ],
          "Border Polys": [
            { "Name": "Province", "Path": [200, 200, 400, 200, 300, 400] }
          ],
          "Depth Labels": ["Depth1", "Depth2"]
        }
        """.trimIndent()

        parser.parse(jjws1Json)
        assertTrue(parser.hasBoundary)
        assertEquals("Ironbottom_Sound_Map.jpg", parser.pendingBackgroundName)
        assertEquals(2, parser.landPolys.size)
        assertEquals(1, parser.miscPolys.size)
        assertEquals(1, parser.labels.size)
        assertEquals("Henderson Field", parser.labels[0].first)
        assertEquals(1, parser.waterLabels.size)
        assertEquals("Iron Bottom Sound", parser.waterLabels[0].first)
        assertEquals(1, parser.cityLabels.size)
        assertEquals("Honiara", parser.cityLabels[0].first)
        assertEquals(1, parser.depthPolys.size)
        assertEquals(1, parser.borderPolys.size)
        assertEquals(listOf("Depth1", "Depth2"), parser.depthTexts)
    }

    /** 模拟同目录查找的解析决策链 */
    sealed class MapLoadResult {
        data class LoadedFromScenarioFolder(val mapName: String, val bgName: String?, val bgFound: Boolean) : MapLoadResult()
        data class NotFound(val mapName: String, val toastMessage: String) : MapLoadResult()
    }

    private fun resolveMap(
        mapName: String,
        scenarioFolderFiles: Map<String, String> // fileName -> content
    ): MapLoadResult {
        val lower = mapName.lowercase()

        val scenarioEntry = scenarioFolderFiles.entries.firstOrNull { it.key.equals(mapName, ignoreCase = true) }
        if (scenarioEntry != null) {
            val content = scenarioEntry.value
            val parser = MapDataParser()
            if (lower.endsWith(".json") || content.trimStart().startsWith("{")) {
                parser.parse(content)
                val bg = parser.pendingBackgroundName
                val bgFound = if (!bg.isNullOrBlank()) {
                    scenarioFolderFiles.keys.any { it.equals(bg, ignoreCase = true) }
                } else false
                return MapLoadResult.LoadedFromScenarioFolder(scenarioEntry.key, bg, bgFound)
            }
        }

        return MapLoadResult.NotFound(mapName, "未找到地图文件：$mapName，请点击顶部「地图」手动选择")
    }

    @Test
    fun testScenarioFolderPriorityWithSameFolderBackground() {
        val scenarioFiles = mapOf(
            "Guadalcanal_Map.json" to """{"BackgroundFileName": "Ironbottom_Sound_Map.jpg", "BoundaryRect": {"Left":0,"Top":100,"Width":100,"Height":100}}""",
            "Ironbottom_Sound_Map.jpg" to "IMAGE_BINARY_DATA"
        )

        val result = resolveMap("Guadalcanal_Map.json", scenarioFiles)
        assertTrue(result is MapLoadResult.LoadedFromScenarioFolder)
        val loaded = result as MapLoadResult.LoadedFromScenarioFolder
        assertEquals("Guadalcanal_Map.json", loaded.mapName)
        assertEquals("Ironbottom_Sound_Map.jpg", loaded.bgName)
        assertTrue(loaded.bgFound)
    }

    @Test
    fun testCaseInsensitiveMapAndBackgroundMatching() {
        val scenarioFiles = mapOf(
            "guadalcanal_map.json" to """{"BackgroundFileName": "ironbottom_sound_map.jpg", "BoundaryRect": {"Left":0,"Top":100,"Width":100,"Height":100}}""",
            "Ironbottom_Sound_Map.jpg" to "IMAGE"
        )

        val result = resolveMap("Guadalcanal_Map.json", scenarioFiles)
        assertTrue(result is MapLoadResult.LoadedFromScenarioFolder)
        val loaded = result as MapLoadResult.LoadedFromScenarioFolder
        assertEquals("guadalcanal_map.json", loaded.mapName)
        assertEquals("ironbottom_sound_map.jpg", loaded.bgName)
        assertTrue(loaded.bgFound)
    }

    @Test
    fun testNotFoundGracefulDegradationToastMessage() {
        val scenarioFiles = emptyMap<String, String>()

        val result = resolveMap("NonExistent_Map.json", scenarioFiles)
        assertTrue(result is MapLoadResult.NotFound)
        val notFound = result as MapLoadResult.NotFound
        assertEquals("未找到地图文件：NonExistent_Map.json，请点击顶部「地图」手动选择", notFound.toastMessage)
    }

    @Test
    fun testFileSiblingLookupCaseInsensitive() {
        val tempDir = File.createTempFile("simplot_test", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val scenarioFile = File(tempDir, "TestScenario.json")
            scenarioFile.writeText("{}")
            val mapFile = File(tempDir, "Ironbottom_Sound_Map.jpg")
            mapFile.writeText("sample")

            val targetName = "ironbottom_sound_map.jpg"
            val matched = tempDir.listFiles()?.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
            assertNotNull(matched)
            assertEquals("Ironbottom_Sound_Map.jpg", matched?.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
