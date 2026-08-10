package com.simplot.android.domain.usecase

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.domain.engine.CalcEngine
import com.simplot.android.engine.MovementEngine
import com.simplot.android.engine.TurnState

/**
 * 回合推进 UseCase（文档 §4.1 AdvanceTurnUseCase）。
 *
 * 把「Do 回合」的业务编排从 ViewModel 抽出为纯领域服务：
 * 1. 状态门禁（canDo）
 * 2. MovementEngine.advance 移动
 * 3. Range 耗尽单位检测（返回给 UI 弹窗）
 * 4. 最终航路点到达单位检测
 *
 * 纯 Kotlin 无 Android 依赖 → JVM 单测。
 */
object AdvanceTurnUseCase {

    data class Result(
        val stateBefore: TurnState.State,
        val rangeExhausted: List<String>,   // 耗尽单位 IdNum
        val finalWaypointReached: List<String>,  // 到达最终航路点单位 IdNum
        val newPositionTime: String
    )

    /**
     * 执行 Do。
     * @param file 场景（就地修改）
     * @param interval 回合时长
     * @return null = 门禁拦截（不可 Do）
     */
    fun execute(file: ScenarioFile, interval: TurnInterval): Result? {
        val stateBefore = TurnState.detect(file)
        if (!TurnState.canDo(stateBefore)) return null

        MovementEngine.advance(file, interval)

        val rangeExhausted = file.units
            .filter { it.range == 0 && !it.showSunk && !it.ignoreRange }
            .map { it.idNum }
        val finalWpReached = file.units
            .filter { it.futureWaypointArray.isEmpty() && it.pastWaypointArray.isNotEmpty() }
            .map { it.idNum }

        return Result(
            stateBefore = stateBefore,
            rangeExhausted = rangeExhausted,
            finalWaypointReached = finalWpReached,
            newPositionTime = file.time.currentPositionTime
        )
    }

    /** Undo（带门禁）；返回是否执行 */
    fun undo(file: ScenarioFile, interval: TurnInterval): Boolean {
        if (!TurnState.canUndo(TurnState.detect(file))) return false
        TurnState.undo(file, interval)
        return true
    }

    /** Next 确认（带门禁）；返回是否执行 */
    fun next(file: ScenarioFile, interval: TurnInterval): Boolean {
        if (!TurnState.canNext(TurnState.detect(file))) return false
        TurnState.confirmNext(file, interval)
        return true
    }

    /** 单位是否到达最终航路点（供弹窗/测试） */
    fun hasReachedFinalWaypoint(file: ScenarioFile, idNum: String): Boolean {
        val u = file.units.firstOrNull { it.idNum == idNum } ?: return false
        return u.futureWaypointArray.isEmpty() && u.pastWaypointArray.isNotEmpty()
    }
}
