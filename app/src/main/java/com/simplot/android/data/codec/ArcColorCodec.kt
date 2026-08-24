package com.simplot.android.data.codec

/**
 * 弧颜色编解码（纯 Kotlin，不依赖 android.graphics → 可 JVM 直接单测）。
 *
 * 存储/存档格式与桌面版一致：VB 十六进制 `&h00RRGGBB`（ARGB，前 2 位=Alpha，恒为 00）。
 * 兼容解析（读取存档时宽容处理历史数据）：
 * - `&h` / `&H` / `#` 前缀均可；
 * - 16 进制位长度 ≥6 即可（取右数 6 位为 RRGGBB），与 ArcRenderer.parseColor 原语义逐位一致；
 * - 解析失败（null / 空串 / 非法字符）回退桌面版默认色 黄色 0xFFFF00。
 *
 * 目标：UI 选色器与渲染端共用同一实现，避免两套解析逻辑漂移。
 */
object ArcColorCodec {

    /** 桌面版默认色：黄色（与 ArcRenderer.parseColor 原有 fallback 一致） */
    const val DEFAULT_COLOR: Int = 0xFFFF00

    /**
     * 兼容解析任意历史颜色代码；失败回退 [DEFAULT_COLOR]。
     * 语义与旧 ArcRenderer.parseColor 完全一致（null/异常 → 黄色）。
     */
    fun parseVbColor(vb: String?): Int = tryParseVbColor(vb) ?: DEFAULT_COLOR

    /**
     * 严格解析：成功返回 ARGB（Alpha=0xFF，RGB 取自代码右数 6 位），失败返回 null。
     * UI 用此判断「颜色代码无效」以便提示并回退默认色。
     */
    fun tryParseVbColor(vb: String?): Int? {
        if (vb == null) return null
        val hex = vb.trim()
            .removePrefix("&h").removePrefix("&H").removePrefix("#")
        if (hex.length < 6) return null
        val v = hex.toLongOrNull(16) ?: return null
        val r = ((v shr 16) and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = (v and 0xFF).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /**
     * 回写为规范存档格式 `&h00RRGGBB`（8 位，Alpha 恒 00），与桌面字节级互通。
     * 入参任意 Int（ARGB 或 RGB 均可），仅取低 24 位。
     */
    fun toVbColor(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return "&h00" + "%06X".format(rgb)
    }

    // ---- HSV 转换（纯 Kotlin，供选色器滑杆与 JVM 单测） ----

    /** ARGB → HSV：返回 Triple(h 0..360, s 0..1, v 0..1)，忽略 Alpha。 */
    fun rgbToHsv(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (max == 0f) 0f else d / max
        return Triple(h, s, max)
    }

    /** HSV(h 0..360, s 0..1, v 0..1) → 不透明 ARGB。 */
    fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return 0xFF000000.toInt() or
            ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
            ((b * 255f).toInt().coerceIn(0, 255))
    }
}