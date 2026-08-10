package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 编队管理对话框（R6，对应桌面版 WindowFormation）。
 *
 * 列出场景所有编队，提供：移动准备（DoPrepare）/ 撤销（DoCancel）。
 */
@Composable
fun FormationDialog(
    formationNames: List<String>,
    onDismiss: () -> kotlin.Unit,
    onPrepare: (String) -> kotlin.Unit,
    onCancel: (String) -> kotlin.Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编队管理") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (formationNames.isEmpty()) {
                    Text("场景中无编队（可用「护航队」创建）", style = MaterialTheme.typography.bodyMedium)
                }
                formationNames.forEach { name ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 8.dp))
                        OutlinedButton(onClick = { onPrepare(name) }) { Text("准备") }
                        OutlinedButton(onClick = { onCancel(name) }) { Text("撤销") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {}
    )
}
