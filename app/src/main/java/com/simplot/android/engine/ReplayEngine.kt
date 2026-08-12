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
 *
 * ⚠️ 性能（Phase 3 优化）：轨迹点时间戳预解析为 LocalDateTime 一次，
 * 帧构建用双指针维护"每单位最后已知位置"，避免 O(帧×点×parse)。
 */
object ReplayEngine {

    /**
     * G64/N7 哨兵澄清（对照桌面存档对齐值）：
     * - 未删除哨兵 = "2020-01-01 00:00:00"（PositionTimeDeleted 的默认值，P1-1 与桌面存档对齐；
     *   空串同理）—— 单位从未被删除，回放全程显示。
     * - 远期哨兵 = 2999 年起（感知 PositionTimeEnd 用 "2999-12-31 00:00:00" 表示"永不失效"）——
     *   删除时间在 2999 年视为永不删除。
     * 此前用 startsWith("2020-01-01") 前缀匹配当哨兵，会把"2020-01-01 12:00:00"这类
     * 真实删除时间误判为未删除 → 该时刻删除的单位回放不隐藏。修正为完整语义比较。
     */
    private val SENTINEL_NOT_DELETED: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0)
    private val SENTINEL_END_OF_TIME: LocalDateTime = LocalDateTime.of(2999, 1, 1, 0, 0, 0)

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

    /** 预解析轨迹点：时间戳 → LocalDateTime（避免回放中重复 parse） */
    private class TrackPoint(
        val time: LocalDateTime,
        val x: Long,
        val y: Long
    )

    /**
     * 从存档重建回放时间线。
     * @return 按时间升序的帧列表；空轨迹/无时间数据的单位仅出现在最后（当前位置）。
     * E7 修复：单位在 PositionTimeCreated 之前、PositionTimeDeleted 之后不显示（防止"提前出现/死后复活"）。
     */
    fun buildTimeline(file: ScenarioFile): List<Frame> {
        // 收集所有时间点（字符串格式即字典序=时间序）
        val times = sortedSetOf<String>()
        for (u in file.units) {
            for (wp in u.pastWaypointArray) {
                if (wp.positionTime.isNotBlank()) times.add(wp.positionTime)
            }
        }
        times.add(file.time.currentPositionTime)
        if (times.isEmpty()) return emptyList()

        // 每单位预解析轨迹（按时间升序，字符串字典序即时间序）
        val tracks = file.units.associateWith { u ->
            u.pastWaypointArray
                .filter { it.positionTime.isNotBlank() }
                .sortedBy { it.positionTime }
                .map { TrackPoint(TimeUtil.parse(it.positionTime), it.x, it.y) }
        }
        val currentTime = TimeUtil.parse(file.time.currentPositionTime)

        val list = times.toList()
        return list.map { t ->
            val target = TimeUtil.parse(t)
            val positions = mutableMapOf<String, UnitPos>()
            for (u in file.units) {
                positionAt(u, tracks[u] ?: emptyList(), target, currentTime)?.let { positions[u.idNum] = it }
            }
            Frame(time = t, positions = positions)
        }
    }

    /**
     * 单位在指定时刻的位置：二分查找 ≤ target 的最后一个轨迹点；无则当前位置。
     * E7：单位创建前/删除后返回 null（帧中不显示）；当前时刻起用当前位置。
     */
    private fun positionAt(u: Unit, track: List<TrackPoint>, target: LocalDateTime, currentTime: LocalDateTime): UnitPos? {
        // E7：创建前不显示（PositionTimeCreated 早于该帧时间才存在）
        if (u.positionTimeCreated.isNotBlank()) {
            try {
                val created = TimeUtil.parse(u.positionTimeCreated)
                if (target.isBefore(created)) return null
            } catch (e: Exception) { /* 解析失败容忍 */ }
        }
        // E7/G64：删除后不显示（哨兵值 = 未删除/永不失效，不生效）
        if (u.positionTimeDeleted.isNotBlank() && !isNotDeletedSentinel(u.positionTimeDeleted)) {
            try {
                val deleted = TimeUtil.parse(u.positionTimeDeleted)
                if (!target.isBefore(deleted)) return null
            } catch (e: Exception) { /* 容忍 */ }
        }
        // 已到当前时间 → 当前位置（轨迹点之后单位已移动）
        if (!target.isBefore(currentTime)) {
            return UnitPos(u.idNum, u.side, u.name, u.x, u.y)
        }
        if (track.isEmpty()) return UnitPos(u.idNum, u.side, u.name, u.x, u.y)
        // 二分：最后一个 time <= target 的点
        var lo = 0
        var hi = track.size - 1
        var best: TrackPoint? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val p = track[mid]
            if (!p.time.isAfter(target)) {
                best = p
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val b = best ?: return UnitPos(u.idNum, u.side, u.name, u.x, u.y)
        return UnitPos(u.idNum, u.side, u.name, b.x, b.y)
    }

    /** 删除时间是否为"未删除/永不失效"哨兵（空串、2020-01-01 默认值、2999 远期值）；解析失败视为非哨兵 */
    private fun isNotDeletedSentinel(v: String): Boolean {
        if (v.isBlank()) return true
        return try {
            val d = TimeUtil.parse(v)
            d == SENTINEL_NOT_DELETED || !d.isBefore(SENTINEL_END_OF_TIME)
        } catch (e: Exception) {
            false
        }
    }

    /** 时间字符串转可排序键（字符串格式即字典序一致） */
    fun sortKey(t: String): LocalDateTime = TimeUtil.parse(t)
}
