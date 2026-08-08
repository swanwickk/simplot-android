package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.Unit
import kotlin.math.roundToInt

/**
 * 单位属性编辑弹层（长按单位弹出，触摸屏替代桌面右键表单）
 * - 航向：滑杆（0-360）+ 数值输入
 * - 航速：滑杆（0-40 节）
 * - 高度（飞机）/深度（潜艇）
 * - 可见性：对蓝/红方可见开关（需求二）
 * - 沉没标记
 */
@Composable
fun UnitEditSheet(
    unit: Unit,
    onApply: (Unit) -> kotlin.Unit,
    onDelete: (Unit) -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit
) {
    var course by remember { mutableFloatStateOf(unit.courseDeg().toFloat()) }
    var speed by remember { mutableFloatStateOf(unit.speedKnots().toFloat()) }
    var alt by remember { mutableStateOf(unit.altitudeMeters()?.toString() ?: "") }
    var depth by remember { mutableStateOf(unit.depthMeters()?.toString() ?: "") }
    var showName by remember { mutableStateOf(unit.textTags.tagName) }
    var showCS by remember { mutableStateOf(unit.textTags.tagCourseSpeed) }
    var sunk by remember { mutableStateOf(unit.showSunk) }
    var visibleBlue by remember { mutableStateOf(com.simplot.android.engine.FogOfWar.isVisibleTo(unit, "Blue")) }
    var visibleRed by remember { mutableStateOf(com.simplot.android.engine.FogOfWar.isVisibleTo(unit, "Red")) }

    // 受限项（需求二：对可见单位的脱敏设置，取 Blue 感知记录的值作为编辑状态）
    val blueRec = unit.perceptionArray?.firstOrNull { it.seenBySide == "Blue" }
    var showNameBlue by remember { mutableStateOf(blueRec?.showName ?: true) }
    var showCSBlue by remember { mutableStateOf(blueRec?.showCourseSpeed ?: true) }
    var showClassBlue by remember { mutableStateOf(blueRec?.showClass ?: true) }
    var showTypeBlue by remember { mutableStateOf(blueRec?.showAsType ?: "") }
    var showSideBlue by remember { mutableStateOf(blueRec?.showAsSide ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(unit.name.ifEmpty { unit.idNum }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("航向：${course.roundToInt()}°", style = MaterialTheme.typography.bodySmall)
                Slider(value = course, onValueChange = { course = it }, valueRange = 0f..360f)
                Text("航速：${speed.roundToInt()} 节", style = MaterialTheme.typography.bodySmall)
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0f..40f)

                if (unit.isAircraft()) {
                    OutlinedTextField(
                        value = alt, onValueChange = { alt = it },
                        label = { Text("高度（米）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (unit.isSubmarine()) {
                    OutlinedTextField(
                        value = depth, onValueChange = { depth = it },
                        label = { Text("深度（米）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()
                Text("标签显示", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showName, onCheckedChange = { showName = it })
                    Text("显示名称")
                    Checkbox(checked = showCS, onCheckedChange = { showCS = it })
                    Text("显示航向航速")
                }

                HorizontalDivider()
                Text("可见性（需求二）", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visibleBlue, onCheckedChange = { visibleBlue = it })
                    Text("对蓝方可见")
                    Checkbox(checked = visibleRed, onCheckedChange = { visibleRed = it })
                    Text("对红方可见")
                }

                // 受限项（脱敏）：蓝方可见时的显示限制
                Text("受限项（蓝方视角）", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showNameBlue, onCheckedChange = { showNameBlue = it })
                    Text("显示名称")
                    Checkbox(checked = showCSBlue, onCheckedChange = { showCSBlue = it })
                    Text("显示航向航速")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showClassBlue, onCheckedChange = { showClassBlue = it })
                    Text("显示级别")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示为类型：", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = showTypeBlue, onValueChange = { showTypeBlue = it },
                        label = { Text("留空=真实类型") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示为阵营：", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = showSideBlue, onValueChange = { showSideBlue = it },
                        label = { Text("留空=真实阵营") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sunk, onCheckedChange = { sunk = it })
                    Text("沉没")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                unit.setCourse(course.toDouble())
                unit.setSpeed(speed.toDouble())
                if (unit.isAircraft() && alt.isNotBlank()) unit.altitude = alt.toInt() * 1000
                if (unit.isSubmarine() && depth.isNotBlank()) unit.depth = depth.toInt() * 1000
                unit.textTags.tagName = showName
                unit.textTags.tagCourseSpeed = showCS
                unit.showSunk = sunk
                com.simplot.android.engine.FogOfWar.setVisibility(
                    unit, "Blue", visibleBlue, unit.positionTimeCreated,
                    file = null
                )
                com.simplot.android.engine.FogOfWar.setVisibility(
                    unit, "Red", visibleRed, unit.positionTimeCreated,
                    file = null
                )
                // 受限项写回 Blue 感知记录（仅当该单位对蓝方可见且有记录时）
                val bluePer = unit.perceptionArray?.firstOrNull { it.seenBySide == "Blue" }
                if (bluePer != null && visibleBlue) {
                    bluePer.showName = showNameBlue
                    bluePer.showCourseSpeed = showCSBlue
                    bluePer.showClass = showClassBlue
                    bluePer.showAsType = showTypeBlue
                    bluePer.showAsSide = showSideBlue
                }
                onApply(unit)
                onDismiss()
            }) { Text("应用") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                onDelete(unit); onDismiss()
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    )
}
