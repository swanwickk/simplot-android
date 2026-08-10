package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.codec.SpScnCodec
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 光荣号原始存档兼容回归（2026-08-10 用户上传原始档，md5=fixture）。
 *
 * 验证：
 * 1. 加载原始档（含 WpDistance / PastWaypointArray1 后缀）不丢字段；
 * 2. 序列化输出不新增 FormationType/编队键（P1-1 修复）；
 * 3. WpDistance 保留（§9.1 发现）；
 * 4. Scenario 无 MapFileName 时空键省略。
 */
class GloriousOriginalCompatTest {

    private fun loadOriginal(): ScenarioFile {
        val f = File("src/test/resources/scenarios/光荣号航母.json")
        assertTrue("fixture 存在", f.exists())
        val text = SpScnCodec.fromJsonFileBytes(f.readBytes())
        return JsonUtil.fromJson(text)
    }

    @Test
    fun `original loads with wpdistance and 1-suffix arrays`() {
        val loaded = loadOriginal()
        assertEquals(5, loaded.units.size)
        // WpDistance 字段加载（scn_tool 键）
        assertTrue(loaded.units.all { it.wpDistance == 0 })
        // PastWaypointArray1 alternate 读取成功（空 {} → 空列表）
        assertTrue(loaded.units.all { it.pastWaypointArray.isEmpty() })
        assertTrue(loaded.units.all { it.futureWaypointArray.isEmpty() })
        // 无编队键/感知键（原始档无）
        assertTrue(loaded.units.all { it.isInFormation == null })
        assertTrue(loaded.units.all { it.formationType == null })
        assertTrue(loaded.units.all { it.perceptionArray == null })
    }

    @Test
    fun `reserialization does not add formation keys and keeps wpdistance`() {
        val loaded = loadOriginal()
        val json = JsonUtil.toCompactJson(loaded)
        // P1-1 修复：不写 FormationType / 编队键（原始档没有，app 保存不应新增）
        assertFalse("不应输出 FormationType", json.contains("\"FormationType\""))
        assertFalse("不应输出 IsInFormation", json.contains("\"IsInFormation\""))
        assertFalse("不应输出 IsFormationCenter", json.contains("\"IsFormationCenter\""))
        // WpDistance 保留
        assertTrue("WpDistance 应保留", json.contains("\"WpDistance\":0"))
        // PositionTimeDeleted 默认对齐 2020-01-01
        assertTrue(json.contains("\"PositionTimeDeleted\":\"2020-01-01 00:00:00\""))
        // Scenario 空 MapFileName 省略（原始档无此键）
        assertFalse("空 MapFileName 应省略", json.contains("\"MapFileName\":\"\""))
    }

    @Test
    fun `reserialization keeps unit key set minimal`() {
        val loaded = loadOriginal()
        val json = JsonUtil.toCompactJson(loaded)
        // 单位键集应保持原始 21 键左右（+WpDistance 已在其中），不膨胀
        val unitObj = JsonUtil.gson.fromJson(json, com.google.gson.JsonObject::class.java)
            .getAsJsonArray("Units")[0].asJsonObject
        val keys = unitObj.keySet()
        // 原始键：Course,FutureWaypointArray1→(读为 PastWaypointArray 键名输出),... 序列化用主键名
        assertTrue("键数应 ≤24（原始 21 + WpDistance 无新增）", keys.size <= 24)
        assertFalse(keys.contains("FormationType"))
        assertFalse(keys.contains("IsInFormation"))
        assertTrue(keys.contains("WpDistance"))
    }
}
