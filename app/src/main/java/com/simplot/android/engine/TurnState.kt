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

    /**
     * 时间字符串语义相等比较（E11 修复：统一走 [TimeUtil.equal] 解析比较，
     * 容忍跨格式存档差异；空白-空白保持相等，兼容空时间存档的原 == 门禁语义）。
     */
    private fun sameTime(a: String, b: String): Boolean =
        (a.isBlank() && b.isBlank()) || TimeUtil.equal(a, b)

    fun detect(file: ScenarioFile): State {
        val tt = file.time.currentTurnTime
        val pt = file.time.currentPositionTime
        // 主判据：Scenario.Phase（桌面版权威字段，advanceTime 置 2 / confirmNext·undo 置 0）
        if (file.scenario.phase == PHASE_POST_MOVEMENT) return State.DO_AFTER
        // 兜底（旧存档 phase 缺失/恒 0）：双时钟 + 轨迹推断
        val hasWp = file.units.any { it.pastWaypointArray.isNotEmpty() }
        return when {
            sameTime(tt, pt) -> if (hasWp) State.DO_NEXT else State.DO_BEFORE
            else -> State.DO_AFTER
        }
    }

    fun label(state: State): String = when (state) {
        State.DO_BEFORE -> "规划中（Do 前）"
        State.DO_AFTER -> "已移动（Do 后）"
        State.DO_NEXT -> "回合已确认"
    }

    // ---- 门禁（反馈②③）：纯函数，供按钮 enabled + VM 防御共用；引擎本体 advanceTime/confirmNext/undo 不改 ----

    /** Do 仅在非 DO_AFTER 状态可用（DO_BEFORE 初动 / DO_NEXT 新一轮） */
    fun canDo(state: State) = state != State.DO_AFTER

    /** Undo 仅在 DO_AFTER（Do 后未确认）可用 */
    fun canUndo(state: State) = state == State.DO_AFTER

    /** Next 仅在 DO_AFTER（Do 后未确认）可用 */
    fun canNext(state: State) = state == State.DO_AFTER

    /** 推进时间（Do 移动）：仅 PositionTime 推进；TurnTime 由 Next 追上（#8：移除未使用的 stateBefore 参数） */
    fun advanceTime(file: ScenarioFile, interval: TurnInterval) {
        val minutes = interval.totalMinutes()
        val newPt = TimeUtil.advance(file.time.currentPositionTime, minutes)

        // Do = 执行移动：无论何种前置状态，仅 PositionTime 推进；TurnTime 由 Next 追上
        // （桌面版：Do 移动不推进回合时间；Next 确认时 TurnTime=PositionTime）
        file.time.currentPositionTime = newPt
        file.scenario.phase = PHASE_POST_MOVEMENT
        file.time.currentTurnInterval = TurnInterval(interval.minutes, interval.seconds)
        // R3：快照 Do 时 interval 供 Next 复用（桌面 TurnInterval 按回合记录）
        file.lastTurnInterval = TurnInterval(interval.minutes, interval.seconds)
    }

    /**
     * Next：确认当前回合。TurnTime 追上 PositionTime，Turns 追加，Phase 回 0。
     * （桌面版行为：Do 后按 Next 确认回合，不产生新移动）
     * R3 修复：优先复用 Do 快照 interval，调用方传参仅为回退
     */
    fun confirmNext(file: ScenarioFile, interval: TurnInterval) {
        // R3：复用 Do 快照 interval；快照为空（如直接加载 DO_AFTER 存档）则回退调用方传入
        val useInterval = file.lastTurnInterval ?: interval
        val pt = file.time.currentPositionTime
        file.time.currentTurnTime = pt
        val exists = file.turns.any { sameTime(it.turnTime, pt) }
        if (!exists) {
            file.turns.add(Turn(turnTime = pt, turnInterval = TurnInterval(useInterval.minutes, useInterval.seconds)))
        }
        file.scenario.phase = PHASE_PLOTTING
        file.time.currentTurnInterval = TurnInterval(useInterval.minutes, useInterval.seconds)
        // Next 消费快照（下一轮 Do 会重新写入）
        file.lastTurnInterval = null
    }

    /**
     * Undo：回退一个回合。
     * - 有快照 → 恢复单位状态（位置/航向/航速/轨迹/Range）
     * - PositionTime 回退一个回合时长，Phase 回 0，Turns 移除该回合记录
     *
     * ⚠️ 危险路径（反馈②③）：DO_BEFORE（初始）下直接调用会把 PositionTime 回退到初始之前
     * 并清空快照 → 必须由 VM 层门禁（canUndo）拦截，本函数保持纯引擎语义不设防。
     */
    fun undo(file: ScenarioFile, interval: TurnInterval) {
        // R3/T8：回退时长优先级 = Do 内存快照 > Turns 历史记录（跨存盘重启后快照丢失，
        // 用刚追加的 Turn 条目的 interval 反推，与桌面 PushUndoTurn 语义一致）> 当前设置值
        val effective = file.lastTurnInterval
            ?: file.turns.lastOrNull()?.turnInterval?.let { TurnInterval(it.minutes, it.seconds) }
            ?: interval
        val minutes = effective.totalMinutes()
        val ptBefore = file.time.currentPositionTime   // 回退前的位置时间
        val back = TimeUtil.advance(ptBefore, -minutes)
        file.time.currentPositionTime = back
        file.scenario.phase = PHASE_PLOTTING
        // 恢复单位状态（深拷贝快照）；E5：Objects 用快照恢复，不重建
        file.undoSnapshot?.let { snap ->
            file.units.clear()
            file.units.addAll(snap)
        }
        file.undoObjects?.let { snap ->
            file.objects.clear()
            file.objects.addAll(snap)
        }
        // 移除刚确认回合（TurnTime == 回退前 PositionTime）的 Turns 记录
        file.turns.removeAll { sameTime(it.turnTime, ptBefore) }
        file.undoSnapshot = null
        file.undoObjects = null
        file.lastTurnInterval = null
    }
}
