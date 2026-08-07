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

    /** 推进时间：当前 + minutes（支持小数分钟，如 0.75 = 45 秒） */
    fun advance(positionTime: String, minutes: Double): String {
        val base = parse(positionTime)
        val wholeMins = minutes.toInt()
        val secs = ((minutes - wholeMins) * 60.0).toInt()
        return base.plusMinutes(wholeMins.toLong()).plusSeconds(secs.toLong()).format(FORMAT)
    }

    /** 判断两个时间字符串是否相等（解析比较，容忍格式差异） */
    fun equal(a: String, b: String): Boolean = parse(a) == parse(b)
}
