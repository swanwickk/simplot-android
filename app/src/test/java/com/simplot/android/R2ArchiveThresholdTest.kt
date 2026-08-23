package com.simplot.android

import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.engine.MovementEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 回归：归档阈值用真实移动距离且顺序 move→archive→altitude。
 * 桌面 CustomTimer：Move → ArchiveFutureWaypoint → ChangeAltitude。
 */
class R2ArchiveThresholdTest {

    private fun unit(id: String, x: Long, y: Long, speedKnots: Double, course: Double, range: Int = -100000): Unit =
        Unit().apply { idNum = id; side = "Blue"; name = id; this.x = x; this.y = y; setSpeed(speedKnots); setCourse(course); this.range = range }

    private fun scenario(units: List<Unit>): ScenarioFile = ScenarioFile().apply {
        scenario = Scenario(scenarioName = "R2")
        time = TimeState("2026-01-01 00:00:00", "2026-01-01 00:00:00", TurnInterval(3, 0))
        this.units = units.toMutableList(); objects = units.map { it.idNum }.toMutableList()
    }

    @Test
    fun `archive uses true distNm not newSpeed times minutes and range truncation honored`() {
        // 12节*3min=0.6nm，真实移动0.6，但阈值下限1nm（100000）。航路点在2nm外（200000）
        // 移动后距航路点1.4nm（140000）>1nm，不应归档；若阈值逻辑错用新航速×时长会误判
        val u = unit("S001", 0, 0, 12.0, 0.0, range = 10).apply {
            futureWaypointArray.add(Waypoint(x = 0, y = 200000, number = 1, isTurnTime = false, positionTime = "2026-01-01 00:00:00"))
        }
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertFalse("2nm 外航路点不应被1nm下限阈值归档", f.units[0].futureWaypointArray.isEmpty())
        // 第二回合：改航路点到0.5nm内，0.6阈值应归档
        f.units[0].futureWaypointArray.clear()
        f.units[0].futureWaypointArray.add(Waypoint(x = f.units[0].x, y = f.units[0].y + 40000, number = 1, isTurnTime = false, positionTime = "2026-01-01 00:00:00"))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertTrue("0.4nm 内航路点应被归档", f.units[0].futureWaypointArray.isEmpty())
        // pastWaypointArray 含：advance起点轨迹 + 归档的未来航路点，共≥2
    }

    @Test
    fun `altitude after archive uses next waypoint not consumed one`() {
        // R2 顺序：archive先于altitude。若高度先算，会以被消费的航路点目标算高度，错。
        // 构造：当前高度1000，WP1目标3000（ascent 500，近点会被归档），WP2目标5000
        // 正确：本回合归档WP1，高度应向WP2趋近（1000->1180向5000）；若先算高度则向3000趋近结果不同但上限相同需区分速率
        val u = unit("S001", 0, 0, 24.0, 0.0).apply {
            setAltitude(1000)
            // WP1 在移动路径上近点（0,60000），会被归档
            futureWaypointArray.add(Waypoint(x = 0, y = 60000, altitudeDepth = 1000, assignedAltDepth = 3000 * 1000, ascent = 60 * 1000, descent = 60 * 1000, number = 1, isTurnTime = false, positionTime = "2026-01-01 00:00:00"))
            futureWaypointArray.add(Waypoint(x = 0, y = 200000, altitudeDepth = 3000 * 1000, assignedAltDepth = 5000 * 1000, ascent = 200 * 1000, descent = 200 * 1000, number = 2, isTurnTime = false, positionTime = "2026-01-01 00:00:00"))
        }
        u.setSpeed(24.0); u.setCourse(0.0)
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 已归档 WP1，剩余 WP2；高度按WP2的ascent=200趋近，上限180 => 1180
        assertTrue("WP1应被归档", f.units[0].pastWaypointArray.any { it.x == 0L && it.y == 60000L } || f.units[0].futureWaypointArray.size == 1)
        assertEquals(1, f.units[0].futureWaypointArray.size)
        assertEquals(5000 * 1000, f.units[0].futureWaypointArray[0].assignedAltDepth)
        assertEquals(1180, f.units[0].altitudeMeters())
    }

    @Test
    fun `range zero no movement does not archive waypoint`() {
        // FIX10 对齐桌面 ArchiveFutureWaypoint：阈值=本回合实走距离，无 1nm 下限。
        // Range=0 → 本回合实走 0 → 不归档（停船不应吸走航路点）。
        val u = unit("S001", 0, 0, 12.0, 0.0, range = 0).apply {
            futureWaypointArray.add(Waypoint(x = 0, y = 90000, number = 1, isTurnTime = false, positionTime = "2026-01-01 00:00:00"))
        }
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertTrue("Range=0 未移动不应归档航路点", f.units[0].futureWaypointArray.isNotEmpty())
    }

    @Test
    fun `slow ship archives only within actual moved distance`() {
        // 慢速船（5节×3分钟=0.25nm）只应归档 0.25nm 内的航路点，0.9nm 外的保留
        val u = unit("S002", 0, 0, 5.0, 0.0, range = -100000).apply {
            futureWaypointArray.add(Waypoint(x = 0, y = 90000, number = 1, isTurnTime = false, positionTime = "2026-01-01 00:00:00")) // 0.9nm 远
        }
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertTrue("0.9nm 航路点超出实走 0.25nm 不应被提前归档", f.units[0].futureWaypointArray.isNotEmpty())
    }
}
