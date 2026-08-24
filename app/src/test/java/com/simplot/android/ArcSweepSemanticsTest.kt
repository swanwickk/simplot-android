package com.simplot.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R1-v3 回归锁：ArcAngle=0 必须按整圆（360°）绘制。
 *
 * 背景：示例存档（第三次所罗门海战）所有雷达弧 FC L/M/S 的 ArcAngle 均为 0，
 * 桌面反编译 DrawSensors 确认桌面版对 ArcAngle=0 画整圆。历史两版缺陷：
 * - arcfix1：arcAngle<=0 提前 return → 完全不画；
 * - arcfix2：仅取消提前 return，sweep 仍为 0 → drawArc 扫 0° 仍等于没画。
 * 本测试锁定「0 → 360」的映射规则（纯 Kotlin 抽取，渲染层直接引用同一函数）。
 */
class ArcSweepSemanticsTest {

    @Test
    fun `ArcAngle=0 映射为整圆 360 度`() {
        assertEquals(360f, com.simplot.android.render.ArcRenderer.sweepOf(0.0))
    }

    @Test
    fun `正角度原样保留`() {
        assertEquals(45f, com.simplot.android.render.ArcRenderer.sweepOf(45.0))
        assertEquals(360f, com.simplot.android.render.ArcRenderer.sweepOf(360.0))
        assertEquals(0.5f, com.simplot.android.render.ArcRenderer.sweepOf(0.5))
    }

    @Test
    fun `负角度视为无效（返回 null 不绘制）`() {
        assertEquals(null, com.simplot.android.render.ArcRenderer.sweepOf(-1.0))
    }
}
