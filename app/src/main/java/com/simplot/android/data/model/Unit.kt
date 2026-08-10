package com.simplot.android.data.model

import com.google.gson.annotations.SerializedName
import kotlin.jvm.Transient

/**
 * 单位数据模型（对应存档 Units 数组元素，键序与桌面版 2.3.9 真实存档一致）
 *
 * 数值编码（与桌面版一致）：
 * - Speed = 节 × 1000
 * - Course = 度 × 1000（罗盘角 0=北 顺时针）
 * - Altitude = 米（整数，桌面版原样存取，无定点！实测 JsonToUnit 对 Altitude 直接 movsd）
 * - Depth = 米（同上，原样存取）
 * - X / Y = 海里 × 100000（整数定点，Y 向北为正）
 * - Range = 海里 × 1（整数，-100000 表示无限制）
 * ⚠️ 高度/深度无 ×1000 定点（此前误写为 ×1000，与桌面版不兼容，已修正）
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
    @SerializedName("IsInFormation") var isInFormation: Boolean? = null,
    @SerializedName("IsFormationCenter") var isFormationCenter: Boolean? = null,
    @SerializedName("FormationBearing") var formationBearing: Int? = null,   // ×1000 定点（罗盘角；Course 模式为相对编队航向）
    @SerializedName("FormationDistance") var formationDistance: Int? = null,  // 文件单位（海里×100000，桌面版 Double 原样与中心坐标相加）
    @SerializedName("FormationName") var formationName: String? = null,
    @SerializedName("FormationType") var formationType: String? = null,  // RelativeToCompass/Column/RelativeToCourse；可空=非编队成员不落盘（用户原始存档无此键）
    @SerializedName("PositionTimeCreated") var positionTimeCreated: String = "",
    @SerializedName("PositionTimeDeleted") var positionTimeDeleted: String = "2020-01-01 00:00:00",
    @SerializedName("Speed") var speed: Int = 0,                    // 航速 ×1000
    @SerializedName("Course") var course: Int = 0,                   // 航向 ×1000
    @SerializedName("Range") var range: Int = -100000,             // 可移动距离（海里），-100000=无限制
    @SerializedName("WpDistance") var wpDistance: Int = 0,          // 航路点距离（scn_tool 特有键，用户原始存档含此键，需保留）
    @SerializedName(value = "PastWaypointArray", alternate = ["PastWaypointArray1"]) var pastWaypointArray: MutableList<Waypoint> = mutableListOf(),   // 历史轨迹点（兼容用户场景 PastWaypointArray1）
    @SerializedName(value = "FutureWaypointArray", alternate = ["FutureWaypointArray1"]) var futureWaypointArray: MutableList<Waypoint> = mutableListOf(),  // 未来航路点（兼容 FutureWaypointArray1）
    @SerializedName("TextTags") var textTags: TextTags = TextTags(),
    @SerializedName("SensorArray") var sensorArray: MutableList<Sensor>? = null,       // 传感器射程弧
    @SerializedName("WeaponArray") var weaponArray: MutableList<Weapon>? = null,       // 武器射程弧
    @SerializedName("Altitude") var altitude: Int? = null,             // 飞机高度（米，原样存取）
    @SerializedName("AssignedAltitude") var assignedAltitude: Int? = null,
    @SerializedName("Climb") var climb: Int? = null,                   // 爬升速率（米/回合？桌面版运行时 /180 得每秒变化）
    @SerializedName("Descend") var descend: Int? = null,
    @SerializedName("Depth") var depth: Int? = null,                // 潜艇深度（米，原样存取）
    @SerializedName("AssignedDepth") var assignedDepth: Int? = null,
    @SerializedName("Ascend") var ascend: Int? = null,
    @SerializedName("DescRate") var descRate: Int? = null,
    @SerializedName("PerceptionArray") var perceptionArray: MutableList<Perception>? = null,
    @SerializedName("PassiveBearingArray") var passiveBearingArray: MutableList<PassiveBearing>? = null,   // 被动方位（声呐/ES，桌面版 PassiveBearings）

    /** 瞬态标记：本回合新加入的单位不移动（不落盘，Gson 忽略） */
    @Transient var isNewThisTurn: Boolean = false,

    /** 瞬态：最大航速（节），由舰船信息表填写；用于 A 级快慢判定与 75% 加速档（不落盘） */
    @Transient var maxSpeedKnots: Double? = null,

    /** 瞬态：Range 耗尽后选择"继续移动"（桌面版 Continue Movement = 无视 Range 继续航行，不落盘） */
    @Transient var ignoreRange: Boolean = false,

    /** 瞬态：编队 prepare 时的未来航路点备份（E12：cancel 恢复用，不落盘） */
    @Transient var formationWaypointBackup: MutableList<Waypoint>? = null
) {
    // ---- 便捷换算（节/度；高度深度已是米，直接返回） ----
    fun speedKnots(): Double = speed / 1000.0
    fun courseDeg(): Double = course / 1000.0
    fun altitudeMeters(): Int? = altitude
    fun depthMeters(): Int? = depth

    fun setSpeed(knots: Double) { speed = (knots * 1000).toInt() }
    fun setCourse(deg: Double) { course = ((deg % 360) * 1000).toInt() }

    // ---- 类型判断 ----
    fun isSubmarine(): Boolean = depth != null
    fun isAircraft(): Boolean = altitude != null
    fun isSurface(): Boolean = !isSubmarine() && !isAircraft()
}

