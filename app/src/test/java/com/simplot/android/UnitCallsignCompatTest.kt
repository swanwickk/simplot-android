package com.simplot.android

import com.google.gson.JsonParser
import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.PassiveBearing
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.data.model.shiftWaypoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tagCallsign 存档兼容收尾（批次2-轮2，主 agent 决策）：
 * - TextTags.callsign 为瞬态字段（@Transient，Gson 排除）→ 序列化保持桌面版 9 键字节级兼容
 * - 渲染语义统一走 callsignOrName()（独立呼叫号空串 → 回退单位 Name，桌面版"呼叫号=Name"）
 * - 顺带覆盖 G19 被动方位模型序列化键集（桌面 PassiveBearings.CBearing 10 键）
 */
class UnitCallsignCompatTest {

    /** 桌面版 TextTags 固定 9 键（反汇编确认，无独立呼叫号字符串键） */
    private val DESKTOP_9_KEYS = setOf(
        "TagAltitude", "TagCallsign", "TagClass", "TagCourseSpeed", "TagDepth",
        "TagName", "TagTrackNum", "TagUnitType", "AdditionalText"
    )

    @Test
    fun `textTags serializes with exactly 9 desktop keys and no Callsign key`() {
        val unit = Unit(idNum = "S001", name = "Hood").apply {
            textTags.callsign = "HORNET"   // 瞬态字段：必须不落盘
            textTags.additionalText = "x"
        }
        val json = JsonParser.parseString(JsonUtil.gson.toJson(unit)).asJsonObject
        val tags = json.getAsJsonObject("TextTags")
        assertEquals("TextTags 必须固定 9 键（桌面字节级兼容）", DESKTOP_9_KEYS, tags.keySet())
        assertFalse("瞬态 callsign 不得落盘", tags.has("Callsign"))
    }

    @Test
    fun `desktop 9-key textTags deserializes and callsign defaults to blank`() {
        // 桌面版真实 9 键 JSON（无 Callsign 键）→ 反序列化 callsign 默认空串
        val json = """
            {"IdNum":"S001","Name":"Hood","TextTags":{"TagAltitude":false,"TagCallsign":false,
            "TagClass":false,"TagCourseSpeed":true,"TagDepth":false,"TagName":false,
            "TagTrackNum":false,"TagUnitType":false,"AdditionalText":"wake"}}
        """.trimIndent()
        val unit = JsonUtil.gson.fromJson(json, Unit::class.java)
        assertEquals("", unit.textTags.callsign)
        assertEquals("wake", unit.textTags.additionalText)
        // 渲染语义：独立呼叫号空 → 回退单位 Name（桌面版呼叫号 = Name）
        assertEquals("Hood", unit.callsignOrName())
    }

    @Test
    fun `json with unknown Callsign key still deserializes`() {
        // 外部文件若带 "Callsign" 未知键：Gson 忽略未知键，不破坏互通
        val json = """
            {"IdNum":"S001","Name":"Hood","TextTags":{"TagAltitude":false,"TagCallsign":false,
            "TagClass":false,"TagCourseSpeed":true,"TagDepth":false,"TagName":false,
            "TagTrackNum":false,"TagUnitType":false,"AdditionalText":"","Callsign":"IGNORED"}}
        """.trimIndent()
        val unit = JsonUtil.gson.fromJson(json, Unit::class.java)
        assertEquals("Hood", unit.callsignOrName())
    }

    @Test
    fun `callsignOrName prefers callsign and falls back to name`() {
        val withCallsign = Unit(idNum = "S001", name = "Hood").apply { textTags.callsign = "HORNET" }
        assertEquals("HORNET", withCallsign.callsignOrName())
        val blankCallsign = Unit(idNum = "S001", name = "Hood").apply { textTags.callsign = "  " }
        assertEquals("Hood", blankCallsign.callsignOrName())
    }

    @Test
    fun `transient callsign survives in-memory gson round trip`() {
        // 编辑期字段不落盘，但同进程内深拷贝（复制/粘贴）应保留渲染语义
        val unit = Unit(idNum = "S001", name = "Hood").apply { textTags.callsign = "HORNET" }
        val copy = JsonUtil.gson.fromJson(JsonUtil.gson.toJson(unit), Unit::class.java)
        // 深拷贝经 Gson 走序列化 → 瞬态字段丢失属预期；渲染语义回退 Name（与桌面一致）
        assertEquals("Hood", copy.callsignOrName())
    }

    @Test
    fun `passiveBearing serializes with desktop 10 keys`() {
        val b = PassiveBearing(type = "ES", beamLength = 3.0, beamWidth = 5.0, bearing = 270.0,
            emitter = "S003", es = "", label = "ES 270", positionTimeStart = "", positionTimeEnd = "", showAsSide = "Blue")
        val obj = JsonParser.parseString(JsonUtil.gson.toJson(b)).asJsonObject
        assertEquals(
            setOf("Type", "BeamLength", "BeamWidth", "Bearing", "Emitter", "ES",
                "Label", "PositionTimeStart", "PositionTimeEnd", "ShowAsSide"),
            obj.keySet()
        )
    }

    // ============ G32：shiftWaypoints 纯函数（relocate 航路点平移） ============

    @Test
    fun `shiftWaypoints translates every point by delta`() {
        val wps = mutableListOf(Waypoint(x = 1000, y = 2000), Waypoint(x = 3000, y = 4000))
        val returned = shiftWaypoints(wps, 200, -50)
        assertTrue("就地修改并返回同一列表", returned === wps)
        assertEquals(1200L, wps[0].x)
        assertEquals(1950L, wps[0].y)
        assertEquals(3200L, wps[1].x)
        assertEquals(3950L, wps[1].y)
    }

    @Test
    fun `shiftWaypoints zero delta leaves points unchanged`() {
        val wps = mutableListOf(Waypoint(x = 1000, y = 2000))
        shiftWaypoints(wps, 0, 0)
        assertEquals(1000L, wps[0].x)
        assertEquals(2000L, wps[0].y)
    }

    @Test
    fun `shiftWaypoints empty list is safe`() {
        val wps = mutableListOf<Waypoint>()
        shiftWaypoints(wps, 5, 5)
        assertTrue(wps.isEmpty())
    }
}
