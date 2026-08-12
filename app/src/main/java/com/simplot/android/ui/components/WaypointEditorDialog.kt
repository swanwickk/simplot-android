package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplot.android.data.model.Unit
import com.simplot.android.data.model.Waypoint
import com.simplot.android.data.model.WaypointImportMode
import com.simplot.android.data.model.WaypointImporter
import com.simplot.android.domain.engine.CalcEngine
import com.simplot.android.ui.GameViewModel

/**
 * 航路点编辑器（P1，对应桌面版 WindowWaypoints）。
 *
 * 列出单位的 FutureWaypointArray（未来航路点），支持：
 * - 添加/删除航路点
 * - 编辑 X/Y/Speed/Course/AssignedAltDepth（高度深度共用）
 * - 显示到达时间（CalcEngine.arriveTime，按段速度估算）
 * - G04：导入航路点（桌面版 WindowImportWaypoints：源单位选择 +
 *   CopyExactWaypoints 精确复制 / CopyOffsetWaypoints 偏移复制 单选）
 * 写回由 onApply 统一提交（后端持久化）。
 *
 * @param allUnits 场景全部单位（导入源候选；缺省时回退 GameViewModel 当前场景，
 *   保持 MainActivity 调用点兼容）
 */
@Composable
fun WaypointEditorDialog(
    unit: Unit,
    currentTime: String,
    onApply: (Unit, List<Waypoint>) -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit,
    allUnits: List<Unit> = emptyList()
) {
    var waypoints by remember { mutableStateOf(unit.futureWaypointArray.toMutableList()) }

    // ---- G04 导入入口状态（桌面 WindowImportWaypoints） ----
    var showImport by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf(WaypointImportMode.EXACT) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var importNote by remember { mutableStateOf<String?>(null) }

    // 源单位池：显式参数优先，否则读取当前场景全部单位（viewModel() 返回 MainActivity 同实例）
    val sourcePool: List<Unit> = if (allUnits.isNotEmpty()) allUnits
        else viewModel<GameViewModel>().file?.units?.toList() ?: emptyList()
    val candidates = remember(unit, sourcePool) { WaypointImporter.sourceCandidates(sourcePool, unit) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        waypoints.add(
                            Waypoint(
                                x = unit.x, y = unit.y,
                                speed = unit.speed, course = unit.course,
                                // R12 修复：新航路点高度/深度继承单位当前值（桌面 CreateWaypoint 语义），
                                // 原实现误用 fileToNm(unit.x)（单位 X 坐标海里值当高度）
                                altitudeDepth = unit.altitude ?: unit.depth ?: 0,
                                assignedAltDepth = unit.altitude ?: unit.depth ?: 0,
                                number = waypoints.size + 1,
                                isTurnTime = true,
                                positionTime = currentTime
                            )
                        )
                    }, modifier = Modifier.weight(1f)) { Text("+ 添加航路点") }
                    // G05：删除全部（桌面 WindowWaypoints PushDeleteAllWP；空列表时禁用防误触）
                    OutlinedButton(
                        onClick = { waypoints.clear() },
                        enabled = waypoints.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("删除全部", color = MaterialTheme.colorScheme.error)
                    }
                }
                // ---- G04：导入航路点入口（桌面 WindowImportWaypoints） ----
                OutlinedButton(onClick = { showImport = !showImport }) { Text("导入航路点…") }
                if (showImport) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        if (candidates.isEmpty()) {
                            Text("无含航路点的其他单位可导入", style = MaterialTheme.typography.bodySmall)
                        } else {
                            // 源单位选择（桌面 PopupMenu1：列出所有含航路点的单位）
                            SourceUnitDropdown(
                                candidates = candidates,
                                selectedId = sourceId,
                                onSelect = { sourceId = it }
                            )
                            // 模式单选（桌面 RadioButton1=CopyExactWaypoints / RadioButton2=CopyOffsetWaypoints）
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                ImportModeOption("精确复制", importMode == WaypointImportMode.EXACT) {
                                    importMode = WaypointImportMode.EXACT
                                }
                                ImportModeOption("偏移复制", importMode == WaypointImportMode.OFFSET) {
                                    importMode = WaypointImportMode.OFFSET
                                }
                            }
                            Button(
                                onClick = {
                                    val src = candidates.firstOrNull { it.idNum == sourceId } ?: candidates.first()
                                    val imported = WaypointImporter.copyFrom(src, unit, importMode, waypoints.size + 1)
                                    if (imported.isNotEmpty()) {
                                        waypoints.addAll(imported)
                                        importNote = "已从 ${src.name.ifEmpty { src.idNum }} 导入 ${imported.size} 个航路点"
                                    }
                                }
                            ) { Text("执行导入") }
                        }
                        importNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
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
    // ×1000 定点：编辑按米，落盘 ×1000
    var altText by remember { mutableStateOf((wp.assignedAltDepth / 1000).toString()) }

    fun commit() {
        val altM = altText.toIntOrNull()
        onUpdate(
            Waypoint(
                name = wp.name,
                x = xText.toLongOrNull() ?: wp.x,
                y = yText.toLongOrNull() ?: wp.y,
                speed = ((speedText.toDoubleOrNull() ?: wp.speed / 1000.0) * 1000).toInt(),
                course = ((courseText.toDoubleOrNull() ?: wp.course / 1000.0) * 1000).toInt(),
                altitudeDepth = (altM ?: wp.altitudeDepth / 1000) * 1000,
                assignedAltDepth = (altM ?: wp.assignedAltDepth / 1000) * 1000,
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

/**
 * G04 源单位下拉（桌面 WindowImportWaypoints PopupMenu1）。
 * 样式对齐 UnitEditSheet.ShowAsDropdown（material3 1.3.0 ExposedDropdownMenuBox）。
 */
@OptIn(ExperimentalMaterial3Api::class)   // ExposedDropdownMenuBox/menuAnchor 在 material3 1.x 为实验 API
@Composable
private fun SourceUnitDropdown(
    candidates: List<Unit>,
    selectedId: String?,
    onSelect: (String) -> kotlin.Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = candidates.firstOrNull { it.idNum == selectedId } ?: candidates.first()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name.ifEmpty { selected.idNum },
            onValueChange = {},
            readOnly = true,
            label = { Text("源单位") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            candidates.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.name.ifEmpty { c.idNum }}（${c.idNum}）") },
                    onClick = { onSelect(c.idNum); expanded = false }
                )
            }
        }
    }
}

/**
 * G04 导入模式单选行（桌面 RadioButton1=精确复制 / RadioButton2=偏移复制）。
 * RowScope 扩展：weight(1f) 需在父 Row 作用域内解析（同 FormationDialog.TypeOption）。
 */
@Composable
private fun RowScope.ImportModeOption(label: String, selected: Boolean, onSelect: () -> kotlin.Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
