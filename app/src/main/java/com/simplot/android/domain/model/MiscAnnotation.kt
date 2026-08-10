package com.simplot.android.domain.model

/**
 * Misc 标注对象（R7，桌面版 MiscBox/Oval/Line/Polygon/Label，Overlays 数据）。
 *
 * 反编译确认格式（CreateFromJson）：
 * - MiscLabel:  {Name, Side, ColorName, FontSize, IsBold, IsItalic, Rotation, X, Y}
 * - MiscBox:    {Name, Side, ColorName, Transparency, IsFilled, Rotation, Height, X, Y, Width}
 * - MiscOval:   {Name, Side, ColorName, Transparency, IsFilled, Rotation, Height, X, Y, Width}
 * - MiscLine:   {Name, Side, ColorName, Dash, Spacing, Path:[x1,y1,x2,y2,...]}
 * - MiscPolygon: {Name, Side, ColorName, Transparency, Dash, Spacing, IsFilled, Path:[...]}
 *
 * 坐标单位为地图/存档坐标（×100000 海里定点）。
 */
sealed class MiscAnnotation(
    open val name: String,
    open val side: String,
    open val colorName: String
) {
    /** 文字标注 */
    data class Label(
        override val name: String, override val side: String, override val colorName: String,
        val text: String, val x: Long, val y: Long, val fontSize: Double, val isBold: Boolean,
        val isItalic: Boolean, val rotation: Double
    ) : MiscAnnotation(name, side, colorName)

    /** 矩形 */
    data class Box(
        override val name: String, override val side: String, override val colorName: String,
        val x: Long, val y: Long, val width: Long, val height: Long,
        val transparency: Double, val isFilled: Boolean, val rotation: Double
    ) : MiscAnnotation(name, side, colorName)

    /** 椭圆 */
    data class Oval(
        override val name: String, override val side: String, override val colorName: String,
        val x: Long, val y: Long, val width: Long, val height: Long,
        val transparency: Double, val isFilled: Boolean, val rotation: Double
    ) : MiscAnnotation(name, side, colorName)

    /** 折线 */
    data class Line(
        override val name: String, override val side: String, override val colorName: String,
        val path: List<Pair<Long, Long>>, val dash: Double, val spacing: Double
    ) : MiscAnnotation(name, side, colorName)

    /** 多边形 */
    data class Polygon(
        override val name: String, override val side: String, override val colorName: String,
        val path: List<Pair<Long, Long>>, val transparency: Double,
        val dash: Double, val spacing: Double, val isFilled: Boolean
    ) : MiscAnnotation(name, side, colorName)
}
