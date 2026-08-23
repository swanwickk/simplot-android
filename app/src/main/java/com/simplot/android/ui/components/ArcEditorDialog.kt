package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.Sensor
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Weapon

/**
 * 传感器/武器射程弧编辑器（P1，对应桌面版 ContainerSensors / ContainerWeapons）。
 *
 * 列出单位的 SensorArray / WeaponArray：
 * - 传感器弧：Tag/Label/MinRange/MaxRange/StartAngle/ArcAngle/IsFilled/IsVisible/ArcColor
 * - 武器弧：同上
 * 支持增删、编辑字段、可见性开关。写回由 onApply 统一提交（后端持久化）。
 */
@Composable
fun ArcEditorDialog(
    unit: Unit,
    onApply: (Unit, List<Sensor>, List<Weapon>) -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit
) {
    val sensors = remember {
        mutableStateListOf<Sensor>().apply {
            addAll(unit.sensorArray.orEmpty().map { it.copy() })
        }
    }
    val weapons = remember {
        mutableStateListOf<Weapon>().apply {
            addAll(unit.weaponArray.orEmpty().map { it.copy() })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("传感器/武器弧：${unit.name.ifEmpty { unit.idNum }}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()
            ) {
                Text("传感器", style = MaterialTheme.typography.labelMedium)
                sensors.forEachIndexed { i, s ->
                    key(s) {
                        ArcRow(
                            label = s.tag.ifBlank { s.label.ifBlank { "传感器 ${i + 1}" } },
                            minRange = s.minRange, maxRange = s.maxRange,
                            startAngle = s.startAngle, arcAngle = s.arcAngle,
                            filled = s.isFilled, visible = s.isVisible, color = s.arcColor,
                            onMoveUp = if (i > 0) ({ sensors.moveItem(i, i - 1) }) else null,
                            onMoveDown = if (i < sensors.size - 1) ({ sensors.moveItem(i, i + 1) }) else null,
                            onDelete = { sensors.removeAt(i) },
                            onUpdate = { minR, maxR, stA, arA, fill, vis, col ->
                                s.minRange = minR; s.maxRange = maxR; s.startAngle = stA; s.arcAngle = arA
                                s.isFilled = fill; s.isVisible = vis; s.arcColor = col
                            }
                        )
                    }
                }
                Button(onClick = {
                    sensors.add(Sensor(label = "New Sensor", maxRange = 50.0, arcAngle = 360.0, isFilled = true, isVisible = true))
                }) { Text("+ 添加传感器") }

                HorizontalDivider()
                Text("武器", style = MaterialTheme.typography.labelMedium)
                weapons.forEachIndexed { i, w ->
                    key(w) {
                        ArcRow(
                            label = w.tag.ifBlank { w.label.ifBlank { "武器 ${i + 1}" } },
                            minRange = w.minRange, maxRange = w.maxRange,
                            startAngle = w.startAngle, arcAngle = w.arcAngle,
                            filled = w.isFilled, visible = w.isVisible, color = w.arcColor,
                            onMoveUp = if (i > 0) ({ weapons.moveItem(i, i - 1) }) else null,
                            onMoveDown = if (i < weapons.size - 1) ({ weapons.moveItem(i, i + 1) }) else null,
                            onDelete = { weapons.removeAt(i) },
                            onUpdate = { minR, maxR, stA, arA, fill, vis, col ->
                                w.minRange = minR; w.maxRange = maxR; w.startAngle = stA; w.arcAngle = arA
                                w.isFilled = fill; w.isVisible = vis; w.arcColor = col
                            }
                        )
                    }
                }
                Button(onClick = {
                    weapons.add(Weapon(label = "New Weapon", maxRange = 50.0, arcAngle = 360.0, isFilled = true, isVisible = true))
                }) { Text("+ 添加武器") }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(unit, sensors.toList(), weapons.toList())
                onDismiss()
            }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 单个弧字段编辑行（传感器/武器共用） */
@Composable
private fun ArcRow(
    label: String,
    minRange: Double,
    maxRange: Double,
    startAngle: Double,
    arcAngle: Double,
    filled: Boolean,
    visible: Boolean,
    color: String,
    onMoveUp: (() -> kotlin.Unit)? = null,
    onMoveDown: (() -> kotlin.Unit)? = null,
    onDelete: () -> kotlin.Unit,
    onUpdate: (Double, Double, Double, Double, Boolean, Boolean, String) -> kotlin.Unit
) {
    var minText by remember(minRange) { mutableStateOf(minRange.toString()) }
    var maxText by remember(maxRange) { mutableStateOf(maxRange.toString()) }
    var startText by remember(startAngle) { mutableStateOf(startAngle.toString()) }
    var arcText by remember(arcAngle) { mutableStateOf(arcAngle.toString()) }
    var colorText by remember(color) { mutableStateOf(color) }
    var isFilled by remember(filled) { mutableStateOf(filled) }
    var isVisible by remember(visible) { mutableStateOf(visible) }

    fun commit() {
        onUpdate(
            minText.toDoubleOrNull() ?: minRange,
            maxText.toDoubleOrNull() ?: maxRange,
            startText.toDoubleOrNull() ?: startAngle,
            arcText.toDoubleOrNull() ?: arcAngle,
            isFilled, isVisible,
            colorText
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row {
            OutlinedTextField(value = minText, onValueChange = { minText = it; commit() },
                label = { Text("MinRange") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(end = 4.dp))
            OutlinedTextField(value = maxText, onValueChange = { maxText = it; commit() },
                label = { Text("MaxRange") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Row {
            OutlinedTextField(value = startText, onValueChange = { startText = it; commit() },
                label = { Text("StartAngle") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(end = 4.dp))
            OutlinedTextField(value = arcText, onValueChange = { arcText = it; commit() },
                label = { Text("ArcAngle") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Row {
            Checkbox(checked = isFilled, onCheckedChange = { isFilled = it; commit() })
            Text("填充")
            Checkbox(checked = isVisible, onCheckedChange = { isVisible = it; commit() })
            Text("显示")
            OutlinedTextField(value = colorText, onValueChange = { colorText = it; commit() },
                label = { Text("颜色(&hRRGGBB)") }, singleLine = true,
                modifier = Modifier.weight(1f))
        }
        Row {
            TextButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) { Text("↑") }
            TextButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) { Text("↓") }
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * 列表项上移/下移（G23：桌面列表顺序即绘制顺序，弧编辑器 ↑/↓ 重排用）。
 * 纯 Kotlin 顶层扩展 → 可 JVM 单测；越界 / 同位置返回 false 且不修改列表。
 */
internal fun <T> MutableList<T>.moveItem(from: Int, to: Int): Boolean {
    if (from < 0 || to < 0 || from >= size || to >= size || from == to) return false
    val item = removeAt(from)
    add(to, item)
    return true
}
