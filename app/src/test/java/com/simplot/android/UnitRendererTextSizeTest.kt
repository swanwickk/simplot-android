package com.simplot.android

import com.simplot.android.render.UnitRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约6 字号加大回归测试（反馈㉑ 再放大）：默认 zoom=32f、缩手下限 24f、放大上限 64f。
 */
class UnitRendererTextSizeTest {

    @Test
    fun `default zoom label size is 32f`() {
        assertEquals(32f, UnitRenderer.labelTextSize(0.0015f), 0.001f)
    }

    @Test
    fun `la plata zoom label size stays at least 24f`() {
        // 拉普拉塔默认 zoom≈0.0011：32 × 0.73 = 23.4 → clamp 到 24f，不再小于按钮文字
        assertTrue(UnitRenderer.labelTextSize(0.0011f) >= 24f)
    }

    @Test
    fun `zoomed in label size does not exceed 64f`() {
        // 放大后字号有上限，防过大
        assertTrue(UnitRenderer.labelTextSize(0.01f) <= 64f)
    }
}
