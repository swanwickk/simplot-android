package com.simplot.android

import com.google.gson.GsonBuilder
import com.simplot.android.domain.model.PlayerSettings
import com.simplot.android.domain.model.SymbolSet
import com.simplot.android.domain.model.SymbolSize
import com.simplot.android.render.BearingRenderer
import com.simplot.android.render.MapRenderer
import com.simplot.android.render.UnitRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次3 显示/渲染定制（G08/G09/G45/G46/G47）纯逻辑测试：
 * - 符号集四选 / 尺寸档 / 精灵图变体映射
 * - 阵营色板（PlayerSettings 颜色键 → Palette）与颜色列表 Load/Save/Reset 辅助
 * - 友军符号 / Dots 点渲染判定
 * - SpeedLeaders 箭头几何（G46）
 * - BeamWidth 波束边界角（G45）
 * - 地图颜色加深/换 alpha 辅助（G09）
 */
class DisplayCustomizationTest {

    // ============ G47：符号集四选 ============

    @Test
    fun `symbol set has four desktop choices`() {
        val sets = SymbolSet.entries
        assertEquals(4, sets.size)
        assertEquals(SymbolSet.CWS_COLOR_FILLED, sets[0])
        assertEquals(SymbolSet.CWS_COLOR_UNFILLED, sets[1])
        assertEquals(SymbolSet.CWS_MONO_FILLED, sets[2])
        assertEquals(SymbolSet.NTDS, sets[3])
        // 桌面 PopupSet 文案
        assertEquals("CWS Color Filled", SymbolSet.CWS_COLOR_FILLED.label)
        assertEquals("CWS Color Unfilled", SymbolSet.CWS_COLOR_UNFILLED.label)
        assertEquals("CWS Mono Filled", SymbolSet.CWS_MONO_FILLED.label)
        assertEquals("NTDS", SymbolSet.NTDS.label)
    }

    @Test
    fun `symbol set fromLabel falls back to color filled`() {
        assertEquals(SymbolSet.NTDS, SymbolSet.fromLabel("NTDS"))
        assertEquals(SymbolSet.CWS_MONO_FILLED, SymbolSet.fromLabel("CWS Mono Filled"))
        assertEquals(SymbolSet.CWS_COLOR_FILLED, SymbolSet.fromLabel("不存在的符号集"))
    }

    @Test
    fun `sprite variant maps to desktop sprite filename suffix`() {
        assertEquals("color_filled", UnitRenderer.spriteVariant(SymbolSet.CWS_COLOR_FILLED))
        assertEquals("color_unfilled", UnitRenderer.spriteVariant(SymbolSet.CWS_COLOR_UNFILLED))
        assertEquals("mono_filled", UnitRenderer.spriteVariant(SymbolSet.CWS_MONO_FILLED))
    }

    // ============ G08：符号尺寸档 ============

    @Test
    fun `symbol size has four levels with monotonic scale`() {
        val sizes = SymbolSize.entries
        assertEquals(4, sizes.size)
        assertEquals(listOf("Dots", "Reduced", "Default", "Enlarged"), sizes.map { it.label })
        // 尺寸档缩放系数单调递增且 Default=1.0
        assertTrue(sizes.map { it.scale }.zipWithNext { a, b -> a < b }.all { it })
        assertEquals(1.0f, SymbolSize.DEFAULT.scale, 0.0001f)
        assertTrue(SymbolSize.DOTS.scale < SymbolSize.REDUCED.scale)
        assertTrue(SymbolSize.ENLARGED.scale > 1.0f)
    }

    @Test
    fun `symbol size fromLabel falls back to default`() {
        assertEquals(SymbolSize.DOTS, SymbolSize.fromLabel("Dots"))
        assertEquals(SymbolSize.ENLARGED, SymbolSize.fromLabel("Enlarged"))
        assertEquals(SymbolSize.DEFAULT, SymbolSize.fromLabel("不存在"))
    }

    // ============ G08：Dots/友军符号 点渲染判定 ============

    @Test
    fun `dot rendering when size is dots or friendly symbols hidden for blue`() {
        // Dots 档：任何阵营都画点
        assertTrue(UnitRenderer.isDotRendering(SymbolSize.DOTS, true, "Blue"))
        assertTrue(UnitRenderer.isDotRendering(SymbolSize.DOTS, true, "Red"))
        // 关闭友军符号：仅 Blue（友方）画点，敌方不受影响
        assertTrue(UnitRenderer.isDotRendering(SymbolSize.DEFAULT, false, "Blue"))
        assertFalse(UnitRenderer.isDotRendering(SymbolSize.DEFAULT, false, "Red"))
        assertFalse(UnitRenderer.isDotRendering(SymbolSize.DEFAULT, false, "Neutral"))
        // 开启友军符号：任何阵营都画完整军标
        assertFalse(UnitRenderer.isDotRendering(SymbolSize.DEFAULT, true, "Blue"))
        assertFalse(UnitRenderer.isDotRendering(SymbolSize.ENLARGED, true, "Red"))
    }

