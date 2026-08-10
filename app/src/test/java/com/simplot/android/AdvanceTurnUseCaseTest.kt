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
    fun `final waypoint reached detected`() {
        val f = movingScenario()
        // 构造：无未来航路点 + 有历史轨迹 → 视为到达最终航路点
        f.units[0].pastWaypointArray.add(
            com.simplot.android.data.model.Waypoint(x = 0, y = 0)
        )
        assertTrue(AdvanceTurnUseCase.hasReachedFinalWaypoint(f, "S001"))
        assertFalse(AdvanceTurnUseCase.hasReachedFinalWaypoint(f, "NOPE"))
    }
}
