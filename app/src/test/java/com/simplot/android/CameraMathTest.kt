package com.simplot.android

import com.simplot.android.render.CameraMath
import org.junit.Assert.assertEquals
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
    fun `zoom clamped to min and max`() {
        val (zoom1, _, _) = CameraMath.zoomAt(1000f, 0f, 0f, 0.0015f, 0L, 0L, 0.00001f, 0.05f, cw, ch)
        assertEquals(0.05f, zoom1, 0.0001f)
        val (zoom2, _, _) = CameraMath.zoomAt(0.000001f, 0f, 0f, 0.0015f, 0L, 0L, 0.00001f, 0.05f, cw, ch)
        assertEquals(0.00001f, zoom2, 0.0001f)
    }
}
