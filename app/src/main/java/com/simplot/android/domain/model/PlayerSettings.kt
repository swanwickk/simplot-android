package com.simplot.android.domain.model

/**
 * 符号集四选（G47，桌面版 WindowCustomizeDisplay PopupSet）。
 *
 * 桌面签名：CWS Color Filled / CWS Color Unfilled / CWS Mono Filled / NTDS。
 * CWS 各变体渲染差异（UnitRenderer）：
 * - [CWS_COLOR_FILLED]：优先精灵图（assets/symbols/ 下 color_filled 后缀图），缺失走矢量填充；
 * - [CWS_COLOR_UNFILLED]：优先精灵图（color_unfilled 后缀图），缺失走矢量描边（空心）；
 * - [CWS_MONO_FILLED]：优先精灵图（mono_filled 后缀图），缺失走矢量填充（单色）；
 * - [NTDS]：桌面 NTDS 描边符号（不填充）。
 *
 * 值用枚举 name 经 Gson 序列化进本地设置 JSON；新增/缺省字段不影响旧存档读取（默认值兜底）。
 */
enum class SymbolSet(val label: String) {
    CWS_COLOR_FILLED("CWS Color Filled"),
    CWS_COLOR_UNFILLED("CWS Color Unfilled"),
    CWS_MONO_FILLED("CWS Mono Filled"),
    NTDS("NTDS");

    companion object {
        /** 显示名 → 枚举（设置对话框/测试共用）；未知回退 [CWS_COLOR_FILLED] */
        fun fromLabel(label: String): SymbolSet = entries.firstOrNull { it.label == label } ?: CWS_COLOR_FILLED
    }
}

/**
 * 符号尺寸档（G08，桌面版 WindowCustomizeDisplay PopupSize：Dots/Reduced/Default/Enlarged）。
 *
 * [scale]：图标像素基准的缩放系数（SceneCanvas 中 iconSizePx(zoom) × scale）。
 * [DOTS] 走特殊渲染：单位仅画小圆点（桌面 Dots 语义：不画完整军标，仅位置点）。
 */
enum class SymbolSize(val label: String, val scale: Float) {
    DOTS("Dots", 0.35f),
    REDUCED("Reduced", 0.7f),
    DEFAULT("Default", 1.0f),
    ENLARGED("Enlarged", 1.4f);

    companion object {
        fun fromLabel(label: String): SymbolSize = entries.firstOrNull { it.label == label } ?: DEFAULT
    }
}

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
 *
 * G08/G47/G09/G22（批次3）新增（本地 SharedPreferences JSON，Gson 序列化；缺省字段由默认值兜底，兼容旧存档）：
 * - [symbolSet] 符号集四选（桌面 PopupSet）
 * - [ww2Symbols] WW2 附加切换（桌面 WW2Symbols 超集）
 * - [symbolSize] 符号尺寸档（桌面 PopupSize）
 * - [showFriendlySymbols] CheckFriendlySymbols（友军=蓝方符号显示）
 * - [useLabelBackground] CheckBackground（桌面签名："Use background color under labels"）
 * - [savedColors] 颜色方案快照（WindowCustomizeColor PushSave/Load 的本地持久化载体）
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
    /** R4 修复：ES（电子支援）被动方位线开关（桌面 ShowEs） */
    val showEs: Boolean = true,
    // R7：显示颜色（桌面版 Colors.SaveColors/LoadColors 键）
    val backgroundColor: Long = 0xFFF0F2F5,       // 背景
    val gridColor: Long = 0x883C789C,             // 网格
    val blueForColor: Long = 0xFF005AC8,          // 蓝方
    val redForColor: Long = 0xFFC81E1E,           // 红方
    val mapLandColor: Long = 0x7896AA82,          // 陆地
    val mapOceanColor: Long = 0x40C8DCE8,         // 海洋
    // ---- G47：符号集四选 + WW2 附加切换 ----
    val symbolSet: SymbolSet = SymbolSet.CWS_COLOR_FILLED,
    val ww2Symbols: Boolean = false,
    // ---- G08：符号尺寸档 / CheckFriendlySymbols / CheckBackground ----
    val symbolSize: SymbolSize = SymbolSize.DEFAULT,
    val showFriendlySymbols: Boolean = true,
    val useLabelBackground: Boolean = true,
    // ---- G09：颜色方案快照（WindowCustomizeColor PushSave/PushLoad）----
    val savedColors: List<Long>? = null
) {
    companion object {
        val DEFAULT = PlayerSettings()

        /** 颜色键清单（G09：WindowCustomizeColor ListboxColors 的可编辑色序，与桌面 Colors 键一致） */
        val COLOR_KEYS: List<ColorKey> = listOf(
            ColorKey("背景", { it.backgroundColor }, { s, v -> s.copy(backgroundColor = v) }),
            ColorKey("网格", { it.gridColor }, { s, v -> s.copy(gridColor = v) }),
            ColorKey("蓝方", { it.blueForColor }, { s, v -> s.copy(blueForColor = v) }),
            ColorKey("红方", { it.redForColor }, { s, v -> s.copy(redForColor = v) }),
            ColorKey("陆地", { it.mapLandColor }, { s, v -> s.copy(mapLandColor = v) }),
            ColorKey("海洋", { it.mapOceanColor }, { s, v -> s.copy(mapOceanColor = v) })
        )

        /** 当前设置的颜色列表（按 [COLOR_KEYS] 顺序） */
        fun colorsOf(s: PlayerSettings): List<Long> = COLOR_KEYS.map { it.get(s) }

        /** 用颜色列表替换当前颜色（长度不匹配时按可对齐部分覆盖，多余忽略） */
        fun withColors(s: PlayerSettings, colors: List<Long>): PlayerSettings {
            var out = s
            COLOR_KEYS.forEachIndexed { i, k ->
                colors.getOrNull(i)?.let { v -> out = k.set(out, v) }
            }
            return out
        }
    }
}

/** 颜色键描述（G09：标签 + 取值/设值访问器，供设置对话框颜色列表编辑与 Load/Save/Reset 复用） */
data class ColorKey(
    val label: String,
    val get: (PlayerSettings) -> Long,
    val set: (PlayerSettings, Long) -> PlayerSettings
)
