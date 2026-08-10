package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 护航队创建对话框（P2 恢复，桌面版 WindowConvoy）。
 *
 * 参数：指挥舰名 + 商船数量 + 编队距离（码）。
 * 生成：COMMODORE 居中 + 环绕 Merchant（均匀分布，桌面版 CreateConvoy 逻辑）。
 */
@Composable
fun ConvoyDialog(
    onDismiss: () -> kotlin.Unit,
    onCreate: (commodoreName: String, escortCount: Int, distYards: Int) -> kotlin.Unit
) {
    var commodoreName by remember { mutableStateOf("COMMODORE") }
    var escortText by remember { mutableStateOf("6") }
    var distText by remember { mutableStateOf("2000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建护航队") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = commodoreName, onValueChange = { commodoreName = it },
                    label = { Text("指挥舰名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = escortText, onValueChange = { escortText = it.filter { c -> c.isDigit() } },
                    label = { Text("商船数量（环绕）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = distText, onValueChange = { distText = it.filter { c -> c.isDigit() } },
                    label = { Text("编队距离（码）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("蓝方 Merchant 商船环绕指挥舰，角度均匀分布。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(
                    commodoreName.ifBlank { "COMMODORE" },
                    (escortText.toIntOrNull() ?: 6).coerceIn(1, 24),
                    (distText.toIntOrNull() ?: 2000).coerceAtLeast(100)
                )
                onDismiss()
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
