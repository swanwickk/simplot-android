package com.simplot.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.simplot.android.data.codec.ArcColorCodec

/** 预设色板（共享选色器）：桌面默认 6 色 + 常用色 + 各键变体（原 SettingsDialog G09 私有色板提升共享） */
internal val COLOR_PRESETS: List<Long> = listOf(
    0xFFF0F2F5, 0xFF1A1A2E, 0xFF102030, 0xFFE8E0D0,
    0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFF404040,
    0xFF005AC8, 0xFF1E5AA8, 0xFF2040A0, 0xFF006080,
    0xFFC81E1E, 0xFFA02020, 0xFFB03030, 0xFF801010,
    0xFF96AA82, 0xFF6B8F5A, 0xFFC8DCE8, 0xFFA8C8D8,
    0x883C789C, 0x44444444, 0x88FFFFFF, 0x88555555,
    0xFF40C040, 0xFFFFA000, 0xFFA040C0, 0xFFC06020
)

/**
 * 共享选色弹窗（G09 提升为公共组件，弧编辑器与颜色设置共用）。
 *
 * 交互：
 * - 预设色板网格：点选即回调并关闭（保持 SettingsDialog 原语义）；
 * - HSV 三滑杆：实时预览（保留当前 Alpha），点「确定」回调并关闭，「取消」不回调；
 * - 顶部实时色块 + 规范存档代码文本。
 *
 * @param current 当前颜色（0xAARRGGBB，Long 位模式；与 SettingsDialog 既有用法一致）
 */
@Composable
fun ColorPickerDialog(
    title: String,
    current: Long,
    onPick: (Long) -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit
) {
    val alpha = ((current shr 24) and 0xFF).toInt()
    var hsv by remember(current) {
        mutableStateOf(ArcColorCodec.rgbToHsv(current.toInt()))
    }
    var colorLong by remember(current) { mutableStateOf(current) }

    fun applyHsv(h: Float, s: Float, v: Float) {
        hsv = Triple(h, s, v)
        val rgb = ArcColorCodec.hsvToRgb(h, s, v)
        colorLong = (alpha.toLong() shl 24) or (rgb.toLong() and 0xFFFFFFL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 实时预览：色块 + 规范代码文本
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .border(1.dp, Color(0xFF666666), CircleShape)
                            .background(Color(colorLong), CircleShape)
                    )
                    Text(
                        ArcColorCodec.toVbColor(colorLong.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                HorizontalDivider()
                // 预设色板网格
                COLOR_PRESETS.chunked(4).forEach { rowColors ->
                    Row {
                        rowColors.forEach { c ->
                            val selected = c == colorLong
                            Box(
                                Modifier
                                    .padding(4.dp)
                                    .size(38.dp)
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else Color(0xFF999999),
                                        CircleShape
                                    )
                                    .background(Color(c), CircleShape)
                                    .testTag("preset_${c.toString(16)}")
                                    .clickable {
                                        colorLong = c
                                        hsv = ArcColorCodec.rgbToHsv(c.toInt())
                                        onPick(c)
                                        onDismiss()
                                    }
                            )
                        }
                    }
                }
                // HSV 滑杆：实时预览，确定才回调
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("H", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = hsv.first,
                        onValueChange = { applyHsv(it, hsv.second, hsv.third) },
                        valueRange = 0f..360f,
                        modifier = Modifier.weight(1f).padding(start = 6.dp, end = 4.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("S", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = hsv.second,
                        onValueChange = { applyHsv(hsv.first, it, hsv.third) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(start = 6.dp, end = 4.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("V", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = hsv.third,
                        onValueChange = { applyHsv(hsv.first, hsv.second, it) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(start = 6.dp, end = 4.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(colorLong); onDismiss() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}