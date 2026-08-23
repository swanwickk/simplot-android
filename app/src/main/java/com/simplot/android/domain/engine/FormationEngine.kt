package com.simplot.android.domain.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit

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
    fun isMember(u: Unit): Boolean = u.isInFormation == true && u.isFormationCenter != true

    /** 查找编队中心单位（IsFormationCenter 优先，否则同编队第一个） */
    fun centerOf(units: List<Unit>, formationName: String): Unit? {
        val members = units.filter { (it.isInFormation == true || it.isFormationCenter == true) && it.formationName == formationName }
        return members.firstOrNull { it.isFormationCenter == true } ?: members.firstOrNull()
    }

    /** 编队成员列表（不含中心） */
    fun membersOf(units: List<Unit>, formationName: String): List<Unit> =
        units.filter { isMember(it) && it.formationName == formationName }

    /** 单位所属编队名（无则空） */
    fun formationOf(u: Unit): String = u.formationName ?: ""

    /**
     * 编队移动准备（桌面版 DoPrepare）：为中心外每个成员备份当前未来航路点
     * （E12 修复：取消时要恢复原航路点，桌面版 CancelMovement 用 CopyWayPoint 恢复），
     * 并记录移动前位置（#22 修复：改用瞬态字段 formationPrepPosition 而非向 PastWaypointArray
     * 加轨迹点，避免 DO_BEFORE 状态下准备编队被 TurnState.detect 误判为"回合已确认"）。
     * @return 处理了多少成员
     */
    fun prepare(units: List<Unit>, formationName: String): Int {
        var count = 0
        for (m in membersOf(units, formationName)) {
            // E12：备份未来航路点（取消时恢复，而不是清空）
            m.formationWaypointBackup = m.futureWaypointArray.toMutableList()
            // #22：记录移动前位置到瞬态字段（供 Cancel 恢复；不产生状态机轨迹）
            m.formationPrepPosition = m.x to m.y
            count++
        }
        return count
    }

    /**
     * 编队移动撤销（桌面版 DoCancel）：把成员恢复到移动前位置（formationPrepPosition），
     * 并恢复 prepare 时备份的未来航路点（E12 修复，原实现直接清空会丢成员规划航线）。
     * @return 恢复的成员数
     */
    fun cancel(units: List<Unit>, formationName: String): Int {
        var count = 0
        for (m in membersOf(units, formationName)) {
            val prev = m.formationPrepPosition
            if (prev != null) {
                m.x = prev.first
                m.y = prev.second
                m.formationPrepPosition = null
                // E12：恢复备份的未来航路点；无备份时清空（旧行为兜底）
                m.futureWaypointArray = m.formationWaypointBackup?.toMutableList() ?: mutableListOf()
                m.formationWaypointBackup = null
                count++
            }
        }
        return count
    }

    /** 把单位从编队移除（桌面版 RemoveUnitFromFormation）：清队形标志 */
    fun removeFromFormation(u: Unit) {
        u.isInFormation = null
        u.isFormationCenter = null
        u.formationName = null
    }

    /** 场景中所有编队名（去重，保序） */
    fun formationNames(file: ScenarioFile): List<String> =
        file.units.map { it.formationName ?: "" }.filter { it.isNotBlank() }.distinct()

    // ================= G02 编队编辑器：创建/重命名/删除/成员/设中心/类型/距离单位 =================

    /** 队形类型（桌面版 Formations.FormationTypes） */
    object FormationTypes {
        const val COLUMN = "Column"                    // 纵队：相对队形轴线
        const val COMPASS = "RelativeToCompass"        // 罗盘：相对罗盘方位
        const val COURSE = "RelativeToCourse"          // 航向：相对编队航向
    }

    /** 距离单位（桌面版 GetEnumDistance：nmi / yards / meters） */
    object FormationDistanceUnits {
        const val NMI = "nmi"
        const val YARDS = "yards"
        const val METERS = "meters"
    }

    /**
     * 队形规格（G02：创建时定义的类型/距离单位）。
     * 存档层队形由成员单位携带字段（formationName/formationType…），
     * 空队形仅存于内存注册表（MutableMap），有成员才随存档持久化。
     */
    data class FormationSpec(
        val name: String,
        val type: String = FormationTypes.COLUMN,
        val distanceUnit: String = FormationDistanceUnits.NMI
    )

    /** 注册/更新队形规格（创建或改类型/距离单位） */
    fun registerFormation(specs: MutableMap<String, FormationSpec>, name: String, type: String, distanceUnit: String): FormationSpec {
        val spec = FormationSpec(name, type, distanceUnit)
        specs[name] = spec
        return spec
    }

    /** 队形重命名：更新规格键 + 全部成员 formationName。@return 受影响成员数 */
    fun renameFormation(units: List<Unit>, specs: MutableMap<String, FormationSpec>, oldName: String, newName: String): Int {
        if (oldName == newName) return 0
        var count = 0
        for (u in units) {
            if (u.formationName == oldName) {
                u.formationName = newName
                count++
            }
        }
        specs[oldName]?.let {
            specs.remove(oldName)
            specs[newName] = it.copy(name = newName)
        }
        return count
    }

    /** 删除队形：清全部成员队形标志 + 移除规格。@return 受影响成员数 */
    fun deleteFormation(units: List<Unit>, specs: MutableMap<String, FormationSpec>, name: String): Int {
        var count = 0
        for (u in units) {
            if (u.formationName == name) {
                removeFromFormation(u)
                count++
            }
        }
        specs.remove(name)
        return count
    }

    /** 设中心：同队形内先清旧中心，再把指定单位设为中心（中心不属于成员）。@return 是否成功 */
    fun setCenter(units: List<Unit>, formationName: String, unitId: String): Boolean {
        if (units.none { it.idNum == unitId && it.formationName == formationName }) return false
        for (u in units) {
            // #2 修复：旧中心被降级后回置为普通成员（isInFormation=true），
            // 否则其 isInFormation 与 isFormationCenter 均为 null → 成"孤岛"不再随编队移动
            if (u.formationName == formationName && u.isFormationCenter == true) {
                u.isFormationCenter = null
                u.isInFormation = true
            }
        }
        val target = units.first { it.idNum == unitId }
        target.isFormationCenter = true
        target.isInFormation = null
        return true
    }

    /**
     * 添加成员：设置队形标志 + 默认方位/距离（0° 正前方、1 海里，桌面版 AddUnit 落位默认值）。
     * 已有方位/距离值保留（单位从旧编队转来时不清空）。
     */
    fun addMember(u: Unit, formationName: String, type: String = FormationTypes.COLUMN) {
        u.formationName = formationName
        u.formationType = type
        u.isFormationCenter = null
        u.isInFormation = true
        if (u.formationBearing == null) u.formationBearing = 0
        if (u.formationDistance == null) u.formationDistance = 100000  // 1 海里（文件单位 海里×100000）
    }

    /** 移除成员：清全部队形字段（桌面版 RemoveUnitFromFormation） */
    fun removeMember(u: Unit) {
        removeFromFormation(u)
        u.formationType = null
        u.formationBearing = null
        u.formationDistance = null
    }

    /** 队形类型：以任一同编队成员 formationType 为准（规格缺失时回退），无则 null */
    fun typeOf(units: List<Unit>, formationName: String): String? =
        units.firstOrNull { it.formationName == formationName && !it.formationType.isNullOrBlank() }?.formationType

    /** 修改队形类型：规格 + 全部成员 formationType 同步。@return 更新成员数 */
    fun setType(units: List<Unit>, specs: MutableMap<String, FormationSpec>, name: String, type: String): Int {
        var count = 0
        for (u in units) {
            if (u.formationName == name && u.formationType != type) {
                u.formationType = type
                count++
            }
        }
        specs[name]?.let { specs[name] = it.copy(type = type) }
        return count
    }

    /** 修改距离单位（仅规格，供显示层换算） */
    fun setDistanceUnit(specs: MutableMap<String, FormationSpec>, name: String, unit: String) {
        specs[name]?.let { specs[name] = it.copy(distanceUnit = unit) }
    }

    /** 成员罗盘方位（度，0=北 顺时针；formationBearing 为 ×1000 定点） */
    fun bearingDeg(u: Unit): Double? = u.formationBearing?.div(1000.0)

    /** 成员距离换算为指定单位显示值（文件单位 = 海里×100000；1 海里 = 2025.37 码 = 1852 米） */
    fun distanceValue(u: Unit, unit: String = FormationDistanceUnits.NMI): Double? {
        val fileUnit = u.formationDistance ?: return null
        val nmi = fileUnit / 100000.0
        return when (unit) {
            FormationDistanceUnits.NMI -> nmi
            FormationDistanceUnits.YARDS -> nmi * 2025.37
            FormationDistanceUnits.METERS -> nmi * 1852.0
            else -> nmi
        }
    }
}
