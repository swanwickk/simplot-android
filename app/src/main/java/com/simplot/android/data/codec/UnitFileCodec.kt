package com.simplot.android.data.codec

import com.google.gson.JsonParser
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit

/**
 * 单位级导入导出编解码（G28：桌面版 Units → Import Unit / Export Unit，UnitFiles）。
 *
 * 单单位文件 = 场景 Units 数组元素的完整 JSON（UnitToJson 键序，含航路点/弧/感知/被动方位），
 * 与官方 2.3.9 键序 / scn_tool JsonToUnit 兼容。桌面 UnitFiles 目录的具体文件布局未在
 * 反编译资料中展开，此处以"单位 JSON 原样"为契约（跨场景搬运单位的最小完整表示），
 * 文件名采用 `Unit <IdNum>.json`。
 *
 * 导入语义（合并）：IdNum 已存在于场景 → 原位替换；否则 → 追加新单位（基本版，不重新
 * 分配 IdNum/TrackNumber；场景级搬运的低频操作，冲突由用户自行处理）。
 *
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object UnitFileCodec {

    /** 序列化：单位 → 单单位 JSON 文本（瞬态字段不落盘，与场景存档键序一致） */
    fun toJson(unit: Unit): String = JsonUtil.gson.toJson(unit)

    /**
     * 解析单单位 JSON 文本 → Unit。
     * @throws IllegalArgumentException 非 JSON 对象或缺 IdNum（不是有效单位文件）
     */
    fun fromJson(text: String): Unit {
        val el = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的单位文件（JSON 解析失败）", e)
        }
        if (!el.isJsonObject) throw IllegalArgumentException("不是有效的单位文件（缺 JSON 对象）")
        val o = el.asJsonObject
        if (!o.has("IdNum")) throw IllegalArgumentException("不是有效的单位文件（缺 IdNum 键）")
        return JsonUtil.gson.fromJson(o, Unit::class.java)
    }

    /**
     * 合并导入到场景：IdNum 已存在 → 原位替换；否则 → 追加。
     * @return true=替换了已有单位；false=新增单位
     */
    fun importInto(file: ScenarioFile, unit: Unit): Boolean {
        val idx = file.units.indexOfFirst { it.idNum == unit.idNum }
        return if (idx >= 0) {
            file.units[idx] = unit
            true
        } else {
            file.units.add(unit)
            false
        }
    }
}
