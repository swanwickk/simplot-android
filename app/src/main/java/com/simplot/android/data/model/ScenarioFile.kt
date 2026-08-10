package com.simplot.android.data.model

import com.google.gson.annotations.SerializedName
import com.simplot.android.data.model.Unit
import kotlin.jvm.Transient

/**
 * 场景存档顶层模型（对应 SimPlot2 存档 JSON 结构）
 *
 * 键序与桌面版 2.3.9 真实存档逐字节一致（实测 Iron Bottom Sound 场景）：
 * File, SimPlot Version, IsIntegerFile, Scenario, TypeOfGame, Time, Turns,
 * Overlays, Objects, Units, Formations
 */
data class ScenarioFile(
    @SerializedName("File") var file: String = "Referee",                     // Referee / Blue / Red
    @SerializedName("SimPlot Version")
    var simPlotVersion: String = "2.3",
    @SerializedName("IsIntegerFile")
    var isIntegerFile: Boolean = true,
    @SerializedName("Scenario") var scenario: Scenario = Scenario(),
    @SerializedName("TypeOfGame") var typeOfGame: Int = 0,
    @SerializedName("Time") var time: TimeState = TimeState(),
    @SerializedName("Turns") var turns: MutableList<Turn> = mutableListOf(),
    @SerializedName("Overlays") var overlays: Map<String, Any?> = emptyMap(),
    @SerializedName("Objects") var objects: MutableList<String> = mutableListOf(),
    @SerializedName("Units") var units: MutableList<Unit> = mutableListOf(),
    @SerializedName("Formations") var formations: Map<String, Any?> = emptyMap(),

    /** 瞬态：Do 前单位状态快照（undo 用，不落盘） */
    @Transient var undoSnapshot: List<Unit>? = null,

    /** 瞬态：Do 前 Objects 数组快照（E5 修复：undo 不得用 units 重建 objects） */
    @Transient var undoObjects: MutableList<String>? = null
) {
    companion object {
        fun fresh(): ScenarioFile = ScenarioFile()
    }
}
