package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.ui.pasteUnitInto
import com.simplot.android.ui.relocateUnitInto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G29 剪贴板粘贴 + G32 Relocate 核心纯逻辑测试：
 * - pasteUnitInto：深拷贝、防撞号（IdNum 走 nextIdFor / TrackNumber 走 TrackCounter 分侧计数器）、
 *   航路点随位移平移、红方走红方计数器
 * - relocateUnitInto：单位移动 + 历史/未来航路点同步平移（桌面 CanvasMap_MouseDrag → RecalcWaypoints）
 */
class UnitPasteRelocateTest {

    private fun baseFile(): ScenarioFile = ScenarioFile(
        units = mutableListOf(
            Unit(idNum = "S001", side = "Blue", trackNumber = 2401, name = "Hood", x = 100, y = 200)
        )
    )

    // ============ G29：Paste 防撞号 ============

    @Test
    fun `paste allocates non-colliding idNum and trackNumber`() {
        val f = baseFile()
        val clip = Unit(idNum = "S001", side = "Blue", trackNumber = 2401, name = "Hood", x = 100, y = 200)
        val pasted = pasteUnitInto(f, clip, 500, 600)

        assertEquals(2, f.units.size)
        // 防撞号：IdNum 不与现有冲突、TrackNumber 不与现有冲突
        assertEquals("S002", pasted.idNum)
        assertEquals(2402, pasted.trackNumber)
        assertNotEquals("IdNum 不得与源单位冲突", clip.idNum, pasted.idNum)
        assertNotEquals("TrackNumber 不得与源单位冲突", clip.trackNumber, pasted.trackNumber)
        // 位置 = 粘贴点
        assertEquals(500L, pasted.x)
        assertEquals(600L, pasted.y)
        // 深拷贝：修改粘贴单位不影响剪贴板/源单位
        pasted.name = "changed"
        assertEquals("Hood", clip.name)
    }

    @Test
    fun `paste shifts waypoints by the position delta`() {
        val f = baseFile()
        val clip = Unit(idNum = "S001", side = "Blue", trackNumber = 2401, name = "Hood", x = 100, y = 200,
            futureWaypointArray = mutableListOf(Waypoint(x = 1000, y = 2000), Waypoint(x = 3000, y = 4000)),
            pastWaypointArray = mutableListOf(Waypoint(x = -500, y = -600))
        )
        val pasted = pasteUnitInto(f, clip, 300, 100)   // dx=200, dy=-100

        assertEquals(listOf(1200L, 3200L), pasted.futureWaypointArray.map { it.x })
        assertEquals(listOf(1900L, 3900L), pasted.futureWaypointArray.map { it.y })
        assertEquals(-300L, pasted.pastWaypointArray[0].x)
        assertEquals(-700L, pasted.pastWaypointArray[0].y)
        // 剪贴板原航路点不被污染（深拷贝）
        assertEquals(1000L, clip.futureWaypointArray[0].x)
    }

    @Test
    fun `paste red unit uses red track counter and keeps counters separate`() {
        val f = baseFile()
        val clip = Unit(idNum = "S001", side = "Red", trackNumber = 9000, name = "Bismarck", x = 0, y = 0)
        val pasted = pasteUnitInto(f, clip, 50, 50)

        assertEquals("S002", pasted.idNum)          // 蓝方 S001 不冲突
        assertEquals(9001, pasted.trackNumber)      // 红方走红方计数器（9000 + 1）
        assertEquals(9001, f.scenario.currentPlayerTrackNumber)
        assertEquals(2400, f.scenario.currentTrackNumber)   // 蓝方计数器不受影响
    }

    @Test
    fun `paste writes back lastId counter`() {
        val f = baseFile()
        val clip = Unit(idNum = "S001", side = "Blue", trackNumber = 2401, name = "Hood", x = 100, y = 200)
        val pasted = pasteUnitInto(f, clip, 500, 600)
        assertEquals(2, f.scenario.lastId)   // 计数器写回：桌面 GetIdNumber 依赖存档计数器
        assertEquals("S002", pasted.idNum)
    }

    @Test
    fun `repeated paste produces sequential ids and track numbers`() {
        val f = baseFile()
        val clip = Unit(idNum = "S001", side = "Blue", trackNumber = 2401, name = "Hood", x = 100, y = 200)
        val p1 = pasteUnitInto(f, clip, 300, 300)
        val p2 = pasteUnitInto(f, clip, 400, 400)
        val p3 = pasteUnitInto(f, clip, 500, 500)

        assertEquals(listOf("S002", "S003", "S004"), listOf(p1.idNum, p2.idNum, p3.idNum))
        assertEquals(listOf(2402, 2403, 2404), listOf(p1.trackNumber, p2.trackNumber, p3.trackNumber))
        // 全程无撞号
        val ids = f.units.map { it.idNum }
        val tns = f.units.map { it.trackNumber }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(tns.size, tns.toSet().size)
    }

    // ============ G32：Relocate 航路点平移 ============

    @Test
    fun `relocate moves unit and shifts both waypoint arrays`() {
        val f = baseFile()
        f.units[0].pastWaypointArray = mutableListOf(Waypoint(x = 0, y = 0))
        f.units[0].futureWaypointArray = mutableListOf(Waypoint(x = 1000, y = 2000), Waypoint(x = 2000, y = 3000))

        val ok = relocateUnitInto(f, "S001", 300, 250)   // dx=200, dy=50

        assertTrue(ok)
        assertEquals(300L, f.units[0].x)
        assertEquals(250L, f.units[0].y)
        assertEquals(200L, f.units[0].pastWaypointArray[0].x)
        assertEquals(50L, f.units[0].pastWaypointArray[0].y)
        assertEquals(listOf(1200L, 2200L), f.units[0].futureWaypointArray.map { it.x })
        assertEquals(listOf(2050L, 3050L), f.units[0].futureWaypointArray.map { it.y })
    }

    @Test
    fun `relocate missing unit returns false`() {
        val f = baseFile()
        assertFalse(relocateUnitInto(f, "S999", 1, 1))
        assertEquals(100L, f.units[0].x)   // 场景不变
    }

    @Test
    fun `relocate to same position keeps waypoints`() {
        val f = baseFile()
        f.units[0].futureWaypointArray = mutableListOf(Waypoint(x = 1000, y = 2000))
        assertTrue(relocateUnitInto(f, "S001", 100, 200))
        assertEquals(1000L, f.units[0].futureWaypointArray[0].x)
        assertEquals(2000L, f.units[0].futureWaypointArray[0].y)
    }
}
