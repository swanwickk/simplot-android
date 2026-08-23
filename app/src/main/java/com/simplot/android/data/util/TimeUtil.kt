package com.simplot.android.data.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 存档时间工具：格式严格为 YYYY-MM-DD HH:MM:SS（24 小时制）
 */
object TimeUtil {
    val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun now(): String = LocalDateTime.now().format(FORMAT)

    fun parse(s: String): LocalDateTime = LocalDateTime.parse(s, FORMAT)

    /** 推进时间：当前 + minutes（支持小数分钟，如 0.75 = 45 秒）；脏时间回原值并打 log（T1：避免 now 跳变，回原值+logError语义由调用方 GameViewModel.logError 兜底） */
    fun advance(positionTime: String, minutes: Double): String {
        val base = try { parse(positionTime) } catch (_: Exception) { parseLenient(positionTime) }
        if (base == null) {
            try { android.util.Log.e("TimeUtil", "advance: invalid positionTime='$positionTime', return original") } catch (_: Exception) {}
            return positionTime
        }
        val wholeMins = minutes.toInt()
        val secs = ((minutes - wholeMins) * 60.0).toInt()
        return base.plusMinutes(wholeMins.toLong()).plusSeconds(secs.toLong()).format(FORMAT)
    }

    /**
     * 判断两个时间字符串是否语义相等（E11：解析比较，容忍跨格式存档差异，如无前导零）。
     *
     * 依次尝试：标准格式 → ISO_LOCAL_DATE_TIME（T 分隔）→ 正则补零；均失败视为不可解析。
     * 任一为空/空白/不可解析 → false（不抛异常，避免存档脏数据炸掉回合门禁）。
     */
    fun equal(a: String, b: String): Boolean {
        val da = parseLenient(a) ?: return false
        val db = parseLenient(b) ?: return false
        return da == db
    }

    /** 宽容解析：标准格式 → ISO → 补零正则；不可解析返回 null */
    private fun parseLenient(s: String): LocalDateTime? {
        val t = s.trim()
        if (t.isEmpty()) return null
        try {
            return LocalDateTime.parse(t, FORMAT)
        } catch (_: Exception) {
            // 落宽松路径
        }
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (_: Exception) {
            // 落补零路径
        }
        // 补零：2026-1-1 0:00:00 / 2026-01-01 0:00 / 2026-01-01T0:00:00 等 → 标准格式
        val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?""").matchEntire(t) ?: return null
        val g = m.groupValues
        val sec = if (g.size > 6 && g[6].isNotEmpty()) g[6].padStart(2, '0') else "00"
        return try {
            LocalDateTime.parse(
                "%04d-%02d-%02d %02d:%02d:%s".format(
                    g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), sec
                ),
                FORMAT
            )
        } catch (_: Exception) {
            null
        }
    }
}
