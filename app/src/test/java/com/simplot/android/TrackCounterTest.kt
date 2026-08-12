package com.simplot.android

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.domain.engine.TrackCounter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TrackNumber 计数器测试（桌面版 GetTrackNumber / GetPlayerTrackNumber：蓝/红各一套）。
 * P0/N2：新建单位、复制单位、护航队三条创建路径共用本分配器，
 * 写回场景计数器（currentTrackNumber / currentPlayerTrackNumber），删除后不回退。
 */
class TrackCounterTest {

    @Test
    fun `empty scenario allocates from 2401`() {
        val f = ScenarioFile()
        assertEquals(2401, TrackCounter.allocate(f, "Blue"))
        assertEquals(2401, f.scenario.currentTrackNumber)
    }

    @Test
    fun `blue allocation takes max of counter and existing units`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", trackNumber = 2450)))
        f.scenario.currentTrackNumber = 2400
        assertEquals(2451, TrackCounter.allocate(f, "Blue"))
        assertEquals(2451, f.scenario.currentTrackNumber)
    }

    @Test
    fun `red uses currentPlayerTrackNumber and keeps counters separate`() {
        val f = ScenarioFile()
        assertEquals(9001, TrackCounter.allocate(f, "Red"))
        assertEquals(9001, f.scenario.currentPlayerTrackNumber)
        // 蓝方分配不触碰红方计数器，反之亦然
        assertEquals(2401, TrackCounter.allocate(f, "Blue"))
        assertEquals(2401, f.scenario.currentTrackNumber)
        assertEquals(9001, f.scenario.currentPlayerTrackNumber)
    }

    @Test
    fun `red allocation takes max of player counter and existing units`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", side = "Red", trackNumber = 9100)))
        f.scenario.currentPlayerTrackNumber = 9000
        assertEquals(9101, TrackCounter.allocate(f, "Red"))
        assertEquals(9101, f.scenario.currentPlayerTrackNumber)
        // 红方分配只写红方计数器
        assertEquals(2400, f.scenario.currentTrackNumber)
    }

    // ============ G63/N3：分侧语义（蓝/红各一套计数器，互不顶高） ============

    @Test
    fun `blue allocation ignores red units high track numbers`() {
        // 红方单位大号段（9500）不得顶高蓝方计数器（G63 缺陷场景）
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", side = "Red", trackNumber = 9500)))
        assertEquals(2401, TrackCounter.allocate(f, "Blue"))
        assertEquals(2401, f.scenario.currentTrackNumber)
        assertEquals(9000, f.scenario.currentPlayerTrackNumber)   // 红方计数器不受影响
    }

    @Test
    fun `red allocation ignores blue units high track numbers`() {
        // 蓝方单位大号段（9100）不得顶高红方计数器
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", side = "Blue", trackNumber = 9100)))
        assertEquals(9001, TrackCounter.allocate(f, "Red"))
        assertEquals(9001, f.scenario.currentPlayerTrackNumber)
        assertEquals(2400, f.scenario.currentTrackNumber)
    }

    @Test
    fun `blue allocation considers same side units only`() {
        // 蓝方单位 2450 + 红方单位 9500 → 蓝方取 2451（非全局 max）
        val f = ScenarioFile(units = mutableListOf(
            Unit(idNum = "S001", side = "Blue", trackNumber = 2450),
            Unit(idNum = "S002", side = "Red", trackNumber = 9500)
        ))
        assertEquals(2451, TrackCounter.allocate(f, "Blue"))
        assertEquals(2451, f.scenario.currentTrackNumber)
    }

    @Test
    fun `red allocation considers same side units only`() {
        val f = ScenarioFile(units = mutableListOf(
            Unit(idNum = "S001", side = "Red", trackNumber = 9100),
            Unit(idNum = "S002", side = "Blue", trackNumber = 2450)
        ))
        assertEquals(9101, TrackCounter.allocate(f, "Red"))
        assertEquals(9101, f.scenario.currentPlayerTrackNumber)
    }

    @Test
    fun `neutral units share blue counter`() {
        // 桌面 CUnit 构造默认 Neutral 走 GetTrackNumber（蓝方计数器）
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", side = "Neutral", trackNumber = 2500)))
        assertEquals(2501, TrackCounter.allocate(f, "Neutral"))
        assertEquals(2501, f.scenario.currentTrackNumber)
    }

    @Test
    fun `unknown side shares blue counter`() {
        // 非红阵营（Unknown 等）共用蓝方计数器（TrackCounter 注释语义）
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", side = "Unknown", trackNumber = 2500)))
        assertEquals(2501, TrackCounter.allocate(f, "Unknown"))
        assertEquals(2501, f.scenario.currentTrackNumber)
    }

    @Test
    fun `red allocation ignores neutral units high track numbers`() {
        // 非红单位（Neutral）大号段不得顶高红方计数器（G63 分侧语义反向）
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", side = "Neutral", trackNumber = 9500)))
        assertEquals(9001, TrackCounter.allocate(f, "Red"))
        assertEquals(9001, f.scenario.currentPlayerTrackNumber)
        assertEquals(2400, f.scenario.currentTrackNumber)
    }

    @Test
    fun `counter does not regress after unit deletion`() {
        val f = ScenarioFile(units = mutableListOf(Unit(idNum = "S001", trackNumber = 2450)))
        assertEquals(2451, TrackCounter.allocate(f, "Blue"))
        f.units.clear()
        // 删除单位后继续分配：不回退到现有最大（仍按计数器 +1）
        assertEquals(2452, TrackCounter.allocate(f, "Blue"))
        assertEquals(2452, f.scenario.currentTrackNumber)
    }

    @Test
    fun `repeated allocation produces sequential numbers`() {
        val f = ScenarioFile()
        val seq = (1..5).map { TrackCounter.allocate(f, "Blue") }
        assertEquals((2401..2405).toList(), seq)
        assertEquals(2405, f.scenario.currentTrackNumber)
    }
}
