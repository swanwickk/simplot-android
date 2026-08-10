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
    val showSonar: Boolean = true,
    // R7：显示颜色（桌面版 Colors.SaveColors/LoadColors 键）
    val backgroundColor: Long = 0xFFF0F2F5,       // 背景
    val gridColor: Long = 0x883C789C,             // 网格
    val blueForColor: Long = 0xFF005AC8,          // 蓝方
    val redForColor: Long = 0xFFC81E1E,           // 红方
    val mapLandColor: Long = 0x7896AA82,          // 陆地
    val mapOceanColor: Long = 0x40C8DCE8          // 海洋
) {
    companion object {
        val DEFAULT = PlayerSettings()
    }
}
