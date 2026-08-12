package com.simplot.android

import com.google.gson.JsonParser
import com.simplot.android.data.codec.PlayerSettingsCodec
import com.simplot.android.domain.model.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G55：player_settings 与桌面互通测试（PlayerSettingsCodec 往返 / 键序 / 容错）。
 */
class PlayerSettingsCodecTest {

    /** 桌面 Display_Options 键序（与 SaveDisplayOptions 反汇编一致） */
    private val desktopKeyOrder = listOf(
        "ShowCities", "ShowCountries", "ShowWaters", "ShowWaypoints", "ShowDepths",
        "ShowDepthKey", "ShowEs", "ShowGrid", "ShowScaleBar", "ShowWeapons",
        "ShowSensors", "ShowSonar", "ShowLabels", "ShowSpeedLeaders", "ShowFormations"
    )

    /** 桌面版缺省 player_settings.json（与 ScenarioRepository.DEFAULT_PLAYER_SETTINGS 同构） */
    private val desktopSample = """{"Player_Settings":{"File":"","Display_Options":{"ShowCities":true,"ShowCountries":true,"ShowWaters":true,"ShowWaypoints":true,"ShowDepths":true,"ShowDepthKey":true,"ShowEs":true,"ShowGrid":true,"ShowScaleBar":true,"ShowWeapons":true,"ShowSensors":true,"ShowSonar":true,"ShowLabels":true,"ShowSpeedLeaders":true,"ShowFormations":true},"PlayerName":"Player","Units":[]}}"""

    @Test
    fun `toDesktopJson matches desktop schema and Display_Options key order`() {
        val json = JsonParser.parseString(PlayerSettingsCodec.toDesktopJson(PlayerSettings.DEFAULT)).asJsonObject
        // 根只有 Player_Settings 包装
        assertEquals(listOf("Player_Settings"), json.keySet().toList())
        val ps = json.getAsJsonObject("Player_Settings")
        // 包装键序：File / Display_Options / PlayerName / Units（桌面 PlayerSettings.SaveFile 同序）
        assertEquals(listOf("File", "Display_Options", "PlayerName", "Units"), ps.keySet().toList())
        // Display_Options 键序逐项对齐桌面
        val disp = ps.getAsJsonObject("Display_Options")
        assertEquals(desktopKeyOrder, disp.keySet().toList())
        assertEquals("Player", ps.get("PlayerName").asString)
        assertTrue(ps.getAsJsonArray("Units").size() == 0)
    }

    @Test
    fun `round trip preserves all display options and player name`() {
        val custom = PlayerSettings.DEFAULT.copy(
            playerName = "RedForce",
            showGrid = false, showScaleBar = false, showLabels = false, showSpeedLeaders = false,
            showSensors = false, showWeapons = false, showWaypoints = false, showFormations = false,
            showCities = false, showCountries = false, showWaters = false, showDepths = false,
            showDepthKey = false, showSonar = false, showEs = false
        )
        val back = PlayerSettingsCodec.fromDesktopJson(PlayerSettingsCodec.toDesktopJson(custom))!!
        assertEquals("RedForce", back.playerName)
        assertEquals(custom, back)
    }

    @Test
    fun `parse desktop default file applies fields`() {
        val s = PlayerSettingsCodec.fromDesktopJson(desktopSample)!!
        assertEquals("Player", s.playerName)
        assertTrue(s.showGrid)
        assertTrue(s.showCities)
        assertTrue(s.showEs)
        assertTrue(s.showFormations)
    }

    @Test
    fun `parse applies false values and honors file PlayerName`() {
        val text = """{"Player_Settings":{"Display_Options":{"ShowGrid":false,"ShowScaleBar":false},"PlayerName":"Navy"}}"""
        val s = PlayerSettingsCodec.fromDesktopJson(text)!!
        assertEquals("Navy", s.playerName)
        assertFalse(s.showGrid)
        assertFalse(s.showScaleBar)
        // 缺失键回退默认
        assertTrue(s.showLabels)
        assertTrue(s.showCities)
    }

    @Test
    fun `parse invalid json returns null`() {
        assertNull(PlayerSettingsCodec.fromDesktopJson("not json{{{"))
        assertNull(PlayerSettingsCodec.fromDesktopJson("""{"Foo":1}"""))
    }

    @Test
    fun `parse without Player_Settings wrapper returns null`() {
        assertNull(PlayerSettingsCodec.fromDesktopJson("""{"Display_Options":{"ShowGrid":false}}"""))
    }
}
