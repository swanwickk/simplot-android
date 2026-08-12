package com.simplot.android

import com.simplot.android.ui.isValidScenarioStartTime
import com.simplot.android.ui.newScenarioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G01 新场景创建纯逻辑测试（桌面版 WindowNewScenario）：
 * - newScenarioFile：空 Referee 存档骨架（场景名/双时钟/TypeOfMap/MapFileName/计数器默认/空单位与回合）
 * - isValidScenarioStartTime：起始日期时间格式校验（严格 YYYY-MM-DD HH:MM:SS）
 */
class NewScenarioCreateTest {

    @Test
    fun `blank scenario creates empty referee file without map`() {
        val f = newScenarioFile("冰海巨兽", "2026-01-01 00:00:00")

        assertEquals("Referee", f.file)
        assertEquals("2.3", f.simPlotVersion)
        assertTrue(f.isIntegerFile)
        assertEquals("冰海巨兽", f.scenario.scenarioName)
        // 无地图 → TypeOfMap=0 且 MapFileName 不落盘
        assertEquals(0, f.scenario.typeOfMap)
        assertNull(f.scenario.mapFileName)
        // 双时钟 = 起始时间
        assertEquals("2026-01-01 00:00:00", f.time.currentTurnTime)
        assertEquals("2026-01-01 00:00:00", f.time.currentPositionTime)
        // 计数器桌面默认起点
        assertEquals(0, f.scenario.lastId)
        assertEquals(2400, f.scenario.currentTrackNumber)
        assertEquals(9000, f.scenario.currentPlayerTrackNumber)
        // 空骨架：无单位/回合/对象/标注/编队
        assertTrue(f.units.isEmpty())
        assertTrue(f.turns.isEmpty())
        assertTrue(f.objects.isEmpty())
        assertTrue(f.overlays.isEmpty())
        assertTrue(f.formations.isEmpty())
        assertEquals(0, f.typeOfGame)
        assertEquals(0, f.scenario.phase)
    }

    @Test
    fun `map file name sets typeOfMap to 1 and records file name`() {
        val f = newScenarioFile("测试", "2024-06-15 08:30:00", "JJWS1.json")

        assertEquals(1, f.scenario.typeOfMap)
        assertEquals("JJWS1.json", f.scenario.mapFileName)
        assertEquals("2024-06-15 08:30:00", f.time.currentTurnTime)
        assertEquals("2024-06-15 08:30:00", f.time.currentPositionTime)
    }

    @Test
    fun `blank map file name is treated as no map`() {
        val f = newScenarioFile("测试", "2024-06-15 08:30:00", "   ")

        assertEquals(0, f.scenario.typeOfMap)
        assertNull("空白地图名不落盘", f.scenario.mapFileName)
    }

    @Test
    fun `valid start times pass validation`() {
        assertTrue(isValidScenarioStartTime("2026-01-01 00:00:00"))
        assertTrue(isValidScenarioStartTime("2026-12-31 23:59:59"))
        assertTrue(isValidScenarioStartTime(" 2024-06-15 08:30:00 "))
    }

    @Test
    fun `invalid start times fail validation`() {
        assertFalse(isValidScenarioStartTime(""))
        assertFalse(isValidScenarioStartTime("2026-01-01"))            // 缺时间
        assertFalse(isValidScenarioStartTime("2026-01-01 25:00:00"))   // 小时越界
        assertFalse(isValidScenarioStartTime("2026-13-01 00:00:00"))   // 月份越界
        assertFalse(isValidScenarioStartTime("not-a-time"))
        assertFalse(isValidScenarioStartTime("2026/01/01 00:00:00"))   // 分隔符不符
    }

    @Test
    fun `new scenario is independent of old file state`() {
        // 骨架必须全新，不继承任何旧场景数据（构造即空）
        val f = newScenarioFile("A", "2020-05-05 05:05:05")
        f.units.add(com.simplot.android.data.model.Unit(idNum = "S001", side = "Blue"))

        val f2 = newScenarioFile("B", "2021-06-06 06:06:06")
        assertTrue(f2.units.isEmpty())
        assertEquals("B", f2.scenario.scenarioName)
        assertEquals("2021-06-06 06:06:06", f2.time.currentPositionTime)
    }
}
