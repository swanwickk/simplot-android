package com.simplot.android.domain.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.util.CoordUtil

/**
 * 护航队创建引擎（P2 恢复，桌面版 Game.Convoy.CreateConvoy）。
 *
 * 两种布局（G03 补全桌面 WindowConvoy 参数）：
 * - 环绕布局（默认，反汇编确认）：COMMODORE 居中，Merchant 商船环绕，
 *   角度均匀分布 = 360/商船数 × i，距离 = distYards（默认 2000 码），阵营 Blue。
 * - 网格布局（桌面 WindowConvoy TextNumCols/TextNumRows/TextSpaceCols/TextSpaceRows）：
 *   商船按 列×行 排布、网格居中于指挥舰，间距 = spaceColsYards/spaceRowsYards（码）。
 *   列/行任一 ≤0 时回退环绕布局（向后兼容）。
 * - 航向/速度（桌面 TextCourse/TextSpeed）：对指挥舰与全部商船统一设置。
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object ConvoyEngine {

    data class ConvoySpec(
        val commodoreName: String = "COMMODORE",
        val escortCount: Int = 6,
        val distYards: Int = 2000,
        val side: String = "Blue",
        val formationName: String = "Convoy",
        // G03：桌面版 WindowConvoy 六参数全部可选，默认值与旧版行为完全一致（向后兼容）。
        /** 护航队统一航向（度，0=北；对指挥舰与全部商船生效） */
        val courseDeg: Double = 0.0,
        /** 护航队统一航速（节） */
        val speedKnots: Double = 0.0,
        /** 网格列数；>0 且 numRows>0 时启用网格布局（商船数=列×行，忽略 escortCount） */
        val numCols: Int = 0,
        /** 网格行数 */
        val numRows: Int = 0,
        /** 列间距（码） */
        val spaceColsYards: Int = 0,
        /** 行间距（码） */
        val spaceRowsYards: Int = 0
    ) {
        /** 网格布局是否启用（列/行均 >0） */
        fun isGridLayout(): Boolean = numCols > 0 && numRows > 0

        /** 商船总数（网格模式=列×行，环绕模式=escortCount） */
        fun merchantCount(): Int = if (isGridLayout()) numCols * numRows else escortCount
    }

    /**
     * 生成护航队单位列表（IdNum 与 TrackNumber 分配由调用方注入，保持纯函数）。
     * @param nextId 为每个新单位生成 IdNum 的 lambda（调用方传）
     * @param nextTrackNumber 为每个新单位分配 TrackNumber 的 lambda（调用方传；
     *        N2 修复：必须走 TrackCounter 计数器写回，否则桌面续建单位会撞号）
     * @param centerX 指挥舰世界坐标 X（#4 修复：默认 0；GameViewModel 传视野中心，
     *        避免护航队整体落在 (0,0) 视野外——与 NewUnitDialog 默认=视野中心同源修复）
     * @param centerY 指挥舰世界坐标 Y
     */
    fun build(
        file: ScenarioFile,
        spec: ConvoySpec,
        nextId: (prefix: String) -> String,
        nextTrackNumber: (side: String) -> Int,
        centerX: Long = 0L,
        centerY: Long = 0L
    ): List<Unit> {
        val commodore = Unit(
            idNum = nextId("S"),
            side = spec.side,
            unitType = "Merchant",
            unitClass = "AO",
            name = spec.commodoreName,
            trackNumber = nextTrackNumber(spec.side),
            x = centerX,
            y = centerY,
            isNewThisTurn = true,
            isFormationCenter = true,
            formationName = spec.formationName
        )
        // G03：护航队统一航向/航速（桌面 WindowConvoy TextCourse/TextSpeed）
        commodore.setCourse(spec.courseDeg)
        commodore.setSpeed(spec.speedKnots)
        val units = mutableListOf(commodore)
        if (spec.isGridLayout()) {
            // 网格布局：formationBearing/Distance 用罗盘方位角+距离表示（与环绕布局同约定，
            // 编队移动引擎 moveFormations 可直接驱动）；序号 = 行优先 列×行 位置。
            for (r in 0 until spec.numRows) {
                for (c in 0 until spec.numCols) {
                    val offX = (c - (spec.numCols - 1) / 2.0) * spec.spaceColsYards
                    val offY = (r - (spec.numRows - 1) / 2.0) * spec.spaceRowsYards
                    val dx = CoordUtil.yardsToFile(offX)
                    val dy = CoordUtil.yardsToFile(offY)
                    units.add(
                        gridMerchant(
                            spec, commodore, dx, dy, index = r * spec.numCols + c + 1,
                            nextId, nextTrackNumber
                        )
                    )
                }
            }
        } else {
            // 环绕布局（桌面 Game.Convoy.CreateConvoy 反汇编逻辑）：角度均匀分布 360/n × i
            val distFile = CoordUtil.yardsToFile(spec.distYards.toDouble()).toInt()
            for (i in 0 until spec.escortCount) {
                val angle = 360.0 / spec.escortCount * i
                val (dx, dy) = CoordUtil.offsetYards(angle, spec.distYards.toDouble())
                units.add(
                    circleMerchant(
                        spec, commodore, dx, dy, angle, distFile, index = i + 1,
                        nextId, nextTrackNumber
                    )
                )
            }
        }
        return units
    }

    /** 环绕布局商船（原有逻辑：名称/方位角/距离与既有测试一致） */
    private fun circleMerchant(
        spec: ConvoySpec,
        commodore: Unit,
        dx: Long,
        dy: Long,
        angle: Double,
        distFile: Int,
        index: Int,
        nextId: (prefix: String) -> String,
        nextTrackNumber: (side: String) -> Int
    ): Unit = Unit(
        idNum = nextId("S"),
        side = spec.side,
        unitType = "Merchant",
        unitClass = "AO",
        name = "Merchant $index",
        trackNumber = nextTrackNumber(spec.side),
        x = commodore.x + dx,
        y = commodore.y + dy,
        isNewThisTurn = true,
        isInFormation = true,
        formationName = spec.formationName,
        formationBearing = (angle * 1000).toInt(),
        formationDistance = distFile
    ).also { it.setCourse(spec.courseDeg); it.setSpeed(spec.speedKnots) }

    /** 网格布局商船（G03：阵位 = 距中心 (dx,dy) 文件单位，bearing = 罗盘方位角） */
    private fun gridMerchant(
        spec: ConvoySpec,
        commodore: Unit,
        dx: Long,
        dy: Long,
        index: Int,
        nextId: (prefix: String) -> String,
        nextTrackNumber: (side: String) -> Int
    ): Unit {
        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
        val bearing = CoordUtil.bearingDeg(0, 0, dx, dy)
        return Unit(
            idNum = nextId("S"),
            side = spec.side,
            unitType = "Merchant",
            unitClass = "AO",
            name = "Merchant $index",
            trackNumber = nextTrackNumber(spec.side),
            x = commodore.x + dx,
            y = commodore.y + dy,
            isNewThisTurn = true,
            isInFormation = true,
            formationName = spec.formationName,
            formationBearing = (bearing * 1000).toInt(),
            formationDistance = dist
        ).also { it.setCourse(spec.courseDeg); it.setSpeed(spec.speedKnots) }
    }
}
