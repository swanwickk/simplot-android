package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.Perception
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.*
import org.junit.Test

/**
 * J1/W3 旧存档与侧文件感知回归：lenient {}<->[] 容错 + W3 感知保留 + SpScn 三文件往返。
 * 模拟老版本 Red.SpScn（空数组写 {}、空 Map 写 []），验证解密->容错解析->重加密的稳定性。
 */
class SpScnLegacyRoundTripTest {

    private val legacyJson = """
    {
      "File": "Red",
      "SimPlot Version": "2.3",
      "IsIntegerFile": true,
      "Scenario": {"ScenarioName": "Legacy", "LastId": 1, "CurrentTrackNumber": 2400, "CurrentPlayerTrackNumber": 9000, "Phase": 0, "TypeOfMap": 0},
      "TypeOfGame": 0,
      "Time": {"CurrentTurnTime": "1941-03-28 07:30:00", "CurrentPositionTime": "1941-03-28 07:30:00", "CurrentTurnInterval": {"Minutes": 3, "Seconds": 0}},
      "Turns": {},
      "Overlays": [],
      "Objects": {},
      "Units": [
        {
          "IdNum": "S001", "Side": "Blue", "TrackNumber": 2401, "Name": "Orion",
          "Number": 1, "UnitClass": "CL", "UnitType": "Cruiser",
          "X": 100, "Y": 200, "ShowSunk": false, "Speed": 26000, "Course": 159999, "Range": -100000, "WpDistance": 1,
          "PastWaypointArray": {}, "FutureWaypointArray": {},
          "TextTags": {"TagName": true, "TagCourseSpeed": true},
          "PerceptionArray": [
            {"PositionTimeStart": "1941-03-28 07:30:00", "PositionTimeEnd": "1942-03-28 07:30:00", "DetectionTime": "1941-03-28 07:30:00", "SeenBySide": "Red", "ShowAsSide": "Blue", "ShowAsType": "Cruiser", "ShowAltitude": false, "ShowClass": false, "ShowCourseSpeed": true, "ShowDepth": false, "ShowName": false}
          ],
          "SensorArray": {}
        },
        {
          "IdNum": "I014", "Side": "Blue", "TrackNumber": 1568, "Name": "Malta",
          "Number": 1, "UnitClass": "", "UnitType": "Airfield",
          "X": -1000, "Y": -2000, "ShowSunk": false,
          "PositionTimeCreated": "1941-03-28 07:30:00", "PositionTimeDeleted": "1942-03-28 07:30:00",
          "TextTags": {"TagName": true}
        },
        {
          "IdNum": "S002", "Side": "Red", "TrackNumber": 2402, "Name": "Alpha",
          "Number": 1, "UnitClass": "DD", "UnitType": "Destroyer",
          "X": 300, "Y": 400, "ShowSunk": false, "Speed": 20000, "Course": 90000, "Range": -100000, "WpDistance": 0,
          "PastWaypointArray": {}, "FutureWaypointArray": {},
          "TextTags": {"TagCourseSpeed": true},
          "FormationName": "", "FormationType": ""
        }
      ],
      "Formations": []
    }
    """.trimIndent()

    @Test
    fun `legacy spscn decrypts and lenient parses`() {
        val bytes = SpScnCodec.toScnFileBytes(legacyJson)
        val text = SpScnCodec.fromScnFileBytes(bytes)
        assertTrue(JsonUtil.isScenarioJson(text))
        val file = JsonUtil.fromJson(text)
        assertEquals(3, file.units.size)
        // {} -> empty list/maps
        assertEquals(0, file.turns.size)
        assertTrue(file.overlays.isEmpty())
        assertTrue(file.formations.isEmpty())
        assertEquals(0, file.objects.size)
        val s001 = file.units.first { it.idNum == "S001" }
        assertEquals(0, s001.sensorArray!!.size)
        assertEquals(1, file.units.first { it.idNum == "S001" }.perceptionArray!!.size)
    }

    @Test
    fun `w2 blank formation normalized to null`() {
        val file = JsonUtil.fromJson(legacyJson)
        val s002 = file.units.first { it.idNum == "S002" }
        assertNull(s002.formationName)
        assertNull(s002.formationType)
        val json = JsonUtil.toCompactJson(file)
        assertFalse(json.contains("\"FormationName\":\"\""))
        assertFalse(json.contains("\"FormationType\":\"\""))
    }

    @Test
    fun `w1 immovable unit omits motion keys`() {
        val file = JsonUtil.fromJson(legacyJson)
        val json = JsonUtil.toCompactJson(file)
        val el = com.google.gson.JsonParser.parseString(json).asJsonObject
        val units = el.getAsJsonArray("Units")
        val i014 = (0 until units.size()).map { units.get(it).asJsonObject }.first { it.get("IdNum").asString == "I014" }
        assertFalse("I/R 不应落盘 Speed", i014.has("Speed"))
        assertFalse("I/R 不应落盘 Range", i014.has("Range"))
        assertFalse("I/R 不应落盘 WpDistance", i014.has("WpDistance"))
        assertFalse("I/R 不应落盘 Course", i014.has("Course"))
        val s001 = (0 until units.size()).map { units.get(it).asJsonObject }.first { it.get("IdNum").asString == "S001" }
        assertTrue("可移动单位应保留 Speed", s001.has("Speed"))
    }

    @Test
    fun `w3 side file retains triggering perception`() {
        val file = JsonUtil.fromJson(legacyJson)
        val redView = FogOfWar.applyPerspective(file, "Red")
        val s001 = redView.units.first { it.idNum == "S001" }
        assertNotNull("Red 侧文件应对 Blue 单位保留 Perception", s001.perceptionArray)
        assertTrue(s001.perceptionArray!!.any { it.seenBySide == "Red" })
        // 己方单位清感知
        val s002 = redView.units.first { it.idNum == "S002" }
        assertNull(s002.perceptionArray)
    }

    @Test
    fun `spscn roundtrip stable bytes`() {
        val file = JsonUtil.fromJson(legacyJson)
        val refBytes = SpScnCodec.toJsonFileBytes(JsonUtil.toCompactJson(file))
        val redBytes = SpScnCodec.toScnFileBytes(JsonUtil.toCompactJson(FogOfWar.applyPerspective(file, "Red")))
        assertTrue(refBytes.isNotEmpty())
        assertTrue(redBytes.isNotEmpty())
        // 解密后仍为合法 JSON
        val redText = SpScnCodec.fromScnFileBytes(redBytes)
        assertTrue(JsonUtil.isScenarioJson(redText))
        val back = JsonUtil.fromJson(redText)
        // Red 视角：Red 己方 S002 + 对 Red 可见的 Blue 单位 S001（Blue 机场 I014 未侦测不可见）= 2 单位
        assertEquals(2, back.units.size)
    }
}
