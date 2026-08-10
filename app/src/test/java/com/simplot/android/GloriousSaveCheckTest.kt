package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 真机反馈"无法保存"排查：用用户真实场景（光荣号）跑完整保存链路 */
class GloriousSaveCheckTest {

    @Test
    fun gloriousSaveChain() {
        val f = File("/tmp/glorious.json")
        if (!f.exists()) return  // 非本机环境跳过
        val text = SpScnCodec.fromJsonFileBytes(f.readBytes())
        val loaded: ScenarioFile = JsonUtil.fromJson(text)
        assertTrue("场景应有单位", loaded.units.isNotEmpty())

        val blue = FogOfWar.applyPerspective(loaded, "Blue")
        val red = FogOfWar.applyPerspective(loaded, "Red")
        val refJson = JsonUtil.toCompactJson(loaded.copy(file = "Referee"))
        val blueJson = JsonUtil.toCompactJson(blue)
        val redJson = JsonUtil.toCompactJson(red)
        val refBytes = SpScnCodec.toJsonFileBytes(refJson)
        val blueScn = SpScnCodec.toScnFileBytes(blueJson)
        val redScn = SpScnCodec.toScnFileBytes(redJson)

        // 回读验证（SpScn 混淆往返）
        val blueRound = SpScnCodec.fromScnFileBytes(blueScn)
        assertTrue("蓝方回读应为合法 JSON", JsonUtil.isScenarioJson(blueRound))
        assertTrue("ref 字节非空", refBytes.isNotEmpty())
        assertTrue("blue 字节非空", blueScn.isNotEmpty())
        assertTrue("red 字节非空", redScn.isNotEmpty())
    }
}
