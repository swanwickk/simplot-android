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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import com.simplot.android.domain.registry.UnitTypeRegistry

/**
 * 新建单位对话框（P1，对应桌面版各类型 NewUnit 窗口入口）。
 *
 * 用 UnitTypeRegistry 提供：Domain 大类选择 → 该 Domain 的子类型菜单（桌面版 Fill*Types）。
 * 阵营/名称/类/坐标可填；X/Y 默认 0（场景原点），可后续编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewUnitDialog(
    onDismiss: () -> kotlin.Unit,
    onCreate: (domain: UnitTypeRegistry.Domain, name: String, unitType: String, unitClass: String, side: String, x: Long, y: Long) -> kotlin.Unit,
    // 问题2修复：默认坐标 = 当前视野中心（新建单位不再落在 (0,0) 视野外）
    defaultX: Long = 0,
    defaultY: Long = 0
) {
    var domain by remember { mutableStateOf(UnitTypeRegistry.Domain.SURFACE) }
    var domainExpanded by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(UnitTypeRegistry.SURFACE_TYPES.first()) }
    var typeExpanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var unitClass by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("Blue") }
    var sideExpanded by remember { mutableStateOf(false) }
    var xText by remember { mutableStateOf(defaultX.toString()) }
    var yText by remember { mutableStateOf(defaultY.toString()) }

    val domainOptions = UnitTypeRegistry.Domain.entries.filter { it != UnitTypeRegistry.Domain.UNKNOWN }
    val typeOptions = UnitTypeRegistry.typesOf(domain)
    val sideOptions = listOf("Blue", "Red", "Neutral", "Unknown")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建单位") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Domain 大类下拉
                LabeledDropdown(
                    label = "类型大类",
                    options = domainOptions.map { it.label },
                    selected = domain.label,
                    onSelect = { idx -> domain = domainOptions[idx]; type = UnitTypeRegistry.typesOf(domain).firstOrNull() ?: "" }
                )
                // 子类型下拉（桌面版 Fill*Types）
                if (typeOptions.isNotEmpty()) {
                    LabeledDropdown(
                        label = "子类型",
                        options = typeOptions,
                        selected = type,
                        onSelect = { idx -> type = typeOptions[idx] }
                    )
                }
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = unitClass, onValueChange = { unitClass = it },
                    label = { Text("类型简码（如 DD/CL/CV，可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // 阵营下拉
                LabeledDropdown(
                    label = "阵营",
                    options = sideOptions,
                    selected = side,
                    onSelect = { idx -> side = sideOptions[idx] }
                )
                Row {
                    OutlinedTextField(value = xText, onValueChange = { xText = it },
                        label = { Text("X") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).padding(end = 4.dp))
                    OutlinedTextField(value = yText, onValueChange = { yText = it },
                        label = { Text("Y") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(
                    domain,
                    name.ifBlank { type.ifBlank { "新单位" } },
                    type,
                    unitClass,
                    side,
                    xText.toLongOrNull() ?: 0L,
                    yText.toLongOrNull() ?: 0L
                )
                onDismiss()
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 通用只读下拉（label + options + selected + onSelect index） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (Int) -> kotlin.Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(i); expanded = false })
            }
        }
    }
}
