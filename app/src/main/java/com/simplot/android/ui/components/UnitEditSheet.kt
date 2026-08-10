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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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

/**
 * 单位属性编辑弹层（长按单位弹出，触摸屏替代桌面右键表单）
 * - 航向/航速：数字输入（反馈⑨：去掉滑杆，改为纯输入）
 * - 高度（飞机）/深度（潜艇）
 * - 可见性：对蓝/红方可见开关（需求二）
 * - 沉没标记
 * - 反馈⑨：内容超屏可滚动（Column verticalScroll）
 */
@Composable
fun UnitEditSheet(
    unit: Unit,
    onApply: (Unit) -> kotlin.Unit,
    onDelete: (Unit) -> kotlin.Unit,
    onShowAsSunk: (Unit) -> kotlin.Unit = {},
    onDuplicate: (Unit) -> kotlin.Unit = {},
    onDismiss: () -> kotlin.Unit
) {
    // 反馈⑨：航向/航速纯数字输入（去掉滑杆；文本与数值双向同步，非法输入保留上次有效值）
    var course by remember { mutableFloatStateOf(unit.courseDeg().toFloat()) }
    var speed by remember { mutableFloatStateOf(unit.speedKnots().toFloat()) }
    var courseText by remember { mutableStateOf(formatCourseSpeed(unit.courseDeg())) }
    var speedText by remember { mutableStateOf(formatCourseSpeed(unit.speedKnots())) }
    var alt by remember { mutableStateOf(unit.altitudeMeters()?.toString() ?: "") }
    var depth by remember { mutableStateOf(unit.depthMeters()?.toString() ?: "") }
    // R-P2：X/Y 编辑（桌面版各单位窗口有 X/Y 字段，替代无 Relocate 时的位置调整）
    var xText by remember { mutableStateOf(unit.x.toString()) }
    var yText by remember { mutableStateOf(unit.y.toString()) }
    // 删除三选弹窗状态（R-P2：桌面 DeleteUnit 确认 Remove/Show as Sunk/Cancel）
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // R9：Domain 判定（参考点无航向航速；浮标显示深度）
    val domain = com.simplot.android.domain.registry.UnitTypeRegistry.domainOf(unit)
    val isReference = domain == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.REFERENCE_POINT
    val isSonobuoy = domain == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SONOBUOY
    var showName by remember { mutableStateOf(unit.textTags.tagName) }
    var showCS by remember { mutableStateOf(unit.textTags.tagCourseSpeed) }
    var sunk by remember { mutableStateOf(unit.showSunk) }
    var visibleBlue by remember { mutableStateOf(com.simplot.android.engine.FogOfWar.isVisibleTo(unit, "Blue")) }
    var visibleRed by remember { mutableStateOf(com.simplot.android.engine.FogOfWar.isVisibleTo(unit, "Red")) }

    // 受限项（需求二：对可见单位的脱敏设置，取 Blue/Red 感知记录的值作为编辑状态，双视角）
    val blueRec = unit.perceptionArray?.firstOrNull { it.seenBySide == "Blue" }
    var showNameBlue by remember { mutableStateOf(blueRec?.showName ?: true) }
    var showCSBlue by remember { mutableStateOf(blueRec?.showCourseSpeed ?: true) }
    var showClassBlue by remember { mutableStateOf(blueRec?.showClass ?: true) }
    var showTypeBlue by remember { mutableStateOf(blueRec?.showAsType ?: "") }
    var showSideBlue by remember { mutableStateOf(blueRec?.showAsSide ?: "") }
    val redRec = unit.perceptionArray?.firstOrNull { it.seenBySide == "Red" }
    var showNameRed by remember { mutableStateOf(redRec?.showName ?: true) }
    var showCSRed by remember { mutableStateOf(redRec?.showCourseSpeed ?: true) }
    var showClassRed by remember { mutableStateOf(redRec?.showClass ?: true) }
    var showTypeRed by remember { mutableStateOf(redRec?.showAsType ?: "") }
    var showSideRed by remember { mutableStateOf(redRec?.showAsSide ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(unit.name.ifEmpty { unit.idNum }) },
        text = {
            // 反馈⑨：内容超出屏幕时可滚动（此前无滚动，小屏/内容多时底部被截断）
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (!isReference) {
                    OutlinedTextField(
                        value = courseText,
                        onValueChange = {
                            courseText = it
                            it.toFloatOrNull()?.let { v -> course = v }   // 非法输入保留上次有效值
                        },
                        label = { Text("航向（度 0-360）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = speedText,
                        onValueChange = {
                            speedText = it
                            it.toFloatOrNull()?.let { v -> speed = v }   // 非法输入保留上次有效值
                        },
                        label = { Text("航速（节）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }

                if (unit.isAircraft()) {
                    OutlinedTextField(
                        value = alt, onValueChange = { alt = it },
                        label = { Text("高度（米）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (unit.isSubmarine() || isSonobuoy) {
                    OutlinedTextField(
                        value = depth, onValueChange = { depth = it },
                        label = { Text("深度（米）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }

                // R-P2：X/Y 位置编辑（桌面版各单位窗口有 X/Y 字段）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = xText, onValueChange = { xText = it },
                        label = { Text("X（文件单位）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = yText, onValueChange = { yText = it },
                        label = { Text("Y（文件单位）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
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

                // 受限项（脱敏）：红蓝双方视角各自的显示限制
                Text("受限项（蓝方视角）", style = MaterialTheme.typography.labelMedium)
                RestrictedRow(
                    showName = showNameBlue,
                    showCS = showCSBlue,
                    showClass = showClassBlue,
                    showType = showTypeBlue,
                    showSide = showSideBlue,
                    onName = { showNameBlue = it },
                    onCS = { showCSBlue = it },
                    onClass = { showClassBlue = it },
                    onType = { showTypeBlue = it },
                    onSide = { showSideBlue = it }
                )
                HorizontalDivider()
                Text("受限项（红方视角）", style = MaterialTheme.typography.labelMedium)
                RestrictedRow(
                    showName = showNameRed,
                    showCS = showCSRed,
                    showClass = showClassRed,
                    showType = showTypeRed,
                    showSide = showSideRed,
                    onName = { showNameRed = it },
                    onCS = { showCSRed = it },
                    onClass = { showClassRed = it },
                    onType = { showTypeRed = it },
                    onSide = { showSideRed = it }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sunk, onCheckedChange = { sunk = it })
                    Text("沉没")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // 反馈⑨：航向 clamp 0-360；航速不限上限（仅保留下限 0，避免负航速）
                unit.setCourse(course.toDouble().coerceIn(0.0, 360.0))
                unit.setSpeed(speed.toDouble().coerceAtLeast(0.0))
                xText.toLongOrNull()?.let { unit.x = it }    // R-P2：X/Y 应用
                yText.toLongOrNull()?.let { unit.y = it }
                if (unit.isAircraft() && alt.isNotBlank()) unit.altitude = alt.toInt()
                if ((unit.isSubmarine() || isSonobuoy) && depth.isNotBlank()) unit.depth = depth.toInt()
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
                // 受限项写回 Blue/Red 感知记录（仅当该单位对该方可见且有记录时）
                writePerception(unit, "Blue", visibleBlue, showNameBlue, showCSBlue, showClassBlue, showTypeBlue, showSideBlue)
                writePerception(unit, "Red", visibleRed, showNameRed, showCSRed, showClassRed, showTypeRed, showSideRed)
                onApply(unit)
                onDismiss()
            }) { Text("应用") }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = {
                    // R-P2：删除先弹三选确认（桌面 DeleteUnit：Remove/Show as Sunk/Cancel）
                    showDeleteConfirm = true
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                // R6：复制入口（桌面版 Copy Unit）
                androidx.compose.material3.TextButton(onClick = {
                    onDuplicate(unit); onDismiss()
                }) { Text("复制") }
                // 反馈⑨：编辑菜单加「取消」（不应用、不删除，仅关闭），顺序：删除、取消、应用
                androidx.compose.material3.TextButton(onClick = { onDismiss() }) { Text("取消") }
            }
        }
    )

    // 删除三选确认弹窗（桌面版 DeleteUnit 语义）
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除 TN ${unit.trackNumber} x ${unit.number}  (${unit.name})") },
            text = { Text("选择操作：") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete(unit); onDismiss()
                }) { Text("Remove") }
            },
            dismissButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        showDeleteConfirm = false
                        onShowAsSunk(unit); onDismiss()
                    }) { Text("Show as Sunk") }
                    androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            }
        )
    }
}

/**
 * 航向/航速数值格式化（去尾零，契约8）：217.0 → "217"，12.5 → "12.5"，0.0 → "0"，40.0 → "40"。
 * 纯函数（top-level），便于 JVM 单测。
 */
fun formatCourseSpeed(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** 「显示为类型」选项：真实类型（留空）→ 空串；其余值与 unitClass 简码一致（契约8）
 *  与 UnitRenderer.CWS_CLASS_CELLS 映射键集合对齐（含 CV 系列 / CG / CL / CA 别名） */
private val SHOW_TYPE_OPTIONS: List<Pair<String, String>> = listOf(
    "真实类型（留空）" to "",
    "BB" to "BB", "CG" to "CG", "CV" to "CV", "DD" to "DD", "FF" to "FF", "PC" to "PC",
    "LA" to "LA", "LC" to "LC", "LS" to "LS", "AR" to "AR", "AS" to "AS",
    "CL" to "CL", "CA" to "CA", "CC" to "CC"
)

/** 「显示为阵营」选项：真实阵营（留空）→ 空串 */
private val SHOW_SIDE_OPTIONS: List<Pair<String, String>> = listOf(
    "真实阵营（留空）" to "",
    "Blue" to "Blue", "Red" to "Red", "Neutral" to "Neutral", "Unknown" to "Unknown"
)

/**
 * 受限项一行：显示名称/航向航速/级别 三个复选框 + 显示为类型/阵营两个下拉。
 * 供蓝方/红方视角复用（契约8 红蓝双视角）。
 */
@Composable
private fun RestrictedRow(
    showName: Boolean,
    showCS: Boolean,
    showClass: Boolean,
    showType: String,
    showSide: String,
    onName: (Boolean) -> kotlin.Unit,
    onCS: (Boolean) -> kotlin.Unit,
    onClass: (Boolean) -> kotlin.Unit,
    onType: (String) -> kotlin.Unit,
    onSide: (String) -> kotlin.Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = showName, onCheckedChange = onName)
        Text("显示名称")
        Checkbox(checked = showCS, onCheckedChange = onCS)
        Text("显示航向航速")
        Checkbox(checked = showClass, onCheckedChange = onClass)
        Text("显示级别")
    }
    ShowAsDropdown(
        label = "显示为类型",
        options = SHOW_TYPE_OPTIONS,
        selected = showType,
        onSelect = onType,
        modifier = Modifier.fillMaxWidth()
    )
    ShowAsDropdown(
        label = "显示为阵营",
        options = SHOW_SIDE_OPTIONS,
        selected = showSide,
        onSelect = onSide,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 写回感知记录（指定阵营）：记录存在且该方可见时更新受限项 */
private fun writePerception(
    unit: Unit,
    side: String,
    visible: Boolean,
    showName: Boolean,
    showCS: Boolean,
    showClass: Boolean,
    showType: String,
    showSide: String
) {
    val per = unit.perceptionArray?.firstOrNull { it.seenBySide == side }
    if (per != null && visible) {
        per.showName = showName
        per.showCourseSpeed = showCS
        per.showClass = showClass
        per.showAsType = showType
        per.showAsSide = showSide
    }
}

/**
 * 「显示为类型/阵营」只读下拉（契约8：受限项原为文本输入框，应为选项）。
 * ExposedDropdownMenuBox + menuAnchor(MenuAnchorType.PrimaryNotEditable)：
 * material3 1.3.0（BOM 2024.09.03）API，MenuAnchorType 为新版必传参数。
 * 选中「真实类型/阵营」写回空串（=真实，与现语义一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)   // ExposedDropdownMenuBox/menuAnchor 在 material3 1.x 为实验 API
@Composable
private fun ShowAsDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> kotlin.Unit,   // 全限定：本文件 Unit 被 data.model.Unit 遮蔽
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.second == selected }?.first ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
