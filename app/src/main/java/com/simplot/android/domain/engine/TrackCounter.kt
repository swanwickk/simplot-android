package com.simplot.android.domain.engine

import com.simplot.android.data.model.ScenarioFile

/**
 * TrackNumber 分配器（桌面版 GetTrackNumber / GetPlayerTrackNumber：蓝/红各一套计数器）。
 *
 * P0 修复：红方用 `currentPlayerTrackNumber`，其余用 `currentTrackNumber`；
 * 取 max(计数器, 同侧现有最大) + 1 并写回（删除后不回退）。
 * G63/N3 修复：`现有最大` 按侧过滤（红方只统计 Side=="Red" 单位，其余统计非红单位）——
 * 此前取全场景全局 max，蓝方新单位会被红方大号段顶高（桌面蓝/红各一套计数器，互不干扰）。
 * Neutral/Unknown 等非红阵营与蓝方共用一套计数器（桌面 CUnit 构造默认 Neutral 走 GetTrackNumber）。
 * N2 修复：护航队创建路径也走本分配器（原先 ConvoyEngine 本地 max+1 不写回计数器，
 * 桌面续建单位会撞号）。GameViewModel.createNewUnit / duplicateUnit / createConvoy 共用。
 *
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object TrackCounter {

    /** 空场景兜底起始轨迹号（桌面版计数器默认 2400，分配从 2401 起） */
    const val DEFAULT_TRACK_NUMBER = 2400

    /**
     * 分配下一个 TrackNumber 并写回场景计数器。
     *
     * @param file 场景（计数器与现有单位都会被读取；计数器被写回）
     * @param side 阵营："Red" 走 currentPlayerTrackNumber，其余走 currentTrackNumber
     * @return 新单位应使用的 TrackNumber
     */
    fun allocate(file: ScenarioFile, side: String): Int {
        if (side == "Red") {
            // 只统计红方单位：蓝方大号段不顶高红方计数器（G63/N3 分侧语义）
            val maxTn = file.units.asSequence()
                .filter { it.side == "Red" }
                .maxOfOrNull { it.trackNumber } ?: file.scenario.currentPlayerTrackNumber
            val next = maxOf(file.scenario.currentPlayerTrackNumber, maxTn) + 1
            file.scenario.currentPlayerTrackNumber = next
            return next
        }
        // 非红方（Blue/Neutral/Unknown 等）共用蓝方计数器；红方单位不参与
        val maxTn = file.units.asSequence()
            .filter { it.side != "Red" }
            .maxOfOrNull { it.trackNumber } ?: file.scenario.currentTrackNumber
        val next = maxOf(file.scenario.currentTrackNumber, maxTn) + 1
        file.scenario.currentTrackNumber = next
        return next
    }
}
