package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.engine.FogOfWar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 用户真实场景（光荣号）保存链路：加载→视角→序列化→读回（用户反馈 SpScn 无法读取） */
class GloriousSaveChainTest {
    @Test
    fun gloriousFullChain() {
        val f = File("src/test/resources/scenarios/光荣号航母.json")
        assertTrue("场景存在", f.exists())
        val text = SpScnCodec.fromJsonFileBytes(f.readBytes())
        assertTrue("加载为合法 JSON", JsonUtil.isScenarioJson(text))
        val loaded = JsonUtil.fromJson(text)
        println("loaded: File=${loaded.file} units=${loaded.units.size}")
        assertEquals(5, loaded.units.size)
        // 航路点字段是否被正确读取（用户场景用 PastWaypointArray1！）
        val cv = loaded.units.firstOrNull { it.unitClass == "CV" }
        println("CV unit: name=${cv?.name} wp=${cv?.pastWaypointArray?.size} futureWp=${cv?.futureWaypointArray?.size}")
        // 蓝方视角保存
        val blue = FogOfWar.applyPerspective(loaded, "Blue")
        val json = JsonUtil.toCompactJson(blue)
        val bytes = SpScnCodec.toScnFileBytes(json)
        val text2 = SpScnCodec.fromScnFileBytes(bytes)
        assertTrue("保存的 SpScn 读回为合法 JSON", JsonUtil.isScenarioJson(text2))
        val back = JsonUtil.fromJson(text2)
        assertEquals("Blue", back.file)
        println("SpScn 读回 OK: File=${back.file} units=${back.units.size}")
        // 保存到 /tmp 供 scn_tool 交叉验证
        File("/tmp/glorious_Blue.SpScn").writeBytes(bytes)
        File("/tmp/glorious_Blue.json").writeText(json)
    }
}
