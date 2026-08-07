package com.simplot.android.data.model

import com.google.gson.annotations.SerializedName

/**
 * 单位数据模型（对应存档 Units 数组元素）
 *
 * 数值编码（与桌面版一致）：
 * - Speed = 节 × 1000
 * - Course = 度 × 1000（罗盘角 0=北 顺时针）
 * - Altitude = 米 × 1000（飞机）
 * - Depth = 米 × 1000（潜艇）
 * - X / Y = 海里 × 100000（整数定点，Y 向北为正）
 */
data class Unit(
    @SerializedName("IdNum") var idNum: String = "S001",            // 对象 ID：S=水面 A=飞机 U=潜艇 L=岸上
    @SerializedName("Side") var side: String = "Blue",             // Blue / Red / Neutral / Unknown
    @SerializedName("TrackNumber") var trackNumber: Int = 2401,           // 航迹编号
    @SerializedName("Name") var name: String = "",                 // 单位名称
    @SerializedName("Number") var number: Int = 1,
    @SerializedName("UnitClass") var unitClass: String = "CL",          // 类型简码：BB/BC/CL/CA/DD/CV...
    @SerializedName("UnitType") var unitType: String = "Cruiser",      // 类型全称
    @SerializedName("X") var x: Long = 0,                       // 位置 X（文件单位）
    @SerializedName("Y") var y: Long = 0,                       // 位置 Y（文件单位）
    @SerializedName("ShowSunk") var showSunk: Boolean = false,
    @SerializedName("IsActiveRadar") var isActiveRadar: Boolean = false,
    @SerializedName("IsActiveSonar") var isActiveSonar: Boolean = false,
    @SerializedName("PositionTimeCreated") var positionTimeCreated: String = "",
    @SerializedName("PositionTimeDeleted") var positionTimeDeleted: String = "2999-12-31 00:00:00",
    @SerializedName("Speed") var speed: Int = 0,                    // 航速 ×1000
    @SerializedName("Course") var course: Int = 0,                   // 航向 ×1000
    @SerializedName("Range") var range: Int = -100000,
    @SerializedName("WpDistance") var wpDistance: Int = 0,
    @SerializedName("PastWaypointArray1") var pastWaypointArray1: Any = emptyList<Any>(),    // 历史轨迹点
    @SerializedName("FutureWaypointArray1") var futureWaypointArray1: Any = emptyList<Any>(),  // 未来航路点
    @SerializedName("TextTags") var textTags: TextTags = TextTags(),
    @SerializedName("Altitude") var altitude: Int? = null,             // 飞机高度 ×1000（仅飞机）
    @SerializedName("AssignedAltitude") var assignedAltitude: Int? = null,
    @SerializedName("Climb") var climb: Int? = null,
    @SerializedName("Descend") var descend: Int? = null,
    @SerializedName("Depth") var depth: Int? = null,                // 潜艇深度 ×1000（仅潜艇）
    @SerializedName("AssignedDepth") var assignedDepth: Int? = null,
    @SerializedName("Ascend") var ascend: Int? = null,
    @SerializedName("DescRate") var descRate: Int? = null,
    @SerializedName("PerceptionArray") var perceptionArray: MutableList<Perception> = mutableListOf()
) {
    // ---- 便捷换算（节/度/米） ----
    fun speedKnots(): Double = speed / 1000.0
    fun courseDeg(): Double = course / 1000.0
    fun altitudeMeters(): Int? = altitude?.let { it / 1000 }
    fun depthMeters(): Int? = depth?.let { it / 1000 }

    fun setSpeed(knots: Double) { speed = (knots * 1000).toInt() }
    fun setCourse(deg: Double) { course = ((deg % 360) * 1000).toInt() }

    // ---- 类型判断 ----
    fun isSubmarine(): Boolean = depth != null
    fun isAircraft(): Boolean = altitude != null
    fun isSurface(): Boolean = !isSubmarine() && !isAircraft()
}

/** 标签显示设置 */
data class TextTags(
    @SerializedName("TagName") var tagName: Boolean = true,
    @SerializedName("TagTrackNum") var tagTrackNum: Boolean = false,
    @SerializedName("TagCourseSpeed") var tagCourseSpeed: Boolean = true,
    @SerializedName("TagClass") var tagClass: Boolean = false,
    @SerializedName("TagUnitType") var tagUnitType: Boolean = false,
    @SerializedName("TagAltitude") var tagAltitude: Boolean = false,
    @SerializedName("TagDepth") var tagDepth: Boolean = false,
    @SerializedName("TagCallsign") var tagCallsign: Boolean = false,
    @SerializedName("AdditionalText") var additionalText: String = ""
)

/** 感知数据（裁判视角，迷雾核心） */
data class Perception(
    @SerializedName("PositionTimeStart") var positionTimeStart: String = "",
    @SerializedName("PositionTimeEnd") var positionTimeEnd: String = "",
    @SerializedName("DetectionTime") var detectionTime: String = "",
    @SerializedName("SeenBySide") var seenBySide: String = "Red",       // 被哪一方感知
    @SerializedName("ShowAsSide") var showAsSide: String = "Blue",      // 显示为哪一方
    @SerializedName("ShowAsType") var showAsType: String = "Cruiser",   // 显示为哪种类型
    @SerializedName("ShowAltitude") var showAltitude: Boolean = false,
    @SerializedName("ShowClass") var showClass: Boolean = false,
    @SerializedName("ShowCourseSpeed") var showCourseSpeed: Boolean = false,
    @SerializedName("ShowDepth") var showDepth: Boolean = false,
    @SerializedName("ShowName") var showName: Boolean = false
)
