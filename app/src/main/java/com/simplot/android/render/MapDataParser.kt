package com.simplot.android.render

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 地图配置解析器（纯 Kotlin，无 Android 依赖 → 可纯 JVM 单测）。
 *
 * 解析官方 MapMaker JSON（实测 Iron Bottom Sound JJWS1.json）：
 * - BoundaryRect: {Left, Top, Width, Height} —— 地图世界范围
 *   ⚠️ 地图坐标为 海里×10000，存档坐标为 海里×100000 → 解析时 ×10 转存档坐标
 * - Land Polygons / Misc Polygons / Depth Polygons / Border Polys: Path=[x1,y1,x2,y2,...]
 * - Misc/Water/City/Country Labels: {Name, X, Y}
 * - BackgroundFileName: 背景图文件名
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

    /** 国家名 (text, x, y) */
    val countryLabels = mutableListOf<Triple<String, Long, Long>>()

    /** 深度色带 (点列表, 级别 0-4) */
    val depthPolys = mutableListOf<Pair<List<Pair<Long, Long>>, Int>>()

    /** 国界线（点列表） */
    val borderPolys = mutableListOf<List<Pair<Long, Long>>>()

    /** 深度标签字符串 */
    val depthTexts = mutableListOf<String>()

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
        root.get("BackgroundFileName")?.takeIf { !it.isJsonNull }?.let {
            pendingBackgroundName = it.asString
        }
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
    }

    fun clear() {
        boundaryLeft = 0; boundaryTop = 0; boundaryWidth = 0; boundaryHeight = 0
        hasBoundary = false
        mapWorldMinX = 0; mapWorldMinY = 0
        landPolys.clear(); miscPolys.clear(); labels.clear()
        waterLabels.clear(); cityLabels.clear(); countryLabels.clear()
        depthPolys.clear(); borderPolys.clear(); depthTexts.clear()
        pendingBackgroundName = null
    }

    /**
     * R5：光栅地图 .map/.txt 解析（桌面版 MercatorRaster.LoadMapData）。
     * 每行 "KEY = ***"：
     * - MAP = 光栅图片文件名（png/jpg）
     * - SCALE = 比例尺（km/像素，桌面版 ConvertDouble 本地化解析）
     * - CITY = 城市名|像素X|像素Y（多个 CITY 行）
     * - COUNTRY = 国家名|像素X|像素Y
     * 换算：1 世界单位 = 1/100000 海里 = 1852/100000 米；
     *       像素距离(米) = px × scale_km_per_px × 1000 → 世界坐标增量 = px × scale × 1000 × 100000 / 1852
     * @param pendingMapName 输出参数：解析到的 MAP 文件名（调用方据此加载图片）
     * @return 是否解析到 SCALE（无 SCALE 时城市/国家坐标无法换算）
     */
    fun parseRasterMap(text: String, pendingMapName: StringBuilder? = null): Boolean {
        val map = Regex("(?m)^MAP\\s*=\\s*(.+)\\s*$").find(text)?.groupValues?.get(1)?.trim()
        val scale = Regex("(?m)^SCALE\\s*=\\s*([\\d.,]+)\\s*$").find(text)?.groupValues?.get(1)
            ?.replace(",", ".")?.toDoubleOrNull()
        if (map != null) pendingMapName?.append(map)
        if (scale == null || scale <= 0.0) return false

        val pxToWorld = scale * 1000.0 * 100000.0 / 1852.0

        Regex("(?m)^CITY\\s*=\\s*(.+)$").findAll(text).forEach { m ->
            val parts = m.groupValues[1].split("|")
            if (parts.size >= 3) {
                val name = parts[0].trim()
                val px = parts[1].trim().toDoubleOrNull() ?: return@forEach
                val py = parts[2].trim().toDoubleOrNull() ?: return@forEach
                cityLabels.add(Triple(name, (px * pxToWorld).toLong(), (py * pxToWorld).toLong()))
            }
        }
        Regex("(?m)^COUNTRY\\s*=\\s*(.+)$").findAll(text).forEach { m ->
            val parts = m.groupValues[1].split("|")
            if (parts.size >= 3) {
                val name = parts[0].trim()
                val px = parts[1].trim().toDoubleOrNull() ?: return@forEach
                val py = parts[2].trim().toDoubleOrNull() ?: return@forEach
                countryLabels.add(Triple(name, (px * pxToWorld).toLong(), (py * pxToWorld).toLong()))
            }
        }
        return true
    }

    private fun parseBoundary(root: JsonObject) {
        root.getAsJsonObject("BoundaryRect")?.let { b ->
            boundaryLeft = b.get("Left")?.asLong ?: 0L
            boundaryTop = b.get("Top")?.asLong ?: 0L
            boundaryWidth = b.get("Width")?.asLong ?: 0L
            boundaryHeight = b.get("Height")?.asLong ?: 0L
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
            val x = (o.get("X")?.asLong ?: 0L) * 10
            val y = (o.get("Y")?.asLong ?: 0L) * 10
            sink.add(Triple(name, x, y))
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
