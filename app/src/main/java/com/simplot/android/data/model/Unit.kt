package com.simplot.android.data.model

import com.google.gson.annotations.SerializedName
import kotlin.jvm.Transient

/**
 * 单位数据模型（对应存档 Units 数组元素，键序与桌面版 2.3.9 真实存档一致）
 *
 * 数值编码（与桌面版一致）：
 * - Speed = 节 × 1000
 * - Course = 度 × 1000（罗盘角 0=北 顺时针）
 * - Altitude = 米 × 1000（整数定点，桌面版实测 red 存档：3000000=3000米）
 * - Depth = 米 × 1000（同上，整数定点）
 * - X / Y = 海里 × 100000（整数定点，Y 向北为正）
 * - Range = 海里 × 1（整数，-100000 表示无限制）
 * ⚠️ 高度/深度为 ×1000 定点（与 Speed/Course 同规则；此前的"原样无定点"注释与实现矛盾，已修正注释，实现不变）
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
    @SerializedName("Range") var range: Int = -100000,             // 可移动距离（海里），-100000=无限制；R4：保留 Int 存档键，运行时用 rangeMm 毫米余额
    @SerializedName("RangeMm") var rangeMm: Long? = null,           // R4 毫米海里余额（1000=1海里；可空=旧存档/桌面互通回退用 Range 整数；持久化时与 Range 双写）
    @SerializedName("WpDistance") var wpDistance: Int = 0,          // 航路点距离（scn_tool 特有键，用户原始存档含此键，需保留）
    @SerializedName(value = "PastWaypointArray", alternate = ["PastWaypointArray1"]) var pastWaypointArray: MutableList<Waypoint> = mutableListOf(),   // 历史轨迹点（兼容用户场景 PastWaypointArray1）
    @SerializedName(value = "FutureWaypointArray", alternate = ["FutureWaypointArray1"]) var futureWaypointArray: MutableList<Waypoint> = mutableListOf(),  // 未来航路点（兼容 FutureWaypointArray1）
    @SerializedName("TextTags") var textTags: TextTags = TextTags(),
    @SerializedName("SensorArray") var sensorArray: MutableList<Sensor>? = null,       // 传感器射程弧
    @SerializedName("WeaponArray") var weaponArray: MutableList<Weapon>? = null,       // 武器射程弧
    @SerializedName("Altitude") var altitude: Int? = null,             // 飞机高度（米 ×1000 定点，桌面版实测 red 存档：3000000=3000米）
    @SerializedName("AssignedAltitude") var assignedAltitude: Int? = null,
    @SerializedName("Climb") var climb: Int? = null,                   // 爬升速率（米/回合，×1000）
    @SerializedName("Descend") var descend: Int? = null,
    @SerializedName("Depth") var depth: Int? = null,                // 潜艇深度（米 ×1000 定点）
    @SerializedName("AssignedDepth") var assignedDepth: Int? = null,
    @SerializedName("Ascend") var ascend: Int? = null,
    @SerializedName("DescRate") var descRate: Int? = null,
    @SerializedName("PerceptionArray") var perceptionArray: MutableList<Perception>? = null,
    @SerializedName("PassiveBearingArray") var passiveBearingArray: MutableList<PassiveBearing>? = null,   // 被动方位（声呐/ES，桌面版 PassiveBearings）

    /** 瞬态标记：本回合新加入的单位不移动（不落盘，Gson 忽略） */
    @Transient var isNewThisTurn: Boolean = false,

    /** 瞬态：Range 耗尽后选择"继续移动"（桌面版 Continue Movement = 无视 Range 继续航行，不落盘）；E3：maxSpeedKnots 已移除（D9 能力表死代码，勿恢复） */
    @Transient var ignoreRange: Boolean = false,

    /** 瞬态：编队 prepare 时的未来航路点备份（E12：cancel 恢复用，不落盘） */
    @Transient var formationWaypointBackup: MutableList<Waypoint>? = null,

    /** 瞬态：编队 prepare 时的移动前位置（#22：cancel 恢复用，不落盘；
     *  改用瞬态字段替代向 PastWaypointArray 加轨迹点，避免 DO_BEFORE 状态被状态机误判） */
    @Transient var formationPrepPosition: Pair<Long, Long>? = null,

    /** 瞬态：本回合是否消费了最后一个未来航路点（#6 G40：引擎精确标记，触发 NoFutureWaypoints 弹窗；不落盘） */
    @Transient var reachedFinalWaypoint: Boolean = false,
    /** R4 运行时毫米余额镜像（与 rangeMm 持久化键同步；-1=未初始化）；存盘用 rangeMm，运行时用此镜像避免每回合读可空装箱 */
    @Transient var rangeNmMm: Long = -1L
) {
    // ---- 便捷换算（节/度；高度/深度为 ×1000 定点，转米显示） ----
    /** R4：真实剩余 Range（海里，毫米精度；unlimited=-100000 保持语义）；运行时用 rangeNmMm/rangeMm 余额 */
    fun effectiveRangeNm(): Double = if (range == -100000) -1.0 else if (rangeNmMm >= 0) rangeNmMm / 1000.0 else if (rangeMm != null) rangeMm!! / 1000.0 else range.toDouble()
    fun isRangeUnlimited(): Boolean = range == -100000
    /** R4：同步写回 Int 存档键与 RangeMm 持久化键（向下取整海里存 Range，毫米余数留在 rangeMm/rangeNmMm） */
    fun syncRangeIntFromMm() {
        if (range == -100000) { rangeMm = null; return }
        if (rangeNmMm >= 0) { range = (rangeNmMm / 1000).toInt(); rangeMm = rangeNmMm }
        else if (rangeMm != null) range = (rangeMm!! / 1000).toInt()
    }
    /** R4：从持久化键初始化运行时余额（旧存档无 RangeMm 时按 Range 整海里回退） */
    fun initRangeMmFromPersisted() {
        if (range == -100000) { rangeNmMm = -1L; return }
        rangeNmMm = rangeMm ?: range.toLong() * 1000L
    }
    fun speedKnots(): Double = speed / 1000.0
    fun courseDeg(): Double = course / 1000.0

    /** 高度（米）：存档值 ÷1000（×1000 定点，桌面版实测） */
    fun altitudeMeters(): Int? = altitude?.div(1000)

    /** 深度（米）：存档值 ÷1000 */
    fun depthMeters(): Int? = depth?.div(1000)

    /** 设置高度（米 → ×1000 存） */
    fun setAltitude(meters: Int) { altitude = meters * 1000 }

    /** 设置深度（米 → ×1000 存） */
    fun setDepth(meters: Int) { depth = meters * 1000 }

    fun setSpeed(knots: Double) { speed = (knots * 1000).toInt() }
    fun setCourse(deg: Double) { course = ((deg % 360) * 1000).toInt() }

    /** 呼叫号显示值（G21）：独立呼叫号为空时回退单位名称（桌面版呼叫号=Name 语义） */
    fun callsignOrName(): String = textTags.callsign.ifBlank { name }

    // ---- 类型判断 ----
    fun isSubmarine(): Boolean = depth != null
    fun isAircraft(): Boolean = altitude != null
    fun isSurface(): Boolean = !isSubmarine() && !isAircraft()

    /**
     * 是否为未删除哨兵（2020-01-01 00:00:00 / 2999-* 远期 / 空串），与 ReplayEngine 哨兵一致。
     * 哨兵视为"未删除"，其余真实删除时间视为已删边界。
     */
    fun isNotDeletedSentinel(v: String): Boolean {
        if (v.isBlank()) return true
        return try {
            val d = com.simplot.android.data.util.TimeUtil.parse(v)
            d == java.time.LocalDateTime.of(2020, 1, 1, 0, 0, 0) || !d.isBefore(java.time.LocalDateTime.of(2999, 1, 1, 0, 0, 0))
        } catch (e: Exception) { false }
    }

    /** 当前时刻是否存活（Created <= now < Deleted，非哨兵同刻生死视为已删不画） */
    fun isAliveAt(nowStr: String): Boolean {
        return try {
            val now = com.simplot.android.data.util.TimeUtil.parse(nowStr)
            if (positionTimeCreated.isNotBlank()) {
                val c = com.simplot.android.data.util.TimeUtil.parse(positionTimeCreated)
                if (now.isBefore(c)) return false
            }
            if (positionTimeDeleted.isNotBlank() && !isNotDeletedSentinel(positionTimeDeleted)) {
                val d = com.simplot.android.data.util.TimeUtil.parse(positionTimeDeleted)
                if (!now.isBefore(d)) return false
            }
            true
        } catch (e: Exception) { true }
    }

    /** 同刻生死（Created==Deleted 非哨兵）即已删 — PC 不显示的 5 个白/蓝点根因 */
    fun isSameTickDeleted(): Boolean {
        if (positionTimeCreated.isBlank() || positionTimeDeleted.isBlank()) return false
        if (isNotDeletedSentinel(positionTimeDeleted)) return false
        return try {
            com.simplot.android.data.util.TimeUtil.parse(positionTimeCreated) ==
                com.simplot.android.data.util.TimeUtil.parse(positionTimeDeleted)
        } catch (e: Exception) { false }
    }
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
    @SerializedName("AltitudeDepth") var altitudeDepth: Int = 0,   // 米 ×1000 定点（red 存档实测 3000000=3000m）
    @SerializedName("AssignedAltDepth") var assignedAltDepth: Int = 0,  // 米 ×1000 定点
    @SerializedName("Ascent") var ascent: Int = 0,          // 爬升速率（米/回合 ×1000）
    @SerializedName("Descent") var descent: Int = 0,        // 下降速率（米/回合 ×1000）
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
 *
 * G21/tagCallsign 独立呼叫号字段：桌面版 CTextTag 的属性名为 Callsign（反汇编确认），
 * 但桌面版 TextTags JSON 固定 9 键、无独立呼叫号字符串键（呼叫号 = 单位 Name）。
 * 为满足"独立呼叫号字段 + 存档互通"（主 agent 决策，批次2-轮2）：
 * - [callsign] 为瞬态字段（@Transient，Gson 排除、不落盘）→ 序列化保持桌面 9 键
 *   字节级兼容（不得再恢复 @SerializedName("Callsign") 落盘键）；
 * - 渲染/UI 呼叫号显示统一走 [Unit.callsignOrName]（空串回退单位 Name），
 *   与桌面版"呼叫号 = Name"语义一致。
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
    @SerializedName("AdditionalText") var additionalText: String = "",
    /** 独立呼叫号（G21 编辑期字段；瞬态不落盘，空串=用单位 Name 显示，见 [Unit.callsignOrName]） */
    @Transient var callsign: String = ""
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

/**
 * 平移航路点列表（G32 Relocate：单位整体搬移时，其历史/未来航路点同步平移，
 * 保持轨迹与移动计划的绝对位置语义，等价桌面版 CanvasMap_MouseDrag → RecalcWaypoints）。
 *
 * 顶层纯函数（可 JVM 单测）：就地修改并返回同一列表；dx/dy 为文件单位增量。
 */
fun shiftWaypoints(waypoints: MutableList<Waypoint>, dx: Long, dy: Long): MutableList<Waypoint> {
    if (dx == 0L && dy == 0L) return waypoints
    for (w in waypoints) {
        w.x += dx
        w.y += dy
    }
    return waypoints
}
