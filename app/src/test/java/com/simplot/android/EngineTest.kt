package com.simplot.android

import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.FogOfWar
import com.simplot.android.engine.MovementEngine
import com.simplot.android.engine.ReplayEngine
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

    // ============ 极地行动第一回合回归基准（移动指南 §10） ============

    private fun polarUnit(id: String, course: Double, maxSpeed: Double, unitClass: String = "CL"): Unit {
        return surfaceUnit(id, "Blue", 0, 0, 12.0, course).apply {
            this.maxSpeedKnots = maxSpeed
            this.unitClass = unitClass
        }
    }

    private fun levelClass(unitClass: String, maxSpeed: Double): String =
        com.simplot.android.engine.SizeLevels.levelName(unitClass, maxSpeed)

    @Test
    fun `polar action sharnhorst boost turn 60`() {
        // 沙恩霍斯特 快速A(31节) 12节 090°：加速 + 右转60°
        val u = polarUnit("S001", 90.0, 31.0, "BB")
        assertEquals("fastA", levelClass("BB", 31.0))
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newCourse = 150.0, boost = true)
        ))
        val moved = f.units.first()
        assertEquals(11.0, moved.speedKnots(), 0.01)   // 12+3(减半)−4
        assertEquals(150.0, moved.courseDeg(), 0.01)   // 前冲400×2 完成
    }

    @Test
    fun `polar action gneisenau decel turn 80 stops`() {
        // 格奈森瑙 快速A(31节) 12节 090°：减速 + 右转80° → 12−9−4=−1→0，距离0不转向
        val u = polarUnit("S001", 90.0, 31.0, "BB")
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newCourse = 170.0, decel = true)
        ))
        val moved = f.units.first()
        assertEquals(0.0, moved.speedKnots(), 0.01)
        assertEquals(90.0, moved.courseDeg(), 0.01)    // 航向保持
        assertEquals(0L, moved.x)
        assertEquals(0L, moved.y)
    }

    @Test
    fun `polar action dunkirk boost straight`() {
        // 敦刻尔克 快速A(29节) 12节 030°：加速直行 → 18节
        val u = polarUnit("S001", 30.0, 29.0, "BB")
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", boost = true)
        ))
        assertEquals(18.0, f.units.first().speedKnots(), 0.01)
    }

    @Test
    fun `polar action moncalm boost turn left 130`() {
        // 蒙卡姆 B(33节) 12节 030°：加速 + 左转130° → 12+5(减半)−6=11节，260°
        val u = polarUnit("S001", 30.0, 33.0, "CA")
        assertEquals("B", levelClass("CA", 33.0))
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newCourse = 260.0, boost = true)
        ))
        val moved = f.units.first()
        assertEquals(11.0, moved.speedKnots(), 0.01)
        assertEquals(260.0, moved.courseDeg(), 0.01)
    }

    @Test
    fun `polar action voltair boost turn plus 170`() {
        // 伏尔塔 C(39节) 12节 030°：加速 + 目标200°（最小角度+170°）→ 12+6(减半)−4=14节
        val u = polarUnit("S001", 30.0, 39.0, "DD")
        assertEquals("C", levelClass("DD", 39.0))
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newCourse = 200.0, boost = true)
        ))
        val moved = f.units.first()
        assertEquals(14.0, moved.speedKnots(), 0.01)   // 12+6−4
        assertEquals(200.0, moved.courseDeg(), 0.01)
    }

    @Test
    fun `75 percent lane uses accelHigh`() {
        // 当前航速 > 最大航速×75% → 用 accelHigh 列（快速A: 3 节）
        val u = polarUnit("S001", 30.0, 31.0, "BB")
        u.setSpeed(26.0)   // 26 > 31×0.75=23.25 → 75-100% 档
        assertTrue(MovementEngine.accelHighLane(u))
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", boost = true)
        ))
        // 26 + 3(accelHigh) = 29
        assertEquals(29.0, f.units.first().speedKnots(), 0.01)
    }

    @Test
    fun `new speed unreachable clamps to reachable`() {
        // 具体航速写法：12节 B级 直行，指定 25 节但加速能力仅 10 → 可达 22
        val u = polarUnit("S001", 30.0, 33.0, "CA")
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newSpeed = 25.0)
        ))
        assertEquals(22.0, f.units.first().speedKnots(), 0.01)
    }
}

