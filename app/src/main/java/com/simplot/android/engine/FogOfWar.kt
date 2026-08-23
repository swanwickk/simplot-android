package com.simplot.android.engine

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.Perception
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit

/**
 * FogOfWar 感知过滤引擎（需求二核心）
 *
 * 职责：根据裁判设定的 PerceptionArray，生成某一阵营的"可见单位视图"。
 * 该视图用于保存 Blue.SpScn / Red.SpScn，实现红蓝存档中可见性的真实体现。
 *
 * 语义模型（与桌面版帮助文档一致）：
 * - 己方单位 → 始终可见（不可隐藏）。
 * - PerceptionArray 为空/缺失（未启用迷雾）→ 对双方均可见（兼容无迷雾的普通场景）。
 * - PerceptionArray 非空（已启用迷雾）→ 仅存在 `SeenBySide == 该阵营` 记录时可见。
 * - 桌面版 Perception 仅对对方阵营生效：Blue 单位只有 "Seen By Red" 组，
 *   Neutral 单位双方均可设置（本实现通过调用方传 side 控制）。
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
        val pa = unit.perceptionArray ?: return true
        if (pa.isEmpty()) return true
        // 已启用迷雾 → 仅当存在该阵营的感知记录
        return pa.any { p ->
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

        val pa = unit.perceptionArray ?: mutableListOf<Perception>().also { unit.perceptionArray = it }

        if (visible) {
            // 添加感知记录（该方可看到此单位）
            val existing = pa.firstOrNull { it.seenBySide == side }
            if (existing == null) {
                pa.add(
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
            pa.removeAll { it.seenBySide == side }
            // 若感知数组因此为空，添加己方占位记录，保持"迷雾已启用"状态（否则会退化为全可见）
            if (pa.isEmpty()) {
                pa.add(
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
     *
     * 落盘规则（与桌面版一致）：
     * - 不可见单位（无该阵营 Perception 记录且已启用迷雾）→ 剔除
     * - 可见单位 → 按该阵营 Perception 记录的受限项脱敏：
     *   ShowName=false→名称清空；ShowCourseSpeed=false→航向航速清零；
     *   ShowClass=false→级别清空；ShowAsType→类型替换；ShowAsSide→阵营替换；
     *   ShowAltitude/ShowDepth=false→高度/深度清零
     *
     * @param source 裁判全量数据
     * @param side   "Blue" / "Red"
     */
    fun applyPerspective(source: ScenarioFile, side: String): ScenarioFile {
        val copy = deepCopy(source)
        val visible = visibleUnits(copy.units, side)
        copy.units = visible.toMutableList()
        copy.objects = visible.map { it.idNum }.toMutableList()
        // W3 修复：侧文件保留触发可见的 Perception 记录（Mediterranean 实测 Red.SpScn 仍含 Perception）。
        // 己方单位清感知（全知）；敌方可见单位脱敏后仅保留该侧的可见记录，避免下一轮可见性丢失。
        visible.forEach { unit ->
            if (unit.side == side) {
                unit.perceptionArray = null
            } else {
                applyRestrictions(unit, side)
                // 仅保留 SeenBySide==side 的记录（触发可见的那条），其余感知清理；若过滤后为空则置 null（省略键）
                val kept = unit.perceptionArray?.filter { p -> p.seenBySide == side || p.seenBySide.equals(side, ignoreCase = true) }
                unit.perceptionArray = if (kept.isNullOrEmpty()) null else kept.toMutableList()
            }
        }
        copy.file = side
        return copy
    }

    /**
     * 对单位应用指定阵营感知记录的受限项脱敏（就地修改）。
     * 无该阵营感知记录 → 视为全可见（不脱敏）。
     */
    fun applyRestrictions(unit: Unit, side: String) {
        val rec = unit.perceptionArray?.firstOrNull { p ->
            p.seenBySide == side || p.seenBySide.equals(side, ignoreCase = true)
        } ?: return

        if (!rec.showName) unit.name = ""
        if (!rec.showCourseSpeed) {
            unit.speed = 0
            unit.course = 0
        }
        if (!rec.showClass) unit.unitClass = ""
        if (rec.showAsType.isNotBlank()) unit.unitType = rec.showAsType
        if (rec.showAsSide.isNotBlank() && rec.showAsSide != unit.side) unit.side = rec.showAsSide
        if (!rec.showAltitude) unit.altitude = null
        if (!rec.showDepth) unit.depth = null
    }

    /**
     * 校验红蓝视角差异是否已生效（用于 UI 提示/测试）
     */
    fun summarize(source: ScenarioFile): Map<String, Int> {
        val blue = visibleUnits(source.units, "Blue").size
        val red = visibleUnits(source.units, "Red").size
        return mapOf("referee" to source.units.size, "blue" to blue, "red" to red)
    }

    /** 深拷贝（Gson 序列化往返，复用 JsonUtil 统一实例以保持 Waypoint/键序兼容） */
    private fun deepCopy(src: ScenarioFile): ScenarioFile {
        return JsonUtil.fromJson(JsonUtil.toCompactJson(src))
    }
}
