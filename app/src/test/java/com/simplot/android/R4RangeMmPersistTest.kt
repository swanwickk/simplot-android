package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.MovementEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R4 毫米海里余额跨存盘：RangeMm 持久化 + 回填 + 引擎全链路 */
class R4RangeMmPersistTest {

    private fun unit(range: Int, rangeMm: Long? = null): Unit = Unit().apply {
        idNum = "S001"; side = "Blue"; name = "S001"
        setSpeed(12.0); setCourse(90.0)
        this.range = range; this.rangeMm = rangeMm
        if (rangeMm != null) initRangeMmFromPersisted() else if (range != -100000) rangeNmMm = -1L
    }

    private fun scenario(u: Unit): ScenarioFile = ScenarioFile().apply {
        scenario = Scenario(scenarioName = "R4"); time = TimeState("2026-01-01 00:00:00","2026-01-01 00:00:00", TurnInterval(3,0))
        units = mutableListOf(u); objects = mutableListOf(u.idNum)
    }

    @Test
    fun `advance then serialize preserves fraction via RangeMm`() {
        val u = unit(1)
        val f = scenario(u)
        // 12节*3min=0.6，1海里分两回合（0.6+0.4），第一回合后整海里为0但毫米余额400
        MovementEngine.advance(f, TurnInterval(3, 0))
        assertEquals(0, f.units[0].range)
        assertEquals(400L, f.units[0].rangeMm)
        assertEquals(400L, f.units[0].rangeNmMm)
        // 存盘往返（JsonUtil.fromJson 回填镜像）
        val json = JsonUtil.toCompactJson(f)
        assertTrue(json.contains("\"RangeMm\":400"))
        val f2 = JsonUtil.fromJson(json)
        assertEquals(400L, f2.units[0].rangeMm)
        assertEquals(400L, f2.units[0].rangeNmMm)
        assertEquals(0.4, f2.units[0].effectiveRangeNm(), 0.001)
        // 第二回合应恰好走0.4并耗尽
        MovementEngine.advance(f2, TurnInterval(3, 0))
        assertEquals(0, f2.units[0].range)
        assertEquals(0L, f2.units[0].rangeMm)
    }

    @Test
    fun `old save without RangeMm falls back to Range integer`() {
        val json = """{"File":"Referee","SimPlot Version":"2.3","IsIntegerFile":true,"Scenario":{"ScenarioName":"t","LastId":1,"CurrentTrackNumber":2400,"CurrentPlayerTrackNumber":9000,"Phase":0,"TypeOfMap":0},"TypeOfGame":0,"Time":{"CurrentTurnTime":"2026-01-01 00:00:00","CurrentPositionTime":"2026-01-01 00:00:00","CurrentTurnInterval":{"Minutes":3,"Seconds":0}},"Turns":[],"Overlays":{},"Objects":["S001"],"Units":[{"IdNum":"S001","Side":"Blue","TrackNumber":2401,"Name":"S001","Number":1,"UnitClass":"CL","UnitType":"Cruiser","X":0,"Y":0,"ShowSunk":false,"IsActiveRadar":false,"IsActiveSonar":false,"PositionTimeCreated":"2026-01-01 00:00:00","PositionTimeDeleted":"2020-01-01 00:00:00","Speed":12000,"Course":90000,"Range":2,"WpDistance":0,"PastWaypointArray":{},"FutureWaypointArray":{},"TextTags":{"TagAltitude":false,"TagCallsign":false,"TagClass":false,"TagCourseSpeed":true,"TagDepth":false,"TagName":false,"TagTrackNum":false,"TagUnitType":false,"AdditionalText":""}}],"Formations":{}}"""
        val f = JsonUtil.fromJson(json)
        // 旧存档无 RangeMm：持久化键为 null，回填后运行时镜像为 2000
        assertEquals(null, f.units[0].rangeMm)
        assertEquals(2000L, f.units[0].rangeNmMm)
    }

    @Test
    fun `ignoreRange still bypasses RangeMm`() {
        val u = unit(0).apply { ignoreRange = true }
        val f = scenario(u)
        MovementEngine.advance(f, TurnInterval(3, 0))
        // 忽略 Range 时不扣减，RangeMm 保持 null（未初始化即不落盘）
        assertEquals(0, f.units[0].range)
    }

    @Test
    fun `manualMove plan and step consistent with RangeMm`() {
        val u = unit(1).apply { rangeNmMm = 1000L; range = 1; rangeMm = 1000L }
        // plan 不应改余额，仅计算
        val plan = MovementEngine.planManualMove(u, 3.0, 1.0)
        assertEquals(1000L, u.rangeNmMm) // plan 不改
        assertTrue(plan != null)
        MovementEngine.manualMoveStep(u, 3.0, 1.0, "2026-01-01 00:00:00")
        assertEquals(400L, u.rangeNmMm)
        assertEquals(0, u.range)
        assertEquals(400L, u.rangeMm)
    }
}
