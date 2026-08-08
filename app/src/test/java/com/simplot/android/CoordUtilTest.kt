package com.simplot.android

import com.simplot.android.data.util.CoordUtil
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * CoordUtil 距离/方位公式单测（反馈②数据层依赖）。
 *
 * 断言与桌面版 scn_tool.py 一致：
 * - 文件 X/Y = 实际值（海里）× 100000（整数定点）
 * - 罗盘方位：0=北，顺时针；Y 轴向北为正
 * - distanceNm = hypot(dx,dy)/1e5 海里
 */
class CoordUtilTest {

    @Test
    fun `same point distance is zero`() {
        assertEquals(0.0, CoordUtil.distanceNm(0, 0, 0, 0), 1e-9)
        assertEquals(0.0, CoordUtil.bearingDeg(0, 0, 0, 0), 1e-9)
    }

    @Test
    fun `north one nmi distance one and bearing north`() {
        // (0,0) → (0,100000)：正北 1 海里，方位 0°
        assertEquals(1.0, CoordUtil.distanceNm(0, 0, 0, 100000), 1e-6)
        assertEquals(0.0, CoordUtil.bearingDeg(0, 0, 0, 100000), 1e-6)
    }

    @Test
    fun `east one nmi bearing east`() {
        // (0,0) → (100000,0)：正东 1 海里，方位 90°
        assertEquals(1.0, CoordUtil.distanceNm(0, 0, 100000, 0), 1e-6)
        assertEquals(90.0, CoordUtil.bearingDeg(0, 0, 100000, 0), 1e-6)
    }

    @Test
    fun `south and west bearings`() {
        // 正南 1 海里 → 180°；正西 1 海里 → 270°
        assertEquals(1.0, CoordUtil.distanceNm(0, 0, 0, -100000), 1e-6)
        assertEquals(180.0, CoordUtil.bearingDeg(0, 0, 0, -100000), 1e-6)
        assertEquals(1.0, CoordUtil.distanceNm(0, 0, -100000, 0), 1e-6)
        assertEquals(270.0, CoordUtil.bearingDeg(0, 0, -100000, 0), 1e-6)
    }

    @Test
    fun `diagonal northeast distance sqrt2 bearing 45`() {
        // (0,0) → (100000,100000)：斜向 45°，距离 sqrt(2) 海里
        assertEquals(sqrt(2.0), CoordUtil.distanceNm(0, 0, 100000, 100000), 1e-6)
        assertEquals(45.0, CoordUtil.bearingDeg(0, 0, 100000, 100000), 1e-6)
    }

    @Test
    fun `large distance five nmi`() {
        // (0,0) → (500000,0)：5 海里
        assertEquals(5.0, CoordUtil.distanceNm(0, 0, 500000, 0), 1e-6)
    }

    @Test
    fun `negative coordinates southwest distance sqrt8 bearing 225`() {
        // 象限 III（dx、dy 均为负）：(100000,200000) → (-100000,0)，dx=-200000(-2 nmi)、dy=-200000(-2 nmi)
        // → dist = sqrt(2²+2²) = sqrt(8)≈2.828；方位 atan2(-2,-2) = 180+45 = 225°（验证象限处理）
        // ⚠️ 合同原文终点写 (-100000,-200000)，但该终点 dx=-2 nmi、dy=-4 nmi → dist=sqrt(20)≈4.472、方位≈206.565°，
        //    与合同自身断言（sqrt(8)/225°）矛盾；此处按合同断言的象限 III 意图修正终点为 (-100000,0)。
        assertEquals(sqrt(8.0), CoordUtil.distanceNm(100000, 200000, -100000, 0), 1e-6)
        assertEquals(225.0, CoordUtil.bearingDeg(100000, 200000, -100000, 0), 1e-6)
    }
}
