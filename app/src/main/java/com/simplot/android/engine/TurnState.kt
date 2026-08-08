package com.simplot.android.engine

import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Turn
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.data.util.TimeUtil

/**
 * 回合状态机（对应桌面版 Do/Undo/Next 机制）
 *
 * | 操作 | TurnTime | PositionTime | Phase | 说明 |
 * | Do(移动) | 不变 | 推进 | 2 | 移动已发生，可 Undo |
 * | Undo | 回退 | 回退 | 0 | 撤销移动 |
 * | Next(确认) | 追上Position | 不变 | 0 | 回合确认，Turns 追加 |
 *
 * 状态识别：
 * - TurnTime==PositionTime 且无轨迹点 → do_before（初始）
 * - TurnTime != PositionTime → do_after（Do 后）
 * - TurnTime==PositionTime 且有轨迹点 → do_next（已确认）
 */
object TurnState {
    const val PHASE_PLOTTING = 0
    const val PHASE_POST_MOVEMENT = 2

    enum class State { DO_BEFORE, DO_AFTER, DO_NEXT }

    fun detect(file: ScenarioFile): State {
        val tt = file.time.currentTurnTime
        val pt = file.time.currentPositionTime
        val hasWp = file.units.any { it.pastWaypointArray.isNotEmpty() }
        return when {
            tt == pt -> if (hasWp) State.DO_NEXT else State.DO_BEFORE
            else -> State.DO_AFTER
        }
    }

    fun label(state: State): String = when (state) {
        State.DO_BEFORE -> "规划中（Do 前）"
        State.DO_AFTER -> "已移动（Do 后）"
        State.DO_NEXT -> "回合已确认"
    }

    /** 是否为"推进后"状态（Do 后 / Next 后），决定时间推进方式
     *  @param stateBefore 移动前的状态（必须在使用轨迹点判断前捕获） */
    fun advanceTime(file: ScenarioFile, interval: TurnInterval, stateBefore: State = detect(file)) {
        val minutes = interval.totalMinutes()
        val newPt = TimeUtil.advance(file.time.currentPositionTime, minutes)

        if (stateBefore == State.DO_NEXT) {
            // Next 后状态：TurnTime 同步推进，Turns 追加，Phase=0
            file.time.currentTurnTime = newPt
            file.time.currentPositionTime = newPt
            val exists = file.turns.any { it.turnTime == newPt }
            if (!exists) {
                file.turns.add(Turn(turnTime = newPt, turnInterval = TurnInterval(interval.minutes, interval.seconds)))
            }
            file.scenario.phase = PHASE_PLOTTING
        } else {
            // Do 前 / Do 后 状态：TurnTime 不变，PositionTime 推进，Phase=2（可 Undo）
            file.time.currentPositionTime = newPt
            file.scenario.phase = PHASE_POST_MOVEMENT
        }
        file.time.currentTurnInterval = TurnInterval(interval.minutes, interval.seconds)
    }

    /**
     * Next：确认当前回合。TurnTime 追上 PositionTime，Turns 追加，Phase 回 0。
     * （桌面版行为：Do 后按 Next 确认回合，不产生新移动）
     */
    fun confirmNext(file: ScenarioFile, interval: TurnInterval) {
        val pt = file.time.currentPositionTime
        file.time.currentTurnTime = pt
        val exists = file.turns.any { it.turnTime == pt }
        if (!exists) {
            file.turns.add(Turn(turnTime = pt, turnInterval = TurnInterval(interval.minutes, interval.seconds)))
        }
        file.scenario.phase = PHASE_PLOTTING
        file.time.currentTurnInterval = TurnInterval(interval.minutes, interval.seconds)
    }

    /** Undo：将 PositionTime 回退一个回合时长，Phase 回 0 */
    fun undo(file: ScenarioFile, interval: TurnInterval) {
        val minutes = interval.totalMinutes()
        val back = TimeUtil.advance(file.time.currentPositionTime, -minutes)
        file.time.currentPositionTime = back
        file.scenario.phase = PHASE_PLOTTING
    }
}
