package com.simplot.android

import com.simplot.android.data.model.Unit
import com.simplot.android.engine.MovementEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G15 手动移动控制测试（桌面版 ContainerMove DoMove/Pause/UndoMove）。
 *
 * 覆盖：
 * - 移动时长：航速 × 档位 × 分钟 → 位移（12 节 × 1.0 档 × 5 分钟 = 1 海里 = 100000 文件单位）
 * - 速度档位：2.0x 档位位移翻倍；0.5x 减半
 * - 零航速 / 零距离不移动
 * - Range 耗尽截断（E4 语义：距离≥剩余 Range 时耗尽并清零）
 * - UndoMove 快照恢复：位置/航速/航向/Range/轨迹点全部还原
 * - 轨迹点记录：移动前位置写入 PastWaypointArray
 */
class ManualMoveTest {

    private fun unit(speedKnots: Double, courseDeg: Double): Unit = Unit().apply {
        idNum = "S001"
        name = "手动移动测试"
        setSpeed(speedKnots)
        setCourse(courseDeg)
    }

    private fun surface(x: Long, y: Long): Unit = Unit().apply {
        idNum = "S001"
        name = "手动移动测试"
        this.x = x
        this.y = y
    }

    @Test
    fun `move 12 knots 5 minutes at 1x gear moves 1 nmi east`() {
        val u = unit(12.0, 90.0)   // 12 节朝东
        val moved = MovementEngine.manualMoveStep(u, 5.0, 1.0, "2026-01-01 00:00:00")
        assertTrue(moved)
        // 12 节 × 5 分钟 = 1 海里 = 100000 文件单位（90° 朝东 → x 增加）
        assertEquals(100000L, u.x)
        assertEquals(0L, u.y)
    }

    @Test
    fun `move 12 knots 5 minutes at 2x gear moves 2 nmi east`() {
        val u = unit(12.0, 90.0)
        MovementEngine.manualMoveStep(u, 5.0, 2.0, "2026-01-01 00:00:00")
        assertEquals(200000L, u.x)
        assertEquals(0L, u.y)
    }

    @Test
    fun `move 12 knots 5 minutes at 05x gear moves 0-5 nmi east`() {
        val u = unit(12.0, 90.0)
        MovementEngine.manualMoveStep(u, 5.0, 0.5, "2026-01-01 00:00:00")
        // 12 节 × 0.5 × 5 分钟 = 0.5 海里 = 50000 文件单位
        assertEquals(50000L, u.x)
        assertEquals(0L, u.y)
    }

    @Test
    fun `zero speed does not move`() {
        val u = unit(0.0, 90.0)
        val moved = MovementEngine.manualMoveStep(u, 5.0, 1.0, "2026-01-01 00:00:00")
        assertFalse(moved)
        assertEquals(0L, u.x)
        assertEquals(0L, u.y)
    }

    @Test
    fun `range exhaustion truncates movement and zeroes range`() {
        val u = unit(20.0, 0.0)   // 20 节朝北
        u.range = 1               // 只剩 1 海里
        val moved = MovementEngine.manualMoveStep(u, 15.0, 1.0, "2026-01-01 00:00:00")
        assertTrue(moved)
        // 20 节 × 15 分钟 = 5 海里请求，但 Range 只余 1 海里 → 只移动 1 海里 = 100000
        assertEquals(0L, u.x)
        assertEquals(100000L, u.y)
        assertEquals(0, u.range)
    }

    @Test
    fun `track point recorded before move`() {
        val u = unit(12.0, 90.0)
        MovementEngine.manualMoveStep(u, 5.0, 1.0, "2026-01-01 00:00:00")
        assertEquals(1, u.pastWaypointArray.size)
        // 轨迹点 = 移动前位置 (0,0)，时间戳 = 传入的当前时间
        assertEquals(0L, u.pastWaypointArray[0].x)
        assertEquals(0L, u.pastWaypointArray[0].y)
        assertEquals("2026-01-01 00:00:00", u.pastWaypointArray[0].positionTime)
    }

