package com.simplot.android

import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.MovementEngine
import com.simplot.android.engine.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R3 回归：lastTurnInterval 快照在 confirmNext/undo 真正使用而非仍传新 interval。
 * - Do 推进时快照 interval，Next/Undo 优先用快照，确保与推进时长一致。
 */
class R3SnapshotConsistencyTest {

    private fun scenario(interval: TurnInterval = TurnInterval(3, 0)): ScenarioFile = ScenarioFile().apply {
        scenario = Scenario(scenarioName = "R3")
        time = TimeState("2026-01-01 00:00:00", "2026-01-01 00:00:00", interval)
        units = mutableListOf(Unit().apply { idNum = "S001"; side = "Blue"; name = "S001"; setSpeed(12.0); setCourse(0.0) })
        objects = mutableListOf("S001")
    }

    @Test
    fun `confirmNext uses snapshot interval not new interval`() {
        val f = scenario(TurnInterval(3, 0))
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 用户改档把 interval 改成 5 分钟再 Next，桌面语义应仍按快照 3 分钟写 Turns
        f.time.currentTurnInterval = TurnInterval(5, 0)
        assertNotNull(f.lastTurnInterval)
        assertEquals(3, f.lastTurnInterval!!.minutes)
        TurnState.confirmNext(f, TurnInterval(5, 0))
        assertEquals(3, f.turns.first().turnInterval.minutes)
        assertEquals(0, f.turns.first().turnInterval.seconds)
        assertEquals(3, f.time.currentTurnInterval.minutes)
        assertNull("快照应被消费", f.lastTurnInterval)
    }

    @Test
    fun `confirmNext falls back to passed interval when snapshot absent`() {
        val f = scenario(TurnInterval(3, 0))
        // 不经 advance 直接 confirmNext（加载 DO_AFTER 存档场景）
        f.time.currentPositionTime = "2026-01-01 00:03:00"
        assertNull(f.lastTurnInterval)
        TurnState.confirmNext(f, TurnInterval(3, 0))
        assertEquals(3, f.turns.first().turnInterval.minutes)
    }

    @Test
    fun `undo uses snapshot interval for time rollback`() {
        val f = scenario(TurnInterval(5, 0))
        MovementEngine.advance(f, TurnInterval(5, 0))
        // 改档为新 interval，undo 仍应按快照 5 分钟回退
        f.time.currentTurnInterval = TurnInterval(3, 0)
        TurnState.undo(f, TurnInterval(3, 0))
        assertEquals("2026-01-01 00:00:00", f.time.currentPositionTime)
        assertNull(f.lastTurnInterval)
    }

    @Test
    fun `undo falls back when snapshot absent`() {
        val f = scenario(TurnInterval(3, 0))
        MovementEngine.advance(f, TurnInterval(3, 0))
        f.lastTurnInterval = null
        TurnState.undo(f, TurnInterval(3, 0))
        assertEquals("2026-01-01 00:00:00", f.time.currentPositionTime)
    }
}
