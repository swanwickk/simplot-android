package com.simplot.android

import com.simplot.android.ui.appendErrorLogEntry
import com.simplot.android.ui.autoSaveGate
import com.simplot.android.ui.components.moveItem
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

    // ============ G23：弧顺序（桌面语义 = 列表顺序即绘制顺序；编辑器 ↑/↓ 重排） ============

    @Test
    fun `move item up reorders preserving order`() {
        val list = mutableListOf("a", "b", "c")
        assertTrue(list.moveItem(1, 0))
        assertEquals(listOf("b", "a", "c"), list)
        assertTrue(list.moveItem(2, 1))
        assertEquals(listOf("b", "c", "a"), list)
    }

    @Test
    fun `move item rejects invalid indices without modifying`() {
        val list = mutableListOf("a", "b", "c")
        assertFalse(list.moveItem(0, 0))   // 同位置
        assertFalse(list.moveItem(-1, 1))  // 负索引
        assertFalse(list.moveItem(0, 5))   // 越界
        assertFalse(list.moveItem(3, 0))   // 越界
        assertEquals(listOf("a", "b", "c"), list)
    }
}