class ReplayTest {
    private fun unit(id: String, x: Long, y: Long, side: String = "Blue"): Unit {
        return Unit().apply { this.idNum = id; this.side = side; this.x = x; this.y = y }
    }

    private fun scenario(units: List<Unit>): ScenarioFile {
        return ScenarioFile().apply {
            scenario = Scenario(scenarioName = "回放测试")
            time = TimeState(
                currentTurnTime = "2026-01-01 00:00:00",
                currentPositionTime = "2026-01-01 00:06:00",
                currentTurnInterval = TurnInterval(3, 0)
            )
            this.units = units.toMutableList()
        }
    }

    @Test
    fun `timeline built from waypoint times`() {
        val u = unit("S001", 60000, 0)
        // 两个轨迹点：00:00 起点 (0,0)，00:03 转向点 (30000,0)，当前位置 00:06 (60000,0)
        u.pastWaypointArray.add(com.simplot.android.data.model.Waypoint(x = 0, y = 0, positionTime = "2026-01-01 00:00:00"))
        u.pastWaypointArray.add(com.simplot.android.data.model.Waypoint(x = 30000, y = 0, positionTime = "2026-01-01 00:03:00"))
        val f = scenario(listOf(u))
        val tl = ReplayEngine.buildTimeline(f)
        assertEquals(3, tl.size)
        assertEquals("2026-01-01 00:00:00", tl[0].time)
        assertEquals(0L, tl[0].positions["S001"]!!.x)
        assertEquals("2026-01-01 00:03:00", tl[1].time)
        assertEquals(30000L, tl[1].positions["S001"]!!.x)
        assertEquals("2026-01-01 00:06:00", tl[2].time)
        assertEquals(60000L, tl[2].positions["S001"]!!.x)
    }

    @Test
    fun `timeline empty when no tracks`() {
        val f = scenario(listOf(unit("S001", 0, 0)))
        val tl = ReplayEngine.buildTimeline(f)
        // 无轨迹但单位存在 → 至少一帧（当前位置时间）
        assertEquals(1, tl.size)
        assertEquals(0L, tl[0].positions["S001"]!!.x)
    }

    // ============ 高度/深度引擎（桌面版 ChangeAltitude/ChangeDepth，A1 修复） ============

    /** 飞机：指定高度（米）+ 未来航路点目标高度/速率 */
    private fun airUnit(id: String, altitudeM: Int, wpTargetM: Int, ascent: Int = 0, descent: Int = 0): Unit {
        return Unit().apply {
            this.idNum = id
            side = "Blue"
            name = id
            this.altitude = altitudeM
            if (wpTargetM != 0 || ascent != 0 || descent != 0) {
                futureWaypointArray.add(
                    com.simplot.android.data.model.Waypoint(
                        x = 0, y = 0, altitudeDepth = altitudeM,
                        assignedAltDepth = wpTargetM, ascent = ascent, descent = descent
                    )
                )
            }
        }
    }

    /** 潜艇：指定深度（米） */
    private fun subUnit(id: String, depthM: Int, wpTargetM: Int, ascent: Int = 0, descent: Int = 0): Unit {
        return Unit().apply {
            this.idNum = id
            side = "Blue"
            name = id
            this.depth = depthM
            if (wpTargetM != 0 || ascent != 0 || descent != 0) {
                futureWaypointArray.add(
                    com.simplot.android.data.model.Waypoint(
                        x = 0, y = 0, altitudeDepth = depthM,
                        assignedAltDepth = wpTargetM, ascent = ascent, descent = descent
                    )
                )
            }
        }
    }

