package com.simplot.android

import com.simplot.android.data.export.RelativePositionsCsv
import com.simplot.android.data.model.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相对位置 CSV 导出格式测试（桌面版 ExportData.RelativeUnitPositions.Export 约定）。
 * N1：CSV 文本由纯函数生成（可 JVM 单测），验证表头逐字节一致、
 * 列序与参考单位语义（选中单位，无则第一个）符合桌面版。
 */
class RelativePositionsCsvTest {

    /** 参考单位（原点，无高度/深度） */
    private val ref = Unit(idNum = "S001", trackNumber = 2401, name = "REF", x = 0, y = 0)

    /** 正北 10 海里、高度 3000 米的飞机 */
    private val north = Unit(
        idNum = "A002", trackNumber = 2402, name = "NORTH", side = "Blue",
        x = 0, y = 1000000, course = 45000, speed = 12000, altitude = 3000000
    )

    /** 正东 10 海里、深度 1500 米的潜艇 */
    private val east = Unit(
        idNum = "U003", trackNumber = 2403, name = "EAST", side = "Blue",
        x = 1000000, y = 0, course = 90000, speed = 20000, depth = 1500000
    )

    /** 水面单位（无高度/深度 → Alt/Depth 列 0） */
    private val surface = Unit(
        idNum = "S004", trackNumber = 2404, name = "SURF", side = "Blue",
        x = 0, y = -1000000, course = 0, speed = 0
    )

    @Test
    fun `header matches desktop exactly`() {
        val csv = RelativePositionsCsv.build(listOf(ref, north), ref.idNum)
        val header = csv.lineSequence().first()
        assertEquals("TN,X,Y,Course,Speed,Alt/Depth,Bearing,Range NMI,Range Yards,Range Meters", header)
    }

    @Test
    fun `rows relative to selected reference unit`() {
        val csv = RelativePositionsCsv.build(listOf(ref, north, east), ref.idNum)
        val lines = csv.trimEnd().lineSequence().toList()
        assertEquals(3, lines.size) // 表头 + 2 行
        // 参考单位自身不输出
        assertFalse(lines.any { it.startsWith("2401,") })
        // 正北 10 海里：方位 0.0、距离 10.00 海里 / 20253.7 码 / 18520.0 米，Alt/Depth=高度 3000
        assertEquals("2402,0,1000000,45,12,3000,0.0,10.00,20253.7,18520.0", lines[1])
        // 正东 10 海里：方位 90.0，Alt/Depth=深度 1500
        assertEquals("2403,1000000,0,90,20,1500,90.0,10.00,20253.7,18520.0", lines[2])
    }

    @Test
    fun `surface unit altitude depth column is zero`() {
        val csv = RelativePositionsCsv.build(listOf(ref, surface), ref.idNum)
        val row = csv.trimEnd().lineSequence().last()
        assertTrue(row.startsWith("2404,0,-1000000,0,0,0,"))
    }

    @Test
    fun `falls back to first unit when no selection`() {
        val csv = RelativePositionsCsv.build(listOf(ref, north), null)
        assertFalse(csv.lineSequence().any { it.startsWith("2401,") })
        assertTrue(csv.lineSequence().any { it.startsWith("2402,") })
    }

    @Test
    fun `falls back to first unit when selected id unknown`() {
        val csv = RelativePositionsCsv.build(listOf(ref, north), "GONE")
        assertFalse(csv.lineSequence().any { it.startsWith("2401,") })
        assertTrue(csv.lineSequence().any { it.startsWith("2402,") })
    }

    @Test
    fun `empty units produce empty csv`() {
        assertEquals("", RelativePositionsCsv.build(emptyList(), null))
    }
}
