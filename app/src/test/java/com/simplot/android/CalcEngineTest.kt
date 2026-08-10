package com.simplot.android

import com.simplot.android.data.util.CoordUtil
import com.simplot.android.domain.engine.CalcEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 计算引擎测试（文档 §4.3）：方位/距离/新位置/到达时间。
 */
class CalcEngineTest {

    @Test
    fun `bearing north is 0`() {
        // 从 (0,0) 到 (0, 100000)（正北，Y 北为正）
        assertEquals(0.0, CalcEngine.bearing(0, 0, 0, 100000), 1e-9)
    }

    @Test
    fun `bearing east is 90`() {
        assertEquals(90.0, CalcEngine.bearing(0, 0, 100000, 0), 1e-9)
    }

    @Test
    fun `bearing southwest is 225`() {
        assertEquals(225.0, CalcEngine.bearing(0, 0, -100000, -100000), 1e-9)
    }

    @Test
    fun `range in nm`() {
        // 100000 文件单位 = 1 海里
        assertEquals(1.0, CalcEngine.rangeNm(0, 0, 100000, 0), 1e-9)
        assertEquals(5.0, CalcEngine.rangeNm(0, 0, 0, 500000), 1e-9)
    }

    @Test
    fun `newPosition east 10nm`() {
        val (x, y) = CalcEngine.newPosition(0, 0, 90.0, 10.0)
        assertEquals(CoordUtil.nmToFile(10.0), x)
        assertEquals(0L, y)
    }

    @Test
    fun `newPosition north 5nm`() {
        val (x, y) = CalcEngine.newPosition(0, 0, 0.0, 5.0)
        assertEquals(0L, x)
        assertEquals(CoordUtil.nmToFile(5.0), y)
    }

    @Test
    fun `arriveTime at 30kts over 15nm is 30min later`() {
        val t = CalcEngine.arriveTime("2026-01-01 00:00:00", 15.0, 30.0)
        assertNotNull(t)
        assertEquals("2026-01-01 00:30:00", t)
    }

    @Test
    fun `arriveTime zero speed is null`() {
        assertNull(CalcEngine.arriveTime("2026-01-01 00:00:00", 15.0, 0.0))
    }
}