/**
 * 轨迹/航路点（桌面版对象结构，实测 Iron Bottom Sound）：
 * {"Name":"","X":-400000,"Y":400000,"Speed":0,"Course":0,"AltitudeDepth":0,
 *  "AssignedAltDepth":0,"Ascent":0,"Descent":0,"Number":1,"IsTurnTime":true,
 *  "PositionTime":"1942-10-01 00:00:00"}
 */
data class Waypoint(
    @SerializedName("Name") var name: String = "",
    @SerializedName("X") var x: Long = 0,
    @SerializedName("Y") var y: Long = 0,
    @SerializedName("Speed") var speed: Int = 0,            // ×1000
    @SerializedName("Course") var course: Int = 0,           // ×1000
    @SerializedName("AltitudeDepth") var altitudeDepth: Int = 0,   // 米（原样存取，无定点）
    @SerializedName("AssignedAltDepth") var assignedAltDepth: Int = 0,  // 米（原样存取）
    @SerializedName("Ascent") var ascent: Int = 0,          // 爬升速率（米/回合）
    @SerializedName("Descent") var descent: Int = 0,        // 下降速率（米/回合）
    @SerializedName("Number") var number: Int = 1,
    @SerializedName("IsTurnTime") var isTurnTime: Boolean = true,
    @SerializedName("PositionTime") var positionTime: String = ""
)

/** 传感器射程弧（桌面版 SensorArray 元素；默认值对齐 CArc 构造：MaxRange=50、ArcColor=黄色 &h00FFFF00） */
data class Sensor(
    @SerializedName("Tag") var tag: String = "",
    @SerializedName("Label") var label: String = "",
    @SerializedName("MinRange") var minRange: Double = 0.0,
    @SerializedName("MaxRange") var maxRange: Double = 50.0,
    @SerializedName("StartAngle") var startAngle: Double = 0.0,
    @SerializedName("ArcAngle") var arcAngle: Double = 0.0,
    @SerializedName("ArcColor") var arcColor: String = "&h00FFFF00",
    @SerializedName("IsFilled") var isFilled: Boolean = false,
    @SerializedName("IsVisible") var isVisible: Boolean = false
)

/** 武器射程弧（桌面版 WeaponArray 元素，结构与 Sensor 相同） */
data class Weapon(
    @SerializedName("Tag") var tag: String = "",
    @SerializedName("Label") var label: String = "",
    @SerializedName("MinRange") var minRange: Double = 0.0,
    @SerializedName("MaxRange") var maxRange: Double = 50.0,
    @SerializedName("StartAngle") var startAngle: Double = 0.0,
    @SerializedName("ArcAngle") var arcAngle: Double = 0.0,
    @SerializedName("ArcColor") var arcColor: String = "&h00FFFF00",
    @SerializedName("IsFilled") var isFilled: Boolean = false,
    @SerializedName("IsVisible") var isVisible: Boolean = false
)

/**
 * 标签显示设置（键序与桌面版一致：
 * TagAltitude, TagCallsign, TagClass, TagCourseSpeed, TagDepth, TagName,
 * TagTrackNum, TagUnitType, AdditionalText）
 */
data class TextTags(
    @SerializedName("TagAltitude") var tagAltitude: Boolean = false,
    @SerializedName("TagCallsign") var tagCallsign: Boolean = false,
    @SerializedName("TagClass") var tagClass: Boolean = false,
    @SerializedName("TagCourseSpeed") var tagCourseSpeed: Boolean = true,
    @SerializedName("TagDepth") var tagDepth: Boolean = false,
    @SerializedName("TagName") var tagName: Boolean = false,
    @SerializedName("TagTrackNum") var tagTrackNum: Boolean = false,
    @SerializedName("TagUnitType") var tagUnitType: Boolean = false,
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

/**
 * 被动方位（R7，桌面版 PassiveBearings.CBearing）：
 * 声呐/ES 探测到的目标方位线，{Type, Bearing, Emitter, ES, Label, PositionTimeStart/End, ShowAsSide}。
 * BearingToJson 含 Emitter/ES 字段（反汇编确认）。
 */
data class PassiveBearing(
    @SerializedName("Type") var type: String = "ES",
    @SerializedName("BeamLength") var beamLength: Double = 0.0,
    @SerializedName("BeamWidth") var beamWidth: Double = 0.0,
    @SerializedName("Bearing") var bearing: Double = 0.0,               // 方位角（度，0=北）
    @SerializedName("Emitter") var emitter: String = "",                // 目标单位 IdNum
    @SerializedName("ES") var es: String = "",
    @SerializedName("Label") var label: String = "",
    @SerializedName("PositionTimeStart") var positionTimeStart: String = "",
    @SerializedName("PositionTimeEnd") var positionTimeEnd: String = "",
    @SerializedName("ShowAsSide") var showAsSide: String = "Unknown"
)
