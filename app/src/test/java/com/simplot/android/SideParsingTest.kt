package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.Unit
import com.simplot.android.render.UnitRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 验证 Side 阵营解析（Bug 1 排查：示例剧本"都是蓝的"） */
class SideParsingTest {

    @Test
    fun `side parsed from json`() {
        val json = """{"IdNum":"S001","Side":"Red","Name":"沙恩霍斯特","UnitType":"Battleship","X":0,"Y":0}"""
        val u: Unit = JsonUtil.gson.fromJson(json, Unit::class.java)
        assertEquals("Red", u.side)
    }

    @Test
    fun `full scenario side distribution`() {
        val f = JsonUtil.fromJson(resourceText("scenarios/冰海巨兽.json"))
        val red = f.units.count { it.side == "Red" }
        val blue = f.units.count { it.side == "Blue" }
        assertEquals(2, red)
        assertEquals(5, blue)
        // 解析 → 渲染色值管道：红方单位渲染为 Red 色、蓝方单位渲染为 Blue 色
        f.units.forEach { u ->
            val expected = if (u.side == "Red") UnitRenderer.colorOf("Red") else UnitRenderer.colorOf("Blue")
            assertEquals("单位 ${u.name}（side=${u.side}）渲染色值", expected, UnitRenderer.colorOf(u.side))
        }
    }

    @Test
    fun `la plata scenario side distribution`() {
        val f = JsonUtil.fromJson(resourceText("scenarios/拉普拉塔河口海战.json"))
        assertEquals(1, f.units.count { it.side == "Red" })
        assertEquals(3, f.units.count { it.side == "Blue" })
    }

    @Test
    fun `red and blue render colors differ`() {
        // 红蓝阵营渲染色值必须不同（否则全军一个色无法区分阵营）
        assertNotEquals(UnitRenderer.colorOf("Red"), UnitRenderer.colorOf("Blue"))
    }

    /** 读取 test resources 场景文本；缺失即断言失败（不再 `?: return` 静默空转） */
    private fun resourceText(path: String): String {
        val text = javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("test resources 缺少 $path", text)
        return text!!
    }
}
