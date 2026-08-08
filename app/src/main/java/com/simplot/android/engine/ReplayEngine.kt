package com.simplot.android.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.util.TimeUtil
import java.time.LocalDateTime

/**
 * 回合回放引擎（对应桌面版 Turn Replay 菜单）。
 *
 * 数据来源：各单位 PastWaypointArray 轨迹点（含 PositionTime 时间戳）。
 * 每个轨迹点 = 该单位在该时刻的位置；当前位置 = 最后时刻位置。
 *
 * 时间线：收集所有轨迹点时间 → 排序去重 → 每一帧记录各单位位置。
 * 回放时按时间顺序切换帧，模拟桌面版 Play / Back / Forward / Pause。
 */
object ReplayEngine {

    /** 回放帧：某时刻各单位的位置 */
    data class Frame(
        val time: String,                                   // 时刻（存档时间格式）
        val positions: Map<String, UnitPos>                  // IdNum → 位置
    )

    data class UnitPos(
        val idNum: String,
        val side: String,
        val name: String,
        val x: Long,
        val y: Long
    )

    /**
     * 从存档重建回放时间线。
     * @return 按时间升序的帧列表；空轨迹/无时间数据的单位仅出现在最后（当前位置）。
     */
    fun buildTimeline(file: ScenarioFile): List<Frame> {
        // 收集所有时间点
        val times = sortedSetOf<String>()
        for (u in file.units) {
            for (wp in u.pastWaypointArray) {
                if (wp.positionTime.isNotBlank()) times.add(wp.positionTime)
            }
        }
        // 当前时间帧（单位当前位置对应的时刻，必须作为末帧）
        times.add(file.time.currentPositionTime)
        if (times.isEmpty()) return emptyList()

        val list = times.toList()
        return list.map { t ->
            val positions = mutableMapOf<String, UnitPos>()
            for (u in file.units) {
                // 该时刻的单位位置：时间戳 <= t 的最后一个轨迹点；否则当前位置
                val at = positionAt(u, t, file)
                positions[u.idNum] = at
            }
            Frame(time = t, positions = positions)
        }
    }

    /** 单位在指定时刻的位置（按轨迹点时间戳插值到"最后已知"） */
    private fun positionAt(u: Unit, t: String, file: ScenarioFile): UnitPos {
        // t 已到当前时间 → 当前位置（最后轨迹点之后单位已移动）
        if (t >= file.time.currentPositionTime) {
            return UnitPos(u.idNum, u.side, u.name, u.x, u.y)
        }
        val target = TimeUtil.parse(t)
        var best: UnitPos? = null
        for (wp in u.pastWaypointArray) {
            if (wp.positionTime.isBlank()) continue
            val wt = TimeUtil.parse(wp.positionTime)
            if (!wt.isAfter(target)) {
                best = UnitPos(u.idNum, u.side, u.name, wp.x, wp.y)
            }
        }
        return best ?: UnitPos(u.idNum, u.side, u.name, u.x, u.y)
    }

    /** 时间字符串转可排序键（字符串格式即字典序一致） */
    fun sortKey(t: String): LocalDateTime = TimeUtil.parse(t)
}
