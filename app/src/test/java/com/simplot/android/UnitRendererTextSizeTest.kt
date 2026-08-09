package com.simplot.android

import com.simplot.android.render.UnitRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约6 字号加大回归测试：默认 zoom=24f、拉普拉塔 zoom 不再小于 18f、放大不超过 48f。
 */
class UnitRendererTextSizeTest {

    @Test
    fun `default zoom label size is 24f`() {
        assertEquals(24f, UnitRenderer.labelTextSize(0.0015f), 0.001f)
    }

    @Test
    fun `la plata zoom label size stays at least 18f`() {
        // 拉普拉塔默认 zoom≈0.0011：24 × 0.73 = 17.6 → clamp 到 18f，不再小于按钮文字
        assertTrue(UnitRenderer.labelTextSize(0.0011f) >= 18f)
    }

    @Test
    fun `zoomed in label size does not exceed 48f`() {
        // 放大后字号有上限，防过大
        assertTrue(UnitRenderer.labelTextSize(0.01f) <= 48f)
    }
}
