package com.simplot.android

import com.simplot.android.data.util.TimeUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/** T1：脏时间回原值+logError（原回 now 会跳变） */
class T1TimeUtilRobustnessTest {
    @Test fun `advance invalid time returns original`() {
        val dirty = "not-a-time"
        assertEquals(dirty, TimeUtil.advance(dirty, 3.0))
        assertEquals("", TimeUtil.advance("", 3.0))
        assertEquals("   ", TimeUtil.advance("   ", 3.0))
    }
    @Test fun `advance valid time still works`() {
        assertEquals("2026-01-01 00:03:00", TimeUtil.advance("2026-01-01 00:00:00", 3.0))
        assertEquals("2026-01-01 00:01:00", TimeUtil.advance("2026-01-01 00:00:00", 1.0))
    }
}
