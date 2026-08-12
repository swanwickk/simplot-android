package com.simplot.android

import com.simplot.android.data.codec.UnitFileCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G28：单位级导入导出测试（单单位 JSON 往返 / 替换 / 新增 / 非法文件拒绝）。
 */
class UnitFileCodecTest {

    private fun sampleUnit(): Unit = Unit(
        idNum = "S001", side = "Blue", name = "DD-1", unitClass = "DD", unitType = "Destroyer",
        trackNumber = 2401, x = 123456, y = -789, speed = 30000, course = 90000,
        futureWaypointArray = mutableListOf(
            Waypoint(x = 100000, y = 200000, speed = 30000, course = 90000, number = 1)
        )
    )

    @Test
    fun `round trip preserves unit fields`() {
        val u = sampleUnit()
        val json = UnitFileCodec.toJson(u)
        // 单单位文件 = 单位 JSON 原样（含 IdNum 顶层键，与场景 Units 元素同构）
        assertTrue(json.contains("\"IdNum\":\"S001\""))
        val back = UnitFileCodec.fromJson(json)
        assertEquals(u.idNum, back.idNum)
        assertEquals(u.name, back.name)
        assertEquals(u.side, back.side)
        assertEquals(u.unitType, back.unitType)
        assertEquals(u.trackNumber, back.trackNumber)
        assertEquals(u.x, back.x)
        assertEquals(u.y, back.y)
        assertEquals(u.speed, back.speed)
        assertEquals(u.course, back.course)
        assertEquals(1, back.futureWaypointArray.size)
        assertEquals(100000, back.futureWaypointArray[0].x)
        assertEquals(1, back.futureWaypointArray[0].number)
    }

    @Test
    fun `import replaces existing idnum in place`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", name = "OLD")))
        val replaced = UnitFileCodec.importInto(f, Unit(idNum = "S001", name = "NEW"))
        assertTrue(replaced)
        assertEquals(1, f.units.size)
        assertEquals("NEW", f.units[0].name)
    }

    @Test
    fun `import adds new idnum`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001")))
        val replaced = UnitFileCodec.importInto(f, Unit(idNum = "A002", name = "PLANE"))
        assertFalse(replaced)
        assertEquals(2, f.units.size)
        assertEquals("PLANE", f.units[1].name)
        assertEquals("A002", f.units[1].idNum)
    }

    @Test
    fun `parse rejects non unit json`() {
        assertThrows(IllegalArgumentException::class.java) { UnitFileCodec.fromJson("""{"Foo":1}""") }
        assertThrows(IllegalArgumentException::class.java) { UnitFileCodec.fromJson("[1,2]") }
        assertThrows(IllegalArgumentException::class.java) { UnitFileCodec.fromJson("not json") }
    }
}