    // ============ G09：阵营色板 ============

    @Test
    fun `default palette matches desktop GetUnitColor semantics`() {
        assertEquals(0xFF005AC8.toInt(), UnitRenderer.colorOf("Blue"))
        assertEquals(0xFFC81E1E.toInt(), UnitRenderer.colorOf("Red"))
        assertEquals(0xFFFFFFFF.toInt(), UnitRenderer.colorOf("Neutral"))
        assertEquals(0xFF787878.toInt(), UnitRenderer.colorOf("All"))
        assertEquals(0xFF5A5A5A.toInt(), UnitRenderer.colorOf("Unknown"))
        assertEquals(0xFF5A5A5A.toInt(), UnitRenderer.colorOf("其他"))
    }

    @Test
    fun `paletteOf maps player settings color keys to side colors`() {
        val s = PlayerSettings.DEFAULT.copy(blueForColor = 0xFF112233, redForColor = 0xFF445566)
        val p = UnitRenderer.paletteOf(s)
        assertEquals(0xFF112233.toInt(), p.sideColor("Blue"))
        assertEquals(0xFF445566.toInt(), p.sideColor("Red"))
        // Neutral/All/Unknown 固定桌面语义，不随玩家颜色键变化
        assertEquals(0xFFFFFFFF.toInt(), p.sideColor("Neutral"))
    }

    @Test
    fun `color key list round trips through withColors`() {
        val s = PlayerSettings.DEFAULT
        val colors = PlayerSettings.colorsOf(s)
        assertEquals(6, colors.size)
        // 与桌面 6 色键顺序一致：背景/网格/蓝/红/陆地/海洋
        assertEquals(s.backgroundColor, colors[0])
        assertEquals(s.gridColor, colors[1])
        assertEquals(s.blueForColor, colors[2])
        assertEquals(s.redForColor, colors[3])
        assertEquals(s.mapLandColor, colors[4])
        assertEquals(s.mapOceanColor, colors[5])
        // 换色后取回一致
        val newColors = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        val s2 = PlayerSettings.withColors(s, newColors)
        assertEquals(newColors, PlayerSettings.colorsOf(s2))
        // 多余颜色忽略、不足覆盖可对齐部分
        assertEquals(PlayerSettings.colorsOf(PlayerSettings.withColors(s, listOf(9L)))[0], 9L)
    }

    @Test
    fun `saved colors snapshot load save reset helpers`() {
        val s = PlayerSettings.DEFAULT.copy(blueForColor = 0xFF000001, redForColor = 0xFF000002)
        // Save：当前 6 色进快照
        val saved = s.copy(savedColors = PlayerSettings.colorsOf(s))
        assertEquals(6, saved.savedColors?.size)
        // Load：快照恢复
        val loaded = PlayerSettings.withColors(PlayerSettings.DEFAULT, saved.savedColors!!)
        assertEquals(0xFF000001, loaded.blueForColor)
        assertEquals(0xFF000002, loaded.redForColor)
        // Reset：恢复默认色并清快照
        val reset = PlayerSettings.withColors(s, PlayerSettings.colorsOf(PlayerSettings.DEFAULT))
            .copy(savedColors = null)
        assertEquals(PlayerSettings.DEFAULT.blueForColor, reset.blueForColor)
        assertEquals(PlayerSettings.DEFAULT.redForColor, reset.redForColor)
        assertNull(reset.savedColors)
    }

    // ============ G09：PlayerSettings 序列化兼容（旧 JSON 无新字段 → 默认值） ============

    @Test
    fun `old settings json without batch3 fields loads with defaults`() {
        val gson = GsonBuilder().create()
        // 模拟旧版本（批次3 之前）持久化的设置 JSON：无 symbolSet/ww2Symbols/symbolSize/友军/标签背景/颜色快照
        val oldJson = """{"playerName":"Player","showGrid":true,"showScaleBar":true,"showLabels":true,"showSpeedLeaders":true,"showSensors":true,"showWeapons":true,"showWaypoints":true,"showFormations":true,"showCities":true,"showCountries":true,"showWaters":true,"showDepths":true,"showDepthKey":true,"showSonar":true,"showEs":true,"backgroundColor":4294370037,"gridColor":2283514012,"blueForColor":4279310536,"redForColor":4294902302,"mapLandColor":2024086146,"mapOceanColor":1088093416}"""
        val loaded = gson.fromJson(oldJson, PlayerSettings::class.java)
        assertNotNull(loaded)
        // 旧字段保留（JSON 原值，非默认色：0xFF1119C8 = 4279310536）
        assertEquals("Player", loaded!!.playerName)
        assertEquals(4279310536L, loaded.blueForColor)
        // 批次3 新字段 → 默认值兜底
        assertEquals(SymbolSet.CWS_COLOR_FILLED, loaded.symbolSet)
        assertFalse(loaded.ww2Symbols)
        assertEquals(SymbolSize.DEFAULT, loaded.symbolSize)
        assertTrue(loaded.showFriendlySymbols)
        assertTrue(loaded.useLabelBackground)
        assertNull(loaded.savedColors)
    }

