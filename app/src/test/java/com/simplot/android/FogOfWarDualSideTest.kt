package com.simplot.android

import com.simplot.android.data.model.Perception
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.*
import org.junit.Test

/**
 * Show Side 视图过滤 + SpScn 导出回归测试。
 * SceneCanvas 视图：桌面原始行为 = All全部 / Blue仅蓝方 / Red仅红方。
 * SpScn 导出：需要感知过滤。
 */
class FogOfWarDualSideTest {

    @Test
    fun `show side simple filter matches desktop behavior`() {
        val blue = Unit(idNum = "S001", side = "Blue", name = "DD-1")
        val red = Unit(idNum = "S002", side = "Red", name = "CC-2")
        val neutral = Unit(idNum = "R001", side = "Neutral", name = "Reference")

        // ALL 视图：全部可见
        assertTrue(com.simplot.android.ui.ShowSide.ALL.allows(blue))
        assertTrue(com.simplot.android.ui.ShowSide.ALL.allows(red))
        assertTrue(com.simplot.android.ui.ShowSide.ALL.allows(neutral))

        // BLUE 视图：仅蓝方
        assertTrue(com.simplot.android.ui.ShowSide.BLUE.allows(blue))
        assertFalse(com.simplot.android.ui.ShowSide.BLUE.allows(red))
        assertFalse(com.simplot.android.ui.ShowSide.BLUE.allows(neutral))

        // RED 视图：仅红方
        assertFalse(com.simplot.android.ui.ShowSide.RED.allows(blue))
        assertTrue(com.simplot.android.ui.ShowSide.RED.allows(red))
        assertFalse(com.simplot.android.ui.ShowSide.RED.allows(neutral))
    }

    @Test
    fun `spScn export uses fog of war perception for filtering`() {
        val scn = ScenarioFile(units = mutableListOf(
            Unit(idNum = "S001", side = "Blue", name = "DD-1", unitType = "Destroyer",
                perceptionArray = mutableListOf(Perception(seenBySide = "Red"))),
            Unit(idNum = "S002", side = "Red", name = "CC-2", unitType = "Cruiser",
                perceptionArray = mutableListOf(Perception(seenBySide = "Blue"))),
            Unit(idNum = "S003", side = "Blue", name = "Sub-3", unitType = "Submarine")
        ))
        // Red SpScn：S001 对 Red 可见(SeenBySide=Red) + S002 己方 = 2；S003 无 Red 感知 → 被剔除
        val redView = FogOfWar.applyPerspective(scn, "Red")
        assertEquals(2, redView.units.size)
        assertTrue(redView.units.any { it.idNum == "S001" })
        assertTrue(redView.units.any { it.idNum == "S002" })
        assertFalse(redView.units.any { it.idNum == "S003" })

        // Blue SpScn：S002 对 Blue 可见(SeenBySide=Blue) + S001 己方 + S003 己方 = 3；全部可见
        val blueView = FogOfWar.applyPerspective(scn, "Blue")
        assertEquals(3, blueView.units.size)
    }
}
