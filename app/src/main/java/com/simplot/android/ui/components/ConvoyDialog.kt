package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.simplot.android.domain.engine.ConvoyEngine

/**
 * 护航队创建对话框（P2 恢复，桌面版 WindowConvoy）。
 *
 * 参数（G03 补全桌面 WindowConvoy 六字段）：
 * - 指挥舰名 + 商船数量/环绕距离（码）：环绕布局（COMMODORE 居中，Merchant 环绕）
 * - 航向/速度：护航队统一航向航速（对指挥舰与全部商船生效）
 * - 列数/行数/列间距/行间距：列、行均 >0 时启用网格布局（商船数 = 列×行，网格居中于指挥舰）
 * 创建契约 = [ConvoyEngine.ConvoySpec]（引擎纯 Kotlin 可单测）。
 */
@Composable
fun ConvoyDialog(
    onDismiss: () -> kotlin.Unit,
    onCreate: (ConvoyEngine.ConvoySpec) -> kotlin.Unit
) {
    var commodoreName by remember { mutableStateOf("COMMODORE") }
    var escortText by remember { mutableStateOf("6") }
    var distText by remember { mutableStateOf("2000") }
    // G03：桌面 WindowConvoy TextCourse/TextSpeed/TextNumCols/TextNumRows/TextSpaceCols/TextSpaceRows
    var courseText by remember { mutableStateOf("0") }
    var speedText by remember { mutableStateOf("10") }
    var colsText by remember { mutableStateOf("3") }
    var rowsText by remember { mutableStateOf("2") }
    var spaceColsText by remember { mutableStateOf("500") }
    var spaceRowsText by remember { mutableStateOf("500") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建护航队") },
        text = {
            // 字段多，内容超屏可滚动（与 UnitEditSheet 同模式）
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = commodoreName, onValueChange = { commodoreName = it },
                    label = { Text("指挥舰名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = escortText, onValueChange = { escortText = it.filter { c -> c.isDigit() } },
                    label = { Text("商船数量（环绕模式）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = distText, onValueChange = { distText = it.filter { c -> c.isDigit() } },
                    label = { Text("环绕距离（码）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = courseText,
                        onValueChange = { courseText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("航向（度）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = speedText,
                        onValueChange = { speedText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("航速（节）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = colsText,
                        onValueChange = { colsText = it.filter { c -> c.isDigit() } },
                        label = { Text("列数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter { c -> c.isDigit() } },
                        label = { Text("行数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = spaceColsText,
                        onValueChange = { spaceColsText = it.filter { c -> c.isDigit() } },
                        label = { Text("列间距（码）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = spaceRowsText,
                        onValueChange = { spaceRowsText = it.filter { c -> c.isDigit() } },
                        label = { Text("行间距（码）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "列数、行数均 >0 时使用网格布局（商船数 = 列×行，网格居中于指挥舰）；" +
                        "任一为 0 时使用环绕布局（蓝方 Merchant 环绕指挥舰，角度均匀分布）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(
                    ConvoyEngine.ConvoySpec(
                        commodoreName = commodoreName.ifBlank { "COMMODORE" },
                        escortCount = (escortText.toIntOrNull() ?: 6).coerceIn(1, 24),
                        distYards = (distText.toIntOrNull() ?: 2000).coerceAtLeast(100),
                        courseDeg = (courseText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 360.0),
                        speedKnots = (speedText.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                        numCols = colsText.toIntOrNull() ?: 0,
                        numRows = rowsText.toIntOrNull() ?: 0,
                        spaceColsYards = (spaceColsText.toIntOrNull() ?: 0).coerceAtLeast(0),
                        spaceRowsYards = (spaceRowsText.toIntOrNull() ?: 0).coerceAtLeast(0)
                    )
                )
                onDismiss()
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
