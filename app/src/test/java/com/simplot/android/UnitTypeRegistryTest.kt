package com.simplot.android

import com.simplot.android.data.model.Unit
import com.simplot.android.domain.registry.UnitTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ============ P1-2（G13 反向切换）回归 ============

    @Test
    fun `apply domain surface clears altitude and depth`() {
        // P1-2 修复回归：飞机/潜艇改回水面时，高度与深度必须被清空（此前无法置 null → 永久卡在飞机/潜艇）。
        val u = Unit(idNum = "A001", unitType = "AC Fighter", altitude = 3000, depth = null)
        UnitTypeRegistry.applyDomainDimensions(u, UnitTypeRegistry.Domain.SURFACE)
        assertNull("改回水面后高度应清空", u.altitude)
        assertNull("改回水面后深度应清空", u.depth)
        assertFalse("改回水面后不再是飞机", u.isAircraft())
        assertTrue("改回水面后是水面单位", u.isSurface())
    }

    @Test
    fun `apply domain air clears depth keeps altitude`() {
        // 切到飞机：保留高度、清空深度。
        val u = Unit(idNum = "S001", unitType = "Destroyer", altitude = null, depth = 500)
        UnitTypeRegistry.applyDomainDimensions(u, UnitTypeRegistry.Domain.AIR)
        assertNull("切到飞机后深度应清空", u.depth)
        assertNull("水面单位切飞机时高度暂空（由调用方写回）", u.altitude)
    }

    @Test
    fun `apply domain subsurface clears altitude keeps depth`() {
        // 切到潜艇：清空高度、保留深度。
        val u = Unit(idNum = "A001", unitType = "AC Fighter", altitude = 3000, depth = 500)
        UnitTypeRegistry.applyDomainDimensions(u, UnitTypeRegistry.Domain.SUBSURFACE)
        assertNull("切到潜艇后高度应清空", u.altitude)
        assertEquals("切到潜艇后深度保留", 500, u.depth!!.toLong())
        assertTrue("是潜艇判定", u.isSubmarine())
    }
}
