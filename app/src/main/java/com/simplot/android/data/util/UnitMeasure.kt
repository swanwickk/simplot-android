package com.simplot.android.data.util

import com.simplot.android.data.model.ScenarioFile

/**
 * 点选单位自动测量（反馈②）：选中单位到所有其它单位的距离/方位。
 *
 * 纯 Kotlin 顶层函数（无 Android 依赖）→ 可直接 JVM 单测，
 * 避免 AndroidViewModel 需 Application 实例化导致单测依赖 Robolectric。
 */
data class UnitDistance(
    val idNum: String,
    val name: String,
    val side: String,
    val distNm: Double,
    val bearingDeg: Double
)

/**
 * 选中单位到所有其它单位的距离/方位，按距离升序。
 *
 * - 单位不存在 / 场景无单位 → 空列表
 * - 排除自身
 * - 距离/方位复用 [CoordUtil]（与桌面版 scn_tool.py 公式一致）
 */
fun unitDistances(file: ScenarioFile, unitId: String): List<UnitDistance> {
    val me = file.units.firstOrNull { it.idNum == unitId } ?: return emptyList()
    return file.units
        .filter { it.idNum != unitId }
        .map { other ->
            UnitDistance(
                idNum = other.idNum,
                name = other.name,
                side = other.side,
                distNm = CoordUtil.distanceNm(me.x, me.y, other.x, other.y),
                bearingDeg = CoordUtil.bearingDeg(me.x, me.y, other.x, other.y)
            )
        }
        .sortedBy { it.distNm }
}
