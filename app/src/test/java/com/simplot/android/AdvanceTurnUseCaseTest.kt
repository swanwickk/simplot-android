package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.domain.usecase.AdvanceTurnUseCase
import com.simplot.android.engine.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合推进 UseCase 测试（文档 §4.1 AdvanceTurnUseCase）。
 */
class AdvanceTurnUseCaseTest {

    private fun movingScenario(): ScenarioFile {
        val u = Unit(
            idNum = "S001", side = "Blue", name = "DD-1", unitClass = "DD",
            speed = 30000, course = 90000, x = 0, y = 0, range = 1000
        )
        return ScenarioFile(units = mutableListOf(u))
    }

    @Test
    fun `do advances position time and moves unit`() {
        val f = movingScenario()
        val r = AdvanceTurnUseCase.execute(f, TurnInterval(3, 0))
        assertNotNull(r)
        // 3 分钟 30 节 = 1.5 海里 → X 增加（朝东 90°）
        assertTrue(f.units[0].x > 0)
        assertTrue(r!!.rangeExhausted.isEmpty())
        assertEquals(TurnState.State.DO_BEFORE, r.stateBefore)
    }

    @Test
    fun `undo after do restores position`() {
        val f = movingScenario()
        AdvanceTurnUseCase.execute(f, TurnInterval(3, 0))
        val xAfter = f.units[0].x
        assertTrue(xAfter > 0)
        val ok = AdvanceTurnUseCase.undo(f, TurnInterval(3, 0))
        assertTrue(ok)
        assertEquals(0L, f.units[0].x)
        // Undo 后回到 DO_BEFORE，不能再 Undo
        assertFalse(AdvanceTurnUseCase.undo(f, TurnInterval(3, 0)))
    }

    @Test
    fun `do is blocked when already after`() {
        val f = movingScenario()
        AdvanceTurnUseCase.execute(f, TurnInterval(3, 0))
        // Do 后未 Undo/Next → DO_AFTER，再次 Do 应被拦截
        assertNull(AdvanceTurnUseCase.execute(f, TurnInterval(3, 0)))
        // Next 确认后可再 Do
        assertTrue(AdvanceTurnUseCase.next(f, TurnInterval(3, 0)))
        assertNotNull(AdvanceTurnUseCase.execute(f, TurnInterval(3, 0)))
    }

    @Test
    fun `range exhausted unit reported`() {
        val u = Unit(idNum = "S001", side = "Blue", name = "DD-1", unitClass = "DD",
            speed = 30000, course = 90000, x = 0, y = 0, range = 1) // 1 海里，跑 1.5 海里会耗尽
        val f = ScenarioFile(units = mutableListOf(u))
        val r = AdvanceTurnUseCase.execute(f, TurnInterval(3, 0))
        assertNotNull(r)
        assertTrue(r!!.rangeExhausted.contains("S001"))
    }

    @Test
    fun `final waypoint reached detected only when engine marked`() {
        // P1-1 修复回归：hasReachedFinalWaypoint 读引擎精确标记 reachedFinalWaypoint，
        // 而非旧过宽条件（无未来航路点 + 有历史轨迹 即误判）。
        val f = movingScenario()
        // 未推进回合：标记为 false
        assertFalse(AdvanceTurnUseCase.hasReachedFinalWaypoint(f, "S001"))
        assertFalse(AdvanceTurnUseCase.hasReachedFinalWaypoint(f, "NOPE"))
        // 推进一回合：单位无未来航路点（从未设航线）→ 引擎精确标记仍为 false
        AdvanceTurnUseCase.execute(f, TurnInterval(3, 0))
        assertFalse(AdvanceTurnUseCase.hasReachedFinalWaypoint(f, "S001"))
    }

    @Test
    fun `final waypoint reported only for unit that consumed last waypoint this turn`() {
        // P1-1 修复回归：execute 的 finalWaypointReached 仅含"本回合消费最后一个未来航路点"的单位；
        // 从未设航线的单位（历史有轨迹、当前无未来航路点）不得被误报。
        val u1 = Unit(
            idNum = "S001", side = "Blue", name = "DD-1", unitClass = "DD",
            speed = 30000, course = 0, x = 0, y = 0, range = 1000   // 朝正北，0°=北
        )
        // 本回合到达并消费唯一未来航路点（正北 0.5 海里，30 节 × 3 分钟 = 1.5 海里足够）
        u1.futureWaypointArray.add(
            com.simplot.android.data.model.Waypoint(x = 0, y = 50000, number = 1, isTurnTime = true, positionTime = "2026-01-01 00:00:00")
        )
        val u2 = Unit(
            idNum = "S002", side = "Blue", name = "DD-2", unitClass = "DD",
            speed = 30000, course = 90000, x = 0, y = 100000, range = 1000
        )
        // 从未设航线：历史有轨迹、当前无未来航路点 → 不应被标记
        u2.pastWaypointArray.add(
            com.simplot.android.data.model.Waypoint(x = 0, y = 100000, number = 1, isTurnTime = true, positionTime = "2026-01-01 00:00:00")
        )
        val f = ScenarioFile(units = mutableListOf(u1, u2))
        val r = AdvanceTurnUseCase.execute(f, TurnInterval(3, 0))
        assertNotNull(r)
        // S001 本回合消费了最后一个未来航路点 → 被报告
        assertTrue("S001 本回合消费最后一个航路点应报告", r!!.finalWaypointReached.contains("S001"))
        // S002 从未设航线 → 不得误报
        assertFalse("S002 从未设航线不应误报", r.finalWaypointReached.contains("S002"))
    }
}
