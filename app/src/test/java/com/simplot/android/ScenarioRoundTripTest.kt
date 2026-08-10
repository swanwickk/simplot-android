package com.simplot.android

import com.google.gson.JsonParser
import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面版真实存档兼容性测试。
 *
 * fixture 来自官方 SimPlot 2.3.9 场景「Iron Bottom Sound」的 Referee 存档
 * （harpgamer file/1020，2026-08-08 下载）。
 * 这类测试是之前审阅中缺失的关键一环——旧代码顶层键大小写错误、
 * TurnInterval 键名错误、Waypoint 结构错误都会在此暴露。
 */
class ScenarioRoundTripTest {

    private fun fixtureText(): String {
        val stream = javaClass.getResourceAsStream("/scenarios/IronBottomSound_Referee.json")
            ?: throw AssertionError("缺少 fixture: IronBottomSound_Referee.json")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `parse real desktop scenario`() {
        val f = JsonUtil.fromJson(fixtureText())

        // 顶层
        assertEquals("Referee", f.file)
        assertEquals("2.3", f.simPlotVersion)
        assertTrue(f.isIntegerFile)
        assertEquals("Iron Bottom Sound", f.scenario.scenarioName)
        assertEquals(16, f.scenario.lastId)
        assertEquals(0, f.scenario.phase)
        assertEquals(3, f.scenario.typeOfMap)
        assertEquals("Iron Bottom Sound JJWS1.json", f.scenario.mapFileName)

        // 时间（关键：TurnInterval 键名 Minutes/Seconds 大写）
        assertEquals("1942-10-01 00:00:00", f.time.currentTurnTime)
        assertEquals("1942-10-01 00:00:00", f.time.currentPositionTime)
        assertEquals(3, f.time.currentTurnInterval.minutes)
        assertEquals(0, f.time.currentTurnInterval.seconds)

        // 回合历史
        assertEquals(1, f.turns.size)
        assertEquals("1942-10-01 00:00:00", f.turns[0].turnTime)
        assertEquals(3, f.turns[0].turnInterval.minutes)

        // 单位
        assertEquals(16, f.units.size)
        assertEquals("L001", f.units[0].idNum)
        assertEquals("Neutral", f.units[0].side)
        assertEquals(-400000L, f.units[0].x)
        assertEquals(400000L, f.units[0].y)

        // 传感器/武器
        val cruiser = f.units.first { it.idNum == "S004" }
        assertNotNull(cruiser.sensorArray)
        assertTrue(cruiser.sensorArray!!.isNotEmpty())
        assertEquals(18.0, cruiser.sensorArray!![0].maxRange, 0.001)
        assertEquals("&h00FFFF00", cruiser.sensorArray!![0].arcColor)
        assertNotNull(cruiser.weaponArray)
        assertTrue(cruiser.weaponArray!!.isNotEmpty())
        assertEquals(32.0, cruiser.weaponArray!![0].maxRange, 0.001)
    }

    @Test
    fun `waypoint empty object maps to empty list`() {
        val f = JsonUtil.fromJson(fixtureText())
        // 未移动单位 S004 PastWaypointArray 为 {} → 解析为空列表
        val s004 = f.units.first { it.idNum == "S004" }
        assertTrue(s004.pastWaypointArray.isEmpty())
        assertTrue(s004.futureWaypointArray.isEmpty())
    }

    @Test
    fun `waypoint missing field maps to empty list`() {
        val f = JsonUtil.fromJson(fixtureText())
        // I002 无 PastWaypointArray 字段（null）→ 解析为空列表
        val i002 = f.units.first { it.idNum == "I002" }
        assertTrue(i002.pastWaypointArray.isEmpty())
    }

    @Test
    fun `serialize then parse roundtrip preserves data`() {
        val f = JsonUtil.fromJson(fixtureText())
        val json = JsonUtil.toCompactJson(f)
        val f2 = JsonUtil.fromJson(json)

        assertEquals(f.file, f2.file)
        assertEquals(f.scenario.scenarioName, f2.scenario.scenarioName)
        assertEquals(f.time.currentTurnTime, f2.time.currentTurnTime)
        assertEquals(f.time.currentTurnInterval.minutes, f2.time.currentTurnInterval.minutes)
        assertEquals(f.units.size, f2.units.size)
        assertEquals(f.units[0].x, f2.units[0].x)
        assertEquals(f.units.first { it.idNum == "S004" }.sensorArray!!.size,
            f2.units.first { it.idNum == "S004" }.sensorArray!!.size)
    }

    @Test
    fun `serialized json uses desktop key names`() {
        val f = JsonUtil.fromJson(fixtureText())
        val json = JsonUtil.toCompactJson(f)
        val el = JsonParser.parseString(json).asJsonObject

        // 顶层键
        assertTrue(el.has("File"))
        assertTrue(el.has("SimPlot Version"))
        assertTrue(el.has("IsIntegerFile"))
        assertTrue(el.has("Scenario"))
        assertTrue(el.has("TypeOfGame"))
        assertTrue(el.has("Time"))
        assertTrue(el.has("Turns"))
        assertTrue(el.has("Objects"))
        assertTrue(el.has("Units"))
        assertTrue(el.has("Formations"))

        // TurnInterval 大写键
        val interval = el.getAsJsonObject("Time").getAsJsonObject("CurrentTurnInterval")
        assertTrue(interval.has("Minutes"))
        assertTrue(interval.has("Seconds"))

        // 单位键
        val u0 = el.getAsJsonArray("Units")[0].asJsonObject
        assertTrue(u0.has("IdNum"))
        assertTrue(u0.has("Side"))
        assertTrue(u0.has("PastWaypointArray"))
        assertFalse(u0.has("PastWaypointArray1"))   // 旧键名必须消失
        assertTrue(u0.has("WpDistance"))            // 用户原始存档含 WpDistance（2026-08-10 裁定保留）
        assertTrue(u0.has("TextTags"))
    }

    @Test
    fun `empty waypoint serializes as empty object not array`() {
        val f = JsonUtil.fromJson(fixtureText())
        val json = JsonUtil.toCompactJson(f)
        val el = JsonParser.parseString(json).asJsonObject
        // 找 S004（无轨迹）→ 序列化后应为 {}（桌面版兼容），而不是 []
        val units = el.getAsJsonArray("Units")
        val s004 = (0 until units.size())
            .map { units.get(it).asJsonObject }
            .first { it.get("IdNum").asString == "S004" }
        assertTrue(s004.getAsJsonObject("PastWaypointArray").size() == 0)
        assertTrue(s004.getAsJsonObject("FutureWaypointArray").size() == 0)
    }

    @Test
    fun `movement waypoint uses desktop object structure`() {
        // 引擎写入的 Waypoint 序列化后必须是对象结构
        val w = Waypoint(x = 100L, y = 200L, altitudeDepth = 0, number = 1, isTurnTime = true, positionTime = "1942-10-01 00:00:00")
        val json = JsonUtil.gson.toJson(w)
        val el = JsonParser.parseString(json).asJsonObject
        assertEquals(100L, el.get("X").asLong)
        assertEquals(200L, el.get("Y").asLong)
        assertTrue(el.has("IsTurnTime"))
        assertTrue(el.has("PositionTime"))
        assertTrue(el.has("AltitudeDepth"))
    }
}
