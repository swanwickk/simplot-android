package com.simplot.android.domain.model

/**
 * 玩家本地显示设置（对应桌面版 Player_Settings.json 的 Display_Options）。
 *
 * 桌面版键（反汇编确认 SaveDisplayOptions / LoadDisplayOptions）：
 * ShowCities / ShowCountries / ShowWaters / ShowWaypoints / ShowDepths / ShowDepthKey /
 * ShowEs / ShowGrid / ShowScaleBar / ShowWeapons / ShowSensors / ShowSonar / ShowLabels /
 * ShowSpeedLeaders / ShowFormations
 *
 * Android 端落地已具备绘制链路的开关（Grid/ScaleBar/Labels/SpeedLeaders/Sensors/Weapons），
 * 其余键保留兼容（默认值），未落地项后续补。
 */
data class PlayerSettings(
    val playerName: String = "Player",
    val showGrid: Boolean = true,
    val showScaleBar: Boolean = true,
    val showLabels: Boolean = true,
    val showSpeedLeaders: Boolean = true,
    val showSensors: Boolean = true,
    val showWeapons: Boolean = true,
    val showWaypoints: Boolean = true,
    val showFormations: Boolean = true,
    val showCities: Boolean = true,
    val showCountries: Boolean = true,
    val showWaters: Boolean = true,
    val showDepths: Boolean = true,
    val showDepthKey: Boolean = true,
    val showSonar: Boolean = true
) {
    companion object {
        val DEFAULT = PlayerSettings()
    }
}
