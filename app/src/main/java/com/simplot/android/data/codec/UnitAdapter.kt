package com.simplot.android.data.codec

import com.google.gson.*
import com.simplot.android.data.model.Unit
import java.lang.reflect.Type

/**
 * Unit 序列化适配器 — 修复 W1/W2 持久化污染。
 *
 * W1: 不可移动单位（IdNum 前缀 I/R/L/B  或 Domain INSTALLATION/REFERENCE等）不落盘
 *     Range/WpDistance/Speed/Course（与 2.3.9 官方存档字节一致）。
 *     判定：idNum 首字符 in {I,R,L,B}  → 不可移动；否则按 domainOf 回退（避免循环依赖，
 *     直接用前缀法，与桌面 LoadUnits 分派一致）。
 *
 * W2: Formation* 字段空串归一 null（序列化时省略空白值，反序列化时空白→null）。
 *     显式空白值在桌面存档中不出现，仍落盘会造成 SpScn 字节漂移与分组误匹配。
 */
object UnitAdapter : JsonSerializer<Unit>, JsonDeserializer<Unit> {

    private val immovablePrefixes = setOf('I', 'R')

    private fun isMovable(idNum: String, unitType: String, altitude: Int?, depth: Int?): Boolean {
        val prefix = idNum.firstOrNull()?.uppercaseChar()
        if (prefix != null && prefix in immovablePrefixes) return false
        // W1 边界核验（IronBottom L001 / Mediterranean L012 实测）：
        // L(陆上编队)/B(声呐浮标) 在桌面 2.3.9 中仍含 Speed/Course/Range 等运动键
        // （L001: Formation 含 PastWaypointArray；L012: 含 WpDistance=1），故不视为不可移动。
        // 仅 I(岸上设施 Installation)/R(参考点 Reference) 为固定设施，无运动学键。
        // 机场/Airfield 等 Installation 类型必为 I 前缀，已被覆盖；无需额外 Domain 判定。
        return true
    }

    private fun waypointAdapter() = object : JsonSerializer<MutableList<com.simplot.android.data.model.Waypoint>>, JsonDeserializer<MutableList<com.simplot.android.data.model.Waypoint>> {
        override fun serialize(src: MutableList<com.simplot.android.data.model.Waypoint>, typeOfSrc: java.lang.reflect.Type, ctx: JsonSerializationContext): JsonElement {
            if (src.isEmpty()) return JsonObject()
            val arr = JsonArray()
            for (w in src) arr.add(ctx.serialize(w, com.simplot.android.data.model.Waypoint::class.java))
            return arr
        }
        override fun deserialize(json: JsonElement, typeOfT: java.lang.reflect.Type, ctx: JsonDeserializationContext): MutableList<com.simplot.android.data.model.Waypoint> {
            if (json.isJsonObject) return mutableListOf()
            if (json.isJsonArray) {
                val arr = json.asJsonArray
                val list = mutableListOf<com.simplot.android.data.model.Waypoint>()
                for (el in arr) {
                    when {
                        el.isJsonObject -> list.add(ctx.deserialize(el, com.simplot.android.data.model.Waypoint::class.java))
                        el.isJsonArray -> {
                            val a = el.asJsonArray
                            fun str(i: Int) = a.get(i).takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""
                            fun num(i: Int) = a.get(i).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L
                            fun bool(i: Int) = a.get(i).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
                            list.add(com.simplot.android.data.model.Waypoint(name=str(0), x=num(1), y=num(2), speed=num(3).toInt(), course=num(4).toInt(), altitudeDepth=num(5).toInt(), assignedAltDepth=num(6).toInt(), ascent=num(7).toInt(), descent=num(8).toInt(), number=num(9).toInt(), isTurnTime=bool(10), positionTime=str(11)))
                        }
                        else -> throw JsonParseException("Unexpected waypoint element: " + el.toString())
                    }
                }
                return list
            }
            throw JsonParseException("Unexpected PastWaypointArray: " + json.toString())
        }
    }

