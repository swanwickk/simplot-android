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
 *
 * G56：导入校验与合并语义——
 * - 导入为「按 IdNum 合并」：仅更新文件中出现的单位的未来航路点，未出现的单位保持原状
 *   （与桌面 LoadMoveOrders 一致，非整体替换场景）；
 * - parse 阶段校验每个航路点的关键数值键（X/Y/Number）存在、且 Number 连续（1..N）。
 *   跨版本文件可能乱序/缺键，此时拒绝导入并给出明确错误（避免静默写坏存档）。
 */
object MovementOrdersCodec {

    /** 航路点对象必需数值键（桌面 WaypointToJson 12 键中的数值核心；缺失即拒绝导入） */
    private val WAYPOINT_REQUIRED_KEYS = listOf("X", "Y", "Number")

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

    /**
     * 解析 Movement Orders 文本 → IdNum → 航路点 映射。
     * G56：逐单位校验航路点关键键（X/Y/Number）与 Number 连续性（1..N），
     * 校验失败抛 [IllegalArgumentException]（调用方应在任何写入前完成 parse，保证无半导入状态）。
     */
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
                if (!wpEl.isJsonObject) {
                    throw IllegalArgumentException("运动命令单位 $idNum 的航路点格式错误（应为 JSON 对象）")
                }
                validateWaypointKeys(idNum, wpEl.asJsonObject)
                list.add(JsonUtil.gson.fromJson(wpEl, Waypoint::class.java))
            }
            validateNumberContinuity(idNum, list)
            result[idNum] = list
        }
        return result
    }

    /** G56：航路点必需数值键校验（缺失/非数值 → 拒绝，提示跨版本文件乱序风险） */
    private fun validateWaypointKeys(idNum: String, wp: JsonObject) {
        for (k in WAYPOINT_REQUIRED_KEYS) {
            val v = wp.get(k)
            if (v == null || !v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) {
                throw IllegalArgumentException(
                    "运动命令单位 $idNum 的航路点缺少数值键 $k（跨版本文件可能乱序，请用桌面版重新保存后导入）"
                )
            }
        }
    }

    /** G56：Number 连续性校验（应为 1..N 严格连续；乱序 → 拒绝导入） */
    private fun validateNumberContinuity(idNum: String, wps: List<Waypoint>) {
        if (wps.isEmpty()) return
        val expected = (1..wps.size).toList()
        val actual = wps.map { it.number }
        if (actual != expected) {
            throw IllegalArgumentException(
                "运动命令单位 $idNum 航路点 Number 不连续（期望 1..${wps.size}，实际 $actual）；已拒绝导入"
            )
        }
    }

    /** 应用导入结果到场景单位（按 IdNum 匹配，替换未来航路点；G56 合并语义：未出现单位不动）；返回已导入单位数 */
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
