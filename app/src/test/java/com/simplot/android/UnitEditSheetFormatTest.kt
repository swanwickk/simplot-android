package com.simplot.android

import com.simplot.android.ui.components.formatCourseSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 契约8：UnitEditSheet 航向/航速数值格式化纯函数（去尾零）。
 * 217.0 → "217"、12.5 → "12.5"、0.0 → "0"、40.0 → "40"（默认值显示用）。
 */
class UnitEditSheetFormatTest {

    @Test
    fun `format course speed trims trailing zeros`() {
        assertEquals("217", formatCourseSpeed(217.0))
        assertEquals("12.5", formatCourseSpeed(12.5))
        assertEquals("0", formatCourseSpeed(0.0))
        assertEquals("40", formatCourseSpeed(40.0))
    }

    @Test
    fun `format course speed keeps fraction for non-integers`() {
        assertEquals("217.5", formatCourseSpeed(217.5))
        assertEquals("0.5", formatCourseSpeed(0.5))
    }
}
