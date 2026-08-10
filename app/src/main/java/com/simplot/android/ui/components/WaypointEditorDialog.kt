package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.domain.engine.CalcEngine

/**
 * 航路点编辑器（P1，对应桌面版 WindowWaypoints）。
 *
 * 列出单位的 FutureWaypointArray（未来航路点），支持：
 * - 添加/删除航路点
 * - 编辑 X/Y/Speed/Course/AssignedAltDepth（高度深度共用）
 * - 显示到达时间（CalcEngine.arriveTime，按段速度估算）
 * 写回由 onApply 统一提交（后端持久化）。
 */
@Composable
fun WaypointEditorDialog(
    unit: Unit,
    currentTime: String,
    onApply: (Unit, List<Waypoint>) -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit
) {
    var waypoints by remember { mutableStateOf(unit.futureWaypointArray.toMutableList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("航路点：${unit.name.ifEmpty { unit.idNum }}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (waypoints.isEmpty()) {
                    Text("无未来航路点（该单位当前无航行计划）", style = MaterialTheme.typography.bodyMedium)
                }
                waypoints.forEachIndexed { i, wp ->
                    WaypointRow(
                        index = i,
                        unit = unit,
                        wp = wp,
                        currentTime = currentTime,
                        onDelete = { waypoints.removeAt(i) },
                        onUpdate = { w -> waypoints[i] = w }
                    )
                }
                Button(onClick = {
                    waypoints.add(
                        Waypoint(
                            x = unit.x, y = unit.y,
                            speed = unit.speed, course = unit.course,
                            altitudeDepth = com.simplot.android.data.util.CoordUtil.fileToNm(unit.x).toInt(),
                            number = waypoints.size + 1,
                            isTurnTime = true,
                            positionTime = currentTime
                        )
                    )
                }) { Text("+ 添加航路点") }
                HorizontalDivider()
                Text(
                    "到达时间按 段距离/段速度 估算（桌面版 CalcArriveTime）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(unit, waypoints)
                onDismiss()
            }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 单个航路点编辑行 */
@Composable
private fun WaypointRow(
    index: Int,
    unit: Unit,
    wp: Waypoint,
    currentTime: String,
    onDelete: () -> kotlin.Unit,
    onUpdate: (Waypoint) -> kotlin.Unit
) {
    var xText by remember { mutableStateOf(wp.x.toString()) }
    var yText by remember { mutableStateOf(wp.y.toString()) }
    var speedText by remember { mutableStateOf((wp.speed / 1000.0).toString()) }
    var courseText by remember { mutableStateOf((wp.course / 1000.0).toString()) }
    var altText by remember { mutableStateOf(wp.assignedAltDepth.toString()) }

    fun commit() {
        onUpdate(
            Waypoint(
                name = wp.name,
                x = xText.toLongOrNull() ?: wp.x,
                y = yText.toLongOrNull() ?: wp.y,
                speed = ((speedText.toDoubleOrNull() ?: wp.speed / 1000.0) * 1000).toInt(),
                course = ((courseText.toDoubleOrNull() ?: wp.course / 1000.0) * 1000).toInt(),
                altitudeDepth = altText.toIntOrNull() ?: wp.altitudeDepth,
                assignedAltDepth = altText.toIntOrNull() ?: wp.assignedAltDepth,
                ascent = wp.ascent, descent = wp.descent,
                number = wp.number, isTurnTime = wp.isTurnTime,
                positionTime = wp.positionTime
            )
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Text("航路点 ${index + 1}", style = MaterialTheme.typography.labelMedium)
        Row {
            OutlinedTextField(value = xText, onValueChange = { xText = it; commit() },
                label = { Text("X") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(end = 4.dp))
            OutlinedTextField(value = yText, onValueChange = { yText = it; commit() },
                label = { Text("Y") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Row {
            OutlinedTextField(value = speedText, onValueChange = { speedText = it; commit() },
                label = { Text("航速(节)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(end = 4.dp))
            OutlinedTextField(value = courseText, onValueChange = { courseText = it; commit() },
                label = { Text("航向(度)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Row {
            OutlinedTextField(value = altText, onValueChange = { altText = it; commit() },
                label = { Text("高度/深度(米)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(end = 4.dp))
            // 到达时间（只读展示）：按 单位→航路点 直线距离 / 段速度 估算
            val arrive = CalcEngine.arriveTime(currentTime, wpDistNm(unit, wp), wp.speed / 1000.0)
            OutlinedTextField(
                value = arrive ?: "—",
                onValueChange = {},
                readOnly = true,
                label = { Text("到达时间") },
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }
        TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
    }
}

/** 单位当前位置到航路点的直线距离（海里），用于到达时间估算 */
private fun wpDistNm(unit: Unit, wp: Waypoint): Double =
    com.simplot.android.data.util.CoordUtil.distanceNm(unit.x, unit.y, wp.x, wp.y)
