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
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
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

    /**
     * 旧存档容错（真机反馈：读取旧 Red 文件 IllegalStateException）：
     * 桌面版对空数组有时写 {}（空对象），对空 Map 有时写 []（空数组）。
     * 当前仅 PastWaypointArray 有专用兼容；Objects/SensorArray/PerceptionArray/Turns 等
     * Collection 字段遇 {}、Formations/Overlays 等 Map 字段遇 [] 会抛
     * "Expected BEGIN_ARRAY/BEGIN_OBJECT but was ..."。
     *
     * 通用容错：反序列化时
     * - Collection 目标 + JSON 对象 → 跳过内容返回空列表
     * - Map 目标 + JSON 数组 → 跳过内容返回空 Map
     * 序列化保持默认（写 [] / {} 由字段默认类型决定，与桌面版一致）。
     */
    private val lenientFactory = object : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            val raw = type.rawType
            val isCollection = Collection::class.java.isAssignableFrom(raw)
            val isMap = Map::class.java.isAssignableFrom(raw)
            if (!isCollection && !isMap) return null
            val delegate = gson.getDelegateAdapter(this, type)
            return object : TypeAdapter<T>() {
                override fun write(out: JsonWriter, value: T) = delegate.write(out, value)

                @Suppress("UNCHECKED_CAST")
                override fun read(reader: JsonReader): T {
                    return when {
                        isCollection && reader.peek() == JsonToken.BEGIN_OBJECT -> {
                            // 期望列表但遇到 {}（旧存档空数组）→ 空列表
                            reader.beginObject()
                            while (reader.hasNext()) reader.skipValue()
                            reader.endObject()
                            emptyList<Any?>() as T
                        }
                        isMap && reader.peek() == JsonToken.BEGIN_ARRAY -> {
                            // 期望 Map 但遇到 []（旧存档空 Map）→ 空 Map
                            reader.beginArray()
                            while (reader.hasNext()) reader.skipValue()
                            reader.endArray()
                            emptyMap<Any?, Any?>() as T
                        }
                        else -> delegate.read(reader)
                    }
                }
            }
        }
    }

    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .registerTypeAdapter(object : TypeToken<MutableList<Waypoint>>() {}.type, WaypointListAdapter)
        .registerTypeAdapterFactory(lenientFactory)
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
