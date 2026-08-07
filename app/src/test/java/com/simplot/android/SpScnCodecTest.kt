package com.simplot.android

import com.simplot.android.data.codec.SpScnCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SpScn 编解码单元测试：验证与桌面版字节级兼容
 */
class SpScnCodecTest {

    @Test
    fun `encode then decode returns original`() {
        val plain = "{\"File\":\"Red\",\"Units\":[]}".toByteArray(Charsets.UTF_8)
        val encrypted = SpScnCodec.encode(plain)
        val decrypted = SpScnCodec.decode(encrypted)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun `obfuscation matches known example`() {
        // 桌面版: 明文 {"File":"Red"  →  密文 |#Gjmf#;#Sfe#
        val plain = "{\"File\":\"Red\"".toByteArray(Charsets.UTF_8)
        val encrypted = SpScnCodec.encode(plain)
        assertEquals("|#Gjmf#;#Sfe#", String(encrypted, Charsets.UTF_8))
    }

    @Test
    fun `json file bytes end with CRLF`() {
        val bytes = SpScnCodec.toJsonFileBytes("{}")
        assertEquals("\r\n", String(bytes.copyOfRange(bytes.size - 2, bytes.size)))
        assertEquals("{}", String(bytes.copyOfRange(0, bytes.size - 2)))
    }

    @Test
    fun `scn file bytes tolerate trailing marker`() {
        val json = "{\"File\":\"Blue\"}"
        val bytes = SpScnCodec.toScnFileBytes(json)
        val text = SpScnCodec.fromScnFileBytes(bytes)
        assertEquals(json, text)
    }

    @Test
    fun `roundtrip json file`() {
        val json = "{\"File\":\"Referee\",\"Units\":[{\"IdNum\":\"S001\"}]}"
        val bytes = SpScnCodec.toJsonFileBytes(json)
        val text = SpScnCodec.fromJsonFileBytes(bytes)
        assertEquals(json, text)
    }
}
