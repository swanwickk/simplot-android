package com.simplot.android

import com.simplot.android.render.MapDataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MapDataParser 纯 Kotlin 解析测试（架构重构 Phase 1：渲染层拆分后解析逻辑可单测）。
 *
 * 用真实 Iron Bottom Sound JJWS1.json 验证：BoundaryRect ×10、多边形、标注。
 */
class MapDataParserTest {

    /** 真实 JJWS1.json 的关键片段（BoundaryRect + Land Polygons + Misc Labels） */
    private val sampleJson = """
    {
      "BackgroundFileName": "Iron Bottom Sound Image.png",
      "BoundaryRect": { "Left": 115000, "Top": 145000, "Width": 100000, "Height": 120000 },
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

    @Test
    fun `boundary rect scaled by 10 into file coords`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertTrue(p.hasBoundary)
        assertEquals(115000L * 10, p.mapWorldMinX)
        // Top 145000 - Height 120000 = 25000 → ×10 = 250000
        assertEquals(25000L * 10, p.mapWorldMinY)
        assertEquals(100000L * 10, p.boundaryWidth * 10)
        assertEquals(120000L * 10, p.boundaryHeight * 10)
    }

    @Test
    fun `land polygons extracted with scaled coords`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertEquals(2, p.landPolys.size)
        // 第一个多边形：首点 (100,200) → (1000, 2000)
        val first = p.landPolys[0]
        assertEquals(1000L, first[0].first)
        assertEquals(2000L, first[0].second)
        assertEquals(4, first.size)
    }

    @Test
    fun `misc polygons and labels extracted`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertEquals(1, p.miscPolys.size)
        assertEquals(1000L * 10, p.miscPolys[0].first[0].first)
        assertEquals("Henderson Field", p.labels[0].first)
        assertEquals(900L * 10, p.labels[0].third)
    }

    @Test
    fun `water city country depth border extracted`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertEquals("Iron Bottom Sound", p.waterLabels[0].first)
        assertEquals("Honiara", p.cityLabels[0].first)
        assertEquals(1, p.depthPolys.size)
        assertEquals(4, p.depthPolys[0].second)   // DepthLevelIndex
        assertEquals(1, p.borderPolys.size)
        assertEquals(listOf("Depth1", "Depth2"), p.depthTexts)
        assertEquals("Iron Bottom Sound Image.png", p.pendingBackgroundName)
    }

    @Test
    fun `invalid json does not throw`() {
        val p = MapDataParser()
        p.parse("not json at all {{{")
        assertTrue(!p.hasBoundary)
        assertTrue(p.landPolys.isEmpty())
    }
}
