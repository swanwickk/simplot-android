package com.simplot.android.render

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.roundToLong

/**
 * 地图配置解析器（纯 Kotlin，无 Android 依赖 → 可纯 JVM 单测）。
 *
 * 解析官方 MapMaker JSON（实测 Iron Bottom Sound JJWS1.json），支持两套变体键：
 * - NewMap 键（MapMaker 编辑格式）：BoundaryRect + Land Polygons / Misc Polygons /
 *   Misc Labels / Water Labels / City Labels / Country Labels / Depth Polygons / Border Polys
 * - MercatorPolygon 键（G49，官方矢量地图发布格式）：Scale + Width/Height（无 BoundaryRect
 *   时推算范围）+ Countries / Cities / Waters / Land / Borders / Depths / Depth Labels
 * - BoundaryRect: {Left, Top, Width, Height} —— 地图世界范围
 *   ⚠️ 地图坐标为 海里×10000，存档坐标为 海里×100000 → 解析时 ×10 转存档坐标
 * - Land Polygons / Misc Polygons / Depth Polygons / Border Polys: Path=[x1,y1,x2,y2,...]
 * - Misc/Water/City/Country Labels: {Name, X, Y}
 * - BackgroundFileName: 背景图文件名
 *
 * G49 坐标约定：MercatorPolygon 变体的 SimPlotX/SimPlotY 与 Path 点同属"地图坐标
 * （海里×10000）"，与 BoundaryRect 一致 → 统一 ×10 转存档坐标（读现有 parser 比例尺
 * 约定确认：桌面 MercatorPolygon.LoadMapData 直接读 SimPlotX/Y，不做像素换算，只有
 * 光栅 MercatorRaster 才做 像素÷Scale；Scale 键对矢量地图仅作记录）。
 *
 * 产出纯数据（点列表），绘制层（MapPainter）负责转屏幕坐标。
 */
class MapDataParser {

    // ---- 地图边界（存档世界坐标，×10 后） ----
    var boundaryLeft = 0L
    var boundaryTop = 0L
    var boundaryWidth = 0L
    var boundaryHeight = 0L
    var hasBoundary = false

    // 地图世界范围（存档坐标）
    var mapWorldMinX = 0L
    var mapWorldMinY = 0L

    /** 矢量地图比例尺（MercatorPolygon/NewMap "Scale" 键原样保存；矢量坐标已含换算，仅记录） */
    var mapScale: Double = 0.0

    /** 陆地多边形（存档坐标点列表） */
    val landPolys = mutableListOf<List<Pair<Long, Long>>>()

    /** 覆盖多边形（机场等）(点列表, 颜色索引) */
    val miscPolys = mutableListOf<Pair<List<Pair<Long, Long>>, Int>>()

    /** 文字标注 (text, x, y) */
    val labels = mutableListOf<Triple<String, Long, Long>>()

    /** 水域名 (text, x, y) */
    val waterLabels = mutableListOf<Triple<String, Long, Long>>()

    /** 城市 (text, x, y) */
    val cityLabels = mutableListOf<Triple<String, Long, Long>>()

    /** 城市标注锚点（MercatorPolygon "Position"："Above Right" 等；与 cityLabels 按索引对齐，空串=默认） */
    val cityPositions = mutableListOf<String>()

    /** 国家名 (text, x, y) */
    val countryLabels = mutableListOf<Triple<String, Long, Long>>()

    /** 深度色带 (点列表, 级别 0-4) */
    val depthPolys = mutableListOf<Pair<List<Pair<Long, Long>>, Int>>()

    /** 国界线（点列表） */
    val borderPolys = mutableListOf<List<Pair<Long, Long>>>()

    /** 深度标签字符串 */
    val depthTexts = mutableListOf<String>()

    /** 水域是否主要（MercatorPolygon "IsMajor"；与 waterLabels 按索引对齐） */
    val waterIsMajor = mutableListOf<Boolean>()

    /** 待加载背景图文件名（官方配置） */
    var pendingBackgroundName: String? = null

