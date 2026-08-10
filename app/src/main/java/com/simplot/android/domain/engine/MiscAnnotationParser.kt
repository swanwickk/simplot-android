package com.simplot.android.domain.engine

import com.simplot.android.domain.model.MiscAnnotation

/**
 * Misc 标注解析器（R7，桌面版 Misc*.CreateFromJson）。
 *
 * 输入：场景 Overlays 原始数据（Gson 解析后的 Map/List 结构）。
 * 输出：MiscAnnotation 列表（纯 Kotlin，可 JVM 单测）。
 *
 * Overlays 结构（实测存档）：{"MiscOverlays": [ {...}, ... ]} 或直接数组。
 */
object MiscAnnotationParser {

    /** 从 Overlays Map 解析全部标注（兼容 {"MiscOverlays":[...]} 与直接数组） */
    fun parse(overlays: Map<String, Any?>?): List<MiscAnnotation> {
        val result = mutableListOf<MiscAnnotation>()
        if (overlays == null) return result
        val raw: Any? = overlays["MiscOverlays"] ?: overlays["Misc"] ?: return result
        when (raw) {
            is List<*> -> raw.forEach { el -> el?.let { parseOne(it, result) } }
            is Map<*, *> -> raw.values.forEach { el -> el?.let { parseOne(it, result) } }
        }
        return result
    }

    /** 从单元素解析（兼容 Map 或 JsonObject 结构） */
    private fun parseOne(el: Any, sink: MutableList<MiscAnnotation>) {
        val m = el as? Map<*, *> ?: return
        fun s(key: String): String = m[key]?.toString() ?: ""
        fun d(key: String): Double = (m[key] as? Number)?.toDouble() ?: 0.0
        fun l(key: String): Long = (m[key] as? Number)?.toLong() ?: 0L
        fun b(key: String): Boolean = m[key] as? Boolean ?: false
        fun path(): List<Pair<Long, Long>> {
            val p = m["Path"] ?: return emptyList()
            val list = mutableListOf<Pair<Long, Long>>()
            if (p is List<*>) {
                var i = 0
                while (i + 1 < p.size) {
                    val x = (p[i] as? Number)?.toLong() ?: 0L
                    val y = (p[i + 1] as? Number)?.toLong() ?: 0L
                    list.add(x to y)
                    i += 2
                }
            }
            return list
        }

        val name = s("Name").ifBlank { "Misc" }
        val side = s("Side").ifBlank { "All" }
        val color = s("ColorName")
        val type = s("Type").lowercase()
        when {
            type.contains("label") || (m.containsKey("FontSize") && !m.containsKey("Path")) ->
                sink.add(MiscAnnotation.Label(name, side, color, s("Text").ifBlank { name },
                    l("X"), l("Y"), d("FontSize"), b("IsBold"), b("IsItalic"), d("Rotation")))
            type.contains("box") || (m.containsKey("Width") && m.containsKey("Height") && !m.containsKey("Path")) ->
                sink.add(MiscAnnotation.Box(name, side, color, l("X"), l("Y"), l("Width"), l("Height"),
                    d("Transparency"), b("IsFilled"), d("Rotation")))
            type.contains("oval") ->
                sink.add(MiscAnnotation.Oval(name, side, color, l("X"), l("Y"), l("Width"), l("Height"),
                    d("Transparency"), b("IsFilled"), d("Rotation")))
            type.contains("line") || type.contains("polyline") ->
                sink.add(MiscAnnotation.Line(name, side, color, path(), d("Dash"), d("Spacing")))
            type.contains("poly") ->
                sink.add(MiscAnnotation.Polygon(name, side, color, path(), d("Transparency"),
                    d("Dash"), d("Spacing"), b("IsFilled")))
            else -> {
                // 无 Type 键：按字段形状推断
                when {
                    m.containsKey("Path") -> sink.add(MiscAnnotation.Polygon(name, side, color, path(), d("Transparency"), d("Dash"), d("Spacing"), b("IsFilled")))
                    m.containsKey("Width") -> sink.add(MiscAnnotation.Box(name, side, color, l("X"), l("Y"), l("Width"), l("Height"), d("Transparency"), b("IsFilled"), d("Rotation")))
                    else -> sink.add(MiscAnnotation.Label(name, side, color, s("Text").ifBlank { name }, l("X"), l("Y"), d("FontSize"), b("IsBold"), b("IsItalic"), d("Rotation")))
                }
            }
        }
    }
}
