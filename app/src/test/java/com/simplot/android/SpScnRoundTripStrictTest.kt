package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 严格往返：Gson 序列化 → SpScn 编码 → 解码 → Gson 反序列化 → 再序列化，两次 JSON 必须一致。
 * 暴露 Gson 序列化/反序列化不对称（真机反馈：保存的 SpScn 无法读取）。
 */
class SpScnRoundTripStrictTest {

    private fun loadScenario(path: String): ScenarioFile {
        val f = File(path)
        val text = SpScnCodec.fromJsonFileBytes(f.readBytes())
        return JsonUtil.fromJson(text)
    }

    @Test
    fun strictRoundTrip_gloriousScenarios() {
        val dir = File("src/test/resources/scenarios")
        val candidates = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        var checked = 0
        for (scn in candidates) {
            val loaded = loadScenario(scn.absolutePath)
            if (loaded.units.isEmpty()) continue
            // 蓝方视角（含感知脱敏，最容易出问题）
            val blue = FogOfWar.applyPerspective(loaded, "Blue")
            val json1 = JsonUtil.toCompactJson(blue)
            val bytes = SpScnCodec.toScnFileBytes(json1)
            val text2 = SpScnCodec.fromScnFileBytes(bytes)
            val parsed = JsonUtil.fromJson(text2)
            val json2 = JsonUtil.toCompactJson(parsed)
            assertEquals("SpScn 往返 JSON 不一致: ${scn.name}\n---1---\n$json1\n---2---\n$json2", json1, json2)
            checked++
        }
        assertTrue("至少验证 1 个场景", checked > 0)
    }
}
