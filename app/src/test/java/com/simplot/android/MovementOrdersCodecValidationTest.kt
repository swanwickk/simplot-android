package com.simplot.android

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.MovementOrdersCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G56：运动命令导入合并校验测试（Number 连续性 / 关键键缺失 / 合并语义）。
 */
class MovementOrdersCodecValidationTest {

    private fun wp(number: Int, x: Long = 100000L) =
        Waypoint(x = x, y = 200000, speed = 30000, course = 90000, number = number)

    /** 构造 Movement Orders JSON（Waypoint 走标准 12 键序列化） */
    private fun ordersJson(wps: List<Waypoint>, idNum: String = "S001"): String {
        val root = JsonObject()
        root.addProperty("File", "Movement Orders")
        val arr = JsonArray()
        val o = JsonObject()
        o.addProperty("IdNum", idNum)
        val wpsArr = JsonArray()
        wps.forEach { wpsArr.add(JsonUtil.gson.toJsonTree(it)) }
        o.add("Waypoints", wpsArr)
        arr.add(o)
        root.add("Units", arr)
        return JsonUtil.gson.toJson(root)
    }

    @Test
    fun `valid contiguous numbering imports`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001")))
        val n = MovementOrdersCodec.applyTo(f, ordersJson(listOf(wp(1), wp(2))))
        assertEquals(1, n)
        assertEquals(2, f.units[0].futureWaypointArray.size)
        assertEquals(1, f.units[0].futureWaypointArray[0].number)
    }

    @Test
    fun `gapped numbering rejected before any import`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001")))
        val e = assertThrows(IllegalArgumentException::class.java) {
            MovementOrdersCodec.applyTo(f, ordersJson(listOf(wp(1), wp(3))))
        }
        assertTrue(e.message!!.contains("Number"))
        // 拒绝后场景未发生任何写入（无半导入状态）
        assertEquals(0, f.units[0].futureWaypointArray.size)
    }

    @Test
    fun `waypoint missing numeric key rejected`() {
        // 去掉标准 Waypoint JSON 的 X 键 → 模拟跨版本/手工文件缺键
        val wpObj = JsonUtil.gson.toJsonTree(wp(1)).asJsonObject
        wpObj.remove("X")
        val root = JsonObject()
        root.addProperty("File", "Movement Orders")
        val arr = JsonArray()
        val o = JsonObject()
        o.addProperty("IdNum", "S001")
        val wpsArr = JsonArray()
        wpsArr.add(wpObj)
        o.add("Waypoints", wpsArr)
        arr.add(o)
        root.add("Units", arr)
        val e = assertThrows(IllegalArgumentException::class.java) {
            MovementOrdersCodec.parse(JsonUtil.gson.toJson(root))
        }
        assertTrue(e.message!!.contains("X"))
    }

    @Test
    fun `merge semantics keep unmatched units untouched`() {
        // 场景只有 S002，导入文件只含 S001 → 不匹配，场景单位航路点保持不变
        val f = ScenarioFile(units = mutableListOf(
            Unit(idNum = "S002", futureWaypointArray = mutableListOf(wp(1)))
        ))
        val n = MovementOrdersCodec.applyTo(f, ordersJson(listOf(wp(1), wp(2))))
        assertEquals(0, n)
        assertEquals(1, f.units[0].futureWaypointArray.size)
    }

    @Test
    fun `empty waypoint list is valid`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001")))
        val n = MovementOrdersCodec.applyTo(f, ordersJson(emptyList()))
        assertEquals(1, n)
        assertEquals(0, f.units[0].futureWaypointArray.size)
    }
}
