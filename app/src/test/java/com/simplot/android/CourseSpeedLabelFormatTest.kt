package com.simplot.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 反馈㉕：航向/航速标签紧凑格式测试。
 * 格式："190°/28kts"（无 Course/Speed 字样，度/节 斜杠分隔）。
 * drawUnitLabel 内为字符串模板，此处锁定格式约定（与实现同构的纯函数）。
 */
class CourseSpeedLabelFormatTest {

    /** 与 SceneCanvas.drawUnitLabel 同构的格式函数（提取自实现） */
    private fun formatCs(courseDeg: Double, speedKnots: Double): String =
        "${courseDeg.toInt()}°/${speedKnots.toInt()}kts"

    @Test
    fun `course speed compact format`() {
        assertEquals("190°/28kts", formatCs(190.0, 28.0))
        assertEquals("0°/0kts", formatCs(0.0, 0.0))
        assertEquals("359°/30kts", formatCs(359.7, 30.4))
    }
}
