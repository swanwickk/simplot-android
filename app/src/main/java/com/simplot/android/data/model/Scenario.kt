package com.simplot.android.data.model

import com.google.gson.annotations.SerializedName

/** 场景元数据 */
data class Scenario(
    @SerializedName("ScenarioName") var scenarioName: String = "",
    @SerializedName("LastId") var lastId: Int = 0,
    @SerializedName("CurrentTrackNumber") var currentTrackNumber: Int = 2400,
    @SerializedName("CurrentPlayerTrackNumber") var currentPlayerTrackNumber: Int = 9000,
    @SerializedName("Phase") var phase: Int = 0,                    // 0=规划 plotting, 2=移动后 post-movement
    @SerializedName("TypeOfMap") var typeOfMap: Int = 0,            // 0=无地图 1/3=自定义地图
    @SerializedName("MapFileName") var mapFileName: String? = null    // 地图文件名；可空=空时不落盘（用户原始存档无此键）
)
