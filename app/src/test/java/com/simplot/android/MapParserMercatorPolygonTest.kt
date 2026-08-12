package com.simplot.android

import com.simplot.android.render.MapDataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G49：MercatorPolygon 矢量地图变体键解析测试。
 *
 * 官方矢量地图发布格式（反汇编字符串 + 伪代码_剩余模块.md §17）：
 * { "Scale", "Width", "Height",
 *   "Countries": [{Name, SimPlotX, SimPlotY}],
 *   "Cities":    [{Name, SimPlotX, SimPlotY, Position("Above Right")}],
 *   "Waters":    [{Name, SimPlotX, SimPlotY, IsMajor}],
 *   "Land":      [{Name, Path}], "Borders": [{Name, Path}],
 *   "Depths":    [{Id, Depth4, Path}], "Depth Labels": [...] }
 * 坐标体系与 BoundaryRect 一致（海里×10000 → ×10 转存档坐标）。
 */
class MapParserMercatorPolygonTest {

    private val sampleJson = """
    {
      "Scale": 3.071,
      "Width": 100000,
      "Height": 120000,
      "Countries": [
        { "Name": "Solomon Islands", "SimPlotX": 20000, "SimPlotY": 30000 }
      ],
      "Cities": [
        { "Name": "Honiara", "SimPlotX": 40000, "SimPlotY": 50000, "Position": "Above Right" },
        { "Name": "Tulagi", "SimPlotX": 60000, "SimPlotY": 70000 }
      ],
      "Waters": [
        { "Name": "Pacific Ocean", "SimPlotX": 10000, "SimPlotY": 110000, "IsMajor": true },
        { "Name": "Iron Bottom Sound", "SimPlotX": 50000, "SimPlotY": 60000, "IsMajor": false }
      ],
      "Land": [
        { "Name": "Guadalcanal", "Path": [100, 200, 300, 200, 300, 400, 100, 400] },
        { "Name": "Florida", "Path": "500,100 700,100 600,300" }
      ],
      "Borders": [
        { "Name": "Province", "Path": [200, 200, 400, 200, 300, 400] }
      ],
      "Depths": [
        { "Id": 0, "Depth4": 4, "Path": [50, 50, 150, 50, 100, 150] },
        { "Id": 1, "Depth4": 1, "Path": "250,250 350,250 300,350" }
      ],
      "Depth Labels": ["Deep Basin", "Shallow"]
    }
    """.trimIndent()

