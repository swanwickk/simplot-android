package com.simplot.android.data.codec

import java.io.ByteArrayOutputStream

/**
 * SpScn 存档编解码（与桌面版 SimPlot2 字节级兼容）
 *
 * 编码规则（源自逆向分析 scn_tool.py）：
 * - 明文是标准 JSON 文本（UTF-8）
 * - 每个字节做 ASCII −1 混淆（加密）
 * - `.SpScn` 文件：明文尾部追加 \x0c\t 两个标记字节后再整体混淆
 * - `.json` 文件：明文尾部 \r\n，不混淆
 *
 * 混淆示例: 密文 `|#Gjmf#;#Sfe#` → 明文 `{"File":"Red"`
 */
object SpScnCodec {

    /** 混淆：每个字节 +1（写 SpScn 用） */
    fun encode(plain: ByteArray): ByteArray {
        val out = ByteArray(plain.size)
        for (i in plain.indices) {
            out[i] = ((plain[i].toInt() + 1) and 0xFF).toByte()
        }
        return out
    }

    /** 解混淆：每个字节 −1（读 SpScn 用） */
    fun decode(encrypted: ByteArray): ByteArray {
        val out = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            out[i] = ((encrypted[i].toInt() - 1) and 0xFF).toByte()
        }
        return out
    }

    /**
     * 明文 JSON 序列化为 .json 文件字节（尾部 \r\n，不混淆）
     * 与 scn_tool.write_json 字节级一致
     */
    fun toJsonFileBytes(jsonText: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(jsonText.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * 明文 JSON 序列化为 .SpScn 文件字节（尾部 \x0c\t + 整体 ASCII-1 混淆）
     * 与 scn_tool.write_scn 字节级一致
     */
    fun toScnFileBytes(jsonText: String): ByteArray {
        val plain = ByteArrayOutputStream()
        plain.write(jsonText.toByteArray(Charsets.UTF_8))
        plain.write(byteArrayOf(0x0c, 0x09)) // \x0c\t
        return encode(plain.toByteArray())
    }

    /** 读取 .SpScn 原始字节 → 明文 JSON 文本（容忍尾部 \x0c\t） */
    fun fromScnFileBytes(raw: ByteArray): String {
        val plain = decode(raw)
        var text = String(plain, Charsets.UTF_8)
        // 去掉尾部标记（\x0c\t 可能因解码产生不可见字符）
        text = text.trimEnd('\u000c', '\t', '\r', '\n')
        return stripBom(text)
    }

    /** 读取 .json 原始字节 → 明文 JSON 文本（尾部 \r\n；P3-5：剥 BOM） */
    fun fromJsonFileBytes(raw: ByteArray): String {
        return stripBom(String(raw, Charsets.UTF_8).trimEnd('\r', '\n', '\u000c', '\t'))
    }

    /** 剥 UTF-8 BOM（防御：桌面文件无 BOM，第三方工具可能带） */
    private fun stripBom(s: String): String =
        if (s.startsWith("\uFEFF")) s.substring(1) else s
}