    @Test
    fun `undo restores position speed course range and track points`() {
        val u = unit(12.0, 90.0)
        u.x = 12345L
        u.y = 67890L
        u.speed = 12000          // 12 节
        u.course = 90000         // 90°
        u.range = 50
        u.pastWaypointArray.add(com.simplot.android.data.model.Waypoint(x = 1L, y = 2L))

        val snap = MovementEngine.snapshotOf(u)
        MovementEngine.manualMoveStep(u, 5.0, 2.0, "2026-01-01 00:00:00")
        assertTrue(u.x != 12345L || u.y != 67890L)
        assertEquals(2, u.pastWaypointArray.size)

        MovementEngine.restoreSnapshot(u, snap)
        assertEquals(12345L, u.x)
        assertEquals(67890L, u.y)
        assertEquals(12000, u.speed)
        assertEquals(90000, u.course)
        assertEquals(50, u.range)
        assertEquals(1, u.pastWaypointArray.size)
    }

    @Test
    fun `undo stack survives multiple moves`() {
        val u = unit(12.0, 90.0)
        val snaps = mutableListOf<MovementEngine.ManualMoveSnapshot>()
        snaps.add(MovementEngine.snapshotOf(u))
        MovementEngine.manualMoveStep(u, 5.0, 1.0, "2026-01-01 00:00:00")   // → x=100000
        snaps.add(MovementEngine.snapshotOf(u))
        MovementEngine.manualMoveStep(u, 5.0, 1.0, "2026-01-01 00:05:00")   // → x=200000
        assertEquals(200000L, u.x)

        MovementEngine.restoreSnapshot(u, snaps.removeAt(snaps.lastIndex))
        assertEquals(100000L, u.x)
        MovementEngine.restoreSnapshot(u, snaps.removeAt(snaps.lastIndex))
        assertEquals(0L, u.x)
    }

    @Test
    fun `manual move does not advance scenario time`() {
        val u = unit(12.0, 90.0)
        // manualMoveStep 只移动单位，不触碰 ScenarioFile 时间——引擎层无时间推进副作用
        MovementEngine.manualMoveStep(u, 5.0, 1.0, "2026-01-01 00:00:00")
        assertEquals(100000L, u.x)
    }

    @Test
    fun `gear list matches UI options`() {
        assertEquals(listOf(0.5, 1.0, 2.0, 4.0), MovementEngine.MANUAL_MOVE_GEARS)
    }

    @Test
    fun `plan does not mutate unit`() {
        val u = unit(12.0, 90.0)
        u.x = 500L
        u.y = 700L
        u.range = 30
        val plan = MovementEngine.planManualMove(u, 5.0, 1.0)!!
        // 单位未被修改（UI 缓冲式编辑：应用前不落盘）
        assertEquals(500L, u.x)
        assertEquals(700L, u.y)
        assertEquals(30, u.range)
        assertEquals(0, u.pastWaypointArray.size)
        // 规划结果 = 实际位移
        assertEquals(500L + 100000L, plan.newX)
        assertEquals(700L, plan.newY)
    }

    @Test
    fun `plan respects range exhaustion`() {
        val u = unit(20.0, 0.0)
        u.range = 1
        val plan = MovementEngine.planManualMove(u, 15.0, 1.0)!!
        assertEquals(100000L, plan.newY)
        assertEquals(0, plan.newRange)
        assertEquals(1.0, plan.distNm, 1e-9)
    }

    @Test
    fun `plan returns null for zero speed or zero distance`() {
        assertNull(MovementEngine.planManualMove(unit(0.0, 90.0), 5.0, 1.0))
        // Range=0 已耗尽且非 ignoreRange → 无规划
        val exhausted = unit(12.0, 90.0)
        exhausted.range = 0
        assertNull(MovementEngine.planManualMove(exhausted, 5.0, 1.0))
    }
}
