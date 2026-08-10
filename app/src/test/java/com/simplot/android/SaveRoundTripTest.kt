package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 保存链路回归：加载→视角过滤→三文件序列化，全程不得抛异常（真机反馈：无法保存） */
class SaveRoundTripTest {

    private fun loadScenario(path: String): ScenarioFile {
        val f = File(path)
        val text = SpScnCodec.fromJsonFileBytes(f.readBytes())
        return JsonUtil.fromJson(text)
    }

    @Test
    fun saveChain_gloriousScenario() {
        val dir = File("src/test/resources/scenarios")
        val candidates = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        var checked = 0
        for (scn in candidates) {
            val loaded = loadScenario(scn.absolutePath)
            if (loaded.units.isEmpty()) continue
            // 视角过滤（真机保存链路核心步骤）
            val blue = FogOfWar.applyPerspective(loaded, "Blue")
            val red = FogOfWar.applyPerspective(loaded, "Red")
            // 三文件序列化
            val refJson = JsonUtil.toCompactJson(loaded.copy(file = "Referee"))
            val blueJson = JsonUtil.toCompactJson(blue)
            val redJson = JsonUtil.toCompactJson(red)
            val refBytes = SpScnCodec.toJsonFileBytes(refJson)
            val blueScn = SpScnCodec.toScnFileBytes(blueJson)
            val redScn = SpScnCodec.toScnFileBytes(redJson)
            assertTrue("ref 序列化字节为空: ${scn.name}", refBytes.isNotEmpty())
            assertTrue("blue 序列化字节为空: ${scn.name}", blueScn.isNotEmpty())
            assertTrue("red 序列化字节为空: ${scn.name}", redScn.isNotEmpty())
            checked++
        }
        assertTrue("至少验证 1 个场景", checked > 0)
    }
}
