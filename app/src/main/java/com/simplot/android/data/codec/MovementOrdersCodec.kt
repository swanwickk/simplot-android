package com.simplot.android.data.codec

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint

/**
 * 运动命令编解码（桌面版 MovementOrders：BuildUnitArray / ParseUnitArray）。
 *
 * 格式：{"File":"Movement Orders","Units":[{"IdNum":0,"Waypoints":[<WaypointToJson 12 键>]}]}
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object MovementOrdersCodec {

    /** 序列化：单位列表 → Movement Orders JSON 文本 */
    fun toJson(units: List<Unit>): String {
        val root = JsonObject()
        root.addProperty("File", "Movement Orders")
        val arr = JsonArray()
        units.forEach { u ->
            val o = JsonObject()
            o.addProperty("IdNum", u.idNum)
            val wps = JsonArray()
            // D7 决策：运动命令只导出未来航路点（命令不含历史轨迹）
            u.futureWaypointArray.forEach { w ->
                wps.add(JsonUtil.gson.toJsonTree(w))
            }
            o.add("Waypoints", wps)
            arr.add(o)
        }
        root.add("Units", arr)
        return JsonUtil.gson.toJson(root)
    }

    /** 解析 Movement Orders 文本 → IdNum → 航路点 映射 */
    fun parse(text: String): Map<String, List<Waypoint>> {
        val root = JsonParser.parseString(text).asJsonObject
        val unitsArr = root.getAsJsonArray("Units")
            ?: throw IllegalArgumentException("不是有效的运动命令文件（缺 Units）")
        val result = mutableMapOf<String, List<Waypoint>>()
        for (el in unitsArr) {
            val o = el.asJsonObject
            val idNum = o.get("IdNum")?.asString ?: continue
            val wpsArr = o.getAsJsonArray("Waypoints") ?: continue
            val list = mutableListOf<Waypoint>()
            for (wpEl in wpsArr) {
                list.add(JsonUtil.gson.fromJson(wpEl, Waypoint::class.java))
            }
            result[idNum] = list
        }
        return result
    }

    /** 应用导入结果到场景单位（按 IdNum 匹配，替换未来航路点）；返回已导入单位数 */
    fun applyTo(file: ScenarioFile, text: String): Int {
        val byId = file.units.associateBy { it.idNum }
        var count = 0
        parse(text).forEach { (idNum, wps) ->
            val target = byId[idNum] ?: return@forEach
            target.futureWaypointArray = wps.toMutableList()
            count++
        }
        return count
    }
}
