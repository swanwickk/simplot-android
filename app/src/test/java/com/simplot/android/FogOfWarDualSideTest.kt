package com.simplot.android

import com.simplot.android.data.model.Perception
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.*
import org.junit.Test

/**
 * Show Side 视图过滤 + SpScn 导出回归测试。
 * 视图：己方+对己方可见的敌方+中立默认可见。
 * SpScn 导出：感知过滤（Visibility 行为一致）。
 */
class FogOfWarDualSideTest {

    @Test
    fun `show side perception-based filter`() {
        val blue = Unit(idNum = "S001", side = "Blue", name = "DD-1")
        val red = Unit(idNum = "S002", side = "Red", name = "CC-2")
        val neutral = Unit(idNum = "R001", side = "Neutral", name = "Reference")
        val redSpotted = Unit(idNum = "S003", side = "Red", name = "Cruiser",
            perceptionArray = mutableListOf(Perception(seenBySide = "Blue")))

        val showAll = com.simplot.android.ui.ShowSide.ALL
        val showBlue = com.simplot.android.ui.ShowSide.BLUE
        val showRed = com.simplot.android.ui.ShowSide.RED

        // ALL 视图：全部可见
        assertTrue(showAll.allows(blue)); assertTrue(showAll.allows(red)); assertTrue(showAll.allows(neutral))

        // BLUE 视图：蓝方己方 + 被蓝方侦测的红方 + 中立默认可见
        assertTrue(showBlue.allows(blue))          // 己方
        assertFalse(showBlue.allows(red))           // 敌方，无 Blue 感知 → 隐藏
        assertTrue(showBlue.allows(neutral))        // 中立 → 默认可见
        assertTrue(showBlue.allows(redSpotted))     // 敌方，有 Blue 感知 → 可见

        // RED 视图：红方己方 + 中立默认可见
        assertFalse(showRed.allows(blue))           // 敌方，无 Red 感知 → 隐藏
        assertTrue(showRed.allows(red))              // 己方
        assertTrue(showRed.allows(neutral))          // 中立 → 默认可见
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
        // Red SpScn：S001 对 Red 可见 + S002 己方 = 2；S003 无 Red 感知 → 被剔除
        val redView = FogOfWar.applyPerspective(scn, "Red")
        assertEquals(2, redView.units.size)
        assertTrue(redView.units.any { it.idNum == "S001" })
        assertTrue(redView.units.any { it.idNum == "S002" })
        assertFalse(redView.units.any { it.idNum == "S003" })

        // Blue SpScn：S002 对 Blue 可见 + S001 己方 + S003 己方 = 3
        val blueView = FogOfWar.applyPerspective(scn, "Blue")
        assertEquals(3, blueView.units.size)
    }
}
