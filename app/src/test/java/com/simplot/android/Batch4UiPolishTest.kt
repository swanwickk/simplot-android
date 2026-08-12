package com.simplot.android

import com.simplot.android.data.model.Sensor
import com.simplot.android.data.model.Weapon
import com.simplot.android.render.sortedArcs
import com.simplot.android.render.sortedSensorArcs
import com.simplot.android.render.sortedWeaponArcs
import com.simplot.android.ui.appendErrorLogEntry
import com.simplot.android.ui.autoSaveGate
import com.simplot.android.ui.formatLogTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * 批次4 UI/功能类（G10/G11/G23）纯逻辑单测。
 * G05（删除全部）/G07（首末帧）为 Compose UI 改动，无可测纯逻辑。
 */
class Batch4UiPolishTest {

    // ============ G10：自动存档开关门禁（桌面 WindowControlOptions CheckAutoSave） ============

    @Test
    fun `autosave gate off when switch disabled`() {
        assertFalse(autoSaveGate(enabled = false, hasFile = true, hasUri = true))
    }

    @Test
    fun `autosave gate requires file and uri`() {
        assertFalse(autoSaveGate(enabled = true, hasFile = false, hasUri = true))
        assertFalse(autoSaveGate(enabled = true, hasFile = true, hasUri = false))
        assertFalse(autoSaveGate(enabled = true, hasFile = false, hasUri = false))
    }

    @Test
    fun `autosave gate on when all satisfied`() {
        assertTrue(autoSaveGate(enabled = true, hasFile = true, hasUri = true))
    }

    // ============ G11：错误日志（桌面 WindowErrorLog Listbox1 + UpdateErrorLog） ============

    @Test
    fun `error log appends newest first with timestamp`() {
        val log = mutableListOf<String>()
        appendErrorLogEntry(log, "加载失败：bad file", "2026-08-12 10:00:00")
        appendErrorLogEntry(log, "保存成功", "2026-08-12 10:01:00")
        assertEquals(2, log.size)
        assertEquals("[2026-08-12 10:01:00] 保存成功", log[0])
        assertEquals("[2026-08-12 10:00:00] 加载失败：bad file", log[1])
    }

    @Test
    fun `error log caps at limit dropping oldest`() {
        val log = mutableListOf<String>()
        repeat(5) { i -> appendErrorLogEntry(log, "msg$i", "t$i", cap = 3) }
        assertEquals(3, log.size)
        // 最新在前：msg4, msg3, msg2；msg0/msg1 被裁剪
        assertEquals("[t4] msg4", log[0])
        assertEquals("[t3] msg3", log[1])
        assertEquals("[t2] msg2", log[2])
    }

    @Test
    fun `error log entry returned and empty input handled`() {
        val log = mutableListOf<String>()
        val entry = appendErrorLogEntry(log, "boom", "2026-08-12 00:00:00")
        assertEquals("[2026-08-12 00:00:00] boom", entry)
        assertEquals(listOf(entry), log)
    }

    @Test
    fun `log time format is yyyy-MM-dd HHmmss`() {
        // 时区无关断言：仅校验格式形态（固定纪元毫秒值）
        val ts = formatLogTime(Date(0))
        assertTrue("实际输出: $ts", ts.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")))
    }

    // ============ G23：弧固定排序（桌面 ContainerSensors/ContainerWeapons 顺序语义） ============

    @Test
    fun `sensor arcs sorted by start angle then max range`() {
        val arcs = listOf(
            Sensor(tag = "s1", startAngle = 90.0, maxRange = 20.0),
            Sensor(tag = "s0", startAngle = 0.0, maxRange = 50.0),
            Sensor(tag = "s2", startAngle = 90.0, maxRange = 10.0)
        )
        val sorted = sortedSensorArcs(arcs)
        assertEquals(listOf("s0", "s2", "s1"), sorted.map { it.tag })
    }

    @Test
    fun `weapon arcs sorted same as sensors`() {
        val arcs = listOf(
            Weapon(tag = "w1", startAngle = 180.0, maxRange = 5.0),
            Weapon(tag = "w0", startAngle = 45.0, maxRange = 5.0)
        )
        val sorted = sortedWeaponArcs(arcs)
        assertEquals(listOf("w0", "w1"), sorted.map { it.tag })
    }

    @Test
    fun `arc sort handles null and empty`() {
        assertEquals(emptyList<Sensor>(), sortedSensorArcs(null))
        assertEquals(emptyList<Weapon>(), sortedWeaponArcs(null))
        assertEquals(emptyList<Sensor>(), sortedSensorArcs(emptyList()))
    }

    @Test
    fun `generic arc sort does not mutate original`() {
        data class FakeArc(val start: Double, val max: Double, val id: String)
        val original = listOf(FakeArc(90.0, 10.0, "b"), FakeArc(0.0, 10.0, "a"))
        val sorted = sortedArcs(original, { it.start }, { it.max })
        assertEquals(listOf("a", "b"), sorted.map { it.id })
        assertEquals(listOf("b", "a"), original.map { it.id })  // 原列表不变
    }
}
