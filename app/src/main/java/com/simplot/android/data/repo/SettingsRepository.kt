package com.simplot.android.data.repo

import android.content.Context
import com.google.gson.GsonBuilder
import com.simplot.android.domain.model.PlayerSettings

/**
 * 玩家设置仓库（文档 §2.2.4 SettingsRepository）。
 *
 * 本地持久化到 app 私有文件（非共享存档数据，桌面版 Player_Settings.json 的本地对应物）。
 * 用 Gson 存取 PlayerSettings；读取失败/文件缺失回退默认值。
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("simplot_player_settings", Context.MODE_PRIVATE)
    private val gson = GsonBuilder().create()

    /** 读取设置；无记录 → 默认 */
    fun load(): PlayerSettings {
        val json = prefs.getString("settings", null) ?: return PlayerSettings.DEFAULT
        return try {
            gson.fromJson(json, PlayerSettings::class.java) ?: PlayerSettings.DEFAULT
        } catch (e: Exception) {
            PlayerSettings.DEFAULT
        }
    }

    /** 保存设置 */
    fun save(settings: PlayerSettings) {
        prefs.edit().putString("settings", gson.toJson(settings)).apply()
    }

    /** 玩家名（快捷访问） */
    var playerName: String
        get() = load().playerName
        set(value) = save(load().copy(playerName = value))
}
