package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.PassiveBearing
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.render.BearingRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈㉔/桌面语义对齐测试：
 * 1. BearingArray 键名（桌面反编译 SaveUnits 键序实测）往返 + 旧键兼容；
 * 2. CalcBearing（本站→目标实时方位，Floor 取整，桌面 CBearing.CalcBearing 复刻）；
 * 3. bearingOf：Emitter 命中→实时方位；未命中→存档值。
 */
class BearingArrayAndCalcBearingTest {

    // ---- 键名与序列化 ----

    @Test
    fun `desktop key BearingArray round trips through gson`() {
        val u = Unit(
            idNum = "S001", side = "Blue",
            passiveBearingArray = mutableListOf(
                PassiveBearing(type = "Sonar", bearing = 190.0, emitter = "S002", beamLength = 12.0, beamWidth = 20.0)
            )
        )
        val f = ScenarioFile(units = mutableListOf(u))
        val json = JsonUtil.toCompactJson(f)
        assertTrue("序列化应写桌面键名 BearingArray", json.contains("\"BearingArray\""))
        val back = JsonUtil.fromJson(json)
        val b = back.units[0].passiveBearingArray!![0]
        assertEquals(190.0, b.bearing, 1e-9)
        assertEquals("Sonar", b.type)
    }

    @Test
    fun `legacy key PassiveBearingArray still readable`() {
        val legacy = """{"Scenario":{"ScenarioName":"t"},"Units":[{"IdNum":"S001",
            "PassiveBearingArray":[{"Type":"ES","Bearing":45.0,"Emitter":"S002"}]}],"Objects":["S001"]}"""
        val f = JsonUtil.fromJson(legacy)
        assertNotNull(f.units[0].passiveBearingArray)
        assertEquals(45.0, f.units[0].passiveBearingArray!![0].bearing, 1e-9)
    }

    // ---- CalcBearing（桌面复刻） ----

    @Test
    fun `calcBearing north is 0`() {
        assertEquals(0.0, BearingRenderer.calcBearing(0, 0, 0, 100000)!!, 1e-9)
    }

    @Test
    fun `calcBearing east is 90`() {
        assertEquals(90.0, BearingRenderer.calcBearing(0, 0, 100000, 0)!!, 1e-9)
    }

    @Test
    fun `calcBearing south west normalizes to positive`() {
        val b = BearingRenderer.calcBearing(0, 0, -100000, -100000)!!
        assertTrue("西南方位应在 180~270", b in 180.0..270.0)
        assertEquals(225.0, b, 1e-6)
    }

    @Test
    fun `calcBearing floors to integer degrees`() {
        // atan2(3,4)≈36.87° → Floor=36
        assertEquals(36.0, BearingRenderer.calcBearing(0, 0, 30000, 40000)!!, 1e-9)
    }

    @Test
    fun `calcBearing same point returns null`() {
        assertNull(BearingRenderer.calcBearing(5, 5, 5, 5))
    }

    // ---- bearingOf 实时重算 ----

    @Test
    fun `bearingOf uses live emitter position when found`() {
        val owner = Unit(idNum = "S001", x = 0, y = 0)
        val target = Unit(idNum = "S002", x = 100000, y = 0)   // 正东
        assertEquals(
            90.0,
            BearingRenderer.bearingOf(bearingStored = 0.0, emitterId = "S002", allUnits = listOf(owner, target), owner = owner),
            1e-9
        )
    }

    @Test
    fun `bearingOf falls back to stored value when emitter missing or blank`() {
        val owner = Unit(idNum = "S001", x = 0, y = 0)
        assertEquals(77.0, BearingRenderer.bearingOf(77.0, "NOPE", listOf(owner), owner), 1e-9)
        assertEquals(77.0, BearingRenderer.bearingOf(77.0, "", emptyList(), owner), 1e-9)
    }
}
