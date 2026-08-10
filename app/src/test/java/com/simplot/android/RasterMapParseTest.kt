package com.simplot.android

import com.simplot.android.render.MapDataParser
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光栅地图解析测试（R5：桌面版 MercatorRaster .map/.txt 格式）。
 */
class RasterMapParseTest {

    private val sampleMap = """
        MAP = pacific.png
        SCALE = 3.071
        CITY = Honolulu|100|200
        CITY = Pearl Harbor|150|220
        COUNTRY = USA|10|10
    """.trimIndent()

    @Test
    fun `parses map name and scale`() {
        val p = MapDataParser()
        val mapName = StringBuilder()
        val ok = p.parseRasterMap(sampleMap, mapName)
        assertTrue(ok)
        assertEquals("pacific.png", mapName.toString())
    }

    @Test
    fun `parses city and country labels`() {
        val p = MapDataParser()
        p.parseRasterMap(sampleMap)
        assertEquals(2, p.cityLabels.size)
        assertEquals("Honolulu", p.cityLabels[0].first)
        assertEquals("USA", p.countryLabels[0].first)
        // 坐标按 SCALE 换算（R6/D4：桌面 SimPlotX ← 像素 ÷ Scale，再 ×10 转存档坐标，四舍五入）
        val expected = (100 / 3.071 * 10).roundToLong()
        assertEquals(expected, p.cityLabels[0].second)
    }

    @Test
    fun `missing scale returns false`() {
        val p = MapDataParser()
        assertFalse(p.parseRasterMap("MAP = x.png"))
    }

    @Test
    fun `empty text is safe`() {
        val p = MapDataParser()
        assertFalse(p.parseRasterMap(""))
    }
}
