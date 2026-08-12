package com.simplot.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.Unit as SimUnit
import com.simplot.android.domain.engine.FormationEngine
import com.simplot.android.domain.engine.FormationEngine.FormationSpec
import kotlin.math.cos
import kotlin.math.sin

/** 队形类型中文标签（G02：三选 纵队/罗盘/航向） */
fun formationTypeLabel(type: String): String = when (type) {
    FormationEngine.FormationTypes.COLUMN -> "纵队"
    FormationEngine.FormationTypes.COMPASS -> "罗盘"
    FormationEngine.FormationTypes.COURSE -> "航向"
    else -> type
}

/** 距离单位中文标签（G02：海里/码/米） */
fun formationDistanceUnitLabel(unit: String): String = when (unit) {
    FormationEngine.FormationDistanceUnits.NMI -> "海里"
    FormationEngine.FormationDistanceUnits.YARDS -> "码"
    FormationEngine.FormationDistanceUnits.METERS -> "米"
    else -> unit
}

/**
 * 编队管理对话框（G02，对应桌面版 WindowFormation）。
 *
 * 能力（相对 R6 骨架版补全）：
 * - 创建编队：队形名 + 类型三选（纵队/罗盘/航向）+ 距离单位
 * - 队形列表：选择查看详情 / 重命名 / 删除
 * - 详情：类型三选、距离单位、成员列表（方位/距离）、添加/移除成员、设中心、
 *   静态罗盘预览（桌面版 CompassDraw 为拖拽式，触屏降级为只读预览）、准备/撤销
 *
 * 新增参数均带默认值，旧调用点（MainActivity 仅传 4 参）保持兼容。
 */
@Composable
fun FormationDialog(
    formationNames: List<String>,
    onDismiss: () -> Unit,
    onPrepare: (String) -> Unit,
    onCancel: (String) -> Unit,
    units: List<SimUnit> = emptyList(),
    specs: Map<String, FormationSpec> = emptyMap(),
    onCreate: (name: String, type: String, distanceUnit: String) -> Unit = { _, _, _ -> },
    onRename: (oldName: String, newName: String) -> Unit = { _, _ -> },
    onDelete: (name: String) -> Unit = {},
    onAddMember: (formation: String, unitId: String) -> Unit = { _, _ -> },
    onRemoveMember: (formation: String, unitId: String) -> Unit = { _, _ -> },
    onSetCenter: (formation: String, unitId: String) -> Unit = { _, _ -> },
    onSetType: (formation: String, type: String) -> Unit = { _, _ -> },
    onSetDistanceUnit: (formation: String, unit: String) -> Unit = { _, _ -> }
) {
    // 全部队形名 = 单位携带的 + 规格表（空队形仅存规格表）
    val allNames = (formationNames + specs.keys).distinct()

    var newName by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf(FormationEngine.FormationTypes.COLUMN) }
    var newUnit by remember { mutableStateOf(FormationEngine.FormationDistanceUnits.NMI) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编队管理") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // ---- 1. 新建编队（桌面版 CreateFormation：名 + 类型三选 + 距离单位） ----
                Text("新建编队", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("队形名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TypeOption("纵队", newType == FormationEngine.FormationTypes.COLUMN) { newType = FormationEngine.FormationTypes.COLUMN }
                    TypeOption("罗盘", newType == FormationEngine.FormationTypes.COMPASS) { newType = FormationEngine.FormationTypes.COMPASS }
                    TypeOption("航向", newType == FormationEngine.FormationTypes.COURSE) { newType = FormationEngine.FormationTypes.COURSE }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    UnitOption("海里", newUnit == FormationEngine.FormationDistanceUnits.NMI) { newUnit = FormationEngine.FormationDistanceUnits.NMI }
                    UnitOption("码", newUnit == FormationEngine.FormationDistanceUnits.YARDS) { newUnit = FormationEngine.FormationDistanceUnits.YARDS }
                    UnitOption("米", newUnit == FormationEngine.FormationDistanceUnits.METERS) { newUnit = FormationEngine.FormationDistanceUnits.METERS }
                }
                Button(
                    onClick = {
                        onCreate(newName.trim(), newType, newUnit)
                        if (newName.isNotBlank()) { selectedName = newName.trim(); newName = "" }
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("创建编队") }

                // ---- 2. 编队列表：选择 / 重命名 / 删除 ----
                Text("编队列表", style = MaterialTheme.typography.titleSmall)
                if (allNames.isEmpty()) {
                    Text("场景中无编队（可用上方「创建编队」新建）", style = MaterialTheme.typography.bodyMedium)
                }
                allNames.forEach { name ->
                    val type = specs[name]?.type ?: FormationEngine.typeOf(units, name)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f).padding(top = 6.dp)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                type?.let { "类型：${formationTypeLabel(it)}" } ?: "类型：—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { selectedName = name }) { Text("选择") }
                        TextButton(onClick = { renameTarget = name; renameText = name }) { Text("重命名") }
                        TextButton(onClick = { onDelete(name); if (selectedName == name) selectedName = null }) { Text("删除") }
                    }
                    if (renameTarget == name) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = renameText, onValueChange = { renameText = it },
                                label = { Text("新队形名") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            Button(onClick = { onRename(name, renameText.trim()); renameTarget = null }) { Text("确定") }
                            TextButton(onClick = { renameTarget = null }) { Text("取消") }
                        }
                    }
                }

                // ---- 3. 选中编队详情：类型/距离单位/成员/设中心/预览/准备/撤销 ----
                val sel = selectedName
                if (sel != null) {
                    val center = FormationEngine.centerOf(units, sel)
                    val members = FormationEngine.membersOf(units, sel)
                    val selSpec = specs[sel]
                    val selType = selSpec?.type ?: FormationEngine.typeOf(units, sel) ?: FormationEngine.FormationTypes.COLUMN
                    val selUnit = selSpec?.distanceUnit ?: FormationEngine.FormationDistanceUnits.NMI
                    // 可加入的单位：场景中存在且未加入任何编队
                    val available = units.filter { it.formationName.isNullOrBlank() }

                    Text("编队：$sel", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TypeOption("纵队", selType == FormationEngine.FormationTypes.COLUMN) { onSetType(sel, FormationEngine.FormationTypes.COLUMN) }
                        TypeOption("罗盘", selType == FormationEngine.FormationTypes.COMPASS) { onSetType(sel, FormationEngine.FormationTypes.COMPASS) }
                        TypeOption("航向", selType == FormationEngine.FormationTypes.COURSE) { onSetType(sel, FormationEngine.FormationTypes.COURSE) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        UnitOption("海里", selUnit == FormationEngine.FormationDistanceUnits.NMI) { onSetDistanceUnit(sel, FormationEngine.FormationDistanceUnits.NMI) }
                        UnitOption("码", selUnit == FormationEngine.FormationDistanceUnits.YARDS) { onSetDistanceUnit(sel, FormationEngine.FormationDistanceUnits.YARDS) }
                        UnitOption("米", selUnit == FormationEngine.FormationDistanceUnits.METERS) { onSetDistanceUnit(sel, FormationEngine.FormationDistanceUnits.METERS) }
                    }

                    Text("成员（${members.size + if (center != null) 1 else 0}）", style = MaterialTheme.typography.titleSmall)
                    if (center == null && members.isEmpty()) {
                        Text("尚无成员，从下方添加", style = MaterialTheme.typography.bodyMedium)
                    }
                    center?.let {
                        Text("★ 中心：${it.callsignOrName()}（${it.idNum}）", style = MaterialTheme.typography.bodyMedium)
                    }
                    members.forEach { m ->
                        val bearing = FormationEngine.bearingDeg(m)
                        val dist = FormationEngine.distanceValue(m, selUnit)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${m.callsignOrName()}（${m.idNum}） 方位${bearing?.let { "%.0f°".format(it) } ?: "—"} 距离${dist?.let { "%.1f".format(it) } ?: "—"}${formationDistanceUnitLabel(selUnit)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(top = 8.dp)
                            )
                            TextButton(onClick = { onSetCenter(sel, m.idNum) }) { Text("设中心") }
                            TextButton(onClick = { onRemoveMember(sel, m.idNum) }) { Text("移除") }
                        }
                    }

                    Text("可用单位（未入编队）", style = MaterialTheme.typography.titleSmall)
                    if (available.isEmpty()) {
                        Text("无可用单位", style = MaterialTheme.typography.bodyMedium)
                    }
                    available.forEach { u ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${u.callsignOrName()}（${u.idNum}）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(top = 8.dp)
                            )
                            OutlinedButton(onClick = { onAddMember(sel, u.idNum) }) { Text("添加") }
                        }
                    }

                    // ---- 罗盘预览（桌面版 CompassDraw；拖拽式触屏降级为静态方位预览） ----
                    Text("罗盘预览（静态，桌面版为拖拽式）", style = MaterialTheme.typography.bodySmall)
                    CompassPreview(center, members)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPrepare(sel) }, modifier = Modifier.weight(1f)) { Text("准备") }
                        OutlinedButton(onClick = { onCancel(sel) }, modifier = Modifier.weight(1f)) { Text("撤销") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {}
    )
}

