package com.simplot.android

import com.simplot.android.render.MapDataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * 光栅地图 .txt 解析与中心锚定换算测试（桌面版 MercatorRaster 格式）。
 * 覆盖「第三次所罗门海战.txt」真实桌面样例：MAP=第三次所罗门海战.jpg / SCALE=20.3。
 */
class RasterMapParseTest {

    private val sampleRaster = "MAP=第三次所罗门海战.jpg\r\nSCALE=20.3"

    @Test
    fun `raster map parses map name and scale`() {
        val p = MapDataParser()
        val mapName = StringBuilder()
        val ok = p.parseRasterMap(sampleRaster, mapName)
        assertTrue(ok)
        assertEquals("第三次所罗门海战.jpg", mapName.toString())
        // SCALE（像素/海里）记录到 mapScale 供底图定位换算
        assertEquals(20.3, p.mapScale, 0.001)
    }

    @Test
    fun `raster map with city country parses labels and centers to origin`() {
        val text = """
            MAP=TestMap.jpg
            SCALE=3.071
            CITY=Honiara|100|200
            COUNTRY=Solomon|50|60
        """.trimIndent()
        val p = MapDataParser()
        val mapName = StringBuilder()
        val ok = p.parseRasterMap(text, mapName)
        assertTrue(ok)
        assertEquals("TestMap.jpg", mapName.toString())
        assertEquals(1, p.rasterCityPixels.size)
        assertEquals(1, p.rasterCountryPixels.size)

        // 底图加载后中心化：图宽 1000 x 800
        p.applyRasterCenter(1000, 800)
        assertEquals(1, p.cityLabels.size)
        assertEquals("Honiara", p.cityLabels[0].first)
        // wx = (100 - 500) / 3.071 * 100000 = -13025073
        // wy = (400 - 200) / 3.071 * 100000 = 6512537
        val expectedCityX = ((-400.0) / 3.071 * 100000.0).roundToLong()
        val expectedCityY = (200.0 / 3.071 * 100000.0).roundToLong()
        assertEquals(expectedCityX, p.cityLabels[0].second)
        assertEquals(expectedCityY, p.cityLabels[0].third)

        assertEquals(1, p.countryLabels.size)
        assertEquals("Solomon", p.countryLabels[0].first)
        val expectedCountryX = ((50.0 - 500.0) / 3.071 * 100000.0).roundToLong()
        val expectedCountryY = ((400.0 - 60.0) / 3.071 * 100000.0).roundToLong()
        assertEquals(expectedCountryX, p.countryLabels[0].second)
        assertEquals(expectedCountryY, p.countryLabels[0].third)
    }

    @Test
    fun `raster map without scale returns false`() {
        val p = MapDataParser()
        val mapName = StringBuilder()
        val ok = p.parseRasterMap("MAP=NoScale.jpg", mapName)
        assertFalse(ok)
        assertEquals("NoScale.jpg", mapName.toString())
    }
}