    @Test
    fun `altitude climbs toward waypoint target at ascent rate`() {
        // 当前 1000 米 → 目标 3000 米，爬升速率 500 米/回合
        val f = scenario(listOf(airUnit("A001", 1000, 3000, ascent = 500)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(1500, f.units[0].altitude)
    }

    @Test
    fun `altitude descends toward waypoint target at descent rate`() {
        // 当前 3000 米 → 目标 1000 米，下降速率 800 米/回合
        val f = scenario(listOf(airUnit("A001", 3000, 1000, descent = 800)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(2200, f.units[0].altitude)
    }

    @Test
    fun `altitude does not overshoot target`() {
        // 当前 1000 → 目标 1200，速率 500 → 一步到位 1200
        val f = scenario(listOf(airUnit("A001", 1000, 1200, ascent = 500)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(1200, f.units[0].altitude)
    }

    @Test
    fun `altitude unchanged when rate zero`() {
        // 速率为 0 → 不调整（与桌面版 Ascent/Descent 语义一致）
        val f = scenario(listOf(airUnit("A001", 1000, 3000)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(1000, f.units[0].altitude)
    }

    @Test
    fun `altitude is plain meters not scaled`() {
        // A1 修复核心：Altitude 存档单位 = 实际米（无 ×1000 定点）
        val u = Unit().apply { altitude = 10000; depth = 200 }
        assertEquals(10000, u.altitudeMeters())
        assertEquals(200, u.depthMeters())
        // 序列化后应保持原值（无 ×1000）
        val json = com.simplot.android.data.codec.JsonUtil.gson.toJson(u)
        assertTrue(json.contains("\"Altitude\":10000"))
        assertTrue(json.contains("\"Depth\":200"))
    }

    @Test
    fun `depth ascends toward waypoint target`() {
        // 当前 300 米 → 目标 100 米（上浮），上浮速率 100 米/回合
        val f = scenario(listOf(subUnit("U001", 300, 100, ascent = 100)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(200, f.units[0].depth)
    }

    // ============ 编队移动（桌面版 MoveCompassFormation，A2 修复） ============

    @Test
    fun `formation member positioned at bearing distance from center`() {
        // 中心 (0,0)，成员在 90° 方位（正东）、距离 1 海里（文件单位 100000）
        val center = unit("S001", 0, 0).apply { isFormationCenter = true; formationName = "Convoy" }
        val member = unit("S002", 0, 0).apply {
            isInFormation = true
            formationName = "Convoy"
            formationBearing = 90000      // 90°
            formationDistance = 100000    // 1 海里 = 文件单位
        }
        val f = scenario(listOf(center, member))
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 90° → dx=dist×sin(90°)=100000, dy=dist×cos(90°)=0 → 新位置 (100000, 0)
        assertEquals(100000L, f.units[1].x)
        assertEquals(0L, f.units[1].y)
    }

    @Test
    fun `formation distance is file units not nautical miles`() {
        // A2 核心：formationDistance 直接是文件单位（×100000 海里定点），不能乘 NMI_SCALE
        val center = unit("S001", 0, 0).apply { isFormationCenter = true; formationName = "Convoy" }
        val member = unit("S002", 0, 0).apply {
            isInFormation = true
            formationName = "Convoy"
            formationBearing = 0          // 正北
            formationDistance = 100000    // 1 海里
        }
        val f = scenario(listOf(center, member))
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 0° → dx=0, dy=dist×cos(0°)=100000 → 新位置 (0, 100000)（北 1 海里，而不是 100000 海里外）
        assertEquals(0L, f.units[1].x)
        assertEquals(100000L, f.units[1].y)
        assertTrue(f.units[1].x < 1_000_000L)   // 没有甩到 2000 海里外
    }

    // ============ Range 耗尽三选（桌面版 HasRangeRemaining，C3 修复） ============

    /** 有限航程单位：12 节，Range=10 海里 */
    private fun rangedUnit(id: String, rangeNm: Int, ignore: Boolean = false): Unit {
        return unit(id, 0, 0).apply {
            setSpeed(12.0)
            setCourse(90.0)
            range = rangeNm
            ignoreRange = ignore
        }
    }

    @Test
    fun `range decreases with movement until exhausted`() {
        // 12 节 × 3 分钟 = 0.6 海里/回合；Range 10 → 每回合扣 1（至少 1 海里），移动 0.6 海里
        val f = scenario(listOf(rangedUnit("S001", 10)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(9, f.units[0].range)
        assertEquals(60000L, f.units[0].x)   // 0.6 海里 × 100000
    }

    @Test
    fun `range exhausted stops movement`() {
        // Range 0.5 海里，12 节×3 分钟 = 0.6 → 本回合耗尽，只走 0.5
        val f = scenario(listOf(rangedUnit("S001", 0)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(0, f.units[0].range)
        assertEquals(0L, f.units[0].x)   // Range 0 → 不移动
    }

    @Test
    fun `ignoreRange continues movement after exhaustion`() {
        // C3 核心：选"继续移动"后 Range=0 仍正常航行（无视 Range 限制）
        val u = rangedUnit("S001", 0, ignore = true)
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 12 节 × 3 分钟 = 0.6 海里 = 60000 文件单位
        assertEquals(60000L, f.units[0].x)
        assertEquals(0, f.units[0].range)   // Range 保持 0
    }
}
