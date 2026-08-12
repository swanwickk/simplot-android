package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.util.CoordUtil
import com.simplot.android.domain.engine.ConvoyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G03：护航队网格布局 + 航向/航速参数测试（桌面 WindowConvoy TextCourse/TextSpeed/
 * TextNumCols/TextNumRows/TextSpaceCols/TextSpaceRows）。
 * 默认规格（列/行=0）回退环绕布局，行为由 ConvoyEngineTest 覆盖，此处只测新增参数。
 */
class ConvoySpecGridTest {

    private fun nextIdCounter() = object {
        var n = 0
        fun next(prefix: String): String {
            n++
            return prefix + n.toString().padStart(3, '0')
        }
    }.let { c -> { prefix: String -> c.next(prefix) } }

    private fun nextTrackNumberCounter() = object {
        var n = 2400
        fun next(side: String): Int {
            n++
            return n
        }
    }.let { c -> { side: String -> c.next(side) } }

    @Test
    fun `grid layout creates cols times rows merchants centered on commodore`() {
        val f = ScenarioFile()
        val spec = ConvoyEngine.ConvoySpec(
            commodoreName = "COMMODORE",
            numCols = 3, numRows = 2,
            spaceColsYards = 1000, spaceRowsYards = 500
        )
        val units = ConvoyEngine.build(f, spec, nextIdCounter(), nextTrackNumberCounter())
        // 1 指挥舰 + 3×2 商船
        assertEquals(7, units.size)
        assertEquals(6, spec.merchantCount())
        assertTrue(spec.isGridLayout())
        val commodore = units[0]
        val merchants = units.drop(1)
        // 商船序号 1..6
        assertEquals((1..6).map { "Merchant $it" }, merchants.map { it.name })
        // 列/行间距：网格以指挥舰为中心对称排布，±半间距分别定点舍入会产生
        // ≤1 文件单位误差（1 单位 ≈ 1.9 厘米），断言用容差 1 而非精确相等。
        val colFile = CoordUtil.yardsToFile(1000.0)
        val rowFile = CoordUtil.yardsToFile(500.0)
        val colXs = merchants.map { it.x }.distinct().sorted()
        assertEquals(3, colXs.size)
        assertEquals(colFile.toDouble(), (colXs[1] - colXs[0]).toDouble(), 1.0)
        assertEquals(colFile.toDouble(), (colXs[2] - colXs[1]).toDouble(), 1.0)
        val rowYs = merchants.map { it.y }.distinct().sorted()
        assertEquals(2, rowYs.size)
        assertEquals(rowFile.toDouble(), (rowYs[1] - rowYs[0]).toDouble(), 1.0)
        // 质心 ≈ 指挥舰（对称）
        val cx = merchants.map { it.x }.average()
        val cy = merchants.map { it.y }.average()
        assertTrue(kotlin.math.abs(cx - commodore.x) <= 1)
        assertTrue(kotlin.math.abs(cy - commodore.y) <= 1)
        // 全部为编队成员，formationBearing/formationDistance 已设置（编队移动可驱动）
        merchants.forEach { m ->
            assertTrue(m.isInFormation == true)
            assertTrue(m.formationBearing != null)
            assertTrue(m.formationDistance != null && m.formationDistance!! > 0)
        }
    }

    @Test
    fun `grid merchants track formation geometry`() {
        val f = ScenarioFile()
        val spec = ConvoyEngine.ConvoySpec(numCols = 2, numRows = 1, spaceColsYards = 1000, spaceRowsYards = 1000)
        val units = ConvoyEngine.build(f, spec, nextIdCounter(), nextTrackNumberCounter())
        val commodore = units[0]
        val left = units[1]
        val right = units[2]
        // 左槽 (0,0)：dx=-500 码（正西），罗盘方位 270；右槽 (1,0)：dx=+500 码，方位 90
        val half = CoordUtil.yardsToFile(500.0)
        assertEquals(commodore.x - half, left.x)
        assertEquals(commodore.y, left.y)
        assertEquals(commodore.x + half, right.x)
        assertEquals(commodore.y, right.y)
        assertEquals(270.0, left.formationBearing!! / 1000.0, 0.01)
        assertEquals(90.0, right.formationBearing!! / 1000.0, 0.01)
        assertEquals(half.toInt(), left.formationDistance)
    }

    @Test
    fun `course and speed applied to commodore and all merchants`() {
        val f = ScenarioFile()
        val spec = ConvoyEngine.ConvoySpec(
            escortCount = 3, distYards = 1000,
            courseDeg = 45.0, speedKnots = 12.5
        )
        val units = ConvoyEngine.build(f, spec, nextIdCounter(), nextTrackNumberCounter())
        units.forEach { u ->
            assertEquals(45.0, u.courseDeg(), 0.001)
            assertEquals(12.5, u.speedKnots(), 0.001)
        }
    }

    @Test
    fun `course and speed also applied in grid layout`() {
        val f = ScenarioFile()
        val spec = ConvoyEngine.ConvoySpec(
            numCols = 2, numRows = 2, spaceColsYards = 500, spaceRowsYards = 500,
            courseDeg = 180.0, speedKnots = 8.0
        )
        val units = ConvoyEngine.build(f, spec, nextIdCounter(), nextTrackNumberCounter())
        assertEquals(5, units.size)
        units.forEach { u ->
            assertEquals(180.0, u.courseDeg(), 0.001)
            assertEquals(8.0, u.speedKnots(), 0.001)
        }
    }

    @Test
    fun `default spec keeps circle layout and merchant count`() {
        val spec = ConvoyEngine.ConvoySpec()
        assertTrue(!spec.isGridLayout())
        assertEquals(6, spec.merchantCount())
        val f = ScenarioFile()
        val units = ConvoyEngine.build(f, spec, nextIdCounter(), nextTrackNumberCounter())
        assertEquals(7, units.size)
        // 环绕布局：方位角均匀 0/60/120...
        val bearings = units.drop(1).map { (it.formationBearing ?: 0) / 1000.0 }
        assertEquals(listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0), bearings)
    }
}
