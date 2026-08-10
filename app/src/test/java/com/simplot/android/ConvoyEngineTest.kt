package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.domain.engine.ConvoyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 护航队创建引擎测试（P2 恢复，桌面版 CreateConvoy 逻辑）。
 */
class ConvoyEngineTest {

    private fun nextIdCounter() = object {
        var n = 0
        fun next(prefix: String): String {
            n++
            return prefix + n.toString().padStart(3, '0')
        }
    }.let { c -> { prefix: String -> c.next(prefix) } }

    @Test
    fun `creates commodore plus 6 merchants`() {
        val f = ScenarioFile()
        val units = ConvoyEngine.build(f, ConvoyEngine.ConvoySpec(), nextIdCounter())
        assertEquals(7, units.size)
        // 第一艘是指挥舰（编队中心）
        assertTrue(units[0].isFormationCenter)
        assertEquals("COMMODORE", units[0].name)
        assertEquals("Convoy", units[0].formationName)
        // 其余是环绕 Merchant
        units.drop(1).forEach { m ->
            assertEquals("Merchant", m.unitType)
            assertTrue(m.isInFormation)
            assertEquals("Convoy", m.formationName)
            // 都有位置偏移（环绕中心）
            assertTrue(m.x != 0L || m.y != 0L)
        }
    }

    @Test
    fun `custom spec respected`() {
        val f = ScenarioFile()
        val units = ConvoyEngine.build(
            f,
            ConvoyEngine.ConvoySpec(commodoreName = "TASK FORCE", escortCount = 3, distYards = 1000),
            nextIdCounter()
        )
        assertEquals(4, units.size)
        assertEquals("TASK FORCE", units[0].name)
        // 三艘商船方位角均匀：0/120/240 度
        val bearings = units.drop(1).map { it.formationBearing / 1000.0 }
        assertEquals(listOf(0.0, 120.0, 240.0), bearings)
    }

    @Test
    fun `track numbers increment`() {
        val existing = ScenarioFile(
            units = mutableListOf(
                com.simplot.android.data.model.Unit(idNum = "S001", trackNumber = 2450)
            )
        )
        val units = ConvoyEngine.build(existing, ConvoyEngine.ConvoySpec(), nextIdCounter())
        assertEquals(2451, units[0].trackNumber)
        assertEquals(2452, units[1].trackNumber)
    }
}
