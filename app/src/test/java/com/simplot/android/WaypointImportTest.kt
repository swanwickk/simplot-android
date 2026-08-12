package com.simplot.android

import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.data.model.WaypointImportMode
import com.simplot.android.data.model.WaypointImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G04 航路点导入核心逻辑测试（桌面版 WindowImportWaypoints → CopyExactWaypoints / CopyOffsetWaypoints）：
 * - 精确复制：位置/速度/航向/高度深度等全字段深拷贝，绝对坐标原样
 * - 偏移复制：叠加「目标单位位置 − 源单位位置」平移量（保留相对位置关系）
 * - 源单位数据不被污染（深拷贝）；编号按追加顺序重新排
 * - sourceCandidates：仅含航路点的其他单位
 */
class WaypointImportTest {

    private fun sourceUnit(): Unit = Unit(
        idNum = "S001", name = "Hood", x = 100, y = 200,
        futureWaypointArray = mutableListOf(
            Waypoint(name = "A", x = 1000, y = 2000, speed = 15000, course = 45000,
                altitudeDepth = 3000000, assignedAltDepth = 3000000,
                ascent = 10, descent = 20, number = 1, isTurnTime = true,
                positionTime = "1942-10-01 00:00:00"),
            Waypoint(name = "B", x = 3000, y = 4000, speed = 12000, course = 90000,
                number = 2, isTurnTime = false, positionTime = "1942-10-02 00:00:00")
        )
    )

    private fun targetUnit(): Unit = Unit(idNum = "S002", name = "Repulse", x = 500, y = 600)

    // ============ 精确复制 CopyExactWaypoints ============

    @Test
    fun `exact copy keeps absolute coordinates and all fields`() {
        val src = sourceUnit()
        val dst = targetUnit()
        val out = WaypointImporter.copyFrom(src, dst, WaypointImportMode.EXACT)

        assertEquals(2, out.size)
        // 绝对坐标原样（精确复制）
        assertEquals(listOf(1000L, 3000L), out.map { it.x })
        assertEquals(listOf(2000L, 4000L), out.map { it.y })
        // 速度/航向/高度深度/爬升下降/时间全字段保留
        val a = out[0]
        assertEquals("A", a.name)
        assertEquals(15000, a.speed)
        assertEquals(45000, a.course)
        assertEquals(3000000, a.altitudeDepth)
        assertEquals(3000000, a.assignedAltDepth)
        assertEquals(10, a.ascent)
        assertEquals(20, a.descent)
        assertEquals(true, a.isTurnTime)
        assertEquals("1942-10-01 00:00:00", a.positionTime)
        val b = out[1]
        assertEquals("B", b.name)
        assertEquals(false, b.isTurnTime)
        assertEquals("1942-10-02 00:00:00", b.positionTime)
    }

    @Test
    fun `exact copy is deep copy and does not mutate source`() {
        val src = sourceUnit()
        val out = WaypointImporter.copyFrom(src, targetUnit(), WaypointImportMode.EXACT)
        out[0].x = 999999
        out[0].name = "mutated"
        // 源单位不被污染
        assertEquals(1000L, src.futureWaypointArray[0].x)
        assertEquals("A", src.futureWaypointArray[0].name)
    }

    @Test
    fun `numbers continue from startNumber like desktop Add count+1`() {
        val src = sourceUnit()
        // 目标已有 1 个航路点 → 追加的编号从 2 开始
        val out = WaypointImporter.copyFrom(src, targetUnit(), WaypointImportMode.EXACT, startNumber = 2)
        assertEquals(listOf(2, 3), out.map { it.number })
        // 默认 startNumber=1
        assertEquals(listOf(1, 2), WaypointImporter.copyFrom(src, targetUnit(), WaypointImportMode.EXACT).map { it.number })
    }

    // ============ 偏移复制 CopyOffsetWaypoints ============

    @Test
    fun `offset copy translates each waypoint by target minus source`() {
        val src = sourceUnit()          // 源位置 (100, 200)
        val dst = targetUnit()          // 目标位置 (500, 600) → 偏移 (400, 400)
        val out = WaypointImporter.copyFrom(src, dst, WaypointImportMode.OFFSET)

        assertEquals(listOf(1400L, 3400L), out.map { it.x })   // 1000+400, 3000+400
        assertEquals(listOf(2400L, 4400L), out.map { it.y })   // 2000+400, 4000+400
        // 相对位置关系保留：两点间距与源一致
        assertEquals(2000L, out[1].x - out[0].x)
        assertEquals(2000L, out[1].y - out[0].y)
    }

    @Test
    fun `offset copy supports negative delta`() {
        // 目标在源西南方向 → 负偏移
        val src = sourceUnit()                          // (100, 200)
        val dst = Unit(idNum = "S002", x = -100, y = -300)   // 偏移 (-200, -500)
        val out = WaypointImporter.copyFrom(src, dst, WaypointImportMode.OFFSET)
        assertEquals(listOf(800L, 2800L), out.map { it.x })
        assertEquals(listOf(1500L, 3500L), out.map { it.y })
    }

    @Test
    fun `offset copy still preserves speed course and altitude`() {
        val src = sourceUnit()
        val out = WaypointImporter.copyFrom(src, targetUnit(), WaypointImportMode.OFFSET)
        assertEquals(15000, out[0].speed)
        assertEquals(45000, out[0].course)
        assertEquals(3000000, out[0].assignedAltDepth)
    }

    // ============ 边界与候选 ============

    @Test
    fun `empty source waypoints produce empty result`() {
        val src = Unit(idNum = "S003", name = "Empty", x = 0, y = 0)
        assertTrue(WaypointImporter.copyFrom(src, targetUnit(), WaypointImportMode.EXACT).isEmpty())
        assertTrue(WaypointImporter.copyFrom(src, targetUnit(), WaypointImportMode.OFFSET).isEmpty())
    }

    @Test
    fun `source candidates list units with waypoints excluding self`() {
        val self = Unit(idNum = "S001", name = "Hood", futureWaypointArray = mutableListOf(Waypoint(x = 1, y = 1)))
        val withWp = Unit(idNum = "S002", name = "Repulse", futureWaypointArray = mutableListOf(Waypoint(x = 2, y = 2)))
        val noWp = Unit(idNum = "S003", name = "Empty")
        val otherWithWp = Unit(idNum = "S004", name = "Duke", futureWaypointArray = mutableListOf(Waypoint(x = 3, y = 3)))

        val candidates = WaypointImporter.sourceCandidates(listOf(self, withWp, noWp, otherWithWp), self)
        assertEquals(listOf("S002", "S004"), candidates.map { it.idNum })
        // 空航路点单位与自身均不在候选
        assertNotEquals("S003", candidates.map { it.idNum }.joinToString())
    }

    @Test
    fun `self unit with waypoints is excluded from candidates`() {
        val self = Unit(idNum = "S001", name = "Hood", futureWaypointArray = mutableListOf(Waypoint(x = 1, y = 1)))
        val candidates = WaypointImporter.sourceCandidates(listOf(self), self)
        assertTrue(candidates.isEmpty())
    }
}
