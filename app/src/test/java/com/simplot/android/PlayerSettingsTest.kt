package com.simplot.android

import com.simplot.android.domain.model.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玩家设置模型测试（R4：PlayerSettings 默认值/开关语义）。
 */
class PlayerSettingsTest {

    @Test
    fun `defaults match desktop display options`() {
        val d = PlayerSettings.DEFAULT
        assertTrue(d.showGrid)
        assertTrue(d.showScaleBar)
        assertTrue(d.showLabels)
        assertTrue(d.showSpeedLeaders)
        assertTrue(d.showSensors)
        assertTrue(d.showWeapons)
        assertEquals("Player", d.playerName)
    }

    @Test
    fun `copy toggles independently`() {
        val d = PlayerSettings.DEFAULT
        val off = d.copy(showGrid = false, showSensors = false)
        assertFalse(off.showGrid)
        assertFalse(off.showSensors)
        // 其它开关不受影响
        assertTrue(off.showWeapons)
        assertTrue(off.showLabels)
        // 原对象不变（data class 不可变）
        assertTrue(d.showGrid)
    }

    @Test
    fun `player name persisted via copy`() {
        val renamed = PlayerSettings.DEFAULT.copy(playerName = "RedForce")
        assertEquals("RedForce", renamed.playerName)
    }
}
