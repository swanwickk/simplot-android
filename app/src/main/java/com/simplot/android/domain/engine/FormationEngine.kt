package com.simplot.android.domain.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint

/**
 * 编队引擎（R6，桌面版 Formations.Movement 对应）。
 *
 * - prepare：移动前为编队成员生成目标航路点（DoPrepare）
 * - cancel：撤销编队移动，恢复成员原航路点（DoCancel → CancelCompass/Course/ColumnMovement）
 * - search：查找包含单位的编队（SearchForFormation）
 * - remove：把单位从编队移除（RemoveUnitFromFormation）
 *
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object FormationEngine {

    /** 编队成员判定：在编队中且非中心 */
    fun isMember(u: Unit): Boolean = u.isInFormation && !u.isFormationCenter

    /** 查找编队中心单位（IsFormationCenter 优先，否则同编队第一个） */
    fun centerOf(units: List<Unit>, formationName: String): Unit? {
        val members = units.filter { (it.isInFormation || it.isFormationCenter) && it.formationName == formationName }
        return members.firstOrNull { it.isFormationCenter } ?: members.firstOrNull()
    }

    /** 编队成员列表（不含中心） */
    fun membersOf(units: List<Unit>, formationName: String): List<Unit> =
        units.filter { isMember(it) && it.formationName == formationName }

    /** 单位所属编队名（无则空） */
    fun formationOf(u: Unit): String = u.formationName

    /**
     * 编队移动准备（桌面版 DoPrepare）：为中心外每个成员在当前位置创建航路点，
     * 记录移动前位置（供取消恢复）。返回创建了多少航路点。
     */
    fun prepare(units: List<Unit>, formationName: String): Int {
        var count = 0
        for (m in membersOf(units, formationName)) {
            // 记录移动前位置到 PastWaypointArray（供 Cancel 恢复 + 轨迹）
            if (m.pastWaypointArray.none { it.x == m.x && it.y == m.y }) {
                m.pastWaypointArray.add(Waypoint(x = m.x, y = m.y, number = 1, isTurnTime = true))
                count++
            }
        }
        return count
    }

    /**
     * 编队移动撤销（桌面版 DoCancel）：把成员恢复到移动前位置（从 PastWaypointArray 末尾取）。
     * 清除成员航路点（未来航路点被恢复为准备时状态：清空）。
     * @return 恢复的成员数
     */
    fun cancel(units: List<Unit>, formationName: String): Int {
        var count = 0
        for (m in membersOf(units, formationName)) {
            val prev = m.pastWaypointArray.lastOrNull()
            if (prev != null) {
                m.x = prev.x
                m.y = prev.y
                m.pastWaypointArray.removeAt(m.pastWaypointArray.size - 1)
                m.futureWaypointArray.clear()
                count++
            }
        }
        return count
    }

    /** 把单位从编队移除（桌面版 RemoveUnitFromFormation）：清队形标志 */
    fun removeFromFormation(u: Unit) {
        u.isInFormation = false
        u.isFormationCenter = false
        u.formationName = ""
    }

    /** 场景中所有编队名（去重，保序） */
    fun formationNames(file: ScenarioFile): List<String> =
        file.units.map { it.formationName }.filter { it.isNotBlank() }.distinct()
}
