package com.simplot.android

import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.FogOfWar
import com.simplot.android.engine.MovementEngine
import com.simplot.android.engine.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎单元测试：回合状态机、运动计算、感知迷雾
 */
class EngineTest {

    private fun surfaceUnit(id: String, side: String, x: Long, y: Long, speedKnots: Double, course: Double): Unit {
        return Unit().apply {
            idNum = id
            this.side = side
            name = id
            this.x = x
            this.y = y
            setSpeed(speedKnots)
            setCourse(course)
        }
    }

    private fun scenario(units: List<Unit>): ScenarioFile {
        return ScenarioFile().apply {
            scenario = Scenario(scenarioName = "测试")
            time = TimeState(
                currentTurnTime = "2026-01-01 00:00:00",
                currentPositionTime = "2026-01-01 00:00:00",
                currentTurnInterval = TurnInterval(3, 0)
            )
            this.units = units.toMutableList()
            objects = units.map { it.idNum }.toMutableList()
        }
    }

    @Test
    fun `do before advances position time and phase 2`() {
        val f = scenario(listOf(surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals("2026-01-01 00:03:00", f.time.currentPositionTime)
        assertEquals("2026-01-01 00:00:00", f.time.currentTurnTime)
        assertEquals(2, f.scenario.phase)
        assertEquals(TurnState.State.DO_AFTER, TurnState.detect(f))
    }

    @Test
    fun `next confirms turn and appends to turns`() {
        val f = scenario(listOf(surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        TurnState.confirmNext(f, TurnInterval(3, 0))
        assertEquals("2026-01-01 00:03:00", f.time.currentTurnTime)
        assertEquals("2026-01-01 00:03:00", f.time.currentPositionTime)
        assertEquals(0, f.scenario.phase)
        assertTrue(f.turns.any { it.turnTime == "2026-01-01 00:03:00" })
    }

    @Test
    fun `straight movement 12 knots 3 min east moves 1 nmi`() {
        // 12 节 × 3 分钟 = 0.6 海里；朝 90°（东）移动 0.6 nmi = 60000 文件单位
        val f = scenario(listOf(surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        val u = f.units.first()
        assertEquals(60000L, u.x)
        assertEquals(0L, u.y)
    }

    @Test
    fun `turn less than 10 deg no advance point`() {
        val f = scenario(listOf(surfaceUnit("S001", "Blue", 0, 0, 20.0, 0.0)))
        val result = MovementEngine.turnMotion(0, 0, 0.0, 5.0, 1000000.0, 400.0 * 100000 / 2025.37)
        assertEquals(0, result.turnPoints.size)
    }

    @Test
    fun `zero distance does not turn`() {
        val f = scenario(listOf(surfaceUnit("S001", "Blue", 0, 0, 0.0, 0.0)))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove(idNum = "S001", newCourse = 90.0)
        ))
        val u = f.units.first()
        assertEquals(0.0, u.courseDeg(), 0.01)
        assertEquals(0L, u.x)
        assertEquals(0L, u.y)
    }

    @Test
    fun `custom turn interval 45 seconds advances correctly`() {
        val f = scenario(listOf(surfaceUnit("S001", "Blue", 0, 0, 12.0, 0.0)))
        MovementEngine.advance(f, TurnInterval(0, 45))
        // 12 节 × 0.75 分钟 = 0.15 海里 = 15000 文件单位
        val u = f.units.first()
        assertEquals(15000L, u.y)
        assertEquals("2026-01-01 00:00:45", f.time.currentPositionTime)
    }

    @Test
    fun `fog of war hides unit from red when perception set`() {
        val u = surfaceUnit("S001", "Blue", 0, 0, 10.0, 0.0)
        FogOfWar.setVisibility(u, "Red", false, "2026-01-01 00:00:00", null)
        assertTrue(FogOfWar.isVisibleTo(u, "Blue"))
        assertFalse(FogOfWar.isVisibleTo(u, "Red"))
    }

    @Test
    fun `fog of war own side always visible`() {
        val u = surfaceUnit("S001", "Red", 0, 0, 10.0, 0.0)
        FogOfWar.setVisibility(u, "Red", false, "2026-01-01 00:00:00", null)
        assertTrue(FogOfWar.isVisibleTo(u, "Red"))
    }

    @Test
    fun `applyPerspective filters units for player view`() {
        val hidden = surfaceUnit("S001", "Blue", 0, 0, 10.0, 0.0)
        val redOwn = surfaceUnit("S002", "Red", 100, 100, 10.0, 0.0)
        val f = scenario(listOf(hidden, redOwn))
        FogOfWar.setVisibility(hidden, "Red", false, "2026-01-01 00:00:00", null)

        val redView = FogOfWar.applyPerspective(f, "Red")
        // 红方视角：红方自己的单位在，蓝方隐藏单位被剔除
        assertEquals(1, redView.units.size)
        assertEquals("S002", redView.units.first().idNum)
    }

    @Test
    fun `range depletes and stops unit`() {
        // Range 单位：海里整数；12 节 × 3 分钟 = 0.6 海里/回合
        val u = surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)
        u.range = 1  // 仅 1 海里航程
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        val moved = f.units.first()
        assertEquals(60000L, moved.x)   // 0.6 海里移动
        assertEquals(0, moved.range)    // Range 耗尽
        // 下一回合：Range=0 → 不再移动
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(60000L, moved.x)
        assertEquals(0L, moved.y)
    }

    @Test
    fun `range partially depletes`() {
        // 12 节 × 6 分钟 = 1.2 海里；Range=0.5 → 只走 0.5 海里（0.5 舍入为 1）
        val u = surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)
        u.range = 1
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(6, 0))
        val moved = f.units.first()
        assertEquals(100000L, moved.x)  // 0.5 海里 = 50000... 见下：distNm=1.2 >= range=1 → distNm=1 → 1 海里
        assertEquals(0, moved.range)
    }

    @Test
    fun `range unlimited by default`() {
        val u = surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)
        assertEquals(-100000, u.range)
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(60000L, f.units.first().x)  // 0.6 海里正常移动
    }

    @Test
    fun `new unit does not move this turn`() {
        val u = surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)
        u.isNewThisTurn = true
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        val moved = f.units.first()
        assertEquals(0L, moved.x)
        assertEquals(0L, moved.y)
        assertEquals(0, moved.pastWaypointArray.size)   // 不产生轨迹
    }

    @Test
    fun `movement records desktop waypoint object`() {
        val u = surfaceUnit("S001", "Blue", 0, 0, 12.0, 90.0)
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        val wp = f.units.first().pastWaypointArray
        assertEquals(1, wp.size)
        // 起点轨迹：移动前位置 (0,0)，时间=回合开始时间
        assertEquals(0L, wp[0].x)
        assertEquals(0L, wp[0].y)
        assertEquals("2026-01-01 00:00:00", wp[0].positionTime)
        assertTrue(wp[0].isTurnTime)
    }
}
