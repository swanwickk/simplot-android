package com.simplot.android

import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.engine.ReplayEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G64/N7：回放哨兵澄清测试。
 *
 * 桌面存档对齐：PositionTimeDeleted 默认值 "2020-01-01 00:00:00" 表示"从未删除"；
 * 感知 PositionTimeEnd 用 2999 远期值表示"永不失效"。
 * 缺陷：此前用 startsWith("2020-01-01") 前缀匹配当哨兵，真实在 2020-01-01 当天
 * 删除的单位（如 12:00）回放不隐藏。修正为完整语义比较。
 */
class ReplaySentinelTest {

    /** 时间线横跨 2020-01-01 00:00（轨迹起点）→ 13:00（当前时刻） */
    private fun scenario(unit: Unit): ScenarioFile {
        unit.pastWaypointArray.add(Waypoint(x = 0, y = 0, positionTime = "2020-01-01 00:00:00"))
        return ScenarioFile().apply {
            scenario = Scenario(scenarioName = "哨兵测试")
            time = TimeState(
                currentTurnTime = "2020-01-01 13:00:00",
                currentPositionTime = "2020-01-01 13:00:00",
                currentTurnInterval = TurnInterval(3, 0)
            )
            units = mutableListOf(unit)
        }
    }

    private fun unit(deleted: String = "2020-01-01 00:00:00", created: String = ""): Unit {
        return Unit().apply {
            idNum = "S001"
            side = "Blue"
            name = "S001"
            x = 100
            y = 200
            positionTimeCreated = created
            positionTimeDeleted = deleted
        }
    }

    /** 取指定时刻帧中该单位位置（null = 帧中不显示） */
    private fun posAt(tl: List<ReplayEngine.Frame>, time: String): ReplayEngine.UnitPos? {
        val frame = tl.firstOrNull { it.time == time } ?: return null
        return frame.positions["S001"]
    }

    @Test
    fun `default sentinel 2020-01-01 00-00-00 treated as not deleted`() {
        // 默认值（从未删除）→ 全程显示
        val tl = ReplayEngine.buildTimeline(scenario(unit(deleted = "2020-01-01 00:00:00")))
        assertEquals(2, tl.size)
        assertTrue(posAt(tl, "2020-01-01 00:00:00") != null)
        assertTrue(posAt(tl, "2020-01-01 13:00:00") != null)
    }

    @Test
    fun `blank deleted treated as not deleted`() {
        val tl = ReplayEngine.buildTimeline(scenario(unit(deleted = "")))
        assertTrue(posAt(tl, "2020-01-01 13:00:00") != null)
    }

    @Test
    fun `real deletion on sentinel date noon hides unit after noon`() {
        // G64 回归：2020-01-01 12:00 的真实删除 → 前缀匹配曾误判为未删除
        val tl = ReplayEngine.buildTimeline(scenario(unit(deleted = "2020-01-01 12:00:00")))
        // 00:00 帧（删除前）显示
        assertTrue(posAt(tl, "2020-01-01 00:00:00") != null)
        // 13:00 帧（删除后）隐藏
        assertNull("2020-01-01 当天真实删除的单位在删除后必须隐藏", posAt(tl, "2020-01-01 13:00:00"))
    }

    @Test
    fun `real deletion hides unit at and after deletion time`() {
        val tl = ReplayEngine.buildTimeline(scenario(unit(deleted = "2020-01-01 06:00:00")))
        assertTrue(posAt(tl, "2020-01-01 00:00:00") != null)      // 删除前
        assertNull(posAt(tl, "2020-01-01 13:00:00"))               // 删除后（当前帧）
    }

    @Test
    fun `far future sentinel never hides unit`() {
        // 2999 远期哨兵（感知 PositionTimeEnd 惯例 = 永不失效）
        val tl = ReplayEngine.buildTimeline(scenario(unit(deleted = "2999-12-31 00:00:00")))
        assertTrue(posAt(tl, "2020-01-01 13:00:00") != null)
    }

    @Test
    fun `far future sentinel boundary 2999-01-01 treated as not deleted`() {
        // SENTINEL_END_OF_TIME 边界：>= 2999-01-01 即视为永不删除
        val tl = ReplayEngine.buildTimeline(scenario(unit(deleted = "2999-01-01 00:00:00")))
        assertTrue(posAt(tl, "2020-01-01 00:00:00") != null)
        assertTrue(posAt(tl, "2020-01-01 13:00:00") != null)
    }

    @Test
    fun `legacy date-only default 2020-01-01 does not hide unit`() {
        // 旧版安卓存档（v0.5.0）默认写 date-only "2020-01-01"（= 从未删除）；
        // 严格格式解析失败走容忍路径 → 单位全程可见（默认值语义保持，不误判为真实删除）
        val tl = ReplayEngine.buildTimeline(scenario(unit(deleted = "2020-01-01")))
        assertTrue(posAt(tl, "2020-01-01 00:00:00") != null)
        assertTrue(posAt(tl, "2020-01-01 13:00:00") != null)
    }

    @Test
    fun `unit hidden at frame exactly equal to deletion time`() {
        // 删除时刻帧本身不显示（target >= deleted 即隐藏）
        val f = scenario(unit(deleted = "2020-01-01 06:00:00"))
        f.units[0].pastWaypointArray.add(Waypoint(x = 50, y = 50, positionTime = "2020-01-01 06:00:00"))
        val tl = ReplayEngine.buildTimeline(f)
        assertTrue(posAt(tl, "2020-01-01 00:00:00") != null)      // 删除前
        assertNull("删除时刻帧不显示", posAt(tl, "2020-01-01 06:00:00"))
        assertNull(posAt(tl, "2020-01-01 13:00:00"))               // 删除后
    }

    @Test
    fun `unit hidden before created time`() {
        // E7 创建过滤仍生效：创建时间晚于帧时间 → 不显示
        val tl = ReplayEngine.buildTimeline(
            scenario(unit(created = "2020-01-01 06:00:00"))
        )
        assertNull("创建前不显示", posAt(tl, "2020-01-01 00:00:00"))
        assertTrue("创建后显示", posAt(tl, "2020-01-01 13:00:00") != null)
    }
}
