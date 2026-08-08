package com.simplot.android

import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.MovementEngine
import com.simplot.android.engine.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合门禁测试（反馈②③）：TurnState.canDo/canUndo/canNext 矩阵 + Do/Next 闭环 + 危险路径文档化。
 *
 * 引擎本体（advanceTime/confirmNext/undo）保持纯引擎语义不改；门禁为纯函数，供按钮 enabled
 * 与 GameViewModel 防御共用（A2：VM 层依赖 Android Application，JVM 单测不覆盖，门禁逻辑全部在此覆盖）。
 */
class TurnStateGateTest {

    private fun surfaceUnit(id: String): Unit = Unit().apply {
        idNum = id
        side = "Blue"
        name = id
        setSpeed(12.0)
        setCourse(0.0)
    }

    private fun scenario(units: List<Unit>): ScenarioFile = ScenarioFile().apply {
        scenario = Scenario(scenarioName = "门禁测试")
        time = TimeState(
            currentTurnTime = "2026-01-01 00:00:00",
            currentPositionTime = "2026-01-01 00:00:00",
            currentTurnInterval = TurnInterval(3, 0)
        )
        this.units = units.toMutableList()
        objects = units.map { it.idNum }.toMutableList()
    }

    @Test
    fun `gate matrix for all states`() {
        val f = scenario(listOf(surfaceUnit("S001")))
        // DO_BEFORE（初始）：Do✓ Undo✗ Next✗
        val before = TurnState.detect(f)
        assertEquals(TurnState.State.DO_BEFORE, before)
        assertTrue("DO_BEFORE 应可 Do", TurnState.canDo(before))
        assertFalse("DO_BEFORE 不可 Undo", TurnState.canUndo(before))
        assertFalse("DO_BEFORE 不可 Next", TurnState.canNext(before))

        // Do → DO_AFTER：Do✗ Undo✓ Next✓
        MovementEngine.advance(f, TurnInterval(3, 0))
        val after = TurnState.detect(f)
        assertEquals(TurnState.State.DO_AFTER, after)
        assertFalse("DO_AFTER 不可 Do", TurnState.canDo(after))
        assertTrue("DO_AFTER 可 Undo", TurnState.canUndo(after))
        assertTrue("DO_AFTER 可 Next", TurnState.canNext(after))

        // Next → DO_NEXT：Do✓ Undo✗ Next✗
        TurnState.confirmNext(f, TurnInterval(3, 0))
        val next = TurnState.detect(f)
        assertEquals(TurnState.State.DO_NEXT, next)
        assertTrue("DO_NEXT 可 Do（新一轮）", TurnState.canDo(next))
        assertFalse("DO_NEXT 不可 Undo", TurnState.canUndo(next))
        assertFalse("DO_NEXT 不可 Next", TurnState.canNext(next))
    }

    @Test
    fun `do next do closed loop`() {
        val f = scenario(listOf(surfaceUnit("S001")))
        // 初态 DO_BEFORE → Do
        assertTrue(TurnState.canDo(TurnState.detect(f)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(TurnState.State.DO_AFTER, TurnState.detect(f))
        // Next 确认 → DO_NEXT
        TurnState.confirmNext(f, TurnInterval(3, 0))
        assertEquals(TurnState.State.DO_NEXT, TurnState.detect(f))
        assertEquals("2026-01-01 00:03:00", f.time.currentTurnTime)
        // DO_NEXT 可再 Do → DO_AFTER（PositionTime 继续推进）
        assertTrue(TurnState.canDo(TurnState.detect(f)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(TurnState.State.DO_AFTER, TurnState.detect(f))
        assertEquals("2026-01-01 00:06:00", f.time.currentPositionTime)
        // 闭环回到 DO_AFTER 后 Undo/Next 再次可用
        assertTrue(TurnState.canUndo(TurnState.detect(f)))
        assertTrue(TurnState.canNext(TurnState.detect(f)))
    }

    @Test
    fun `dangerous undo path at do before is gated`() {
        // 危险路径文档化（反馈②③）：DO_BEFORE 下直接调 TurnState.undo 会无条件回退时间——
        // 证明 VM 门禁（按钮 enabled + doTurn/undo/next 防御）的必要性。
        val f = scenario(listOf(surfaceUnit("S001")))
        assertEquals(TurnState.State.DO_BEFORE, TurnState.detect(f))
        // 门禁拦截：canUndo(DO_BEFORE) == false
        assertFalse(TurnState.canUndo(TurnState.detect(f)))
        // 但引擎本体不设防：直接调用会把 PositionTime 回退一个回合时长（00:00 → 前一天 23:57）
        TurnState.undo(f, TurnInterval(3, 0))
        assertEquals("2025-12-31 23:57:00", f.time.currentPositionTime)
        assertEquals(0, f.scenario.phase)
        // 若发生在 VM 层：此路径已被 canUndo 拦截，不会执行
    }
}
