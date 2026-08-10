package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.domain.engine.FormationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编队引擎测试（R6：桌面版 Formations.Movement DoPrepare/DoCancel）。
 */
class FormationEngineTest {

    private fun convoyScenario(): ScenarioFile {
        val center = Unit(idNum = "S001", name = "COMMODORE", isFormationCenter = true, formationName = "Convoy")
        val m1 = Unit(idNum = "S002", name = "Merchant 1", isInFormation = true, formationName = "Convoy", x = 100, y = 0)
        val m2 = Unit(idNum = "S003", name = "Merchant 2", isInFormation = true, formationName = "Convoy", x = -100, y = 0)
        return ScenarioFile(units = mutableListOf(center, m1, m2))
    }

    @Test
    fun `center and members identified`() {
        val f = convoyScenario()
        val center = FormationEngine.centerOf(f.units, "Convoy")
        assertEquals("S001", center!!.idNum)
        val members = FormationEngine.membersOf(f.units, "Convoy")
        assertEquals(2, members.size)
        assertTrue(members.all { it.isInFormation == true })
    }

    @Test
    fun `prepare records positions`() {
        val f = convoyScenario()
        val n = FormationEngine.prepare(f.units, "Convoy")
        assertEquals(2, n)
        // 每个成员都有移动前位置记录
        assertTrue(f.units[1].pastWaypointArray.isNotEmpty())
        assertTrue(f.units[2].pastWaypointArray.isNotEmpty())
    }

    @Test
    fun `cancel restores positions`() {
        val f = convoyScenario()
        FormationEngine.prepare(f.units, "Convoy")
        // 成员移动（模拟 Do 后位置变化）
        f.units[1].x = 999
        f.units[2].x = -999
        val n = FormationEngine.cancel(f.units, "Convoy")
        assertEquals(2, n)
        // 恢复到移动前位置（prepare 记录的是当时位置）
        assertEquals(100, f.units[1].x)
        assertEquals(-100, f.units[2].x)
    }

    @Test
    fun `remove from formation clears flags`() {
        val f = convoyScenario()
        FormationEngine.removeFromFormation(f.units[1])
        assertFalse(f.units[1].isInFormation == true)
        assertEquals(null, f.units[1].formationName)
    }

    @Test
    fun `formation names listed`() {
        val f = convoyScenario()
        assertEquals(listOf("Convoy"), FormationEngine.formationNames(f))
    }
}
