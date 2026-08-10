package com.simplot.android.domain.registry

import com.simplot.android.data.model.Unit

/**
 * 单位类型注册表（文档 §2.2.2，对应桌面版 Fill*Types 运行时菜单数据源）。
 *
 * 桌面版单位体系（反汇编确认）：
 * - 大类 Domain 代码：A=Air U=Subsurface I=Installation B=Sonobuoy L=LandFormation
 *   R=ReferencePoint S=Surface V=Vehicle
 * - 每类下有子类型（UnitType）与类型简码（UnitClass）。
 *
 * Android 端用该注册表统一：
 * - 单位 → Domain 判定（优先 UnitType 字符串，回退 idNum 前缀）
 * - 类型菜单数据（编辑下拉 / 新建单位）
 * - 类型 → 默认符号/尺寸级关联
 */
object UnitTypeRegistry {

    /** 单位大类（桌面版 Domain） */
    enum class Domain(val code: String, val label: String) {
        SURFACE("S", "水面舰艇"),
        AIR("A", "飞机"),
        SUBSURFACE("U", "潜艇"),
        VEHICLE("V", "车辆"),
        INSTALLATION("I", "岸上设施"),
        LAND_FORMATION("L", "陆地编队"),
        REFERENCE_POINT("R", "参考点"),
        SONOBUOY("B", "声呐浮标"),
        UNKNOWN("", "未知")
    }

    /** 子类型表（桌面版 Fill*Types 全量，字符串来自二进制反汇编） */
    val AIR_TYPES: List<String> = listOf(
        "Aircraft", "Helicopter", "Missile", "AC Attack", "AC Bomber", "AC Transport",
        "AC Command Post", "AC Fighter", "AC SAR", "AC EW", "AC Tanker", "AC Patrol",
        "AC Recon", "AC ASW", "AC AEW", "AC Drone", "Helo ASW", "Helo Attack",
        "Helo Transport", "ComAir", "Missile AAM"
    )

    val SURFACE_TYPES: List<String> = listOf(
        "Carrier", "Surface Ship", "Battleship", "Cruiser", "Destroyer", "Frigate",
        "Patrol", "LHD", "Landing Craft", "Troop Ship", "Tanker/Oiler", "Support",
        "Auxiliary", "MCM", "Hospital", "Merchant", "Fishing"
    )

    val SUBSURFACE_TYPES: List<String> = listOf(
        "Torpedo", "Subsurface", "Submarine", "Sub Diesel", "Sub Diesel Attack", "Sub Diesel Missile",
        "Sub Diesel Ballistic Missile", "Sub Nuclear", "Sub Nuclear Attack",
        "Sub Nuclear Missile", "Sub Nuclear Ballistic Missile", "Mine", "Fish"
    )

    val INSTALLATION_TYPES: List<String> = listOf(
        "Airfield", "Installation", "Military", "Civilian", "Port", "Radar",
        "Communications", "SSM", "SAM", "EW"
    )

    val VEHICLE_TYPES: List<String> = listOf(
        "Vehicle", "Radar", "SSM", "SAM", "AAA"
    )

    val LAND_TYPES: List<String> = listOf(
        "Formation", "Infantry", "Armor"
    )

    val REFERENCE_TYPES: List<String> = listOf(
        "Reference Point", "Datum"
    )

    val SONOBUOY_TYPES: List<String> = listOf(
        "LOFAR", "DIFAR", "VLAD", "BT", "Sonobuoy", "CASS", "DICASS", "RO",
        "Ambient Noise", "ADAR"
    )

    /** 判定单位大类：优先 UnitType 字符串，回退 idNum 前缀（兼容旧场景） */
    fun domainOf(unit: Unit): Domain {
        val type = unit.unitType.lowercase()
        return when {
            type in AIR_TYPES.map { it.lowercase() } -> Domain.AIR
            type in SURFACE_TYPES.map { it.lowercase() } -> Domain.SURFACE
            type in SUBSURFACE_TYPES.map { it.lowercase() } -> Domain.SUBSURFACE
            type in INSTALLATION_TYPES.map { it.lowercase() } -> Domain.INSTALLATION
            type in VEHICLE_TYPES.map { it.lowercase() } -> Domain.VEHICLE
            type in LAND_TYPES.map { it.lowercase() } -> Domain.LAND_FORMATION
            type in REFERENCE_TYPES.map { it.lowercase() } -> Domain.REFERENCE_POINT
            type in SONOBUOY_TYPES.map { it.lowercase() } -> Domain.SONOBUOY
            unit.depth != null -> Domain.SUBSURFACE
            unit.altitude != null -> Domain.AIR
            else -> when (unit.idNum.firstOrNull()?.uppercaseChar()) {
                'A' -> Domain.AIR
                'U' -> Domain.SUBSURFACE
                'I' -> Domain.INSTALLATION
                'B' -> Domain.SONOBUOY
                'L' -> Domain.LAND_FORMATION
                'R' -> Domain.REFERENCE_POINT
                'V' -> Domain.VEHICLE
                else -> Domain.SURFACE
            }
        }
    }

    /** 该 Domain 的子类型菜单（新建/编辑用） */
    fun typesOf(domain: Domain): List<String> = when (domain) {
        Domain.AIR -> AIR_TYPES
        Domain.SURFACE -> SURFACE_TYPES
        Domain.SUBSURFACE -> SUBSURFACE_TYPES
        Domain.INSTALLATION -> INSTALLATION_TYPES
        Domain.VEHICLE -> VEHICLE_TYPES
        Domain.LAND_FORMATION -> LAND_TYPES
        Domain.REFERENCE_POINT -> REFERENCE_TYPES
        Domain.SONOBUOY -> SONOBUOY_TYPES
        Domain.UNKNOWN -> emptyList()
    }
}
