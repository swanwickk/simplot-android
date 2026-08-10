package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 模拟 Android 读取保存的 Blue.SpScn（用户反馈：保存的 SpScn 无法读取） */
class LoadSavedScnTest {
    @Test
    fun loadSavedBlueScn() {
        val f = File("/tmp/android_Blue.SpScn")
        if (!f.exists()) return  // 未导出时跳过
        val raw = f.readBytes()
        // 模拟 repo.load 的 .spscn 分支
        val text = SpScnCodec.fromScnFileBytes(raw)
        assertTrue("解密后非空", text.isNotBlank())
        assertTrue("是合法 JSON", JsonUtil.isScenarioJson(text))
        val loaded = JsonUtil.fromJson(text)
        assertEquals("Blue", loaded.file)
        assertTrue("单位非空", loaded.units.isNotEmpty())
        println("load Blue.SpScn OK: File=${loaded.file} units=${loaded.units.size}")
    }
}
