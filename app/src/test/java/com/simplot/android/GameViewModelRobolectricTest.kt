package com.simplot.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.simplot.android.data.model.Scenario
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TimeState
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.ui.GameViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GameViewModel 核心编排的 Robolectric 本地单元测试（P3-5：UI/VM 层零自动化测试补强）。
 *
 * 覆盖历史 UI 层漏网缺陷的代表路径：
 * - 场景切换（applyLoaded）临时状态彻底清理（0.7.1 修复回归）
 * - G40 最终航路点弹窗仅本回合消费最后一个未来航路点的单位触发（P1-1 修复回归）
 * - 护航队以视野中心为原点（#4 修复回归）
 * - 新场景创建状态清理（G01）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameViewModelRobolectricTest {

    private fun scenario(
        units: List<Unit> = mutableListOf(),
        startTime: String = "2026-01-01 00:00:00"
    ): ScenarioFile = ScenarioFile(
        file = "Referee",
        scenario = Scenario(scenarioName = "测试场景", lastId = 0, currentTrackNumber = 2400, currentPlayerTrackNumber = 9000),
        time = TimeState(
            currentTurnTime = startTime,
            currentPositionTime = startTime,
            currentTurnInterval = TurnInterval(3, 0)
        ),
        units = units.toMutableList()
    )

    /** 水面单位辅助（Speed/Course ×1000；x/y 文件单位） */
    private fun surfaceUnit(id: String, speedKnots: Double, courseDeg: Double, x: Long = 0, y: Long = 0): Unit =
        Unit(idNum = id, side = "Blue", name = id, unitClass = "DD", x = x, y = y)
            .apply { setSpeed(speedKnots); setCourse(courseDeg) }

    // ============ 0.7.1：场景切换临时状态彻底清理 ============

    @Test
    fun `applyLoaded clears all transient interaction state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(app)
        vm.applyLoaded(scenario())
        // 制造跨场景残留状态
        vm.selectedUnitId = "S001"
        vm.measureMode = true
        // 加载新场景 → 全部清理（clipboardUnit/finalWaypointUnit 为 private set，读断言即可）
        vm.applyLoaded(scenario())
        assertNull("选中单位应清空", vm.selectedUnitId)
        assertFalse("测量模式应退出", vm.measureMode)
        assertNull("剪贴板应清空", vm.clipboardUnit)
        assertNull("最终航路点弹窗应清空", vm.finalWaypointUnit)
        assertEquals("新场景单位数为 0", 0, vm.file?.units?.size)
    }

    // ============ P1-1：G40 精确标记链路（VM 层） ============

    @Test
    fun `doTurn pops final waypoint dialog only for unit consuming last waypoint`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(app)
        // 两个单位：S001 本回合到达并消费唯一未来航路点；S002 从未设航线（历史有轨迹、无未来航路点）
        val u1 = surfaceUnit("S001", 24.0, 0.0).apply {
            futureWaypointArray.add(
                Waypoint(x = 0, y = 60000, number = 1, isTurnTime = true, positionTime = "2026-01-01 00:00:00")
            )
        }
        val u2 = surfaceUnit("S002", 12.0, 0.0, y = 100000).apply {
            // 历史有轨迹、当前无未来航路点（从未设航线）
            pastWaypointArray.add(
                Waypoint(x = 0, y = 100000, number = 1, isTurnTime = true, positionTime = "2026-01-01 00:00:00")
            )
        }
        vm.applyLoaded(scenario(listOf(u1, u2)))
        vm.doTurn()
        // S001 消费了最后一个未来航路点 → 弹窗应指向 S001
        assertNotNull("本回合消费最后一个航路点应弹窗", vm.finalWaypointUnit)
        assertEquals("弹窗单位应为 S001", "S001", vm.finalWaypointUnit?.idNum)
    }

    @Test
    fun `doTurn does not pop final waypoint dialog for unit never had waypoints`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(app)
        // 只有从未设航线的静止单位（历史有轨迹、当前无未来航路点）
        val u = surfaceUnit("S001", 0.0, 0.0).apply {
            pastWaypointArray.add(
                Waypoint(x = 0, y = 0, number = 1, isTurnTime = true, positionTime = "2026-01-01 00:00:00")
            )
        }
        vm.applyLoaded(scenario(listOf(u)))
        vm.doTurn()
        // 修复前（过宽条件）此用例会误弹 S001；修复后不应弹
        assertNull("从未设航线单位不应误弹最终航路点", vm.finalWaypointUnit)
    }

    // ============ #4：护航队以视野中心为原点 ============

    @Test
    fun `convoy centers on camera view center`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(app)
        vm.applyLoaded(scenario())
        // 移动视野中心到非原点
        vm.camera.centerOn(500000L, -300000L)
        vm.createConvoy()
        val commodore = vm.file?.units?.firstOrNull { it.isFormationCenter == true }
        assertNotNull("护航队指挥舰应存在", commodore)
        assertEquals("指挥舰 X 应为视野中心", 500000L, commodore!!.x)
        assertEquals("指挥舰 Y 应为视野中心", -300000L, commodore.y)
        // 商船应环绕指挥舰（数量 = 默认 6）
        val merchants = vm.file?.units?.filter { it.name.startsWith("Merchant") } ?: emptyList()
        assertEquals("默认 6 艘商船", 6, merchants.size)
    }

    // ============ G01：新场景创建 ============

    @Test
    fun `createNewScenario builds fresh scenario and clears state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(app)
        vm.applyLoaded(scenario(listOf(surfaceUnit("S001", 12.0, 0.0))))
        vm.selectedUnitId = "S001"
        vm.createNewScenario("新剧本", "2026-08-21 00:00:00", null)
        assertNotNull(vm.file)
        assertEquals("新场景名", "新剧本", vm.file?.scenario?.scenarioName)
        assertEquals("新场景应无单位", 0, vm.file?.units?.size)
        assertNull("新场景应无选中单位", vm.selectedUnitId)
        assertNull("新场景应无当前 URI", vm.currentUri)
        assertEquals("TypeOfMap 应为 0（无地图）", 0, vm.file?.scenario?.typeOfMap)
    }

    @Test
    fun `createNewScenario with map sets type of map and filename`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(app)
        vm.createNewScenario("带地图", "2026-08-21 00:00:00", "ironbottom.json")
        assertEquals(1, vm.file?.scenario?.typeOfMap)
        assertEquals("ironbottom.json", vm.file?.scenario?.mapFileName)
    }

    @Test
    fun `invalid start time rejected`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(app)
        val before = vm.file
        vm.createNewScenario("坏时间", "not-a-date", null)
        // 校验失败：file 不被替换
        assertEquals(before, vm.file)
    }
}
