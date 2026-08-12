package com.simplot.android

import com.simplot.android.ui.ShowSide
import com.simplot.android.ui.showSideLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G30：Show Side 视图过滤纯逻辑（桌面 Show Side 菜单 All/Blue/Red）。
 * ALL 不过滤；BLUE/RED 只放行对应阵营；未知/空阵营按 ALL 语义不过滤。
 */
class ShowSideFilterTest {

    @Test
    fun `all allows every side`() {
        assertTrue(ShowSide.ALL.allows("Blue"))
        assertTrue(ShowSide.ALL.allows("Red"))
        assertTrue(ShowSide.ALL.allows("Neutral"))
        assertTrue(ShowSide.ALL.allows("Unknown"))
        assertTrue(ShowSide.ALL.allows(null))
        assertTrue(ShowSide.ALL.allows(""))
    }

    @Test
    fun `blue only allows blue`() {
        assertTrue(ShowSide.BLUE.allows("Blue"))
        assertFalse(ShowSide.BLUE.allows("Red"))
        assertFalse(ShowSide.BLUE.allows("Neutral"))
        assertFalse(ShowSide.BLUE.allows(null))
    }

    @Test
    fun `red only allows red`() {
        assertTrue(ShowSide.RED.allows("Red"))
        assertFalse(ShowSide.RED.allows("Blue"))
        assertFalse(ShowSide.RED.allows("Neutral"))
        assertFalse(ShowSide.RED.allows(null))
    }

    @Test
    fun `cycle order is all blue red`() {
        // 纯逻辑侧验证三态枚举的 sideName 映射（UI 循环由 ViewModel 持有，此处验证映射表）
        assertEquals(null, ShowSide.ALL.sideName)
        assertEquals("Blue", ShowSide.BLUE.sideName)
        assertEquals("Red", ShowSide.RED.sideName)
    }

    @Test
    fun `labels are chinese`() {
        assertEquals("全部", showSideLabel(ShowSide.ALL))
        assertEquals("蓝方", showSideLabel(ShowSide.BLUE))
        assertEquals("红方", showSideLabel(ShowSide.RED))
    }
}
