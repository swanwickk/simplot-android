package com.simplot.android

import com.simplot.android.render.UnitRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单位渲染纯函数测试（反馈⑥/反馈㉒）：标签字号/锚点偏移系数随 zoom 缩放 + clamp（density 感知）。
 */
class UnitRendererTest {

    @Test
    fun `label text size scales with zoom`() {
        // 单测环境 d=1：默认 zoom（LABEL_BASE_ZOOM）→ 20f；下限 16f；上限 40f
        assertEquals(20f, UnitRenderer.labelTextSize(0.0015f), 0.001f)
        assertEquals(16f, UnitRenderer.labelTextSize(0.0007f), 0.001f)   // 撞下限（拉普拉塔默认视野）
        assertEquals(40f, UnitRenderer.labelTextSize(0.05f), 0.001f)     // 撞上限（放大）
    }

    @Test
    fun `label text size clamps at extremes`() {
        // 极远缩放 → 下限 16f；极大放大 → 上限 40f
        assertEquals(16f, UnitRenderer.labelTextSize(0.00001f), 0.001f)
        assertEquals(40f, UnitRenderer.labelTextSize(1f), 0.001f)
    }

    @Test
    fun `icon size scales with zoom`() {
        // 契约7/反馈⑩：默认 zoom→12dp；放大撞上限 32dp（不遮挡航向标）；缩小撞下限 10dp
        // 注：单测环境 appContext==null → density=1，断言即 dp 数值
        assertEquals(12f, UnitRenderer.iconSizePx(0.0015f), 0.001f)
        assertEquals(32f, UnitRenderer.iconSizePx(0.05f), 0.001f)
        assertEquals(10f, UnitRenderer.iconSizePx(0.0005f), 0.001f)
    }

    @Test
    fun `label scale k clamps between 07 and 25`() {
        // 锚点偏移系数：默认 1.0，clamp [0.7, 2.5]
        assertEquals(0.7f, UnitRenderer.labelScaleK(0.00001f), 0.001f)
        assertEquals(1f, UnitRenderer.labelScaleK(0.0015f), 0.001f)
        assertEquals(2.5f, UnitRenderer.labelScaleK(0.05f), 0.001f)
    }
}
