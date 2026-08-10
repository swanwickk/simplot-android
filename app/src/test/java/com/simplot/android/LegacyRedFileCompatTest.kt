package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧存档容错回归测试（真机反馈：读取旧 Red 文件抛 IllegalStateException）。
 *
 * 桌面版旧存档把空数组写为 {}（空对象）、空 Map 写为 []（空数组），
 * 当前 Gson 需容忍这些结构，否则 "Expected BEGIN_ARRAY but was BEGIN_OBJECT"。
 */
class LegacyRedFileCompatTest {

    /** 模拟旧版 Red.SpScn 明文结构：Objects={}、SensorArray={}、PerceptionArray={}、Formations=[]、Overlays=[] */
    private val legacyRedJson = """
    {
      "File": "Red",
      "SimPlot Version": "2.3",
      "IsIntegerFile": true,
      "Scenario": {"ScenarioName": "Legacy", "LastId": 1, "CurrentTrackNumber": 2400, "CurrentPlayerTrackNumber": 9000, "Phase": 0, "TypeOfMap": 0, "MapFileName": ""},
      "TypeOfGame": 0,
      "Time": {"CurrentTurnTime": "1942-10-01 00:00:00", "CurrentPositionTime": "1942-10-01 00:00:00", "CurrentTurnInterval": {"Minutes": 3, "Seconds": 0}},
      "Turns": {},
      "Overlays": [],
      "Objects": {},
      "Units": [
        {
          "IdNum": "S001", "Side": "Red", "TrackNumber": 2401, "Name": "CC-1",
          "Number": 1, "UnitClass": "CC", "UnitType": "Cruiser",
          "X": 100, "Y": 200, "ShowSunk": false, "Speed": 20000, "Course": 90000, "Range": -100000,
          "PastWaypointArray": {}, "FutureWaypointArray": {},
          "TextTags": {"TagName": false, "TagCourseSpeed": true},
          "SensorArray": {}, "WeaponArray": {}, "PerceptionArray": {}
        }
      ],
      "Formations": []
    }
    """.trimIndent()

    @Test
    fun `legacy red json parses without exception`() {
        val file = JsonUtil.fromJson(legacyRedJson)
        assertNotNull(file)
        assertEquals("Red", file.file)
        assertEquals(1, file.units.size)
        // 空对象字段 → 空列表
        assertEquals(0, file.units[0].sensorArray!!.size)
        assertEquals(0, file.units[0].weaponArray!!.size)
        assertEquals(0, file.units[0].perceptionArray!!.size)
        assertEquals(0, file.objects.size)
        assertEquals(0, file.turns.size)
        // 空数组 Map 字段 → 空 Map
        assertTrue(file.overlays.isEmpty())
        assertTrue(file.formations.isEmpty())
    }

    @Test
    fun `legacy red spscn bytes decrypt and parse`() {
        // 模拟完整链路：Red.SpScn 字节 → 解密 → 容错解析
        val bytes = SpScnCodec.toScnFileBytes(legacyRedJson)
        val text = SpScnCodec.fromScnFileBytes(bytes)
        assertTrue(JsonUtil.isScenarioJson(text))
        val file = JsonUtil.fromJson(text)
        assertEquals("Red", file.file)
        assertEquals(1, file.units.size)
    }

    @Test
    fun `normal arrays still parse`() {
        val json = legacyRedJson
            .replace("\"Objects\": {}", "\"Objects\": [\"S001\"]")
            .replace("\"Turns\": {}", "\"Turns\": [{\"TurnTime\": \"1942-10-01 00:00:00\", \"TurnInterval\": {\"Minutes\": 3, \"Seconds\": 0}}]")
        val file = JsonUtil.fromJson(json)
        assertEquals(listOf("S001"), file.objects)
        assertEquals(1, file.turns.size)
    }

    @Test
    fun `round trip keeps compatibility`() {
        // 容错解析后能正常序列化（不破坏保存链路）
        val file = JsonUtil.fromJson(legacyRedJson)
        val json = JsonUtil.toCompactJson(file)
        val back = JsonUtil.fromJson(json)
        assertEquals(1, back.units.size)
        assertEquals("Red", back.file)
    }
}