    @Test
    fun `new settings round trip keeps batch3 fields`() {
        val gson = GsonBuilder().create()
        val s = PlayerSettings.DEFAULT.copy(
            symbolSet = SymbolSet.CWS_MONO_FILLED,
            ww2Symbols = true,
            symbolSize = SymbolSize.ENLARGED,
            showFriendlySymbols = false,
            useLabelBackground = false,
            savedColors = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        )
        val back = gson.fromJson(gson.toJson(s), PlayerSettings::class.java)
        assertEquals(SymbolSet.CWS_MONO_FILLED, back!!.symbolSet)
        assertTrue(back.ww2Symbols)
        assertEquals(SymbolSize.ENLARGED, back.symbolSize)
        assertFalse(back.showFriendlySymbols)
        assertFalse(back.useLabelBackground)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), back.savedColors)
    }

    // ============ G46：SpeedLeaders 箭头几何 ============

    @Test
    fun `speed leader points north when course 0`() {
        val g = UnitRenderer.speedLeaderGeometry(100f, 100f, 0.0, 40f, 8f)
        // 起点 = 圆心沿北偏移 r（屏幕上 y 减小）
        assertEquals(100f, g.startX, 0.001f)
        assertEquals(92f, g.startY, 0.001f)
        // 终点 = 起点再北延 40px
        assertEquals(100f, g.endX, 0.001f)
        assertEquals(52f, g.endY, 0.001f)
        // 箭头 tip = 线终点；两个 base 关于线对称（x 一左一右）
        assertEquals(g.endX, g.arrowTipX, 0.001f)
        assertEquals(g.endY, g.arrowTipY, 0.001f)
        assertEquals(g.arrowBase1X, -g.arrowBase2X + 2 * g.arrowTipX, 0.001f)
        assertEquals(g.arrowBase1Y, g.arrowBase2Y, 0.001f)
    }

    @Test
    fun `speed leader points east when course 90`() {
        val g = UnitRenderer.speedLeaderGeometry(100f, 100f, 90.0, 40f, 8f)
        assertEquals(108f, g.startX, 0.001f)
        assertEquals(100f, g.startY, 0.001f)
        assertEquals(148f, g.endX, 0.001f)
        assertEquals(100f, g.endY, 0.001f)
        // 箭头 base 上下对称
        assertEquals(g.arrowBase1X, g.arrowBase2X, 0.001f)
        assertEquals(g.arrowBase1Y, -g.arrowBase2Y + 2 * g.arrowTipY, 0.001f)
    }

    // ============ G45：BeamWidth 波束边界角 ============

    @Test
    fun `beam edge bearings spread by half width`() {
        val (lo, hi) = BearingRenderer.beamEdgeBearings(90.0, 10.0)
        assertEquals(85.0, lo, 0.0001)
        assertEquals(95.0, hi, 0.0001)
        // 宽度 0 → 边界与中心重合（调用方不画边线）
        val (lo0, hi0) = BearingRenderer.beamEdgeBearings(45.0, 0.0)
        assertEquals(45.0, lo0, 0.0001)
        assertEquals(45.0, hi0, 0.0001)
        // 负宽度按 0 处理
        val (lon, hin) = BearingRenderer.beamEdgeBearings(180.0, -5.0)
        assertEquals(180.0, lon, 0.0001)
        assertEquals(180.0, hin, 0.0001)
    }

    // ============ G09：地图颜色辅助 ============

    @Test
    fun `darker color scales rgb and keeps alpha`() {
        // 0xAARRGGBB：alpha=0x78，RGB=(150,170,130)
        val c = 0x7896AA82.toInt()
        val d = MapRenderer.darkerColor(c, 0.6f)
        assertEquals(0x78, (d ushr 24) and 0xFF)
        assertEquals(90, (d ushr 16) and 0xFF)
        assertEquals(102, (d ushr 8) and 0xFF)
        assertEquals(78, d and 0xFF)
        // factor=1 → 不变；factor=0 → 黑（alpha 保留）
        assertEquals(c, MapRenderer.darkerColor(c, 1f))
        val black = MapRenderer.darkerColor(c, 0f)
        assertEquals(0x78 shl 24, black and 0xFF000000.toInt())
        assertEquals(0, black and 0x00FFFFFF)
    }

    @Test
    fun `with alpha replaces alpha channel`() {
        val c = 0x40C8DCE8.toInt()
        val a = MapRenderer.withAlphaColor(c, 190)
        assertEquals(190, (a ushr 24) and 0xFF)
        assertEquals(0xC8DCE8, a and 0x00FFFFFF)
        // alpha 越界钳制
        assertEquals(255, (MapRenderer.withAlphaColor(c, 999) ushr 24) and 0xFF)
        assertEquals(0, (MapRenderer.withAlphaColor(c, -3) ushr 24) and 0xFF)
    }
}
