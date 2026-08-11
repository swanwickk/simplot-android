package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面版导出的 Red.SpScn（用户上传，浏览器改后缀为 .json）加载回归。
 * 用户反馈：桌面发来的 SpScn 读取报错。
 */
class MediterraneanRedLoadTest {

    @Test
    fun `loads desktop red spscn`() {
        val f = File("src/test/resources/scenarios/Mediterranean_Red.spScn")
        assertTrue("fixture 存在", f.exists())
        val text = SpScnCodec.fromScnFileBytes(f.readBytes())
        assertTrue("解密为合法 JSON", JsonUtil.isScenarioJson(text))
        val loaded: ScenarioFile = JsonUtil.fromJson(text)
        assertEquals("Red", loaded.file)
        assertEquals(131, loaded.units.size)
        assertEquals(29, loaded.turns.size)
        // 单位字段完整性
        val u = loaded.units.first { it.idNum == "S001" }
        assertEquals("Orion", u.name)
        assertEquals(-607888L, u.x)
        assertEquals(1, u.wpDistance)
        assertTrue(u.perceptionArray != null)
        assertTrue(u.sensorArray != null)
    }
}
