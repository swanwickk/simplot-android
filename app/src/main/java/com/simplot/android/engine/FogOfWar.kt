package com.simplot.android.engine

import com.simplot.android.data.model.Perception
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit

/**
 * FogOfWar 感知过滤引擎（需求二核心）
 *
 * 职责：根据裁判设定的 PerceptionArray，生成某一阵营的"可见单位视图"。
 * 该视图用于保存 Blue.SpScn / Red.SpScn，实现红蓝存档中可见性的真实体现。
 *
 * 语义模型（自洽约定）：
 * - 己方单位 → 始终可见（不可隐藏）。
 * - PerceptionArray 为空（未启用迷雾）→ 对双方均可见（兼容无迷雾的普通场景）。
 * - PerceptionArray 非空（已启用迷雾）→ 仅存在 `SeenBySide == 该阵营` 记录时可见。
 *
 * 落盘规则（与桌面版兼容）：
 * - Referee.json：全量单位 + 完整 PerceptionArray（裁判全知）。
 * - Blue/Red.SpScn：仅保留该阵营可见的单位（不可见单位被剔除）。
 */
object FogOfWar {

    /** 单位是否被指定阵营看见 */
    fun isVisibleTo(unit: Unit, side: String): Boolean {
        // 己方单位始终可见
        if (unit.side == side) return true
        // 未启用迷雾（无感知记录）→ 全可见
        if (unit.perceptionArray.isEmpty()) return true
        // 已启用迷雾 → 仅当存在该阵营的感知记录
        return unit.perceptionArray.any { p ->
            p.seenBySide == side || p.seenBySide.equals(side, ignoreCase = true)
        }
    }

    /**
     * 设置单位对某阵营的可见性（需求二 UI 入口）。
     * - visible=true  → 添加该阵营的感知记录（SeenBySide=该阵营）
     * - visible=false → 移除该阵营的感知记录；若因此清空，则添加一条己方占位记录以保持"迷雾已启用"标记
     * @param file 可选：若非 null 则同步更新该存档单位（通常传 null 由调用方提交）
     */
    fun setVisibility(unit: Unit, side: String, visible: Boolean, time: String, file: ScenarioFile?) {
        // 己方单位不允许隐藏自己
        if (unit.side == side) return

        if (visible) {
            // 添加感知记录（该方可看到此单位）
            val existing = unit.perceptionArray.firstOrNull { it.seenBySide == side }
            if (existing == null) {
                unit.perceptionArray.add(
                    Perception(
                        positionTimeStart = time,
                        positionTimeEnd = "2999-12-31 00:00:00",
                        detectionTime = time,
                        seenBySide = side,
                        showAsSide = unit.side,
                        showAsType = unit.unitType,
                        showAltitude = true,
                        showClass = true,
                        showCourseSpeed = true,
                        showDepth = true,
                        showName = true
                    )
                )
            }
        } else {
            // 移除该阵营感知记录 → 该方不可见
            unit.perceptionArray.removeAll { it.seenBySide == side }
            // 若感知数组因此为空，添加己方占位记录，保持"迷雾已启用"状态（否则会退化为全可见）
            if (unit.perceptionArray.isEmpty()) {
                unit.perceptionArray.add(
                    Perception(
                        positionTimeStart = time,
                        positionTimeEnd = "2999-12-31 00:00:00",
                        detectionTime = time,
                        seenBySide = unit.side,
                        showAsSide = unit.side,
                        showAsType = unit.unitType,
                        showAltitude = true, showClass = true,
                        showCourseSpeed = true, showDepth = true, showName = true
                    )
                )
            }
        }
    }

    /**
     * 生成某阵营可见的单位列表（用于 Blue/Red.SpScn）。
     * 可见单位原样保留；不可见单位被剔除。
     */
    fun visibleUnits(units: List<Unit>, side: String): List<Unit> {
        return units.filter { isVisibleTo(it, side) }
    }

    /**
     * 生成某阵营视角的完整 ScenarioFile（深拷贝，不污染原数据）。
     * @param source 裁判全量数据
     * @param side   "Blue" / "Red"
     */
    fun applyPerspective(source: ScenarioFile, side: String): ScenarioFile {
        val copy = deepCopy(source)
        val visible = visibleUnits(copy.units, side)
        copy.units = visible.toMutableList()
        copy.objects = visible.map { it.idNum }.toMutableList()
        // 玩家视角下，感知字段对己方无意义（自己总能看到全部），清空以保持简洁并兼容桌面版
        visible.forEach { it.perceptionArray = mutableListOf() }
        copy.file = side
        return copy
    }

    /**
     * 校验红蓝视角差异是否已生效（用于 UI 提示/测试）
     */
    fun summarize(source: ScenarioFile): Map<String, Int> {
        val blue = visibleUnits(source.units, "Blue").size
        val red = visibleUnits(source.units, "Red").size
        return mapOf("referee" to source.units.size, "blue" to blue, "red" to red)
    }

    /** 深拷贝（Gson 序列化往返） */
    private fun deepCopy(src: ScenarioFile): ScenarioFile {
        val gson = com.google.gson.GsonBuilder().create()
        val json = gson.toJson(src)
        return gson.fromJson(json, ScenarioFile::class.java)
    }
}
