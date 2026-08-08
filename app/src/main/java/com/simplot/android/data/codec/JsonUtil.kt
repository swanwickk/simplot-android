package com.simplot.android.data.codec

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Waypoint
import java.lang.reflect.Type

/**
 * JSON 序列化工具：使用 Gson，键序按 data class 声明顺序，
 * 省略 null 字段（与桌面版 json.dumps + separators=(',',':') 紧凑格式语义一致）
 */
object JsonUtil {

    /**
     * 桌面版空轨迹的表示是 {}（空对象）而非 []（空数组）。
     * 实测 Iron Bottom Sound：未移动单位 "PastWaypointArray": {}。
     */
    private object WaypointListAdapter : JsonSerializer<MutableList<Waypoint>>, JsonDeserializer<MutableList<Waypoint>> {
        override fun serialize(src: MutableList<Waypoint>, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            if (src.isEmpty()) return JsonObject()
            val arr = JsonArray()
            for (w in src) arr.add(context.serialize(w, Waypoint::class.java))
            return arr
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): MutableList<Waypoint> {
            if (json.isJsonObject) {
                // {} 空轨迹 → 空列表
                return mutableListOf()
            }
            if (json.isJsonArray) {
                val arr = json.asJsonArray
                val list = mutableListOf<Waypoint>()
                for (el in arr) list.add(context.deserialize(el, Waypoint::class.java))
                return list
            }
            throw JsonParseException("Unexpected PastWaypointArray: ${json}")
        }
    }

    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .registerTypeAdapter(object : TypeToken<MutableList<Waypoint>>() {}.type, WaypointListAdapter)
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