    private fun lenientFactory(): com.google.gson.TypeAdapterFactory = object : com.google.gson.TypeAdapterFactory {
        override fun <T> create(gson: com.google.gson.Gson, type: com.google.gson.reflect.TypeToken<T>): com.google.gson.TypeAdapter<T>? {
            val raw = type.rawType
            val isCollection = Collection::class.java.isAssignableFrom(raw)
            val isMap = Map::class.java.isAssignableFrom(raw)
            if (!isCollection && !isMap) return null
            val delegate = gson.getDelegateAdapter(this, type)
            return object : com.google.gson.TypeAdapter<T>() {
                override fun write(out: com.google.gson.stream.JsonWriter, value: T) = delegate.write(out, value)
                @Suppress("UNCHECKED_CAST")
                override fun read(reader: com.google.gson.stream.JsonReader): T {
                    return when {
                        isCollection && reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            while (reader.hasNext()) reader.skipValue()
                            reader.endObject()
                            mutableListOf<Any?>() as T
                        }
                        isMap && reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            while (reader.hasNext()) reader.skipValue()
                            reader.endArray()
                            mutableMapOf<Any?, Any?>() as T
                        }
                        else -> delegate.read(reader)
                    }
                }
            }
        }
    }

    private val plainGsonForSerialize by lazy {
        com.google.gson.GsonBuilder().disableHtmlEscaping()
            .registerTypeAdapter(object : com.google.gson.reflect.TypeToken<MutableList<com.simplot.android.data.model.Waypoint>>() {}.type, waypointAdapter())
            .create()
    }

    private val gsonForDeserialize by lazy {
        com.google.gson.GsonBuilder().disableHtmlEscaping()
            .registerTypeAdapter(object : com.google.gson.reflect.TypeToken<MutableList<com.simplot.android.data.model.Waypoint>>() {}.type, waypointAdapter())
            .registerTypeAdapterFactory(lenientFactory())
            .create()
    }

    override fun serialize(src: Unit, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        // 借助纯净 Gson 生成 JsonObject，再按需剔除污染键，保键序（避免递归调用本 Adapter）
        val gson = plainGsonForSerialize
        val obj = gson.toJsonTree(src).asJsonObject

        // ---- W1: 不可移动单位省略运动学键 ----
        if (!isMovable(src.idNum, src.unitType, src.altitude, src.depth)) {
            obj.remove("Speed")
            obj.remove("Course")
            obj.remove("Range")
            obj.remove("RangeMm")   // R4：安卓新增键，桌面 2.3.9 无此键，不可移动单位必须一并剔除
            obj.remove("WpDistance")
            // 不可移动单位也不应有航路点键污染（若为空，WaypointListAdapter 会写 {}；但桌面无此键）
            // 保留空对象语义？I/R/L/B 实测完全无 Past/Future 键。当前实现统一：若为空对象则移除。
            // 为不破坏可移动单位的 {} 空轨迹语义，仅对不可移动單位移除空对象。
            val past = obj.get("PastWaypointArray")
            val future = obj.get("FutureWaypointArray")
            // 若客户端写了空对象但单位不可移动，移除（桌面无此键）
            if (past != null && past.isJsonObject && past.asJsonObject.size() == 0) obj.remove("PastWaypointArray")
            if (future != null && future.isJsonObject && future.asJsonObject.size() == 0) obj.remove("FutureWaypointArray")
            // 同理 PastWaypointArray1 / FutureWaypointArray1 的空对象兼容：需各自取键
            val past1 = obj.get("PastWaypointArray1")
            val future1 = obj.get("FutureWaypointArray1")
            if (past1 != null && past1.isJsonObject && past1.asJsonObject.size() == 0) obj.remove("PastWaypointArray1")
            if (future1 != null && future1.isJsonObject && future1.asJsonObject.size() == 0) obj.remove("FutureWaypointArray1")
        }

        // ---- W2: Formation 空串归一 null → 省略 ----
        fun blankToRemove(key: String) {
            val el = obj.get(key) ?: return
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString && el.asString.isBlank()) {
                obj.remove(key)
            }
        }
        blankToRemove("FormationName")
        blankToRemove("FormationType")
        // FormationBearing / FormationDistance 为数值，可空已省略；空串不适用

        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Unit {
        if (!json.isJsonObject) return gsonForDeserialize.fromJson(json, Unit::class.java)
        val obj = json.asJsonObject

        // W2: 空串归一 null（落内存即 null，序列化不再落盘）
        fun blankToNull(key: String) {
            val el = obj.get(key) ?: return
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString && el.asString.isBlank()) {
                obj.remove(key)
            }
        }
        blankToNull("FormationName")
        blankToNull("FormationType")

        // W1: 若缺运动学键（桌面不可移动单位无此键），补默认值以保持内存模型一致
        // Gson 默认会用字段默认值；此处无需补。但为避免旧存档显式 null 导致 0 污染，显式 null 时视为缺失？
        // 已由 lenient 处理。

        return gsonForDeserialize.fromJson(obj, Unit::class.java)
    }
}
