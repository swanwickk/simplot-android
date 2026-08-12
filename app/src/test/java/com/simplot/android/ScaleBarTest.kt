package com.simplot.android

import com.simplot.android.render.ScaleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G17：比例尺动态数值测试（1-2-5 序列取整 / 像素长度界 / 随 zoom 变化 / 标签格式）。
 */
class ScaleBarTest {

    @Test
    fun `zoom in reduces scale value`() {
        val far = ScaleBar.compute(0.0001f, 100f)
        val near = ScaleBar.compute(0.001f, 100f)
        assertTrue("放大后比例尺数值应减小", far.first > near.first)
    }

    @Test
    fun `bar length stays within 40 to 100 percent of max width`() {
        for (zoom in floatArrayOf(1e-6f, 1e-5f, 1e-4f, 5e-4f, 0.001f, 0.005f, 0.01f, 0.05f)) {
            val (nmi, px) = ScaleBar.compute(zoom, 100f)
            assertTrue("zoom=$zoom nmi=$nmi px=$px 超上限", px <= 100f + 1e-3f)
            assertTrue("zoom=$zoom nmi=$nmi px=$px 低于 40%", px >= 40f - 1e-3f)
            assertTrue(nmi > 0)
        }
    }

    @Test
    fun `nice number follows 1-2-5 series`() {
        assertEquals(1.0, ScaleBar.niceNumber(1.0), 1e-9)
        assertEquals(1.0, ScaleBar.niceNumber(1.9), 1e-9)
        assertEquals(2.0, ScaleBar.niceNumber(2.0), 1e-9)
        assertEquals(2.0, ScaleBar.niceNumber(4.2), 1e-9)
        assertEquals(5.0, ScaleBar.niceNumber(5.0), 1e-9)
        assertEquals(5.0, ScaleBar.niceNumber(9.9), 1e-9)
        assertEquals(10.0, ScaleBar.niceNumber(10.0), 1e-9)
        assertEquals(20.0, ScaleBar.niceNumber(37.0), 1e-9)
        assertEquals(100.0, ScaleBar.niceNumber(100.0), 1e-9)
        // 亚海里（不大于 value 的 1-2-5 序列整值：0.37→0.2、0.08→0.05）
        assertEquals(0.2, ScaleBar.niceNumber(0.37), 1e-9)
        assertEquals(0.05, ScaleBar.niceNumber(0.08), 1e-9)
    }

    @Test
    fun `label formats`() {
        assertEquals("50 nmi", ScaleBar.label(50.0))
        assertEquals("2 nmi", ScaleBar.label(2.0))
        assertEquals("0.5 nmi", ScaleBar.label(0.5))
        assertEquals("0.05 nmi", ScaleBar.label(0.05))
    }

    @Test
    fun `degenerate zoom falls back to 50 nmi`() {
        val (nmi, px) = ScaleBar.compute(0f, 100f)
        assertEquals(50.0, nmi, 1e-9)
        assertEquals(100f, px, 1e-3f)
    }
}
