package com.simplot.android

import com.simplot.android.data.util.CoordUtil
import com.simplot.android.data.util.CoordUtil.DistanceUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 距离单位切换与格式化测试（海里 / 码 / 米）。
 */
class DistanceUnitTest {

    @Test
    fun `distance unit cycles correctly`() {
        assertEquals(DistanceUnit.YD, DistanceUnit.NM.next())
        assertEquals(DistanceUnit.M, DistanceUnit.YD.next())
        assertEquals(DistanceUnit.NM, DistanceUnit.M.next())
    }

    @Test
    fun `distance formatting in nautical miles`() {
        assertEquals("1.0 nm", CoordUtil.formatDistance(1.0, DistanceUnit.NM))
        assertEquals("12.5 nm", CoordUtil.formatDistance(12.53, DistanceUnit.NM))
        assertEquals("0.5 nm", CoordUtil.formatDistance(0.5, DistanceUnit.NM))
    }

    @Test
    fun `distance formatting in yards`() {
        // 1 nm = 2025.37 yd
        assertEquals("2025 yd", CoordUtil.formatDistance(1.0, DistanceUnit.YD))
        assertEquals("1013 yd", CoordUtil.formatDistance(0.5, DistanceUnit.YD))
        assertEquals("20254 yd", CoordUtil.formatDistance(10.0, DistanceUnit.YD))
    }

    @Test
    fun `distance formatting in meters`() {
        // 1 nm = 1852.0 m
        assertEquals("1852 m", CoordUtil.formatDistance(1.0, DistanceUnit.M))
        assertEquals("926 m", CoordUtil.formatDistance(0.5, DistanceUnit.M))
        assertEquals("18.5 km", CoordUtil.formatDistance(10.0, DistanceUnit.M))
    }
}
