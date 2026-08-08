package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.util.unitDistances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点选单位自动测量数据层单测（反馈②）。
 *
 * 构造 ScenarioFile 直接调顶层函数 unitDistances（纯 Kotlin，无 Android 依赖，无需 VM 实例）。
 */
class UnitMeasureTest {

    private fun unit(id: String, name: String, side: String, x: Long, y: Long): Unit {
        return Unit().apply {
            this.idNum = id
            this.name = name
            this.side = side
            this.x = x
            this.y = y
        }
    }

    private fun scenario(units: List<Unit>): ScenarioFile {
        return ScenarioFile().apply { this.units = units.toMutableList() }
    }

    /** A@(0,0)，B@(0,100000) 北 1 nmi，C@(200000,0) 东 2 nmi */
    private fun threeUnits(): ScenarioFile = scenario(
        listOf(
            unit("A", "Alpha", "Blue", 0, 0),
            unit("B", "Bravo", "Red", 0, 100000),
            unit("C", "Charlie", "Blue", 200000, 0)
        )
    )

    @Test
    fun `returns all other units excluding self`() {
        val result = unitDistances(threeUnits(), "A")
        assertEquals(2, result.size)
        // 排除自身
        assertTrue(result.none { it.idNum == "A" })
        // 含 B/C
        assertEquals(listOf("B", "C"), result.map { it.idNum })
    }

    @Test
    fun `sorted by distance ascending`() {
        val result = unitDistances(threeUnits(), "A")
        // B 1.0 nmi 在前，C 2.0 nmi 在后
        assertEquals("B", result[0].idNum)
        assertEquals("C", result[1].idNum)
        assertEquals(1.0, result[0].distNm, 1e-6)
        assertEquals(2.0, result[1].distNm, 1e-6)
    }

    @Test
    fun `distance bearing and field passthrough correct`() {
        val result = unitDistances(threeUnits(), "A")
        val b = result.first { it.idNum == "B" }
        assertEquals(1.0, b.distNm, 1e-6)          // 北 1 nmi
        assertEquals(0.0, b.bearingDeg, 1e-6)      // 正北 0°
        assertEquals("Bravo", b.name)
        assertEquals("Red", b.side)

        val c = result.first { it.idNum == "C" }
        assertEquals(2.0, c.distNm, 1e-6)          // 东 2 nmi
        assertEquals(90.0, c.bearingDeg, 1e-6)     // 正东 90°
        assertEquals("Charlie", c.name)
        assertEquals("Blue", c.side)
    }

    @Test
    fun `unknown unit returns empty list`() {
        assertEquals(0, unitDistances(threeUnits(), "ZZZ").size)
        assertTrue(unitDistances(threeUnits(), "ZZZ").isEmpty())
    }

    @Test
    fun `empty or single unit scenario returns empty list`() {
        assertTrue(unitDistances(scenario(emptyList()), "A").isEmpty())
        assertTrue(unitDistances(scenario(listOf(unit("A", "Alpha", "Blue", 0, 0))), "A").isEmpty())
    }
}
