package com.simplot.android.data.model

import com.google.gson.annotations.SerializedName

/** 推演时间状态（双时钟 + 回合时长） */
data class TimeState(
    @SerializedName("CurrentTurnTime") var currentTurnTime: String = "2026-01-01 00:00:00",        // 当前回合时间
    @SerializedName("CurrentPositionTime") var currentPositionTime: String = "2026-01-01 00:00:00",    // 当前位置时间
    @SerializedName("CurrentTurnInterval") var currentTurnInterval: TurnInterval = TurnInterval()       // 回合时长（默认 3:00）
)

/** 回合时长：玩家可自由填写 XX分XX秒，默认 3 分钟 */
data class TurnInterval(
    var minutes: Int = 3,
    var seconds: Int = 0
) {
    /** 总分钟数（含秒折算） */
    fun totalMinutes(): Double = minutes + seconds / 60.0

    /** 显示字符串，如 "3:00" / "0:45" */
    fun display(): String = String.format("%d:%02d", minutes, seconds)

    companion object {
        fun of(minutes: Int, seconds: Int = 0) = TurnInterval(minutes, seconds)
        fun fromTotalMinutes(total: Double): TurnInterval {
            val m = total.toInt()
            val s = ((total - m) * 60).toInt()
            return TurnInterval(m, s)
        }
    }
}

/** 已确认回合历史记录 */
data class Turn(
    @SerializedName("TurnTime") var turnTime: String = "",
    @SerializedName("TurnInterval") var turnInterval: TurnInterval = TurnInterval()
)
