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
 * 反馈㉔/反馈㉗/桌面语义对齐测试：
 * 1. BearingArray 键名（桌面反编译 SaveUnits 键序实测）往返 + 旧键兼容；
 * 2. CalcBearing（本站→目标实时方位，Floor 取整，桌面 CBearing.CalcBearing 复刻）；
 * 3. bearingOf：Emitter 命中→带波束误差截断的方位；确保波束缩小（10°→5°）时目标始终在扇区内；
 * 4. randomizeBearingInBeam：随机散布确保目标在扇区内且非恒定中心。
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

    // ---- bearingOf 实时重算与误差角截断（保证目标恒在扇区内） ----

    @Test
    fun `bearingOf with zero width points exactly at target`() {
        val owner = Unit(idNum = "S001", x = 0, y = 0)
        val target = Unit(idNum = "S002", x = 100000, y = 0)   // 正东 90°
        assertEquals(
            90.0,
            BearingRenderer.bearingOf(bearingStored = 0.0, emitterId = "S002", beamWidth = 0.0, allUnits = listOf(owner, target), owner = owner),
            1e-9
        )
    }

    @Test
    fun `bearingOf keeps target inside beam when width shrinks from 10 to 5`() {
        val owner = Unit(idNum = "S001", x = 0, y = 0)
        val target = Unit(idNum = "S002", x = 100000, y = 0)   // 真实方位 90°
        // 10° 波束下，存储了 94°（偏离 4°）
        val deg10 = BearingRenderer.bearingOf(94.0, "S002", 10.0, listOf(owner, target), owner)
        val (lo10, hi10) = BearingRenderer.beamEdgeBearings(deg10, 10.0)
        assertTrue("10° 下目标 90° 应在 [$lo10, $hi10] 内", 90.0 >= lo10 && 90.0 <= hi10)

        // 缩小到 5° 波束，中心角自动向真实方位收紧，保证目标 90° 仍然在扇区内
        val deg5 = BearingRenderer.bearingOf(94.0, "S002", 5.0, listOf(owner, target), owner)
        val (lo5, hi5) = BearingRenderer.beamEdgeBearings(deg5, 5.0)
        assertTrue("缩小到 5° 后目标 90° 仍必须在 [$lo5, $hi5] 内", 90.0 >= lo5 && 90.0 <= hi5)
    }

    @Test
    fun `randomizeBearingInBeam places target inside beam and not exactly at center`() {
        val trueBearing = 133.0
        val width = 10.0
        val r1 = BearingRenderer.randomizeBearingInBeam(trueBearing, width, randomFactor = 0.1)
        val r2 = BearingRenderer.randomizeBearingInBeam(trueBearing, width, randomFactor = 0.9)
        // 验证扇区覆盖了真实目标 133°
        val (lo1, hi1) = BearingRenderer.beamEdgeBearings(r1, width)
        val (lo2, hi2) = BearingRenderer.beamEdgeBearings(r2, width)
        assertTrue("随机方位1扇区 [$lo1, $hi1] 必须包含 133°", trueBearing in lo1..hi1)
        assertTrue("随机方位2扇区 [$lo2, $hi2] 必须包含 133°", trueBearing in lo2..hi2)
        // 验证非恒定中心
        assertTrue("随机散布不应恒等于中心 133°", r1 != trueBearing || r2 != trueBearing)
    }

    @Test
    fun `bearingOf falls back to stored value when emitter missing or blank`() {
        val owner = Unit(idNum = "S001", x = 0, y = 0)
        assertEquals(77.0, BearingRenderer.bearingOf(77.0, "NOPE", 10.0, listOf(owner), owner), 1e-9)
        assertEquals(77.0, BearingRenderer.bearingOf(77.0, "", 10.0, emptyList(), owner), 1e-9)
    }
}
