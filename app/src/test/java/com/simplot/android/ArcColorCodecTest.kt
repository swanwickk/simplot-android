package com.simplot.android

import com.simplot.android.data.codec.ArcColorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 弧颜色编解码（G 选色器）：纯 Kotlin，JVM 直接单测。
 *
 * 覆盖：&h/&H/# 前缀、6/8 位 hex、非法输入回退默认色、规范回写格式、
 * HSV 转换与往返、与 ArcRenderer.parseColor 委托语义一致性（黄色回退）。
 */
class ArcColorCodecTest {
    // tryParseVbColor 返回 ARGB（Alpha 恒 0xFF），RGB 取输入右 6 位

    // ---- tryParseVbColor：合法格式 ----
    @Test
    fun `parse 8位 vb 格式 黄色`() =
        assertEquals(0xFFFFFF00.toInt(), ArcColorCodec.tryParseVbColor("&h00FFFF00"))

    @Test
    fun `parse 8位 vb 格式 蓝色`() =
        assertEquals(0xFF0000FF.toInt(), ArcColorCodec.tryParseVbColor("&h000000FF"))

    @Test
    fun `parse 8位 vb 带 alpha 忽略高位`() =
        assertEquals(0xFFFF0000.toInt(), ArcColorCodec.tryParseVbColor("&h80FF0000"))

    @Test
    fun `parse 6位 vb 格式 红色`() =
        assertEquals(0xFFFF0000.toInt(), ArcColorCodec.tryParseVbColor("&hFF0000"))

    @Test
    fun `parse 大写H前缀`() =
        assertEquals(0xFF00FF00.toInt(), ArcColorCodec.tryParseVbColor("&H00FF00"))

    @Test
    fun `parse 小写hex`() =
        assertEquals(0xFFAABBCC.toInt(), ArcColorCodec.tryParseVbColor("&h00aabbcc"))

    @Test
    fun `parse 井号前缀`() =
        assertEquals(0xFF336699.toInt(), ArcColorCodec.tryParseVbColor("#336699"))

    @Test
    fun `parse 带首尾空白`() =
        assertEquals(0xFF112233.toInt(), ArcColorCodec.tryParseVbColor("  &h00112233  "))

    // ---- tryParseVbColor：非法 → null ----
    @Test
    fun `parse null 返回 null`() = assertNull(ArcColorCodec.tryParseVbColor(null))

    @Test
    fun `parse 空串 返回 null`() = assertNull(ArcColorCodec.tryParseVbColor(""))

    @Test
    fun `parse 仅前缀 返回 null`() = assertNull(ArcColorCodec.tryParseVbColor("&h"))

    @Test
    fun `parse 5位不足 返回 null`() = assertNull(ArcColorCodec.tryParseVbColor("&h12345"))

    @Test
    fun `parse 非法hex 返回 null`() = assertNull(ArcColorCodec.tryParseVbColor("&hZZZZZZ"))

    @Test
    fun `parse 非法字符混合 返回 null`() = assertNull(ArcColorCodec.tryParseVbColor("&h00FF0G"))

    // ---- parseVbColor：fallback 默认色（黄色 0xFFFF00 → ARGB 0xFFFFFF00） ----
    @Test
    fun `parseVbColor null 回退默认黄`() =
        assertEquals(ArcColorCodec.DEFAULT_COLOR, ArcColorCodec.parseVbColor(null))

    @Test
    fun `parseVbColor 非法hex 回退默认黄`() =
        assertEquals(ArcColorCodec.DEFAULT_COLOR, ArcColorCodec.parseVbColor("&hOOPS"))

    @Test
    fun `parseVbColor 空串 回退默认黄`() =
        assertEquals(ArcColorCodec.DEFAULT_COLOR, ArcColorCodec.parseVbColor(""))

    // ---- toVbColor：规范回写格式（与桌面存档字节互通） ----
    @Test
    fun `toVbColor 输出 8位 &h00RRGGBB`() =
        assertEquals("&h00FFFF00", ArcColorCodec.toVbColor(0xFFFFFF00.toInt()))

    @Test
    fun `toVbColor 忽略 alpha 仅取低24位`() =
        assertEquals("&h00FF0000", ArcColorCodec.toVbColor(0x80FF0000.toInt()))

    @Test
    fun `toVbColor 黑色`() =
        assertEquals("&h00000000", ArcColorCodec.toVbColor(0xFF000000.toInt()))

    @Test
    fun `toVbColor 大写hex`() =
        assertEquals("&h00A1B2C3", ArcColorCodec.toVbColor(0xFFA1B2C3.toInt()))

    // ---- 往返：parseVbColor(toVbColor(c)) == c ----
    @Test
    fun `roundtrip 各色一致`() {
        listOf(0xFFFFFF00.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
               0xFF0000FF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
               0xFF336699.toInt(), 0xFF112233.toInt())
            .forEach { c -> assertEquals(c, ArcColorCodec.parseVbColor(ArcColorCodec.toVbColor(c))) }
    }

    @Test
    fun `roundtrip 存档原串解析回写后再解析一致`() {
        listOf("&h00FFFF00", "&hFF0000", "&H00FF0000", "#336699")
            .forEach { raw ->
                val parsed = ArcColorCodec.parseVbColor(raw)
                assertEquals(parsed, ArcColorCodec.parseVbColor(ArcColorCodec.toVbColor(parsed)))
            }
    }

    // ---- HSV 转换 ----
    @Test
    fun `hsv 黄色 60度 全饱和 全亮度`() {
        val (h, s, v) = ArcColorCodec.rgbToHsv(0xFFFFFF00.toInt())
        assertEquals(60f, h, 0.5f)
        assertEquals(1f, s, 0.001f)
        assertEquals(1f, v, 0.001f)
    }

    @Test
    fun `hsv 红色 0度`() {
        val (h, s, v) = ArcColorCodec.rgbToHsv(0xFFFF0000.toInt())
        assertEquals(0f, h, 0.5f)
        assertEquals(1f, s, 0.001f)
        assertEquals(1f, v, 0.001f)
    }

    @Test
    fun `hsv 黑色 饱和度为0`() {
        val (h, s, v) = ArcColorCodec.rgbToHsv(0xFF000000.toInt())
        assertEquals(0f, s, 0.001f)
        assertEquals(0f, v, 0.001f)
    }

    @Test
    fun `hsvToRgb 60度全饱和 返回黄色`() = assertEquals(0xFFFFFF00.toInt(), ArcColorCodec.hsvToRgb(60f, 1f, 1f))

    @Test
    fun `hsvToRgb hue 超范围取模`() = assertEquals(0xFFFFFF00.toInt(), ArcColorCodec.hsvToRgb(420f, 1f, 1f))

    @Test
    fun `hsv 往返误差 每通道不超过2`() {
        listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
               0xFFFFFF00.toInt(), 0xFF336699.toInt(), 0xFF804020.toInt(), 0xFF112233.toInt())
            .forEach { c ->
                val (h, s, v) = ArcColorCodec.rgbToHsv(c)
                val back = ArcColorCodec.hsvToRgb(h, s, v)
                val dr = Math.abs(((back shr 16) and 0xFF) - ((c shr 16) and 0xFF))
                val dg = Math.abs(((back shr 8) and 0xFF) - ((c shr 8) and 0xFF))
                val db = Math.abs((back and 0xFF) - (c and 0xFF))
                assert(dr <= 2 && dg <= 2 && db <= 2) { "roundtrip err: $c -> $back" }
            }
    }
}
