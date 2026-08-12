package com.simplot.android.data.model

import com.simplot.android.data.codec.JsonUtil

/**
 * G04 航路点导入核心逻辑（桌面版 WindowImportWaypoints → CopyExactWaypoints / CopyOffsetWaypoints）。
 *
 * 桌面语义（反汇编 CopyExactWaypoints / CopyOffsetWaypoints + 伪代码_UI交互层.md §32）：
 * - RadioButton1 = CopyExactWaypoints（精确复制）：深拷贝源单位未来航路点，
 *   位置/速度/航向/高度深度等全字段原样保留（绝对坐标不变）；
 * - RadioButton2 = CopyOffsetWaypoints（偏移复制）：深拷贝后在每个航路点上叠加
 *   「目标单位位置 − 源单位位置」的平移量（保留相对位置关系：源单位身边的航路点
 *   形状整体平移到目标单位身边；反汇编确认偏移量 = 目标单位 X/Y − 源单位 X/Y，
 *   通过 CopyWayPoint 深拷贝 + 叠加的方式逐点应用）；
 * - PopupMenu1：源单位选择，列出所有含航路点的单位（排除自身）。
 *
 * 深拷贝复用模型层 Waypoint JSON 编解码（JsonUtil.gson fromJson/toJson，即
 * JsonToWaypoint 等价路径，与 MovementOrdersCodec.parse 同款），与 G29 pasteUnitInto
 * 的深拷贝做法一致。编号按追加顺序重新排（桌面 Add(waypoint, count+1)）。
 *
 * 纯 Kotlin 无 Android 依赖 → 可 JVM 单测。
 */
enum class WaypointImportMode { EXACT, OFFSET }

object WaypointImporter {

    /** 深拷贝单个航路点（复用 JsonToWaypoint 等价 gson 路径，与 G29 同款） */
    fun deepCopy(wp: Waypoint): Waypoint =
        JsonUtil.gson.fromJson(JsonUtil.gson.toJson(wp), Waypoint::class.java)

    /**
     * 从 [source] 复制未来航路点到 [target]（桌面 CopyExactWaypoints / CopyOffsetWaypoints）。
     *
     * @param startNumber 追加到目标单位后的起始编号（目标现有航路点数 + 1；桌面 Add(waypoint, count+1)）
     * @return 深拷贝后的新航路点列表（不改动源单位数据）
     */
    fun copyFrom(source: Unit, target: Unit, mode: WaypointImportMode, startNumber: Int = 1): List<Waypoint> {
        // 偏移量 = 目标单位位置 − 源单位位置（精确复制时为 0）
        val dx = if (mode == WaypointImportMode.OFFSET) target.x - source.x else 0L
        val dy = if (mode == WaypointImportMode.OFFSET) target.y - source.y else 0L
        return source.futureWaypointArray.mapIndexed { i, wp ->
            val c = deepCopy(wp)
            c.x += dx
            c.y += dy
            c.number = startNumber + i
            c
        }
    }

    /**
     * 可导入的源单位候选（桌面 PopupMenu1 列表：所有含航路点的单位；排除自身）。
     */
    fun sourceCandidates(units: List<Unit>, self: Unit): List<Unit> =
        units.filter { it.idNum != self.idNum && it.futureWaypointArray.isNotEmpty() }
}
