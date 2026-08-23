package com.simplot.android.data.codec

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplot.android.domain.model.PlayerSettings

/**
 * 玩家设置编解码（G55：与桌面版 player_settings.json 互通）。
 *
 * 桌面格式（反汇编确认 PlayerSettings.SaveFile/LoadFile）：
 * {"Player_Settings":{"File":"","Display_Options":{15 键},"PlayerName":"...","Units":[]}}
 *
 * Display_Options 键序固定（与桌面 SaveDisplayOptions 一致，写盘顺序即此序）：
 * ShowCities, ShowCountries, ShowWaters, ShowWaypoints, ShowDepths, ShowDepthKey,
 * ShowEs, ShowGrid, ShowScaleBar, ShowWeapons, ShowSensors, ShowSonar,
 * ShowLabels, ShowSpeedLeaders, ShowFormations
 *
 * Android 模型比桌面多 6 个颜色键（R7 本地显示色：backgroundColor 等）。颜色不写入
 * 桌面文件（桌面颜色在独立 Colors 文件管理）——写盘仅输出桌面 15 键 + File + PlayerName
 * + Units，保证与桌面字节结构互通；读盘只覆盖 15 键与玩家名，颜色保留本地默认/本地存档值。
 *
 * 容错：读盘时缺失键回退 [PlayerSettings.DEFAULT] 对应值；无 Player_Settings 包装或
 * JSON 非法 → 返回 null（调用方保留本地设置）。
 *
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object PlayerSettingsCodec {

    /** 桌面 Display_Options 键序 + 取值映射（写盘唯一来源；与 ScenarioRepository.DEFAULT_PLAYER_SETTINGS 对齐）。
     *  #10 修复：toDesktopJson 由此表生成，消灭 v0.6.0 的「双份键序真相」
     *  （此前 DISPLAY_KEY_ORDER 死代码列表 + toDesktopJson 里顺序硬编码 addProperty）。 */
    private val DISPLAY_KEYS: List<Pair<String, (PlayerSettings) -> Boolean>> = listOf(
        "ShowCities" to { it.showCities },
        "ShowCountries" to { it.showCountries },
        "ShowWaters" to { it.showWaters },
        "ShowWaypoints" to { it.showWaypoints },
        "ShowDepths" to { it.showDepths },
        "ShowDepthKey" to { it.showDepthKey },
        "ShowEs" to { it.showEs },
        "ShowGrid" to { it.showGrid },
        "ShowScaleBar" to { it.showScaleBar },
        "ShowWeapons" to { it.showWeapons },
        "ShowSensors" to { it.showSensors },
        "ShowSonar" to { it.showSonar },
        "ShowLabels" to { it.showLabels },
        "ShowSpeedLeaders" to { it.showSpeedLeaders },
        "ShowFormations" to { it.showFormations }
    )

    /**
     * 序列化为桌面 player_settings.json 文本（键序逐项对齐官方）。
     * @param fileTag 桌面 File 键（场景文件名标记；Android 无此语义，默认空串）
     */
    fun toDesktopJson(settings: PlayerSettings, fileTag: String = ""): String {
        val root = JsonObject()
        val ps = JsonObject()
        ps.addProperty("File", fileTag)
        val disp = JsonObject()
        // #10：由 DISPLAY_KEYS 单一来源生成（键序与桌面 SaveDisplayOptions 逐项一致）
        DISPLAY_KEYS.forEach { (key, get) -> disp.addProperty(key, get(settings)) }
        ps.add("Display_Options", disp)
        ps.addProperty("PlayerName", settings.playerName)
        ps.add("Units", JsonArray())
        root.add("Player_Settings", ps)
        return JsonUtil.gson.toJson(root)
    }

    /**
     * 解析桌面 player_settings.json 文本 → PlayerSettings。
     * @return 解析成功返回设置；JSON 非法 / 缺 Player_Settings 包装 → null
     */
    fun fromDesktopJson(text: String): PlayerSettings? = try {
        val root = JsonParser.parseString(text).asJsonObject
        val ps = root.getAsJsonObject("Player_Settings") ?: return null
        var s = PlayerSettings.DEFAULT
        ps.getAsJsonObject("Display_Options")?.let { d ->
            s = s.copy(
                showCities = boolOf(d, "ShowCities", s.showCities),
                showCountries = boolOf(d, "ShowCountries", s.showCountries),
                showWaters = boolOf(d, "ShowWaters", s.showWaters),
                showWaypoints = boolOf(d, "ShowWaypoints", s.showWaypoints),
                showDepths = boolOf(d, "ShowDepths", s.showDepths),
                showDepthKey = boolOf(d, "ShowDepthKey", s.showDepthKey),
                showEs = boolOf(d, "ShowEs", s.showEs),
                showGrid = boolOf(d, "ShowGrid", s.showGrid),
                showScaleBar = boolOf(d, "ShowScaleBar", s.showScaleBar),
                showWeapons = boolOf(d, "ShowWeapons", s.showWeapons),
                showSensors = boolOf(d, "ShowSensors", s.showSensors),
                showSonar = boolOf(d, "ShowSonar", s.showSonar),
                showLabels = boolOf(d, "ShowLabels", s.showLabels),
                showSpeedLeaders = boolOf(d, "ShowSpeedLeaders", s.showSpeedLeaders),
                showFormations = boolOf(d, "ShowFormations", s.showFormations)
            )
        }
        ps.get("PlayerName")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.let { s = s.copy(playerName = it) }
        s
    } catch (e: Exception) {
        null
    }

    private fun boolOf(obj: JsonObject, key: String, def: Boolean): Boolean =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: def
}
