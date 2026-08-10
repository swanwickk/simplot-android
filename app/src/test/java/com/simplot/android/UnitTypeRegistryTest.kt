package com.simplot.android

import com.simplot.android.data.model.Unit
import com.simplot.android.domain.registry.UnitTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 类型注册表测试（文档 §2.2.2）：UnitTypeRegistry 的 Domain 判定与类型菜单。
 */
class UnitTypeRegistryTest {

    @Test
    fun `submarine by unitType`() {
        val u = Unit(idNum = "U001", unitType = "Submarine")
        assertEquals(UnitTypeRegistry.Domain.SUBSURFACE, UnitTypeRegistry.domainOf(u))
    }

    @Test
    fun `aircraft by unitType`() {
        val u = Unit(idNum = "A001", unitType = "AC Fighter")
        assertEquals(UnitTypeRegistry.Domain.AIR, UnitTypeRegistry.domainOf(u))
    }

    @Test
    fun `surface by unitType`() {
        val u = Unit(idNum = "S001", unitType = "Destroyer")
        assertEquals(UnitTypeRegistry.Domain.SURFACE, UnitTypeRegistry.domainOf(u))
    }

    @Test
    fun `fallback by depth or altitude`() {
        val sub = Unit(idNum = "S999", unitType = "SomethingNew", depth = 50)
        assertEquals(UnitTypeRegistry.Domain.SUBSURFACE, UnitTypeRegistry.domainOf(sub))
        val air = Unit(idNum = "S998", unitType = "SomethingNew", altitude = 3000)
        assertEquals(UnitTypeRegistry.Domain.AIR, UnitTypeRegistry.domainOf(air))
    }

    @Test
    fun `fallback by idNum prefix`() {
        val inst = Unit(idNum = "I001", unitType = "")
        assertEquals(UnitTypeRegistry.Domain.INSTALLATION, UnitTypeRegistry.domainOf(inst))
        val sonobuoy = Unit(idNum = "B001", unitType = "")
        assertEquals(UnitTypeRegistry.Domain.SONOBUOY, UnitTypeRegistry.domainOf(sonobuoy))
    }

    @Test
    fun `surface types include desktop list`() {
        val types = UnitTypeRegistry.SURFACE_TYPES
        assertTrue(types.contains("Carrier"))
        assertTrue(types.contains("Battleship"))
        assertTrue(types.contains("Destroyer"))
        assertTrue(types.contains("Merchant"))
        assertTrue(UnitTypeRegistry.typesOf(UnitTypeRegistry.Domain.SURFACE) == types)
    }

    @Test
    fun `air types include desktop list`() {
        assertTrue(UnitTypeRegistry.AIR_TYPES.contains("AC Fighter"))
        assertTrue(UnitTypeRegistry.AIR_TYPES.contains("Helo ASW"))
        assertTrue(UnitTypeRegistry.AIR_TYPES.contains("Missile AAM"))
    }
}
