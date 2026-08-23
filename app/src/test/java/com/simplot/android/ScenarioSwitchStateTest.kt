package com.simplot.android

import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.render.CameraMath
import com.simplot.android.render.MapRenderer
import com.simplot.android.ui.newScenarioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对读取新文件与场景切换时状态刷新、地图缓存清理与相机视野自适应的单元测试。
 *
 * 覆盖：
 * 1. MapRenderer.clearMap() 彻底重置地图边界、多边形、标注、背景图与比例尺
 * 2. 场景切换时 CameraMath.fitBounds 根据新场景单位坐标正确重算视口中心与缩放
 * 3. 无单位但有地图边界的场景回退至地图边界自适应视野
 * 4. 新旧场景切换时的数据与状态隔离
 */
class ScenarioSwitchStateTest {

    private val sampleMapJson = """
    {
      "BackgroundFileName": "Iron_Bottom_Sound.png",
      "BoundaryRect": { "Left": 100000, "Top": 200000, "Width": 400000, "Height": 300000 },
      "Land Polygons": [
        { "Name": "Guadalcanal", "Path": [100, 200, 300, 200, 300, 400, 100, 400] }
      ],
      "Misc Labels": [
        { "Name": "Henderson Field", "X": 1100, "Y": 900 }
      ],
      "Water Labels": [
        { "Name": "Iron Bottom Sound", "X": 500, "Y": 500 }
      ]
    }
    """.trimIndent()

    @Test
    fun `map renderer clearMap completely resets all map data and background`() {
        val renderer = MapRenderer()
        renderer.parseMapConfigJson(sampleMapJson)

        assertTrue("解析后应有边界", renderer.hasBoundary)
        assertEquals("Iron_Bottom_Sound.png", renderer.pendingBackgroundName)
        assertEquals(100000L * 10, renderer.mapWorldMinX)
        assertEquals(400000L, renderer.boundaryWidth)
        assertEquals(300000L, renderer.boundaryHeight)
        assertFalse("解析后 landPolys 不应为空", renderer.parser.landPolys.isEmpty())

        // 执行清理（模拟 applyLoaded / createNewScenario 开头）
        renderer.clearMap()

        assertFalse("clearMap 后 hasBoundary 应为 false", renderer.hasBoundary)
        assertNull("clearMap 后 pendingBackgroundName 应为 null", renderer.pendingBackgroundName)
        assertNull("clearMap 后 bitmap 应为 null", renderer.bitmap)
        assertEquals(0L, renderer.mapWorldMinX)
        assertEquals(0L, renderer.mapWorldMinY)
        assertEquals(0L, renderer.boundaryWidth)
        assertEquals(0L, renderer.boundaryHeight)
        assertEquals(0.0, renderer.mapScaleMetersPerPx, 0.0001)
        assertTrue("clearMap 后 landPolys 应为空", renderer.parser.landPolys.isEmpty())
        assertTrue("clearMap 后 labels 应为空", renderer.parser.labels.isEmpty())
        assertTrue("clearMap 后 waterLabels 应为空", renderer.parser.waterLabels.isEmpty())
    }

    @Test
    fun `camera fitBounds adapts to new scenario unit coordinates on scenario switch`() {
        val canvasW = 1000
        val canvasH = 800
        val minZoom = 0.00001f
        val maxZoom = 0.05f

        // 场景 A：位于正坐标区间 (100000..300000, 200000..400000)
        val scenarioAUnits = listOf(
            Unit().apply { x = 100000L; y = 200000L },
            Unit().apply { x = 300000L; y = 400000L }
        )
        val xsA = scenarioAUnits.map { it.x }
        val ysA = scenarioAUnits.map { it.y }
        val (zoomA, cxA, cyA) = CameraMath.fitBounds(
            xsA.min(), xsA.max(), ysA.min(), ysA.max(),
            canvasW, canvasH, minZoom, maxZoom
        )
        assertEquals(200000L, cxA)
        assertEquals(300000L, cyA)

        // 场景 B：位于负坐标区间 (-800000..-400000, -600000..-200000)
        val scenarioBUnits = listOf(
            Unit().apply { x = -800000L; y = -600000L },
            Unit().apply { x = -400000L; y = -200000L }
        )
        val xsB = scenarioBUnits.map { it.x }
        val ysB = scenarioBUnits.map { it.y }
        val (zoomB, cxB, cyB) = CameraMath.fitBounds(
            xsB.min(), xsB.max(), ysB.min(), ysB.max(),
            canvasW, canvasH, minZoom, maxZoom
        )
        assertEquals(-600000L, cxB)
        assertEquals(-400000L, cyB)

        // 视野中心与缩放必须跟随新场景重置，不再停留在场景 A 的坐标
        assertTrue("场景 A 与场景 B 视野中心 X 应不同", cxA != cxB)
        assertTrue("场景 A 与场景 B 视野中心 Y 应不同", cyA != cyB)
    }

    @Test
    fun `camera fitBounds falls back to map boundary when scenario has no units`() {
        val canvasW = 1000
        val canvasH = 800
        val minZoom = 0.00001f
        val maxZoom = 0.05f

        val renderer = MapRenderer()
        renderer.parseMapConfigJson(sampleMapJson)

        assertTrue(renderer.hasBoundary)
        val minX = renderer.boundaryLeft
        val maxX = renderer.boundaryLeft + renderer.boundaryWidth
        val minY = renderer.boundaryTop
        val maxY = renderer.boundaryTop + renderer.boundaryHeight

        val (zoom, cx, cy) = CameraMath.fitBounds(
            minX, maxX, minY, maxY,
            canvasW, canvasH, minZoom, maxZoom
        )

        assertEquals((100000L + 500000L) / 2, cx)
        assertEquals((200000L + 500000L) / 2, cy)
        assertTrue(zoom in minZoom..maxZoom)
    }

    @Test
    fun `new scenario creation ensures fresh state isolation without inheriting old units`() {
        // 旧场景
        val oldFile = ScenarioFile().apply {
            scenario = Scenario(scenarioName = "旧战局", mapFileName = "OldMap.json")
            units = mutableListOf(Unit().apply { idNum = "S001"; name = "巡洋舰" })
        }
        assertEquals(1, oldFile.units.size)

        // 创建新场景
        val newFile = newScenarioFile("新战局", "2026-08-18 10:00:00", null)
        assertEquals("新战局", newFile.scenario.scenarioName)
        assertTrue("新场景单位列表必须为空", newFile.units.isEmpty())
        assertTrue("新场景编队必须为空", newFile.formations.isEmpty())
        assertTrue("新场景标注必须为空", newFile.overlays.isEmpty())
        assertNull("新场景无地图时 mapFileName 为 null", newFile.scenario.mapFileName)
        assertEquals(0, newFile.scenario.typeOfMap)
    }
}
