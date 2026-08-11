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

    // ============ D9 桌面化回归（2026-08-10：无转向损失/无前冲/无加速档，匀速直航） ============

    private fun polarUnit(id: String, course: Double, maxSpeed: Double, unitClass: String = "CL"): Unit {
        return surfaceUnit(id, "Blue", 0, 0, 12.0, course).apply {
            this.maxSpeedKnots = maxSpeed
            this.unitClass = unitClass
        }
    }

    private fun levelClass(unitClass: String, maxSpeed: Double): String =
        com.simplot.android.engine.SizeLevels.levelName(unitClass, maxSpeed)

    @Test
    fun `d9 course change applies directly no turn loss`() {
        // D9：转向直接生效（无前冲/无 45° 分段/无转向损失）；12 节右转 60° → 沿新航向匀速直行
        val u = polarUnit("S001", 90.0, 31.0, "BB")
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newCourse = 150.0)
        ))
        val moved = f.units.first()
        assertEquals(12.0, moved.speedKnots(), 0.01)   // 无转向损失
        assertEquals(150.0, moved.courseDeg(), 0.01)   // 新航向立即生效
        // 3 分钟 12 节 = 0.6 海里，沿 150°
        val dist = kotlin.math.hypot(moved.x.toDouble() / 100000.0, moved.y.toDouble() / 100000.0)
        assertEquals(0.6, dist, 0.001)
    }

    @Test
    fun `d9 speed set directly no accel cap`() {
        // D9：具体航速直接设定（无"可达性 clamp"）；12 节 → 25 节，本回合按 25 节移动
        val u = polarUnit("S001", 30.0, 33.0, "CA")
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newSpeed = 25.0)
        ))
        assertEquals(25.0, f.units.first().speedKnots(), 0.01)
    }

    @Test
    fun `d9 boost adds one accel level no turn penalty`() {
        // D9：boost = 原速 + 尺寸级 accel（无 75% 分档、无转向损失）
        val u = polarUnit("S001", 30.0, 31.0, "BB")
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newCourse = 260.0, boost = true)
        ))
        // fastA accel=6 → 12+6=18；转向无损失
        assertEquals(18.0, f.units.first().speedKnots(), 0.01)
        assertEquals(260.0, f.units.first().courseDeg(), 0.01)
    }

    @Test
    fun `d9 zero distance does not turn`() {
        // D9：0 距离（停船）时不移动也不转向（与桌面 Move MaxDistanceToMove==0 直接返回一致）
        val u = polarUnit("S001", 90.0, 31.0, "BB")
        u.setSpeed(0.0)
        val f = scenario(listOf(u))
        MovementEngine.advance(f, TurnInterval(3, 0), mapOf(
            "S001" to MovementEngine.UnitMove("S001", newCourse = 170.0)
        ))
        assertEquals(0.0, f.units.first().speedKnots(), 0.01)
        assertEquals(90.0, f.units.first().courseDeg(), 0.01)   // 不转向
        assertEquals(0L, f.units.first().x)
        assertEquals(0L, f.units.first().y)
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

    // ============ 高度/深度引擎（桌面版 ChangeAltitude/ChangeDepth）============
    // 2026-08-11 修正：Altitude/Depth/AssignedAltDepth/速率均为 ×1000 定点（red 存档实测 3000000=3000m）

    /** 飞机：指定高度（米）+ 未来航路点目标高度/速率（米）——内部 ×1000 */
    private fun airUnit(id: String, altitudeM: Int, wpTargetM: Int, ascentM: Int = 0, descentM: Int = 0): Unit {
        return Unit().apply {
            this.idNum = id
            side = "Blue"
            name = id
            setAltitude(altitudeM)
            if (wpTargetM != 0 || ascentM != 0 || descentM != 0) {
                futureWaypointArray.add(
                    com.simplot.android.data.model.Waypoint(
                        x = 0, y = 0, altitudeDepth = altitudeM * 1000,
                        assignedAltDepth = wpTargetM * 1000, ascent = ascentM * 1000, descent = descentM * 1000
                    )
                )
            }
        }
    }

    /** 潜艇：指定深度（米） */
    private fun subUnit(id: String, depthM: Int, wpTargetM: Int, ascentM: Int = 0, descentM: Int = 0): Unit {
        return Unit().apply {
            this.idNum = id
            side = "Blue"
            name = id
            setDepth(depthM)
            if (wpTargetM != 0 || ascentM != 0 || descentM != 0) {
                futureWaypointArray.add(
                    com.simplot.android.data.model.Waypoint(
                        x = 0, y = 0, altitudeDepth = depthM * 1000,
                        assignedAltDepth = wpTargetM * 1000, ascent = ascentM * 1000, descent = descentM * 1000
                    )
                )
            }
        }
    }

    @Test
    fun `altitude climbs toward waypoint target at ascent rate`() {
        // 当前 1000 米 → 目标 3000 米，爬升速率 500 米/回合
        // E3：单回合变化上限 180 米 → 1000+180=1180
        val f = scenario(listOf(airUnit("A001", 1000, 3000, ascentM = 500)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(1180, f.units[0].altitudeMeters())
    }

    @Test
    fun `altitude descends toward waypoint target at descent rate`() {
        // 当前 3000 米 → 目标 1000 米，下降速率 800 米/回合
        // E3：单回合变化上限 180 米 → 3000−180=2820
        val f = scenario(listOf(airUnit("A001", 3000, 1000, descentM = 800)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(2820, f.units[0].altitudeMeters())
    }

    @Test
    fun `altitude does not overshoot target`() {
        // 当前 1000 → 目标 1200，速率 500 米/回合 → E3 单回合上限 180 → 1180（未超目标）
        val f = scenario(listOf(airUnit("A001", 1000, 1200, ascentM = 500)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(1180, f.units[0].altitudeMeters())
    }

    @Test
    fun `altitude unchanged when rate zero`() {
        // 速率为 0 → 不调整（与桌面版 Ascent/Descent 语义一致）
        val f = scenario(listOf(airUnit("A001", 1000, 3000)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(1000, f.units[0].altitudeMeters())
    }

    @Test
    fun `altitude is meters times 1000 fixed point`() {
        // 2026-08-11 修正：Altitude 存档 = 米 ×1000 定点（red 存档实测 3000000=3000m）
        val u = Unit().apply { setAltitude(3000); setDepth(200) }
        assertEquals(3000000, u.altitude)
        assertEquals(3000, u.altitudeMeters())
        assertEquals(200, u.depthMeters())
        // 序列化保持 ×1000（与桌面字节一致）
        val json = com.simplot.android.data.codec.JsonUtil.gson.toJson(u)
        assertTrue(json.contains("\"Altitude\":3000000"))
        assertTrue(json.contains("\"Depth\":200000"))
    }

    @Test
    fun `depth ascends toward waypoint target`() {
        // 当前 300 米 → 目标 100 米（上浮），上浮速率 100 米/回合
        val f = scenario(listOf(subUnit("U001", 300, 100, ascentM = 100)))
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(200, f.units[0].depthMeters())
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

    @Test
    fun `formation course mode adds center heading to bearing`() {
        // RelativeToCourse：bearing 相对编队航向；中心航向 90°（正东），成员 bearing 0° → 实际 90°
        val center = unit("S001", 0, 0).apply {
            isFormationCenter = true; formationName = "TF"
            formationType = "RelativeToCourse"
            setCourse(90.0)
        }
        val member = unit("S002", 0, 0).apply {
            isInFormation = true; formationName = "TF"
            formationBearing = 0; formationDistance = 100000
        }
        val f = scenario(listOf(center, member))
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 90° → dx=100000, dy≈0
        assertEquals(100000L, f.units[1].x)
        assertTrue(kotlin.math.abs(f.units[1].y) < 100L)
    }

    @Test
    fun `formation column mode lines up behind center`() {
        // Column：成员排在中心正后方（航向反方向）；中心航向 0°（正北）→ 成员在正南
        val center = unit("S001", 0, 0).apply {
            isFormationCenter = true; formationName = "Col"
            formationType = "Column"
            setCourse(0.0)
        }
        val m1 = unit("S002", 0, 0).apply {
            isInFormation = true; formationName = "Col"
            formationDistance = 100000
        }
        val m2 = unit("S003", 0, 0).apply {
            isInFormation = true; formationName = "Col"
            formationDistance = 100000
        }
        val f = scenario(listOf(center, m1, m2))
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 航向 0° 反向 = 180°（正南）：m1 在 (0,-100000)，m2 在 (0,-200000)
        assertEquals(0L, f.units[1].x)
        assertEquals(-100000L, f.units[1].y)
        assertEquals(0L, f.units[2].x)
        assertEquals(-200000L, f.units[2].y)
    }
}
