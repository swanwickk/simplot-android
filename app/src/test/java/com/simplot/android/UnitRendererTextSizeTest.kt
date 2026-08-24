package com.simplot.android

import com.simplot.android.render.UnitRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约6 字号加大回归测试（反馈㉒ density 感知放大）：单测下默认 20f、下限 16f、上限 40f（真机乘 density 后为 16sp~40sp）。
 */
class UnitRendererTextSizeTest {

    @Test
    fun `default zoom label size is 20f`() {
        assertEquals(20f, UnitRenderer.labelTextSize(0.0015f), 0.001f)
    }

    @Test
    fun `la plata zoom label size stays at least 16f`() {
        // 拉普拉塔默认 zoom≈0.0011：20 × 0.73 = 14.6 → clamp 到 16f（真机为 16sp）
        assertTrue(UnitRenderer.labelTextSize(0.0011f) >= 16f)
    }

    @Test
    fun `zoomed in label size does not exceed 40f`() {
        // 放大后字号有上限，防过大（真机为 40sp）
        assertTrue(UnitRenderer.labelTextSize(0.01f) <= 40f)
    }
}
