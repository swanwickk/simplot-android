package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加载回退测试（真机反馈：保存的 SpScn 无法读取的根因场景）。
 * 覆盖 ScenarioRepository.load 的「后缀不可知 → 先明文后回退 SpScn」路径。
 * 在测试内用内存字节构造 Blue.SpScn / Referee.json，不依赖外部 /tmp 文件（可靠可回归）。
 */
class RepoLoadFallbackTest {

    private fun sampleScenario(): ScenarioFile = ScenarioFile(
        file = "Blue",
        scenario = com.simplot.android.data.model.Scenario(scenarioName = "测试场景"),
        units = mutableListOf(
            Unit(idNum = "S001", side = "Blue", name = "DD-1", unitClass = "DD", x = 100, y = 200)
        )
    )

    @Test
    fun scnBytesFallbackDecrypts() {
        val raw = SpScnCodec.toScnFileBytes(JsonUtil.toCompactJson(sampleScenario()))
        // 模拟 name 为空（SAF provider 不返回 DISPLAY_NAME）：走「先明文后回退」路径
        val plain = SpScnCodec.fromJsonFileBytes(raw)
        val text = if (JsonUtil.isScenarioJson(plain)) plain
                   else SpScnCodec.fromScnFileBytes(raw)
        assertTrue("回退解密成功", JsonUtil.isScenarioJson(text))
        val loaded = JsonUtil.fromJson(text)
        assertEquals("Blue", loaded.file)
        assertEquals(1, loaded.units.size)
        println("fallback load OK: File=${loaded.file}")
    }

    @Test
    fun scnBytesDetectedByContentEvenWhenSuffixUnknown() {
        val raw = SpScnCodec.toScnFileBytes(JsonUtil.toCompactJson(sampleScenario()))
        // 复刻 Repository.load 逻辑：明文解析失败（密文首字节不是 {）→ 按 SpScn 解密
        val plain = SpScnCodec.fromJsonFileBytes(raw)
        assertTrue("密文不应被误判为明文 JSON", !JsonUtil.isScenarioJson(plain))
        val text = SpScnCodec.fromScnFileBytes(raw)
        assertTrue("回退按 SpScn 解密成功", JsonUtil.isScenarioJson(text))
    }

    @Test
    fun plainJsonStillWorks() {
        val raw = SpScnCodec.toJsonFileBytes(JsonUtil.toCompactJson(sampleScenario()))
        val text = SpScnCodec.fromJsonFileBytes(raw)
        assertTrue(JsonUtil.isScenarioJson(text))
        val loaded = JsonUtil.fromJson(text)
        assertEquals("Blue", loaded.file)
    }
}
