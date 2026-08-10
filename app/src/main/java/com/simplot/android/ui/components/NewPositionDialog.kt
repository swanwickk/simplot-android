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
import com.simplot.android.data.model.Unit

/**
 * 新位置计算器（P2 恢复，桌面版 ContainerNewPosition.PushCalcPosition）。
 *
 * 选参考单位 + 输入方位角（0=北）+ 距离（海里）→ 计算新坐标（显示 + toast）。
 * 公式：newPos = ref + 距离 × (Sin 方位, Cos 方位)（CalcEngine.newPosition）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPositionDialog(
    units: List<Unit>,
    onDismiss: () -> kotlin.Unit,
    onCalc: (refId: String, bearingDeg: Double, distNm: Double) -> kotlin.Unit
) {
    var refId by remember { mutableStateOf(units.firstOrNull()?.idNum ?: "") }
    var refExpanded by remember { mutableStateOf(false) }
    var bearingText by remember { mutableStateOf("") }
    var distText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新位置计算") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // 参考单位下拉
                ExposedDropdownMenuBox(expanded = refExpanded, onExpandedChange = { refExpanded = it }) {
                    val refName = units.firstOrNull { it.idNum == refId }?.let { "${it.name} (${it.side}) TN ${it.trackNumber}" } ?: "请选择"
                    OutlinedTextField(
                        value = refName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("参考单位") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = refExpanded) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = refExpanded, onDismissRequest = { refExpanded = false }) {
                        units.take(50).forEach { u ->
                            DropdownMenuItem(
                                text = { Text("${u.name} (${u.side}) TN ${u.trackNumber}") },
                                onClick = { refId = u.idNum; refExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(value = bearingText, onValueChange = { bearingText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("方位角（度，0=北）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = distText, onValueChange = { distText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("距离（海里）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("新位置 = 参考单位 + 距离 × (Sin 方位, Cos 方位)", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val bearing = bearingText.toDoubleOrNull()
                val dist = distText.toDoubleOrNull()
                if (refId.isEmpty()) { /* noop */ }
                if (bearing == null) { return@Button }
                if (dist == null) { return@Button }
                onCalc(refId, bearing, dist)
                onDismiss()
            }) { Text("计算") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
