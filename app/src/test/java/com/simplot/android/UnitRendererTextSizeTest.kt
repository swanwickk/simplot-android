package com.simplot.android

import com.simplot.android.render.UnitRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约6 字号微调回归测试（反馈㉖）：单测下默认 16f、下限 13f、上限 32f（真机乘 density 后为 13sp~32sp）。
 */
class UnitRendererTextSizeTest {

    @Test
    fun `default zoom label size is 16f`() {
        assertEquals(16f, UnitRenderer.labelTextSize(0.0015f), 0.001f)
    }

    @Test
    fun `la plata zoom label size stays at least 13f`() {
        // 拉普拉塔默认 zoom≈0.0011：16 × 0.73 = 11.7 → clamp 到 13f（真机为 13sp）
        assertTrue(UnitRenderer.labelTextSize(0.0011f) >= 13f)
    }

    @Test
    fun `zoomed in label size does not exceed 32f`() {
        // 放大后字号有上限，防过大（真机为 32sp）
        assertTrue(UnitRenderer.labelTextSize(0.01f) <= 32f)
    }
}
