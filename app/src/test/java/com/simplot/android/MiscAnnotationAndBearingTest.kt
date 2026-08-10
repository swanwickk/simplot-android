package com.simplot.android

import com.simplot.android.data.codec.JsonUtil
import com.simplot.android.data.model.PassiveBearing
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.Unit
import com.simplot.android.domain.engine.MiscAnnotationParser
import com.simplot.android.domain.model.MiscAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7 测试：Misc 标注解析 + 被动方位序列化。
 */
class MiscAnnotationAndBearingTest {

    @Test
    fun `parses polygon and label from overlays`() {
        val overlays = mapOf<String, Any?>(
            "MiscOverlays" to listOf(
                mapOf(
                    "Name" to "poly1", "Side" to "All", "ColorName" to "Red",
                    "Type" to "poly", "IsFilled" to true, "Transparency" to 20.0,
                    "Path" to listOf(0.0, 0.0, 100.0, 0.0, 100.0, 100.0)
                ),
                mapOf(
                    "Name" to "lbl1", "Side" to "All", "ColorName" to "Blue",
                    "Type" to "label", "Text" to "TEST", "X" to 10.0, "Y" to 20.0,
                    "FontSize" to 12.0, "IsBold" to true
                )
            )
        )
        val anns = MiscAnnotationParser.parse(overlays)
        assertEquals(2, anns.size)
        val poly = anns[0] as MiscAnnotation.Polygon
        assertEquals("poly1", poly.name)
        assertEquals(3, poly.path.size)
        assertTrue(poly.isFilled)
        val label = anns[1] as MiscAnnotation.Label
        assertEquals("TEST", label.text)
        assertTrue(label.isBold)
    }

    @Test
    fun `empty overlays safe`() {
        assertEquals(0, MiscAnnotationParser.parse(null).size)
        assertEquals(0, MiscAnnotationParser.parse(emptyMap()).size)
    }

    @Test
    fun `passive bearing survives gson round trip`() {
        val u = Unit(
            idNum = "S001", side = "Blue",
            passiveBearingArray = mutableListOf(
                PassiveBearing(type = "ES", bearing = 45.0, emitter = "S002", label = "Contact 1")
            )
        )
        val f = ScenarioFile(units = mutableListOf(u))
        val json = JsonUtil.toCompactJson(f)
        val back = JsonUtil.fromJson(json)
        val b = back.units[0].passiveBearingArray!![0]
        assertEquals(45.0, b.bearing, 1e-9)
        assertEquals("S002", b.emitter)
        assertEquals("ES", b.type)
    }

    @Test
    fun `passive bearing null safe`() {
        val u = Unit(idNum = "S001")
        val f = ScenarioFile(units = mutableListOf(u))
        val back = JsonUtil.fromJson(JsonUtil.toCompactJson(f))
        assertEquals(null, back.units[0].passiveBearingArray)
    }
}