/** 类型/距离单位单选行（纵队/罗盘/航向、海里/码/米共用）。
 *  RowScope 扩展：weight(1f) 需在父 Row 作用域内解析。 */
@Composable
private fun RowScope.TypeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RowScope.UnitOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 静态罗盘预览（G02 CompassDraw 降级版）：
 * 外圈罗盘 + 方位刻度 + 中心点（红）+ 成员方位线/点（蓝）。
 * 方位 0°=北 顺时针（与桌面版罗盘角一致）；成员距离统一缩放到 0.75 半径。
 */
@Composable
private fun CompassPreview(center: SimUnit?, members: List<SimUnit>) {
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(size.width, size.height) / 2f - 10.dp.toPx()
        // 罗盘外圈
        drawCircle(Color(0xFF90A4AE), r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
        // 方位刻度 N/E/S/W
        listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W").forEach { (deg, _) ->
            val rad = Math.toRadians(deg)
            val p = Offset(cx + (r * sin(rad)).toFloat(), cy - (r * cos(rad)).toFloat())
            drawCircle(Color(0xFF455A64), 3.dp.toPx(), p)
        }
        // 中心单位（红点；无中心时灰点占位）
        drawCircle(
            if (center != null) Color(0xFFD32F2F) else Color(0xFFBDBDBD),
            if (center != null) 6.dp.toPx() else 4.dp.toPx(),
            Offset(cx, cy)
        )
        // 成员：按罗盘方位（0=北 顺时针）画方位线与落位点
        members.forEach { m ->
            val b = FormationEngine.bearingDeg(m) ?: 0.0
            val rad = Math.toRadians(b)
            val p = Offset(cx + (r * 0.75 * sin(rad)).toFloat(), cy - (r * 0.75 * cos(rad)).toFloat())
            drawLine(Color(0xFF1976D2), Offset(cx, cy), p, 1.dp.toPx())
            drawCircle(Color(0xFF1976D2), 4.dp.toPx(), p)
        }
    }
}
