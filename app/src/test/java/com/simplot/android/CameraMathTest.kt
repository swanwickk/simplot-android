package com.simplot.android

import com.simplot.android.render.CameraMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CameraMath 纯数学测试（架构重构 Phase 1：视口数学与 Compose 解耦后可单测）。
 */
class CameraMathTest {

    private val cw = 1000
    private val ch = 800

    @Test
    fun `world to screen roundtrip`() {
        val zoom = 0.0015f
        val cx = 500000L
        val cy = 200000L
        val (sx, sy) = CameraMath.worldToScreen(500000L, 200000L, cx, cy, zoom, cw, ch)
        // 中心点 → 画布中心
        assertEquals(500f, sx, 0.001f)
        assertEquals(400f, sy, 0.001f)
        // 反向
        val (wx, wy) = CameraMath.screenToWorld(sx, sy, cx, cy, zoom, cw, ch)
        assertEquals(500000L, wx)
        assertEquals(200000L, wy)
    }

    @Test
    fun `pan moves center opposite to drag`() {
        // 向右拖 100px → 世界中心左移 round(100/0.0015)=66667
        val (nx, _) = CameraMath.pan(100f, 0f, 500000L, 200000L, 0.0015f)
        assertEquals(500000L - 66667L, nx)
    }

    @Test
    fun `zoom at anchor keeps anchor world point fixed`() {
        val zoom = 0.0015f
        val cx = 500000L
        val cy = 200000L
        val anchorX = 200f
        val anchorY = 300f
        val before = CameraMath.screenToWorld(anchorX, anchorY, cx, cy, zoom, cw, ch)
        val (nz, nx, ny) = CameraMath.zoomAt(2f, anchorX, anchorY, zoom, cx, cy, 0.00001f, 0.05f, cw, ch)
        assertEquals(0.003f, nz, 0.0001f)
        val after = CameraMath.screenToWorld(anchorX, anchorY, nx, ny, nz, cw, ch)
        assertEquals(before.first, after.first)
        assertEquals(before.second, after.second)
    }

    @Test
    fun `fit bounds centers and zooms to world extent`() {
        val (zoom, cx, cy) = CameraMath.fitBounds(0L, 1000000L, 0L, 800000L, cw, ch, 0.00001f, 0.05f)
        assertEquals(500000L, cx)
        assertEquals(400000L, cy)
        // zoom 受 padding 限制：min(840/1e6, 640/8e5)
        assertEquals(0.0008f, zoom, 0.0001f)
    }

    @Test
    fun `world y flipped north is up`() {
        // 翻转（反馈①⑤）：center(0,0) zoom 0.0015 → 北（wy=100000）在屏幕上方 sy < H/2；南（wy=-100000）在下方
        val (_, syN) = CameraMath.worldToScreen(0L, 100000L, 0L, 0L, 0.0015f, cw, ch)
        assertTrue("北应在上方 sy=${syN}", syN < ch / 2f)
        // 定量：400 - 100000×0.0015 = 250
        assertEquals(250f, syN, 0.001f)
        val (_, syS) = CameraMath.worldToScreen(0L, -100000L, 0L, 0L, 0.0015f, cw, ch)
        assertTrue("南应在下方 sy=${syS}", syS > ch / 2f)
        assertEquals(550f, syS, 0.001f)
    }

    @Test
    fun `screen to world north of center`() {
        // 翻转（反馈①⑤）：屏幕中心上方 (500, 300) → 世界北侧 wy > centerY；roundtrip 保持
        val zoom = 0.0015f
        val cx = 500000L
        val cy = 200000L
        val (wx, wy) = CameraMath.screenToWorld(500f, 300f, cx, cy, zoom, cw, ch)
        assertEquals(500000L, wx)
        // wy = (400-300)/0.0015 + 200000 = 266667（北 66667 单位）
        assertEquals(200000L + 66667L, wy)
        // roundtrip：变换互逆
        val (sx, sy) = CameraMath.worldToScreen(wx, wy, cx, cy, zoom, cw, ch)
        assertEquals(500f, sx, 0.001f)
        assertEquals(300f, sy, 0.001f)
    }

    @Test
    fun `pan vertical sign follows flip`() {
        // 翻转（反馈①⑤）：内容下拉（deltaSy=+100）→ 中心 Y 增加（round(100/0.0015)=66667）
        val (_, ny) = CameraMath.pan(0f, 100f, 500000L, 200000L, 0.0015f)
        assertEquals(200000L + 66667L, ny)
        // X 分量语义不变（向右拖 → 中心 X 减小）
        val (nx, _) = CameraMath.pan(100f, 0f, 500000L, 200000L, 0.0015f)
        assertEquals(500000L - 66667L, nx)
    }

    @Test
    fun `zoom clamped to min and max`() {
        val (zoom1, _, _) = CameraMath.zoomAt(1000f, 0f, 0f, 0.0015f, 0L, 0L, 0.00001f, 0.05f, cw, ch)
        assertEquals(0.05f, zoom1, 0.0001f)
        val (zoom2, _, _) = CameraMath.zoomAt(0.000001f, 0f, 0f, 0.0015f, 0L, 0L, 0.00001f, 0.05f, cw, ch)
        assertEquals(0.00001f, zoom2, 0.0001f)
    }
}
