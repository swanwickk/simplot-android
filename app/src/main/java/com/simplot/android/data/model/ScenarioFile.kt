package com.simplot.android.data.model

import com.google.gson.annotations.SerializedName

/**
 * 场景存档顶层模型（对应 SimPlot2 存档 JSON 结构）
 * 序列化键序尽量与桌面版一致（Gson 按声明顺序输出）
 */
data class ScenarioFile(
    var file: String = "Referee",                     // Referee / Blue / Red
    @SerializedName("SimPlot Version")
    var simPlotVersion: String = "2.3",
    @SerializedName("IsIntegerFile")
    var isIntegerFile: Boolean = true,
    var scenario: Scenario = Scenario(),
    var typeOfGame: Int = 0,
    var time: TimeState = TimeState(),
    var turns: MutableList<Turn> = mutableListOf(),
    var overlays: Map<String, Any?> = emptyMap(),
    var objects: MutableList<String> = mutableListOf(),
    var units: MutableList<Unit> = mutableListOf(),
    var formations: Map<String, Any?> = emptyMap()
) {
    companion object {
        fun fresh(): ScenarioFile = ScenarioFile()
    }
}
