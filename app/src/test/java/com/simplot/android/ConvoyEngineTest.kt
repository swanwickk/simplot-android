package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.domain.engine.ConvoyEngine
import com.simplot.android.domain.engine.TrackCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 护航队创建引擎测试（P2 恢复，桌面版 CreateConvoy 逻辑）。
 * N2 修复：TrackNumber 由调用方注入的分配器提供（GameViewModel 传 TrackCounter），
 * 引擎不再本地 max+1（原实现不写回场景计数器，桌面续建会撞号）。
 */
class ConvoyEngineTest {

    private fun nextIdCounter() = object {
        var n = 0
        fun next(prefix: String): String {
            n++
            return prefix + n.toString().padStart(3, '0')
        }
    }.let { c -> { prefix: String -> c.next(prefix) } }

    /** 顺序递增的 TrackNumber 假分配器（模拟 GameViewModel 计数器路径） */
    private fun nextTrackNumberCounter() = object {
        var n = 2400
        fun next(side: String): Int {
            n++
            return n
        }
    }.let { c -> { side: String -> c.next(side) } }

    @Test
    fun `creates commodore plus 6 merchants`() {
        val f = ScenarioFile()
        val units = ConvoyEngine.build(f, ConvoyEngine.ConvoySpec(), nextIdCounter(), nextTrackNumberCounter())
        assertEquals(7, units.size)
        // 第一艘是指挥舰（编队中心）
        assertTrue(units[0].isFormationCenter == true)
        assertEquals("COMMODORE", units[0].name)
        assertEquals("Convoy", units[0].formationName)
        // 其余是环绕 Merchant
        units.drop(1).forEach { m ->
            assertEquals("Merchant", m.unitType)
            assertTrue(m.isInFormation == true)
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
            nextIdCounter(),
            nextTrackNumberCounter()
        )
        assertEquals(4, units.size)
        assertEquals("TASK FORCE", units[0].name)
        // 三艘商船方位角均匀：0/120/240 度
        val bearings = units.drop(1).map { (it.formationBearing ?: 0) / 1000.0 }
        assertEquals(listOf(0.0, 120.0, 240.0), bearings)
    }

    @Test
    fun `track numbers come from allocator sequentially`() {
        val f = ScenarioFile()
        val sides = mutableListOf<String>()
        val units = ConvoyEngine.build(
            f,
            ConvoyEngine.ConvoySpec(escortCount = 3),
            nextIdCounter(),
            nextTrackNumber = { side -> sides.add(side); 2400 + sides.size }
        )
        assertEquals(4, units.size)
        assertEquals(2401, units[0].trackNumber)
        assertEquals(2402, units[1].trackNumber)
        assertEquals(2404, units[3].trackNumber)
        // 每个单位分配一次，且全部按护航队阵营
        assertEquals(4, sides.size)
        assertTrue(sides.all { it == "Blue" })
    }

    @Test
    fun `convoy uses counter allocator and does not collide on desktop continuation`() {
        val f = ScenarioFile(
            units = mutableListOf(
                Unit(idNum = "S001", trackNumber = 2450)
            )
        )
        f.scenario.currentTrackNumber = 2450
        val units = ConvoyEngine.build(
            f,
            ConvoyEngine.ConvoySpec(escortCount = 6),
            nextIdCounter(),
            nextTrackNumber = { side -> TrackCounter.allocate(f, side) }
        )
        // 指挥舰 + 6 商船 = 2451..2457 连续
        assertEquals((2451..2457).toList(), units.map { it.trackNumber })
        // 计数器已写回：桌面续建下一个单位不撞号
        assertEquals(2457, f.scenario.currentTrackNumber)
        assertEquals(2458, TrackCounter.allocate(f, "Blue"))
        assertEquals(2458, f.scenario.currentTrackNumber)
    }

    @Test
    fun `red convoy allocates from player counter and keeps blue counter untouched`() {
        val f = ScenarioFile(
            units = mutableListOf(
                Unit(idNum = "S001", side = "Red", trackNumber = 9500)
            )
        )
        f.scenario.currentPlayerTrackNumber = 9500
        f.scenario.currentTrackNumber = 2400
        val units = ConvoyEngine.build(
            f,
            ConvoyEngine.ConvoySpec(side = "Red", escortCount = 2),
            nextIdCounter(),
            nextTrackNumber = { side -> TrackCounter.allocate(f, side) }
        )
        // 指挥舰 + 2 商船 = 9501..9503，全部走红方计数器
        assertEquals(listOf(9501, 9502, 9503), units.map { it.trackNumber })
        assertTrue(units.all { it.side == "Red" })
        // 红方分配只写回 currentPlayerTrackNumber，蓝方计数器不动
        assertEquals(9503, f.scenario.currentPlayerTrackNumber)
        assertEquals(2400, f.scenario.currentTrackNumber)
    }
}
