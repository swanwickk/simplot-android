package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.Perception
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红蓝双视角感知脱敏测试（文档 P0：感知系统完整实现）。
 *
 * 场景：裁判全量（Referee）含一个蓝方单位与一个红方单位，
 * 各带对另一方的感知记录（受限项差异），验证 Blue/Red 视角落盘时分别脱敏。
 */
class FogOfWarDualSideTest {

    private fun scenario(): ScenarioFile {
        val blue = Unit(
            idNum = "S001", side = "Blue", name = "DD-1", unitType = "Destroyer",
            unitClass = "DD", speed = 30000, course = 90000, x = 100, y = 100
        )
        val red = Unit(
            idNum = "S002", side = "Red", name = "CC-2", unitType = "Cruiser",
            unitClass = "CC", speed = 20000, course = 270000, x = 300, y = 300
        )
        // 蓝方单位对红方可见但脱敏（隐藏名称、显示为护卫舰 FF、阵营 Neutral）
        blue.perceptionArray = mutableListOf(
            Perception(
                seenBySide = "Red", showAsSide = "Neutral", showAsType = "FF",
                showName = false, showClass = false, showCourseSpeed = true,
                showAltitude = true, showDepth = true
            )
        )
        // 红方单位对蓝方可见但脱敏（隐藏航向航速、显示为 FF）
        red.perceptionArray = mutableListOf(
            Perception(
                seenBySide = "Blue", showAsSide = "Blue", showAsType = "FF",
                showName = true, showClass = true, showCourseSpeed = false,
                showAltitude = true, showDepth = true
            )
        )
        return ScenarioFile(units = mutableListOf(blue, red))
    }

    @Test
    fun `blue perspective clears red course speed but keeps name`() {
        val blue = FogOfWar.applyPerspective(scenario(), "Blue")
        assertEquals("Blue", blue.file)
        // 红方单位在蓝方视角：showName=true 保留名字；showCourseSpeed=false → 航向航速清零；类型替换为 FF、阵营替换为 Blue
        val redSeen = blue.units.first { it.idNum == "S002" }
        assertEquals("CC-2", redSeen.name)
        assertEquals("FF", redSeen.unitType)
        assertEquals("Blue", redSeen.side)
        assertEquals(0, redSeen.speed)
        assertEquals(0, redSeen.course)
        // 己方蓝方单位保留原名
        val blueSelf = blue.units.first { it.idNum == "S001" }
        assertEquals("DD-1", blueSelf.name)
    }

    @Test
    fun `red perspective hides blue name and masks side`() {
        val red = FogOfWar.applyPerspective(scenario(), "Red")
        assertEquals("Red", red.file)
        val blueSeen = red.units.first { it.idNum == "S001" }
        assertEquals("", blueSeen.name)
        assertEquals("FF", blueSeen.unitType)
        assertEquals("Neutral", blueSeen.side)
        // 红方自己保留航向航速
        val redSelf = red.units.first { it.idNum == "S002" }
        assertEquals(20000, redSelf.speed)
    }

    @Test
    fun `dual perceptions independent`() {
        val scn = scenario()
        val blue = FogOfWar.applyPerspective(scn, "Blue")
        val red = FogOfWar.applyPerspective(scn, "Red")
        // 脱敏相互独立：蓝视角红单位类型 FF，红视角蓝单位类型 FF
        assertEquals("FF", blue.units.first { it.idNum == "S002" }.unitType)
        assertEquals("FF", red.units.first { it.idNum == "S001" }.unitType)
        // 深拷贝隔离：修改视角视图不污染原裁判数据
        val originalRed = scn.units.first { it.idNum == "S002" }
        assertTrue(originalRed.name.isNotEmpty())
    }

    @Test
    fun `show side allows red unit when visible to blue`() {
        val scn = scenario()
        val blueUnit = scn.units.first { it.idNum == "S001" } // Blue
        val redUnit = scn.units.first { it.idNum == "S002" }  // Red, perception seenBySide="Blue"
        
        val showBlue = com.simplot.android.ui.ShowSide.BLUE
        val showRed = com.simplot.android.ui.ShowSide.RED
        val showAll = com.simplot.android.ui.ShowSide.ALL

        // ALL: 全部可见
        assertTrue(showAll.allows(blueUnit))
        assertTrue(showAll.allows(redUnit))

        // BLUE 视图：蓝方己方可见 + 红方(对蓝可见)也可见！
        assertTrue(showBlue.allows(blueUnit))
        assertTrue("红方单位对蓝方可见时，在蓝方视图下必须允许显示", showBlue.allows(redUnit))

        // RED 视图：红方己方可见 + 蓝方(对红可见)也可见！
        assertTrue(showRed.allows(redUnit))
        assertTrue("蓝方单位对红方可见时，在红方视图下必须允许显示", showRed.allows(blueUnit))
    }

    @Test
    fun `show side hides enemy unit when fog enabled and not visible`() {
        val redHidden = Unit(
            idNum = "S003", side = "Red", name = "Secret-Sub",
            perceptionArray = mutableListOf(
                Perception(seenBySide = "Red") // 仅对红方可见，对蓝方不可见
            )
        )
        val showBlue = com.simplot.android.ui.ShowSide.BLUE
        val showRed = com.simplot.android.ui.ShowSide.RED
        
        assertFalse("红方单位未对蓝方开放可见时，在蓝方视图下必须隐藏", showBlue.allows(redHidden))
        assertTrue("红方单位在红方视图下始终可见", showRed.allows(redHidden))
    }
}
