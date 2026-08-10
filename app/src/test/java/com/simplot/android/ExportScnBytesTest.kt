package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.engine.FogOfWar
import org.junit.Test
import java.io.File

/** 导出 Android 实际生成的 SpScn 字节，供外部工具交叉验证 */
class ExportScnBytesTest {
    @Test
    fun exportBlueScn() {
        val f = File("src/test/resources/scenarios")
        val scnFile = f.listFiles { it.name.endsWith(".json") }?.firstOrNull()
            ?: return
        val text = SpScnCodec.fromJsonFileBytes(scnFile.readBytes())
        val loaded = JsonUtil.fromJson(text)
        val blue = FogOfWar.applyPerspective(loaded, "Blue")
        val json = JsonUtil.toCompactJson(blue)
        val bytes = SpScnCodec.toScnFileBytes(json)
        File("/tmp/android_Blue.SpScn").writeBytes(bytes)
        File("/tmp/android_Blue.json").writeText(json)
        println("exported /tmp/android_Blue.SpScn (${bytes.size} bytes) + json (${json.length})")
    }
}