    /** 解析官方 JSON 地图配置；失败时静默返回（地图缺失不阻塞） */
    fun parse(text: String) {
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            return
        }
        clear()
        parseBoundary(root)
        // G49：MercatorPolygon 变体 —— Scale / Width / Height（无 BoundaryRect 时推算范围）
        root.get("Scale")?.takeIf { it.isJsonPrimitive }?.let { mapScale = it.asDouble }
        if (!hasBoundary) {
            val w = numOrNull(root.get("Width"))
            val h = numOrNull(root.get("Height"))
            if (w != null && w > 0 && h != null && h > 0) {
                // 桌面 CalcBoundary(width, height)：Left=0, Top=Height, Width=Width, Height=Height
                // → 地图覆盖 [0..Width]×[0..Height]（地图单位），存档坐标 [0..W×10]×[0..H×10]
                boundaryLeft = 0; boundaryTop = h; boundaryWidth = w; boundaryHeight = h
                hasBoundary = true
                mapWorldMinX = 0
                mapWorldMinY = 0
            }
        }
        root.get("BackgroundFileName")?.takeIf { !it.isJsonNull }?.let {
            pendingBackgroundName = it.asString
        }
        // ---- 旧版 NewMap 键（保持兼容，缺变体键的老文件不崩、行为回退） ----
        parsePolygons(root.getAsJsonArray("Land Polygons")) { pts, idx -> landPolys.add(pts) }
        parsePolygons(root.getAsJsonArray("Misc Polygons")) { pts, idx -> miscPolys.add(pts to idx) }
        parseLabelArray(root.getAsJsonArray("Misc Labels"), labels)
        parseLabelArray(root.getAsJsonArray("Water Labels"), waterLabels)
        parseLabelArray(root.getAsJsonArray("City Labels"), cityLabels)
        parseLabelArray(root.getAsJsonArray("Country Labels"), countryLabels)
        root.getAsJsonArray("Depth Polygons")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val pathArr = o.getAsJsonArray("Path") ?: return@forEach
            val lvl = (o.get("DepthLevelIndex")?.asInt ?: 0).coerceIn(0, 4)
            depthPolys.add(pointsFromArray(pathArr) to lvl)
        }
        root.getAsJsonArray("Depth Labels")?.forEach { el ->
            if (el.isJsonPrimitive) depthTexts.add(el.asString)
        }
        root.getAsJsonArray("Border Polys")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val pathArr = el.asJsonObject.getAsJsonArray("Path") ?: return@forEach
            borderPolys.add(pointsFromArray(pathArr))
        }
        // ---- G49：MercatorPolygon 变体键（Countries/Cities/Waters/Land/Borders/Depths） ----
        parseCountries(root.getAsJsonArray("Countries"))
        parseCities(root.getAsJsonArray("Cities"))
        parseWaters(root.getAsJsonArray("Waters"))
        root.getAsJsonArray("Land")?.forEach { el ->
            parsePolyObject(el, "Land") { pts -> landPolys.add(pts) }
        }
        root.getAsJsonArray("Borders")?.forEach { el ->
            parsePolyObject(el, "Borders") { pts -> borderPolys.add(pts) }
        }
        root.getAsJsonArray("Depths")?.forEach { el -> parseDepthObject(el) }
    }

    fun clear() {
        boundaryLeft = 0; boundaryTop = 0; boundaryWidth = 0; boundaryHeight = 0
        hasBoundary = false
        mapWorldMinX = 0; mapWorldMinY = 0
        mapScale = 0.0
        landPolys.clear(); miscPolys.clear(); labels.clear()
        waterLabels.clear(); cityLabels.clear(); countryLabels.clear()
        cityPositions.clear(); waterIsMajor.clear()
        depthPolys.clear(); borderPolys.clear(); depthTexts.clear()
        pendingBackgroundName = null
    }

    /**
     * R5：光栅地图 .map/.txt 解析（桌面版 MercatorRaster.LoadMapData）。
     * 每行 "KEY = ***"：
     * - MAP = 光栅图片文件名（png/jpg）
     * - SCALE = 比例尺（Double，桌面 ConvertDouble 本地化解析）
     * - CITY = 城市名|像素X|像素Y（多个 CITY 行）
     * - COUNTRY = 国家名|像素X|像素Y
     * R6 修复（D4 决策）：桌面 LoadMapData 第 4 步 "SimPlotX/Y ← 像素 / Scale"（**除法**），
     * 与矢量地图坐标同为海里×10000 → 再 ×10 转存档坐标（海里×100000）。
     * @param pendingMapName 输出参数：解析到的 MAP 文件名（调用方据此加载图片）
     * @return 是否解析到 SCALE（无 SCALE 时城市/国家坐标无法换算）
     */
    fun parseRasterMap(text: String, pendingMapName: StringBuilder? = null): Boolean {
        val map = Regex("(?m)^MAP\\s*=\\s*(.+)\\s*$").find(text)?.groupValues?.get(1)?.trim()
        val scale = Regex("(?m)^SCALE\\s*=\\s*([\\d.,]+)\\s*$").find(text)?.groupValues?.get(1)
            ?.replace(",", ".")?.toDoubleOrNull()
        if (map != null) pendingMapName?.append(map)
        if (scale == null || scale <= 0.0) return false

        // R6：桌面语义 = 像素 ÷ Scale（得到海里×10000 地图坐标），再 ×10 转存档坐标
        // 例：px=100, scale=3.071 → 32.6（地图单位）→ 326 存档单位
        fun toWorld(px: Double): Long = (px / scale * 10).roundToLong()

        Regex("(?m)^CITY\\s*=\\s*(.+)$").findAll(text).forEach { m ->
            val parts = m.groupValues[1].split("|")
            if (parts.size >= 3) {
                val name = parts[0].trim()
                val px = parts[1].trim().toDoubleOrNull() ?: return@forEach
                val py = parts[2].trim().toDoubleOrNull() ?: return@forEach
                cityLabels.add(Triple(name, toWorld(px), toWorld(py)))
            }
        }
        Regex("(?m)^COUNTRY\\s*=\\s*(.+)$").findAll(text).forEach { m ->
            val parts = m.groupValues[1].split("|")
            if (parts.size >= 3) {
                val name = parts[0].trim()
                val px = parts[1].trim().toDoubleOrNull() ?: return@forEach
                val py = parts[2].trim().toDoubleOrNull() ?: return@forEach
                countryLabels.add(Triple(name, toWorld(px), toWorld(py)))
            }
        }
        return true
    }

    private fun parseBoundary(root: JsonObject) {
        root.getAsJsonObject("BoundaryRect")?.let { b ->
            boundaryLeft = numOrNull(b.get("Left")) ?: 0L
            boundaryTop = numOrNull(b.get("Top")) ?: 0L
            boundaryWidth = numOrNull(b.get("Width")) ?: 0L
            boundaryHeight = numOrNull(b.get("Height")) ?: 0L
            if (boundaryWidth > 0 && boundaryHeight > 0) {
                hasBoundary = true
                mapWorldMinX = boundaryLeft * 10
                mapWorldMinY = (boundaryTop - boundaryHeight) * 10
            }
        }
    }

    private fun parseLabelArray(arr: JsonArray?, sink: MutableList<Triple<String, Long, Long>>) {
        if (arr == null) return
        arr.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val name = o.get("Name")?.asString ?: return@forEach
            val x = (numOrNull(o.get("X")) ?: 0L) * 10
            val y = (numOrNull(o.get("Y")) ?: 0L) * 10
            sink.add(Triple(name, x, y))
        }
    }

    // ============ G49：MercatorPolygon 变体解析 ============

    /** Countries: [{Name, SimPlotX, SimPlotY}]（坐标同 BoundaryRect 体系，×10 转存档坐标） */
    private fun parseCountries(arr: JsonArray?) {
        if (arr == null) return
        arr.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val name = o.get("Name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val x = (numOrNull(o.get("SimPlotX")) ?: 0L) * 10
            val y = (numOrNull(o.get("SimPlotY")) ?: 0L) * 10
            countryLabels.add(Triple(name, x, y))
        }
    }

    /** Cities: [{Name, SimPlotX, SimPlotY, Position("Above Right")}]；Position 记录锚点供绘制偏移 */
    private fun parseCities(arr: JsonArray?) {
        if (arr == null) return
        arr.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val name = o.get("Name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val x = (numOrNull(o.get("SimPlotX")) ?: 0L) * 10
            val y = (numOrNull(o.get("SimPlotY")) ?: 0L) * 10
            cityLabels.add(Triple(name, x, y))
            cityPositions.add(o.get("Position")?.takeIf { !it.isJsonNull }?.asString ?: "")
        }
    }

    /** Waters: [{Name, SimPlotX, SimPlotY, IsMajor}]；IsMajor 记录供绘制加粗放大 */
    private fun parseWaters(arr: JsonArray?) {
        if (arr == null) return
        arr.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val name = o.get("Name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val x = (numOrNull(o.get("SimPlotX")) ?: 0L) * 10
            val y = (numOrNull(o.get("SimPlotY")) ?: 0L) * 10
            waterLabels.add(Triple(name, x, y))
            val major = o.get("IsMajor")
            waterIsMajor.add(major?.isJsonPrimitive == true && major.asJsonPrimitive.isBoolean && major.asBoolean)
        }
    }

    /** Land/Borders 元素: {Name?, Path}；Path 支持 JSON 数组或字符串（"x1,y1 x2,y2"） */
    private fun parsePolyObject(el: JsonElement, key: String, sink: (List<Pair<Long, Long>>) -> Unit) {
        if (!el.isJsonObject) return
        val o = el.asJsonObject
        val path = o.get("Path") ?: return
        val pts = pointsFromPath(path) ?: return
        if (pts.isNotEmpty()) sink(pts)
    }

    /** Depths: [{Id, Depth4, Path}]；级别取 Depth4（0-4；布尔 true=4），回退 DepthLevelIndex（旧格式） */
    private fun parseDepthObject(el: JsonElement) {
        if (!el.isJsonObject) return
        val o = el.asJsonObject
        val path = o.get("Path") ?: return
        val pts = pointsFromPath(path) ?: return
        if (pts.isEmpty()) return
        depthPolys.add(pts to depthLevelOf(o).coerceIn(0, 4))
    }

    private fun depthLevelOf(o: JsonObject): Int {
        o.get("Depth4")?.let { d ->
            if (d.isJsonPrimitive) {
                val p = d.asJsonPrimitive
                if (p.isBoolean) return if (p.asBoolean) 4 else 0
                return p.asInt
            }
        }
        o.get("DepthLevelIndex")?.let { d ->
            if (d.isJsonPrimitive && d.asJsonPrimitive.isNumber) return d.asJsonPrimitive.asInt
        }
        return 0
    }

    /** Path 元素 → 点列表：JSON 数组（旧格式）或字符串（MercatorPolygon 格式） */
    private fun pointsFromPath(el: JsonElement): List<Pair<Long, Long>>? {
        return when {
            el.isJsonArray -> pointsFromArray(el.asJsonArray)
            el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                pointsFromString(el.asJsonPrimitive.asString)
            else -> null
        }
    }

    /** 字符串 Path："x1,y1 x2,y2"（兼容逗号/分号/空白混合分隔）→ 点列表（×10 转存档坐标，四舍五入） */
    private fun pointsFromString(s: String): List<Pair<Long, Long>> {
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(s)
            .map { it.value.toDouble() }
            .toList()
        val pts = mutableListOf<Pair<Long, Long>>()
        var i = 0
        while (i + 1 < nums.size) {
            pts.add((nums[i] * 10).roundToLong() to (nums[i + 1] * 10).roundToLong())
            i += 2
        }
        return pts
    }

    /** JSON 元素 → Long（容忍 Double/字符串数值；非法返回 null） */
    private fun numOrNull(el: JsonElement?): Long? {
        if (el == null || el.isJsonNull) return null
        return try {
            if (el.isJsonPrimitive) {
                val p = el.asJsonPrimitive
                if (p.isNumber) p.asLong else p.asString.trim().toLongOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Path 数组 → 点列表（×10 转存档坐标） */
    private fun pointsFromArray(pathArr: JsonArray): List<Pair<Long, Long>> {
        val pts = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until pathArr.size() step 2) {
            val x = pathArr.get(i).asLong * 10
            val y = pathArr.get(i + 1).asLong * 10
            pts.add(x to y)
        }
        return pts
    }

    private fun parsePolygons(arr: JsonArray?, sink: (List<Pair<Long, Long>>, Int) -> Unit) {
        if (arr == null) return
        var colorIdx = 0
        arr.forEach { el ->
            val o = el.asJsonObject
            val pathArr = o.getAsJsonArray("Path") ?: return@forEach
            sink(pointsFromArray(pathArr), colorIdx)
            colorIdx++
        }
    }
}
