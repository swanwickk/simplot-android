package com.simplot.android

import com.simplot.android.render.ArcRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 弧标注文字格式测试：[弧名称] [最小距离]-[最大距离]。
 */
class ArcLabelFormattingTest {

    @Test
    fun `formatRangeNumber formats integers without decimal point`() {
        assertEquals("0", ArcRenderer.formatRangeNumber(0.0))
        assertEquals("15", ArcRenderer.formatRangeNumber(15.0))
        assertEquals("8", ArcRenderer.formatRangeNumber(8.0))
    }

    @Test
    fun `formatRangeNumber formats fractions with one decimal point`() {
        assertEquals("14.5", ArcRenderer.formatRangeNumber(14.5))
        assertEquals("0.5", ArcRenderer.formatRangeNumber(0.5))
    }

    @Test
    fun `formatArcLabel with name and min max range`() {
        assertEquals("FC L 0-15", ArcRenderer.formatArcLabel("FC L", 0.0, 15.0))
        assertEquals("M 0-14", ArcRenderer.formatArcLabel("M", 0.0, 14.0))
        assertEquals("S 0-8", ArcRenderer.formatArcLabel("S", 0.0, 8.0))
        assertEquals("SS L M 0-22", ArcRenderer.formatArcLabel("SS L M", 0.0, 22.0))
        assertEquals("Main Gun 2-15", ArcRenderer.formatArcLabel("Main Gun", 2.0, 15.0))
    }

    @Test
    fun `formatArcLabel with blank name falls back to range only`() {
        assertEquals("0-15", ArcRenderer.formatArcLabel("", 0.0, 15.0))
        assertEquals("0-15", ArcRenderer.formatArcLabel("   ", 0.0, 15.0))
    }
}
