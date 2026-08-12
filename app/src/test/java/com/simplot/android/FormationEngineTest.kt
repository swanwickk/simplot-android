package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.domain.engine.FormationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ================= G02 编队编辑器：创建/重命名/删除/成员/设中心/类型/距离单位 =================

    private fun specsOf(name: String, type: String = FormationEngine.FormationTypes.COLUMN) =
        mutableMapOf(name to FormationEngine.FormationSpec(name, type, FormationEngine.FormationDistanceUnits.NMI))

    @Test
    fun `register formation spec`() {
        val specs = mutableMapOf<String, FormationEngine.FormationSpec>()
        FormationEngine.registerFormation(specs, "TF61", FormationEngine.FormationTypes.COMPASS, FormationEngine.FormationDistanceUnits.YARDS)
        assertEquals(FormationEngine.FormationTypes.COMPASS, specs["TF61"]?.type)
        assertEquals(FormationEngine.FormationDistanceUnits.YARDS, specs["TF61"]?.distanceUnit)
    }

    @Test
    fun `rename formation updates units and spec key`() {
        val f = convoyScenario()
        val specs = specsOf("Convoy")
        val n = FormationEngine.renameFormation(f.units, specs, "Convoy", "TF61")
        assertEquals(3, n)
        assertTrue(f.units.all { it.formationName == "TF61" })
        assertTrue(specs.containsKey("TF61"))
        assertFalse(specs.containsKey("Convoy"))
    }

    @Test
    fun `delete formation clears members and spec`() {
        val f = convoyScenario()
        val specs = specsOf("Convoy")
        val n = FormationEngine.deleteFormation(f.units, specs, "Convoy")
        assertEquals(3, n)
        assertTrue(f.units.all { it.formationName == null })
        assertTrue(f.units.none { it.isInFormation == true })
        assertFalse(specs.containsKey("Convoy"))
    }

    @Test
    fun `add member sets formation fields with defaults`() {
        val u = Unit(idNum = "S004", name = "Merchant 3")
        FormationEngine.addMember(u, "TF61", FormationEngine.FormationTypes.COMPASS)
        assertEquals("TF61", u.formationName)
        assertEquals(FormationEngine.FormationTypes.COMPASS, u.formationType)
        assertTrue(u.isInFormation == true)
        assertEquals(0, u.formationBearing)
        assertEquals(100000, u.formationDistance) // 1 海里（文件单位）
    }

    @Test
    fun `remove member clears all formation fields`() {
        val u = Unit(idNum = "S004", name = "Merchant 3")
        FormationEngine.addMember(u, "TF61")
        FormationEngine.removeMember(u)
        assertNull(u.formationName)
        assertNull(u.formationType)
        assertNull(u.formationBearing)
        assertNull(u.formationDistance)
    }

    @Test
    fun `set center promotes one unit and demotes old center`() {
        val f = convoyScenario()
        assertTrue(FormationEngine.setCenter(f.units, "Convoy", "S002"))
        assertTrue(f.units[1].isFormationCenter == true)
        assertTrue(f.units[0].isFormationCenter != true)
        // 中心不属于成员：只剩 S003 一个成员
        assertEquals(1, FormationEngine.membersOf(f.units, "Convoy").size)
        assertEquals("S003", FormationEngine.membersOf(f.units, "Convoy")[0].idNum)
    }

    @Test
    fun `set center rejects unit outside formation`() {
        val f = convoyScenario()
        assertFalse(FormationEngine.setCenter(f.units, "Convoy", "S999"))
        assertTrue(f.units[0].isFormationCenter == true)
    }

    @Test
    fun `set type updates spec and members`() {
        val f = convoyScenario()
        val specs = specsOf("Convoy")
        val n = FormationEngine.setType(f.units, specs, "Convoy", FormationEngine.FormationTypes.COURSE)
        assertEquals(3, n)
        assertTrue(f.units.all { it.formationType == FormationEngine.FormationTypes.COURSE })
        assertEquals(FormationEngine.FormationTypes.COURSE, specs["Convoy"]?.type)
    }

    @Test
    fun `distance value converts units`() {
        val u = Unit(idNum = "S004", formationDistance = 100000) // 1 海里
        assertEquals(1.0, FormationEngine.distanceValue(u, FormationEngine.FormationDistanceUnits.NMI)!!, 0.0001)
        assertEquals(2025.37, FormationEngine.distanceValue(u, FormationEngine.FormationDistanceUnits.YARDS)!!, 0.01)
        assertEquals(1852.0, FormationEngine.distanceValue(u, FormationEngine.FormationDistanceUnits.METERS)!!, 0.01)
        assertNull(FormationEngine.distanceValue(Unit(idNum = "S005")))
    }

    @Test
    fun `bearing deg converts fixed point`() {
        val u = Unit(idNum = "S004", formationBearing = 45000)
        assertEquals(45.0, FormationEngine.bearingDeg(u)!!, 0.0001)
    }

    @Test
    fun `type of falls back through members`() {
        val f = convoyScenario()
        assertEquals(null, FormationEngine.typeOf(f.units, "Convoy"))
        FormationEngine.setType(f.units, mutableMapOf(), "Convoy", FormationEngine.FormationTypes.COMPASS)
        assertEquals(FormationEngine.FormationTypes.COMPASS, FormationEngine.typeOf(f.units, "Convoy"))
    }
}