    @Test
    fun `width height derive boundary when boundary rect missing`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertTrue(p.hasBoundary)
        // CalcBoundary(width,height)：Left=0, Top=Height, Width=Width, Height=Height → ×10 存档坐标
        assertEquals(0L, p.boundaryLeft)
        assertEquals(120000L, p.boundaryTop)
        assertEquals(100000L * 10, p.boundaryWidth * 10)
        assertEquals(120000L * 10, p.boundaryHeight * 10)
        assertEquals(0L, p.mapWorldMinX)
        assertEquals(0L, p.mapWorldMinY)
        // Scale 原样记录（矢量坐标已含换算）
        assertEquals(3.071, p.mapScale, 0.0001)
    }

    @Test
    fun `countries and cities parsed with simplot coords scaled by 10`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertEquals(1, p.countryLabels.size)
        assertEquals("Solomon Islands", p.countryLabels[0].first)
        assertEquals(20000L * 10, p.countryLabels[0].second)
        assertEquals(30000L * 10, p.countryLabels[0].third)

        assertEquals(2, p.cityLabels.size)
        assertEquals("Honiara", p.cityLabels[0].first)
        assertEquals(40000L * 10, p.cityLabels[0].second)
        assertEquals(50000L * 10, p.cityLabels[0].third)
        // Position 锚点与 cityLabels 按索引对齐；缺省为空串
        assertEquals("Above Right", p.cityPositions[0])
        assertEquals("", p.cityPositions[1])
    }

    @Test
    fun `waters parsed with is major flag aligned by index`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertEquals(2, p.waterLabels.size)
        assertEquals("Pacific Ocean", p.waterLabels[0].first)
        assertEquals(110000L * 10, p.waterLabels[0].third)
        assertTrue(p.waterIsMajor[0])
        assertFalse(p.waterIsMajor[1])
    }

    @Test
    fun `land path array and string both parsed`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        // 数组 Path + 字符串 Path 各 1 个陆地多边形
        assertEquals(2, p.landPolys.size)
        assertEquals(4, p.landPolys[0].size)
        assertEquals(100L * 10, p.landPolys[0][0].first)
        assertEquals(200L * 10, p.landPolys[0][0].second)
        // 字符串 "500,100 700,100 600,300" → 3 点
        assertEquals(3, p.landPolys[1].size)
        assertEquals(500L * 10, p.landPolys[1][0].first)
        assertEquals(100L * 10, p.landPolys[1][0].second)
        assertEquals(600L * 10, p.landPolys[1][2].first)
    }

    @Test
    fun `borders and depths parsed with depth4 level`() {
        val p = MapDataParser()
        p.parse(sampleJson)
        assertEquals(1, p.borderPolys.size)
        assertEquals(3, p.borderPolys[0].size)

        assertEquals(2, p.depthPolys.size)
        assertEquals(4, p.depthPolys[0].second)          // Depth4: 4
        assertEquals(1, p.depthPolys[1].second)          // Depth4: 1
        assertEquals(3, p.depthPolys[1].first.size)      // 字符串 Path
        assertEquals(listOf("Deep Basin", "Shallow"), p.depthTexts)
    }

    @Test
    fun `depth4 boolean true maps to level 4`() {
        val json = """
        { "Width": 100, "Height": 100,
          "Depths": [ { "Id": 0, "Depth4": true, "Path": [0, 0, 10, 0, 5, 10] } ] }
        """.trimIndent()
        val p = MapDataParser()
        p.parse(json)
        assertEquals(1, p.depthPolys.size)
        assertEquals(4, p.depthPolys[0].second)
    }

    @Test
    fun `boundary rect wins over width height when both present`() {
        val json = """
        { "Width": 100, "Height": 100,
          "BoundaryRect": { "Left": 115000, "Top": 145000, "Width": 100000, "Height": 120000 } }
        """.trimIndent()
        val p = MapDataParser()
        p.parse(json)
        assertTrue(p.hasBoundary)
        assertEquals(115000L * 10, p.mapWorldMinX)
        assertEquals(25000L * 10, p.mapWorldMinY)
    }

    @Test
    fun `missing variant keys parse to empty without crash`() {
        val p = MapDataParser()
        p.parse("""{ "Scale": 1.0 }""")
        assertFalse(p.hasBoundary)
        assertTrue(p.landPolys.isEmpty())
        assertTrue(p.cityLabels.isEmpty())
        assertTrue(p.depthPolys.isEmpty())
    }

    @Test
    fun `old newmap format still parses when variant keys absent`() {
        // 缺变体键的老文件：行为回退到 NewMap 键，不崩（G49 兼容要求）
        val old = """
        {
          "BoundaryRect": { "Left": 115000, "Top": 145000, "Width": 100000, "Height": 120000 },
          "Land Polygons": [ { "Name": "Guadalcanal", "Path": [100, 200, 300, 200, 300, 400, 100, 400] } ],
          "City Labels": [ { "Name": "Honiara", "X": 300, "Y": 300 } ],
          "Water Labels": [ { "Name": "Iron Bottom Sound", "X": 500, "Y": 500 } ],
          "Depth Polygons": [ { "Name": "Deep", "DepthLevelIndex": 4, "Path": [50, 50, 150, 50, 100, 150] } ]
        }
        """.trimIndent()
        val p = MapDataParser()
        p.parse(old)
        assertTrue(p.hasBoundary)
        assertEquals(1, p.landPolys.size)
        assertEquals(1000L, p.landPolys[0][0].first)
        assertEquals("Honiara", p.cityLabels[0].first)
        assertEquals(1, p.waterLabels.size)
        assertEquals(4, p.depthPolys[0].second)
        assertTrue(p.cityPositions.isEmpty())   // 旧格式无 Position
        assertTrue(p.waterIsMajor.isEmpty())
    }

    @Test
    fun `invalid width or height does not set boundary`() {
        val p = MapDataParser()
        p.parse("""{ "Scale": 1.0, "Width": 0, "Height": -5 }""")
        assertFalse(p.hasBoundary)
    }

    @Test
    fun `land path string with mixed separators parses`() {
        // MercatorPolygon Path 字符串兼容逗号/分号/空白混合分隔（pointsFromString）
        val json = """
        { "Width": 1000, "Height": 1000,
          "Land": [ { "Name": "Mixed", "Path": "100,200;300,200 300,400;100,400" } ] }
        """.trimIndent()
        val p = MapDataParser()
        p.parse(json)
        assertEquals(1, p.landPolys.size)
        assertEquals(4, p.landPolys[0].size)
        assertEquals(100L * 10, p.landPolys[0][0].first)
        assertEquals(300L * 10, p.landPolys[0][2].first)
    }

    @Test
    fun `depths fall back to depthlevelindex when depth4 absent`() {
        // 旧格式 DepthLevelIndex 在 MercatorPolygon Depths 键下同样可用（回退路径）
        val json = """
        { "Width": 100, "Height": 100,
          "Depths": [ { "Id": 0, "DepthLevelIndex": 2, "Path": [0, 0, 10, 0, 5, 10] } ] }
        """.trimIndent()
        val p = MapDataParser()
        p.parse(json)
        assertEquals(1, p.depthPolys.size)
        assertEquals(2, p.depthPolys[0].second)
    }

    @Test
    fun `numeric string coords tolerated in countries and cities`() {
        // numOrNull 容错：SimPlotX/Y 以字符串数字给出时同样 ×10 换算
        val json = """
        { "Width": 1000, "Height": 1000,
          "Countries": [ { "Name": "Str", "SimPlotX": "20000", "SimPlotY": "30000" } ],
          "Cities": [ { "Name": "Town", "SimPlotX": "40000", "SimPlotY": "50000" } ] }
        """.trimIndent()
        val p = MapDataParser()
        p.parse(json)
        assertEquals(20000L * 10, p.countryLabels[0].second)
        assertEquals(30000L * 10, p.countryLabels[0].third)
        assertEquals(40000L * 10, p.cityLabels[0].second)
        assertEquals(50000L * 10, p.cityLabels[0].third)
    }
}
