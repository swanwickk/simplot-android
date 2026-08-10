package com.simplot.android

import com.simplot.android.data.codec.MovementOrdersCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运动命令编解码测试（R3：导入导出往返）。
 */
class MovementOrdersCodecTest {

    private fun scenario(): ScenarioFile {
        val u = Unit(idNum = "S001", side = "Blue", name = "DD-1", unitClass = "DD",
            futureWaypointArray = mutableListOf(
                Waypoint(x = 100000, y = 200000, speed = 30000, course = 90000, number = 1),
                Waypoint(x = 300000, y = 0, speed = 25000, course = 45000, number = 2)
            ))
        return ScenarioFile(units = mutableListOf(u))
    }

    @Test
    fun `export then import restores waypoints`() {
        val f = scenario()
        val json = MovementOrdersCodec.toJson(f.units)
        assertTrue(json.contains("\"Movement Orders\""))
        // 导入到另一个同 IdNum 单位
        val target = ScenarioFile(units = mutableListOf(
            Unit(idNum = "S001", side = "Blue", name = "DD-1", unitClass = "DD")
        ))
        val count = MovementOrdersCodec.applyTo(target, json)
        assertEquals(1, count)
        assertEquals(2, target.units[0].futureWaypointArray.size)
        assertEquals(100000, target.units[0].futureWaypointArray[0].x)
        assertEquals(30000, target.units[0].futureWaypointArray[0].speed)
    }

    @Test
    fun `parse ignores unknown idnums`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S002")))
        val json = MovementOrdersCodec.toJson(scenario().units)  // S001
        assertEquals(0, MovementOrdersCodec.applyTo(f, json))    // 无匹配
    }

    @Test
    fun `round trip preserves waypoint fields`() {
        val f = scenario()
        val json = MovementOrdersCodec.toJson(f.units)
        val parsed = MovementOrdersCodec.parse(json)
        val wps = parsed["S001"]!!
        assertEquals(2, wps.size)
        assertEquals(45000, wps[1].course)
        assertEquals(2, wps[1].number)
    }
}
