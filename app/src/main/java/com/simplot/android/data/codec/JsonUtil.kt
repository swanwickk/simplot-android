package com.simplot.android.data.codec

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.simplot.android.data.model.ScenarioFile

/**
 * JSON 序列化工具：使用 Gson，键序按 data class 声明顺序，
 * 省略 null 字段（与桌面版 json.dumps + separators=(',',':') 紧凑格式语义一致）
 */
object JsonUtil {
    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    /** 紧凑序列化（无空格，与桌面版字节级兼容） */
    fun toCompactJson(file: ScenarioFile): String = gson.toJson(file)

    /** 解析明文 JSON 文本 → ScenarioFile */
    fun fromJson(text: String): ScenarioFile = gson.fromJson(text, ScenarioFile::class.java)

    /** 校验是否为合法 SimPlot 存档 JSON */
    fun isScenarioJson(text: String): Boolean {
        return try {
            val el = JsonParser.parseString(text)
            el.isJsonObject && el.asJsonObject.has("Units") && el.asJsonObject.has("Scenario")
        } catch (e: Exception) {
            false
        }
    }
}
