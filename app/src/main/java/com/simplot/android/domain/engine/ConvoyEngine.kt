package com.simplot.android.domain.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.util.CoordUtil

/**
 * 护航队创建引擎（P2 恢复，桌面版 Game.Convoy.CreateConvoy）。
 *
 * 逻辑（反汇编确认）：COMMODORE 居中，Merchant 商船环绕，
 * 角度均匀分布 = 360/商船数 × i，距离固定（默认 2000 码），阵营 Blue。
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object ConvoyEngine {

    data class ConvoySpec(
        val commodoreName: String = "COMMODORE",
        val escortCount: Int = 6,
        val distYards: Int = 2000,
        val side: String = "Blue",
        val formationName: String = "Convoy"
    )

    /**
     * 生成护航队单位列表（不含 IdNum 分配，调用方负责 nextId）。
     * @param nextId 为每个新单位生成 IdNum 的 lambda（调用方传，保持纯函数）
     */
    fun build(
        file: ScenarioFile,
        spec: ConvoySpec,
        nextId: (prefix: String) -> String
    ): List<Unit> {
        val maxTn = file.units.maxOfOrNull { it.trackNumber } ?: 2400
        val commodore = Unit(
            idNum = nextId("S"),
            side = spec.side,
            unitType = "Merchant",
            unitClass = "AO",
            name = spec.commodoreName,
            trackNumber = maxTn + 1,
            isNewThisTurn = true,
            isFormationCenter = true,
            formationName = spec.formationName
        )
        val units = mutableListOf(commodore)
        val distFile = CoordUtil.yardsToFile(spec.distYards.toDouble()).toInt()
        for (i in 0 until spec.escortCount) {
            val angle = 360.0 / spec.escortCount * i
            val (dx, dy) = CoordUtil.offsetYards(angle, spec.distYards.toDouble())
            units.add(
                Unit(
                    idNum = nextId("S"),
                    side = spec.side,
                    unitType = "Merchant",
                    unitClass = "AO",
                    name = "Merchant ${i + 1}",
                    trackNumber = maxTn + 1 + i + 1,
                    x = commodore.x + dx,
                    y = commodore.y + dy,
                    isNewThisTurn = true,
                    isInFormation = true,
                    formationName = spec.formationName,
                    formationBearing = (angle * 1000).toInt(),
                    formationDistance = distFile
                )
            )
        }
        return units
    }
}
